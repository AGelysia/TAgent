import { pathToFileURL } from "node:url";

import Fastify, { type FastifyInstance } from "fastify";

import { runtimeIdentity, type RuntimeIdentity } from "../version.js";

export interface BootstrapResult {
  readonly app: FastifyInstance;
  readonly identity: RuntimeIdentity;
}

export function createRuntimeApp(): FastifyInstance {
  return Fastify({
    logger: false,
  });
}

export async function bootstrap(): Promise<BootstrapResult> {
  const app = createRuntimeApp();
  await app.ready();

  return {
    app,
    identity: runtimeIdentity,
  };
}

function isMainModule(): boolean {
  const entryPath = process.argv[1];
  return entryPath !== undefined && pathToFileURL(entryPath).href === import.meta.url;
}

if (isMainModule()) {
  const result = await bootstrap();
  process.stdout.write(`${result.identity.name} ${result.identity.version}\n`);
  await result.app.close();
}
