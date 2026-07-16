# Progress

Last updated: 2026-07-16

## Current status

Phase 0 through Phase 12 are complete. Phase 13's release-candidate
infrastructure and its historical physical-client acceptance are complete.
Phase 14 implements fixed OpenAI, Anthropic, DeepSeek, Gemini, and
OpenAI-compatible production adapters with controlled endpoint overrides. The
exact `0.2.0` payload passed the clean automated gate, cloud and two-client
results were maintainer-attested, and the owner authorized a controlled public
prerelease exception. The original strict gate remains `REJECTED`: native
Litematica projection execution was not observed against this payload and no
named provider profile has retained live-CLI evidence. Neither is a stable
compatibility claim for `0.2.0`. The plan's world apply/rollback acceptance
remains deliberately unclaimed: the production write catalog is empty pending
the existing typed asynchronous proposal and real-player safety gate.

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
- Provider injection is code-only for tests and smoke. Production selects one
  fixed `openai`, `anthropic`, `deepseek`, `gemini`, or `openai-compatible`
  adapter; no configuration value can select a fake provider.
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
  independently enforces configured concurrency, FIFO queue, and per-player
  cooldown. Phase 12 supersedes its in-memory daily counter with durable
  UTC-daily admission and monthly cost accounting.
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
- Phase 9 capability arguments are closed and required-only. Templates contain
  fixed trusted literals, each declared argument appears exactly once as a
  standalone placeholder, and Brigadier is used only through `parse`.
- Capability identity hashes the closed typed manifest with RFC 8785. Decimal
  bounds must survive an exact IEEE-754/JCS round trip before an identity can
  exist, so distinct source decimals cannot collapse to one approval hash.
- Capability files marked `example` or `draft`, Console-source manifests,
  plugin mismatches, and manifests without an exact Paper-owned
  `(ID, version, SHA-256)` approval are permanently non-effective.
- Phase 9 publishes immutable generation snapshots and diffs, but no registry
  entry contains an executor. Catalog membership cannot authorize Bukkit or
  command mutation.
- Phase 10 uses one raw-JSON `minecraftagent:client` Custom Payload channel.
  Paper binds it to the actual player and positive connection generation; no
  client payload carries or establishes player identity.
- Client features and structured views use exact version intersections. Every
  completion keeps private `fallbackText`; an absent, vanilla, old, or
  incompatible client receives only that fallback.
- Phase 10 transfer limits are 24 KiB decoded per chunk, 1 MiB compressed and
  uncompressed per view, 64 chunks, 2 MiB pending per client, and 15 seconds.
  Reassembly, gzip, and hashes run away from the render loop.
- `client.ack`, UI actions, selections, Litematica state, and material results
  are presentation facts only and cannot raise server permissions, satisfy a
  proposal, or authorize execution.
- The only supported Litematica tuple is Minecraft 1.21.11, Fabric Loader
  0.19.3, Litematica 0.26.12, and MaLiLib 0.27.16. Missing or different versions
  disable only the optional adapter; the base overlay still loads.
- Phase 11 Markdown roots are private and bounded. `server_rules` rank before
  `local_docs`, but every excerpt remains untrusted data and keeps a citation.
- Projects use Runtime SQLite schema v2, authenticated server/player ownership,
  optimistic revisions, and a 20-active-project limit per owner.
- Landmarks are Paper-owned and permission-filtered before matching, counts,
  sorting, and truncation. Same-dimension results use live-player distance.
- Recipe v2 presentation and its text fallback come only from a successful
  `server_registry`/`authoritative` result; model text cannot supply recipe facts.
- Paper is the only build-preview producer trusted for publication. Runtime
  build views are removed, and Paper's request/player-bound view registry is
  disabled unless `MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true` is set exactly.
- A valid preview and native `.litematica` are presentation artifacts. Receipt
  does not auto-load a placement, and load/Material List/ACK state is never
  proposal or world-write authority.
- Phase 12 keeps the universal command Offline gate: every non-toggle form
  returns exactly `AI offline` before permission or management work. Online
  status, doctor, capabilities, and costs use independent permissions.
- Reload is Console/Owner-only. The strict complete candidate is loaded on the
  worker; only owners plus the complete `SecurityPolicy` publish as one CAS
  generation. Every transport, storage, identity, and Capability configuration
  change is restart-required, and any failed or stale attempt retains the old
  snapshot.
- Doctor client diagnostics are anonymous aggregates of negotiated protocol and
  feature versions plus Litematica status/dependency/adapter-version groups.
  They contain no player UUID, connection generation, or local path and remain
  diagnostic rather than authorization input.
- Runtime SQLite schema v3 persists idempotent provider-round usage, UTC
  day/month aggregates, per-player daily admission, and budget reservations.
  Pricing and reservations are explicit integer micro-USD configuration; Paper
  queries only the bounded aggregate through the authenticated management-cost
  exchange.
- Phase 14 keeps strict `configVersion: 2`. Official provider profiles retain
  fixed default URLs and may set a reviewed native-protocol `baseUrl`;
  `openai-compatible` requires a reviewed Chat Completions base URL.
- Explicit base URLs allow HTTPS or literal loopback HTTP only, reject
  userinfo/query/fragment/redirects, and emit only `MODEL_CUSTOM_BASE_URL` plus
  `/model/baseUrl`. The selected API key and model request are still disclosed
  to that endpoint.
- Provider selection is static. Runtime does not automatically retry, fall back,
  rotate models/keys, autodetect protocol, or fetch pricing. The selected model
  must support the adapter's serial tool-call contract.

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

## Phase 9: fail-closed Capability Packs

Completed:

- [x] The shared closed Capability Schema and semantic fixtures cover draft and
      example status, required-only arguments, fixed command roots, numeric
      plugin ranges, effect limits, confirmation policy, and reversal targets.
- [x] Paper performs bounded JSON/YAML discovery with strict UTF-8,
      `SafeConstructor`, a closed typed parser, private ownership/mode checks,
      symlink and hard-link rejection, per-file and aggregate limits, and a
      complete second discovery fingerprint before publication.
- [x] Plugin requirements use a Paper-owned immutable inventory, case-insensitive
      exact names, enabled state, and deterministic one-to-three-component
      numeric version comparisons. Missing, disabled, ambiguous, invalid, or
      mismatched plugins disable the manifest.
- [x] Approval is an exact bounded Paper configuration tuple of capability ID,
      positive manifest version, and lowercase SHA-256. Pack content cannot
      approve itself, and changing hashed content invalidates the tuple.
- [x] Typed codecs reject missing, undeclared, malformed, non-canonical, or
      out-of-range arguments. A fixed template compiler prevents model output
      from changing command literals or argument placement.
- [x] The Brigadier preflight boundary requires a known exact root and complete
      parse context, but calls only `parse`; it has no Bukkit dispatch or
      Brigadier execution path.
- [x] Risk, minimum permission, confirmation, maximum block count, and reversal
      metadata are mapped into immutable effective entries. Console source is
      locally denied, and unknown IDs resolve only as Proposal Only.
- [x] Complete loads publish one immutable generation with deterministic
      `added`, `removed`, `changed`, and `unchanged` sets. Global failures retain
      the prior generation rather than publishing partial or empty state.
- [x] Repository examples are explicitly `example` or `draft`, remain
      non-effective when discovered, and the Paper smoke proves their degraded
      diagnostic without enabling command execution.

## Phase 10: optional client Mod and rich presentation

Completed:

- [x] The shared `client-payload` contract closes the raw JSON envelope and
      direction-specific hello, view begin/chunk/clear, UI control, ACK, and
      error messages. An invalid fixture proves ACK authority fields are
      rejected. Client-to-Paper frames stop at 16 KiB and Paper-to-client frames
      at 40 KiB before parsing.
- [x] Paper binds the actual player to a positive connection generation,
      validates the client hello, records independent feature/dependency
      versions, and selects only exact server-owned view schema intersections.
      Disconnect, world change, Offline, and disable clear transient state.
- [x] Runtime accepts validated Paper-derived client presentation metadata and
      emits unconditional fallback text plus a trusted version `1.0` text view
      when the final application envelope remains within 64 KiB. Otherwise it
      drops the view and preserves the fallback. Paper preserves the private
      fallback whenever no compatible view is selected.
- [x] Paper frames identity/gzip views with per-chunk and complete SHA-256,
      generation/request/view/revision binding, 24 KiB chunks, 1 MiB content,
      64 chunks, eight pending transfers, a 2 MiB uncompressed-byte budget, and
      a 15-second timeout beginning at worker-side preparation. `DISPLAYED`
      closes fallback bookkeeping; rejection, scoped error, timeout, or
      generation replacement sends the private fallback once. None enters
      authorization.
- [x] Fabric registers the single Custom Payload type and performs bounded
      reassembly, strict gzip/UTF-8/hash verification, metadata binding, timeout,
      and disconnect cleanup away from the render loop before scheduling a
      verified update on the client thread. Its single protocol worker queue is
      capped at 256 tasks, client-thread reservations at 128, active
      reassemblies at two, and compressed-byte reservations at 2 MiB.
- [x] The closed version `1.0` decoder and overlay render Text, ItemStack,
      ItemList, and RecipeGrid views. JSON complexity, open-view count, screen
      bounds, scroll, movement, resize, pin/unpin, close, and clear are bounded.
- [x] Item models resolve through the real Minecraft registry and render icons,
      counts, safe components, and vanilla tooltips. Unknown IDs use an explicit
      missing-item state instead of invented content.
- [x] `/agent ui pin|unpin|clear`, client input, and the transparent interaction
      surface reach only presentation state. Position, size, and pin preference
      persist atomically in bounded client-local
      `config/minecraftagent/overlay.json`.
- [x] Litematica remains optional. The resolver enables only Minecraft 1.21.11,
      Fabric Loader 0.19.3,
      [Litematica 0.26.12](https://modrinth.com/mod/litematica/version/b3dJnV8d),
      and [MaLiLib 0.27.16](https://modrinth.com/mod/malilib/version/oaU4Ys3J),
      then links the reviewed signatures behind an isolated reflection adapter.
      Missing, mismatched, or broken dependencies leave the base overlay usable.
- [x] The managed controller bounds and hashes
      `<view-uuid>.<revision>.<artifact-uuid>.litematica`, tracks
      adapter-owned preview load/remove, and opens Litematica's native Material
      List HUD. It reads and hashes at most 16 MiB on the protocol worker, then
      performs final metadata checks and reflected calls on the client thread.
      A runtime adapter failure fails only that operation.

## Phase 11: foundational business modules

Implemented read/preview scope:

- [x] Runtime loads Markdown only from configured relative private roots with
      owner/mode/link/UTF-8/change checks and fixed root/depth/file/byte/AST/chunk
      budgets. Search keeps stable citations, returns bounded excerpts, orders
      `server_rules` first, and treats every document as untrusted data.
- [x] Runtime SQLite migration v2 adds project rows and revision events. The four
      project tools derive ownership from authenticated server/player context,
      cap each owner at 20 active projects, and use exact optimistic revisions.
      Mutations require direct imperative player intent, reject questions,
      hypotheticals, and negations, and are limited to one successful mutation
      per request; build preview requires an exact same-request project read.
- [x] Paper loads a closed private `0600` `landmarks.yml`, rejects unsafe YAML
      and filesystem state, filters live permissions before all observable result
      metadata, and sorts same-dimension matches by live-player distance.
- [x] Recipe view v2 preserves every supported layout, ingredient-choice kind,
      dynamic result, processing value, source/provider, and remaining item.
      Runtime builds the view and same-source text fallback only from the
      successful authoritative server-registry tool result.
- [x] The negotiated client accepts recipe v2 only with `recipeView: 2` and
      supports variant paging, choice cycling, registry icons, and tooltips while
      retaining text fallback for vanilla or incompatible clients.
- [x] `build.preview.create` validates a closed plan and live player, limits each
      axis to 32 and volume to 4096 cells, requires a near/current/loaded target,
      rejects target states and existing cells with block entities, and never
      calls a Bukkit mutation API.
- [x] Paper snapshots at most 128 cells or about 2 ms per primary-thread slice,
      repeats the read to reject a race, then builds deterministic complete-target
      Palette v1 content and domain-separated base-region/change-set hashes on a
      worker.
- [x] Shared Java/TypeScript and Fabric validation covers out-of-order chunks,
      strict single-member gzip, strict UTF-8 and duplicate-free JSON, RFC 8785,
      palette integrity, BlockState resolution, geometry, order, counts, and
      hashes.
- [x] Paper strips Runtime-supplied build views and appends only its short-lived
      request/player-bound artifact. Build-preview publication is an exact
      environment opt-in and remains private-text fallback otherwise. A Paper
      artifact is the exclusive candidate, uses the completion fallback, and is
      invalidated before any later build attempt.
- [x] Fabric deterministically generates and atomically registers a managed
      native Litematica v7 file, waits for an explicit load operation, uses the
      preview origin, and delegates material display to Litematica's native HUD.
      Disconnect removes connection-scoped managed artifacts. Paper exposes
      explicit `/agent ui preview <view-id>` and
      `/agent ui materials <view-id>` presentation commands. Phase 13 adds
      `/agent ui remove <view-id>` for explicit Agent-owned placement/artifact
      removal under the same Online and UI-permission gate.

Deliberately deferred safety scope:

- [ ] Freeze a build artifact into a production write proposal, re-read and
      compare the live region at confirmation, apply through a fixed bounded
      typed adapter, persist durable audit/rollback references off-thread, and
      define conflict and partial-failure behavior. The production write catalog
      remains empty until this complete gate and a real-player integration test
      pass.

## Phase 12: bounded management and durable cost control

Implemented management scope:

- [x] The universal Offline gate precedes command permissions, reload
      authorization, snapshots, and Runtime queries. Only exact `on`/`off`
      bypass it; every other form returns exactly `AI offline`.
- [x] `status`, `doctor`, `capabilities`, and `costs` use independent OP-default
      query permissions. Reload is separately restricted to Console or a player
      in the current Owner UUID snapshot; OP or the named reload permission alone
      cannot authorize it.
- [x] Status and doctor render bounded immutable component, Runtime, request,
      Capability, and client snapshots. Client diagnostics aggregate protocol
      versions, every feature-version distribution, Litematica adapter states,
      and bounded compatibility version groups without UUIDs, names, connection
      generations, credentials, or paths.
- [x] Fabric reports one closed Litematica diagnostic state with bounded
      Minecraft, Fabric Loader, Litematica, MaLiLib, and adapter versions.
      Missing, unsupported, linkage-failed, and preview-storage failure states
      remain presentation diagnostics and cannot raise a client capability.
- [x] Reload reuses the complete strict Paper loader on the worker, validates
      `SecurityPolicy`, and publishes owners plus the full security policy as one
      monotonically versioned CAS snapshot. Admin/toggle and proposal policy
      readers consume that same current generation. Startup candidates publish
      only after Runtime authentication; recovery retains the original trusted
      manager/generation and cannot rebase restart-only fields.
- [x] Server ID, Runtime endpoint/token/timeouts, state directory, Capability
      directory, and approvals are restart-only. Invalid candidates,
      restart-required changes, concurrent attempts, worker rejection, manager
      close, and stale completion retain the previous snapshot and expose only
      stable redacted outcomes.
- [x] Runtime SQLite migrations v3-v5 persist request admissions,
      per-provider-round reservations, idempotent reported/estimated usage
      events, per-player UTC-daily counts, and server-wide UTC daily/monthly
      aggregates. Migration v4 records provider-round start state; startup
      estimates abandoned started rounds and releases not-started reservations
      while preserving settled events and admission counts. Existing v3 ACTIVE
      rows are conservatively marked started during upgrade.
- [x] Migration v5 adds a singleton process-owner row. `BEGIN IMMEDIATE`
      serializes live-owner validation and dead-owner replacement before
      recovery. Disconnect/shutdown cancels queued work before active slots so
      bulk cancellation cannot drain the queue into provider calls.
- [x] Explicit integer micro-USD input/output rates calculate reported cost;
      every provider round reserves budget before the call, a response or
      unknown-billability failure without usage settles at its reservation,
      `NOT_BILLABLE` releases it, and cancellation immediately estimates a
      `STARTED` round while permitting a late reported correction.
- [x] The authenticated application channel accepts the closed
      `management.costs.request` and returns a correlated bounded
      `management.costs.response`. `/agent costs` renders only current UTC
      day/month periods, aggregate requests, reported/estimated provider calls,
      tokens, cost, settled/active reservation exposure, remaining amount, and
      exhaustion; no player identity or per-player breakdown crosses the wire.
- [x] Reload attempts carry an Online operational epoch; final permit validation
      and policy CAS share one gate transition lock and cannot publish after
      `off`, reconnect, or disable. Asynchronous cost/reload replies repeat the
      Online and authority checks at the final main-thread rendering boundary.

Deliberately bounded scope:

- [x] Online reload changes only owners and `SecurityPolicy`; it does not reload
      Runtime configuration, transport/storage, landmarks, knowledge roots, or
      Capability catalog content. Those changes require their existing stopped
      maintenance/restart path.
- [x] The production write catalog remains empty. No management command,
      diagnostic, reload generation, cost record, Capability entry, or client
      declaration can create a proposal or mutate Minecraft state.

## Phase 13: gated release candidate

Implemented automated scope:

- [x] All components use candidate version `0.1.0`. Packaging removes stale
      Runtime/JVM output, selects exact-version JARs, disables Gradle build cache,
      normalizes archive timestamps/order, and emits complete installation and
      upload `SHA256SUMS` files.
- [x] Gradle dependency verification records SHA-256 for the complete
      Gradle-resolved downloaded graph, including the exact Paper API snapshot. One
      four-field trust entry per current Loom-local generated JAR covers 48
      non-reproducible artifacts: layered mappings, merged Minecraft, and remapped
      Fabric API. Their POMs and Gradle-resolved inputs remain pinned; Loom verifies
      direct Minecraft and mappings downloads against Mojang manifest SHA-1 values.
      Ordinary strict and offline builds validate the final metadata.
- [x] `verify-dist.sh` requires the Phase 13 file set, 50 exact schemas, Runtime
      entry layout, matching non-SNAPSHOT component versions, expected JAR
      descriptors/classes/schemas, an exact installation surface, canonical and
      duplicate-free nested JAR entries, bounded extraction, safe paths/modes, no
      private state or credential patterns, and a complete valid checksum manifest.
- [x] `release-check.sh` runs the full serial test lane, pinned real Paper smoke,
      npm audit, archive extraction/re-audit, and a second uncached clean build,
      then compares complete dist and release checksums. Required suites and the
      Phase 13 minimum test inventory fail closed before reports are preserved.
- [x] Read-only GitHub workflows run Bash and PowerShell orchestration and can
      upload a temporary commit-bound candidate. They never create a tag or
      GitHub Release.
- [x] `SECURITY.md`, `CLIENT-COMPATIBILITY.md`, ADR 0011, and the manual client
      checklist define the preview-only release, compatibility tuple, disclosure,
      network, and evidence boundaries. GitHub private vulnerability reporting is
      enabled for `AGelysia/TAgent`.
- [x] MockBukkit `4.110.0` supplements the real Paper smoke with a live Bukkit
      player permission and request-identity submission boundary. Its supported
      JUnit `6.0.3` extension owns the mock lifecycle. Deterministic Capability
      mutation tests cover malformed manifests, paths, types, sizes, numeric/JCS
      edges, templates, descriptors, and runtime arguments.
- [x] `/agent ui remove <view-id>` maps to the existing client preview-remove
      action with canonical UUID parsing, `minecraftagent.ui`, universal Offline
      gating, usage, completion, and focused tests.
- [x] A repository-only deterministic Runtime provider and isolated manual server
      launcher drive fixed text, authoritative recipe, project, live context, and
      3-by-2-by-2 build-preview flows. Each run rebuilds one clean commit, stages
      the complete verified candidate in a fresh private session, installs from
      its candidate lockfile, and injects the test provider beside the staged
      Runtime. It requires exact authenticated access lists, explicit shutdown,
      and payload fingerprints. The fake provider cannot be selected through
      production configuration and is excluded from `dist`.

Manual and publication scope:

- [x] Run Vanilla, Agent Client, and the exact Litematica/MaLiLib client profiles
      from a physical machine and record sanitized evidence for real handshake,
      private fallback, overlay/recipe graphics, native preview/material/remove,
      diagnostics, and disconnect recovery.
- [x] Resolve or record `BLOCKED` for graphical fixtures that cannot be reached
      safely only where the manual gate explicitly allowlists it. Any failure,
      fingerprint mismatch, core blocker, or unlisted blocker rejects the
      candidate.
- [ ] Run native Windows packaging/runtime acceptance if Windows support is to be
      claimed. PowerShell CI currently validates orchestration on the POSIX
      security boundary only.
- [ ] Create the final commit/tag/GitHub Release only after the manual record is
      `ACCEPTED` under its mandatory-core/allowlisted-blocker rule and the final
      clean-build fingerprints match. No publication action is automated.

Phase 13 manual acceptance recorded on 2026-07-15:

- Maintainer-attested graphical sessions used two physical clients against the
  migrated cloud-host harness. Vanilla, base Agent Client, and the exact
  Litematica/MaLiLib profile passed every mandatory core lane, including private
  ordinary-player commands, authoritative recipe presentation, explicit preview
  load/material/remove, cleanup, and Runtime disconnect/recovery.
- Bare `/agent` required OP as designed because it aliases the administrative
  status query. Ordinary-player `/agent say`, `/agent module`, and `/agent ui`
  remained available without OP.
- The checklist's explicitly non-core processing/unsupported recipe, unknown
  Item ID, multi-chunk, dependency/version, and controlled adapter/storage failure
  fixtures remained `BLOCKED`; there were no mandatory blockers or unresolved
  failures. Native Windows equivalence remains unclaimed.
- The tested commit was `3735c5ef7fe55f04fff499b257e72e71707c47c0`.
  Paper, Client, dist manifest, Runtime manifest, protocol manifest, and archive
  SHA-256 values were respectively `468a54dba0cd1cbc79d68831b2e78290a38614fd58438241f2e9b1927f16629f`,
  `44b3048e3cc2163008579485f5121e03b0ef78889f680f24f67a899a287d308a`,
  `34fa1378a1cc46ca6e69332abe59d49cec5712016ed129e151aae2d734cb6a70`,
  `17632450b6dfe9f7e8c3014a126753169c05ce0733971d6025f6e31d511b8915`,
  `8911de23fd119adc6c424c27e6f1b598fd35948c32fa7ab1eb3629fc3bdb2f8e`,
  and `4776c7a11d80a9558df82a620f21bcb7422a43fd91b60c0583413d1f7bd3f136`.
  The values matched a fresh local fingerprint before this package-excluded
  acceptance/progress evidence update.
- Raw working files were not imported into this Git workspace. The sanitized
  environment and fingerprint transcripts plus maintainer PASS/BLOCKED outcomes
  form the retained development record. No tag or GitHub Release has been
  created because the then-planned Phase 14 provider expansion and a new final
  candidate gate remained before publication.

## Phase 14: multi-provider and controlled endpoints

Implemented scope:

- [x] Strict `configVersion: 2` accepts `openai`, `anthropic`, `deepseek`,
      `gemini`, and `openai-compatible`. Existing OpenAI configuration remains
      valid; only `openai-compatible` requires `model.baseUrl`.
- [x] The production factory selects OpenAI Responses, Anthropic Messages,
      DeepSeek Chat Completions, Gemini stateless `generateContent`, or
      OpenAI-compatible Chat Completions without provider fallback or protocol
      autodetection.
- [x] Native adapters implement bounded model health, authentication,
      non-streaming strict-JSON generation, usage mapping, serial tool calls,
      correlated continuation, timeout/cancellation, and safe status mapping.
      DeepSeek thinking is explicitly disabled; Gemini reconstructs its bounded
      content sequence without a provider session handle.
- [x] DeepSeek aggregate prompt usage retains the single configured input rate,
      whose documented safe value is the higher cache-miss rate. Gemini usage
      sums prompt plus tool-use prompt input and candidate plus thinking output.
      HTTP failures from explicit custom endpoints remain
      `BILLABILITY_UNKNOWN` and settle the active reservation conservatively.
- [x] Every official profile may override its base URL. Configuration accepts
      HTTPS or literal `127.0.0.1`/`[::1]` HTTP only, rejects credentials,
      query, fragment, control characters, and redirects, and canonicalizes the
      retained path. Explicit URLs emit `MODEL_CUSTOM_BASE_URL` with only the
      known field, never the configured value.
- [x] Provider factory, configuration, endpoint safety, health, generation,
      tool continuation, malformed/oversized response, usage, abort, and safe
      failure cases have focused offline tests using synthetic credentials and
      injected HTTP implementations.
- [x] ADR 0012, configuration/environment examples, and architecture,
      operations, security, README, and plan documentation define the provider
      and custom-endpoint trust boundary.

Pre-publication acceptance decision:

- [x] The complete clean release-candidate lane fixed a new Phase 14 fingerprint.
      The accepted Phase 13 fingerprint remains historical evidence for commit
      `3735c5e`, not a substitute for the changed Runtime.
- [x] The maintainer attested that the exact `0.2.0` cloud and two-physical-client
      run passed every exercised item except native Litematica projection. That
      client lifecycle remains explicitly `INCOMPLETE`, is not relabelled a pass,
      and is accepted only as a visible prerelease limitation.
- [x] An operator-configured real-provider Minecraft path returned the expected
      AI response. The deployed profile and exact billable CLI transcript were
      not retained, so the public live-provider claim set is empty. The five
      released adapters are implementation/offline-contract claims only.
- [x] Pricing, provider-account behavior, arbitrary compatible endpoints, live
      timeout, and native Windows equivalence remain operator validation tasks
      rather than `0.2.0` compatibility claims.
- [x] The owner authorized a controlled `v0.2.0` prerelease with those limits.
      Publication still fails closed unless the final local comparison and the
      commit-bound GitHub Release Candidate workflow pass without changing any
      payload hash.
- [x] The first manual Release Candidate run failed before publication because
      GitHub's current `ubuntu-24.04` image lacked `rg`; the Runtime READY marker
      was present, but Paper smoke could not search its log. No tag, Release, or
      candidate artifact was created. The workflow now installs `ripgrep`
      explicitly from Ubuntu before running the release gate.
- [x] A fresh private `prepare` on workflow-fix commit
      `72cd98c4d5d7710d2171e550eeddc18b2edc830f` passed the complete local release
      check. Its version, Paper, Client, dist, Runtime, protocol, and archive
      fields match the cloud-tested candidate exactly, and its
      `release.SHA256SUMS` is byte-identical. The manual attestation is rebound
      only on that payload identity; a new final compare and successful GitHub
      workflow run remain mandatory.

Final validation preparation implemented on 2026-07-16:

- [x] The next candidate is versioned `0.2.0`, so the Phase 14 payload does not
      reuse the historical accepted `0.1.0` version.
- [x] A packaged, explicitly billable live-provider CLI checks readiness, text,
      one exact tool call, continuation, positive reported usage, per-round
      timeout, and safe failures. Its evidence identity contains only the fixed
      provider profile, DEFAULT/CUSTOM mode, and a domain-separated model-name
      HMAC-SHA-256 keyed by the private Runtime token; it never prints a URL,
      key, model identifier, prompt, response, or continuation.
- [x] The distribution now includes reviewed split-user systemd examples,
      private Paper/Runtime environment templates, secure Paper properties, and
      the complete cloud validation checklist. Exact distribution verification
      rejects a missing or changed deployment surface; release verification
      installs only production dependencies in an extracted copy and executes
      the compiled Provider CLI without TypeScript sources or development
      dependencies.
- [x] `scripts/final-validation.sh` creates a new private repository-external
      evidence directory only from a clean tree, runs the canonical release
      lane, binds the tested fingerprint, and later compares all seven payload
      fields without overwriting evidence.
- [x] `docs/phase14-final-acceptance-record.md` contains the sanitized final
      prerelease decision. The historical Phase 13 record remains unchanged.
- [x] The exact automated evidence remains private and append-only. The migrated
      manual checklist itself was not returned, so manual results are identified
      as maintainer attestations instead of being represented as raw evidence.

Clean `0.2.0` candidate preparation recorded on 2026-07-16:

```bash
./scripts/final-validation.sh prepare \
  /absolute/private/phase14-evidence
```

- The command started from clean candidate commit
  `3fd09598f61f6223504ad997a6135373523c8e69`, completed the canonical
  release check, and created a private repository-external evidence directory.
  Generated anchors and test results are `0400`; editable checklist/record
  copies are `0600`; every evidence directory is `0700`.
- Runtime passed all 26 suites and 287 tests, Paper passed 59 suites and 463
  tests, and Client passed 17 suites and 210 tests. Every inventory reported
  zero failures, errors, and skips. The extracted production Runtime installed
  without development dependencies, and both Runtime and Provider CLI entries
  executed through the deployment-style `current` symbolic link.
- The pinned Paper `1.21.11-132` smoke passed `offline-lifecycle`,
  `unavailable`, `wrong-token`, and `incompatible` with SHA-256
  `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`.
  npm reported zero high-severity vulnerabilities.
- Both uncached package builds produced identical release manifests. The Paper
  JAR is 752,661 bytes with SHA-256
  `213acda1974d39a65d0fcc9ac8902816284019d01bef8b0c37b9c95c75263d53`;
  the Client JAR is 330,677 bytes with SHA-256
  `f823309e9f22ba505d5fcccc7f107c0f3d9dcee84edf24341b83eb03c9bd38e2`;
  and the 1,300,419-byte `MinecraftAgent-0.2.0.tar.gz` hashes to
  `2326361b0ad606adf96e3d104174822786118b2811a1d38b88a6c3a6a0638a75`.
- The dist, Runtime-subset, protocol-subset, and outer release-manifest hashes
  are respectively
  `4c7369864c3ff842c117af9e24c147f06543c5a9e850057663ac67c112c09dad`,
  `904c2a17907287fb97910e00aef045e69c7c23e857eb5602da2e0ea7a5286b89`,
  `8911de23fd119adc6c424c27e6f1b598fd35948c32fa7ab1eb3629fc3bdb2f8e`,
  and `3a9e34bebc2641becdc214d46e805f7a26cf1524910955d83a5cd4a8256a8656`.
- This preparation itself used no real Provider credential and made no cloud or
  graphical-client claim. Subsequent testing was maintainer-attested on
  2026-07-16: all exercised cloud, real-provider Minecraft, and two-client items
  passed except the exact-Litematica native projection lifecycle. The owner
  accepted that gap for a prerelease; no named live-provider profile or live
  timeout is claimed.

## Verification

Phase 14 automated development verification recorded on 2026-07-15:

```bash
MINECRAFT_AGENT_ALLOW_DIRTY_RELEASE_CHECK=I_UNDERSTAND_THIS_IS_NOT_A_RELEASE \
  ./scripts/release-check.sh
```

- This was deliberately a dirty-worktree development check before the Phase 14
  implementation commit. It is not release evidence and does not replace the
  pending live-provider or physical-client acceptance gates.
- Runtime passed Prettier, ESLint, TypeScript build, and all 24 Vitest files
  with 275 tests. Paper passed 59 suites and 463 tests; Client passed 17 suites
  and 210 tests. Every inventory lane reported zero failures, errors, or skips,
  including the four native/compatible provider suites and provider factory.
- Both uncached package builds passed the exact 50-schema distribution audit and
  produced identical complete checksums. The Runtime manifest hashes to
  `90034d533f4e7ee8e989317c01621474f642982aed74e3c275d0d5ac8f47c7cd`,
  the dist manifest hashes to
  `ca59f5a5ebfadfe06a2514c030baf054d2f105b4736755777469198f1ddb811f`,
  and the protocol manifest remains
  `8911de23fd119adc6c424c27e6f1b598fd35948c32fa7ab1eb3629fc3bdb2f8e`.
- The pinned Paper `1.21.11-132` smoke passed `offline-lifecycle`,
  `unavailable`, `wrong-token`, and `incompatible`; `npm audit` inspected 251
  packages and reported zero vulnerabilities.
- Paper and Client payloads remain respectively
  `468a54dba0cd1cbc79d68831b2e78290a38614fd58438241f2e9b1927f16629f`
  and `44b3048e3cc2163008579485f5121e03b0ef78889f680f24f67a899a287d308a`.
  The changed `MinecraftAgent-0.1.0.tar.gz` hashes to
  `6b0b88da70893e3dcf16a9e52cca78d7ed83a1d3678fa02eeba3b8fc168afe2c`.
- No real provider credential or external compatible endpoint was used. Those
  checks remain explicit pre-publication gaps rather than inferred passes.

Phase 13 automated development verification recorded on 2026-07-15:

```bash
JAVA_HOME=/home/elysia/.local/share/jdks/temurin-21 \
MINECRAFT_AGENT_ALLOW_DIRTY_RELEASE_CHECK=I_UNDERSTAND_THIS_IS_NOT_A_RELEASE \
  ./scripts/release-check.sh
```

- This was deliberately a dirty-worktree development check because the Phase 12
  and Phase 13 implementation has not yet been committed. The canonical manual
  and release lanes still require a clean commit at entry and exit; these results
  are not publication evidence.
- Runtime passed Prettier, ESLint, TypeScript build, and all 20 Vitest files with
  167 tests. Paper passed 59 suites and 463 tests; Client passed 17 suites and 210
  tests. Every lane had zero failures, errors, or skips, and the inventory gate
  found the required manual-provider, usage-accounting, MockBukkit, deterministic
  Capability fuzz, Litematica, and shared-contract suites.
- Strict offline, no-cache JVM reruns passed with JUnit/Jupiter/Platform `6.0.3`,
  MockBukkit `4.110.0`, and Byte Buddy core/agent `1.18.8`. Final Gradle metadata
  contains the exact Paper snapshot; its trusted-artifact entries are limited to
  the 48 exact non-reproducible Loom-local generated JARs described above.
- The pinned Paper `1.21.11-132` smoke retained SHA-256
  `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`
  and passed `offline-lifecycle`, `unavailable`, `wrong-token`, and
  `incompatible`. Every Paper instance used a random loopback-only port verified
  from both startup log and `ss`; Runtime remained at `127.0.0.1:38127`. No test
  process or listener remained afterward.
- `npm audit` inspected 251 packages and reported zero vulnerabilities. GitHub
  private vulnerability reporting was enabled and read back as `enabled=true`.
- Both uncached builds produced identical 215-file installation trees (214
  manifest entries) and release artifacts. `dist/SHA256SUMS` hashes to
  `1106467025d933190cab44c8662ff1f171804100b929526ba3f34b38e612bb67`;
  the Runtime subset hashes to
  `17632450b6dfe9f7e8c3014a126753169c05ce0733971d6025f6e31d511b8915`,
  and the protocol subset hashes to
  `8911de23fd119adc6c424c27e6f1b598fd35948c32fa7ab1eb3629fc3bdb2f8e`.
- `MinecraftAgent-Paper.jar` is 752,660 bytes with SHA-256
  `468a54dba0cd1cbc79d68831b2e78290a38614fd58438241f2e9b1927f16629f`;
  `MinecraftAgent-Client-Fabric.jar` is 330,677 bytes with SHA-256
  `44b3048e3cc2163008579485f5121e03b0ef78889f680f24f67a899a287d308a`;
  and the 1,255,633-byte `MinecraftAgent-0.1.0.tar.gz` hashes to
  `de7ed8c4d486f4b1ffb6be01ac5a567b418f5aa998f0e2c510459587f254409f`.
- Native PowerShell/Windows behavior was not run on this Linux VM because `pwsh`
  is not installed. The pinned GitHub workflows provide the POSIX PowerShell
  orchestration lane, but Phase 13 makes no native Windows equivalence claim.

Phase 12 full implementation verification recorded on 2026-07-14:

```bash
./scripts/test.sh
./scripts/paper-smoke.sh
./scripts/package.sh
```

- Runtime passed Prettier, ESLint, TypeScript build, and all 19 Vitest files with
  166 tests. New coverage includes SQLite v3-v5 migration, exact micro-USD
  calculation, UTC/restart-stable admission, concurrent reservation,
  reported/estimated idempotent settlement, cancellation/error/late response,
  queue-safe bulk cancellation, transactional process ownership, Tool-loop
  accounting, and the authenticated management-cost exchange.
- The full Paper build passed Spotless and 457 tests with no failure, error, or
  skip. Coverage includes the universal Offline gate, independent management
  permissions, Console/Owner-only reload, complete candidate validation,
  restart-required fields, epoch-atomic CAS publication and stale/failure retention,
  redacted management snapshots, aggregate costs, and Runtime correlation.
- The full Fabric build passed Spotless and 210 tests with no failure, error, or
  skip. Coverage includes the closed client diagnostic states, dependency and
  adapter versions, safe metadata fallback, generation replacement/cleanup, and
  proof that diagnostics do not raise negotiated capabilities or authority.
- The pinned Paper `1.21.11-132` smoke verified SHA-256
  `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`
  and passed `offline-lifecycle`, `unavailable`, `wrong-token`, and
  `incompatible`. The lifecycle case exercised real Console status,
  capabilities, authenticated aggregate costs, unchanged atomic reload, and
  exact `AI offline` short-circuiting for every new management command.
- Packaging produced 209 files (2.7 MiB) with private-safe deterministic modes.
  `MinecraftAgent-Paper.jar` is 752,539 bytes with SHA-256
  `279f0f2faa5b370e67ebd5dea84e61bdeb1bf30f736551d9d4da29ccfc52412c`;
  `MinecraftAgent-Client-Fabric.jar` is 330,685 bytes with SHA-256
  `7a4cf0b8c07d4f1f58d0b50c09fed43da3972b248eca83e9a7cdfbbb3be05c10`.
- This automated lane used no graphical Fabric client, real Litematica/MaLiLib
  UI, or real online player and makes no claim for those manual integrations.
  Phase 13 release completion is also not claimed here.

### Phase 11 completion lane

Phase 11 verification recorded in the final serial completion lane:

```bash
cd agent-runtime
npm run check

cd ..
./gradlew --no-daemon --max-workers=1 :paper-plugin:test
./gradlew --no-daemon --max-workers=1 :client-mod:test :client-mod:spotlessCheck
./scripts/paper-smoke.sh
./scripts/package.sh
```

- Runtime full check passed with 18 Vitest files and 142 tests, plus TypeScript,
  ESLint, and Prettier. New coverage includes private Markdown loading/search,
  server-rule priority/citations/injection handling, project ownership/revisions,
  local execution, recipe-authority selection, and complete Palette semantics.
- Fabric's full test suite passed with 198 tests and Spotless. It includes the
  shared manifest plus recipe v2 decoding/state, strict build-preview decoding,
  native Litematica writing, managed atomic publication, explicit load, and
  cleanup tests.
- The shared protocol manifest contains 115 cases; its JVM contract class passed
  116 tests (including a direct gzip-header case), and the full Paper lane
  passed 407 tests. Paper and Fabric Spotless checks also passed. Focused
  TypeScript build-preview semantic coverage also passed. New fixtures
  include recipe v2, local tools, landmarks, empty targets, out-of-order chunks,
  concatenated gzip, duplicate JSON keys, noncanonical content, and bad palette
  hashes.
- Focused Paper landmark, build snapshot/artifact, authoritative-view filtering,
  recipe-v2 validation/selection, command, and registry tests passed. The pinned
  Paper `1.21.11-132` smoke verified SHA-256
  `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` and
  passed `offline-lifecycle`, `unavailable`, `wrong-token`, and `incompatible`.
- `scripts/package.sh` reran the serial Runtime, Paper, and Fabric lanes and
  produced `dist/MinecraftAgent-Paper.jar` (664,596 bytes, SHA-256
  `3ffefb24510de417de6f04ed22fbe244168320e0c2e6aac50e6a22e3a352bc76`) and
  `dist/MinecraftAgent-Client-Fabric.jar` (321,726 bytes, SHA-256
  `1b0542120ed1a01cc83bb8eb06629f1d124c2cb7dcb731ead30b9ecb6994a6e9`).
- No graphical Fabric client or real Litematica/MaLiLib UI ran on this headless
  host, so this phase makes no graphical rendering or interaction claim.

### Phase 10 baseline

Phase 10 was verified serially on 2026-07-13 with:

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

- Runtime: 14 Vitest files, 109 tests passed; TypeScript build, ESLint, and
  Prettier passed; full and production-only npm audits reported 0
  vulnerabilities. Tests cover safe Unicode fallback generation, the trusted
  text-view builder, nonzero display-only capability acceptance, and final
  UTF-8 64 KiB envelope downgrade to fallback-only output.
- Paper: build and Spotless passed; 354 tests passed, including 90 shared dynamic
  Schema/HMAC/semantic cases. Phase 10 coverage includes canonical client
  payload decoding, actual-player generation binding, exact feature selection,
  framing/gzip/hash budgets, timeout and scoped-error fallback, re-hello and
  Offline races, idempotent reservation cleanup, `/agent ui`, and proof that ACK
  data never enters authority. Existing lifecycle, request/session/tool,
  proposal/audit, Capability, command-map, and private-reply suites also passed.
- Fabric: remapped JAR build and Spotless passed; 140 tests passed, including the
  same 90 shared protocol cases. The remaining tests cover bounded network and
  client-thread queues, strict reassembly/gzip/view decoding, overlay state and
  preferences, item/recipe view models, generation-safe presentation dispatch,
  and exact-version Litematica selection, managed-file preparation, placement,
  removal, and native Material List HUD bindings.
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
  from the provider, example Capability publication as generation 1, permanent
  `EXAMPLE_ONLY` disablement, degraded doctor output, and exception-free plugin
  disable. This smoke used no Fabric client and makes no graphical presentation
  claim. No Paper, Runtime, or Gradle process remained.
- Packaged Runtime preserved its compiled authenticated application endpoint,
  OpenAI tool-loop provider, migrations, session/message repository, context
  reducer, Module Manifest, fixed Tool Registry, configuration template, and
  all shared read-tool/proposal/Capability/client-payload schemas. The Paper JAR contains the
  fixed read registry, Bukkit adapters, Paper-owned proposal/audit classes,
  embedded JCS implementation, bounded Capability loader, exact approval and
  immutable registry types, argument/template compiler, parse-only Brigadier
  preflight, client channel, bounded transfer/fallback state, and the same
  schemas. The Fabric JAR contains the closed client codec, bounded task and
  transfer queues, overlay/view decoders, preferences, and optional Litematica
  adapter. Artifact SHA-256 values are
  `736bfdb86aa1a7ce14636db16ba4f646f8e2d473641ddaa54211cc5bd7d8e3ab`
  for `MinecraftAgent-Paper.jar` and
  `60281fab8bb725676ff7cb5e246d3c224b5a57ed6a79f608b816a0af84e58c28`
  for `MinecraftAgent-Client-Fabric.jar`. The package includes only explicitly
  marked Capability examples and drafts; the production write catalog remains
  empty.
- All protocol JSON files parse successfully; Bash scripts pass `bash -n`.

PowerShell scripts were reviewed for native exit-code propagation but were not
executed because `pwsh` is unavailable on this Linux host. A graphical Fabric
client, real registry icon/tooltip rendering, a real online player executing
`/agent say`, a live OpenAI tool call, and Litematica were not started.
Ordinary-player UUID binding, primary-thread
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
- Online Capability Pack content reload or any capability command execution.
  Phase 12 `/agent reload` publishes only owners plus `SecurityPolicy`; Phase 9
  still loads Capability metadata only through startup/recovery, and no executor,
  Bukkit dispatch, proposal-creation route, or Runtime capability handler exists.
- A production build write proposal, confirmation-time region re-read, bounded
  world apply, partial-failure/rollback policy, or durable rollback reference.
  Phase 11 deliberately stops at a reproducible Paper-owned preview and keeps
  the production write catalog empty.
- Automatic preview placement, Easy Place, printer behavior, or a client result
  that can authorize server behavior. Native schematic load and Material List
  remain explicit local presentation operations.
- Live knowledge, landmark, or Capability reload. Their validated snapshots are
  installed only through startup/recovery paths; do not edit the trees while
  their owning process is running.
- Native Windows equivalence and the Phase 13 allowlisted non-core graphical
  fixtures remain unverified. The mandatory physical-client lanes were
  maintainer-attested on the migrated cloud harness; raw working files were not
  imported into this Git workspace.

The presence of a schema or proposal domain object does not mark a write tool
implemented.

## Known design risks

### Provider boundary

Phase 14 supplies five fixed profiles using OpenAI Responses, Anthropic Messages,
DeepSeek/OpenAI-compatible Chat Completions, or Gemini `generateContent`, with
safe status mapping, bounded bodies, a serial strict-function loop,
timeout/cancellation, and no prompt/completion logging. It does not retry,
stream, fall back, rotate providers, or fetch pricing. A custom base URL is an
operator trust decision: URL validation and redirect refusal do not prove the
endpoint is private, honest, tool-compatible, or compliant with the native
protocol. Phase 12 persists provider-round usage and applies the configured
monthly reservation-based conservative admission bound from operator-supplied
micro-USD rates and reservations. It is not a provider billing cap: post-paid
reported cost can exceed both a round reservation and the local bound before
later calls are blocked. Provider
account-side pricing, retention, and policy remain operator responsibilities
even though Runtime requests storage off where the selected protocol supports it
and retains no provider conversation handle.

### Node SQLite stability

Node 22's built-in synchronous SQLite API avoids a native addon on this small
host, but Node still labels it active development and emits an ExperimentalWarning.
Phase 6 uses it for indexed, hard-limited context reads and short atomic
exchange writes; Phase 12 adds short admission, reservation, event, and aggregate
transactions. No transaction spans provider I/O. The driver remains experimental
and should be reassessed before higher-volume deployment or unbounded data
features.

### Conditional command registration

ADR 0001 selects public late `CommandMap` registration and the exact Paper smoke
proves both labels and conditional absence. An initial failure deliberately has
no in-process recovery path and requires restart. Unit tests prove that
`Player#updateCommands()` is invoked, but a real connected client was not part
of the smoke; observing its refreshed Brigadier tree remains a later integration
test gap.

### Litematica compatibility

Litematica and MaLiLib internals are exact-version sensitive. Phase 10/11 lock one
tuple and isolates reflected calls behind `litematica-reflection-1`; every other
tuple fails closed while the overlay stays available. Focused tests prove
optional class loading, signature selection, strict Palette decoding, native
schematic generation, managed preview lifecycle, and native Material List HUD
calls through test bindings. A graphical client with
the released 0.26.12/0.27.16 artifacts has not run on this host, and no
compatibility is implied for newer releases. Each additional tuple requires a
new reviewed matrix entry and real-mod integration lane.

### Large world writes

A preflight region hash does not make a multi-tick update atomic. The first
write implementation must use conservative limits and define conflict and
partial-failure behavior before raising block limits.

### Third-party command parsing

Paper plugins do not universally expose a side-effect-free Brigadier validation
path. A command-backed Capability remains disabled unless complete preflight
parsing is demonstrated for the locked plugin version; otherwise use a typed
adapter.

### Capability filesystem and runtime binding

Phase 9 detects ordinary concurrent changes with private ownership/mode checks,
stable reads, and two complete discovery fingerprints. Path-based traversal
cannot eliminate every intermediate symlink swap or restored ABA state by a
hostile writer running as the same OS UID or root, so operators must stop Paper
before changing packs or approvals. Production also relies on the previously
validated single-owner `0700` plugin data ancestor; the loader does not compare
the candidate root owner with the process effective UID. Execution, online
reload, reuse outside that composition, or a stronger local threat model
requires an explicit trusted-owner input and descriptor-relative
`SecureDirectoryStream` traversal.

Catalog loading produces an unpublished candidate. Phase 12 binds publication
to the authenticated Runtime application and current coordinator attempt, so a
failed or stale handshake retains the active generation. Later plugin
enable/disable events update only the inventory snapshot. Before any execution
adapter is added, every invocation must still recheck catalog availability plus
the required plugin's live enabled state and version; a retained catalog alone
is not authority.

### Proposal integration coverage

The pinned Paper smoke exercises startup, Offline, restart, Runtime loss, and
command-map lifecycle without a real connected player. It does not create or
click a proposal. Focused JVM tests cover live OP/Owner/permission changes,
request and catalog binding, expiry, hash tampering, atomic double-click
behavior, Offline/quit invalidation, fixed Adventure actions, and audit
redaction; a real-player click remains a later integration lane.

## Next gates

1. Publish only the fixed `v0.2.0` controlled prerelease after both final
   comparison gates pass. Keep its Litematica, provider-profile, Windows, and
   no-world-write limitations visible in the GitHub notes.
2. Exercise native preview load, placement inspection, Material List, removal,
   changed-position reload, and disconnect cleanup on the exact Litematica tuple
   before promoting that adapter from experimental in any successor release.
3. Complete live-key acceptance for each provider profile intended to gain a
   named compatibility claim, including one reviewed `openai-compatible`
   fixture. Record only sanitized PASS/FAIL evidence and never commit credentials
   or response bodies. Recheck prices and round reservations for each deployment.
4. Keep the production write catalog empty until the first fixed typed adapter
   has operation-specific validation, limits, rollback/partial-failure policy,
   confirmation-time region revalidation, off-thread durable audit/rollback
   persistence, and a real-player proposal integration test.
5. Resolve only the explicitly allowlisted Phase 13 graphical fixtures when safe
   deterministic inputs become available; do not relabel a missing fixture as a
   pass.
6. Add a real online-player integration lane for private `/agent say` delivery,
   proposal confirmation, and late dynamic command-tree refresh without
   increasing routine weak-host resource usage.
7. Do not generalize the same-lane uncached checksum match into a cross-platform
   byte-for-byte reproducibility claim. Native Windows and an independently
   pinned host image remain unverified.
