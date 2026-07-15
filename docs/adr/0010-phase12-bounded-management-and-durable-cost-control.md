# ADR 0010: Phase 12 bounded management and durable cost control

## Status

Accepted for Phase 12.

## Context

Operational queries must expose enough state to diagnose the Paper, Runtime,
optional client, Litematica, Capability, and provider boundaries without
bypassing the persistent Offline state or disclosing player identity and local
paths. Configuration reload is more sensitive than a query: partially applying
owners and write policy could give different authorization paths inconsistent
views of the same generation.

The Runtime also needs restart-stable daily admission and monthly cost control.
The configured model name is arbitrary and cannot safely select a hard-coded
price table. Provider usage may be absent, cancellation may race a billable
response, and concurrent requests need reservations before making post-paid
provider calls.

## Decision

- The command boundary keeps one universal Offline gate. Every command and
  malformed form returns exactly `AI offline` before permission checks or
  management gateway calls while the Agent is not Online. Exact `on` and `off`
  remain the only exceptions and retain their existing toggle authorization.
- Online read-only management queries use independent permissions:
  `minecraftagent.admin.status`, `minecraftagent.admin.doctor`,
  `minecraftagent.admin.capabilities`, and `minecraftagent.admin.costs`.
  Possessing one does not grant another.
- Reload is not an ordinary permission grant. Only the local Console or a live
  player whose UUID is in the current Owner snapshot may invoke it. OP status
  and `minecraftagent.admin.reload` alone are insufficient.
- Status, doctor, and capability output is built from bounded immutable
  snapshots. Doctor reports component/protocol state, anonymous client protocol
  and feature-version distributions, and grouped Litematica adapter status plus
  Minecraft, Fabric Loader, Litematica, MaLiLib, and adapter versions. It emits
  no player UUID, player name, connection generation, credential, or local
  filesystem path. Client declarations remain diagnostic data and never become
  permission or proposal authority.
- Reload parses the complete Paper configuration with the same strict loader
  and captured environment used by startup, then independently validates the
  candidate `SecurityPolicy`. Candidate loading runs away from the server
  thread and has no publication side effect.
- The first live reload surface may replace only `owners` and the complete
  `SecurityPolicy`. They are published together as one immutable, monotonically
  versioned `ReloadPolicySnapshot` using compare-and-set. Admin/toggle and
  proposal authorization read that same current generation.
- Server ID, Runtime endpoint/token/connect timeout/handshake timeout, state
  directory, Capability directory, and Capability approvals are bound to the
  trusted startup candidate. Any change returns a stable restart-required
  result. Reload does not live-reload Capability Packs, landmarks, Runtime
  configuration, knowledge roots, transport, or storage.
- Only one reload attempt may be active. Invalid configuration, invalid policy,
  worker rejection, unexpected failure, manager close, or stale completion
  retains the previous snapshot and returns only a stable redacted result. A
  candidate is visible only after complete validation and successful CAS
  publication.
- Core self-check produces a discardable candidate. Initial policy and reload
  authority publish only after Runtime authentication succeeds. The trusted
  reload manager then survives Offline/recovery transitions; recovery compares
  restart-only fields to that original baseline and cannot reset generation.
  Every reload attempt is bound to the Online operational epoch. Permit
  validation and policy CAS share the gate transition lock, so an epoch change
  is totally ordered before or after publication rather than racing it.
- Runtime SQLite migrations v3-v5 own durable request admissions,
  per-provider-round reservations, idempotent provider usage events,
  per-player UTC-daily request counts, and server-wide UTC daily/monthly
  aggregates. Migration v4 records whether a provider round started. Startup
  estimates abandoned started rounds and releases not-started reservations
  without erasing admitted counts or settled events.
- Migration v5 takes a singleton SQLite process-owner lock before abandoned-work
  recovery. `BEGIN IMMEDIATE` serializes live-owner validation and dead-owner
  replacement. Bulk cancellation removes queued work before active slots, so it
  cannot drain never-started requests into provider calls.
- Model prices are explicit integer micro-USD rates per one million input and
  output tokens. Reported event cost is calculated with integer arithmetic and
  rounded up to one micro-USD. Every provider round reserves the configured
  micro-USD amount before the call; missing or unknown-billability usage settles
  conservatively at the reservation, while `NOT_BILLABLE` releases it. A
  cancelled `STARTED` round is immediately estimated, and a late reported
  response can correct that event idempotently.
- Paper obtains costs only through the authenticated, replay-protected
  `management.costs.request` / `management.costs.response` WebSocket exchange.
  The response contains bounded current UTC day/month aggregates and budget
  state. It has no player identity, per-player breakdown, prompt, completion,
  credential, or provider body.
- The command projection preserves UTC periods, reported/estimated provider-call
  counts, settled cost, and active reservations. Asynchronous management replies
  repeat the Online and authority checks at their final main-thread output
  boundary.
- Phase 12 adds no write executor. The production proposal/write catalog stays
  empty, Capability entries remain non-executable, and management state cannot
  authorize a Minecraft mutation.

## Consequences

Operators can inspect stable health, capability, client compatibility, and cost
state without broadening the data or execution surface. Offline remains an
intentional hard operational boundary, so management queries are unavailable
until an authorized `on` succeeds.

Owner removal and security changes take effect as one generation; a caller who
removes its own Owner UUID cannot use Owner-only reload again. Configuration
that affects transport, storage, or catalog construction requires a restart
instead of a partial live transition.

The round reservation must be a conservative operational estimate. The monthly
setting is a reservation-based admission bound, not a provider billing cap.
Provider billing is post-paid, so reported cost may exceed both its reservation
and the configured local bound before later calls are blocked. Conversely, an
oversized reservation may reject affordable concurrent work.

Tests cover the command gate and permissions, redacted aggregate snapshots,
reload validation/CAS/stale failure paths, durable usage idempotence and restart
behavior, and the authenticated management-cost exchange. They do not establish
that a graphical Fabric/Litematica session has run, that a real online player
has exercised the commands, or that Phase 13 release verification is complete.
