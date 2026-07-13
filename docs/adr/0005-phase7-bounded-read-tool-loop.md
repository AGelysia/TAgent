# ADR 0005: Bounded read-only tool loop

Date: 2026-07-13

## Status

Accepted for Phase 7.

## Context

Phase 6 sends one model request and accepts one final answer. Phase 7 must let
the model request current Minecraft facts without making the model, Runtime, or
WebSocket connection an authority over server state. The first catalog contains
exactly six read-only tools for player context, held items, server metadata,
plugins, and recipe lookup/usage.

Tool traffic crosses an authenticated but asynchronous boundary. A request can
be cancelled, a player can leave, or Paper can move to a new Online epoch while
a model or server-thread task is in flight. Recipe data also has enough nested
structure to exceed a frame or stall a tick if it is flattened and scanned
without bounds.

## Decision

Runtime owns the model loop and Paper owns the effective executable catalog.
Both sides independently validate the tool ID, module allowlist, closed
arguments, live request binding, and typed result. Paper alone supplies
provenance and trust labels.

The initial loop is serial. A provider turn may return one final answer or one
tool call; parallel or multiple calls are invalid. Each emitted call consumes a
zero-based sequence slot, and protocol 1.0 allows at most eight slots. The
configured limit may reduce that bound but cannot increase it. Reaching the
limit gives the provider one tool-disabled final-answer turn and never emits a
ninth call.

Provider function names use an explicit safe-name mapping because the protocol
tool IDs contain dots. Runtime allocates the protocol UUID independently of the
provider call ID. A result must match the live request, temporary or durable
session, player, tool, sequence, and call UUID before it can be returned to the
provider.

Conversation storage being disabled does not disable tools. Runtime creates an
ephemeral per-request session UUID for wire correlation, returns a null session
in the final completion, and writes no conversation or tool data.

Paper receives a tool call as an intermediate message and retains the live
request. It schedules the bounded Bukkit snapshot on the server thread without
blocking the transport thread. Before execution and again before sending the
result, it checks the Online epoch, connection, request, player, and pending
call. Cancellation, timeout, Offline, disconnect, terminal response, or player
quit invalidates pending work; its late result is discarded.

Recipe results are typed data rather than prose. They preserve the recipe key
and variant, result item stack, real layout where applicable, ingredient choice
kind and alternatives, processing metadata, and remaining items when the API
provides them. Registry scans, recipe counts, alternatives, strings, nesting,
and the final application frame are bounded. An empty match is a successful
typed result.

## Consequences

- Unknown, module-disallowed, or malformed model calls stop before Paper I/O.
- Paper repeats policy and argument validation, so Runtime compromise does not
  create a generic Bukkit invocation surface.
- Authoritative structured output can still contain untrusted player/plugin
  text. It is passed as tool output, never concatenated into trusted
  instructions.
- The single-call loop is less efficient than parallel reads but gives protocol
  1.0 an unambiguous sequence and simpler cancellation semantics.
- Recipe queries return bounded snapshots and may report truncation. They do
  not claim to be a complete registry export.
- Read-tool traffic is transient in Phase 7. Only the successful final
  user/assistant exchange participates in the Phase 6 conversation transaction.
