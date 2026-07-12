# Minecraft Agent

Minecraft Agent is a security-first Minecraft assistant composed of a Paper plugin, a local
TypeScript runtime, and an optional Fabric client mod. This repository currently implements the
Phase 0-4 foundation: shared protocol contracts, fail-closed readiness checks, an authenticated
loopback Runtime-Paper handshake, conditional `/agent` registration, and persistent emergency
Offline controls. It does not yet call a model, execute a tool, or mutate Minecraft state.

The product and delivery baseline is recorded in
[`minecraft_agent_vibe_coding_plan.md`](minecraft_agent_vibe_coding_plan.md). Implementation status
is tracked in [`docs/progress.md`](docs/progress.md).

## Version baseline

| Component     | Version               |
| ------------- | --------------------- |
| Java          | 21                    |
| Minecraft     | 1.21.11               |
| Paper API     | 1.21.11-R0.1-SNAPSHOT |
| Fabric Loader | 0.19.3                |
| Fabric API    | 0.141.4+1.21.11       |
| Node.js       | 22.16-22.x            |

Minecraft 1.21.11 is intentionally pinned because the product baseline requires Java 21. Paper
26.x requires Java 25 and is outside this compatibility line. Paper publishes the 1.21.11 API only
through a mutable snapshot coordinate, so release builds must retain Gradle dependency verification
metadata before they can be called byte-for-byte reproducible.

## Repository layout

- `protocol/`: the single source of truth for JSON Schema and contract fixtures.
- `paper-plugin/`: the authoritative server-side security and execution boundary.
- `agent-runtime/`: local TypeScript process for routing, model access, and persistence.
- `client-mod/`: optional, client-only Fabric presentation layer.
- `capability-packs/`: reviewed capability manifests; never an arbitrary command interface.
- `docs/`: architecture, security, protocol, operations, and progress records.
- `scripts/`: resource-conscious development, verification, and packaging commands.

## Prerequisites

- JDK 21
- Node.js 22.16 or newer in the 22.x line, and npm 10
- Bash or PowerShell

Do not install a system Gradle. The checked-in wrapper pins the build tool. The default Gradle
settings use one worker and a 768 MiB heap so the projects can build on a small server.

## Verify

Run all checks sequentially:

```bash
./scripts/test.sh
```

Or run each project explicitly:

```bash
cd agent-runtime
npm ci
npm run format:check
npm run lint
npm test
npm run build

cd ..
./gradlew --no-daemon --max-workers=1 :paper-plugin:build
./gradlew --no-daemon --max-workers=1 :client-mod:build
```

The Fabric build downloads Minecraft artifacts and is deliberately last. Do not run both Gradle
builds concurrently on a low-memory host.

## Phase 2-4 Runtime

```bash
cd agent-runtime
npm run version
npm run check
```

[`agent-runtime/config.example.yml`](agent-runtime/config.example.yml) is the strict configuration
template. Runtime secrets are resolved after YAML parsing from whole-value `${ENV_NAME}` references;
`.env.example` is documentation only and is not loaded automatically. Keep a local configuration at
mode `0600`, inject secrets from the shell or service manager, and start with:

```bash
npm start -- --config config.local.yml
```

Startup checks the configuration, private log directory, shared Capability Schema, Runtime-owned
SQLite file, and an injected model-provider health adapter before binding `127.0.0.1`. `/health`
returns a cached minimal readiness view and does not repeat provider or database work.

Phase 2 deliberately has no production provider network adapter. The default CLI therefore exits
nonzero with `PROVIDER_UNSUPPORTED` after local checks rather than claiming `READY`; tests inject a
fake adapter to verify the final listen gate. Once every readiness check succeeds, Phase 3 exposes
`/agent` on that same loopback listener and accepts only the bounded authenticated hello exchange.
It does not yet accept agent requests, tool calls, heartbeats, or any other reserved message type.

## Phase 3-4 Paper

On first Paper startup, the plugin installs a strict `plugins/MinecraftAgent/config.yml`. Keep its
Runtime token as the complete `${MINECRAFT_AGENT_SERVER_TOKEN}` environment reference. The endpoint
is restricted to `ws://127.0.0.1:<port>/agent`, state stays under the plugin data directory, and the
target server must be Minecraft/Paper 1.21.11 on Java 21 or newer.

The default `owners: []` permits only the local server console to run `/agent on` or
`/agent off`. Add canonical player UUIDs to `owners` for player administration. Setting
`security.allow-op-toggle: true` additionally permits a live OP with
`minecraftagent.admin.toggle`; it remains false by default.

Paper performs configuration, state, policy, descriptor, and Runtime authentication checks away
from the server thread. Only a successful result returns to the primary thread and registers
`agent` plus `minecraftagent:agent` through Paper's public command map. A core failure leaves both
labels absent; fix the external cause and restart the server. `/agent doctor` reports stable health
and optional warning codes. The six core tool entries are non-executable readiness descriptors,
not working Minecraft tools.

After initial registration, `/agent off` closes admission before it cancels transient work and
atomically persists `DISABLED` in `plugins/MinecraftAgent/state/agent-state.yml`. While not ONLINE,
every non-toggle command returns exactly `AI offline`. `/agent on` repeats the full local check and
authenticated handshake, then persists `ENABLED` before publishing ONLINE. A Runtime disconnect
moves the Agent Offline without changing the persisted desired mode, and the command remains
available for an explicit recovery attempt. An initial startup failure still leaves the command
absent and requires an external fix plus restart.

The opt-in exact-server smoke pins Paper `1.21.11-132`, verifies its SHA-256, limits the heap to 512
MiB, and runs every case serially:

```bash
./scripts/paper-smoke.sh
```

This is intentionally separate from routine checks because it downloads and starts a real Paper
server. It cleans up all temporary worlds, logs, credentials, and child processes.
The smoke includes an Offline/restart/on/Runtime-loss/on lifecycle and checks clean dynamic command
unregistration in addition to the three initial transport-failure cases.

## Package

```bash
./scripts/package.sh
```

Artifacts are placed under `dist/`. This packages only implemented skeletons; it does not claim a
deployable end-to-end assistant.

The packaged Runtime preserves its compiled layout and includes the shared protocol schemas and
configuration template. Install production dependencies before exercising its fail-closed startup:

```bash
cd dist/agent-runtime
npm ci --omit=dev
cd ..
./start-agent.sh --config config.example.yml
```

The top-level start scripts forward `--config`. They do not load `.env`; provide environment values
through the invoking shell or service manager. Until a production provider adapter lands, startup is
expected to end with `PROVIDER_UNSUPPORTED` and no listening port.

## Security baseline

The Paper plugin is the final authorization and execution boundary. The project never exposes a
general console, shell, script, reflection, or unrestricted file tool. The optional client and all
model output are untrusted. `/agent` is deliberately absent from `paper-plugin.yml`; Phase 3 creates
it dynamically only after the complete core readiness gate succeeds. Phase 4 keeps the registered
command during later Offline transitions, rotates an epoch permit before cleanup, and requires a
fresh check before returning Online.
