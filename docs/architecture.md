# Architecture

## Status and scope

This document records the target architecture and decisions through Phase 9.
The repository contains build scaffolding, protocol contracts, the Runtime
configuration/readiness boundary, Paper-side authenticated startup and Offline
recovery, private conversations, Runtime-owned sessions, one-shot module
routing, a bounded loop for six fixed read-only tools, and Paper-owned proposal
authorization with private persistent security auditing. Phase 9 adds a
fail-closed Capability Pack loader, typed renderer, parse-only Brigadier
preflight boundary, and immutable generation registry. The production write
catalog remains empty: no Capability route creates a proposal, dispatches a
command, or mutates Minecraft state. Client views and Litematica integration
remain later work. The conditional command and Offline recovery paths have been
validated on the pinned Paper `1.21.11-132` server artifact.

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
  request          live player requests, timeout, cancellation, and private reply
  tool             catalog and execution state machine
  policy           risk and permission intersection
  proposal         frozen proposals, live reauthorization, and confirmation
  capability       pack loading and effective registry
  minecraft        Paper API adapters for context, recipe, and build operations
  client           custom payload gateway and view sanitization
  storage          Paper-owned state, artifacts, and audit repositories
```

Domain and policy packages must not call Bukkit APIs directly. They receive
ports implemented by `minecraft`, `client`, or `storage`. This makes policy and
proposal logic testable without a running server.

Phase 3 introduced `CoreToolRuntime` as non-executable readiness metadata.
Phase 7 retains the exact six read-only, closed-schema descriptors, marks them
executable only after the complete startup gate, and routes calls through the
separate `tool` domain and Bukkit adapter. It still cannot invoke a descriptor
generically. Phase 9 adds a separate Capability loader and registry whose
effective records deliberately contain no invocation operation.

Phase 8 introduces a separate typed proposal catalog and service. Production
publishes an empty implementation: the service has no production `create`
caller and cannot reinterpret a read descriptor as a write tool. Future write
adapters must enter through a fixed decoder, validator, live guard, and executor;
there is no generic command or Bukkit invocation port.

Phase 9 does not fill that catalog or add a creation caller. Capability
validation, approval, and registry publication are prerequisites for a future
adapter, not a bridge around the Phase 8 boundary.

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

Phase 2 established the Runtime startup boundary. It parses restricted YAML,
performs post-parse whole-scalar environment substitution, validates with Zod,
checks private state paths and Capability Schema availability, opens a
Runtime-owned SQLite readiness connection, and invokes an injected provider
health port. The final loopback `listen()` is the port check; no pre-bind probe is
used. Only a successful final bind changes local health from `STARTING` to
`READY`.

Phase 5 supplies the production OpenAI Responses adapter. Startup checks the
configured model without generating an answer, and accepted requests use a
bounded non-streaming text call with provider storage disabled. Runtime owns a
FIFO queue, a concurrency cap, one outstanding request per player, cooldown,
an in-memory daily request limit, provider timeout, and cooperative abort. The
monthly budget and durable usage accounting remain later work.

Phase 7 extends that adapter with strict registered functions and a Runtime-
owned serial loop. Provider-safe function schemas are deliberately smaller than
the full shared schemas; Runtime applies the complete argument contract before
emitting `tool.call` and validates correlation, provenance, trust, and the
tool-specific result contract after `tool.result`. Provider call IDs never
become protocol identities. The wire call UUID and zero-based sequence are
Runtime-generated, with at most eight calls and one call in flight.

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

The authority split is:

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
versioned protocol. Phase 2 creates the Runtime-owned readiness database file
and holds its checked connection. Phase 6 applies versioned session/message
migrations and uses the same private Runtime-owned connection for bounded
repository operations. Paper still opens no database.
Phase 8 keeps active proposal state in a bounded in-memory repository and writes
authoritative, redacted JSONL security events below Paper's private state
directory. Restart therefore cannot revive a proposal, while the audit history
survives it.

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
- Paper independently caps live correlations at 64 and keeps at most one live
  request per player. Network and model completion never block the server thread.
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
The request and proposal cleanup ports have concrete producers; operation and
client producers arrive in later phases.

Paper persists only `DesiredMode` in a strict private state file. Transient
health, Runtime failure, connection identity, and Offline reason are never
written as user intent. Recovery repeats the same core check and handshake, then
atomically persists ENABLED before publishing ONLINE. Runtime loss retains
desired ENABLED and requires an explicit `/agent on`; there is no Phase 4
auto-reconnect.

## Phase 5 private conversation

[ADR 0003](adr/0003-phase5-private-conversation-channel.md) selects the
authenticated application channel and Paper-owned request lifecycle.
`/agent say <message>` is the only Phase 5 conversation entry point. It is
available to ordinary players through `minecraftagent.use`, derives identity
from the actual `Player`, fixes the module to `general`, and does not install a
chat listener. Paper creates the request ID and binds it to the authenticated
connection, configured server ID, player UUID, null Phase 5 session, and the
current `OperationalGate` permit.

The authenticated WebSocket remains open after hello. Application messages are
capped at 64 KiB, strict UTF-8/JSON decoded, replay checked, direction checked,
and validated against their type-specific schemas. Paper accepts only
`agent.complete` and `agent.error`; Runtime accepts only `agent.request` and
`agent.cancel`. Structured views remain empty until the client channel exists.

Completion, error, send failure, timeout, player quit, Offline transition,
Runtime loss, and plugin disable compete for one live record. Removing that
record is the single terminal transition. Paper revalidates the original epoch
and connection immediately before scheduling a literal `Component.text` reply
to the player UUID. Unknown, duplicated, late, wrong-player, old-connection, or
old-epoch terminal messages cannot produce a reply.

## Phase 6 sessions and modules

[ADR 0004](adr/0004-phase6-owned-sessions-and-one-shot-modules.md) selects a
Runtime-owned versioned SQLite schema and dedicated resume exchange. A
successful provider result commits the session and complete user/assistant pair
in one short transaction. All lookups include authenticated server ID and actual
player UUID, and recent context is ordered and bounded before model dispatch.

Paper keeps only the current in-memory session selection. A successful first
reply or `session.resumed` updates it after the same request, player, connection,
and epoch validation used by private replies. `/agent resume [session]` never
becomes model input, and tab completion does not enumerate identifiers.

The fixed Module Manifest resolves trusted instructions independently for each
request. `/agent say` chooses `general`; `/agent module <name> <message>` chooses
one explicit route for one request. Sessions do not store an active module.
Phase 6 kept every tool allowlist empty; Phase 7 replaces those placeholders
with fixed per-module intersections of the six registered read tools.

## Phase 7 read-tool boundary

The implemented Phase 7 boundary is:

- Paper has no conversation database. Runtime exclusively owns the versioned
  session/message schema and commits only complete successful exchanges.
- Resume is a dedicated authenticated application exchange. Session lookup and
  context reads always include server ID and player UUID.
- The six fixed modules provide one-shot prompt routing and fixed read-tool
  allowlists. They do not persist an active route on the session.
- Conversation storage can be disabled. That mode persists no message content,
  returns no durable session, and rejects resume explicitly.
- The application channel supports intermediate `tool.call` and `tool.result`
  only for the six fixed read tools. Runtime-Paper proposal dispatch and write
  execution remain unsupported.
- Paper independently repeats the fixed catalog, module, closed-argument,
  player permission, session, sequence, connection, and epoch checks. Bukkit
  reads are scheduled on the server thread; recipe registry scans are sliced
  and all results are bounded before transport.
- Tool traffic is transient. Only a successful final user/assistant pair is
  committed to the Runtime conversation transaction.

The following remained outside the Phase 7 implementation boundary:

- Durable usage accounting and monthly budget enforcement.
- Proposal creation, confirmation, audit persistence, and every write tool.
- Capability Pack loading was assigned to Phase 9; generic command-backed
  execution remains excluded.
- Client payload networking, overlay UI, item views, recipe behavior, locate,
  guide, project, build, and Litematica behavior.

Schemas in the repository define future contracts; they are not evidence that
the corresponding behavior is active.

## Phase 8 proposal authorization boundary

[ADR 0006](adr/0006-phase8-paper-owned-proposal-authorization.md) keeps proposal
identity and execution admission entirely under Paper authority. A creation
request must match a live Paper-originated request context and a registered
typed write tool. Paper freezes a detached RFC 8785 canonical argument object,
hashes the domain plus a zero byte plus its UTF-8 canonical bytes, records the
current catalog generation, and assigns an opaque UUID and server-owned expiry.
Production fixes the TTL at 60 seconds; the reusable service rejects a TTL over
ten minutes.

The active repository permits exactly one atomic transition from `PENDING` to
`CLAIMED`. After the claim and before the typed executor, Paper repeats the
Online epoch, server/request/session/player binding, actual online player UUID,
current permission and dynamic risk policy, catalog generation, request
context, expiry, and frozen hash checks. `WRITE_WORLD` and `WRITE_PLAYER`
unconditionally require live OP status; an Owner-only local setting adds Owner
membership instead of replacing OP. The executor receives the same frozen
arguments and never calls the model again.

The final compare-and-set is `CLAIMED` to `EXECUTING`. Offline and quit cleanup
may invalidate `PENDING` or `CLAIMED`, but cannot rewrite `EXECUTING` while an
already admitted typed side effect reaches its terminal audited state.
Production adapters must keep final admission and Bukkit mutation on the
primary server thread so Offline commands and player lifecycle events are
serialized with them.

Adventure confirmation is derived from a safe `ProposalView`. Click events use
only fixed namespaced `/minecraftagent:agent confirm <uuid>` and
`/minecraftagent:agent reject <uuid>` commands generated by Paper. Proposal IDs
are not tab-completed, and neither a model summary nor argument can become a
command. Player quit invalidates that player's active entries; Offline, Runtime
loss, and disable invalidate all entries through the proposal cleanup port.

Before execution admission, Paper appends and forces a fixed record to
`<state>/audit/security-audit-v1.jsonl`. The directory is `0700`, the file is
`0600`, unsafe links or modes fail closed, and fields are limited to trusted
correlation IDs, time, risk, tool, catalog generation, event type, and stable
outcome code. Arguments, display summaries, prompts, credentials, and arbitrary
exceptions have no audit field. Audit unavailability prevents new proposal
execution admission.

This phase intentionally stops at the authorization boundary. The production
write catalog is empty, the synchronous domain service has no production
creation route, and the authenticated WebSocket still rejects
`proposal.create`, `proposal.confirmed`, and `proposal.cancelled` as unsupported.
Unit tests exercise the permission and single-use chain; the pinned real-Paper
smoke does not connect a player or click an actual proposal.

The synchronous file audit calls are unreachable from a production proposal
command in Phase 8. Before registering the first write tool, the proposal API
must be split into worker-thread durable intent persistence and a primary-thread
final reauthorization/`EXECUTING`/mutation step. No adapter may call
`force(true)` or wait for storage on the Paper primary thread.

## Phase 9 fail-closed capability broker

[ADR 0007](adr/0007-phase9-fail-closed-capability-broker.md) separates
declarative discovery, effective registry publication, command preflight, and
actual execution. Phase 9 implements the first three boundaries without
providing the fourth.

The loader walks only the configured Paper-owned capability root. Entry count,
file count, per-file and total bytes, directory depth, YAML aliases, and YAML
depth are bounded. Unsafe roots, path escapes, links, non-regular or hard-linked
manifest files, unsafe write modes, invalid UTF-8, and incomplete discovery
produce stable diagnostics. Only `.json`, `.yml`, and `.yaml` files are installed
manifests. All three pass through SnakeYAML `SafeConstructor` and a second
closed manual parser that rejects unknown keys, node types, values, and
inconsistent risk, permission, confirmation, or reversal declarations.

Relative path components use a closed bounded grammar. Files are checked around
their final-component `NOFOLLOW_LINKS` read, directories are checked before and
after enumeration, and a second full discovery must reproduce the first sorted
entry fingerprint after parsing and approval. An ordinary concurrent change is
therefore non-publishable. Traversal remains path based, however; it does not
claim descriptor-relative protection against an intermediate symlink swap or a
restored ABA state by a same-UID/root writer. The current threat model requires
offline pack maintenance. Online reload or a broader local-adversary model must
first adopt `SecureDirectoryStream` descriptor-relative traversal.

Plugin compatibility is derived from a Paper-owned point-in-time inventory.
The v1 range language accepts only explicit comparisons over one to three
numeric components. Missing, disabled, ambiguous, non-numeric, or mismatched
plugins disable a manifest. A manifest with `status: example` or
`status: draft` is permanently non-effective; absence of status is only
eligibility for the separate approval step. Console source is rejected by
default and pack data has no local-policy override.

That inventory is load-time evidence only. A future executor must recheck the
current catalog entry/generation and each required plugin's live enabled state
and version at proposal creation and final execution. Registry membership alone
cannot satisfy live compatibility.

Paper hashes the RFC 8785 canonical typed manifest with SHA-256. Owner approval
is an exact port lookup over capability ID, positive integer manifest version,
and lowercase content hash. Production supplies that port from the bounded,
strict `capabilities.approvals` configuration snapshot. A complete load builds
an immutable candidate registry. Preview computes `added`, `removed`,
`changed`, and `unchanged` IDs; publication atomically swaps the snapshot and
advances the generation. An incomplete traversal or global authority failure
cannot publish. An ordinary rejected manifest remains a disabled draft in the
complete evaluated snapshot, so independent valid entries need not be hidden.
Publication uses compare-and-set against the preview base. An unavailable
optional root retains the prior generation. Fixed logs expose only validated
diff IDs, generation/status, stable draft-disabled code counts, separate stable
global catalog diagnostic codes, and the exact ID/version/hash tuple needed for
a separately reviewed approval. Global failures are not double-counted in the
draft-manifest totals.

Unapplied `STALE` and `REJECTED` previews use `proposed_added`,
`proposed_removed`, `proposed_changed`, and `proposed_unchanged`; only a
`PUBLISHED` event uses unprefixed diff fields. For an unapplied preview, the
event's generation remains the active snapshot generation. Publication runs
after local desired-state and proposal-audit path safety checks but before
Runtime authentication. A failed Runtime handshake can therefore leave a new
metadata generation, which remains non-executable and does not make startup
successful.

The argument compiler accepts only the closed manifest types and required
arguments. Supplied JSON must contain every declared name and no undeclared
name. Type-specific codecs apply range and grammar checks before a fixed ASCII
template can render. The template, rendered command, and root are bounded to
1024 characters; placeholders cannot alter trusted literals or introduce
additional template structure.

Before content hashing, each `number` argument bound must round-trip from its
normalized decimal through IEEE-754 binary64 and RFC 8785/JCS serialization
back to the same decimal. Collision-prone values such as
`0.10000000000000001` and `9007199254740993` are rejected before an approval
identity can be created.

`BrigadierCommandPreflight` removes the one trusted leading slash and invokes
only dispatcher parsing. It requires exact root membership, complete reader
consumption, context nodes, and a resolved command, and never invokes
`CommandDispatcher.execute` or Bukkit dispatch. Paper's top-level Brigadier
node can be a compatibility wrapper for a third-party Bukkit command. That node
alone does not demonstrate a complete side-effect-free target parser, so a
locked target-specific parser or typed API adapter is still required before
execution can be enabled.

An effective record maps effect category to the matching Phase 8 `RiskLevel`
and carries typed permission, confirmation, and block-limit metadata, but no
executor. Reversal references additionally require a separately effective
target with matching command source, effect category, scope, and normalized
plugin requirements; incompatibility and cycles disable the referencing graph.

Production exposes no generic command-dispatch port, Capability proposal
creation route, or pack-backed Runtime tool. Effective capability records are
validated metadata without an executor. Unknown commands remain Proposal Only
and may produce non-executable draft material, never a proposal. Before the
first write adapter, the Phase 8 durable-audit path must also move persistence
and `force(true)` to the worker and return to the primary thread for final live
reauthorization, `EXECUTING` admission, and Bukkit mutation.
