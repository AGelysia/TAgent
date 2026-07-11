# Architecture

## Status and scope

This document records the target architecture and the decisions required by
Phase 0 and Phase 1. The repository currently contains build scaffolding and
protocol contracts only. It does not yet contain a live Runtime-Paper
connection, model access, SQLite repositories, commands, tools, client views,
or Litematica integration.

The implementation has three deployable components:

```text
Player
  -> Paper plugin (authority and Minecraft adapter)
       <-> local Agent Runtime (reasoning and durable conversations)
       <-> optional Fabric client (presentation and local integrations)
  -> Minecraft world
```

The Paper plugin is the only execution authority. The Runtime proposes typed
operations but cannot execute Minecraft commands or mutate the world directly.
The Fabric client is untrusted presentation software and cannot grant
permission or confirm that server state is safe to modify.

## Locked compatibility baseline

Phase 0 locks the following baseline rather than tracking a moving `latest`
version:

| Component     | Locked value              |
| ------------- | ------------------------- |
| Minecraft     | 1.21.11                   |
| Paper API     | 1.21.11-R0.1-SNAPSHOT     |
| Fabric target | Minecraft 1.21.11         |
| Fabric API    | 0.141.4+1.21.11           |
| Fabric Loader | 0.19.3                    |
| Fabric Loom   | 1.17.13                   |
| JVM toolchain | Java 21                   |
| Runtime       | Node.js 22 and TypeScript |
| Wire protocol | 1.0                       |

Litematica and MaLiLib are deliberately not part of the Phase 0 dependency
graph. Their exact versions must be locked by a later compatibility adapter.
The base client must load when both mods are absent.

## Component boundaries

### Paper plugin

Paper will own:

- Command registration, command authorization, and the top-level Offline gate.
- Runtime authentication and protocol negotiation.
- Binding a network request to the actual server ID and connected player UUID.
- The effective tool catalog and final Tool Policy decision.
- All Paper API reads and writes, with server-thread scheduling at the adapter
  boundary.
- Proposal creation, frozen arguments, expiry, confirmation, and one-time
  execution.
- Capability Pack validation and the effective capability generation.
- Security audit records and immutable validated build artifacts.
- Client capability negotiation, payload limits, and sanitization of views.

Paper must reject a tool call unless it belongs to a live request originally
created by Paper and matches its request, session, player, module, catalog
generation, and tool schema.

Suggested package boundaries for later phases are:

```text
dev.minecraftagent.paper
  bootstrap        configuration and lifecycle wiring
  state            desired mode, operational state, health observations
  command          command tree, Offline gate, sender authorization
  transport        authenticated Runtime connection
  protocol         DTO conversion and schema validation
  tool             catalog and execution state machine
  policy           risk and permission intersection
  proposal         frozen proposals and confirmation
  capability       pack loading and effective registry
  minecraft        Paper API adapters for context, recipe, and build operations
  client           custom payload gateway and view sanitization
  storage          Paper-owned state, artifacts, and audit repositories
```

Domain and policy packages must not call Bukkit APIs directly. They receive
ports implemented by `minecraft`, `client`, or `storage`. This makes policy and
proposal logic testable without a running server.

### Agent Runtime

The Runtime will own:

- Provider adapters and the bounded model/tool loop.
- Session and message persistence, resume behavior, and context reduction.
- Module routing and the per-module tool allowlist.
- Usage accounting, budget reservations, and request rate limits.
- Fixed view-model builders; model output is never an arbitrary UI tree.
- Controlled document ingestion and retrieval under configured roots.
- Project intent and deterministic planner inputs.

Suggested TypeScript boundaries are:

```text
src
  bootstrap        process startup and dependency composition
  config           environment substitution and Zod validation
  protocol         schema registry, contract fixtures, and wire DTOs
  transport        Runtime-Paper WebSocket endpoint
  requests         request lifecycle, cancellation, and backpressure
  agent            bounded provider/tool loop
  providers        provider-specific adapters
  modules          module manifests and routing
  tools            effective catalog proxy and Runtime pre-policy
  sessions         conversations and context management
  storage          Runtime-owned repositories and migrations
  usage            token, cost, quota, and budget accounting
  rag              controlled document indexing and retrieval
  views            trusted view-model builders
  projects         project metadata and planner inputs
```

Runtime policy is defense in depth. It does not replace Paper's final policy.

### Fabric client

The optional client will own:

- Versioned capability negotiation over Minecraft custom payloads.
- Bounded payload reassembly away from the render loop.
- Local overlay state, input, item rendering, and player preferences.
- Exact-version Litematica adapters behind a small internal interface.

Suggested packages are `network`, `view`, `overlay`, `item`, `preferences`, and
`litematica`. No class in the base client may eagerly link a Litematica class.
A compatible adapter must be selected explicitly; otherwise only Litematica
features are disabled.

## Storage authority

Paper and Runtime must never write the same SQLite file. A shared SQLite file
would blur the security boundary and introduces two-process migration and lock
contention.

The planned split is:

| Paper-owned store                         | Runtime-owned store                |
| ----------------------------------------- | ---------------------------------- |
| Desired enabled state                     | Sessions and messages              |
| Proposals and execution status            | Requests and provider usage events |
| Security audit events                     | Daily usage projections            |
| Capability approvals and effective hashes | Project metadata and membership    |
| Validated build artifact references       | Landmarks and their ACL metadata   |
| Tool execution idempotency records        | Document index metadata            |

Paper may send audit or usage events to Runtime for display, but such a copy is
not authoritative. The two processes exchange IDs and hashes only through the
versioned protocol. SQLite itself is not introduced during Phase 0 or Phase 1.

## State and health

Operational state and health are separate dimensions.

```text
Operational state: UNREGISTERED | STARTING | ONLINE | STOPPING | OFFLINE
Desired mode:       ENABLED | DISABLED
Health:             HEALTHY | DEGRADED | UNAVAILABLE
```

`DEGRADED` is not another operational state. For example, an ONLINE service can
be DEGRADED because one optional Capability Pack or Litematica adapter is
unavailable. A lost authenticated Runtime connection makes the service
OFFLINE/UNAVAILABLE. Manual Offline persists `Desired mode = DISABLED`; a
transient health failure must not silently rewrite that preference.

The exact reconnect policy for non-manual failures is deferred until Phase 3.
Security and protocol incompatibility failures must never auto-recover without
a fresh authenticated self-check.

## Threading and resource rules

- No model, network, filesystem, compression, or database wait may run on the
  Paper server thread.
- Paper API access that requires the server thread is isolated behind a
  scheduler port. Only immutable snapshots leave that thread.
- Region reads and writes are bounded and spread according to a measured tick
  budget. Hash validation alone does not make a multi-tick write atomic.
- Runtime request concurrency, queue length, context size, and build size are
  configuration limits rather than assumptions.
- Client decompression and reassembly enforce compressed and decompressed byte
  limits before allocating large buffers. Rendering changes are scheduled onto
  the client render thread.

The repository is configured for a constrained build host: Gradle parallelism
is disabled, one worker is allowed, and the Gradle heap is capped. JVM and Node
checks should be run serially.

## Phase 3 command-registration spike

The product baseline says both that `/agent` is registered only after the core
startup self-check succeeds and that `/agent on` can recover an Offline agent.
Those requirements are not equivalent when the initial startup check fails:
there is no command through which recovery can be requested.

Paper command registration is lifecycle-bound, while the Runtime and model
checks are asynchronous network work. Phase 3 must prove one supported design
against the locked Paper version:

1. An official, safe late-registration path after asynchronous self-check; or
2. A bounded startup-only check followed by registration, accepting that a
   failed initial check requires an external fix and server restart; or
3. A narrowly redefined core check that allows a lifecycle-only `/agent on|off`
   surface without exposing the remaining command tree.

No implementation should silently fall back to a permanently registered full
command that merely reports "not ready". The chosen behavior requires an ADR
and integration test before Phase 3 is considered complete.

## Current non-features

Phase 0-1 explicitly do not implement:

- Model API calls or provider health checks.
- Runtime-Paper WebSocket listening or authentication enforcement.
- Runtime or Paper SQLite databases.
- `/agent` commands, Offline transitions, tools, proposals, or permissions.
- Capability Pack loading or command execution.
- Client payload networking, overlay UI, or item views.
- Recipe, locate, guide, project, build, or Litematica behavior.

Schemas in the repository define future contracts; they are not evidence that
the corresponding behavior is active.
