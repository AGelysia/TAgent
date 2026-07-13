export const moduleIds = ["general", "recipe", "guide", "locate", "build", "project"] as const;

export type ModuleId = (typeof moduleIds)[number];

export interface ModuleManifest {
  readonly id: ModuleId;
  readonly displayName: string;
  readonly instructions: string;
  readonly toolAllowlist: readonly string[];
}

const manifests = [
  {
    id: "general",
    displayName: "General",
    instructions:
      "Answer the player's Minecraft question as concise plain text. Use read-only server tools when live facts are needed. Never claim to have changed server or player state.",
    toolAllowlist: [
      "player.context.read",
      "player.held_item.read",
      "server.info.read",
      "server.plugins.list",
      "server.recipe.lookup",
      "server.recipe.uses",
    ],
  },
  {
    id: "recipe",
    displayName: "Recipe",
    instructions:
      "Answer the player's Minecraft crafting and recipe question as concise plain text. Use the read-only recipe tools for live recipe facts and preserve their authoritative details. Never invent a recipe grid or claim to have changed server state.",
    toolAllowlist: ["player.held_item.read", "server.recipe.lookup", "server.recipe.uses"],
  },
  {
    id: "guide",
    displayName: "Guide",
    instructions:
      "Give concise plain-text Minecraft gameplay guidance. Use read-only server tools when live facts are relevant, and distinguish those facts from general knowledge.",
    toolAllowlist: [
      "player.context.read",
      "player.held_item.read",
      "server.info.read",
      "server.plugins.list",
      "server.recipe.lookup",
      "server.recipe.uses",
    ],
  },
  {
    id: "locate",
    displayName: "Locate",
    instructions:
      "Give concise plain-text Minecraft navigation guidance. Use read-only context tools for live player or server facts. Do not claim that a landmark was found unless a tool supplied it.",
    toolAllowlist: ["player.context.read", "server.info.read"],
  },
  {
    id: "build",
    displayName: "Build",
    instructions:
      "Give concise plain-text Minecraft building advice. Read player or server context only when needed. Do not claim to have inspected, previewed, or changed the world.",
    toolAllowlist: ["player.context.read", "player.held_item.read", "server.info.read"],
  },
  {
    id: "project",
    displayName: "Project",
    instructions:
      "Give concise plain-text planning advice for a Minecraft project. Use read-only server context when relevant. Do not claim that a project or world state has been saved or changed.",
    toolAllowlist: ["player.context.read", "server.info.read", "server.plugins.list"],
  },
] as const satisfies readonly ModuleManifest[];

const manifestById = new Map<ModuleId, ModuleManifest>(
  manifests.map((manifest) => [manifest.id, Object.freeze(manifest)]),
);

export class ModuleRegistry {
  public list(): readonly ModuleManifest[] {
    return manifests;
  }

  public get(id: ModuleId): ModuleManifest {
    const manifest = manifestById.get(id);
    if (manifest === undefined) {
      throw new TypeError("Unknown Agent module.");
    }
    return manifest;
  }
}
