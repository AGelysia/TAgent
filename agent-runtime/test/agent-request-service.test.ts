import { beforeEach, describe, expect, it, vi } from "vitest";

import type { RuntimeConfig } from "../src/config/runtime-config.js";
import type {
  ModelGenerationRequest,
  ModelGenerationResult,
  ModelProvider,
} from "../src/providers/model-provider.js";
import {
  AgentRequestService,
  type AgentRequestInput,
  type AgentTerminalResponse,
} from "../src/requests/agent-request-service.js";

const PLAYER_ONE = "11111111-1111-4111-8111-111111111111";
const PLAYER_TWO = "22222222-2222-4222-8222-222222222222";

function config(overrides: Partial<RuntimeConfig["limits"]> = {}): RuntimeConfig {
  return {
    configVersion: 1,
    server: { id: "test-server" },
    transport: {
      host: "127.0.0.1",
      port: 38_127,
      serverToken: "test-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    },
    model: {
      provider: "openai",
      apiKey: "test-api-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      model: "test-model",
      timeoutSeconds: 2,
    },
    storage: { sqlitePath: "./data/runtime.db" },
    logging: { directory: "./logs", level: "info" },
    limits: {
      maxConcurrentRequests: 1,
      maxQueuedRequests: 1,
      maxToolRounds: 4,
      perPlayerCooldownSeconds: 0,
      dailyRequestsPerPlayer: 100,
      monthlyBudgetUsd: 10,
      ...overrides,
    },
    privacy: {
      storeConversations: true,
      retentionDays: 7,
      logMessageContent: false,
      logToolCalls: true,
    },
  };
}

function request(requestId: string, playerUuid = PLAYER_ONE): AgentRequestInput {
  return {
    requestId,
    playerUuid,
    sessionId: null,
    module: "general",
    message: `private prompt ${requestId}`,
  };
}

function provider(
  generate: (requestValue: ModelGenerationRequest) => Promise<ModelGenerationResult>,
): ModelProvider {
  return {
    check: vi.fn().mockResolvedValue({ ok: true }),
    generate: vi.fn(generate),
  };
}

function deferred<T>(): {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
} {
  let resolveValue: ((value: T) => void) | undefined;
  const promise = new Promise<T>((resolve) => {
    resolveValue = resolve;
  });
  return {
    promise,
    resolve: (value) => resolveValue?.(value),
  };
}

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe("Agent request service", () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  it("bounds global work, queues FIFO, and permits only one outstanding request per player", async () => {
    const first = deferred<ModelGenerationResult>();
    const second = deferred<ModelGenerationResult>();
    const adapter = provider((providerRequest) =>
      providerRequest.input.includes("request-1") ? first.promise : second.promise,
    );
    const service = new AgentRequestService({ provider: adapter, config: config() });
    const responses: AgentTerminalResponse[] = [];

    service.submit(request("request-1"), (response) => responses.push(response));
    service.submit(request("request-2", PLAYER_TWO), (response) => responses.push(response));
    service.submit(request("request-3"), (response) => responses.push(response));
    service.submit(request("request-4", "33333333-3333-4333-8333-333333333333"), (response) =>
      responses.push(response),
    );
    await flush();

    expect(adapter.generate).toHaveBeenCalledTimes(1);
    expect(service.activeCount).toBe(1);
    expect(service.queuedCount).toBe(1);
    expect(responses.map((response) => response.type)).toEqual(["agent.error", "agent.error"]);
    expect(
      responses.map((response) =>
        response.type === "agent.error" ? response.payload.code : "complete",
      ),
    ).toEqual(["REQUEST_LIMITED", "REQUEST_LIMITED"]);

    first.resolve({ fallbackText: "first answer" });
    await flush();
    expect(adapter.generate).toHaveBeenCalledTimes(2);
    expect(responses.at(-1)).toMatchObject({
      type: "agent.complete",
      payload: { playerUuid: PLAYER_ONE, fallbackText: "first answer" },
    });

    second.resolve({ fallbackText: "second answer" });
    await flush();
    expect(service.activeCount).toBe(0);
    expect(service.queuedCount).toBe(0);
  });

  it("releases an active slot immediately when a provider ignores cancellation", async () => {
    const abandoned = deferred<ModelGenerationResult>();
    const replacement = deferred<ModelGenerationResult>();
    let calls = 0;
    const adapter = provider(() => {
      calls += 1;
      return calls === 1 ? abandoned.promise : replacement.promise;
    });
    const service = new AgentRequestService({ provider: adapter, config: config() });
    const firstResponses: AgentTerminalResponse[] = [];
    const secondResponses: AgentTerminalResponse[] = [];

    service.submit(request("request-1"), (response) => firstResponses.push(response));
    await flush();
    expect(service.activeCount).toBe(1);
    expect(service.cancel("request-1", PLAYER_ONE)).toBe(true);
    expect(service.activeCount).toBe(0);

    service.submit(request("request-2"), (response) => secondResponses.push(response));
    await flush();
    expect(adapter.generate).toHaveBeenCalledTimes(2);
    expect(service.activeCount).toBe(1);

    abandoned.resolve({ fallbackText: "late answer" });
    await flush();
    expect(firstResponses).toEqual([]);
    expect(service.activeCount).toBe(1);

    replacement.resolve({ fallbackText: "replacement answer" });
    await flush();
    expect(secondResponses).toHaveLength(1);
    expect(service.activeCount).toBe(0);
  });

  it("times out without retaining a slot and ignores a late provider result", async () => {
    vi.useFakeTimers();
    const abandoned = deferred<ModelGenerationResult>();
    const adapter = provider(() => abandoned.promise);
    const service = new AgentRequestService({
      provider: adapter,
      config: config(),
      timeoutMilliseconds: 10,
    });
    const responses: AgentTerminalResponse[] = [];

    service.submit(request("request-1"), (response) => responses.push(response));
    await flush();
    await vi.advanceTimersByTimeAsync(10);

    expect(service.activeCount).toBe(0);
    expect(responses).toEqual([
      {
        type: "agent.error",
        payload: {
          playerUuid: PLAYER_ONE,
          code: "MODEL_TIMEOUT",
          fallbackText: "The AI request timed out. Try again.",
          retryable: true,
        },
      },
    ]);

    abandoned.resolve({ fallbackText: "late answer" });
    await flush();
    expect(responses).toHaveLength(1);
  });

  it("does not let a duplicate request id replace the live request", async () => {
    const pending = deferred<ModelGenerationResult>();
    const adapter = provider(() => pending.promise);
    const service = new AgentRequestService({ provider: adapter, config: config() });
    const duplicateResponses: AgentTerminalResponse[] = [];

    service.submit(request("same-id", PLAYER_ONE), () => undefined);
    service.submit(request("same-id", PLAYER_TWO), (response) => duplicateResponses.push(response));
    await flush();

    expect(adapter.generate).toHaveBeenCalledTimes(1);
    expect(duplicateResponses).toMatchObject([
      { type: "agent.error", payload: { playerUuid: PLAYER_TWO, code: "REQUEST_LIMITED" } },
    ]);
    expect(service.cancel("same-id", PLAYER_ONE)).toBe(true);
    expect(service.activeCount).toBe(0);
    pending.resolve({ fallbackText: "ignored" });
    await flush();
  });

  it("contains a synchronous provider exception and releases admission", async () => {
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn(() => {
        throw new Error("private provider detail");
      }),
    };
    const service = new AgentRequestService({ provider: adapter, config: config() });
    const responses: AgentTerminalResponse[] = [];

    service.submit(request("request-1"), (response) => responses.push(response));
    await flush();

    expect(service.activeCount).toBe(0);
    expect(responses).toMatchObject([
      { type: "agent.error", payload: { code: "RUNTIME_INTERNAL_ERROR" } },
    ]);
    expect(JSON.stringify(responses)).not.toContain("private provider detail");
  });

  it.each(["   ", "\ud800"])(
    "maps an unusable provider fallback %j to a safe error",
    async (fallbackText) => {
      const adapter = provider(async () => ({ fallbackText }));
      const service = new AgentRequestService({ provider: adapter, config: config() });
      const responses: AgentTerminalResponse[] = [];

      service.submit(request("request-1"), (response) => responses.push(response));
      await flush();

      expect(responses).toMatchObject([
        { type: "agent.error", payload: { code: "MODEL_RESPONSE_INVALID" } },
      ]);
      await vi.waitFor(() => expect(service.activeCount).toBe(0));
    },
  );

  it("contains a synchronous responder exception and releases admission", async () => {
    const adapter = provider(async () => ({ fallbackText: "answer" }));
    const service = new AgentRequestService({ provider: adapter, config: config() });

    service.submit(request("request-1"), () => {
      throw new Error("transport stopped");
    });
    await vi.waitFor(() => expect(service.activeCount).toBe(0));
    const responses: AgentTerminalResponse[] = [];
    service.submit(request("request-2", PLAYER_TWO), (response) => responses.push(response));
    await flush();
    expect(responses).toMatchObject([
      { type: "agent.complete", payload: { fallbackText: "answer" } },
    ]);
  });
});
