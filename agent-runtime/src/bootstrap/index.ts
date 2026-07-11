import { pathToFileURL } from "node:url";

import Fastify, { type FastifyInstance } from "fastify";

import { asRuntimeStartupError, RuntimeStartupError } from "./startup-error.js";
import { loadRuntimeConfig, type LoadRuntimeConfigOptions } from "../config/runtime-config.js";
import { checkLogDirectory } from "../health/filesystem.js";
import {
  checkModelProvider,
  type ModelProviderHealthCheck,
  UnsupportedProductionProviderHealthCheck,
} from "../health/model-provider.js";
import { registerHealthRoute, RuntimeHealthState } from "../health/runtime-health.js";
import { checkRuntimeSqlite, type RuntimeSqliteHandle } from "../health/sqlite.js";
import { RuntimeLogger } from "../observability/runtime-logger.js";
import { SchemaRegistry } from "../protocol/schema-registry.js";
import { runtimeIdentity, type RuntimeIdentity } from "../version.js";

export interface BootstrapOptions extends LoadRuntimeConfigOptions {
  readonly logger?: RuntimeLogger;
  readonly modelProviderHealthCheck?: ModelProviderHealthCheck;
  readonly protocolRoot?: string;
  readonly now?: () => Date;
}

export interface RuntimeListenAddress {
  readonly host: "127.0.0.1";
  readonly port: number;
}

export interface BootstrapResult {
  readonly app: FastifyInstance;
  readonly identity: RuntimeIdentity;
  readonly health: RuntimeHealthState;
  readonly listenAddress: RuntimeListenAddress;
}

export interface StartRuntimeResult extends BootstrapResult {
  close(): Promise<void>;
}

export function createRuntimeApp(health?: RuntimeHealthState): FastifyInstance {
  const app = Fastify({ logger: false });
  if (health !== undefined) {
    registerHealthRoute(app, health);
  }
  return app;
}

async function checkProtocolSchema(protocolRoot?: string): Promise<void> {
  try {
    const registry = await SchemaRegistry.load(protocolRoot);
    if (!registry.schemaReferences.includes("capability.schema.json")) {
      throw new Error("Capability schema alias is unavailable");
    }
  } catch (error) {
    throw new RuntimeStartupError({
      code: "PROTOCOL_SCHEMA_UNAVAILABLE",
      stage: "protocol",
      safeMessage: "Capability protocol schema could not be loaded.",
      cause: error,
    });
  }
}

export async function bootstrap(options: BootstrapOptions = {}): Promise<BootstrapResult> {
  const logger = options.logger ?? new RuntimeLogger();
  let sqlite: RuntimeSqliteHandle | undefined;
  let app: FastifyInstance | undefined;

  try {
    const loaded = await loadRuntimeConfig(options);
    for (const warning of loaded.warnings) {
      logger.configWarning(warning);
    }

    await checkLogDirectory(loaded.paths.rootDirectory, loaded.paths.logDirectory);
    await checkProtocolSchema(options.protocolRoot);
    sqlite = await checkRuntimeSqlite(loaded.paths.rootDirectory, loaded.paths.sqlite);

    const providerHealthCheck =
      options.modelProviderHealthCheck ?? new UnsupportedProductionProviderHealthCheck();
    await checkModelProvider(loaded.config, providerHealthCheck);

    const health = new RuntimeHealthState(options.now?.().toISOString());
    app = createRuntimeApp(health);
    app.addHook("onClose", async () => {
      health.markStopped();
      sqlite?.close();
    });
    await app.ready();

    return {
      app,
      identity: runtimeIdentity,
      health,
      listenAddress: {
        host: loaded.config.transport.host,
        port: loaded.config.transport.port,
      },
    };
  } catch (error) {
    await app?.close().catch(() => undefined);
    try {
      sqlite?.close();
    } catch {
      // Preserve the original stable startup error when cleanup also fails.
    }
    throw asRuntimeStartupError(error);
  }
}

export async function startRuntime(options: BootstrapOptions = {}): Promise<StartRuntimeResult> {
  const logger = options.logger ?? new RuntimeLogger();
  const result = await bootstrap({ ...options, logger });
  let listenerBound = false;

  try {
    await result.app.listen(result.listenAddress);
    listenerBound = true;
    logger.ready(result.listenAddress.port);
    result.health.markReady();
  } catch (error) {
    await result.app.close().catch(() => undefined);
    throw new RuntimeStartupError({
      code: listenerBound ? "STARTUP_INTERNAL_ERROR" : "LISTEN_FAILED",
      stage: listenerBound ? "startup" : "listen",
      safeMessage: listenerBound
        ? "Runtime failed while completing startup."
        : "Runtime could not bind its local listening port.",
      cause: error,
    });
  }

  return {
    ...result,
    close: async () => {
      await result.app.close();
    },
  };
}

function isMainModule(): boolean {
  const entryPath = process.argv[1];
  return entryPath !== undefined && pathToFileURL(entryPath).href === import.meta.url;
}

function cliConfigPath(arguments_: readonly string[]): string | undefined {
  if (arguments_.length === 0) {
    return undefined;
  }
  if (arguments_.length === 2 && arguments_[0] === "--config" && arguments_[1] !== undefined) {
    return arguments_[1];
  }

  throw new RuntimeStartupError({
    code: "CONFIG_PATH_INVALID",
    stage: "config",
    safeMessage: "Usage: minecraft-agent-runtime [--config <path>]",
  });
}

async function runMain(): Promise<void> {
  const logger = new RuntimeLogger();
  let runtime: StartRuntimeResult;
  try {
    const configPath = cliConfigPath(process.argv.slice(2));
    runtime = await startRuntime({
      logger,
      ...(configPath === undefined ? {} : { configPath }),
    });
  } catch (error) {
    logger.startupFailure(asRuntimeStartupError(error));
    process.exitCode = 1;
    return;
  }

  let stopping = false;
  const stop = async (): Promise<void> => {
    if (stopping) {
      return;
    }
    stopping = true;
    process.off("SIGINT", stop);
    process.off("SIGTERM", stop);
    await runtime.close();
    logger.stopped();
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
}

if (isMainModule()) {
  await runMain();
}
