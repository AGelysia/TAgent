# Progress

Last updated: 2026-07-13

## Current status

Phase 0 through Phase 8 are complete. Phase 9 Capability Pack loading is next.

## Locked decisions

- Minecraft, Paper, and Fabric target: 1.21.11.
- Paper API: 1.21.11-R0.1-SNAPSHOT.
- Java toolchain: 21.
- Runtime: Node.js 22 and TypeScript.
- Runtime-Paper protocol: 1.0.
- Runtime and Paper will have separate authoritative stores; they will never
  share-write one SQLite file.
- Every Runtime-Paper envelope has `messageId`, `requestId`, timestamp, and
  nonce. `requestId` is required even for standalone control/health exchanges.
- `DEGRADED` is a health dimension, not an additional operational state.
- Build-preview chunks are framing data inside the protocol 1.0 preview schema;
  semantic validation treats reassembly as a separate validation layer.
- The optional Fabric client must load without Litematica or MaLiLib.
- Gradle and npm work is run serially on this constrained host.
- Provider injection is code-only for tests and smoke. Production uses the
  OpenAI Responses adapter; no configuration value can select a fake provider.
- Phase 3 uses `paper.hello` followed by `runtime.hello`; the Runtime echoes
  Paper's challenge and selects exact protocol `1.0`. Both sides enforce the
  shared HMAC golden transcript and a cross-connection replay window.
- `/agent` is absent from `paper-plugin.yml` and is registered through Paper's
  public `CommandMap` only after every core check succeeds. An initial failure
  requires an external fix and server restart; Phase 4 `/agent on` cannot recover
  a command that was never registered.
- Paper persists only the desired ENABLED/DISABLED mode. Runtime loss changes
  operational state to OFFLINE without rewriting that preference. Every Online
  permit carries a monotonic epoch so work admitted before an Offline transition
  remains invalid after a later recovery.
- Toggle authorization is local console, a configured Owner UUID, or a live OP
  with `minecraftagent.admin.toggle` when `allow-op-toggle` is explicitly enabled.
- Phase 5 keeps one live Paper request per player and 64 globally. Runtime
  independently enforces configured concurrency, FIFO queue, per-player
  cooldown, and an in-memory daily request limit.
- Phase 5 replies are literal private text. Paper installs no ordinary-chat
  listener and revalidates the request epoch and connection at final delivery.
- Phase 6 sessions are owned by Runtime and queried with the complete
  authenticated `(server ID, player UUID)` key. Missing, foreign-player, and
  foreign-server resume identifiers share one safe result.
- `/agent module` is a one-request route. No active module is persisted on a
  session, and ordinary `/agent say` always returns to `general`.
- Conversation storage is controlled by `privacy.storeConversations`. Disabled
  storage persists no prompts or completions and makes resume unavailable.
- Protocol 1.0 permits one in-flight Tool Call and sequences at most eight
  calls as `0..7`. Runtime owns the provider loop; Paper owns the executable
  catalog and repeats policy before every fixed adapter.
- Privacy-disabled requests use an ephemeral tool-correlation Session UUID but
  still return a null completion Session and write no conversation content.
- Recipe results are typed bounded snapshots. Registry scans are split across
  ticks and preserve real recipe variants, layouts, ingredient choices, item
  stacks, processing metadata, and available remaining items.
- Phase 8 proposals are Paper-owned and bound to a live request, player,
  session, tool, catalog generation, and Online epoch. Production uses a
  server-fixed 60-second TTL; the domain service rejects any TTL above ten
  minutes.
- Frozen proposal arguments use RFC 8785 canonical JSON and the
  `minecraft-agent/proposal-arguments/v1` domain-separated SHA-256 golden.
  Confirmation claims one proposal atomically and never asks the model to
  recreate arguments.
- `WRITE_WORLD` and `WRITE_PLAYER` always require a live OP and their dedicated
  permission. An `OWNER` policy is an additional Owner UUID restriction, never
  an alternative to OP. `SERVER_ADMIN` is Owner-only, and an absent typed tool
  remains unavailable regardless of player authority.
- Proposal audit events append and force fixed redacted JSONL records under the
  private Paper state directory. The audit format has no argument, summary,
  prompt, credential, or free-text error field.
- Phase 8 deliberately publishes an empty production write catalog. The
  synchronous proposal domain has no production `create` caller, and proposal
  Runtime-Paper transport handlers remain unsupported.

## Phase 0: repository scaffold

Completed:

- [x] Root Gradle build, locked version catalog, and constrained worker/memory
      settings are complete and verified.
- [x] Paper Java 21 scaffold builds a descriptor-valid JAR. A live Paper server
      launch is intentionally outside the headless Phase 0 verification lane.
- [x] Runtime Node.js 22/TypeScript scaffold builds, tests, formats, and reports
      its version without listening on a network port.
- [x] Fabric Java 21 scaffold builds a client-only JAR and has no Litematica or
      MaLiLib dependency. A graphical client launch is not claimed on this host.
- [x] Checksummed Gradle wrapper and npm lockfile are present.
- [x] Repository ignore, editor, license, and example environment files are
      reviewed.
- [x] Development scripts and top-level README describe the verified commands.

## Phase 1: protocol contracts

Completed:

- [x] Protocol 1.0 Envelope schema includes a nonce and required correlation
      fields with closed bounds.
- [x] Runtime-Paper handshake and agent request schemas are complete.
- [x] Tool call, tool result, and proposal schemas are complete.
- [x] Client handshake and fixed structured-view schemas are complete.
- [x] Recipe view and build preview schemas preserve structured semantics.
- [x] Capability manifest schema is declarative and closed.
- [x] Build-preview transfer metadata and chunks have bounded schema fields and
      separate reassembly/hash semantic validation.
- [x] Minimal valid and focused invalid fixtures cover every contract and local
      cross-schema reference.
- [x] Paper Java, Client Java, and Runtime TypeScript validate the same manifest
      entries and agree on results.
- [x] Semantic tests cover protocol mismatch, index-based chunk reassembly,
      byte limits, bounded gzip expansion, canonical base64, and SHA-256 checks.
- [x] View negotiation rejects a structured view when the client did not
      declare every required feature version.
- [x] Protocol documentation matches the final landed schemas, fixtures, and
      explicitly limited Phase 1 semantic implementation boundary.

## Phase 2: Runtime configuration and readiness

Completed:

- [x] Strict, versioned YAML configuration is capped at 64 KiB, rejects aliases,
      duplicate/unknown fields, unsafe paths, unsafe writable permissions, and
      dangerous secret combinations.
- [x] Environment substitution occurs after YAML parsing and only for complete
      `${NAME}` scalar values; missing API key and server token have stable errors.
- [x] Startup diagnostics contain stable codes and known field paths without
      serializing configuration values, provider exceptions, or unknown key names.
- [x] Log and SQLite state are confined below the configuration directory. New
      directories/files use `0700`/`0600`, and symlinks, hard-linked databases,
      broad state permissions, and root-directory state paths are rejected.
- [x] Runtime-owned SQLite readiness performs read, integrity, and real
      rollback-write probes through Node's built-in SQLite API, then retains one
      handle until shutdown. No migration or repository is implied.
- [x] Capability Schema loading and an abortable model-provider health interface
      are part of the fixed startup gate. Tests use a fake adapter; the default
      production adapter returns `PROVIDER_UNSUPPORTED` and never reports READY.
- [x] Fastify binds only after every injected core check passes. `/health` is
      loopback-only, cached, `no-store`, secret-free, and does not rerun checks.
- [x] Failed provider checks, occupied ports, and post-bind startup failures close
      the Fastify listener and SQLite handle without a transient READY state.
- [x] The packaged configuration template and top-level Bash/PowerShell launchers
      carry and forward explicit `--config` arguments.

## Phase 3: Paper self-check and conditional registration

Completed:

- [x] Runtime readiness now installs a loopback `/agent` WebSocket endpoint that
      accepts only the authenticated hello exchange. Strict UTF-8, duplicate
      keys, 16 KiB, Schema, time, identity, HMAC, and bounded replay checks run
      before a Paper connection occupies the single authenticated slot.
- [x] The shared HMAC transcript has real Paper and Runtime golden proofs that
      both TypeScript and JVM tests verify. Runtime echoes the Paper challenge;
      proof encoding is canonical unpadded base64url.
- [x] Paper configuration is strict and bounded, accepts only
      `ws://127.0.0.1:<port>/agent`, resolves the token from a whole environment
      reference, checks Java/Minecraft compatibility, and rejects unsafe policy,
      paths, permissions, symlinks, state probes, and core descriptors.
- [x] Six closed, read-only, non-executable core descriptors satisfy the Phase 3
      readiness check. Phase 7 later binds those descriptors to fixed adapters.
- [x] Missing or invalid optional capability storage produces
      `OPTIONAL_CAPABILITY_UNAVAILABLE` and `DEGRADED` without blocking command
      registration. Capability Pack loading remains Phase 9.
- [x] `onEnable` returns without waiting for filesystem or network work. A
      generation guard closes late connections after disable and prevents stale
      or duplicate registration.
- [x] Successful authentication returns to the primary thread, preflights both
      command labels, verifies identity after public `CommandMap` registration,
      refreshes online-player command trees, and performs identity-only rollback
      and disable cleanup.
- [x] `/agent` and `/agent doctor` expose readiness and stable warning codes.
      Phase 4 supersedes the original disconnect behavior: Runtime loss now
      retains the registered command and enters Offline.
- [x] Paper `1.21.11-132` smoke with the pinned SHA-256 proves authenticated
      registration, both labels, doctor degradation, and absence for unavailable,
      mismatched-token, and incompatible-protocol cases.

## Phase 4: persistent emergency Offline

Completed:

- [x] Operational state, desired mode, and health are separate immutable
      dimensions. `DEGRADED` remains usable ONLINE health, not an Offline state.
- [x] A monotonic `OperationalGate` issues permits only while ONLINE and rejects
      every permit from an earlier epoch, including after a successful recovery.
- [x] Paper owns a strict 4 KiB `agent-state.yml`. Missing state defaults to
      ENABLED; malformed, aliased, duplicated, unknown, symlinked, non-private,
      or non-regular state fails closed. Writes use a forced `0600` temporary,
      atomic replacement, and a private `0700` directory.
- [x] `/agent off` first enters STOPPING and closes admission, invokes
      request/proposal/operation/client cleanup while the connection is available,
      then detaches the authenticated connection. Cleanup ports isolate
      exceptions; Paper persists DISABLED off-thread, then enters OFFLINE. A
      persistence failure never returns Online.
- [x] `/agent on` is asynchronous and reruns configuration, platform, security,
      state, descriptor, optional capability, protocol, token, and Runtime checks.
      It persists ENABLED before publishing ONLINE; failure returns the exact safe
      message and leaves the registered command Offline.
- [x] Runtime disconnect enters OFFLINE/RUNTIME_UNAVAILABLE, invalidates the
      current epoch, invokes cleanup, retains desired ENABLED, and does not remove
      either command label. There is no automatic reconnect in Phase 4.
- [x] Offline command dispatch precedes ordinary permission, doctor, and usage
      handling. Every non-exact `on`/`off` form returns one exact `AI offline`.
      Toggle commands still require console, Owner UUID, or the explicitly enabled
      OP policy.
- [x] Initial core failure still leaves `/agent` absent. Persisted DISABLED startup
      still completes the full Runtime handshake before registering the command
      Offline, so Offline cannot bypass the startup trust gate.
- [x] The real Paper lifecycle smoke proves manual off, `0600` persistence,
      restart remaining Offline, explicit recovery, Runtime-loss command retention,
      recovery against a restarted Runtime, and clean identity-only unregistration.

## Phase 5: basic conversation

Completed:

- [x] Protocol 1.0 has closed `agent.complete`, `agent.error`, and
      `agent.cancel` payloads. All initiating requests use
      `messageId == requestId`; all terminal/cancel messages retain correlation.
- [x] The authenticated WebSocket remains open after hello, caps application
      frames at 64 KiB, rejects binary/duplicate/stale/replayed/wrong-direction
      traffic, and uses a bounded serialized Paper send queue.
- [x] Runtime has an injectable provider boundary and a production OpenAI
      Responses implementation with model readiness, `store: false`, no tools,
      bounded response reads, fixed safe errors, and AbortSignal support.
- [x] Runtime enforces configured concurrent/queued work, FIFO activation,
      one outstanding request per player, cooldown, in-memory daily limits,
      timeout, cancellation, and late-provider suppression.
- [x] `/agent say <message>` is available to ordinary players through
      `minecraftagent.use`; identity comes only from the actual `Player`, and
      Phase 5 fixes session null, module general, and client capabilities off.
- [x] Paper owns the live correlation record, permits one request per player and
      64 globally, and binds request, player, server, connection, and Offline
      epoch through the final main-thread reply boundary.
- [x] Completion/error fallback is sent with literal `Component.text` only to
      the online requesting UUID. No chat listener or broadcast path exists.
- [x] Timeout, send failure, quit, Offline, Runtime loss, and disable remove the
      record exactly once and issue best-effort cancellation. Old, duplicate,
      wrong-player, wrong-session, and late terminal messages have no effect.

## Phase 6: sessions, resume, and modules

Completed:

- [x] Protocol 1.0 has closed `session.resume` and `session.resumed` payloads,
      stable non-enumerating session errors, and shared fixtures for resumed
      non-default module requests.
- [x] Runtime migrates one private SQLite connection to versioned `sessions`
      and `messages` tables, rejects unsupported future versions, and rolls
      back incomplete exchanges.
- [x] A successful answer atomically commits the complete user/assistant pair.
      Provider failure, invalid output, timeout, or cancellation writes no
      partial turn and no transaction spans provider I/O.
- [x] Every exact/latest lookup and history read includes server ID and player
      UUID. Runtime restart, cross-player denial, cross-server denial, latest
      selection, retention, and cascade behavior are covered by repository and
      request-service tests.
- [x] `/agent resume [session]` uses a dedicated non-model application exchange.
      Paper updates its current selection only for a bound response and never
      tab-completes session identifiers.
- [x] `/agent module list` and `/agent module <name> <message>` use the closed
      six-entry manifest. Module instructions are trusted, tool allowlists are
      empty, and the next ordinary request uses `general`.
- [x] Provider input preserves user/assistant roles. Context keeps the current
      prompt and drops complete oldest exchanges against both message-count and
      total-character limits.
- [x] When conversation storage is disabled, Runtime writes no message rows,
      returns nullable completion sessions, and rejects resume explicitly while
      ordinary private questions continue to work.

## Phase 7: bounded read-only tool loop

Completed:

- [x] Shared `tool.call` and `tool.result` contracts strongly correlate request,
      session, player, tool, sequence, and call ID. Successful and failed result
      states are mutually exclusive and carry explicit source/trust labels.
- [x] Six closed argument/result Schema pairs cover player context, held items,
      server metadata, plugins, recipe lookup, and recipe usage. Empty recipe
      matches are valid typed results.
- [x] Runtime publishes only the current module's registered tools through
      provider-safe function names and strict schemas, then reapplies the full
      shared argument Schema before Paper traffic.
- [x] The OpenAI Responses adapter supports serial function calls with provider
      storage disabled, preserves bounded assistant/reasoning/call output, and
      returns correlated function output without trusting provider call IDs as
      wire identities.
- [x] The loop allows one call in flight, consumes at most eight sequence slots,
      gives the model a tool-disabled final turn at the configured limit, and
      shares one timeout/cancellation signal across every provider/tool round.
- [x] Runtime rejects unknown, module-disallowed, malformed, mismatched, or
      provenance-invalid calls/results. Cancellation, disconnect, and timeout
      suppress late results and never commit a partial conversation exchange.
- [x] Paper independently repeats live request, player, session, module,
      sequence, unique call ID, permission, connection, and Online-epoch checks.
      Invalid registered-tool requests return a typed policy rejection.
- [x] Bukkit reads are scheduled on the primary thread without blocking the
      WebSocket thread. Recipe scans use bounded per-tick slices and cancellable
      futures rather than scanning the complete registry in one tick.
- [x] Recipe adapters preserve shaped, shapeless, cooking, stonecutting,
      smithing transform/trim, and transmute layouts plus IngredientChoice,
      ItemStack, processing, source, and remaining-item data. Unsupported
      plugin recipes remain explicit instead of becoming model-authored text.
- [x] Tool results enforce both the 64 KiB application frame and Runtime's JSON
      structural-token budget; an oversized success becomes a bounded typed
      failure.

## Phase 8: permissions and Paper-owned proposals

Completed:

- [x] Five explicit risk levels separate reads, temporary writes, world writes,
      player writes, and server administration. The current production write
      catalog is intentionally empty.
- [x] Proposal creation accepts only a registered typed tool and active
      Paper-originated request context, freezes detached arguments, binds the
      current catalog generation, and assigns an opaque Paper-owned UUID.
- [x] RFC 8785 canonicalization, bounded structure/size, a domain-separated
      SHA-256 digest, constant-time digest comparison, and a shared golden
      fixture prevent insertion-order or numeric-spelling ambiguity.
- [x] Paper owns expiry. Production proposals live for 60 seconds and neither a
      model nor a caller can extend the service's ten-minute hard maximum.
- [x] Confirmation performs an atomic `PENDING` to `CLAIMED` transition, then
      repeats Online epoch, actual UUID, online status, current OP/Owner and
      permission policy, request context, tool/catalog generation, and frozen
      argument hash checks before an atomic `CLAIMED` to `EXECUTING` admission
      and typed execution.
- [x] World and player writes always require a live OP. Owner-restricted policy
      adds the configured Owner UUID check, and dynamic policy changes or OP
      removal invalidate an old proposal at confirmation.
- [x] Adventure actions render only fixed namespaced confirm/reject commands
      containing the Paper-owned proposal UUID. IDs are not tab-completed and
      model-controlled command text cannot enter the click event.
- [x] `/agent off`, Runtime loss, disable, and player quit invalidate pending or
      claimed proposals. Atomic claim makes concurrent or repeated confirmation
      single-use; final `EXECUTING` admission cannot be rewritten underneath an
      already admitted side effect.
- [x] Paper persists append-and-force JSONL audit events at
      `<state>/audit/security-audit-v1.jsonl` with `0700`/`0600` permissions.
      The fixed record shape omits frozen arguments, display summaries, prompts,
      credentials, and arbitrary exception text, and an unavailable audit sink
      fails closed before execution admission.
- [x] Proposal create/confirmed/cancelled schemas and the argument-hash golden
      are shared contracts. Runtime and Paper proposal transport dispatchers
      remain unsupported until an explicitly reviewed integration is added.

## Verification

Phase 8 was verified serially on 2026-07-13 with:

```bash
cd agent-runtime
npm run check
npm audit
npm audit --omit=dev

cd ..
./gradlew --no-daemon --max-workers=1 :paper-plugin:build
./gradlew --no-daemon --max-workers=1 :client-mod:build
./scripts/paper-smoke.sh
./scripts/package.sh
```

Results:

- Runtime: 13 Vitest files, 102 tests passed; TypeScript build, ESLint, and
  Prettier passed; full and production-only npm audits reported 0
  vulnerabilities.
- Paper: build and Spotless passed; 250 tests passed, including 73 shared dynamic
  Schema/HMAC/semantic cases plus strict desired-state parsing/atomic persistence,
  operational epoch invalidation, Owner/OP/console authorization, cancellable
  real-WebSocket handshake/application exchange, provider request correlation,
  cancellation/timeout/late-response races, lifecycle/persistence races,
  session selection/resume binding, explicit module routing, fixed read-tool
  policy and correlation, typed Bukkit recipe mapping, proposal hashing,
  live reauthorization, single-use and `EXECUTING` transitions, audit storage
  hardening/redaction, fixed Adventure actions, output-budget downgrade,
  command-map transactions, private reply gating, exact Offline output, doctor
  output, and descriptor tests.
- Fabric: remapped JAR build and Spotless passed; 74 tests passed, including the
  same 73 shared protocol cases plus client metadata.
- Both JVM reports contain no remote Schema load, `UnknownHostException`,
  invalid-schema error, skipped test, or failed test.
- Paper `1.21.11-132` JAR SHA-256
  `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`
  passed Offline lifecycle, unavailable, token-mismatch, and
  incompatible-protocol cases at `-Xms256M -Xmx512M`. The lifecycle case proved
  online doctor degradation, exact per-command Offline output, `0700`/`0600`
  audit storage, audit content redaction, Console proposal-confirmation
  isolation, unsafe `0640` audit startup failure with no command registration,
  `0600` DISABLED persistence, restart remaining Offline, successful on,
  Runtime-loss retention, failed on while Runtime was absent, successful on
  after Runtime restart, both command labels, Console `/agent say` isolation
  from the provider, and exception-free plugin disable. No Paper, Runtime, or
  Gradle process remained.
- Packaged Runtime preserved its compiled authenticated application endpoint,
  OpenAI tool-loop provider, migrations, session/message repository, context
  reducer, Module Manifest, fixed Tool Registry, configuration template, and
  all shared read-tool/proposal schemas. The Paper JAR contains the fixed read
  registry, Bukkit adapters, Paper-owned proposal/audit classes, embedded JCS
  implementation, and the same schemas. The production write catalog remains
  empty.
- All protocol JSON files parse successfully; Bash scripts pass `bash -n`.

PowerShell scripts were reviewed for native exit-code propagation but were not
executed because `pwsh` is unavailable on this Linux host. A graphical Fabric
client, a real online player executing `/agent say`, a live OpenAI tool call,
and Litematica were not started. Ordinary-player UUID binding, primary-thread
private reply, no broadcast, timeout, concurrent-request behavior, provider
function-call continuation, and typed Paper tool execution are covered by
JVM/Runtime tests; the pinned smoke only proves that Console cannot enter the
model path. Resume ownership, one-shot modules, nullable storage mode, session
correlation, and command permissions are covered by JVM/Runtime tests. Gradle
reports Loom-originated deprecation warnings for future Gradle 10 compatibility,
but both builds pass on the locked Gradle 9.5.1 wrapper. Node emits its
documented ExperimentalWarning when the built-in SQLite module is loaded; it is
not suppressed.

## Explicitly not implemented

- Heartbeat or automatic reconnect. Phase 5 recovery still creates a fresh
  authenticated connection only after explicit `/agent on`.
- Any production proposal-creation route, write-capable or external-command
  tool, or Minecraft mutation. Phase 8 supplies the Paper-owned proposal domain,
  confirmation command boundary, and audit sink, but its production write
  catalog is empty and the synchronous domain service has no production
  `create` caller.
- Runtime-Paper proposal message handlers. The create/confirmed/cancelled
  schemas and fixtures are active contracts, while authenticated application
  dispatch still rejects those message types as unsupported.
- Paper conversation repositories; Paper retains only a transient current
  session selection. Runtime owns the conversation database exclusively.
- Durable rate accounting, token/cost accounting, or monthly budget
  enforcement.
- Capability Pack discovery, approval, reload, or command execution.
- Client custom payload networking, overlay UI, item rendering, or preferences.
- Structured recipe client views, locate/project/build behavior, world mutation,
  or Litematica behavior. The recipe tools expose server data only to the model.
- Full build-preview logical validation: strict single-member gzip framing,
  RFC 8785 content/Palette canonicalization, geometry, block count,
  base-region hash, and change-policy checks. These remain mandatory Phase 10
  gates before a preview network handler can be enabled.

The presence of a schema or proposal domain object does not mark a write tool
implemented.

## Known design risks

### Provider boundary

Phase 7 supplies one fixed OpenAI Responses adapter with safe status mapping,
bounded bodies, a serial strict-function loop, timeout/cancellation, and no
prompt/completion logging. It does not retry, stream, rotate providers, persist
usage, or enforce the configured monthly budget. Provider account-side
retention and policy remain an operator responsibility even though requests set
`store: false`.

### Node SQLite stability

Node 22's built-in synchronous SQLite API avoids a native addon on this small
host, but Node still labels it active development and emits an ExperimentalWarning.
Phase 6 uses it for indexed, hard-limited context reads and short atomic
exchange writes. No transaction spans provider I/O. The driver remains
experimental and should be reassessed before higher-volume deployment or
unbounded data features.

### Conditional command registration

ADR 0001 selects public late `CommandMap` registration and the exact Paper smoke
proves both labels and conditional absence. An initial failure deliberately has
no in-process recovery path and requires restart. Unit tests prove that
`Player#updateCommands()` is invoked, but a real connected client was not part
of the smoke; observing its refreshed Brigadier tree remains a later integration
test gap.

### Litematica compatibility

Litematica and MaLiLib internals are exact-version sensitive. A future adapter
must lock both versions and prove optional class loading, preview lifecycle, and
native Material List HUD integration. No compatibility is assumed now.

### Large world writes

A preflight region hash does not make a multi-tick update atomic. The first
write implementation must use conservative limits and define conflict and
partial-failure behavior before raising block limits.

### Third-party command parsing

Paper plugins do not universally expose a side-effect-free Brigadier validation
path. A command-backed Capability remains disabled unless complete preflight
parsing is demonstrated for the locked plugin version; otherwise use a typed
adapter.

### Proposal integration coverage

The pinned Paper smoke exercises startup, Offline, restart, Runtime loss, and
command-map lifecycle without a real connected player. It does not create or
click a proposal. Focused JVM tests cover live OP/Owner/permission changes,
request and catalog binding, expiry, hash tampering, atomic double-click
behavior, Offline/quit invalidation, fixed Adventure actions, and audit
redaction; a real-player click remains a later integration lane.

## Next gates

1. Implement Phase 9 Capability Pack discovery, approval, immutable catalog
   publication, version matching, and typed argument rendering without enabling
   an unsafe command-dispatch fallback.
2. Keep the production write catalog empty until the first fixed typed adapter
   has operation-specific validation, limits, rollback/partial-failure policy,
   and a real-player proposal integration test.
3. Add durable usage/cost accounting before treating daily/monthly limits as
   restart-stable budgets.
4. Add a real online-player integration lane for private `/agent say` delivery,
   proposal confirmation, and late dynamic command-tree refresh without
   increasing routine weak-host resource usage.
5. Add Gradle dependency-verification metadata before calling a release
   byte-for-byte reproducible; Paper 1.21.11 is available only through an
   upstream mutable snapshot coordinate.
