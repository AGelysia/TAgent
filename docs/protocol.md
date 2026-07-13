# Protocol 1.0

## Scope

Protocol 1.0 defines contracts for two separate links:

1. Paper to the local Agent Runtime over an authenticated WebSocket. Phase 7
   keeps the Phase 3 hello exchange and enables the bounded agent, session, and
   read-tool exchanges after authentication. Phase 8 adds proposal contracts
   without enabling proposal transport handlers.
2. Paper to the optional Fabric client over versioned Minecraft custom payloads
   in a later phase.

Phase 1 owns JSON Schemas, fixtures, and cross-language contract tests. Later
phases add closed payloads while keeping the socket open for a strictly bounded
conversation and read-tool channel. The current implementation still does not
register Fabric payload handlers or dispatch proposal, control, or view
messages.

The canonical schema source is `protocol/schemas/`. JVM builds copy those files
as resources; the TypeScript Runtime loads the same files through its schema
registry. Zod remains suitable for Runtime configuration and internal models,
but wire validation uses the shared JSON Schemas.

All schemas use JSON Schema Draft 2020-12, a stable `$id`, closed objects where
appropriate, explicit length/count limits, and protocol version `1.0`.

## Runtime-Paper envelope

Every Runtime-Paper message uses this shape:

```json
{
  "protocolVersion": "1.0",
  "messageId": "00000000-0000-4000-8000-000000000002",
  "requestId": "00000000-0000-4000-8000-000000000002",
  "serverId": "survival-main",
  "type": "agent.request",
  "timestamp": "2026-07-11T00:00:00Z",
  "nonce": "base64url-random-value",
  "payload": {}
}
```

The fields have distinct roles:

| Field             | Decision                                                                         |
| ----------------- | -------------------------------------------------------------------------------- |
| `protocolVersion` | Exactly `1.0`; incompatible peers are rejected.                                  |
| `messageId`       | Unique UUID for this envelope. It supports deduplication and audit correlation.  |
| `requestId`       | Required UUID for every envelope. All messages in one logical exchange reuse it. |
| `serverId`        | Stable configured server identity, not a display name supplied by a player.      |
| `type`            | Closed message discriminator. It selects the payload schema and direction.       |
| `timestamp`       | UTC RFC 3339 timestamp used by handshake and later message freshness checks.     |
| `nonce`           | Unpredictable base64url value unique within the active replay window.            |
| `payload`         | Type-specific closed object validated after the envelope.                        |

`requestId` is intentionally not nullable. The initiator creates one for every
top-level exchange, including a handshake, control transition, or ping/pong
pair. This avoids special correlation rules and prevents a peer from borrowing
the ID of an unrelated player request. A sender must not reuse a request ID for
unrelated operations.

The nonce is not an authentication mechanism by itself. Schema validation only
checks its representation. Handshake and application receivers enforce a
bounded clock window and a bounded TTL replay cache shared across connections;
a new socket therefore cannot make a previously accepted envelope reusable.
Handshake messages are capped at 16 KiB. Authenticated application messages are
capped at 64 KiB before strict UTF-8 and JSON parsing.

## Validation order

Receivers will apply validation in this order:

1. Enforce the transport byte limit before parsing JSON.
2. Parse once with duplicate-key rejection where the JSON library supports it.
3. Validate `envelope.schema.json`.
4. Verify negotiated protocol, authenticated connection, timestamp, nonce, and
   expected server ID.
5. Verify that the message type is legal for the sender and current connection
   state.
6. Validate `payload` against the schema selected by `type`.
7. Apply semantic checks that JSON Schema cannot express, such as request
   liveness, hash equality, ownership, or chunk reassembly.
8. Only then pass an immutable DTO to application code.

Schema acceptance never grants permission to run a tool.

## Correlation and idempotence

Paper creates the request ID for a player request and keeps an in-memory request
record. Tool calls and results reuse that ID and add a unique `toolCallId`.
Paper accepts a tool call only while the request is live and only once for the
tuple `(requestId, toolCallId)`.

`messageId` deduplicates envelopes. It does not make an unsafe operation safe to
retry. Phase 8 gives Paper-local proposals an atomic single-use claim and a
durable audit event; active proposal state is memory-only and cannot survive a
restart. No proposal transport handler or write tool is enabled.

Cancellation, timeout, `/agent off`, player disconnect, Runtime disconnect, and
terminal agent messages close a request. `agent.cancel` reuses the original
request ID and carries the Paper-derived player UUID; unknown or repeated
cancellation has no effect. Late completion, error, or tool traffic is never
delivered or executed.

## Handshake

`handshake.schema.json` describes the Runtime-Paper hello payload. It includes:

- Component kind and semantic component version.
- Supported protocol versions and the selected intersection.
- HMAC-SHA-256 scheme, key ID, random challenge, and proof.

No raw token is placed in an envelope. The exact challenge transcript and
canonical byte representation are fixed by `protocol/README.md` and the shared
`handshake-proof-v1` golden fixture. The Runtime reply echoes Paper's challenge,
and nonces, challenges, and proofs use canonical base64url without padding. A
selected version of `null` represents negotiation failure and cannot transition
a connection to ready.

The Fabric handshake is separate. `client-handshake.schema.json` advertises the
client protocol, mod version, fixed feature versions, and detected Litematica
and MaLiLib versions. It contains no Runtime authentication fields.

## Payload contracts

Phase 1 includes or targets these closed contracts:

| Contract             | Purpose                                                               |
| -------------------- | --------------------------------------------------------------------- |
| `handshake`          | Runtime-Paper version and authentication negotiation                  |
| `agent-request`      | Session, actual player identity, module, message, and client features |
| `agent-complete`     | Correlated private fallback text and bounded structured views         |
| `agent-error`        | Correlated stable error code, safe fallback text, and retry hint      |
| `agent-cancel`       | Correlated Paper-originated cancellation reason                       |
| `session-resume`     | Player-owned exact or latest session selection request                |
| `session-resumed`    | Correlated selected non-null session identifier                       |
| `tool-call`          | Typed tool identity, closed arguments, and bounded loop sequence      |
| `tool-result`        | Status, source, trust label, result, or stable error                  |
| `proposal`           | Frozen write intent and integrity hashes                              |
| `proposal-confirmed` | Redacted correlation after trusted confirmation admission             |
| `proposal-cancelled` | Redacted correlation and a closed cancellation reason                 |
| `client-handshake`   | Client feature and dependency negotiation                             |
| `structured-view`    | Trusted fixed view variants and fallback relationship                 |
| `recipe-view`        | Structured server recipe representation                               |
| `build-preview`      | Bounded target projection and transform metadata                      |
| `capability`         | Declarative Capability Pack manifest                                  |

Schemas can exist before their behavior. Phase 5 enables agent request,
completion, error, and cancellation after hello; Phase 6 adds dedicated session
resume and explicit modules; Phase 7 adds the bounded read-tool exchange. A
request has a nullable owned session, one of six explicit modules, and no
connected-client features. A completion always contains non-empty
`fallbackText`; conversation storage can leave its session null, and the current
implementation emits an empty `structuredViews` array. Resume uses the
authenticated server ID and player UUID for both exact and latest lookup, and
all unavailable exact IDs share `SESSION_NOT_FOUND`.

During a live query, Runtime may send one serial `tool.call` at a time and Paper
returns one correlated `tool.result`. The result repeats session, player, tool,
and sequence in addition to the envelope request ID and unique call ID. Runtime
and Paper both apply the six-tool contract and module allowlist. Sequence is
zero-based and protocol 1.0 permits `0..7`; an eighth completed call exhausts
the loop and no ninth call is emitted. Failed/rejected results require
`result: null`, while a successful result requires typed object data and fixed
source/trust provenance. The implementation still does not publish a structured
view, create a proposal through the authenticated channel, or load a capability.

Phase 8 registers `proposal.create`, `proposal.confirmed`, and
`proposal.cancelled` payload schemas and a shared RFC 8785 argument-hash golden.
Paper owns proposal ID, frozen arguments, expiry, risk, and correlation. The
argument digest is SHA-256 over the UTF-8 bytes of
`minecraft-agent/proposal-arguments/v1`, one `0x00` byte, and the RFC 8785
canonical argument object; its wire form is exactly 64 lowercase hexadecimal
characters. Terminal proposal payloads repeat only fixed correlation and the
argument hash. They cannot contain arguments, rendered commands, credentials,
free-form errors, or execution output.

These are contracts, not active application directions. Runtime and Paper still
reject all three proposal envelope types as unsupported because no dispatcher is
wired. The Paper-local synchronous proposal service also has no production
`create` caller and its production typed write catalog is empty. A valid
proposal envelope therefore cannot cause a proposal or Minecraft write.

## Client views

An agent completion always has usable `fallbackText`. Structured views are sent
only when the connected client declared support for the exact view version.
Paper validates and sanitizes a fixed view model before publishing it. The model
cannot supply arbitrary widgets, texture paths, NBT, or click commands.

Client action fields are enums with typed IDs. A proposal action, for example,
contains a proposal ID that Paper resolves; it cannot contain `/op`, `/execute`,
or any other command text.

## Build preview transfer framing

The Phase 1 `build-preview` contract contains both preview metadata and a
bounded array of transfer chunks. Semantic validation treats the chunk array as
a transport layer even though protocol 1.0 carries it in the same JSON object.
The content hash is calculated from the complete uncompressed content, so it
remains stable when only chunk boundaries change.

The top-level object includes:

```text
schemaVersion, previewId, projectId, and revision
operation, dimension, bounds, origin, and transform
baseRegionHash, contentHash, and paletteHash
contentFormat and encoding
chunkCount
compressedBytes and uncompressedBytes
blockCount, difference counts, and palette
```

Each chunk carries a zero-based `index`, decoded `byteLength`, chunk `sha256`,
and canonical standard-base64 `data`. Semantic validation rejects negative or
duplicate indices, gaps, count mismatches, non-canonical base64, length
mismatches, chunk hash mismatches, whole-content hash mismatches, unsupported
encoding, and configured compressed/decompressed limits. Decompression happens
only after the compressed budget is accepted and remains subject to an output
limit.

For `operation = create`, `baseRegionHash` is null. For `operation = modify`, it
is required. An ACK correlates to preview ID and content hash but conveys no
server-side trust. Transfer timeout and per-connection aggregate budgets are
runtime controls for the later client-network phase, not claims made by the
Phase 1 JSON Schema.

## Build preview canonical form

A build preview must make these values explicit rather than leaving them in
natural language:

- Preview, project, and revision identifiers.
- Dimension and inclusive integer bounds.
- Origin, rotation, and mirror transform.
- Versioned palette of allowed block-state identifiers.
- Deterministically ordered block data or sections.
- Block count, encoding version, and whole-content hash.

The canonical hashing algorithm and block ordering are part of the encoding
version. A future format change creates a new encoding version; it does not
silently change hash behavior under protocol 1.0.

## Compatibility rules

- Runtime-Paper protocol 1.0 currently negotiates only exact version `1.0`.
- Fabric features are independently versioned; an old recipe view does not
  imply support for a build preview.
- Unknown fields are rejected rather than ignored in security-sensitive
  contracts.
- Unknown message types are rejected and audited.
- A client protocol failure falls back to private text; it must not take the
  Runtime-Paper link Offline.
- An incompatible Runtime-Paper protocol prevents readiness.

## Contract tests

Each contract manifest entry points to a schema and a valid or invalid fixture,
with paths relative to `protocol/`. Java and TypeScript validators must agree on
every fixture. Semantic rules cover behavior not expressible in JSON Schema,
including protocol matching and transfer reassembly/hash verification.

Test outcomes belong in `docs/progress.md` only after the responsible build has
actually run.
