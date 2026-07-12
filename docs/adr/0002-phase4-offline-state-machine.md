# ADR 0002: Phase 4 Offline state machine

## Status

Accepted and implemented on 2026-07-12.

## Context

Phase 3 conditionally registers `/agent` only after a complete local check and
authenticated Runtime handshake. Phase 4 must add an emergency stop that cannot
be bypassed by queued work, late Runtime responses, old proposals, or client
state. Manual Offline must survive restart, while a transient Runtime failure
must not silently change the operator's desired mode.

The command also has to remain reachable after a post-registration failure so an
authorized administrator can request a fresh check. This differs from initial
startup failure, where exposing a permanently not-ready command would weaken the
Phase 3 gate.

## Decision

Paper models three separate dimensions:

```text
AgentState:   UNREGISTERED | STARTING | ONLINE | STOPPING | OFFLINE
DesiredMode: ENABLED | DISABLED
AgentHealth: HEALTHY | DEGRADED | UNAVAILABLE
```

Only desired mode is persistent. Paper writes a strict, bounded
`state/agent-state.yml` under the already verified private state directory.
Missing state defaults to ENABLED. Existing invalid or unsafe state is a core
failure. Writes use a `0600` temporary file, file force, atomic replacement, and
post-write verification; there is no non-atomic fallback.

Initial startup always performs the full Runtime handshake, even when the stored
mode is DISABLED. Success registers both command labels once. ENABLED transfers
the authenticated connection to the active lease; DISABLED closes the candidate
and publishes OFFLINE/MANUAL. Initial failure remains UNREGISTERED with no
command.

Every lifecycle attempt has a generation, an explicitly cancellable transport
attempt, and ownership of its candidate connection. `off`, disable, or a newer
attempt cancels the socket/handshake and makes an older completion stale; any
late connection is closed and cannot publish state. Active connection-loss
callbacks also check connection identity, so closing an old lease cannot take a
newer one Offline.

An `OperationalGate` rotates a monotonic epoch on every transition. It issues a
permit only ONLINE and accepts it later only if the state is still ONLINE and the
epoch matches. `/agent off` enters STOPPING and rotates this gate before it
detaches Runtime, invokes the request/proposal/operation/client cleanup ports,
or queues persistence. Cleanup exceptions are isolated and logged. A failed
DISABLED write leaves the in-memory Agent Offline and never restores admission.

`/agent on` is asynchronous. It reruns configuration, compatibility, state,
policy, descriptor, optional capability, protocol, token, and Runtime checks.
After authentication it persists ENABLED before the primary thread transfers the
candidate and publishes ONLINE. Failure closes the candidate and remains
Offline. Runtime loss follows the same admission and cleanup boundary but does
not rewrite desired mode and does not auto-reconnect.

Toggle authorization accepts the local server console, a UUID in `owners`, or a
live OP with `minecraftagent.admin.toggle` only when `allow-op-toggle` is true.
Other sender types are rejected. Offline dispatch recognizes only an exact
single-argument `on` or `off` exception; all other forms return exactly
`AI offline` before ordinary command handling.

## Consequences

- Initial trust failure still requires an external fix and Paper restart.
- A successfully registered command remains until plugin disable, including
  Runtime loss and failed recovery.
- Runtime failure cannot overwrite a deliberate manual preference.
- Work admitted under an older epoch stays invalid after a later recovery.
- Phase 4 provides explicit cleanup ports but has no request, proposal, tool, or
  client-transfer producer yet. Their future implementations must carry and
  revalidate the permit at their final Paper side-effect boundary; no end-to-end
  cleanup of nonexistent producers is claimed.
- A manual off closes admission immediately but persists on the worker. Plugin
  disable lets an already queued DISABLED write finish before its non-daemon
  worker terminates, so an immediate graceful stop does not discard the user's
  preference.

## Validation

Focused JVM tests cover strict state parsing and atomic replacement, status
invariants, epoch invalidation, command messages and authorization, cleanup
isolation, Runtime loss, recovery success/failure, off/recovery races, and
identity-only command removal.

The pinned Paper `1.21.11-132` smoke exercises initial Online, manual off,
private DISABLED persistence, restart remaining Offline, explicit recovery,
Runtime process loss with command retention, recovery against a restarted
Runtime, and clean plugin disable on both server runs.
