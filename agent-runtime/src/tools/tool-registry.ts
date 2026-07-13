import type { ModelToolDefinition } from "../providers/model-provider.js";
import type { SchemaRegistry } from "../protocol/schema-registry.js";
import { coreToolIds, type CoreToolId, type ToolResultPayload } from "./tool-types.js";

export interface CoreToolDescriptor extends ModelToolDefinition {
  readonly id: CoreToolId;
  readonly argumentsSchema: string;
  readonly resultSchema: string;
  readonly source: ToolResultPayload["source"];
  readonly trust: ToolResultPayload["trust"];
}

interface DescriptorSource {
  readonly id: CoreToolId;
  readonly providerName: string;
  readonly description: string;
  readonly source: ToolResultPayload["source"];
  readonly trust: ToolResultPayload["trust"];
}

const descriptorSources = [
  {
    id: "player.context.read",
    providerName: "player_context_read",
    description:
      "Read the requesting player's current dimension, position, orientation, game mode, and environment context.",
    source: "paper_api",
    trust: "authoritative",
  },
  {
    id: "player.held_item.read",
    providerName: "player_held_item_read",
    description: "Read the requesting player's current main-hand and off-hand item stacks.",
    source: "paper_api",
    trust: "authoritative",
  },
  {
    id: "server.info.read",
    providerName: "server_info_read",
    description: "Read bounded current Minecraft server identity, version, and player-count facts.",
    source: "paper_api",
    trust: "authoritative",
  },
  {
    id: "server.plugins.list",
    providerName: "server_plugins_list",
    description: "List bounded public metadata for plugins currently loaded by the server.",
    source: "paper_api",
    trust: "authoritative",
  },
  {
    id: "server.recipe.lookup",
    providerName: "server_recipe_lookup",
    description:
      "Look up authoritative server recipes that produce the exact Minecraft item ID supplied.",
    source: "server_registry",
    trust: "authoritative",
  },
  {
    id: "server.recipe.uses",
    providerName: "server_recipe_uses",
    description:
      "Look up authoritative server recipes that use the exact Minecraft item ID supplied as an ingredient.",
    source: "server_registry",
    trust: "authoritative",
  },
] as const satisfies readonly DescriptorSource[];

function schemaReference(id: CoreToolId, kind: "arguments" | "result"): string {
  return `tools/${id.replaceAll(/[._]/gu, "-")}-${kind}.schema.json`;
}

function providerParameters(id: CoreToolId): Readonly<Record<string, unknown>> {
  const recipeTool = id === "server.recipe.lookup" || id === "server.recipe.uses";
  return {
    type: "object",
    properties: recipeTool
      ? {
          itemId: {
            type: "string",
            description: "A canonical namespaced Minecraft item ID.",
          },
        }
      : {},
    required: recipeTool ? ["itemId"] : [],
    additionalProperties: false,
  };
}

export class ToolRegistry {
  readonly #schemaRegistry: SchemaRegistry;
  readonly #byId = new Map<CoreToolId, CoreToolDescriptor>();
  readonly #byProviderName = new Map<string, CoreToolDescriptor>();

  public constructor(schemaRegistry: SchemaRegistry) {
    this.#schemaRegistry = schemaRegistry;
    for (const source of descriptorSources) {
      const argumentsSchema = schemaReference(source.id, "arguments");
      const resultSchema = schemaReference(source.id, "result");
      const descriptor: CoreToolDescriptor = Object.freeze({
        ...source,
        argumentsSchema,
        resultSchema,
        parameters: providerParameters(source.id),
      });
      if (
        this.#byId.set(source.id, descriptor).size !== this.#byProviderName.size + 1 ||
        this.#byProviderName.has(source.providerName)
      ) {
        throw new Error("Core Tool Registry contains a duplicate identity.");
      }
      this.#byProviderName.set(source.providerName, descriptor);
    }
    if (this.#byId.size !== coreToolIds.length) {
      throw new Error("Core Tool Registry is incomplete.");
    }
  }

  public list(): readonly CoreToolDescriptor[] {
    return coreToolIds.map((id) => this.#required(this.#byId.get(id)));
  }

  public forAllowlist(allowlist: readonly string[]): readonly CoreToolDescriptor[] {
    return allowlist.map((id) => {
      if (!coreToolIds.includes(id as CoreToolId)) {
        throw new Error("Module Tool allowlist contains an unknown Tool.");
      }
      return this.#required(this.#byId.get(id as CoreToolId));
    });
  }

  public byProviderName(providerName: string): CoreToolDescriptor | undefined {
    return this.#byProviderName.get(providerName);
  }

  public validateArguments(
    descriptor: CoreToolDescriptor,
    value: Readonly<Record<string, unknown>>,
  ): boolean {
    return this.#schemaRegistry.validate(descriptor.argumentsSchema, value).valid;
  }

  public validateResult(descriptor: CoreToolDescriptor, payload: ToolResultPayload): boolean {
    return (
      payload.status === "succeeded" &&
      payload.source === descriptor.source &&
      payload.trust === descriptor.trust &&
      payload.result !== null &&
      payload.error === null &&
      this.#schemaRegistry.validate(descriptor.resultSchema, payload.result).valid
    );
  }

  #required(descriptor: CoreToolDescriptor | undefined): CoreToolDescriptor {
    if (descriptor === undefined) {
      throw new Error("Core Tool Registry is incomplete.");
    }
    return descriptor;
  }
}
