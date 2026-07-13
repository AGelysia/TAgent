# ADR 0006: Paper-owned proposal authorization

Date: 2026-07-13

## Status

Accepted for Phase 8.

## Context

Phase 7 lets a model request six bounded reads but has no write path. Before any
typed write adapter is added, the server needs one execution-admission boundary
that remains safe when model output, a Runtime connection, a player click, or a
permission snapshot is stale or malicious.

A proposal may outlive the request turn that rendered it. During that interval
the player can disconnect, lose OP or a permission, be removed from `owners`,
the local policy or tool catalog can change, the Agent can enter Offline, or the
arguments can be modified in memory or storage. Concurrent clicks must not
execute twice. Confirmation UI and audit data introduce separate injection and
sensitive-data risks.

## Decision

Paper owns proposal identity, frozen arguments, expiry, state, authorization,
and audit. Runtime and the model cannot allocate or extend a proposal. A
creation request is accepted only for a registered typed proposal tool and a
currently active Paper-originated request context with matching server,
request, session, player, tool, and catalog generation.

The risk set is `READ`, `WRITE_TEMPORARY`, `WRITE_WORLD`, `WRITE_PLAYER`, and
`SERVER_ADMIN`. `READ` does not use a proposal. Temporary writes require their
typed permission. World and player writes require current online status, live
OP, and their typed permission on every check. An `OWNER` policy adds configured
Owner UUID membership; it cannot replace OP. Server administration requires an
online configured Owner and its dedicated permission. The authorizer obtains a
fresh policy snapshot at creation and confirmation.

Paper detaches and freezes the validated argument object. Its hash input is:

```text
UTF8("minecraft-agent/proposal-arguments/v1") || 0x00 || UTF8(RFC8785(arguments))
```

The SHA-256 output is lowercase hexadecimal. Canonicalization has explicit
depth, node, and byte limits, and a shared golden fixes object ordering and
ECMAScript number serialization. Paper compares the caller's hash at creation
and recomputes the frozen value at confirmation. Production assigns a
server-fixed 60-second TTL; the reusable domain service rejects TTLs above ten
minutes.

Active state is a bounded in-memory repository. Confirmation first performs one
atomic `PENDING` to `CLAIMED` transition. It then repeats expiry, frozen hash,
Online epoch, actual player UUID and online state, current OP/Owner/permission
policy, live request context, and exact tool/catalog generation. A failure is
terminal. A successful admission invokes only the registered typed executor
with the same frozen arguments and does not call the model again. There is no
generic command, reflection, or Bukkit execution adapter.

Paper builds the Adventure response from a safe proposal view. Click events are
fixed namespaced `/minecraftagent:agent confirm <uuid>` and
`/minecraftagent:agent reject <uuid>` commands. Only the Paper-owned UUID is
variable; model text, display text, and arguments cannot become a command.
Proposal IDs are not tab-completed.

Player quit invalidates that player's proposals. `/agent off`, Runtime loss,
plugin disable, and every Offline epoch transition invalidate all proposals
through the lifecycle cleanup port. Restart begins with an empty repository, so
an old click cannot recover active state.

Paper appends and forces authoritative audit records to
`<state>/audit/security-audit-v1.jsonl`. The directory is `0700` and the file is
`0600`; unsafe links, file types, or modes fail closed. The record shape is a
fixed allowlist of correlation IDs, timestamp, tool, risk, catalog generation,
event type, and stable outcome code. It deliberately has no argument, summary,
prompt, credential, rendered command, provider response, exception, or general
free-text field. A failed pre-execution audit append prevents execution
admission.

The production typed write catalog is empty. The synchronous proposal service
has no production `create` caller, and the Runtime-Paper application channel
continues to reject `proposal.create`, `proposal.confirmed`, and
`proposal.cancelled` as unsupported. Phase 8 therefore establishes the
authorization boundary without making a write reachable.

## Consequences

- A deopped player, removed Owner, expired proposal, changed catalog/request,
  modified argument object, player quit, or Offline transition cannot reuse an
  old confirmation.
- Atomic claim makes duplicate and concurrent clicks single-use, at the cost of
  making a failed confirmation terminal rather than retryable after policy is
  restored.
- Final admission is an atomic `CLAIMED` to `EXECUTING` transition. Cleanup can
  invalidate work before that point but cannot rewrite an admitted execution's
  terminal state or suppress its result audit.
- Active proposals intentionally disappear on restart; the redacted audit
  history remains durable.
- Audit failure is availability-impacting because safe execution requires a
  durable pre-execution intent record.
- Future capability and world-write adapters must supply typed validation and
  operation-specific state checks. The proposal service does not make an
  unknown command executable.
- Before the first production write tool is registered, the synchronous API
  must be split so durable audit I/O runs on the worker and the final live
  reauthorization, `EXECUTING` admission, and Bukkit mutation run on the Paper
  thread. A write adapter may not block the primary thread on `force(true)`.
- Focused JVM tests cover the authorization chain, fixed click actions, and
  audit redaction. The pinned Paper smoke has no real player and does not click
  a proposal; that remains a later integration lane.
