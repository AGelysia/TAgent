# Minecraft Agent

Minecraft Agent is a security-first Minecraft assistant composed of a Paper plugin, a local
TypeScript runtime, and an optional Fabric client mod. This repository currently implements the
Phase 0/1 foundation: reproducible project skeletons and a shared protocol contract. It does not
yet call a model, open a runtime transport, register `/agent`, or mutate Minecraft state.

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
| Node.js       | 22.x                  |

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
- Node.js 22 and npm 10
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

## Run the Phase 0 runtime

```bash
cd agent-runtime
npm run version
npm run dev
```

The current bootstrap prints its version and exits without listening on a port. Runtime self-check,
configuration, SQLite, and local WebSocket transport belong to later phases.

## Package

```bash
./scripts/package.sh
```

Artifacts are placed under `dist/`. This packages only implemented skeletons; it does not claim a
deployable end-to-end assistant.

The packaged Runtime preserves its `dist/` layout and includes the shared protocol schemas. Install
production dependencies before running that artifact:

```bash
cd dist/agent-runtime
npm ci --omit=dev
cd ..
./start-agent.sh
```

## Security baseline

The Paper plugin is the final authorization and execution boundary. The project never exposes a
general console, shell, script, reflection, or unrestricted file tool. The optional client and all
model output are untrusted. No `/agent` command is declared in the Phase 0 plugin descriptor because
later conditional registration must remain fail closed.
