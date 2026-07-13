export const coreToolIds = [
  "player.context.read",
  "player.held_item.read",
  "server.info.read",
  "server.plugins.list",
  "server.recipe.lookup",
  "server.recipe.uses",
] as const;

export type CoreToolId = (typeof coreToolIds)[number];

export type ToolResultStatus = "succeeded" | "rejected" | "failed";
export type ToolResultSource =
  | "paper_api"
  | "paper_policy"
  | "server_registry"
  | "plugin_provider"
  | "server_docs"
  | "web_documentation"
  | "model_knowledge"
  | "capability";
export type ToolResultTrust = "authoritative" | "verified" | "untrusted";

export interface ToolResultError {
  readonly code: string;
  readonly message: string;
  readonly retryable: boolean;
}

export interface ToolResultPayload {
  readonly toolCallId: string;
  readonly sessionId: string;
  readonly playerUuid: string;
  readonly tool: string;
  readonly sequence: number;
  readonly status: ToolResultStatus;
  readonly source: ToolResultSource;
  readonly trust: ToolResultTrust;
  readonly result: Readonly<Record<string, unknown>> | null;
  readonly error: ToolResultError | null;
}

export interface ToolCallPayload {
  readonly toolCallId: string;
  readonly sessionId: string;
  readonly playerUuid: string;
  readonly module: string;
  readonly tool: CoreToolId;
  readonly arguments: Readonly<Record<string, unknown>>;
  readonly sequence: number;
}
