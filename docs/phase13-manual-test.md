# Phase 13 Manual Client Test

## Purpose

Use this checklist for the release-specific graphical and real-player lane that
cannot be established by unit tests or the headless Paper smoke. It covers the
preview-only release boundary. It does not authorize or test world apply,
rollback, Easy Place, printer behavior, arbitrary commands, or any other
Minecraft mutation.

This is a source-checkout maintainer lane. The packaged checklist does not imply
that the repository-only launcher or deterministic provider is shipped to
players.

Treat every checkbox as a required observation in the external working record,
but leave this packaged template unchanged. Use `PASS`, `FAIL`, or `BLOCKED` in
the final acceptance record; never convert a missing test path into a pass.

## Test record

- Date (UTC):
- Candidate commit:
- Paper JAR SHA-256:
- Client JAR SHA-256:
- Runtime package SHA-256 or package manifest:
- Paper `1.21.11` build identifier and SHA-256:
- Java version:
- Node/npm versions:
- VM operating system and network mode:
- Physical client operating system, GPU, and driver:
- Minecraft account/launcher:
- Tester:
- Evidence directory (outside the Git worktree):

Evidence must not contain an API key, Runtime token, full configuration, private
prompt/completion, private document, player UUID, client-local path, or public VM
address. Use a dedicated harmless test prompt and redact unrelated chat.
Keep working notes and screenshots outside the repository until every harness
session for the candidate has stopped. Do not edit this packaged checklist
template. Only then update the repository-only
[`phase13-acceptance-record.md`](https://github.com/AGelysia/TAgent/blob/main/docs/phase13-acceptance-record.md)
and [`progress.md`](https://github.com/AGelysia/TAgent/blob/main/docs/progress.md);
both are excluded from the package. The launcher requires a clean commit and
rebuilds the same deterministic candidate before each session.

## Release decision

The acceptance record may use only `PENDING`, `ACCEPTED`, or `REJECTED` as its
overall status. `ACCEPTED` requires all of the following:

- the tested and final Paper JAR, Client JAR, complete dist manifest, Runtime
  subtree, protocol subtree, and archive hashes match exactly;
- isolated setup, source-restricted networking, exact whitelist, authenticated
  identity, and cleanup pass;
- Lane A passes real private fallback with a second controlled observer;
- Lane B passes base-client hello, path-free `NOT_INSTALLED` diagnostics, overlay,
  authoritative recipe graphics/choices, and reconnect cleanup;
- Lane C passes the exact dependency tuple, diagnostics, project/build flow,
  non-auto-load boundary, explicit preview load, native Material List, explicit
  remove verified in Litematica, changed-position fresh preview, and disconnect
  cleanup; and
- Runtime stop/restart, Offline behavior, new generation, fixed text work, and
  stale previous-generation rejection pass.

Any observed `FAIL`, fingerprint mismatch, or `BLOCKED` item outside this exact
allowlist makes the result `REJECTED`: processing/unsupported recipe fixtures,
unknown Item ID, multi-chunk transfer, Fabric-rejected missing-dependency profile,
absence of a safely launchable alternate-version profile, and absence of a
controlled adapter-linkage or preview-storage failure fixture. Those allowlisted
gaps remain `BLOCKED`, not `PASS`.
Native Windows may remain untested only because this release makes no native
Windows equivalence claim.

## Isolated server setup

- [ ] Confirm the launcher created a fresh disposable session. It never reuses a
  Paper world, plugin config, whitelist, operator list, or Runtime state, and
  removes the session on exit.
- [ ] Run Paper/Minecraft `1.21.11` on Java 21 and Runtime on Node.js 22.16 or
  newer in the 22.x line.
- [ ] Generate a new high-entropy `MINECRAFT_AGENT_SERVER_TOKEN`; inject the same
  value into Paper and Runtime without writing it into Git or this record.
- [ ] Use `scripts/phase13-manual-server.sh` and its repository-only deterministic
  provider. Confirm its disposable config has conversation storage and all
  content/tool logging disabled. Its non-secret API-key placeholder is never
  transmitted; do not use a real provider key for this lane.
- [ ] Keep Runtime at `127.0.0.1` and confirm its port is not reachable from the
  physical host or Internet.
- [ ] Set `online-mode=true`, `white-list=true`, and `enforce-whitelist=true` in
  `server.properties`. Add only the authenticated test account with the Paper
  console whitelist command.
- [ ] Use a legitimate authenticated Minecraft account. Do not set
  `online-mode=false`, install an authentication bypass, or use a proxy that
  replaces Paper's player identity.
- [ ] Grant OP/Owner status only where a management check requires it. Record
  ordinary-player and operator cases separately, then remove temporary grants.

### VM network exposure

- [ ] Prefer a private bridged LAN, host-only route, VPN, or temporary SSH local
  forward for TCP `25565`.
- [ ] If the VM has a public address, restrict TCP `25565` to the physical
  tester's current source IP before Paper starts. Do not expose Runtime, RCON,
  query, database, or debug ports.
- [ ] Confirm the whitelist from a non-whitelisted authenticated account or an
  equivalent controlled login attempt, without weakening online authentication.
- [ ] Record the firewall/NAT rule removal command before testing and remove the
  rule or port forward after the last client disconnects.

## Candidate installation

Prepare three separate launcher profiles using the same candidate server and
client artifacts:

- [ ] **Vanilla:** Minecraft `1.21.11`, with no Fabric Loader and no Agent Client
  JAR.
- [ ] **Agent client:** Minecraft `1.21.11`, Fabric Loader `0.19.3`, Fabric API
  `0.141.4+1.21.11`, and the candidate `MinecraftAgent-Client-Fabric.jar`; no
  Litematica or MaLiLib.
- [ ] **Agent client + Litematica:** the Agent client profile plus Litematica
  `0.26.12` and MaLiLib `0.27.16`.
- [ ] Use distinct game/config directories and start only one primary test
  profile at a time. A second whitelisted Vanilla observer may be online only for
  the explicit private-broadcast check; list both account names in
  `PHASE13_PLAYER_NAMES`. Only the primary tester may run the fixed project/build
  commands because the deterministic provider owns one process-scoped project.
- [ ] Confirm the fresh server session uses the matching candidate Paper JAR and the Runtime
  copied from the same verified package. Confirm the launcher reports no Runtime
  tree, manifest, or lockfile mismatch while installing production dependencies.
- [ ] Record that the repository-only deterministic provider is absent from
  `dist/agent-runtime/scripts/`; only `version.mjs` may be packaged there.

Do not infer support from Fabric accepting a different version. The only
Litematica adapter claim is the exact tuple above.

## Server readiness baseline

- [ ] Start Runtime, then Paper. Confirm `/agent` is registered only after the
  authenticated Runtime handshake and readiness checks complete.
- [ ] As an authorized operator, run `/agent status`, `/agent doctor`,
  `/agent capabilities`, and `/agent costs`. Record bounded output and confirm it
  contains no credential, player UUID, prompt, completion, or local path.
- [ ] In the local harness console, enter `agent off`; confirm in-game non-toggle
  forms, including `/agent doctor`, return exactly `AI offline`. Enter `agent on`
  in the harness console and confirm a fresh self-check restores service. The
  harness intentionally has no Owner and does not authorize OP toggle.
- [ ] Confirm build preview publication is off for the Vanilla and base Agent
  lanes. Stop that session, then launch a fresh exact-Litematica session with
  `PHASE13_PREVIEW_ENABLED=true`; the harness alone maps it to Paper's production
  preview environment variable.

## Lane A: Vanilla client

- [ ] Join with the whitelisted Vanilla profile. Confirm normal play and
  `/agent` do not require a client mod.
- [ ] Run `/agent say phase13 text`. With a second controlled Vanilla account
  online, confirm the response is private chat fallback and is not broadcast to
  that observer or public server chat. Without a second account, record this row
  `BLOCKED`; server logs alone are not equivalent evidence.
- [ ] Run `/agent module recipe phase13 recipe`.
  Confirm the result is readable private text and no structured overlay appears.
- [ ] Confirm `/agent doctor` does not invent an Agent client connection or
  Litematica capability for this player.
- [ ] Disconnect during or immediately after a bounded request, reconnect, and
  confirm the server remains responsive and a new private request completes.

Evidence:

## Lane B: Agent client without Litematica

- [ ] Join with the base Agent profile. Confirm the overlay initializes and the
  client remains connected without either optional mod.
- [ ] Run `/agent doctor` as an authorized operator. Confirm the anonymous
  aggregate reports the current hello protocol/features and a path-free
  `NOT_INSTALLED` Litematica diagnostic; both Litematica feature versions must be
  zero.
- [ ] Submit `/agent say phase13 text`. Verify overlay text, scrolling, bounded
  resize, drag, pin/unpin, close, and clear. Confirm no interaction changes
  server permissions or world state.
- [ ] Disconnect and reconnect. Confirm connection-scoped views are gone, saved
  geometry/pin preference remains valid, a new hello is negotiated, and a new
  view can be displayed without duplicate or stale content.

### Recipe graphics

- [ ] Run `/agent module recipe phase13 recipe` to query the authoritative
  `minecraft:crafting_table` shaped recipe from the live server registry. Record
  the exact query and recipe ID, then capture the real grid, result, registry
  icons, stack counts, and vanilla tooltip at a fixed resolution and GUI scale.
- [ ] Query a recipe whose authoritative ingredient contains multiple choices.
  Verify choices can be cycled or expanded and are not silently fixed to one
  random material. Record every observed choice and the selected recipe variant.
- [ ] Verify variant paging, processing metadata where applicable, remaining
  items, and explicit unsupported layouts without using model text as recipe
  data. The fixed `minecraft:crafting_table` query does not provide processing or
  unsupported-layout fixtures; record those observations `BLOCKED` unless a
  separately reviewed release-controlled fixture exists.
- [ ] Exercise a release-controlled structured-view fixture containing a
  syntactically valid but unknown Item ID. Verify a missing-item placeholder and
  the original ID are visible and the client does not crash. The production
  package has no operator payload-injection command; if no bounded test fixture
  lane exists, record this row `BLOCKED`. Do not use a packet proxy, edit a
  capability claim, or weaken validation to manufacture the case.
- [ ] Capture approved screenshot evidence with no secret, private prompt, UUID,
  local path, or unrelated player chat visible.

Evidence:

## Lane C: exact Litematica tuple

- [ ] Start the exact Litematica profile and join after Paper has been restarted
  with `MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true`.
- [ ] Run `/agent doctor`. Confirm one anonymous `READY` group reports Minecraft
  `1.21.11`, Loader `0.19.3`, Litematica `0.26.12`, MaLiLib `0.27.16`, and adapter
  `litematica-reflection-1`, with no player UUID or client path.
- [ ] Run `/agent module project save phase13 acceptance project`. The create
  response confirms creation but intentionally omits identifiers; record the
  exact project UUID and revision from the subsequent build fallback. Keep the
  target in the current dimension, within loaded chunks, within 128 blocks, with
  each axis at most 32 and total volume at most 4096.
- [ ] Run `/agent module build phase13 build preview`. Confirm the deterministic
  flow reads live player context and that exact project before creating a 3 by 2
  by 2 walls preview using `minecraft:lime_wool`, rotation `90`, and mirror
  `FRONT_BACK`. Record the fallback's view UUID, project UUID, revision, and
  change count. Record bounds and origin from the Litematica placement after the
  explicit load action; they are not included in private fallback text.
- [ ] Before any load action, inspect Litematica placements. Confirm receipt only
  registered the managed preview and did **not** create or render a placement.
- [ ] Run `/agent ui preview <view-id>`. Confirm exactly one Agent-owned placement
  loads at the Paper-provided origin with the requested dimension, rotation,
  mirror, full target, and added/replaced/removed differences.
- [ ] Run `/agent ui materials <view-id>` only after load. Confirm Litematica's
  native Material List HUD opens for that placement and no model-generated or
  chat-only material estimate is presented as the native result.
- [ ] Run `/agent ui remove <view-id>` for the same view. Confirm only that
  Agent-owned placement and managed artifact are removed. The server message
  confirms dispatch only, not client execution, so inspect Litematica state for
  the actual result and confirm world state is unchanged. Disconnect cleanup or
  closing the overlay is separate evidence and must not be counted as this pass.
- [ ] Move at least one whole block on X or Z while remaining in loaded chunks and
  within the bounded test radius, run the fixed build command again, and record a
  different view UUID before loading it. Reusing the same position/revision is a
  stale view and is not a fresh-preview test. Disconnect after loading the new
  preview and verify all connection-scoped Agent
  placements and managed preview artifacts are cleaned up. Reconnect and confirm
  a stale view UUID cannot be loaded while a newly delivered preview works.
- [ ] Stop the harness after this lane and confirm its disposable session was
  removed. A later invocation defaults preview publication back to `false`; no
  test server state is retained.

Evidence:

## Unsupported and failure diagnostics

Run each safe case in its own profile. Do not install a mod pair that Fabric says
is incompatible with Minecraft `1.21.11`, and do not bypass a required
dependency merely to reach Minecraft Agent code.

- [ ] With neither optional mod installed, confirm `NOT_INSTALLED`, zero
  Litematica capabilities, and a working base overlay.
- [ ] Where Fabric can safely start with Litematica present and MaLiLib absent,
  confirm `MISSING_DEPENDENCY`; otherwise record the graphical case `BLOCKED`
  because Fabric rejected the invalid profile first.
- [ ] With a safely launchable non-matrix Litematica/MaLiLib pair for Minecraft
  `1.21.11`, confirm `UNSUPPORTED_VERSION`, zero Litematica capabilities, and a
  working base overlay. Record exact versions. Do not treat this as support for
  the alternate tuple. If no non-matrix pair can safely launch, record this row
  `BLOCKED`; if one can launch, the observation must be `PASS` or `FAIL`.
- [ ] If a controlled candidate can reproduce adapter linkage or managed storage
  failure without weakening filesystem protections, confirm
  `ADAPTER_LINKAGE_FAILED` or `PREVIEW_STORAGE_UNAVAILABLE` respectively, zero
  Litematica capabilities, no local path in `/agent doctor`, and a working base
  overlay. Otherwise record the case `BLOCKED`.
- [ ] Confirm an unavailable or rejected Litematica adapter does not take the
  Runtime-Paper connection Offline.

Evidence:

## Disconnect and recovery

- [ ] Disconnect during a multi-chunk structured view. The fixed Phase 13
  provider has no bounded multi-chunk fixture, so record this row `BLOCKED`
  unless a separately reviewed release-controlled fixture is added; do not count
  the small recipe or 3-by-2-by-2 preview as multi-chunk evidence.
- [ ] Enter `:runtime-stop` in the harness console during an online player
  session. Confirm Paper enters Offline, the command remains registered, and
  non-toggle forms return `AI offline`.
- [ ] Enter `:runtime-start`, then enter `agent on` in the local harness console.
  Confirm a new authenticated connection and client generation are established, then use
  `/agent say phase13 text` to confirm new work is accepted. Run this only after
  project/build checks: the repository-only provider deliberately forgets its
  in-memory project identity across a Runtime restart.
- [ ] Confirm late replies, ACKs, Material List results, or UI actions from the
  previous connection cannot display stale content or change authority.
- [ ] Review Minecraft Agent events and management output for bounded stable
  codes and no key, token, prompt/completion content, player UUID, or client-local
  path disclosure. Raw Paper/client login logs may legitimately contain account
  UUIDs or addresses; keep them private and remove those fields from evidence.

Evidence:

## Closeout

- [ ] Remove the public firewall rule, NAT port forward, VPN grant, or SSH tunnel
  used for the physical client.
- [ ] Stop Paper and Runtime; confirm no Java or Node process from the test remains.
- [ ] Remove the temporary provider key and Runtime token from the process
  environment and rotate either credential if it appeared in evidence.
- [ ] Preserve only sanitized screenshots, exact artifact hashes, version facts,
  and PASS/FAIL/BLOCKED observations.
- [ ] Record sanitized outcomes and unresolved blockers in
  the repository-only acceptance record and progress log before calling the
  graphical lane complete.
