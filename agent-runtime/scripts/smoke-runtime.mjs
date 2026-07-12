import { startRuntime } from "../dist/bootstrap/index.js";

const configPath = process.argv[2];
if (configPath === undefined) {
  throw new Error("Usage: node scripts/smoke-runtime.mjs <config-path>");
}

const runtime = await startRuntime({
  configPath,
  modelProviderHealthCheck: {
    async check() {
      return { ok: true };
    },
  },
});

process.stdout.write("PAPER_SMOKE_RUNTIME_READY\n");

let stopping = false;
async function stop() {
  if (stopping) {
    return;
  }
  stopping = true;
  await runtime.close();
}

process.once("SIGINT", stop);
process.once("SIGTERM", stop);
