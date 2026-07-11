# Security Model

## Scope

This is the normative security design for later implementation phases. Phase 0
and Phase 1 provide scaffolding and schemas. Phase 2 implements only the local
Runtime configuration and readiness controls described below; it does not
implement transport, Paper authority, model requests, or tool execution.

The governing rule is:

```text
Describe capabilities openly; never expose arbitrary execution.
```

## Trust boundary

The following inputs are untrusted:

- Player text and any player-selected coordinates.
- Books, signs, chat, server documents, and retrieved document content.
- Model output and every Runtime-originated tool call.
- Fabric client packets, capability claims, acknowledgements, and selections.
- Litematica state, preview acknowledgements, and material counts.
- External Capability Packs until explicitly approved and validated.

Paper is the final execution boundary. A Runtime check, model decision, client
preview, or player click cannot replace Paper-side validation.

## Non-negotiable invariants

1. There is no generic console, shell, script, reflection, filesystem-write, or
   unrestricted HTTP tool.
2. A tool must exist in Paper's current effective catalog and have a closed
   argument schema.
3. Every execution is bound to a live Paper-originated request, server ID,
   player UUID, session, module, and catalog generation.
4. Paper checks the player's current permissions at execution time.
5. `WRITE_WORLD` and `WRITE_PLAYER` require the player to be OP even if a
   permission plugin grants a similarly named node.
6. Risky operations use a server-owned, expiring, single-use proposal with
   frozen canonical arguments.
7. Confirmation executes the frozen operation directly. It never asks the
   model to recreate arguments.
8. Client data cannot raise risk limits, permissions, or trust.
9. Paper revalidates bounds, current region state, block count, and allowed
   BlockStates immediately before a world change.
10. Offline gating applies at both command dispatch and tool execution.

The effective permission is the intersection of local policy, current player
permission, module allowlist, tool policy, live request context, and proposal
state. No term in that intersection can grant what another term denies.

## Authentication and replay defense

The Runtime transport is local-only and binds to `127.0.0.1`. Runtime and Paper
use a server token stored outside committed configuration. The token is not
sent to the Fabric client.

Protocol 1.0 defines an HMAC-SHA-256 challenge/proof handshake. Every envelope
also carries a UUID `messageId`, UUID `requestId`, timestamp, and unpredictable
nonce. The schema validates their shape. Phase 3 must add the behavioral
controls:

- Verify the HMAC proof without logging secret material.
- Enforce a small clock-skew window.
- Reject a nonce already seen for the authenticated connection.
- Reject duplicate message IDs unless the message is an explicitly supported
  idempotent replay.
- Correlate all tool messages with a live Paper-originated request.
- Bound message size before JSON parsing and bound all collections afterward.

Loopback binding limits remote exposure but does not protect against another
local process. Token file permissions, log redaction, and host account security
remain required. If later threat analysis requires message integrity after the
handshake, add a canonical-envelope MAC and monotonic connection sequence as a
new protocol version rather than changing 1.0 implicitly.

## Offline behavior

Manual Offline is persisted by Paper. The command entry point checks state
before dispatching every subcommand. While Offline, every command other than
`/agent on` and `/agent off` returns exactly:

```text
AI offline
```

The same state check occurs immediately before any Paper tool execution. On an
`off` transition Paper rejects new requests, invalidates proposals, cancels
queued work, ignores late Runtime calls, and clears transient client transfers.
Cancellation is cooperative: a world mutation already in progress needs an
operation-specific consistency and recovery policy and cannot be assumed to
vanish atomically.

Authorization for `on` and `off` remains mandatory while Offline. The command
gate exception makes those subcommands reachable; it does not make them public.

## Proposal integrity

Paper creates proposals after tool, schema, risk, and permission validation.
Proposal IDs are opaque and server-owned. A proposal stores at least:

- Server, request, session, and player identity.
- Tool ID and effective catalog generation.
- Canonical arguments and a domain-separated SHA-256 argument hash.
- Risk, expiry, and single-use status.
- For builds, immutable artifact ID, base-region hash, and change-set hash.

Canonicalization must be specified before hashing; object property order and
number/string representation cannot depend on a JSON library's incidental
output. Confirmation uses a transactional state transition so two clicks cannot
execute the same proposal twice. After claiming it, Paper repeats Online, UUID,
OP, permission, tool-generation, bounds, size, and state checks.

Vanilla clickable confirmation requires fixed server commands such as
`/agent confirm <proposal-id>` and `/agent reject <proposal-id>`. These command
paths are required by the design even though they are not implemented in Phase
0-1. A structured client action carries only an action kind and proposal ID; it
cannot supply a command string.

## Capability safety

Capability Packs are data, not plugins. Loading a pack must never load Java,
scripts, or arbitrary classes. Unknown server commands produce a non-executable
Capability draft, not an executable proposal.

A command-backed capability is allowed only if the locked Paper and target
plugin APIs provide a reliable parse-and-validate path before execution. If a
third-party command cannot be fully validated without executing it, the
capability stays disabled and a typed plugin adapter is required. Simple string
replacement followed by command dispatch is prohibited.

External pack directories require controlled ownership and permissions. A
future approval record binds pack ID, version, and content hash. Hot reload must
validate a complete replacement registry before atomically changing the active
catalog generation.

## Client and view safety

Paper binds a client payload to the player represented by the actual network
connection. It ignores any UUID claimed inside the payload. The client never
receives the Runtime token or model key.

Structured views use fixed view types and schemas. They do not contain model
supplied click commands, texture paths, arbitrary components, or unrestricted
NBT. Item data uses a documented component allowlist and byte limits. Unknown
registry IDs render as explicit missing values.

Chunked payloads enforce chunk count, contiguous indices, encoded size,
decompressed size, per-chunk hash, whole-content hash, timeout, and one active
byte budget per connection. Duplicate, conflicting, incomplete, or oversized
transfers are discarded. A client ACK proves only that a packet path responded;
it does not prove that a player inspected a preview.

## Build safety

Natural-language intent is not a change set. A deterministic planner consumes a
normalized, versioned intent and an authoritative Paper snapshot. Paper stores
or retains the exact validated artifact referenced by a proposal.

Large multi-tick updates create a time-of-check/time-of-use problem. A single
base-region hash checked before a 100,000-block write is not atomic protection.
The first write implementation must use conservative limits, compare expected
old state before each change or bounded batch, stop on conflict, and define
rollback or partial-failure reporting. Block entity NBT remains rejected unless
a separate allowlisted schema is designed.

Litematica is a preview integration only. Its material list, local world view,
and success ACK are never used for authorization or server-side size checks.

## Data and privacy

Paper security records and Runtime conversation records use separate stores and
separate migrations. Neither process opens the other's SQLite file. Audit events
are written by Paper before or with the authoritative state transition; a
Runtime copy is informational.

Logs redact API keys, server tokens, HMAC proofs, sensitive capability
arguments, and message content by default. Session access always includes both
server ID and player UUID. Command suggestions and resume identifiers are
filtered to the requesting player.

Document retrieval is confined to configured roots, rejects traversal and
symlink escapes, and marks document text as untrusted content in model context.
Live unrestricted web access is not part of the design.

## Runtime startup controls

Phase 2 treats the local configuration as structured but potentially malformed
input. YAML is size/depth bounded, aliases and duplicate keys are rejected, and
environment references are expanded only after parsing and only when the full
scalar is `${NAME}`. Unknown key text and received values never enter diagnostics.
Group/world-writable configuration is rejected; broader read permissions and
inline secrets separately produce warnings as required by the product plan, but
the two may not be combined. Operators should use environment injection and mode
`0600` configuration.

Runtime data and log paths are relative to the canonical configuration directory
and must use private subdirectories. Path traversal, symlinks, hard-linked
databases, unsafe ownership/modes, and state directly in the configuration root
are rejected. Created directories are `0700`; SQLite and probe files are `0600`.

Startup logs serialize only fixed event names, stable codes, stages, and known
field paths. Provider exceptions and configuration values are discarded. The
loopback `/health` response is a cached readiness snapshot with no model name,
path, secret, raw error, or repeated external check. Phase 2 has no production
provider network adapter, so the default CLI fails closed before listening.

## Current enforcement gap

At the end of Phase 2 there is no authenticated connection, Offline gate,
permission check, proposal repository, capability loader, audit store, client
payload handler, or world mutation code. Protocol schemas are necessary input
validation contracts, not a complete security implementation.
