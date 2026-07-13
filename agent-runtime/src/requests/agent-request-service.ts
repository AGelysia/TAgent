import { randomUUID } from "node:crypto";

import type { RuntimeConfig } from "../config/runtime-config.js";
import { type ModuleId, ModuleRegistry } from "../modules/module-manifest.js";
import {
  ModelGenerationError,
  type ModelGenerationContinuation,
  type ModelGenerationFailureCode,
  type ModelGenerationResult,
  type ModelProvider,
  type ModelToolOutput,
} from "../providers/model-provider.js";
import { buildContextWindow } from "../sessions/context-window.js";
import {
  ConversationOwnershipError,
  DisabledConversationRepository,
  type ConversationOwner,
  type ConversationRepository,
} from "../storage/conversation-repository.js";
import { type CoreToolDescriptor, ToolRegistry } from "../tools/tool-registry.js";
import type { ToolCallPayload, ToolResultPayload } from "../tools/tool-types.js";
import { RequestAdmissionController, type RequestAdmissionRejection } from "./request-admission.js";

const MAXIMUM_MODEL_OUTPUT_TOKENS = 1024;

export interface AgentRequestInput {
  readonly requestId: string;
  readonly playerUuid: string;
  readonly sessionId: string | null;
  readonly module: ModuleId;
  readonly message: string;
}

export interface AgentCompletionPayload {
  readonly sessionId: string | null;
  readonly playerUuid: string;
  readonly fallbackText: string;
  readonly structuredViews: readonly StructuredTextView[];
}

export interface StructuredTextView {
  readonly viewSchemaVersion: "1.0";
  readonly viewId: string;
  readonly requestId: string;
  readonly viewType: "text";
  readonly revision: 1;
  readonly title: string;
  readonly fallbackText: string;
  readonly pinnable: true;
  readonly content: {
    readonly text: string;
  };
}

export type AgentErrorCode =
  | "MODEL_TIMEOUT"
  | "MODEL_UNAVAILABLE"
  | "MODEL_AUTHENTICATION_FAILED"
  | "MODEL_RESPONSE_INVALID"
  | "REQUEST_CANCELLED"
  | "REQUEST_LIMITED"
  | "SESSION_NOT_FOUND"
  | "CONVERSATION_STORAGE_DISABLED"
  | "TOOL_REJECTED"
  | "TOOL_ROUND_LIMIT"
  | "RUNTIME_INTERNAL_ERROR";

export interface AgentErrorPayload {
  readonly playerUuid: string;
  readonly code: AgentErrorCode;
  readonly fallbackText: string;
  readonly retryable: boolean;
}

export type AgentTerminalResponse =
  | { readonly type: "agent.complete"; readonly payload: AgentCompletionPayload }
  | { readonly type: "agent.error"; readonly payload: AgentErrorPayload };

export type AgentRuntimeResponse =
  | AgentTerminalResponse
  | { readonly type: "tool.call"; readonly payload: ToolCallPayload };

export interface SessionResumeInput {
  readonly requestId: string;
  readonly playerUuid: string;
  readonly sessionId: string | null;
}

export interface SessionResumedPayload {
  readonly playerUuid: string;
  readonly sessionId: string;
}

export type SessionResumeResponse =
  | { readonly type: "session.resumed"; readonly payload: SessionResumedPayload }
  | { readonly type: "agent.error"; readonly payload: AgentErrorPayload };

export interface AgentRequestServiceOptions {
  readonly provider: ModelProvider;
  readonly config: RuntimeConfig;
  readonly timeoutMilliseconds?: number;
  readonly now?: () => number;
  readonly randomUuid?: () => string;
  readonly conversations?: ConversationRepository;
  readonly modules?: ModuleRegistry;
  readonly tools: ToolRegistry;
}

interface PendingToolCall {
  readonly payload: ToolCallPayload;
  readonly descriptor: CoreToolDescriptor;
  readonly resolve: (payload: ToolResultPayload) => void;
  readonly reject: (reason: unknown) => void;
  readonly removeAbortListener: () => void;
}

interface RequestRecord {
  readonly input: AgentRequestInput;
  readonly respond: (response: AgentRuntimeResponse) => void;
  readonly controller: AbortController;
  phase: "QUEUED" | "ACTIVE" | "WAITING_TOOL";
  timeout: NodeJS.Timeout | undefined;
  terminalSent: boolean;
  suppressResponse: boolean;
  detached: boolean;
  preparedSessionId: string | null;
  executionSessionId: string | null;
  createsSession: boolean;
  pendingTool: PendingToolCall | undefined;
  readonly issuedToolCallIds: Set<string>;
}

class ToolLoopError extends Error {
  public readonly code: "TOOL_REJECTED" | "TOOL_ROUND_LIMIT";

  public constructor(code: "TOOL_REJECTED" | "TOOL_ROUND_LIMIT") {
    super(code);
    this.name = "ToolLoopError";
    this.code = code;
  }
}

function limitedError(
  playerUuid: string,
  reason: RequestAdmissionRejection,
): AgentTerminalResponse {
  const fallbackText =
    reason === "PLAYER_BUSY"
      ? "You already have an AI request in progress."
      : "Too many AI requests. Try again shortly.";
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code: "REQUEST_LIMITED",
      fallbackText,
      retryable: true,
    },
  };
}

function providerError(
  playerUuid: string,
  code: ModelGenerationFailureCode,
): AgentTerminalResponse {
  switch (code) {
    case "MODEL_AUTHENTICATION_FAILED":
      return {
        type: "agent.error",
        payload: {
          playerUuid,
          code: "MODEL_AUTHENTICATION_FAILED",
          fallbackText: "The AI model is unavailable. Ask an administrator to check it.",
          retryable: false,
        },
      };
    case "MODEL_UNAVAILABLE":
      return {
        type: "agent.error",
        payload: {
          playerUuid,
          code: "MODEL_UNAVAILABLE",
          fallbackText: "The AI model is unavailable. Try again later.",
          retryable: true,
        },
      };
    case "MODEL_RATE_LIMITED":
    case "PROVIDER_UNAVAILABLE":
      return {
        type: "agent.error",
        payload: {
          playerUuid,
          code: "MODEL_UNAVAILABLE",
          fallbackText: "The AI model is temporarily unavailable. Try again later.",
          retryable: true,
        },
      };
    case "MODEL_RESPONSE_INVALID":
      return {
        type: "agent.error",
        payload: {
          playerUuid,
          code: "MODEL_RESPONSE_INVALID",
          fallbackText: "The AI returned an unusable response. Try again.",
          retryable: true,
        },
      };
  }
}

function isUsableFallbackText(value: unknown): value is string {
  if (typeof value !== "string" || value.trim().length === 0) {
    return false;
  }
  let codePoints = 0;
  for (const character of value) {
    codePoints += 1;
    if (codePoints > 8192) {
      return false;
    }
    const codePoint = character.codePointAt(0);
    if (
      codePoint === undefined ||
      (codePoint >= 0xd800 && codePoint <= 0xdfff) ||
      ((codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) &&
        codePoint !== 0x09 &&
        codePoint !== 0x0a) ||
      codePoint === 0x061c ||
      codePoint === 0x200e ||
      codePoint === 0x200f ||
      (codePoint >= 0x202a && codePoint <= 0x202e) ||
      (codePoint >= 0x2066 && codePoint <= 0x2069)
    ) {
      return false;
    }
  }
  return true;
}

function sessionError(
  playerUuid: string,
  code: "SESSION_NOT_FOUND" | "CONVERSATION_STORAGE_DISABLED",
): { readonly type: "agent.error"; readonly payload: AgentErrorPayload } {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code,
      fallbackText:
        code === "SESSION_NOT_FOUND"
          ? "That conversation is not available."
          : "Conversation history is disabled on this server.",
      retryable: false,
    },
  };
}

function internalError(playerUuid: string): {
  readonly type: "agent.error";
  readonly payload: AgentErrorPayload;
} {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code: "RUNTIME_INTERNAL_ERROR",
      fallbackText: "The AI request failed. Try again later.",
      retryable: true,
    },
  };
}

function toolLoopError(
  playerUuid: string,
  code: "TOOL_REJECTED" | "TOOL_ROUND_LIMIT",
): { readonly type: "agent.error"; readonly payload: AgentErrorPayload } {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code,
      fallbackText:
        code === "TOOL_REJECTED"
          ? "The requested server lookup was not allowed."
          : "The AI used too many server lookups. Try a more specific question.",
      retryable: code === "TOOL_ROUND_LIMIT",
    },
  };
}

export class AgentRequestService {
  readonly #provider: ModelProvider;
  readonly #config: RuntimeConfig;
  readonly #timeoutMilliseconds: number;
  readonly #admission: RequestAdmissionController;
  readonly #now: () => number;
  readonly #randomUuid: () => string;
  readonly #conversations: ConversationRepository;
  readonly #modules: ModuleRegistry;
  readonly #tools: ToolRegistry;
  readonly #requests = new Map<string, RequestRecord>();
  #closed = false;

  public constructor(options: AgentRequestServiceOptions) {
    const timeoutMilliseconds =
      options.timeoutMilliseconds ?? options.config.model.timeoutSeconds * 1000;
    if (!Number.isSafeInteger(timeoutMilliseconds) || timeoutMilliseconds < 1) {
      throw new TypeError("The Agent request timeout must be a positive bounded integer.");
    }
    this.#provider = options.provider;
    this.#config = options.config;
    this.#now = options.now ?? Date.now;
    this.#randomUuid = options.randomUuid ?? randomUUID;
    this.#conversations = options.conversations ?? new DisabledConversationRepository();
    this.#modules = options.modules ?? new ModuleRegistry();
    this.#tools = options.tools;
    this.#timeoutMilliseconds = timeoutMilliseconds;
    this.#admission = new RequestAdmissionController(
      {
        maximumConcurrent: options.config.limits.maxConcurrentRequests,
        maximumQueued: options.config.limits.maxQueuedRequests,
        perPlayerCooldownMilliseconds: options.config.limits.perPlayerCooldownSeconds * 1000,
        dailyRequestsPerPlayer: options.config.limits.dailyRequestsPerPlayer,
      },
      this.#now,
    );
  }

  public submit(input: AgentRequestInput, respond: (response: AgentRuntimeResponse) => void): void {
    if (this.#closed) {
      this.#safeRespond(respond, {
        type: "agent.error",
        payload: {
          playerUuid: input.playerUuid,
          code: "RUNTIME_INTERNAL_ERROR",
          fallbackText: "The AI Runtime is stopping.",
          retryable: true,
        },
      });
      return;
    }
    if (this.#requests.has(input.requestId)) {
      this.#safeRespond(respond, limitedError(input.playerUuid, "PLAYER_BUSY"));
      return;
    }

    const record: RequestRecord = {
      input,
      respond,
      controller: new AbortController(),
      phase: "QUEUED",
      timeout: undefined,
      terminalSent: false,
      suppressResponse: false,
      detached: false,
      preparedSessionId: null,
      executionSessionId: null,
      createsSession: false,
      pendingTool: undefined,
      issuedToolCallIds: new Set(),
    };
    this.#requests.set(input.requestId, record);
    const decision = this.#admission.admit({
      requestId: input.requestId,
      playerUuid: input.playerUuid,
      start: () => this.#start(record),
    });
    if (!decision.accepted) {
      this.#requests.delete(input.requestId);
      this.#safeRespond(respond, limitedError(input.playerUuid, decision.reason));
      return;
    }

    record.phase = decision.queued ? "QUEUED" : "ACTIVE";
    record.timeout = setTimeout(() => this.#timeout(record), this.#timeoutMilliseconds);
    record.timeout.unref();
  }

  public cancel(requestId: string, playerUuid: string): boolean {
    const record = this.#requests.get(requestId);
    if (record === undefined || record.input.playerUuid !== playerUuid) {
      return false;
    }
    record.suppressResponse = true;
    this.#detach(record);
    record.controller.abort(new Error("REQUEST_CANCELLED"));
    return true;
  }

  public acceptToolResult(
    requestId: string,
    payload: ToolResultPayload,
  ): "accepted" | "ignored" | "violation" {
    const record = this.#requests.get(requestId);
    if (record === undefined || record.terminalSent || record.suppressResponse) {
      return "ignored";
    }
    const pending = record.pendingTool;
    if (pending === undefined) {
      return "violation";
    }
    const expected = pending.payload;
    if (
      payload.toolCallId !== expected.toolCallId ||
      payload.sessionId !== expected.sessionId ||
      payload.playerUuid !== expected.playerUuid ||
      payload.tool !== expected.tool ||
      payload.sequence !== expected.sequence ||
      !this.#validToolResult(pending.descriptor, payload)
    ) {
      return "violation";
    }
    record.pendingTool = undefined;
    pending.removeAbortListener();
    pending.resolve(payload);
    return "accepted";
  }

  public cancelAll(): void {
    for (const record of [...this.#requests.values()]) {
      this.cancel(record.input.requestId, record.input.playerUuid);
    }
  }

  #validToolResult(descriptor: CoreToolDescriptor, payload: ToolResultPayload): boolean {
    if (payload.status === "succeeded") {
      return this.#tools.validateResult(descriptor, payload);
    }
    if (payload.result !== null || payload.error === null || payload.trust !== "authoritative") {
      return false;
    }
    return payload.status === "rejected"
      ? payload.source === "paper_policy"
      : payload.source === descriptor.source;
  }

  public resume(
    input: SessionResumeInput,
    respond: (response: SessionResumeResponse) => void,
  ): void {
    if (this.#closed) {
      this.#safeRespond(respond, internalError(input.playerUuid));
      return;
    }
    if (!this.#conversations.enabled) {
      this.#safeRespond(respond, sessionError(input.playerUuid, "CONVERSATION_STORAGE_DISABLED"));
      return;
    }
    try {
      const owner = this.#owner(input.playerUuid);
      const session =
        input.sessionId === null
          ? this.#conversations.findLatestOwned(owner)
          : this.#conversations.findOwned(input.sessionId, owner);
      this.#safeRespond(
        respond,
        session === undefined
          ? sessionError(input.playerUuid, "SESSION_NOT_FOUND")
          : {
              type: "session.resumed",
              payload: { playerUuid: input.playerUuid, sessionId: session.id },
            },
      );
    } catch {
      this.#safeRespond(respond, internalError(input.playerUuid));
    }
  }

  public close(): void {
    this.#closed = true;
    this.cancelAll();
  }

  public get activeCount(): number {
    return this.#admission.activeCount;
  }

  public get queuedCount(): number {
    return this.#admission.queuedCount;
  }

  #start(record: RequestRecord): void {
    record.phase = "ACTIVE";
    void this.#run(record)
      .catch((error: unknown) => {
        if (record.terminalSent || record.suppressResponse) {
          return;
        }
        record.terminalSent = true;
        this.#safeRespond(
          record.respond,
          error instanceof ModelGenerationError
            ? providerError(record.input.playerUuid, error.code)
            : error instanceof ConversationOwnershipError
              ? sessionError(record.input.playerUuid, "SESSION_NOT_FOUND")
              : error instanceof ToolLoopError
                ? toolLoopError(record.input.playerUuid, error.code)
                : internalError(record.input.playerUuid),
        );
      })
      .finally(() => {
        this.#detach(record);
      })
      .catch(() => undefined);
  }

  async #run(record: RequestRecord): Promise<void> {
    const manifest = this.#modules.get(record.input.module);
    const history = this.#prepareConversation(record);
    const input = buildContextWindow(history, record.input.message, {
      maximumMessages: this.#config.limits.maxContextMessages,
      maximumCharacters: this.#config.limits.maxContextCharacters,
    });
    const allowedTools = this.#tools.forAllowlist(manifest.toolAllowlist);
    const allowedToolIds = new Set(allowedTools.map((tool) => tool.id));
    let sequence = 0;
    let continuation: ModelGenerationContinuation | undefined;
    let toolOutput: ModelToolOutput | undefined;

    while (!record.controller.signal.aborted) {
      const toolsAvailable = sequence < this.#config.limits.maxToolRounds;
      let result: ModelGenerationResult;
      try {
        result = await this.#provider.generate({
          provider: this.#config.model.provider,
          model: this.#config.model.model,
          apiKey: this.#config.model.apiKey,
          instructions: manifest.instructions,
          input,
          tools: toolsAvailable ? allowedTools : [],
          ...(continuation === undefined ? {} : { continuation }),
          ...(toolOutput === undefined ? {} : { toolOutput }),
          maxOutputTokens: MAXIMUM_MODEL_OUTPUT_TOKENS,
          signal: record.controller.signal,
        });
      } catch (error) {
        if (
          !toolsAvailable &&
          error instanceof ModelGenerationError &&
          error.code === "MODEL_RESPONSE_INVALID"
        ) {
          throw new ToolLoopError("TOOL_ROUND_LIMIT");
        }
        throw error;
      }
      if (result.type === "final") {
        this.#complete(record, result.fallbackText);
        return;
      }
      if (!toolsAvailable) {
        throw new ToolLoopError("TOOL_ROUND_LIMIT");
      }
      const descriptor = this.#tools.byProviderName(result.providerName);
      if (
        descriptor === undefined ||
        !allowedToolIds.has(descriptor.id) ||
        !this.#tools.validateArguments(descriptor, result.arguments)
      ) {
        throw new ToolLoopError("TOOL_REJECTED");
      }
      const executionSessionId = record.executionSessionId;
      if (executionSessionId === null) {
        throw new Error("Tool execution session was not prepared.");
      }
      const payload: ToolCallPayload = {
        toolCallId: this.#allocateToolCallId(record),
        sessionId: executionSessionId,
        playerUuid: record.input.playerUuid,
        module: record.input.module,
        tool: descriptor.id,
        arguments: result.arguments,
        sequence,
      };
      const toolResult = await this.#awaitToolResult(record, descriptor, payload);
      if (toolResult.status === "rejected") {
        throw new ToolLoopError("TOOL_REJECTED");
      }
      const providerOutput = JSON.stringify({
        status: toolResult.status,
        source: toolResult.source,
        trust: toolResult.trust,
        result: toolResult.result,
        error: toolResult.error,
      });
      if (providerOutput.length > 64 * 1024) {
        throw new ToolLoopError("TOOL_REJECTED");
      }
      continuation = result.continuation;
      toolOutput = { providerCallId: result.providerCallId, output: providerOutput };
      sequence += 1;
    }
  }

  #complete(record: RequestRecord, fallbackText: string): void {
    if (record.terminalSent || record.suppressResponse) {
      return;
    }
    if (!isUsableFallbackText(fallbackText)) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    if (record.preparedSessionId !== null) {
      this.#conversations.commitExchange({
        ...this.#owner(record.input.playerUuid),
        sessionId: record.preparedSessionId,
        createSession: record.createsSession,
        requestId: record.input.requestId,
        module: record.input.module,
        userContent: record.input.message,
        assistantContent: fallbackText,
        createdAt: new Date(this.#now()).toISOString(),
      });
    }
    record.terminalSent = true;
    this.#safeRespond(record.respond, {
      type: "agent.complete",
      payload: {
        sessionId: record.preparedSessionId,
        playerUuid: record.input.playerUuid,
        fallbackText,
        structuredViews: [
          {
            viewSchemaVersion: "1.0",
            viewId: this.#randomUuid(),
            requestId: record.input.requestId,
            viewType: "text",
            revision: 1,
            title: "Agent response",
            fallbackText,
            pinnable: true,
            content: { text: fallbackText },
          },
        ],
      },
    });
  }

  #awaitToolResult(
    record: RequestRecord,
    descriptor: CoreToolDescriptor,
    payload: ToolCallPayload,
  ): Promise<ToolResultPayload> {
    return new Promise<ToolResultPayload>((resolve, reject) => {
      const onAbort = (): void => reject(record.controller.signal.reason);
      const pending: PendingToolCall = {
        payload,
        descriptor,
        resolve,
        reject,
        removeAbortListener: () => record.controller.signal.removeEventListener("abort", onAbort),
      };
      record.pendingTool = pending;
      record.phase = "WAITING_TOOL";
      record.controller.signal.addEventListener("abort", onAbort, { once: true });
      if (record.controller.signal.aborted) {
        onAbort();
        return;
      }
      this.#safeRespond(record.respond, { type: "tool.call", payload });
    }).finally(() => {
      record.phase = "ACTIVE";
    });
  }

  #owner(playerUuid: string): ConversationOwner {
    return { serverId: this.#config.server.id, playerUuid };
  }

  #allocateToolCallId(record: RequestRecord): string {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const candidate = this.#randomUuid();
      if (
        candidate !== record.input.requestId &&
        candidate !== record.executionSessionId &&
        !record.issuedToolCallIds.has(candidate)
      ) {
        record.issuedToolCallIds.add(candidate);
        return candidate;
      }
    }
    throw new Error("Unable to allocate a unique Tool call ID.");
  }

  #prepareConversation(record: RequestRecord) {
    if (!this.#conversations.enabled) {
      if (record.input.sessionId !== null) {
        throw new ConversationOwnershipError();
      }
      record.executionSessionId = this.#randomUuid();
      return [];
    }

    const owner = this.#owner(record.input.playerUuid);
    if (record.input.sessionId === null) {
      record.preparedSessionId = this.#randomUuid();
      record.executionSessionId = record.preparedSessionId;
      record.createsSession = true;
      return [];
    }
    const session = this.#conversations.findOwned(record.input.sessionId, owner);
    if (session === undefined) {
      throw new ConversationOwnershipError();
    }
    record.preparedSessionId = session.id;
    record.executionSessionId = session.id;
    record.createsSession = false;
    const maximumHistory = Math.max(
      0,
      Math.floor((this.#config.limits.maxContextMessages - 1) / 2) * 2,
    );
    return this.#conversations.loadRecentOwned(session.id, owner, maximumHistory);
  }

  #timeout(record: RequestRecord): void {
    if (record.terminalSent || record.suppressResponse) {
      return;
    }
    record.terminalSent = true;
    this.#detach(record);
    record.controller.abort(new Error("MODEL_TIMEOUT"));
    this.#safeRespond(record.respond, {
      type: "agent.error",
      payload: {
        playerUuid: record.input.playerUuid,
        code: "MODEL_TIMEOUT",
        fallbackText: "The AI request timed out. Try again.",
        retryable: true,
      },
    });
  }

  #clearTimeout(record: RequestRecord): void {
    if (record.timeout !== undefined) {
      clearTimeout(record.timeout);
      record.timeout = undefined;
    }
  }

  #detach(record: RequestRecord): void {
    if (record.detached) {
      return;
    }
    record.detached = true;
    const pending = record.pendingTool;
    if (pending !== undefined) {
      record.pendingTool = undefined;
      pending.removeAbortListener();
      pending.reject(new Error("REQUEST_DETACHED"));
    }
    this.#clearTimeout(record);
    if (this.#requests.get(record.input.requestId) === record) {
      this.#requests.delete(record.input.requestId);
    }
    if (record.phase === "QUEUED") {
      this.#admission.cancelQueued(record.input.requestId, record.input.playerUuid);
    } else {
      this.#admission.releaseActive(record.input.requestId);
    }
  }

  #safeRespond<Response>(responder: (response: Response) => void, response: Response): void {
    try {
      responder(response);
    } catch {
      // A failed transport must not leak a rejection or retain admission state.
    }
  }
}
