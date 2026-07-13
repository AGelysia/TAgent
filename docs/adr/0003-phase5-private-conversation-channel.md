# ADR 0003: Phase 5 private conversation channel

- Status: Accepted
- Date: 2026-07-13

## Context

The Phase 3 WebSocket authenticated one hello and rejected every later frame.
Phase 5 needs model-backed questions without treating player chat, model output,
or payload identity as trusted. Requests must remain cancellable across timeout,
player quit, Offline transitions, Runtime loss, and plugin disable without
blocking Paper's primary thread.

## Decision

The authenticated WebSocket becomes a persistent application channel after the
existing HMAC hello. Protocol 1.0 adds closed payload schemas for
`agent.complete`, `agent.error`, and `agent.cancel`; `agent.request` remains the
only request payload. Each side applies a 64 KiB byte cap, strict JSON parsing,
direction and schema checks, server identity, freshness, and bounded replay
checks before dispatch.

Paper is authoritative for live request correlation. `/agent say <message>` is
the only conversation entry point, accepts only a real `Player`, derives that
player's UUID, and fixes Phase 5 to `sessionId: null`, module `general`, and no
client capabilities. A record binds request ID, player UUID, connection
instance, and `OperationalGate` permit. At most one record exists per player
and 64 globally.

Every terminal path removes the same live record once. A completion/error is
delivered only if its identity still matches and the original permit is valid
at the final primary-thread boundary. Paper looks up the online player again
and sends `Component.text(fallbackText)` only to that player. It installs no
ordinary-chat listener and exposes no model-supplied formatting or actions.

Runtime uses an injectable provider boundary. Production uses the OpenAI
Responses API with `store: false`, no tools, bounded response reading, and
abortable timeout. Runtime independently enforces one outstanding request per
player, cooldown, an in-memory daily limit, global concurrency, and a FIFO
queue. Automated and real-server tests inject a deterministic provider rather
than using a production key.

## Consequences

- Runtime disconnect still moves Paper Offline and requires explicit recovery.
- A timeout or cancellation may not stop a provider that violates AbortSignal,
  but its slot is released and its late result cannot produce a terminal frame.
- Phase 5 has no sessions, resume, explicit modules, tools, proposals, client
  views, durable usage accounting, or monthly budget enforcement.
- Server-level player-command logging can record `/agent say` before plugin
  code runs; operators must control that facility separately.
