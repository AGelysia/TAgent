# Protocol 1.0

## Scope

Protocol 1.0 defines contracts for two separate links:

1. Paper to the local Agent Runtime over an authenticated WebSocket. Phase 7
   keeps the Phase 3 hello exchange and enables the bounded agent, session, and
   read-tool exchanges after authentication. Phase 8 adds proposal contracts
   without enabling proposal transport handlers. Phase 10 permits negotiated
   client capabilities on `agent.request` and closed structured views inside
   `agent.complete`; standalone `view.publish` remains unsupported. Phase 12
   adds a server-wide management cost query over the same authenticated channel.
2. Paper to the optional Fabric client over versioned Minecraft custom payloads
   on the Phase 10 `minecraftagent:client` channel.

Phase 1 owns JSON Schemas, fixtures, and cross-language contract tests. Later
phases add closed payloads while keeping the socket open for a strictly bounded
conversation and read-tool channel. Phase 10 registers the separate Fabric
payload handlers and dispatches only the closed client handshake, view framing,
clear, UI-control, ACK, and error messages described below. Runtime-Paper
proposal messages and every write route remain disabled.

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
hello protocol, mod version, fixed feature versions, and detected Litematica and
MaLiLib versions. The outer client payload remains version `1.0`; the current
hello is `1.1`, while Paper continues to accept the diagnostic-free `1.0` hello.
It contains no Runtime authentication fields or player identity. Paper binds it
to the player on the actual plugin-message connection and replies with the
current positive connection generation.

## Payload contracts

Protocol 1.0 includes these closed contracts:

| Contract                    | Purpose                                                               |
| --------------------------- | --------------------------------------------------------------------- |
| `handshake`                 | Runtime-Paper version and authentication negotiation                  |
| `agent-request`             | Session, actual player identity, module, message, and client features |
| `agent-complete`            | Correlated private fallback text and bounded structured views         |
| `agent-error`               | Correlated stable error code, safe fallback text, and retry hint      |
| `agent-cancel`              | Correlated Paper-originated cancellation reason                       |
| `session-resume`            | Player-owned exact or latest session selection request                |
| `session-resumed`           | Correlated selected non-null session identifier                       |
| `tool-call`                 | Typed tool identity, closed arguments, and bounded loop sequence      |
| `tool-result`               | Status, source, trust label, result, or stable error                  |
| `management-costs-request`  | Empty authenticated request for current server-wide usage             |
| `management-costs-response` | Correlated UTC day/month cost and budget aggregates                   |
| `proposal`                  | Frozen write intent and integrity hashes                              |
| `proposal-confirmed`        | Redacted correlation after trusted confirmation admission             |
| `proposal-cancelled`        | Redacted correlation and a closed cancellation reason                 |
| `client-handshake`          | Client feature and dependency negotiation                             |
| `client-payload`            | Closed raw-JSON client channel envelope and message directions        |
| `structured-view`           | Trusted fixed view variants and fallback relationship                 |
| `recipe-view`               | Structured server recipe representation                               |
| `build-preview`             | Bounded target projection and transform metadata                      |
| `capability`                | Declarative Capability Pack manifest                                  |

Schemas can exist before their behavior. Phase 5 enables agent request,
completion, error, and cancellation after hello; Phase 6 adds dedicated session
resume and explicit modules; Phase 7 adds the bounded read-tool exchange. A
request has a nullable owned session and one of six explicit modules. Phase 10
adds the Paper-owned snapshot of the actual player's negotiated client features;
the Runtime validates but never treats those features as authority. A completion
always contains non-empty `fallbackText`; conversation storage can leave its
session null, and Phase 10 attaches a trusted version `1.0` text view derived
from the same final answer only when the final application envelope remains
within 64 KiB. Otherwise the encoder removes `structuredViews` and preserves the
fallback. Resume uses the authenticated server ID and player UUID for both exact
and latest lookup, and all unavailable exact IDs share `SESSION_NOT_FOUND`.

During a live query, Runtime may send one serial Paper-remote `tool.call` at a
time and Paper returns one correlated `tool.result`. The result repeats session,
player, tool, and sequence in addition to the envelope request ID and unique call
ID. Runtime-local documentation and project tools do not cross the WebSocket;
they use the same descriptor/schema/provenance checks before their bounded result
returns to the model loop. Runtime and Paper both apply the registered-tool
contract and module allowlist. Sequence is
zero-based and protocol 1.0 permits `0..7`; an eighth completed call exhausts
the loop and no ninth call is emitted. Failed/rejected results require
`result: null`, while a successful result requires typed object data and fixed
source/trust provenance. Structured views now travel only as validated fields of
`agent.complete`; the implementation still does not create a proposal through
the authenticated channel or expose a pack-backed Runtime capability.

### Management costs

`management.costs.request` is Paper-to-Runtime and has an empty closed payload.
Like other top-level queries, its `messageId` equals its newly allocated
`requestId`. Runtime returns one correlated `management.costs.response` with a
distinct response `messageId`; normal timestamp, nonce, server-ID, replay-cache,
schema, authentication, and 64 KiB application limits apply.

The response contains exactly `currentDay`, `currentMonth`, and `budget`.
`currentDay.period` is a UTC `YYYY-MM-DD`; `currentMonth.period` and
`budget.month` are UTC `YYYY-MM`. Each usage window contains admitted request
count, total provider calls, reported/estimated call counts, input/output token
totals, and integer `costMicroUsd`. `budget` contains the configured limit,
settled cost, active provider-round reservations, remaining amount after both,
and an exhausted flag. An estimated call means a successful provider response
omitted usage and Runtime conservatively settled that round at its configured
reservation. Counts and monetary fields are non-negative safe integers.

The payload is intentionally server-wide and bounded. It contains no player
UUID, session/request ID list, per-player count, prompt, completion, pricing
credential, or provider response detail. Player identity exists only in the
private Runtime admission tables needed to enforce the daily limit and is not
projected onto this management contract.

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

## Client custom payload channel

Paper and Fabric exchange one raw UTF-8 JSON document per plugin message on
`minecraftagent:client`. The bytes are not prefixed with a Java/Minecraft UTF
length and do not reuse the authenticated WebSocket envelope. The closed outer
shape is capped before parsing at 16 KiB from client to Paper and 40 KiB from
Paper to client:

```json
{
  "clientPayloadVersion": "1.0",
  "messageId": "00000000-0000-4000-8000-000000000020",
  "type": "view.begin",
  "payload": {}
}
```

Directions are fixed:

| Direction       | Types                                                                  |
| --------------- | ---------------------------------------------------------------------- |
| Client to Paper | `client.hello`, `client.ack`, `client.error`                           |
| Paper to client | `server.hello`, `view.begin`, `view.chunk`, `view.clear`, `ui.control` |

Paper assigns a positive per-player generation on the actual connection. Every
post-handshake message repeats it, and reconnect, disconnect, world transition,
Offline cleanup, or plugin disable invalidates pending state from older
generations. The current `client.hello` advertises protocol `1.1`, the client mod
version, independent bounded feature versions (`recipeView` may be `2`), and
nullable detected dependency versions. Protocol `1.1` requires
`diagnostics.litematicaAdapter`: a closed, path-free status plus bounded
Minecraft/Fabric/Litematica/MaLiLib/adapter version tuple. The status is one of
`READY`, `NOT_INSTALLED`, `MISSING_DEPENDENCY`, `UNSUPPORTED_VERSION`,
`ADAPTER_LINKAGE_FAILED`, or `PREVIEW_STORAGE_UNAVAILABLE`, and its nullable
fields and dependency presence must agree. A non-`READY` `1.1` hello must set
both Litematica capability versions to zero; a `READY` diagnostic may still
advertise zero. Paper binds the diagnostic to the current connection generation
for anonymous management aggregation only; it cannot raise a feature version or
authorize a UI action.

Paper also accepts the original hello protocol `1.0`, which has no `diagnostics`
field. Its already-negotiated presentation capabilities remain effective, and
Paper creates an internal `LEGACY_UNREPORTED` diagnostic solely for anonymous
operator counts. That internal status is not a wire value. `server.hello`
selects only server-known view schema `1.0`; it cannot be expanded by a client
claim.

`agent.complete.structuredViews` is an ordered alternatives list. Paper
validates every entry but publishes only the first view whose schema and
required features intersect the negotiated client state.

`view.begin` binds a transfer to generation, transfer, view, request, revision,
show/update mode, identity/gzip encoding, exact byte counts, chunk count, and
whole-content SHA-256. A `view.chunk` supplies the same generation and transfer,
one zero-based index, decoded length, per-chunk SHA-256, and canonical base64.
Production enforces 24 KiB decoded chunks, at most 1 MiB compressed and 1 MiB
uncompressed per view, at most 64 chunks, and a 15-second timeout. Paper permits
eight pending transfers and charges their uncompressed view bytes against a
2 MiB per-connection budget. The client permits two active reassemblies and
reserves their declared compressed bytes against its own 2 MiB budget.

Paper serializes, optionally compresses, hashes, and reserves each selected view
on its worker. The 15-second deadline starts at that reservation; the primary
thread rechecks the actual player, generation, and pending transfer before
sending the bounded plugin frames. A stale or expired reservation returns to the
private fallback path.

The client serializes inbound protocol work through one 256-entry worker queue
and performs reassembly plus bounded gzip and hash validation away from the
render thread. It rejects a stale generation, duplicate or missing index,
non-canonical base64, length/hash mismatch, invalid gzip framing, expansion,
invalid strict UTF-8, or metadata mismatch, and releases reserved bytes on every
terminal path. Only a verified view update can use one of the 128 pending
client-thread action reservations.

`client.ack` reports only `DISPLAYED` or `REJECTED`; `client.error` reports a
stable transport/presentation code. `DISPLAYED` retires the correlated fallback
record. `REJECTED`, a transfer-scoped client error, Paper-side timeout, or
generation replacement sends that private fallback once and cancels any
remaining correlated transfers. Neither message contains or creates permission,
proposal, selection, material, or execution authority.

## Client views

An agent completion always has usable `fallbackText`. Structured views are sent
only when the connected client declared support for the exact view version.
Paper validates and sanitizes a fixed view model before publishing it. The model
cannot supply arbitrary widgets, texture paths, NBT, or click commands.

The client renders version `1.0` Text, ItemStack, ItemList, and RecipeGrid views.
Text requires `overlay: 1`; item views additionally require `itemIcons: 1`; and
legacy recipe views require `recipeView: 1`. Phase 11 recipe v2 requires
`recipeView: 2` and preserves grid, single-input, smithing, transmute, and
explicitly unsupported layouts, complete ingredient choices, dynamic results,
processing metadata, and remaining items from the authoritative recipe tool.
Item IDs resolve through the local
Minecraft registry to real item icons, counts, and vanilla tooltips. An unknown
ID stays visible as an explicit missing-item state. Unsupported view types or
older/incompatible feature versions are not partially decoded; Paper uses the
private fallback instead.

Runtime builds recipe v2 and its text fallback only from a successful result
with `server_registry` / `authoritative` provenance. Failed, rejected, absent,
or differently sourced results use fixed neutral fallback text. The model cannot
supply or amend any recipe presentation field.

The overlay bounds scroll, drag, resize, pin/unpin, close, clear, and open-view
count. Client-local position, size, and pin preference persist independently of
server state. The only server controls are the closed `ui.control` action enum;
they carry a nullable view UUID and no arbitrary command or layout payload.

The Litematica feature versions are `1` only when the optional resolver matches
Minecraft 1.21.11, Fabric Loader 0.19.3, Litematica 0.26.12, and MaLiLib 0.27.16.
Its closed controls are `litematica.preview.load`,
`litematica.preview.remove`, and `litematica.material_list.open`. A load derives
the managed `<view-uuid>.<revision>.<artifact-uuid>.litematica` name locally; no
client filesystem path is
on the wire. Preparation reads and hashes at most 16 MiB on the protocol worker;
the final file metadata recheck and reflected load, remove, and Material List
operations run on the Minecraft client thread. An operation failure does not
dynamically withdraw the feature version already advertised for that connection.
Phase 11 validates Palette v1 and local Registry BlockStates, generates and
atomically registers a native Litematica v7 file, and waits for an explicit load
action. Receipt of a view never triggers placement automatically.

The client defines no proposal action. Proposal and selection view actions
remain later contracts and cannot be inferred from the closed `ui.control` enum.

## Build preview transfer framing

The Phase 1 `build-preview` contract contains both preview metadata and a
bounded array of transfer chunks. Semantic validation treats the chunk array as
a transport layer even though protocol 1.0 carries it in the same JSON object.
This is distinct from the outer view framing above. Phase 11 first verifies the
structured-view transfer, then applies the nested build-preview transfer and
semantic validation before registering a local schematic. The content hash is
calculated from the complete
uncompressed preview content, so it remains stable when only chunk boundaries
change.

The top-level object includes:

```text
schemaVersion, previewId, projectId, and revision
operation, dimension, bounds, origin, and transform
baseRegionHash, changeSetHash, contentHash, and paletteHash
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
only after the compressed budget is accepted, accepts exactly one gzip member,
rejects the optional `FHCRC` header flag, and remains subject to an output
limit. The uncompressed bytes must be strict
UTF-8, duplicate-free JSON already in RFC 8785 form. Palette IDs/states/hash,
complete target geometry, block ordering/counts, and difference bounds are then
checked before use.

Every operation carries Paper-produced `baseRegionHash` and `changeSetHash`.
Paper derives them from its bounded two-pass live-world snapshot using the
`minecraft-agent/region-state/v1` and `minecraft-agent/change-set/v1` domains.
Runtime-originated build views are removed; only the one-shot artifact bound to
the same Paper request and player can be appended for publication. The server
must also opt in with the exact environment value
`MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true`.

An ACK correlates presentation but conveys no server-side trust. The native
`.litematica` and Material List remain client-local. The production proposal
catalog is empty, so protocol 1.0 has no world apply or rollback path.

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
- The client payload envelope remains `1.0`; Paper accepts hello protocols `1.0`
  and `1.1`, and the current Fabric client emits `1.1`.
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
