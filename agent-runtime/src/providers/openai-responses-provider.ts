import { TextDecoder } from "node:util";

import { z } from "zod";

import type {
  ModelProviderHealthRequest,
  ModelProviderHealthResult,
} from "../health/model-provider.js";
import { parseStrictJson } from "../transport/strict-json.js";
import {
  ModelGenerationError,
  type ModelGenerationRequest,
  type ModelGenerationResult,
  type ModelProvider,
} from "./model-provider.js";

const OPENAI_API_ROOT = "https://api.openai.com/v1";
const MAXIMUM_PROVIDER_RESPONSE_BYTES = 1024 * 1024;
const MAXIMUM_FALLBACK_TEXT_LENGTH = 8192;
const MAXIMUM_TOOL_ARGUMENT_CHARACTERS = 16 * 1024;
const PROVIDER_TOOL_NAME = /^[A-Za-z0-9_-]{1,64}$/u;

type FetchImplementation = (
  input: string | URL | globalThis.Request,
  init?: RequestInit,
) => Promise<Response>;

export interface OpenAiResponsesProviderOptions {
  readonly fetch?: FetchImplementation;
}

const healthResponseSchema = z
  .object({
    id: z.string().min(1).max(256),
  })
  .loose();

const assistantMessageSchema = z
  .object({
    id: z.string().min(1).max(256).optional(),
    type: z.literal("message"),
    role: z.literal("assistant"),
    content: z
      .array(
        z
          .object({
            type: z.string().min(1).max(64),
            text: z.string().max(8192).optional(),
          })
          .loose(),
      )
      .max(64),
  })
  .loose();

const functionCallSchema = z
  .object({
    id: z.string().min(1).max(256).optional(),
    type: z.literal("function_call"),
    call_id: z.string().min(1).max(256),
    name: z.string().regex(PROVIDER_TOOL_NAME),
    arguments: z.string().max(MAXIMUM_TOOL_ARGUMENT_CHARACTERS),
    status: z.enum(["in_progress", "completed", "incomplete"]).optional(),
  })
  .loose();

const reasoningItemSchema = z
  .object({
    id: z.string().min(1).max(256),
    type: z.literal("reasoning"),
    encrypted_content: z.string().max(MAXIMUM_PROVIDER_RESPONSE_BYTES).optional(),
    summary: z
      .array(z.object({ type: z.literal("summary_text"), text: z.string().max(8192) }).strict())
      .max(16),
  })
  .loose();

const functionCallOutputSchema = z
  .object({
    type: z.literal("function_call_output"),
    call_id: z.string().min(1).max(256),
    output: z.string().min(1).max(MAXIMUM_PROVIDER_RESPONSE_BYTES),
  })
  .strict();

const continuationItemSchema = z.union([
  assistantMessageSchema,
  functionCallSchema,
  functionCallOutputSchema,
  reasoningItemSchema,
]);

const providerResponseSchema = z
  .object({
    output: z
      .array(z.union([assistantMessageSchema, functionCallSchema, reasoningItemSchema]))
      .max(64),
    usage: z
      .object({
        input_tokens: z.number().int().nonnegative(),
        output_tokens: z.number().int().nonnegative(),
      })
      .loose()
      .optional(),
  })
  .loose();

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function toolArguments(source: string): Readonly<Record<string, unknown>> {
  let parsed: unknown;
  try {
    parsed = parseStrictJson(source, { maximumDepth: 16, maximumTokens: 2048 });
  } catch {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  if (!isRecord(parsed)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return parsed;
}

function continuationItems(
  request: ModelGenerationRequest,
): readonly Readonly<Record<string, unknown>>[] {
  const continuation = request.continuation;
  const toolOutput = request.toolOutput;
  if ((continuation === undefined) !== (toolOutput === undefined)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  if (continuation === undefined || toolOutput === undefined) {
    return [];
  }
  if (continuation.provider !== "openai") {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }

  const parsedItems = continuation.items.map((item) => continuationItemSchema.safeParse(item));
  if (parsedItems.some((item) => !item.success)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  const calls = parsedItems
    .filter((item) => item.success && item.data.type === "function_call")
    .map((item) => item.data);
  const pending = calls.at(-1);
  if (pending?.call_id !== toolOutput.providerCallId) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return [
    ...continuation.items,
    {
      type: "function_call_output",
      call_id: toolOutput.providerCallId,
      output: toolOutput.output,
    },
  ];
}

function providerTools(request: ModelGenerationRequest): readonly Record<string, unknown>[] {
  const names = new Set<string>();
  return (request.tools ?? []).map((tool) => {
    if (!PROVIDER_TOOL_NAME.test(tool.providerName) || names.has(tool.providerName)) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    names.add(tool.providerName);
    return {
      type: "function",
      name: tool.providerName,
      description: tool.description,
      parameters: tool.parameters,
      strict: true,
    };
  });
}

function authorization(apiKey: string): string {
  return `Bearer ${apiKey}`;
}

async function discardBody(response: Response): Promise<void> {
  await response.body?.cancel().catch(() => undefined);
}

async function readBoundedJson(response: Response): Promise<unknown> {
  if (response.body === null) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }

  const declaredLength = response.headers.get("content-length");
  if (
    declaredLength !== null &&
    Number.isFinite(Number(declaredLength)) &&
    Number(declaredLength) > MAXIMUM_PROVIDER_RESPONSE_BYTES
  ) {
    await discardBody(response);
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let byteLength = 0;
  try {
    while (true) {
      const result = await reader.read();
      if (result.done) {
        break;
      }
      byteLength += result.value.byteLength;
      if (byteLength > MAXIMUM_PROVIDER_RESPONSE_BYTES) {
        await reader.cancel();
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      chunks.push(result.value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  let source: string;
  try {
    source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    return parseStrictJson(source);
  } catch (error) {
    if (error instanceof ModelGenerationError) {
      throw error;
    }
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
}

function healthFailure(status: number): ModelProviderHealthResult {
  if (status === 401 || status === 403) {
    return { ok: false, code: "PROVIDER_AUTH_FAILED" };
  }
  if (status === 404) {
    return { ok: false, code: "MODEL_UNAVAILABLE" };
  }
  if (status === 408 || status === 429 || status >= 500) {
    return { ok: false, code: "PROVIDER_UNAVAILABLE" };
  }
  return { ok: false, code: "MODEL_HEALTH_FAILED" };
}

function generationFailure(status: number): ModelGenerationError {
  if (status === 401 || status === 403) {
    return new ModelGenerationError("MODEL_AUTHENTICATION_FAILED", "NOT_BILLABLE");
  }
  if (status === 404) {
    return new ModelGenerationError("MODEL_UNAVAILABLE", "NOT_BILLABLE");
  }
  if (status === 429) {
    return new ModelGenerationError("MODEL_RATE_LIMITED", "NOT_BILLABLE");
  }
  if (status === 408 || status >= 500) {
    return new ModelGenerationError("PROVIDER_UNAVAILABLE", "NOT_BILLABLE");
  }
  return new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
}

function hasUnpairedSurrogate(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (index + 1 >= value.length || next < 0xdc00 || next > 0xdfff) {
        return true;
      }
      index += 1;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      return true;
    }
  }
  return false;
}

function boundedFallbackText(value: string): string {
  let text = value.trim();
  if (hasUnpairedSurrogate(text)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  if (text.length > MAXIMUM_FALLBACK_TEXT_LENGTH) {
    text = text.slice(0, MAXIMUM_FALLBACK_TEXT_LENGTH);
    const last = text.charCodeAt(text.length - 1);
    if (last >= 0xd800 && last <= 0xdbff) {
      text = text.slice(0, -1);
    }
    text = text.trimEnd();
  }
  if (text.length === 0) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return text;
}

export class OpenAiResponsesProvider implements ModelProvider {
  readonly #fetch: FetchImplementation;

  public constructor(options: OpenAiResponsesProviderOptions = {}) {
    this.#fetch = options.fetch ?? globalThis.fetch;
  }

  public async check(request: ModelProviderHealthRequest): Promise<ModelProviderHealthResult> {
    let response: Response;
    try {
      response = await this.#fetch(
        `${OPENAI_API_ROOT}/models/${encodeURIComponent(request.model)}`,
        {
          method: "GET",
          headers: { Authorization: authorization(request.apiKey) },
          redirect: "error",
          signal: request.signal,
        },
      );
    } catch {
      if (request.signal.aborted) {
        throw request.signal.reason;
      }
      return { ok: false, code: "PROVIDER_UNAVAILABLE" };
    }

    if (!response.ok) {
      await discardBody(response);
      return healthFailure(response.status);
    }

    try {
      const parsed = healthResponseSchema.safeParse(await readBoundedJson(response));
      return parsed.success && parsed.data.id === request.model
        ? { ok: true }
        : { ok: false, code: "MODEL_HEALTH_FAILED" };
    } catch {
      return { ok: false, code: "MODEL_HEALTH_FAILED" };
    }
  }

  public async generate(request: ModelGenerationRequest): Promise<ModelGenerationResult> {
    let response: Response;
    const priorItems = continuationItems(request);
    const tools = providerTools(request);
    try {
      response = await this.#fetch(`${OPENAI_API_ROOT}/responses`, {
        method: "POST",
        headers: {
          Authorization: authorization(request.apiKey),
          "Content-Type": "application/json",
        },
        redirect: "error",
        signal: request.signal,
        body: JSON.stringify({
          model: request.model,
          instructions: request.instructions,
          input: [...request.input, ...priorItems],
          max_output_tokens: request.maxOutputTokens,
          store: false,
          tools,
          tool_choice: tools.length === 0 ? "none" : "auto",
          parallel_tool_calls: false,
          include: ["reasoning.encrypted_content"],
        }),
      });
    } catch {
      if (request.signal.aborted) {
        throw request.signal.reason;
      }
      throw new ModelGenerationError("PROVIDER_UNAVAILABLE");
    }

    if (!response.ok) {
      await discardBody(response);
      throw generationFailure(response.status);
    }

    const parsed = providerResponseSchema.safeParse(await readBoundedJson(response));
    if (!parsed.success) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }

    const calls = parsed.data.output.filter((item) => item.type === "function_call");
    if (calls.length > 1 || (tools.length === 0 && calls.length !== 0)) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    const usage = parsed.data.usage;
    const generationUsage =
      usage === undefined
        ? {}
        : {
            usage: {
              inputTokens: usage.input_tokens,
              outputTokens: usage.output_tokens,
            },
          };
    const call = calls[0];
    if (call !== undefined) {
      if (call.status !== undefined && call.status !== "completed") {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      const allowedNames = new Set(tools.map((tool) => String(tool["name"])));
      if (!allowedNames.has(call.name)) {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      const currentContinuation = parsed.data.output.map((item) => {
        if (item.type === "function_call") {
          return {
            ...(item.id === undefined ? {} : { id: item.id }),
            type: item.type,
            call_id: item.call_id,
            name: item.name,
            arguments: item.arguments,
            ...(item.status === undefined ? {} : { status: item.status }),
          };
        }
        if (item.type === "reasoning") {
          return {
            id: item.id,
            type: item.type,
            summary: item.summary,
            ...(item.encrypted_content === undefined
              ? {}
              : { encrypted_content: item.encrypted_content }),
          };
        }
        return {
          ...(item.id === undefined ? {} : { id: item.id }),
          type: item.type,
          role: item.role,
          content: item.content.map((content) => ({
            type: content.type,
            ...(content.text === undefined ? {} : { text: content.text }),
          })),
        };
      });
      return {
        type: "tool_call",
        providerCallId: call.call_id,
        providerName: call.name,
        arguments: toolArguments(call.arguments),
        continuation: {
          provider: "openai",
          items: [...priorItems, ...currentContinuation],
        },
        ...generationUsage,
      };
    }

    const fallbackText = boundedFallbackText(
      parsed.data.output
        .flatMap((item) => (item.type === "message" ? item.content : []))
        .filter((content) => content.type === "output_text")
        .map((content) => content.text ?? "")
        .join(""),
    );
    return {
      type: "final",
      fallbackText,
      ...generationUsage,
    };
  }
}
