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

const providerResponseSchema = z
  .object({
    output: z.array(
      z
        .object({
          type: z.string(),
          role: z.string().optional(),
          content: z
            .array(
              z
                .object({
                  type: z.string(),
                  text: z.string().optional(),
                })
                .loose(),
            )
            .optional(),
        })
        .loose(),
    ),
    usage: z
      .object({
        input_tokens: z.number().int().nonnegative(),
        output_tokens: z.number().int().nonnegative(),
      })
      .loose()
      .optional(),
  })
  .loose();

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
    return new ModelGenerationError("MODEL_AUTHENTICATION_FAILED");
  }
  if (status === 404) {
    return new ModelGenerationError("MODEL_UNAVAILABLE");
  }
  if (status === 429) {
    return new ModelGenerationError("MODEL_RATE_LIMITED");
  }
  if (status === 408 || status >= 500) {
    return new ModelGenerationError("PROVIDER_UNAVAILABLE");
  }
  return new ModelGenerationError("MODEL_RESPONSE_INVALID");
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
          instructions:
            "Answer the player's Minecraft question as concise plain text. Do not claim to have changed the server or player state.",
          input: request.input,
          max_output_tokens: request.maxOutputTokens,
          store: false,
          tools: [],
          tool_choice: "none",
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

    const fallbackText = boundedFallbackText(
      parsed.data.output
        .filter((item) => item.type === "message" && item.role === "assistant")
        .flatMap((item) => item.content ?? [])
        .filter((content) => content.type === "output_text")
        .map((content) => content.text ?? "")
        .join(""),
    );
    const usage = parsed.data.usage;
    return {
      fallbackText,
      ...(usage === undefined
        ? {}
        : {
            usage: {
              inputTokens: usage.input_tokens,
              outputTokens: usage.output_tokens,
            },
          }),
    };
  }
}
