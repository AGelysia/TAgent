import type { DatabaseSync } from "node:sqlite";

export const CURRENT_RUNTIME_SCHEMA_VERSION = 1;

const MIGRATION_ONE = `
  CREATE TABLE sessions (
    id TEXT PRIMARY KEY NOT NULL,
    server_id TEXT NOT NULL CHECK(length(server_id) BETWEEN 1 AND 64),
    player_uuid TEXT NOT NULL CHECK(length(player_uuid) = 36),
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'ARCHIVED')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    updated_sequence INTEGER NOT NULL DEFAULT 0 CHECK(updated_sequence >= 0)
  ) STRICT;
  CREATE INDEX sessions_owner_updated
    ON sessions(server_id, player_uuid, status, updated_sequence DESC, id DESC);

  CREATE TABLE messages (
    sequence INTEGER PRIMARY KEY,
    id TEXT UNIQUE NOT NULL CHECK(length(id) = 36),
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL CHECK(length(request_id) = 36),
    role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
    module TEXT NOT NULL CHECK(module IN ('general', 'recipe', 'guide', 'locate', 'build', 'project')),
    content TEXT NOT NULL CHECK(
      (role = 'user' AND length(content) BETWEEN 1 AND 4096)
      OR (role = 'assistant' AND length(content) BETWEEN 1 AND 8192)
    ),
    created_at TEXT NOT NULL,
    UNIQUE(request_id, role)
  ) STRICT;
  CREATE INDEX messages_session_sequence ON messages(session_id, sequence DESC);
`;

export function migrateRuntimeStorage(database: DatabaseSync, appliedAt: string): void {
  if (!Number.isFinite(Date.parse(appliedAt))) {
    throw new TypeError("Migration timestamp is invalid.");
  }

  database.exec("BEGIN IMMEDIATE");
  try {
    database.exec(`
      CREATE TABLE IF NOT EXISTS runtime_schema_migrations (
        version INTEGER PRIMARY KEY,
        name TEXT UNIQUE NOT NULL,
        applied_at TEXT NOT NULL
      ) STRICT
    `);
    const rows = database
      .prepare("SELECT version FROM runtime_schema_migrations ORDER BY version")
      .all();
    const versions = rows.map((row) => Number(row["version"]));
    if (
      versions.some(
        (version, index) =>
          !Number.isSafeInteger(version) ||
          version !== index + 1 ||
          version > CURRENT_RUNTIME_SCHEMA_VERSION,
      )
    ) {
      throw new Error("Runtime SQLite schema version is unsupported.");
    }

    if (versions.length === 0) {
      database.exec(MIGRATION_ONE);
      database
        .prepare(
          "INSERT INTO runtime_schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
        )
        .run(1, "sessions-and-messages", appliedAt);
    }
    database.exec("COMMIT");
  } catch (error) {
    if (database.isTransaction) {
      database.exec("ROLLBACK");
    }
    throw error;
  }
}
