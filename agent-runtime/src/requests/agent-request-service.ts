import type { RuntimeConfig } from "../config/runtime-config.js";
import {
  ModelGenerationError,
  type ModelGenerationFailureCode,
  type ModelProvider,
} from "../providers/model-provider.js";
import { RequestAdmissionController, type RequestAdmissionRejection } from "./request-admission.js";

const MAXIMUM_MODEL_OUTPUT_TOKENS = 1024;

export interface AgentRequestInput {
  readonly requestId: string;
  readonly playerUuid: string;
  readonly sessionId: null;
  readonly module: "general";
  readonly message: string;
}

export interface AgentCompletionPayload {
  readonly sessionId: null;
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

export interface AgentRequestServiceOptions {
  readonly provider: ModelProvider;
  readonly config: RuntimeConfig;
  readonly timeoutMilliseconds?: number;
  readonly now?: () => number;
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

export class AgentRequestService {
  readonly #provider: ModelProvider;
  readonly #config: RuntimeConfig;
  readonly #timeoutMilliseconds: number;
  readonly #admission: RequestAdmissionController;
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
    this.#timeoutMilliseconds = timeoutMilliseconds;
    this.#admission = new RequestAdmissionController(
      {
        maximumConcurrent: options.config.limits.maxConcurrentRequests,
        maximumQueued: options.config.limits.maxQueuedRequests,
        perPlayerCooldownMilliseconds: options.config.limits.perPlayerCooldownSeconds * 1000,
        dailyRequestsPerPlayer: options.config.limits.dailyRequestsPerPlayer,
      },
      options.now,
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
      .then(() =>
        this.#provider.generate({
          provider: this.#config.model.provider,
          model: this.#config.model.model,
          apiKey: this.#config.model.apiKey,
          input: record.input.message,
          maxOutputTokens: MAXIMUM_MODEL_OUTPUT_TOKENS,
          signal: record.controller.signal,
        }),
      )
      .then(
        (result) => {
          if (!record.terminalSent && !record.suppressResponse) {
            record.terminalSent = true;
            this.#safeRespond(
              record.respond,
              isUsableFallbackText(result.fallbackText)
                ? {
                    type: "agent.complete",
                    payload: {
                      sessionId: null,
                      playerUuid: record.input.playerUuid,
                      fallbackText: result.fallbackText,
                      structuredViews: [],
                    },
                  }
                : providerError(record.input.playerUuid, "MODEL_RESPONSE_INVALID"),
            );
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
              : {
                  type: "agent.error",
                  payload: {
                    playerUuid: record.input.playerUuid,
                    code: "RUNTIME_INTERNAL_ERROR",
                    fallbackText: "The AI request failed. Try again later.",
                    retryable: true,
                  },
                },
          );
        },
      )
      .finally(() => {
        this.#detach(record);
      })
      .catch(() => undefined);
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

  #safeRespond(
    responder: (response: AgentTerminalResponse) => void,
    response: AgentTerminalResponse,
  ): void {
    try {
      responder(response);
    } catch {
      // A failed transport must not leak a rejection or retain admission state.
    }
  }
}
