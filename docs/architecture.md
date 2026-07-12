# Architecture

## Status and scope

This document records the target architecture and decisions through Phase 4.
The repository contains build scaffolding, protocol contracts, the Runtime
configuration/readiness boundary, and Paper-side Phase 3 startup, authenticated
hello, core-descriptor, conditional-command, and Phase 4 Offline lifecycle
components. Production model access, operational tool execution, application
repositories, client views, and Litematica integration remain later work. The
conditional command and Offline recovery paths have been validated on the pinned
Paper `1.21.11-132` server artifact.

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

Phase 3's `CoreToolRuntime` is deliberately narrower than the future `tool`
package. It validates six required read-only, closed-schema descriptors and
reports readiness, but has no invocation API and marks every descriptor
`executionCapable=false`. Typed Minecraft tool implementations are deferred to
the Phase 7 milestone. The Phase 3 optional capability-directory inspection is
not Capability Pack loading; the loader and effective capability registry
remain Phase 9 work.

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

Phase 2 implements only the Runtime startup boundary. It parses restricted YAML,
performs post-parse whole-scalar environment substitution, validates with Zod,
checks private state paths and Capability Schema availability, opens a
Runtime-owned SQLite readiness connection, and invokes an injected provider
health port. The final loopback `listen()` is the port check; no pre-bind probe is
used. Only a successful final bind changes local health from `STARTING` to
`READY`.

The production provider health implementation is intentionally absent. The
default adapter fails with `PROVIDER_UNSUPPORTED`; test-only injection proves
ordering without creating a configurable fake-health bypass.

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
versioned protocol. Phase 2 creates only the Runtime-owned readiness database
file and holds its checked connection. Tables, migrations, and repositories for
the planned Runtime data remain unimplemented; Paper still opens no database.

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

An initial self-check failure remains `UNREGISTERED`: the operator fixes the
external cause and restarts the server. There is no `/agent on` command when the
command was never registered. Phase 4 defines recovery after a successful
initial registration later enters Offline state. Security and protocol
incompatibility failures must never auto-recover without a fresh authenticated
self-check.

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

## Phase 3 conditional command registration

[ADR 0001](adr/0001-phase3-conditional-command-registration.md) selects a public
late-registration path. Paper performs its bounded core self-check away from the
server thread, returns to the primary thread, preflights both `agent` and
`minecraftagent:agent`, and calls `Server#getCommandMap().register(...)` only on
success. The return value and both label mappings must identify the exact
command instance. Failure and disable cleanup remove mappings by that same
identity, and successful registration calls `Player.updateCommands()` for
players already online.

The design rejects static command declaration, post-enable misuse of lifecycle
helpers, server-thread network waits, permanent not-ready commands, reflection,
and internal server command APIs. The real Paper `1.21.11-132` smoke verifies
late registration, both labels, command dispatch, and absence under the three
core transport failures. Unit tests separately verify player refresh calls,
identity cleanup, and disable races; an actual connected-client refresh remains
a later end-to-end test gap.

## Phase 4 Offline lifecycle

[ADR 0002](adr/0002-phase4-offline-state-machine.md) separates operational
state, desired mode, and health. The coordinator owns each connection candidate
until a generation-checked primary-thread commit transfers it to the active
lease. Initial success registers the command once; manual off, Runtime loss, and
failed recovery never unregister it. Plugin disable is the only post-registration
unregistration path.

`OperationalGate` holds the authoritative admission epoch. Every transition
rotates the epoch; permits are issued only ONLINE and revalidation requires both
ONLINE state and the exact issuing epoch. STOPPING therefore closes command and
future request/tool/proposal/client admission before cleanup or filesystem I/O.
The four explicit cleanup ports exist now, while their request, proposal,
operation, and client producers arrive in later phases.

Paper persists only `DesiredMode` in a strict private state file. Transient
health, Runtime failure, connection identity, and Offline reason are never
written as user intent. Recovery repeats the same core check and handshake, then
atomically persists ENABLED before publishing ONLINE. Runtime loss retains
desired ENABLED and requires an explicit `/agent on`; there is no Phase 4
auto-reconnect.

## Current Phase 4 boundary

The following remain outside the Phase 4 implementation boundary:

- Production provider health requests and model calls. The Runtime WebSocket
  endpoint implements authentication only; agent and tool traffic remain
  unsupported.
- Runtime application repositories/migrations and every Paper database.
- `/agent` request routing, proposals, and tool execution. The current command
  surface is readiness, doctor, and authorized Offline toggles only.
- The six core tool entries are non-executable readiness descriptors. Real
  typed tools and Paper adapters are Phase 7.
- Capability Pack loading and command-backed capability execution are Phase 9.
- Client payload networking, overlay UI, item views, recipe behavior, locate,
  guide, project, build, and Litematica behavior.

Schemas in the repository define future contracts; they are not evidence that
the corresponding behavior is active.
