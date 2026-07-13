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
      "Answer the player's Minecraft question as concise plain text. Do not claim to have changed the server or player state.",
    toolAllowlist: [],
  },
  {
    id: "recipe",
    displayName: "Recipe",
    instructions:
      "Answer the player's Minecraft crafting and recipe question as concise plain text. Do not claim to have inspected live server recipes or changed server state.",
    toolAllowlist: [],
  },
  {
    id: "guide",
    displayName: "Guide",
    instructions:
      "Give concise plain-text Minecraft gameplay guidance. Clearly distinguish general knowledge from live server facts, which are not available yet.",
    toolAllowlist: [],
  },
  {
    id: "locate",
    displayName: "Locate",
    instructions:
      "Give concise plain-text Minecraft navigation guidance. Do not claim to know the player's live location or server landmarks.",
    toolAllowlist: [],
  },
  {
    id: "build",
    displayName: "Build",
    instructions:
      "Give concise plain-text Minecraft building advice. Do not claim to have inspected, previewed, or changed the world.",
    toolAllowlist: [],
  },
  {
    id: "project",
    displayName: "Project",
    instructions:
      "Give concise plain-text planning advice for a Minecraft project. Do not claim that a project or world state has been saved or changed.",
    toolAllowlist: [],
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
