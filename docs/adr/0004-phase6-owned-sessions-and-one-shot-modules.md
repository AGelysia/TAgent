# ADR 0004: Phase 6 owned sessions and one-shot modules

- Status: Accepted
- Date: 2026-07-13

## Context

Phase 5 deliberately sent every request with a null session and the `general`
module. Phase 6 must preserve conversations across a Runtime restart, let a
player explicitly recover a previous conversation, and route a single request
through a named module without allowing model output or old state to select a
later route.

Session identifiers are sensitive authorization handles. A lookup by identifier
followed by a separate ownership check could reveal another player's or another
server's session. Persisting an active module would also make a one-shot command
silently affect later `/agent say` requests.

## Decision

Runtime owns a versioned SQLite schema for sessions and messages. Every lookup
uses the complete authenticated owner key: server ID, actual player UUID, and,
when supplied, session ID. An explicit missing, foreign-player, or foreign-server
identifier produces the same `SESSION_NOT_FOUND` result. A resume without an
identifier selects only that owner's most recently updated session.

`session.resume` and `session.resumed` are dedicated application messages.
Resume never becomes a model prompt. Paper accepts the response only while the
request ID, player UUID, authenticated connection, and operational epoch still
match. It then updates an in-memory player-to-session selection. Session IDs are
not offered through tab completion.

A successful model exchange commits the session plus its user and assistant
messages in one short transaction after the provider returns. Provider failure,
timeout, cancellation, or an invalid completion does not leave a partial turn.
No transaction spans a provider await. A request/role uniqueness constraint
rejects duplicate stored turns, and context reads use indexed hard limits plus
a total character budget.

The fixed Module Manifest contains `general`, `recipe`, `guide`, `locate`,
`build`, and `project`. Its trusted instructions and an empty Phase 6 tool
allowlist are resolved independently for every request. `/agent say` always
sends `general`; `/agent module <name> <message>` changes only that envelope.
The selected module is not stored on a session and is not inferred from history.

When `privacy.storeConversations` is false, Runtime stores neither prompts nor
answers, returns no durable session identifier, and rejects resume with the
stable `CONVERSATION_STORAGE_DISABLED` error. Provider-side request storage
remains disabled in both modes.

## Consequences

- Runtime restarts can resume conversations when conversation storage is
  enabled and the same authenticated server ID is configured.
- A Paper restart or player quit loses only the current in-memory selection;
  `/agent resume [session]` can recover the Runtime-owned conversation.
- SQLite uses synchronous bounded operations on Node 22. Queries and write
  transactions must stay indexed and small so the local event loop is not held
  across model work.
- Modules provide prompt routing only in Phase 6. They do not enable tools,
  proposals, server reads, client views, or world changes.
