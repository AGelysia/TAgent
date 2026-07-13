import { readFile } from "node:fs/promises";

import { describe, expect, it } from "vitest";

import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import { ToolRegistry } from "../src/tools/tool-registry.js";
import { coreToolIds } from "../src/tools/tool-types.js";

const schemas = await SchemaRegistry.load();
const registry = new ToolRegistry(schemas);
const fixture = JSON.parse(
  await readFile(
    new URL("../../protocol/fixtures/valid/core-tool-contracts.json", import.meta.url),
    "utf8",
  ),
) as Record<string, { arguments: Record<string, unknown>; result: Record<string, unknown> }>;

describe("Runtime Tool Registry", () => {
  it("exposes exactly six unique provider-safe read tools with self-contained parameters", () => {
    const tools = registry.list();

    expect(tools.map((tool) => tool.id)).toEqual(coreToolIds);
    expect(new Set(tools.map((tool) => tool.providerName)).size).toBe(coreToolIds.length);
    expect(tools.every((tool) => /^[A-Za-z0-9_-]{1,64}$/u.test(tool.providerName))).toBe(true);
    const providerSchemas = JSON.stringify(tools.map((tool) => tool.parameters));
    expect(providerSchemas).not.toMatch(
      /\$schema|\$id|\$ref|maxProperties|pattern|minLength|maxLength/u,
    );
    expect(registry.byProviderName("server_info_read")?.parameters).toEqual({
      type: "object",
      properties: {},
      required: [],
      additionalProperties: false,
    });
    expect(registry.byProviderName("server_recipe_lookup")?.id).toBe("server.recipe.lookup");
    expect(registry.byProviderName("server.recipe.lookup")).toBeUndefined();
  });

  it("validates closed arguments and authoritative typed results", () => {
    const context = registry.byProviderName("player_context_read");
    const recipe = registry.byProviderName("server_recipe_lookup");
    if (context === undefined || recipe === undefined) {
      throw new Error("missing test tools");
    }

    expect(registry.validateArguments(context, {})).toBe(true);
    expect(registry.validateArguments(context, { extra: true })).toBe(false);
    expect(registry.validateArguments(recipe, fixture["recipeLookup"]?.arguments ?? {})).toBe(true);
    expect(registry.validateArguments(recipe, { itemId: "not-an-item" })).toBe(false);
    expect(
      registry.validateResult(context, {
        toolCallId: "11111111-1111-4111-8111-111111111111",
        sessionId: "22222222-2222-4222-8222-222222222222",
        playerUuid: "33333333-3333-4333-8333-333333333333",
        tool: context.id,
        sequence: 0,
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: fixture["playerContext"]?.result ?? {},
        error: null,
      }),
    ).toBe(true);
    expect(
      registry.validateResult(context, {
        toolCallId: "11111111-1111-4111-8111-111111111111",
        sessionId: "22222222-2222-4222-8222-222222222222",
        playerUuid: "33333333-3333-4333-8333-333333333333",
        tool: context.id,
        sequence: 0,
        status: "succeeded",
        source: "paper_api",
        trust: "untrusted",
        result: fixture["playerContext"]?.result ?? {},
        error: null,
      }),
    ).toBe(false);
  });
});
