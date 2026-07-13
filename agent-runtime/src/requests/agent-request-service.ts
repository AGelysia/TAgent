import { randomUUID } from "node:crypto";

import type { RuntimeConfig } from "../config/runtime-config.js";
import { type ModuleId, ModuleRegistry } from "../modules/module-manifest.js";
import {
  ModelGenerationError,
  type ModelGenerationFailureCode,
  type ModelProvider,
} from "../providers/model-provider.js";
import { buildContextWindow } from "../sessions/context-window.js";
import {
  ConversationOwnershipError,
  DisabledConversationRepository,
  type ConversationOwner,
  type ConversationRepository,
} from "../storage/conversation-repository.js";
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
  readonly structuredViews: readonly [];
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
}

interface RequestRecord {
  readonly input: AgentRequestInput;
  readonly respond: (response: AgentTerminalResponse) => void;
  readonly controller: AbortController;
  phase: "QUEUED" | "ACTIVE";
  timeout: NodeJS.Timeout | undefined;
  terminalSent: boolean;
  suppressResponse: boolean;
  detached: boolean;
  preparedSessionId: string | null;
  createsSession: boolean;
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
  if (typeof value !== "string" || value.trim().length === 0 || value.length > 8192) {
    return false;
  }
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (index + 1 >= value.length || next < 0xdc00 || next > 0xdfff) {
        return false;
      }
      index += 1;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
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

export class AgentRequestService {
  readonly #provider: ModelProvider;
  readonly #config: RuntimeConfig;
  readonly #timeoutMilliseconds: number;
  readonly #admission: RequestAdmissionController;
  readonly #now: () => number;
  readonly #randomUuid: () => string;
  readonly #conversations: ConversationRepository;
  readonly #modules: ModuleRegistry;
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

  public submit(
    input: AgentRequestInput,
    respond: (response: AgentTerminalResponse) => void,
  ): void {
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
      createsSession: false,
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

  public cancelAll(): void {
    for (const record of [...this.#requests.values()]) {
      this.cancel(record.input.requestId, record.input.playerUuid);
    }
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
    void Promise.resolve()
      .then(() => {
        const manifest = this.#modules.get(record.input.module);
        const history = this.#prepareConversation(record);
        return this.#provider.generate({
          provider: this.#config.model.provider,
          model: this.#config.model.model,
          apiKey: this.#config.model.apiKey,
          instructions: manifest.instructions,
          input: buildContextWindow(history, record.input.message, {
            maximumMessages: this.#config.limits.maxContextMessages,
            maximumCharacters: this.#config.limits.maxContextCharacters,
          }),
          maxOutputTokens: MAXIMUM_MODEL_OUTPUT_TOKENS,
          signal: record.controller.signal,
        });
      })
      .then(
        (result) => {
          if (!record.terminalSent && !record.suppressResponse) {
            if (!isUsableFallbackText(result.fallbackText)) {
              record.terminalSent = true;
              this.#safeRespond(
                record.respond,
                providerError(record.input.playerUuid, "MODEL_RESPONSE_INVALID"),
              );
              return;
            }
            try {
              if (record.preparedSessionId !== null) {
                this.#conversations.commitExchange({
                  ...this.#owner(record.input.playerUuid),
                  sessionId: record.preparedSessionId,
                  createSession: record.createsSession,
                  requestId: record.input.requestId,
                  module: record.input.module,
                  userContent: record.input.message,
                  assistantContent: result.fallbackText,
                  createdAt: new Date(this.#now()).toISOString(),
                });
              }
              record.terminalSent = true;
              this.#safeRespond(record.respond, {
                type: "agent.complete",
                payload: {
                  sessionId: record.preparedSessionId,
                  playerUuid: record.input.playerUuid,
                  fallbackText: result.fallbackText,
                  structuredViews: [],
                },
              });
            } catch (error) {
              record.terminalSent = true;
              this.#safeRespond(
                record.respond,
                error instanceof ConversationOwnershipError
                  ? sessionError(record.input.playerUuid, "SESSION_NOT_FOUND")
                  : internalError(record.input.playerUuid),
              );
            }
          }
        },
        (error: unknown) => {
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
                : internalError(record.input.playerUuid),
          );
        },
      )
      .finally(() => {
        this.#detach(record);
      })
      .catch(() => undefined);
  }

  #owner(playerUuid: string): ConversationOwner {
    return { serverId: this.#config.server.id, playerUuid };
  }

  #prepareConversation(record: RequestRecord) {
    if (!this.#conversations.enabled) {
      if (record.input.sessionId !== null) {
        throw new ConversationOwnershipError();
      }
      return [];
    }

    const owner = this.#owner(record.input.playerUuid);
    if (record.input.sessionId === null) {
      record.preparedSessionId = this.#randomUuid();
      record.createsSession = true;
      return [];
    }
    const session = this.#conversations.findOwned(record.input.sessionId, owner);
    if (session === undefined) {
      throw new ConversationOwnershipError();
    }
    record.preparedSessionId = session.id;
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
