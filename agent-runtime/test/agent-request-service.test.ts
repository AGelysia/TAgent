import { DatabaseSync } from "node:sqlite";

import { beforeEach, describe, expect, it, vi } from "vitest";

import type { RuntimeConfig } from "../src/config/runtime-config.js";
import {
  ModelGenerationError,
  type ModelGenerationRequest,
  type ModelGenerationResult,
  type ModelProvider,
} from "../src/providers/model-provider.js";
import {
  AgentRequestService,
  type AgentRuntimeResponse,
  type AgentRequestInput,
  type AgentRequestServiceOptions,
  type AgentTerminalResponse,
} from "../src/requests/agent-request-service.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import { SqliteConversationRepository } from "../src/storage/conversation-repository.js";
import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import { ToolRegistry } from "../src/tools/tool-registry.js";
import type { ToolExecutionResult } from "../src/tools/tool-types.js";

const PLAYER_ONE = "11111111-1111-4111-8111-111111111111";
const PLAYER_TWO = "22222222-2222-4222-8222-222222222222";
const PERSISTENT_REQUEST_ONE = "33333333-3333-4333-8333-333333333333";
const PERSISTENT_REQUEST_TWO = "44444444-4444-4444-8444-444444444444";
const tools = new ToolRegistry(await SchemaRegistry.load());

function agentService(options: Omit<AgentRequestServiceOptions, "tools">): AgentRequestService {
  return new AgentRequestService({ ...options, tools });
}

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
      maxContextMessages: 30,
      maxContextCharacters: 32_768,
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
  generate: (
    requestValue: ModelGenerationRequest,
  ) => Promise<ModelGenerationResult | { readonly fallbackText: string }>,
): ModelProvider {
  return {
    check: vi.fn().mockResolvedValue({ ok: true }),
    generate: vi.fn(async (requestValue) => {
      const result = await generate(requestValue);
      return "type" in result ? result : { type: "final", fallbackText: result.fallbackText };
    }),
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

async function runRecipeRound(options: {
  readonly providerName: "server_recipe_lookup" | "server_recipe_uses";
  readonly itemId: string;
  readonly execution: ToolExecutionResult;
  readonly modelFallback: string;
}): Promise<{
  readonly responses: readonly AgentRuntimeResponse[];
  readonly generated: readonly ModelGenerationRequest[];
}> {
  let generation = 0;
  const generated: ModelGenerationRequest[] = [];
  const adapter = provider(async (generationRequest) => {
    generated.push(generationRequest);
    generation += 1;
    if (generation === 1) {
      return {
        type: "tool_call",
        providerCallId: "provider-recipe-call",
        providerName: options.providerName,
        arguments: { itemId: options.itemId },
        continuation: { provider: "openai", items: [] },
      };
    }
    return { type: "final", fallbackText: options.modelFallback };
  });
  const service = agentService({ provider: adapter, config: config() });
  const responses: AgentRuntimeResponse[] = [];
  service.submit(
    {
      ...request(PERSISTENT_REQUEST_ONE),
      module: "recipe",
      message: `show recipes for ${options.itemId}`,
    },
    (response) => responses.push(response),
  );

  await vi.waitFor(() => expect(responses[0]?.type).toBe("tool.call"));
  const call = responses[0];
  if (call?.type !== "tool.call") {
    throw new Error("missing recipe Tool call");
  }
  expect(
    service.acceptToolResult(PERSISTENT_REQUEST_ONE, {
      ...options.execution,
      toolCallId: call.payload.toolCallId,
      sessionId: call.payload.sessionId,
      playerUuid: call.payload.playerUuid,
      tool: call.payload.tool,
      sequence: call.payload.sequence,
    }),
  ).toBe("accepted");
  await vi.waitFor(() => expect(service.activeCount).toBe(0));
  return { responses, generated };
}

describe("Agent request service", () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  it("bounds global work, queues FIFO, and permits only one outstanding request per player", async () => {
    const first = deferred<ModelGenerationResult>();
    const second = deferred<ModelGenerationResult>();
    const adapter = provider((providerRequest) =>
      providerRequest.input.some((message) => message.content.includes("request-1"))
        ? first.promise
        : second.promise,
    );
    const service = agentService({ provider: adapter, config: config() });
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

    first.resolve({ type: "final", fallbackText: "first answer" });
    await flush();
    expect(adapter.generate).toHaveBeenCalledTimes(2);
    expect(responses.at(-1)).toMatchObject({
      type: "agent.complete",
      payload: { playerUuid: PLAYER_ONE, fallbackText: "first answer" },
    });

    second.resolve({ type: "final", fallbackText: "second answer" });
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
    const service = agentService({ provider: adapter, config: config() });
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

    abandoned.resolve({ type: "final", fallbackText: "late answer" });
    await flush();
    expect(firstResponses).toEqual([]);
    expect(service.activeCount).toBe(1);

    replacement.resolve({ type: "final", fallbackText: "replacement answer" });
    await flush();
    expect(secondResponses).toHaveLength(1);
    expect(service.activeCount).toBe(0);
  });

  it("does not commit a late provider result after service close", async () => {
    const database = new DatabaseSync(":memory:");
    migrateRuntimeStorage(database, "2026-07-13T00:00:00.000Z");
    const pending = deferred<ModelGenerationResult>();
    const service = agentService({
      provider: provider(() => pending.promise),
      config: config(),
      conversations: new SqliteConversationRepository(database),
      randomUuid: () => "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    });

    service.submit(request(PERSISTENT_REQUEST_ONE), () => undefined);
    await flush();
    service.close();
    pending.resolve({ type: "final", fallbackText: "late answer" });
    await flush();

    expect(database.prepare("SELECT COUNT(*) AS count FROM sessions").get()?.["count"]).toBe(0);
    expect(database.prepare("SELECT COUNT(*) AS count FROM messages").get()?.["count"]).toBe(0);
    database.close();
  });

  it("times out without retaining a slot and ignores a late provider result", async () => {
    vi.useFakeTimers();
    const abandoned = deferred<ModelGenerationResult>();
    const adapter = provider(() => abandoned.promise);
    const service = agentService({
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

    abandoned.resolve({ type: "final", fallbackText: "late answer" });
    await flush();
    expect(responses).toHaveLength(1);
  });

  it("does not let a duplicate request id replace the live request", async () => {
    const pending = deferred<ModelGenerationResult>();
    const adapter = provider(() => pending.promise);
    const service = agentService({ provider: adapter, config: config() });
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
    pending.resolve({ type: "final", fallbackText: "ignored" });
    await flush();
  });

  it("contains a synchronous provider exception and releases admission", async () => {
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn(() => {
        throw new Error("private provider detail");
      }),
    };
    const service = agentService({ provider: adapter, config: config() });
    const responses: AgentTerminalResponse[] = [];

    service.submit(request("request-1"), (response) => responses.push(response));
    await flush();

    expect(service.activeCount).toBe(0);
    expect(responses).toMatchObject([
      { type: "agent.error", payload: { code: "RUNTIME_INTERNAL_ERROR" } },
    ]);
    expect(JSON.stringify(responses)).not.toContain("private provider detail");
  });

  it.each(["   ", "\ud800", "bad\ftext", "left\u0085right", "left\u202eright"])(
    "maps an unusable provider fallback %j to a safe error",
    async (fallbackText) => {
      const adapter = provider(async () => ({ fallbackText }));
      const service = agentService({ provider: adapter, config: config() });
      const responses: AgentTerminalResponse[] = [];

      service.submit(request("request-1"), (response) => responses.push(response));
      await flush();

      expect(responses).toMatchObject([
        { type: "agent.error", payload: { code: "MODEL_RESPONSE_INVALID" } },
      ]);
      await vi.waitFor(() => expect(service.activeCount).toBe(0));
    },
  );

  it("counts supplementary fallback text by Unicode code point and permits line formatting", async () => {
    const fallbackText = `${"\ud83d\ude00".repeat(5000)}\n\tcomplete`;
    const adapter = provider(async () => ({ fallbackText }));
    const service = agentService({ provider: adapter, config: config() });
    const responses: AgentTerminalResponse[] = [];

    service.submit(request("request-1"), (response) => responses.push(response));
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses).toMatchObject([{ type: "agent.complete", payload: { fallbackText } }]);
  });

  it("contains a synchronous responder exception and releases admission", async () => {
    const adapter = provider(async () => ({ fallbackText: "answer" }));
    const service = agentService({ provider: adapter, config: config() });

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

  it("persists and resumes owned history while keeping explicit modules one-shot", async () => {
    const database = new DatabaseSync(":memory:");
    migrateRuntimeStorage(database, "2026-07-13T00:00:00.000Z");
    const conversations = new SqliteConversationRepository(database);
    const generated: ModelGenerationRequest[] = [];
    const adapter = provider(async (generation) => {
      generated.push(generation);
      return { fallbackText: `answer-${String(generated.length)}` };
    });
    const service = agentService({
      provider: adapter,
      config: config(),
      conversations,
      randomUuid: () => "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      now: () => Date.parse("2026-07-13T00:00:00.000Z"),
    });
    const firstResponses: AgentTerminalResponse[] = [];
    service.submit({ ...request(PERSISTENT_REQUEST_ONE), module: "recipe" }, (response) =>
      firstResponses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));
    const first = firstResponses[0];
    expect(first?.type).toBe("agent.complete");
    const sessionId = first?.type === "agent.complete" ? first.payload.sessionId : null;
    expect(sessionId).toBe("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    expect(first).toMatchObject({
      type: "agent.complete",
      payload: {
        fallbackText:
          "No verified server recipe was checked. Ask again with an exact Minecraft item ID.",
      },
    });

    const secondResponses: AgentTerminalResponse[] = [];
    service.submit(
      { ...request(PERSISTENT_REQUEST_TWO), sessionId, module: "general" },
      (response) => secondResponses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(generated[0]?.instructions).toContain("recipe");
    expect(generated[1]?.instructions).not.toContain("recipe");
    expect(generated[1]?.input).toEqual([
      { role: "user", content: `private prompt ${PERSISTENT_REQUEST_ONE}` },
      {
        role: "assistant",
        content:
          "No verified server recipe was checked. Ask again with an exact Minecraft item ID.",
      },
      { role: "user", content: `private prompt ${PERSISTENT_REQUEST_TWO}` },
    ]);
    expect(
      conversations
        .loadRecentOwned(
          sessionId ?? "",
          {
            serverId: "test-server",
            playerUuid: PLAYER_ONE,
          },
          10,
        )
        .map((messageValue) => messageValue.module),
    ).toEqual(["recipe", "recipe", "general", "general"]);

    service.close();
    const restarted = agentService({
      provider: adapter,
      config: config(),
      conversations: new SqliteConversationRepository(database),
    });
    const resumed: unknown[] = [];
    restarted.resume(
      { requestId: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", playerUuid: PLAYER_ONE, sessionId },
      (response) => resumed.push(response),
    );
    restarted.resume(
      { requestId: "ffffffff-ffff-4fff-8fff-ffffffffffff", playerUuid: PLAYER_TWO, sessionId },
      (response) => resumed.push(response),
    );
    restarted.resume(
      {
        requestId: "12121212-1212-4212-8212-121212121212",
        playerUuid: PLAYER_ONE,
        sessionId: "00000000-0000-0000-0000-000000000000",
      },
      (response) => resumed.push(response),
    );
    expect(resumed).toMatchObject([
      { type: "session.resumed", payload: { sessionId, playerUuid: PLAYER_ONE } },
      { type: "agent.error", payload: { code: "SESSION_NOT_FOUND" } },
      { type: "agent.error", payload: { code: "SESSION_NOT_FOUND" } },
    ]);
    restarted.close();
    database.close();
  });

  it("keeps disabled conversation mode stateless and rejects resume", async () => {
    const adapter = provider(async () => ({ fallbackText: "stateless answer" }));
    const service = agentService({ provider: adapter, config: config() });
    const completions: AgentTerminalResponse[] = [];
    service.submit(request(PERSISTENT_REQUEST_ONE), (response) => completions.push(response));
    await vi.waitFor(() => expect(service.activeCount).toBe(0));
    expect(completions).toMatchObject([{ type: "agent.complete", payload: { sessionId: null } }]);

    const resumes: unknown[] = [];
    service.resume(
      { requestId: PERSISTENT_REQUEST_TWO, playerUuid: PLAYER_ONE, sessionId: null },
      (response) => resumes.push(response),
    );
    expect(resumes).toMatchObject([
      { type: "agent.error", payload: { code: "CONVERSATION_STORAGE_DISABLED" } },
    ]);
  });

  it("runs two correlated read Tool rounds and keeps an ephemeral private session off disk", async () => {
    let generation = 0;
    const generated: ModelGenerationRequest[] = [];
    const adapter = provider(async (generationRequest) => {
      generated.push(generationRequest);
      generation += 1;
      if (generation <= 2) {
        const providerCallId = `provider-call-${String(generation)}`;
        return {
          type: "tool_call",
          providerCallId,
          providerName: "server_info_read",
          arguments: {},
          continuation: {
            provider: "openai",
            items: [
              {
                type: "function_call",
                call_id: providerCallId,
                name: "server_info_read",
                arguments: "{}",
              },
            ],
          },
        };
      }
      return { type: "final", fallbackText: "There is one player online." };
    });
    const allocated = [
      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    ];
    const service = agentService({
      provider: adapter,
      config: config(),
      randomUuid: () => allocated.shift() ?? "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(request(PERSISTENT_REQUEST_ONE), (response) => responses.push(response));

    await vi.waitFor(() =>
      expect(responses.filter((response) => response.type === "tool.call")).toHaveLength(1),
    );
    const firstCall = responses.find((response) => response.type === "tool.call");
    if (firstCall?.type !== "tool.call") {
      throw new Error("missing first Tool call");
    }
    expect(firstCall.payload).toMatchObject({
      sessionId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      toolCallId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      tool: "server.info.read",
      sequence: 0,
    });
    expect(
      service.acceptToolResult(PERSISTENT_REQUEST_ONE, {
        toolCallId: firstCall.payload.toolCallId,
        sessionId: firstCall.payload.sessionId,
        playerUuid: PLAYER_TWO,
        tool: firstCall.payload.tool,
        sequence: 0,
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: serverInfoResult(),
        error: null,
      }),
    ).toBe("violation");
    expect(
      service.acceptToolResult(PERSISTENT_REQUEST_ONE, {
        toolCallId: firstCall.payload.toolCallId,
        sessionId: firstCall.payload.sessionId,
        playerUuid: PLAYER_ONE,
        tool: firstCall.payload.tool,
        sequence: 0,
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: serverInfoResult(),
        error: null,
      }),
    ).toBe("accepted");

    await vi.waitFor(() =>
      expect(responses.filter((response) => response.type === "tool.call")).toHaveLength(2),
    );
    const calls = responses.filter((response) => response.type === "tool.call");
    const secondCall = calls[1];
    if (secondCall?.type !== "tool.call") {
      throw new Error("missing second Tool call");
    }
    expect(secondCall.payload).toMatchObject({
      sessionId: firstCall.payload.sessionId,
      toolCallId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      sequence: 1,
    });
    expect(
      service.acceptToolResult(PERSISTENT_REQUEST_ONE, {
        toolCallId: secondCall.payload.toolCallId,
        sessionId: secondCall.payload.sessionId,
        playerUuid: PLAYER_ONE,
        tool: secondCall.payload.tool,
        sequence: 1,
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: serverInfoResult(),
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses.at(-1)).toMatchObject({
      type: "agent.complete",
      payload: { sessionId: null, fallbackText: "There is one player online." },
    });
    expect(generated[1]?.toolOutput?.output).toContain('"trust":"authoritative"');
    expect(generated[2]?.toolOutput?.providerCallId).toBe("provider-call-2");
  });

  it("publishes unmerged recipe variants from the trusted Tool snapshot and ignores model spoofing", async () => {
    const recipes = [shapedRecipe(), smeltingRecipe()];
    const { responses, generated } = await runRecipeRound({
      providerName: "server_recipe_lookup",
      itemId: "minecraft:torch",
      execution: {
        status: "succeeded",
        source: "server_registry",
        trust: "authoritative",
        result: recipeToolResult("lookup", "minecraft:torch", recipes),
        error: null,
      },
      modelFallback: '{"schemaVersion":"2.0","recipes":[{"recipeId":"spoof:invented_grid"}]}',
    });

    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") {
      throw new Error("missing recipe completion");
    }
    const view = completion.payload.structuredViews[0];
    if (view?.viewType !== "recipe") {
      throw new Error("missing recipe view");
    }
    expect(view).toMatchObject({
      viewSchemaVersion: "1.0",
      requestId: PERSISTENT_REQUEST_ONE,
      pinnable: true,
      content: {
        schemaVersion: "2.0",
        query: { mode: "lookup", itemId: "minecraft:torch" },
        selectedRecipe: 0,
        totalMatches: 2,
        truncated: false,
      },
    });
    expect(view.content.recipes).toEqual(recipes);
    expect(view.content.recipes[1]?.["source"]).toEqual({
      kind: "plugin_provider",
      providerId: "test:recipe_provider",
    });
    expect(completion.payload.fallbackText).toContain(
      "authoritative server registry found 2 recipes",
    );
    expect(completion.payload.fallbackText).not.toContain("spoof");
    expect(view.fallbackText).toBe(completion.payload.fallbackText);
    expect(generated[1]?.toolOutput?.output).toContain('"source":"server_registry"');
  });

  it("constructs an authoritative uses view without relying on client capability input", async () => {
    const recipes = [stonecuttingRecipe()];
    const { responses } = await runRecipeRound({
      providerName: "server_recipe_uses",
      itemId: "minecraft:stone",
      execution: {
        status: "succeeded",
        source: "server_registry",
        trust: "authoritative",
        result: recipeToolResult("uses", "minecraft:stone", recipes),
        error: null,
      },
      modelFallback: "I think stone makes a fake item.",
    });

    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") {
      throw new Error("missing recipe completion");
    }
    const view = completion.payload.structuredViews[0];
    expect(view?.viewType).toBe("recipe");
    if (view?.viewType !== "recipe") {
      throw new Error("missing recipe view");
    }
    expect(view.content.query).toEqual({ mode: "uses", itemId: "minecraft:stone" });
    expect(view.content.recipes).toEqual(recipes);
    expect(completion.payload.fallbackText).toContain("recipe that uses minecraft:stone");
    expect(completion.payload.fallbackText).not.toContain("fake item");
  });

  it("uses the trusted empty recipe result as a concise text-only answer", async () => {
    const { responses } = await runRecipeRound({
      providerName: "server_recipe_lookup",
      itemId: "minecraft:barrier",
      execution: {
        status: "succeeded",
        source: "server_registry",
        trust: "authoritative",
        result: recipeToolResult("lookup", "minecraft:barrier", []),
        error: null,
      },
      modelFallback: "The authoritative recipe is one dirt in a grid.",
    });

    const completion = responses.at(-1);
    expect(completion).toMatchObject({
      type: "agent.complete",
      payload: {
        fallbackText:
          "Text-only recipe summary: the authoritative server registry found no recipes that produce minecraft:barrier.",
        structuredViews: [{ viewType: "text" }],
      },
    });
  });

  it("does not turn a failed recipe lookup or model completion into an authoritative answer", async () => {
    const { responses } = await runRecipeRound({
      providerName: "server_recipe_lookup",
      itemId: "minecraft:diamond_sword",
      execution: {
        status: "failed",
        source: "server_registry",
        trust: "authoritative",
        result: null,
        error: {
          code: "REGISTRY_UNAVAILABLE",
          message: "Recipe registry unavailable",
          retryable: true,
        },
      },
      modelFallback: "Authoritative result: one diamond makes a sword.",
    });

    const completion = responses.at(-1);
    expect(completion).toMatchObject({
      type: "agent.complete",
      payload: {
        fallbackText:
          "The server recipe registry could not provide a verified result. Try again later.",
        structuredViews: [{ viewType: "text" }],
      },
    });
    if (completion?.type === "agent.complete") {
      expect(completion.payload.fallbackText).not.toContain("one diamond");
    }
  });

  it("drops model-only recipe facts when no authoritative Tool result was checked", async () => {
    const adapter = provider(async () => ({
      fallbackText: "Authoritative server recipe: one stick makes a diamond.",
    }));
    const service = agentService({ provider: adapter, config: config() });
    const responses: AgentTerminalResponse[] = [];
    service.submit({ ...request(PERSISTENT_REQUEST_ONE), module: "recipe" }, (response) =>
      responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses).toMatchObject([
      {
        type: "agent.complete",
        payload: {
          fallbackText:
            "No verified server recipe was checked. Ask again with an exact Minecraft item ID.",
          structuredViews: [{ viewType: "text" }],
        },
      },
    ]);
  });

  it("stops at the configured Tool round limit without sending another call to Paper", async () => {
    let generation = 0;
    const adapter = provider(async (generationRequest) => {
      generation += 1;
      if (generation > 1) {
        expect(generationRequest.tools).toEqual([]);
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      return {
        type: "tool_call",
        providerCallId: `provider-call-${String(generation)}`,
        providerName: "server_info_read",
        arguments: {},
        continuation: { provider: "openai", items: [] },
      };
    });
    const service = agentService({
      provider: adapter,
      config: config({ maxToolRounds: 1 }),
      randomUuid: (() => {
        const ids = [
          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        ];
        return () => ids.shift() ?? "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
      })(),
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(request(PERSISTENT_REQUEST_ONE), (response) => responses.push(response));
    await vi.waitFor(() => expect(responses[0]?.type).toBe("tool.call"));
    const call = responses[0];
    if (call?.type !== "tool.call") {
      throw new Error("missing Tool call");
    }
    expect(
      service.acceptToolResult(PERSISTENT_REQUEST_ONE, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: PLAYER_ONE,
        tool: call.payload.tool,
        sequence: 0,
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: serverInfoResult(),
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses.filter((response) => response.type === "tool.call")).toHaveLength(1);
    expect(responses.at(-1)).toMatchObject({
      type: "agent.error",
      payload: { code: "TOOL_ROUND_LIMIT" },
    });
    expect((adapter.generate as ReturnType<typeof vi.fn>).mock.calls[1]?.[0].tools).toEqual([]);
  });

  it.each([
    ["unknown provider name", "unknown_read", {}, "general"],
    ["arguments outside the closed schema", "server_info_read", { extra: true }, "general"],
    ["Tool outside the module allowlist", "server_plugins_list", {}, "locate"],
  ] as const)(
    "rejects %s before emitting a Tool call",
    async (_label, providerName, args, module) => {
      const adapter = provider(async () => ({
        type: "tool_call",
        providerCallId: "provider-call-1",
        providerName,
        arguments: args,
        continuation: { provider: "openai", items: [] },
      }));
      const service = agentService({ provider: adapter, config: config() });
      const responses: AgentRuntimeResponse[] = [];
      service.submit({ ...request(PERSISTENT_REQUEST_ONE), module }, (response) =>
        responses.push(response),
      );
      await vi.waitFor(() => expect(service.activeCount).toBe(0));

      expect(responses).toMatchObject([
        { type: "agent.error", payload: { code: "TOOL_REJECTED" } },
      ]);
      expect(responses.some((response) => response.type === "tool.call")).toBe(false);
    },
  );

  it("times out while waiting for Paper and ignores the late Tool result", async () => {
    vi.useFakeTimers();
    const adapter = provider(async () => ({
      type: "tool_call",
      providerCallId: "provider-call-1",
      providerName: "server_info_read",
      arguments: {},
      continuation: { provider: "openai", items: [] },
    }));
    const service = agentService({
      provider: adapter,
      config: config(),
      timeoutMilliseconds: 10,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(request(PERSISTENT_REQUEST_ONE), (response) => responses.push(response));
    await flush();
    const call = responses.find((response) => response.type === "tool.call");
    if (call?.type !== "tool.call") {
      throw new Error("missing Tool call");
    }
    await vi.advanceTimersByTimeAsync(10);

    expect(responses.at(-1)).toMatchObject({
      type: "agent.error",
      payload: { code: "MODEL_TIMEOUT" },
    });
    expect(
      service.acceptToolResult(PERSISTENT_REQUEST_ONE, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: PLAYER_ONE,
        tool: call.payload.tool,
        sequence: 0,
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: serverInfoResult(),
        error: null,
      }),
    ).toBe("ignored");
    expect(adapter.generate).toHaveBeenCalledOnce();
  });
});

function serverInfoResult(): Readonly<Record<string, unknown>> {
  return {
    serverName: "Paper",
    minecraftVersion: "1.21.11",
    serverVersion: "1.21.11-132",
    onlinePlayers: 1,
    maxPlayers: 20,
    viewDistance: 10,
    simulationDistance: 10,
  };
}

function itemStack(itemId: string, count = 1): Readonly<Record<string, unknown>> {
  return { itemId, count, components: {} };
}

function materialChoice(itemId: string): Readonly<Record<string, unknown>> {
  return {
    choiceType: "material",
    alternatives: [itemStack(itemId)],
  };
}

function shapedRecipe(): Readonly<Record<string, unknown>> {
  return {
    recipeId: "minecraft:torch",
    recipeType: "shaped",
    source: { kind: "server_registry", providerId: null },
    result: itemStack("minecraft:torch", 4),
    layout: {
      kind: "grid",
      width: 1,
      height: 2,
      ingredients: [
        { slot: 0, x: 0, y: 0, ingredient: materialChoice("minecraft:coal") },
        { slot: 1, x: 0, y: 1, ingredient: materialChoice("minecraft:stick") },
      ],
    },
    remainingItems: [],
  };
}

function smeltingRecipe(): Readonly<Record<string, unknown>> {
  return {
    recipeId: "test:smelt_torch",
    recipeType: "smelting",
    source: { kind: "plugin_provider", providerId: "test:recipe_provider" },
    result: itemStack("minecraft:torch", 2),
    layout: {
      kind: "single_input",
      ingredient: materialChoice("minecraft:charcoal"),
    },
    remainingItems: [],
    processing: { timeTicks: 100, experience: 0.1 },
  };
}

function stonecuttingRecipe(): Readonly<Record<string, unknown>> {
  return {
    recipeId: "minecraft:stone_bricks",
    recipeType: "stonecutting",
    source: { kind: "server_registry", providerId: null },
    result: itemStack("minecraft:stone_bricks"),
    layout: {
      kind: "single_input",
      ingredient: materialChoice("minecraft:stone"),
    },
    remainingItems: [],
  };
}

function recipeToolResult(
  mode: "lookup" | "uses",
  itemId: string,
  recipes: readonly Readonly<Record<string, unknown>>[],
): Readonly<Record<string, unknown>> {
  return {
    query: { mode, itemId },
    recipes,
    totalMatches: recipes.length,
    truncated: false,
  };
}
