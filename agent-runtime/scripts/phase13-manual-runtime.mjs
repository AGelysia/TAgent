#!/usr/bin/env node

import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";

const MAXIMUM_TOOL_OUTPUT_CHARACTERS = 64 * 1024;
const MAXIMUM_PENDING_CALLS = 64;
const PENDING_CALL_TTL_MILLIS = 2 * 60 * 1000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const SHA_256 = /^[0-9a-f]{64}$/u;
const NAMESPACED_ID = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/u;

const MANUAL_COMMANDS = Object.freeze({
  text: "phase13 text",
  recipe: "phase13 recipe",
  project: "save phase13 acceptance project",
  build: "phase13 build preview",
});

const PROJECT_PLAN = Object.freeze({
  name: "Phase 13 graphical acceptance",
  summary: "A deterministic local project used only for the Phase 13 manual client lane.",
  goals: Object.freeze(["Render one bounded Litematica preview without changing the world."]),
  constraints: Object.freeze([
    "Use a 3 by 2 by 2 lime wool target.",
    "Keep rotation 90 and mirror FRONT_BACK as explicit planning provenance.",
  ]),
});

const TEXT_RESPONSE = "Phase 13 deterministic text presentation is ready.";
const DEFAULT_RESPONSE = "Phase 13 manual harness accepted no deterministic scenario.";
const PROJECT_CREATED_RESPONSE =
  "Phase 13 deterministic project was created for this Runtime process.";
const PROJECT_EXISTS_RESPONSE =
  "Phase 13 deterministic project is already available in this Runtime process.";
const PROJECT_MISSING_RESPONSE =
  "Phase 13 deterministic project is unavailable. Create it in this Runtime process first.";
const ZERO_USAGE = Object.freeze({ inputTokens: 0, outputTokens: 0 });

class Phase13ManualProviderError extends Error {
  constructor() {
    super("Phase 13 manual provider rejected an unexpected state.");
    this.name = "Phase13ManualProviderError";
  }
}

function fail() {
  throw new Phase13ManualProviderError();
}

function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactObject(value, fields) {
  if (!isRecord(value)) {
    fail();
  }
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  if (
    actual.length !== expected.length ||
    actual.some((field, index) => field !== expected[index])
  ) {
    fail();
  }
  return value;
}

function objectWithOptionalFields(value, required, optional) {
  if (!isRecord(value)) {
    fail();
  }
  const keys = new Set(Object.keys(value));
  if (
    required.some((field) => !keys.has(field)) ||
    [...keys].some((field) => !required.includes(field) && !optional.includes(field))
  ) {
    fail();
  }
  return value;
}

function finiteNumber(value, minimum, maximum) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < minimum || value > maximum) {
    fail();
  }
  return value;
}

function safeInteger(value, minimum, maximum) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    fail();
  }
  return value;
}

function canonicalUuid(value) {
  if (typeof value !== "string" || !UUID.test(value)) {
    fail();
  }
  return value;
}

function namespacedId(value) {
  if (typeof value !== "string" || !NAMESPACED_ID.test(value)) {
    fail();
  }
  return value;
}

function stringArray(value) {
  if (!Array.isArray(value) || !value.every((entry) => typeof entry === "string")) {
    fail();
  }
  return value;
}

function sameStringArray(actual, expected) {
  const values = stringArray(actual);
  if (
    values.length !== expected.length ||
    values.some((value, index) => value !== expected[index])
  ) {
    fail();
  }
}

function samePosition(actual, expected) {
  const position = exactObject(actual, ["x", "y", "z"]);
  if (position.x !== expected.x || position.y !== expected.y || position.z !== expected.z) {
    fail();
  }
}

function sameBounds(actual, expected) {
  const bounds = exactObject(actual, ["min", "max"]);
  samePosition(bounds.min, expected.min);
  samePosition(bounds.max, expected.max);
}

function providerToolNames(request) {
  if (!Array.isArray(request.tools)) {
    fail();
  }
  const names = new Set();
  for (const tool of request.tools) {
    if (!isRecord(tool) || typeof tool.providerName !== "string" || names.has(tool.providerName)) {
      fail();
    }
    names.add(tool.providerName);
  }
  return names;
}

function requireTools(request, required) {
  const available = providerToolNames(request);
  if (required.some((name) => !available.has(name))) {
    fail();
  }
}

function currentMessage(request) {
  if (!Array.isArray(request.input)) {
    fail();
  }
  let current;
  for (const message of request.input) {
    const value = exactObject(message, ["role", "content"]);
    if (
      (value.role !== "user" && value.role !== "assistant") ||
      typeof value.content !== "string" ||
      value.content.length > 16 * 1024
    ) {
      fail();
    }
    if (value.role === "user") {
      current = value.content;
    }
  }
  if (current === undefined) {
    fail();
  }
  return current.normalize("NFKC").trim().toLowerCase();
}

function decodeToolOutput(output, source, trust) {
  if (
    typeof output !== "string" ||
    output.length < 2 ||
    output.length > MAXIMUM_TOOL_OUTPUT_CHARACTERS
  ) {
    fail();
  }
  let parsed;
  try {
    parsed = JSON.parse(output);
  } catch {
    fail();
  }
  const envelope = exactObject(parsed, ["status", "source", "trust", "result", "error"]);
  if (
    envelope.status !== "succeeded" ||
    envelope.source !== source ||
    envelope.trust !== trust ||
    envelope.error !== null ||
    !isRecord(envelope.result)
  ) {
    fail();
  }
  return envelope.result;
}

function finalResult(fallbackText) {
  return { type: "final", fallbackText, usage: { ...ZERO_USAGE } };
}

function validateRecipeResult(result) {
  const value = exactObject(result, ["query", "recipes", "totalMatches", "truncated"]);
  const query = exactObject(value.query, ["mode", "itemId"]);
  if (query.mode !== "lookup" || query.itemId !== "minecraft:crafting_table") {
    fail();
  }
  if (!Array.isArray(value.recipes) || value.recipes.length < 1 || value.recipes.length > 16) {
    fail();
  }
  const totalMatches = safeInteger(value.totalMatches, value.recipes.length, 1_000_000);
  if (
    typeof value.truncated !== "boolean" ||
    (!value.truncated && totalMatches !== value.recipes.length)
  ) {
    fail();
  }
  const graphicalRecipe = value.recipes.some((entry) => {
    const recipe = objectWithOptionalFields(
      entry,
      ["recipeId", "recipeType", "source", "result", "layout", "remainingItems"],
      ["processing"],
    );
    if (
      typeof recipe.recipeId !== "string" ||
      recipe.recipeType !== "shaped" ||
      !isRecord(recipe.source) ||
      !isRecord(recipe.result) ||
      !isRecord(recipe.layout)
    ) {
      return false;
    }
    return (
      recipe.source.kind === "server_registry" &&
      recipe.result.itemId === "minecraft:crafting_table" &&
      recipe.layout.kind === "grid"
    );
  });
  if (!graphicalRecipe) {
    fail();
  }
}

function validateProjectRecord(value) {
  const project = exactObject(value, [
    "projectId",
    "name",
    "summary",
    "goals",
    "constraints",
    "status",
    "revision",
    "createdAt",
    "updatedAt",
  ]);
  canonicalUuid(project.projectId);
  if (
    project.name !== PROJECT_PLAN.name ||
    project.summary !== PROJECT_PLAN.summary ||
    project.status !== "ACTIVE" ||
    typeof project.createdAt !== "string" ||
    typeof project.updatedAt !== "string" ||
    !Number.isFinite(Date.parse(project.createdAt)) ||
    !Number.isFinite(Date.parse(project.updatedAt))
  ) {
    fail();
  }
  sameStringArray(project.goals, PROJECT_PLAN.goals);
  sameStringArray(project.constraints, PROJECT_PLAN.constraints);
  safeInteger(project.revision, 1, 2_147_483_647);
  return project;
}

function validateProjectCreateResult(result) {
  const value = exactObject(result, ["outcome", "project"]);
  if (value.outcome !== "CREATED") {
    fail();
  }
  return validateProjectRecord(value.project);
}

function validateProjectReadResult(result, expected) {
  const value = exactObject(result, ["project"]);
  const project = validateProjectRecord(value.project);
  if (project.projectId !== expected.projectId || project.revision !== expected.revision) {
    fail();
  }
  return project;
}

function validateContextResult(result) {
  const value = exactObject(result, [
    "online",
    "playerName",
    "worldId",
    "position",
    "gameMode",
    "health",
    "maxHealth",
    "foodLevel",
    "saturation",
    "experienceLevel",
  ]);
  if (
    value.online !== true ||
    typeof value.playerName !== "string" ||
    !/^[A-Za-z0-9_]{1,16}$/u.test(value.playerName) ||
    !["survival", "creative", "adventure", "spectator"].includes(value.gameMode)
  ) {
    fail();
  }
  const worldId = namespacedId(value.worldId);
  const position = exactObject(value.position, ["x", "y", "z", "yaw", "pitch"]);
  const x = finiteNumber(position.x, -30_000_000, 30_000_000);
  const y = finiteNumber(position.y, -20_000_000, 20_000_000);
  const z = finiteNumber(position.z, -30_000_000, 30_000_000);
  finiteNumber(position.yaw, -360, 360);
  finiteNumber(position.pitch, -90, 90);
  finiteNumber(value.health, 0, 1_000_000);
  finiteNumber(value.maxHealth, Number.MIN_VALUE, 1_000_000);
  safeInteger(value.foodLevel, 0, 20);
  finiteNumber(value.saturation, 0, 20);
  safeInteger(value.experienceLevel, 0, 2_147_483_647);

  const blockX = Math.floor(x);
  const blockY = Math.floor(y);
  const blockZ = Math.floor(z);
  if (
    blockX < -30_000_000 ||
    blockX > 29_999_998 ||
    blockY < -2048 ||
    blockY > 2047 ||
    blockZ < -30_000_000 ||
    blockZ > 29_999_999
  ) {
    fail();
  }
  return Object.freeze({ worldId, blockX, blockY, blockZ });
}

function previewArguments(project, context) {
  const minimum = Object.freeze({ x: context.blockX, y: context.blockY, z: context.blockZ });
  const maximum = Object.freeze({
    x: context.blockX + 2,
    y: context.blockY + 1,
    z: context.blockZ + 1,
  });
  return Object.freeze({
    projectId: project.projectId,
    revision: project.revision,
    operation: "create",
    dimension: context.worldId,
    bounds: Object.freeze({ min: minimum, max: maximum }),
    origin: minimum,
    pattern: "walls",
    blockState: "minecraft:lime_wool",
    rotation: 90,
    mirror: "FRONT_BACK",
  });
}

function validateBuildResult(result, expected) {
  const value = exactObject(result, [
    "previewId",
    "projectId",
    "revision",
    "dimension",
    "bounds",
    "baseRegionHash",
    "changeSetHash",
    "targetBlockCount",
    "changeCount",
    "difference",
    "previewStatus",
    "worldWriteEnabled",
  ]);
  canonicalUuid(value.previewId);
  if (
    value.projectId !== expected.projectId ||
    value.revision !== expected.revision ||
    value.dimension !== expected.dimension ||
    typeof value.baseRegionHash !== "string" ||
    !SHA_256.test(value.baseRegionHash) ||
    typeof value.changeSetHash !== "string" ||
    !SHA_256.test(value.changeSetHash) ||
    value.previewStatus !== "server_validated" ||
    value.worldWriteEnabled !== false
  ) {
    fail();
  }
  sameBounds(value.bounds, expected.bounds);
  const targetBlockCount = safeInteger(value.targetBlockCount, 0, 12);
  const changeCount = safeInteger(value.changeCount, 0, 12);
  const difference = exactObject(value.difference, ["added", "replaced", "removed"]);
  const added = safeInteger(difference.added, 0, 12);
  const replaced = safeInteger(difference.replaced, 0, 12);
  const removed = safeInteger(difference.removed, 0, 12);
  if (targetBlockCount > 12 || changeCount !== added + replaced + removed) {
    fail();
  }
}

function continuationMarker(flow, step, token) {
  return {
    provider: "openai",
    items: [{ type: "phase13_manual", version: 1, flow, step, token }],
  };
}

function validateContinuation(continuation, pending) {
  const value = exactObject(continuation, ["provider", "items"]);
  if (value.provider !== "openai" || !Array.isArray(value.items) || value.items.length !== 1) {
    fail();
  }
  const marker = exactObject(value.items[0], ["type", "version", "flow", "step", "token"]);
  if (
    marker.type !== "phase13_manual" ||
    marker.version !== 1 ||
    marker.flow !== pending.flow ||
    marker.step !== pending.step ||
    marker.token !== pending.token
  ) {
    fail();
  }
}

export class Phase13ManualProvider {
  #pending = new Map();
  #project;
  #errorFactory;
  #randomUuid;
  #now;

  constructor(options = {}) {
    const value = exactObject(options, [
      ...(Object.hasOwn(options, "errorFactory") ? ["errorFactory"] : []),
      ...(Object.hasOwn(options, "randomUuid") ? ["randomUuid"] : []),
      ...(Object.hasOwn(options, "now") ? ["now"] : []),
    ]);
    this.#errorFactory = value.errorFactory ?? (() => new Phase13ManualProviderError());
    this.#randomUuid = value.randomUuid ?? randomUUID;
    this.#now = value.now ?? Date.now;
    if (
      typeof this.#errorFactory !== "function" ||
      typeof this.#randomUuid !== "function" ||
      typeof this.#now !== "function"
    ) {
      fail();
    }
  }

  async check() {
    return { ok: true };
  }

  async generate(request) {
    if (request?.signal?.aborted) {
      throw request.signal.reason;
    }
    try {
      this.#prune();
      return this.#generate(request);
    } catch (error) {
      if (error instanceof Phase13ManualProviderError) {
        throw this.#errorFactory();
      }
      throw error;
    }
  }

  #generate(request) {
    if (!isRecord(request)) {
      fail();
    }
    if (request.toolOutput !== undefined || request.continuation !== undefined) {
      return this.#continue(request);
    }

    const message = currentMessage(request);
    switch (message) {
      case MANUAL_COMMANDS.text:
        return finalResult(TEXT_RESPONSE);
      case MANUAL_COMMANDS.recipe:
        requireTools(request, ["server_recipe_lookup"]);
        return this.#toolCall(request, "recipe", "recipe.lookup", "server_recipe_lookup", {
          itemId: "minecraft:crafting_table",
        });
      case MANUAL_COMMANDS.project:
        if (this.#project !== undefined) {
          return finalResult(PROJECT_EXISTS_RESPONSE);
        }
        requireTools(request, ["project_create"]);
        return this.#toolCall(request, "project", "project.create", "project_create", {
          name: PROJECT_PLAN.name,
          summary: PROJECT_PLAN.summary,
          goals: [...PROJECT_PLAN.goals],
          constraints: [...PROJECT_PLAN.constraints],
        });
      case MANUAL_COMMANDS.build:
        if (this.#project === undefined) {
          return finalResult(PROJECT_MISSING_RESPONSE);
        }
        requireTools(request, ["player_context_read", "project_read", "build_preview_create"]);
        return this.#toolCall(request, "build", "build.context", "player_context_read", {});
      default:
        return finalResult(DEFAULT_RESPONSE);
    }
  }

  #continue(request) {
    const toolOutput = exactObject(request.toolOutput, ["providerCallId", "output"]);
    if (typeof toolOutput.providerCallId !== "string") {
      fail();
    }
    const pending = this.#pending.get(toolOutput.providerCallId);
    if (pending === undefined) {
      fail();
    }
    this.#pending.delete(toolOutput.providerCallId);
    validateContinuation(request.continuation, pending);

    switch (pending.step) {
      case "recipe.lookup": {
        const result = decodeToolOutput(toolOutput.output, "server_registry", "authoritative");
        validateRecipeResult(result);
        return finalResult("Phase 13 authoritative recipe lookup completed.");
      }
      case "project.create": {
        const result = decodeToolOutput(toolOutput.output, "runtime_storage", "verified");
        const project = validateProjectCreateResult(result);
        this.#project = Object.freeze({
          projectId: project.projectId,
          revision: project.revision,
        });
        return finalResult(PROJECT_CREATED_RESPONSE);
      }
      case "build.context": {
        const result = decodeToolOutput(toolOutput.output, "paper_api", "authoritative");
        const context = validateContextResult(result);
        return this.#toolCall(
          request,
          "build",
          "build.project",
          "project_read",
          { projectId: this.#project?.projectId ?? fail() },
          { context },
        );
      }
      case "build.project": {
        if (this.#project === undefined || pending.context === undefined) {
          fail();
        }
        const result = decodeToolOutput(toolOutput.output, "runtime_storage", "verified");
        validateProjectReadResult(result, this.#project);
        const arguments_ = previewArguments(this.#project, pending.context);
        return this.#toolCall(
          request,
          "build",
          "build.preview",
          "build_preview_create",
          arguments_,
          { previewArguments: arguments_ },
        );
      }
      case "build.preview": {
        if (pending.previewArguments === undefined) {
          fail();
        }
        const result = decodeToolOutput(toolOutput.output, "paper_api", "authoritative");
        validateBuildResult(result, pending.previewArguments);
        return finalResult("Phase 13 authoritative build preview completed.");
      }
      default:
        return fail();
    }
  }

  #toolCall(request, flow, step, providerName, arguments_, retained = {}) {
    requireTools(request, [providerName]);
    if (this.#pending.size >= MAXIMUM_PENDING_CALLS) {
      fail();
    }
    const callUuid = this.#randomUuid();
    const providerCallId = `phase13-${callUuid}`;
    const token = this.#randomUuid();
    if (
      providerCallId.length > 128 ||
      typeof callUuid !== "string" ||
      !UUID.test(callUuid) ||
      typeof token !== "string" ||
      !UUID.test(token) ||
      this.#pending.has(providerCallId)
    ) {
      fail();
    }
    this.#pending.set(providerCallId, {
      flow,
      step,
      token,
      createdAt: this.#now(),
      ...retained,
    });
    return {
      type: "tool_call",
      providerCallId,
      providerName,
      arguments: arguments_,
      continuation: continuationMarker(flow, step, token),
      usage: { ...ZERO_USAGE },
    };
  }

  #prune() {
    const now = this.#now();
    if (!Number.isSafeInteger(now) || now < 0) {
      fail();
    }
    for (const [providerCallId, pending] of this.#pending) {
      if (
        !Number.isSafeInteger(pending.createdAt) ||
        pending.createdAt > now ||
        now - pending.createdAt > PENDING_CALL_TTL_MILLIS
      ) {
        this.#pending.delete(providerCallId);
      }
    }
  }
}

function request(message, tools, continuation, toolOutput) {
  return {
    input: [{ role: "user", content: message }],
    tools: tools.map((providerName) => ({ providerName })),
    signal: new AbortController().signal,
    ...(continuation === undefined ? {} : { continuation }),
    ...(toolOutput === undefined ? {} : { toolOutput }),
  };
}

function succeeded(source, trust, result) {
  return JSON.stringify({ status: "succeeded", source, trust, result, error: null });
}

function continuationRequest(call, tools, output) {
  return request("ignored", tools, call.continuation, {
    providerCallId: call.providerCallId,
    output,
  });
}

async function runSelfCheck() {
  const ids = Array.from({ length: 20 }, (_, index) => {
    const suffix = (index + 1).toString(16).padStart(12, "0");
    return `00000000-0000-4000-8000-${suffix}`;
  });
  const provider = new Phase13ManualProvider({ randomUuid: () => ids.shift() ?? fail() });
  const allBuildTools = ["player_context_read", "project_read", "build_preview_create"];

  const text = await provider.generate(request(MANUAL_COMMANDS.text, []));
  assert.deepEqual(text, {
    type: "final",
    fallbackText: TEXT_RESPONSE,
    usage: { inputTokens: 0, outputTokens: 0 },
  });

  const recipeCall = await provider.generate(
    request(MANUAL_COMMANDS.recipe, ["server_recipe_lookup"]),
  );
  assert.equal(recipeCall.providerName, "server_recipe_lookup");
  const recipeResult = {
    query: { mode: "lookup", itemId: "minecraft:crafting_table" },
    recipes: [
      {
        recipeId: "minecraft:crafting_table",
        recipeType: "shaped",
        source: { kind: "server_registry", providerId: null },
        result: { itemId: "minecraft:crafting_table", count: 1, components: null },
        layout: { kind: "grid", width: 2, height: 2, ingredients: [] },
        remainingItems: [],
      },
    ],
    totalMatches: 1,
    truncated: false,
  };
  const recipeFinal = await provider.generate(
    continuationRequest(
      recipeCall,
      ["server_recipe_lookup"],
      succeeded("server_registry", "authoritative", recipeResult),
    ),
  );
  assert.equal(recipeFinal.type, "final");

  const projectCall = await provider.generate(request(MANUAL_COMMANDS.project, ["project_create"]));
  assert.deepEqual(projectCall.arguments, {
    name: PROJECT_PLAN.name,
    summary: PROJECT_PLAN.summary,
    goals: [...PROJECT_PLAN.goals],
    constraints: [...PROJECT_PLAN.constraints],
  });
  const storedProject = {
    projectId: "11111111-1111-4111-8111-111111111111",
    ...PROJECT_PLAN,
    goals: [...PROJECT_PLAN.goals],
    constraints: [...PROJECT_PLAN.constraints],
    status: "ACTIVE",
    revision: 1,
    createdAt: "2026-07-15T00:00:00.000Z",
    updatedAt: "2026-07-15T00:00:00.000Z",
  };
  const projectFinal = await provider.generate(
    continuationRequest(
      projectCall,
      ["project_create"],
      succeeded("runtime_storage", "verified", {
        outcome: "CREATED",
        project: storedProject,
      }),
    ),
  );
  assert.equal(projectFinal.fallbackText, PROJECT_CREATED_RESPONSE);

  const contextCall = await provider.generate(request(MANUAL_COMMANDS.build, allBuildTools));
  assert.equal(contextCall.providerName, "player_context_read");
  const contextResult = {
    online: true,
    playerName: "Phase13Tester",
    worldId: "minecraft:overworld",
    position: { x: 10.75, y: 64, z: -4.25, yaw: 90, pitch: 0 },
    gameMode: "creative",
    health: 20,
    maxHealth: 20,
    foodLevel: 20,
    saturation: 5,
    experienceLevel: 0,
  };
  const projectReadCall = await provider.generate(
    continuationRequest(
      contextCall,
      allBuildTools,
      succeeded("paper_api", "authoritative", contextResult),
    ),
  );
  assert.deepEqual(projectReadCall.arguments, { projectId: storedProject.projectId });
  const previewCall = await provider.generate(
    continuationRequest(
      projectReadCall,
      allBuildTools,
      succeeded("runtime_storage", "verified", { project: storedProject }),
    ),
  );
  assert.deepEqual(previewCall.arguments, {
    projectId: storedProject.projectId,
    revision: 1,
    operation: "create",
    dimension: "minecraft:overworld",
    bounds: { min: { x: 10, y: 64, z: -5 }, max: { x: 12, y: 65, z: -4 } },
    origin: { x: 10, y: 64, z: -5 },
    pattern: "walls",
    blockState: "minecraft:lime_wool",
    rotation: 90,
    mirror: "FRONT_BACK",
  });
  const buildResult = {
    previewId: "22222222-2222-4222-8222-222222222222",
    projectId: storedProject.projectId,
    revision: 1,
    dimension: "minecraft:overworld",
    bounds: previewCall.arguments.bounds,
    baseRegionHash: "a".repeat(64),
    changeSetHash: "b".repeat(64),
    targetBlockCount: 12,
    changeCount: 12,
    difference: { added: 12, replaced: 0, removed: 0 },
    previewStatus: "server_validated",
    worldWriteEnabled: false,
  };
  const buildFinal = await provider.generate(
    continuationRequest(
      previewCall,
      allBuildTools,
      succeeded("paper_api", "authoritative", buildResult),
    ),
  );
  assert.equal(buildFinal.type, "final");

  const malformedCall = await provider.generate(
    request(MANUAL_COMMANDS.recipe, ["server_recipe_lookup"]),
  );
  const malformed = JSON.stringify({
    status: "succeeded",
    source: "server_registry",
    trust: "authoritative",
    result: recipeResult,
    error: null,
    extra: true,
  });
  await assert.rejects(
    provider.generate(continuationRequest(malformedCall, ["server_recipe_lookup"], malformed)),
    Phase13ManualProviderError,
  );
  await assert.rejects(
    provider.generate(
      continuationRequest(
        malformedCall,
        ["server_recipe_lookup"],
        succeeded("server_registry", "authoritative", recipeResult),
      ),
    ),
    Phase13ManualProviderError,
  );

  const invalidIdentityProvider = new Phase13ManualProvider({ randomUuid: () => "not-a-uuid" });
  await assert.rejects(
    invalidIdentityProvider.generate(request(MANUAL_COMMANDS.recipe, ["server_recipe_lookup"])),
    Phase13ManualProviderError,
  );
}

function isMainModule() {
  const entryPath = process.argv[1];
  return entryPath !== undefined && pathToFileURL(entryPath).href === import.meta.url;
}

async function runRuntime(configPath) {
  const { loadRuntimeConfig, ModelGenerationError, startRuntime } =
    await import("../dist/index.js");
  const loaded = await loadRuntimeConfig({ configPath });
  if (
    loaded.config.privacy.storeConversations ||
    loaded.config.privacy.retentionDays !== 0 ||
    loaded.config.privacy.logMessageContent ||
    loaded.config.privacy.logToolCalls ||
    loaded.config.limits.maxToolRounds < 3 ||
    loaded.config.limits.maxConcurrentRequests !== 1
  ) {
    fail();
  }
  const provider = new Phase13ManualProvider({
    errorFactory: () => new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE"),
  });
  const runtime = await startRuntime({ configPath, modelProvider: provider });
  process.stdout.write("PHASE13_MANUAL_RUNTIME_READY\n");

  let stopping = false;
  const stop = async () => {
    if (stopping) {
      return;
    }
    stopping = true;
    process.off("SIGINT", stop);
    process.off("SIGTERM", stop);
    await runtime.close();
    process.stdout.write("PHASE13_MANUAL_RUNTIME_STOPPED\n");
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
}

async function runMain() {
  try {
    const arguments_ = process.argv.slice(2);
    if (arguments_.length === 1 && arguments_[0] === "--self-check") {
      await runSelfCheck();
      process.stdout.write("PHASE13_MANUAL_SELF_CHECK_OK\n");
      return;
    }
    if (arguments_.length === 2 && arguments_[0] === "--config" && arguments_[1] !== undefined) {
      await runRuntime(arguments_[1]);
      return;
    }
    throw new Phase13ManualProviderError();
  } catch {
    process.stderr.write("PHASE13_MANUAL_RUNTIME_FAILED\n");
    process.exitCode = 1;
  }
}

if (isMainModule()) {
  await runMain();
}
