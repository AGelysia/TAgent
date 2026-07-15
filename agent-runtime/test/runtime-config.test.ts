import { chmod, rm, symlink } from "node:fs/promises";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { RuntimeStartupError } from "../src/bootstrap/startup-error.js";
import { loadRuntimeConfig } from "../src/config/runtime-config.js";
import {
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  TEST_API_KEY,
  validRuntimeConfig,
  writeRuntimeConfig,
} from "./helpers/runtime-fixture.js";

const temporaryDirectories: string[] = [];

async function fixtureDirectory(): Promise<string> {
  const directory = await temporaryRuntimeDirectory();
  temporaryDirectories.push(directory);
  return directory;
}

async function expectStartupError(
  operation: Promise<unknown>,
  code: RuntimeStartupError["code"],
  field?: string,
): Promise<RuntimeStartupError> {
  try {
    await operation;
  } catch (error) {
    expect(error).toBeInstanceOf(RuntimeStartupError);
    const startupError = error as RuntimeStartupError;
    expect(startupError.code).toBe(code);
    if (field !== undefined) {
      expect(startupError.field).toBe(field);
    }
    return startupError;
  }
  throw new Error("Expected RuntimeStartupError");
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("runtime configuration", () => {
  it("loads strict YAML, substitutes whole environment scalars, and resolves local paths", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);

    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });

    expect(loaded.config.configVersion).toBe(2);
    expect(loaded.config.model.apiKey).toBe(TEST_API_KEY);
    expect(loaded.config.model.inputMicroUsdPerMillionTokens).toBe(1_000_000);
    expect(loaded.config.model.outputMicroUsdPerMillionTokens).toBe(4_000_000);
    expect(loaded.config.limits.providerRoundReservationMicroUsd).toBe(50_000);
    expect(loaded.config.transport.host).toBe("127.0.0.1");
    expect(loaded.paths.rootDirectory).toBe(directory);
    expect(loaded.paths.sqlite).toBe(join(directory, "data/runtime.db"));
    expect(loaded.paths.logDirectory).toBe(join(directory, "logs"));
    expect(loaded.paths.knowledgeRoots).toEqual([]);
    expect(loaded.warnings).toEqual([]);
  });

  it("rejects legacy configVersion 1 with a stable upgrade diagnostic", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("configVersion: 2", "configVersion: 1"),
      "legacy-v1.yml",
    );

    const error = await expectStartupError(
      loadRuntimeConfig({ configPath, environment: runtimeEnvironment() }),
      "CONFIG_VERSION_UNSUPPORTED",
      "/configVersion",
    );
    expect(error.toSafeDiagnostic()).toEqual({
      code: "CONFIG_VERSION_UNSUPPORTED",
      stage: "config",
      field: "/configVersion",
      message:
        "Runtime configuration version 1 is no longer supported. Upgrade to configVersion 2.",
    });
  });

  it("resolves optional bounded knowledge roots without changing legacy configuration", async () => {
    const directory = await fixtureDirectory();
    const source = validRuntimeConfig().replace(
      "limits:\n",
      "knowledge:\n  roots:\n    - directory: ./knowledge/rules\n      kind: server_rules\n    - directory: ./knowledge/docs\n      kind: local_docs\nlimits:\n",
    );
    const configPath = await writeRuntimeConfig(directory, source, "knowledge.yml");

    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });
    expect(loaded.paths.knowledgeRoots).toEqual([
      { directory: join(directory, "knowledge/rules"), kind: "server_rules" },
      { directory: join(directory, "knowledge/docs"), kind: "local_docs" },
    ]);
  });

  it("uses stable secret errors for missing environment values", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);

    await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ OPENAI_API_KEY: undefined }),
      }),
      "API_KEY_MISSING",
      "/model/apiKey",
    );
  });

  it("reports concrete known fields without echoing untrusted unknown key names", async () => {
    const directory = await fixtureDirectory();
    const invalidPort = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("port: 38127", "port: not-a-number"),
      "invalid-port.yml",
    );
    const unknownSecretKey = `unknown-${TEST_API_KEY}`;
    const unknownKey = await writeRuntimeConfig(
      directory,
      `${validRuntimeConfig()}${unknownSecretKey}: true\n`,
      "unknown-key.yml",
    );

    await expectStartupError(
      loadRuntimeConfig({ configPath: invalidPort, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/transport/port",
    );
    const unknownError = await expectStartupError(
      loadRuntimeConfig({ configPath: unknownKey, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/",
    );
    expect(JSON.stringify(unknownError.toSafeDiagnostic())).not.toContain(unknownSecretKey);
  });

  it("requires bounded micro-USD pricing and an exact monthly budget", async () => {
    const directory = await fixtureDirectory();
    const fractionalBudget = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("monthlyBudgetUsd: 10", "monthlyBudgetUsd: 0.0000001"),
      "fractional-budget.yml",
    );
    const zeroReservation = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace(
        "providerRoundReservationMicroUsd: 50000",
        "providerRoundReservationMicroUsd: 0",
      ),
      "zero-reservation.yml",
    );

    await expectStartupError(
      loadRuntimeConfig({ configPath: fractionalBudget, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/limits/monthlyBudgetUsd",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: zeroReservation, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/limits/providerRoundReservationMicroUsd",
    );
  });

  it("rejects partial, recursive, duplicate, and path-escape configuration syntax", async () => {
    const directory = await fixtureDirectory();
    const partial = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("${OPENAI_API_KEY}", "prefix-${OPENAI_API_KEY}"),
      "partial.yml",
    );
    const recursive = await writeRuntimeConfig(directory, validRuntimeConfig(), "recursive.yml");
    const duplicate = await writeRuntimeConfig(
      directory,
      `${validRuntimeConfig()}server:\n  id: duplicate\n`,
      "duplicate.yml",
    );
    const alias = await writeRuntimeConfig(
      directory,
      "value: &shared [1]\ncopy: *shared\n",
      "alias.yml",
    );
    const escape = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("./data/runtime.db", "../runtime.db"),
      "escape.yml",
    );

    await expectStartupError(
      loadRuntimeConfig({ configPath: partial, environment: runtimeEnvironment() }),
      "CONFIG_ENV_SYNTAX",
      "/model/apiKey",
    );
    await expectStartupError(
      loadRuntimeConfig({
        configPath: recursive,
        environment: runtimeEnvironment({ OPENAI_API_KEY: "${SECOND}" }),
      }),
      "CONFIG_ENV_SYNTAX",
      "/model/apiKey",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: duplicate, environment: runtimeEnvironment() }),
      "CONFIG_PARSE_FAILED",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: alias, environment: runtimeEnvironment() }),
      "CONFIG_PARSE_FAILED",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: escape, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/storage/sqlitePath",
    );
  });

  it("bounds file size and deeply nested YAML before configuration traversal", async () => {
    const directory = await fixtureDirectory();
    const deep = await writeRuntimeConfig(
      directory,
      `${"[".repeat(5000)}1${"]".repeat(5000)}`,
      "deep.yml",
    );
    const oversized = await writeRuntimeConfig(
      directory,
      `#${"x".repeat(64 * 1024)}\n`,
      "oversized.yml",
    );

    await expectStartupError(
      loadRuntimeConfig({ configPath: deep, environment: runtimeEnvironment() }),
      "CONFIG_PARSE_FAILED",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: oversized, environment: runtimeEnvironment() }),
      "CONFIG_TOO_LARGE",
    );
  });

  it("treats structural text from an environment variable as a scalar, then validates it", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);

    await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ OPENAI_API_KEY: "key\nunexpected: injected" }),
      }),
      "API_KEY_MISSING",
      "/model/apiKey",
    );
  });

  it("rejects placeholder and reused secrets without exposing their values", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);

    const placeholder = await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ OPENAI_API_KEY: "replace-with-real-key" }),
      }),
      "SECRET_PLACEHOLDER",
      "/model/apiKey",
    );
    const reused = await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ MINECRAFT_AGENT_SERVER_TOKEN: TEST_API_KEY }),
      }),
      "SECRET_REUSE",
      "/transport/serverToken",
    );

    expect(JSON.stringify(placeholder.toSafeDiagnostic())).not.toContain("replace-with-real-key");
    expect(JSON.stringify(reused.toSafeDiagnostic())).not.toContain(TEST_API_KEY);
  });

  it("warns separately but rejects broad permissions combined with inline secrets", async () => {
    const directory = await fixtureDirectory();
    const inlineSource = validRuntimeConfig()
      .replace("${OPENAI_API_KEY}", TEST_API_KEY)
      .replace(
        "${MINECRAFT_AGENT_SERVER_TOKEN}",
        "inline-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      );
    const target = await writeRuntimeConfig(directory, inlineSource, "target.yml");

    const loaded = await loadRuntimeConfig({ configPath: target, environment: {} });
    expect(loaded.warnings.map((warning) => warning.code)).toEqual([
      "CONFIG_INLINE_SECRET",
      "CONFIG_INLINE_SECRET",
    ]);

    const broad = await writeRuntimeConfig(directory, validRuntimeConfig(), "broad.yml");
    await chmod(broad, 0o644);
    const broadLoaded = await loadRuntimeConfig({
      configPath: broad,
      environment: runtimeEnvironment(),
    });
    expect(broadLoaded.warnings).toEqual([{ code: "CONFIG_FILE_PERMISSIONS_WIDE" }]);

    await chmod(target, 0o644);
    await expectStartupError(
      loadRuntimeConfig({ configPath: target, environment: {} }),
      "CONFIG_INSECURE_PERMISSIONS",
      "/model/apiKey",
    );

    const link = join(directory, "linked.yml");
    await symlink(target, link);
    await expectStartupError(
      loadRuntimeConfig({ configPath: link, environment: runtimeEnvironment() }),
      "CONFIG_PATH_SYMLINK",
    );
  });

  it("rejects writable configuration and state paths rooted beside the config", async () => {
    const directory = await fixtureDirectory();
    const writable = await writeRuntimeConfig(directory, validRuntimeConfig(), "writable.yml");
    await chmod(writable, 0o660);
    await expectStartupError(
      loadRuntimeConfig({ configPath: writable, environment: runtimeEnvironment() }),
      "CONFIG_INSECURE_PERMISSIONS",
    );

    const rootLog = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("directory: ./logs", "directory: ."),
      "root-log.yml",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: rootLog, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/logging/directory",
    );

    const rootDatabase = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("./data/runtime.db", "runtime.db"),
      "root-database.yml",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: rootDatabase, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/storage/sqlitePath",
    );
  });
});
