# Minecraft Agent

Minecraft Agent is a security-first Minecraft assistant composed of a Paper plugin, a local
TypeScript runtime, and an optional Fabric client mod. This repository currently implements the
Phase 0-12 foundation: shared protocol contracts, fail-closed readiness checks, an authenticated
loopback Runtime-Paper channel, conditional `/agent` registration, persistent emergency Offline
controls, private model replies, Runtime-owned sessions, resume, explicit one-shot modules, a
bounded closed-tool model loop, and Paper-owned proposal authorization and audit
infrastructure. It also includes bounded Capability Pack loading, typed validation, and immutable
catalog publication. Phase 10 adds an optional raw-JSON Fabric channel, exact structured-view
negotiation, bounded transfer, a local rich overlay with registry item rendering, and a fail-closed
exact-version Litematica adapter. Phase 11 adds private Markdown knowledge, player-owned projects,
permission-filtered landmarks, authoritative recipe v2 views, and bounded Paper-owned build
previews that the client can convert to native Litematica schematics. The production write catalog
is still empty: there is no production proposal creator, generic execution surface, or Minecraft
state mutation. Phase 12 adds redacted management diagnostics, a restricted atomic policy reload,
and durable aggregate usage/cost accounting.

The product and delivery baseline is recorded in
the repository-only
[`minecraft_agent_vibe_coding_plan.md`](https://github.com/AGelysia/TAgent/blob/main/minecraft_agent_vibe_coding_plan.md).
Implementation status is tracked in the repository-only
[`docs/progress.md`](https://github.com/AGelysia/TAgent/blob/main/docs/progress.md).

## Version baseline

| Component     | Version               |
| ------------- | --------------------- |
| Java          | 21                    |
| Minecraft     | 1.21.11               |
| Paper API     | 1.21.11-R0.1-SNAPSHOT |
| Fabric Loader | 0.19.3                |
| Fabric API    | 0.141.4+1.21.11       |
| Litematica    | 0.26.12 (optional)    |
| MaLiLib       | 0.27.16 (optional)    |
| Node.js       | 22.16-22.x            |
| Candidate     | 0.1.0                 |

Minecraft 1.21.11 is intentionally pinned because the product baseline requires Java 21. Paper
26.x requires Java 25 and is outside this compatibility line. Paper publishes the 1.21.11 API only
through a mutable snapshot coordinate. The checked-in Gradle dependency verification metadata pins
the resolved snapshot and all other build artifacts by SHA-256; an upstream change fails closed.

## Repository layout

- `protocol/`: the single source of truth for JSON Schema and contract fixtures.
- `paper-plugin/`: the authoritative server-side security and execution boundary.
- `agent-runtime/`: local TypeScript process for routing, model access, and persistence.
- `client-mod/`: optional, client-only Fabric presentation layer.
- `capability-packs/`: non-executable Capability examples and drafts; never an arbitrary command
  interface.
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

The complete Linux release-candidate lane runs two uncached clean builds, the pinned real Paper
smoke, dependency audit, package/JAR/schema/permission inspection, archive extraction, and checksum
comparison:

```bash
./scripts/release-check.sh
```

The canonical command requires a clean Git worktree at entry and exit. It also asserts the Phase 13
minimum test inventory and required suites before the second clean build removes reports. A long,
explicit dirty-worktree override exists only for maintainer diagnostics and cannot produce manual or
release evidence. This proves deterministic output within the recorded pinned lane. It is not a claim that arbitrary
future operating-system or JDK builds are byte-for-byte identical. GitHub's manual release-candidate
workflow uploads an artifact only; it does not create a tag or Release.

## Phase 2-12 Runtime

```bash
cd agent-runtime
npm run version
npm run check
```

[`agent-runtime/config.example.yml`](agent-runtime/config.example.yml) is the strict configuration
template and currently requires `configVersion: 2`; legacy v1 files fail with an explicit upgrade
diagnostic. Runtime secrets are resolved after YAML parsing from whole-value `${ENV_NAME}` references;
`.env.example` is documentation only and is not loaded automatically. Keep a local configuration at
mode `0600`, inject secrets from the shell or service manager, and start with:

```bash
npm start -- --config config.local.yml
```

Startup checks the configuration, private log and configured knowledge directories, shared
Capability Schema, Runtime-owned SQLite file, and the configured OpenAI model before binding
`127.0.0.1`. `/health`
returns a cached minimal readiness view and does not repeat provider or database work.

The Runtime uses the OpenAI Responses API for bounded, non-streaming answers and serial function
calls with provider storage disabled. It applies per-player outstanding/cooldown/durable UTC-daily
limits plus global concurrency, queue, and a monthly reservation-based admission bound. Pricing is explicit rather than
inferred from the model name: configure integer
`model.inputMicroUsdPerMillionTokens`, `model.outputMicroUsdPerMillionTokens`, and
`limits.providerRoundReservationMicroUsd` from the selected model's current price sheet. With
`privacy.storeConversations: true`, a versioned SQLite
repository commits each successful user/assistant exchange and can restore it after a Runtime
restart. Every session read is scoped by authenticated server ID and player UUID, and model context
is bounded by both message count and total text size. Disabling conversation storage leaves no
prompt or answer rows and makes resume unavailable.

After the authenticated hello, the same bounded WebSocket accepts agent request/cancel,
session-resume, and correlated tool-result traffic. A fixed six-entry Module Manifest supplies
trusted per-request instructions and a fixed tool allowlist. Runtime publishes only that module's
intersection of 13 registered Paper-remote and Runtime-local functions, validates every call and
typed result locally, and permits at most eight serial calls. Phase 8 adds shared proposal contracts,
but proposal, standalone
`view.publish`, and heartbeat transport handlers remain unsupported; schema acceptance alone cannot
create or execute a proposal.
Phase 10 accepts Paper-derived connected-client capabilities as presentation metadata and, when the
final 64 KiB application envelope permits it, emits a closed version `1.0` text view alongside the
unconditional fallback. If duplicating the answer into that view would exceed the envelope limit,
Runtime removes the structured view and keeps the fallback. It never treats a client claim as model,
tool, permission, or proposal authority.

Phase 11 loads bounded Markdown from configured private `server_rules` and `local_docs` roots.
`server.docs.search` returns explicit citations, ranks server rules first, and exposes excerpts as
untrusted quoted data rather than instructions. SQLite migration v2 adds projects scoped by the
authenticated server ID and player UUID. Create/read/list/update operations preserve an optimistic
revision and limit each owner to 20 active projects; they store planning text only and never inspect
or change a world. Runtime admits create/update only for a direct imperative mutation request,
rejects questions, hypotheticals, and negations, and permits at most one successful mutation per
request. A remote build preview also requires a successful same-request
`project.read` for the exact project UUID and revision. Recipe presentation is generated only from a successful
`server_registry`/`authoritative` recipe result, including its text fallback, so model output cannot
supply recipe facts.

SQLite migrations v3-v5 persist idempotent provider-round usage events, active cost reservations,
provider-start state, and UTC daily/monthly aggregates. Reported token cost is rounded up to an
integer micro-USD per provider event; a response or started call with unknown usage is conservatively
charged at the configured round reservation and marked estimated. The authenticated Runtime-Paper channel accepts a closed
`management.costs.request` and returns only server-wide current-day/current-month totals plus budget
state. The management payload never contains player UUIDs or per-player usage. See
[`docs/operations.md`](docs/operations.md) before selecting a reservation value. The monthly setting
is a reservation-based conservative admission bound, not a provider billing cap; reported cost can
exceed both a round's reservation and the configured local bound.

## Phase 3-12 Paper

On first Paper startup, the plugin installs a strict `plugins/MinecraftAgent/config.yml`. Keep its
Runtime token as the complete `${MINECRAFT_AGENT_SERVER_TOKEN}` environment reference. The endpoint
is restricted to `ws://127.0.0.1:<port>/agent`, state stays under the plugin data directory, and the
target server must be Minecraft/Paper 1.21.11 on Java 21 or newer.

The default `owners: []` permits only the local server console to run `/agent on` or
`/agent off`. Add canonical player UUIDs to `owners` for player administration. Setting
`security.allow-op-toggle: true` additionally permits a live OP with
`minecraftagent.admin.toggle`; it remains false by default.

`capabilities.approvals` is an optional list and defaults to empty. Each entry must match the exact
capability `id`, positive manifest `version`, and lowercase 64-character `sha256` of the canonical
typed manifest. Pack content cannot approve itself, and editing a hashed field makes the previous
approval miss.

Paper performs configuration, state, policy, descriptor, and Runtime authentication checks away
from the server thread. Only a successful result returns to the primary thread and registers
`agent` plus `minecraftagent:agent` through Paper's public command map. A core failure leaves both
labels absent; fix the external cause and restart the server. `/agent doctor` reports stable health
and optional warning codes. Eight Paper entries now back executable read-only adapters; they are
still closed descriptors and cannot dispatch arbitrary Bukkit, plugin, or command operations.

After initial registration, `/agent off` closes admission before it cancels transient work and
atomically persists `DISABLED` in `plugins/MinecraftAgent/state/agent-state.yml`. While not ONLINE,
every non-toggle command returns exactly `AI offline`. `/agent on` repeats the full local check and
authenticated handshake, then persists `ENABLED` before publishing ONLINE. A Runtime disconnect
moves the Agent Offline without changing the persisted desired mode, and the command remains
available for an explicit recovery attempt. An initial startup failure still leaves the command
absent and requires an external fix plus restart.

Online management commands are `/agent status`, `/agent doctor`, `/agent capabilities`,
`/agent costs`, and `/agent reload`. The four read-only queries default to OP through individual
`minecraftagent.admin.*` permissions; ordinary client controls use `minecraftagent.ui`. Reload
remains Console/Owner-only and does not become available merely because a player is OP or has the
descriptor's `minecraftagent.admin.reload` node. It validates a complete candidate away from the
server thread and atomically replaces only `owners` plus `security` policy. Runtime connection
settings, state/capability paths, and Capability approvals require a server restart. A failed or
stale reload keeps the previous policy generation. The trusted manager is installed only after
Runtime authentication, survives Offline recovery without rebasing restart-only fields, and binds
each publication to its Online epoch. Costs distinguish reported/estimated provider calls, settled
cost, active reservations, and remaining monthly exposure. Doctor renders only bounded component/protocol,
Capability, anonymous client-feature, and Litematica compatibility aggregates; it never renders a
player UUID or client-local path.

Ordinary players have `minecraftagent.use` by default and may run `/agent say <message>`. Paper
derives the UUID from the actual player, permits one live request per player, and sends validated
fallback text only to that player on the primary thread. It does not listen to normal chat or
broadcast model output. Timeout, quit, Offline, Runtime loss, and disable all cancel live request
state; responses from an older connection or Offline epoch are discarded.

Successful replies select their Runtime session for later `/agent say` requests. Players can use
`/agent resume [session]` to recover their own latest or named session; Paper never suggests session
IDs, and Runtime returns the same safe error for an absent, foreign-player, or foreign-server ID.
Players with `minecraftagent.module` may list modules or run
`/agent module <name> <message>`. That route applies once: the next `/agent say` uses `general`.

During a live question, Paper may execute only `player.context.read`,
`player.held_item.read`, `server.info.read`, `server.plugins.list`,
`server.recipe.lookup`, `server.recipe.uses`, `landmark.search`, or
`build.preview.create`. It repeats module, argument, player,
permission, connection, session, sequence, and Online-epoch checks. Bukkit snapshots run on the
server thread without blocking the WebSocket thread; recipe scans are split into bounded slices.
Results carry fixed source/trust labels, and recipe results retain typed layouts, ingredient
choices, item stacks, processing metadata, and remaining items instead of model-authored prose.

Phase 8 adds a Paper-owned proposal service for future typed write adapters. It freezes validated
arguments in RFC 8785 canonical form, hashes them with the
`minecraft-agent/proposal-arguments/v1` domain, binds them to a live request and catalog generation,
and assigns an opaque ID with a server-controlled 60-second expiry. Confirmation atomically claims
the proposal and repeats the Online epoch, actual player UUID, online status, current permission,
dynamic policy, request context, catalog generation, and argument-hash checks before invoking a
fixed typed executor. Final admission moves the entry to `EXECUTING`, which cleanup cannot rewrite
underneath an admitted side effect. `WRITE_WORLD` and `WRITE_PLAYER` always require live OP status; an Owner-only
policy can further restrict those writes but can never replace OP. `/agent off`, Runtime loss,
disable, and player quit invalidate affected proposals.

Adventure confirmation actions are built from fixed namespaced commands and a server-owned UUID;
model text cannot supply a click command. Security events append to
`<state>/audit/security-audit-v1.jsonl` (under `plugins/MinecraftAgent/state/` by default) with a
private `0700` directory and `0600` file. Records contain only fixed correlation, risk, tool, state,
time, and outcome fields; they omit arguments, summaries, prompts, credentials, and free-form
errors. The service and command boundary are active, but no production write tool is registered and
the synchronous proposal domain has no production `create` caller yet.
Before the first write tool is enabled, its adapter must move durable audit I/O to the worker and
return to the Paper thread for final reauthorization, `EXECUTING` admission, and Bukkit mutation.

Phase 9 adds a fail-closed Capability broker under the configured `capabilities.directory`. Discovery
bounds entries, files, bytes, directory and YAML depth, and rejects unsafe paths, links, modes,
aliases, invalid UTF-8, unknown fields, and partial loads. Plugin names and deterministic numeric
version ranges are checked against a Paper-owned snapshot. Manifests marked `example` or `draft`
are permanently non-effective, regardless of any matching approval.

A complete load builds an immutable catalog snapshot. Preview reports `added`, `removed`, `changed`,
and `unchanged` IDs; publication atomically replaces the snapshot and advances its generation.
Arguments are required-only and use closed typed codecs. Fixed templates and rendered commands are
limited to 1024 characters, and Brigadier preflight calls parse only, requires complete consumption,
and never calls execute or Bukkit dispatch. Console source is denied by default, and unknown commands
remain non-executable Proposal Only material. Production still exposes no generic command dispatch,
pack-backed Runtime tool, or Capability proposal-creation route. The first write adapter must retain
the worker-thread durable audit and primary-thread final authorization sequence described above.

Phase 10 registers the separate `minecraftagent:client` Bukkit/Fabric Custom Payload channel. It
uses closed raw UTF-8 JSON messages, binds capabilities and transfers to the actual player's positive
connection generation, and never exposes the Runtime token or provider key. Every completion keeps
private fallback text. Paper sends a structured view only when its exact version registry intersects
the actual client's advertised features; vanilla and incompatible clients stay on fallback text.
Client-to-Paper frames are capped at 16 KiB and Paper-to-client frames at 40 KiB before parsing.
The outer client payload remains `1.0`; the current Fabric hello is `1.1` with closed diagnostics,
while Paper preserves the capabilities of diagnostic-free legacy hello `1.0` clients.

View transfers use identity or gzip framing with 24 KiB decoded chunks, at most 1 MiB compressed and
uncompressed content, 64 chunks, and a 15-second timeout. Paper prepares JSON, compression, hashes,
and at most eight pending transfers away from its primary thread; its 2 MiB budget counts
uncompressed view bytes. The client admits at most two active reassemblies and reserves their declared
compressed bytes against a separate 2 MiB budget. Incoming protocol work uses one 256-entry worker
queue, and verified client-thread actions use at most 128 pending reservations. A reported
`DISPLAYED` status only retires fallback bookkeeping; rejection, a transfer-scoped client error, or
server timeout sends the correlated private fallback once. None of those client reports is authority.

The client performs bounded reassembly, gzip, per-chunk/complete SHA-256, strict UTF-8, and closed view
decoding away from the render loop. The overlay renders version `1.0` Text, ItemStack, ItemList, and
RecipeGrid views, including real registry icons, counts, vanilla tooltips, and explicit missing items.
It supports scroll, drag, bounded resize, pin/unpin, close, clear, `/agent ui pin|unpin|clear`, and
atomic local preferences at `config/minecraftagent/overlay.json`.

The base client loads without Litematica. Optional integration is enabled only for Minecraft 1.21.11,
Fabric Loader 0.19.3,
[Litematica 0.26.12](https://modrinth.com/mod/litematica/version/b3dJnV8d), and
[MaLiLib 0.27.16](https://modrinth.com/mod/malilib/version/oaU4Ys3J). It isolates the reviewed
reflection adapter, derives only managed `<view-uuid>.<revision>.<artifact-uuid>.litematica` files, supports local preview
load/remove and the native Material List HUD, and leaves the base overlay available on a tuple or
linkage mismatch. Load preparation reads and hashes at most 16 MiB on the protocol worker; the final
metadata recheck and all Litematica calls run on the Minecraft client thread. A runtime adapter call
fails only that operation and does not dynamically withdraw the already advertised feature version.
Phase 11 adds strict recipe view v2 decoding and the complete Palette v1 preview path. Paper loads a
private `0600` `plugins/MinecraftAgent/landmarks.yml`; `landmark.search` filters permissions before
counts and truncation, then orders visible same-dimension results by live-player distance. The build
tool accepts only a closed 32-by-32-by-32, 4096-cell maximum plan near the player, checks world
height/border and already-loaded chunks, rejects every target state that creates a block entity and
every target cell that already contains one, and takes two bounded server-thread snapshot passes
before worker-side canonicalization. Paper derives the target palette,
base-region, change-set, palette, and content hashes and never writes the world. Runtime-supplied
build views are discarded; only the Paper-owned one-shot artifact for the same request/player may be
published. When that artifact exists, it is the only candidate view, its fallback is rebound to the
final completion fallback, and a later build attempt first invalidates any earlier artifact.

Build preview publication is disabled by default. Set the exact server-process environment value
`MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true` to advertise and publish the Paper-owned preview to a
compatible client. Fabric revalidates gzip, strict UTF-8, duplicate-free RFC 8785 JSON, palette,
registry BlockStates, geometry, counts, and hashes, then atomically creates a connection-scoped
native `.litematica`. Loading remains an explicit local action; removal and the native Material List
HUD stay presentation-only. No Easy Place, printer, automatic placement, world apply, or rollback is
enabled.

After receiving a build-preview view, use its UUID for the explicit presentation actions:

```text
/agent ui preview <view-id>
/agent ui materials <view-id>
/agent ui remove <view-id>
```

The opt-in exact-server smoke pins Paper `1.21.11-132`, verifies its SHA-256, limits the heap to 512
MiB, and runs every case serially:

```bash
./scripts/paper-smoke.sh
```

This is intentionally separate from routine checks because it downloads and starts a real Paper
server. It cleans up all temporary worlds, logs, credentials, and child processes.
The smoke includes an Offline/restart/on/Runtime-loss/on lifecycle and checks clean dynamic command
unregistration in addition to the three initial transport-failure cases. It does not connect a real
player, graphical Fabric client, or Litematica installation, and does not click a proposal; focused
tests cover the Phase 8/10/11/12 domain and protocol boundaries. Graphical verification is the
separate [`docs/phase13-manual-test.md`](docs/phase13-manual-test.md) lane.

## Package

```bash
./scripts/package.sh
```

The inspected directory is placed under `dist/`; upload-ready JARs, a deterministic
`MinecraftAgent-0.1.0.tar.gz`, and their `SHA256SUMS` are placed under `release/`. The package contains the implemented persistent conversation,
resume, explicit module path, shared tool/proposal schemas, Runtime loop, Paper read adapters, and
Paper proposal authorization and audit infrastructure. It also contains the bounded Capability Pack
loader, exact approval and immutable catalog/diff model, required-only typed renderer, and parse-only
Brigadier preflight. It also contains private Markdown knowledge search, player-owned project
storage, Paper landmark/search and authoritative build-preview services, recipe v2 presentation,
the optional Fabric channel, structured-view transfer/rendering, local overlay/preferences, and
native Litematica generation. The production proposal tool catalog remains empty; Capability
proposal creation, generic execution, world apply/rollback, and every Minecraft mutation remain
unavailable.

Phase 12 packaging also includes the closed management cost schemas, Runtime SQLite v3-v5 usage
accounting, Paper management queries, anonymous client diagnostics, and restricted policy reload.

The packaged Runtime preserves its compiled layout and includes the shared protocol schemas and
configuration template. Install production dependencies before startup:

```bash
cd dist/agent-runtime
npm ci --omit=dev
cd ..
./start-agent.sh --config config.example.yml
```

The top-level start scripts forward `--config`. They do not load `.env`; provide the server token
and provider key through the invoking shell or service manager. Startup fails closed unless the
configured model lookup succeeds. Relative config paths are resolved from the extracted bundle root.

`scripts/package.ps1` remains a developer bundle path and does not establish release equivalence.
The canonical candidate is produced on Linux by `scripts/release-check.sh`; native Windows packaging
and runtime behavior remain an explicit manual gap.

See [`SECURITY.md`](SECURITY.md) before exposing a server and
[`CLIENT-COMPATIBILITY.md`](CLIENT-COMPATIBILITY.md) before installing the optional client stack.

## Security baseline

The Paper plugin is the final authorization and execution boundary. The project never exposes a
general console, shell, script, reflection, or unrestricted file tool. The optional client and all
model output are untrusted. `/agent` is deliberately absent from `paper-plugin.yml`; Phase 3 creates
it dynamically only after the complete core readiness gate succeeds. Phase 4 keeps the registered
command during later Offline transitions, rotates an epoch permit before cleanup, and requires a
fresh check before returning Online. Phase 5 binds every private reply to that permit, the live
authenticated connection, request ID, server ID, and actual player UUID. Phase 6 keeps the same
live binding for resume, and Runtime additionally scopes every durable session operation by server
ID plus player UUID. Phase 7 additionally requires both Runtime and Paper to accept only the fixed
module tool intersection and binds every result to the live request, temporary or durable session,
player, tool ID, sequence, call ID, connection, and Online epoch. Phase 8 keeps proposal identity,
expiry, frozen arguments, one-time claim, live reauthorization, fixed confirmation actions, and
redacted durable audit records under Paper authority. It deliberately enables no production write
adapter or proposal transport handler. Phase 9 bounds Capability discovery and parsing, requires
exact ID/version/hash approval, permanently excludes `example` and `draft`, rejects console source
and incompatible plugins, compiles required-only typed arguments into bounded fixed templates, and
keeps Brigadier at parse-only preflight. Immutable catalog publication cannot introduce a generic
dispatch operation or Capability proposal-creation route; unknown commands remain fail-closed.
Phase 10 binds optional-client traffic to actual player/generation state, exact view features, fixed
transfer budgets, and closed presentation models. ACKs, UI controls, selections, preview state, and
material results never grant permission or confirm a proposal. The only reflection is an isolated,
exact-version, presentation-only Litematica adapter; it is not a model or server execution surface.
Phase 11 treats local documents and stored plans as data, keeps landmarks and build snapshots under
Paper authority, derives recipe presentation only from authoritative registry results, and rejects
Runtime-authored build views. Its hashes and local schematic are preview evidence only; the empty
production write catalog remains the final barrier against world mutation. Phase 12 preserves the
same Offline gate for every management query, exposes no per-player cost or client identity, and
publishes a reload policy only after complete validation; config values outside the supported local
policy snapshot require restart.
