# Minecraft Agent Protocol 1.0

This directory is the source of truth for data exchanged by the Runtime, Paper
plugin, and optional Fabric client. All schemas use JSON Schema Draft 2020-12.
Consumers must load the files locally, register every `$id`, enable `uuid` and
`date-time` format assertions, and must not resolve schema references over the
network.

Protocol 1.0 uses exact version matching. A document declaring another version
is not partially accepted or silently downgraded.

## Validation pipeline

Runtime-Paper messages use two schema passes:

1. Apply transport byte, timeout, and queue limits before parsing. Decode strict
   UTF-8 JSON and reject duplicate object keys.
2. Validate the complete document with `envelope.schema.json`.
3. Select the payload schema from the already validated `type`, then validate
   `payload` with that schema. Never select a schema from an unvalidated type.
4. Run the message-specific semantic checks described below.
5. Apply the current Paper policy, live permissions, Offline state, and
   tool-specific argument or result schema. Schema validity is not permission.

The protocol 1.0 payload registry is:

| Envelope `type`                | Payload schema                   |
| ------------------------------ | -------------------------------- |
| `runtime.hello`, `paper.hello` | `handshake.schema.json`          |
| `agent.request`                | `agent-request.schema.json`      |
| `agent.complete`               | `agent-complete.schema.json`     |
| `agent.error`                  | `agent-error.schema.json`        |
| `agent.cancel`                 | `agent-cancel.schema.json`       |
| `session.resume`               | `session-resume.schema.json`     |
| `session.resumed`              | `session-resumed.schema.json`    |
| `tool.call`                    | `tool-call.schema.json`          |
| `tool.result`                  | `tool-result.schema.json`        |
| `proposal.create`              | `proposal.schema.json`           |
| `proposal.confirmed`           | `proposal-confirmed.schema.json` |
| `proposal.cancelled`           | `proposal-cancelled.schema.json` |
| `view.publish`                 | `structured-view.schema.json`    |

The authenticated application encoder enforces the 64 KiB envelope limit after
payload validation. If an `agent.complete` with `structuredViews` exceeds that
limit, Runtime retries encoding the same completion with an empty view list.
The fallback-only envelope must still fit the limit; Runtime never removes the
fallback.

The envelope reserves the other protocol message types listed in its enum. A
consumer must reject one with `UNSUPPORTED_MESSAGE_TYPE` until a concrete
payload schema is added to its local registry. A generic object payload is not
permission to accept an unimplemented message.

Paper-client Custom Payload messages do not use the authenticated WebSocket
envelope. They use the single `minecraftagent:client` channel and one raw UTF-8
JSON document validated by `client-payload.schema.json`. The closed document has
`clientPayloadVersion`, `messageId`, `type`, and `payload`; the network
connection, rather than a payload field, supplies the player UUID. Receivers
reject client-to-Paper frames above 16 KiB and Paper-to-client frames above
40 KiB before parsing.

The Phase 10 direction registry is:

| Direction       | Client payload `type`                                                  |
| --------------- | ---------------------------------------------------------------------- |
| Client to Paper | `client.hello`, `client.ack`, `client.error`                           |
| Paper to client | `server.hello`, `view.begin`, `view.chunk`, `view.clear`, `ui.control` |

`client.hello` selects `client-handshake.schema.json`. The other payload shapes
are closed definitions in `client-payload.schema.json`. A structured view is the
verified, reassembled content described by a `view.begin` and its
`view.chunk` messages; the view body then validates against
`structured-view.schema.json`. A Litematica build preview additionally uses
`build-preview.schema.json`. Phase 11 publishes that view type only from a
Paper-owned artifact when the operator explicitly enables it.

`structured-view.schema.json` has its own type-discriminated content. Recipe,
build preview, proposal, and ItemStack references resolve to the corresponding
local schemas. The client only accepts a view type and version it advertised.

Phase 1 makes that last rule executable as `view-negotiation-v1`. Text,
selection, and proposal views require `overlay: 1`; item views also require
`itemIcons: 1`; legacy recipe content requires `recipeView: 1`, while
`recipe-view-v2` requires `recipeView: 2`; and build previews require
`litematicaPreview: 1`. The outer structured-view version remains `1.0` for both
recipe content versions. The shared fixtures prove that a v2 recipe is rejected
when the client declared only v1. Paper calls this rule before actual Custom
Payload publication. The Phase 11 client accepts the legacy version `1.0` Text,
ItemStack, ItemList, and RecipeGrid views and recipe v2's expanded layouts only
after negotiating `recipeView: 2`.

### Client transfer semantics

Paper assigns a positive generation to the actual player connection. Every
message after `client.hello` is generation-bound. Reconnect, disconnect, world
change, Offline cleanup, and plugin disable discard pending state, so an ACK or
chunk from an older generation cannot target a later session.

`view.begin` binds `transferId`, `viewId`, `requestId`, `revision`, show/update
mode, identity/gzip encoding, compressed/uncompressed byte counts, chunk count,
and the lowercase SHA-256 of the complete uncompressed view JSON. Each
`view.chunk` repeats the generation and transfer, with one index, decoded byte
length, chunk SHA-256, and canonical standard-base64 data.

The common production view limits are:

| Limit                        | Value      |
| ---------------------------- | ---------- |
| Decoded chunk                | 24 KiB     |
| Compressed view              | 1 MiB      |
| Uncompressed view            | 1 MiB      |
| Chunks per view              | 64         |
| Incomplete transfer lifetime | 15 seconds |

Paper admits at most eight pending transfers and charges their uncompressed view
bytes against a 2 MiB per-connection budget. It serializes, optionally
compresses, hashes, and reserves each selected view on its worker. The transfer
deadline starts at reservation; the primary thread rechecks the player,
generation, and pending state before sending bounded plugin frames.

The client admits at most two active reassemblies and reserves declared
compressed bytes against its own 2 MiB budget. One 256-entry worker queue
serializes inbound protocol tasks. The client performs reassembly, single-member
gzip validation, expansion limiting, exact length checks, per-chunk and
complete-content hashes, and strict UTF-8 decoding away from the render thread.
Duplicate, out-of-range, stale, timed-out, oversized, incomplete, or conflicting
transfers are discarded and release their budget. Only then may the closed
structured-view decoder use one of at most 128 pending client-thread action
reservations.

`client.ack` has only current generation, transfer ID, `DISPLAYED`/`REJECTED`,
and a stable code. `client.error` has the same transport correlation and no
authority fields. The invalid ACK fixture proves that a permission field is
rejected. `DISPLAYED` retires the correlated fallback record; `REJECTED`, a
transfer-scoped error, Paper-side timeout, or generation replacement sends the
private fallback once and cancels remaining correlated transfers. ACK, UI
selection, Litematica, and material-list results never grant permission, satisfy
a proposal, or authorize a world change.

`litematicaPreview: 1` and `litematicaMaterialList: 1` are advertised only by
the exact Minecraft 1.21.11 / Fabric Loader 0.19.3 / Litematica 0.26.12 /
MaLiLib 0.27.16 adapter. The closed controls carry only a local operation name
and nullable view UUID. The client derives a managed
`<view-uuid>.<revision>.<artifact-uuid>.litematica`;
no server-provided path, file content, permission, or execution result is part
of `ui.control`. Load preparation reads and hashes at most 16 MiB on the
protocol worker. The final metadata recheck and every reflected load, remove,
and Material List call run on the Minecraft client thread. A runtime operation
failure does not dynamically withdraw the feature version advertised at hello.
Phase 11 generates the managed native file from a completely validated Palette
v1 view but never loads it automatically. A separate explicit client action is
required before the adapter creates a placement or opens its Material List HUD.

## Envelope and authentication semantics

- `messageId` is unique for every sent message. `requestId` is copied to every
  response, delta, tool call, result, and view belonging to one request. For an
  uncorrelated initiating message, use the same new UUID for both fields.
- `agent.cancel` is correlated by the original `requestId` and trusted player
  identity. Cancellation is idempotent: an unknown, completed, or already
  cancelled request has no effect, and a late terminal response is discarded.
- `session.resume` uses a null `sessionId` to request the most recently updated
  session visible to the authenticated `(serverId, playerUuid)` pair. A UUID
  requests that exact session. The Runtime must use the same owner and server
  predicates for both lookups and return `SESSION_NOT_FOUND` for every miss;
  it must not reveal whether another player or server owns a supplied UUID.
- `session.resumed` returns the selected non-null session ID on the same
  `requestId`. When conversation storage is disabled, the Runtime returns
  `CONVERSATION_STORAGE_DISABLED` instead of accepting a resume request.
- A `module` on `agent.request` is a one-request route. It does not mutate the
  resumed session's default module; an ordinary follow-up uses `general` unless
  it explicitly names another module again.
- The receiver checks an allowed clock-skew window and stores accepted nonces
  until that window expires. A repeated nonce or message ID is rejected before
  payload handling. The nonce contains at least 128 bits from a CSPRNG and is
  encoded as canonical base64url without padding. Handshake challenges and
  proofs use the same unpadded encoding.
- `serverId`, `requestId`, `sessionId`, and `playerUuid` must agree with the
  authenticated connection and active Paper request. Payload values do not
  establish identity.
- `handshake.authentication.proof` is base64url HMAC-SHA-256 using the configured
  server token. The signed UTF-8 input is the newline-separated string
  `minecraft-agent-handshake-v1`, envelope `serverId`, envelope `type`, envelope
  `timestamp`, envelope `nonce`, payload `component`, payload
  `componentVersion`, and payload `authentication.challenge`, in that order.
  The raw token is never sent. Compare proofs in constant time.
- `handshake.component` must match the envelope type. A hello has a null
  `selectedProtocolVersion`; an accepting Runtime reply echoes Paper's exact
  challenge and selects a version present in both peers' advertised sets. Phase
  1 only permits `1.0`.

`fixtures/valid/handshake-proof-v1.json` publishes its key only as an explicit,
deterministic test vector. `publicTestToken` is not a deployment credential and
must never be accepted as a configured server token.

Plain `ws://` is only acceptable on the configured loopback address. The client
channel never receives the server token or model API key.

## Generic tool values

`tool-call.arguments` and successful `tool-result.result` are deliberately
bounded JSON objects but are not treated as self-describing values. After the
outer payload validates, the receiver must look up the registered tool and run
that tool's argument or result schema. Unknown tools, undeclared arguments, and
extra results are rejected. Risk, permission, and confirmation metadata come
from the trusted Paper registry, not from these generic objects or the model.

Every `tool.result` echoes the call's `toolCallId`, `sessionId`, `playerUuid`,
`tool`, and `sequence`. The Runtime must match all of them, plus the envelope
`requestId` and authenticated `serverId`, against the active call. A successful
result has a non-null `result` and null `error`; a rejected or failed result has
a null `result` and non-null `error`. `source` and `trust` come from the trusted
Paper registry and executor, never from model arguments.

Tool call `sequence` is the zero-based model round and is limited to `0..7`,
for a protocol maximum of eight rounds. Phase 7 permits one active Tool Call per
request and round. A Runtime supporting parallel calls later must add an
explicit ordering contract rather than overloading `sequence`.

The registered Phase 11 tools use these closed schemas:

| Tool                    | Arguments schema                                    | Result schema                                    | Source / trust                      |
| ----------------------- | --------------------------------------------------- | ------------------------------------------------ | ----------------------------------- |
| `player.context.read`   | `tools/player-context-read-arguments.schema.json`   | `tools/player-context-read-result.schema.json`   | `paper_api` / `authoritative`       |
| `player.held_item.read` | `tools/player-held-item-read-arguments.schema.json` | `tools/player-held-item-read-result.schema.json` | `paper_api` / `authoritative`       |
| `server.info.read`      | `tools/server-info-read-arguments.schema.json`      | `tools/server-info-read-result.schema.json`      | `paper_api` / `authoritative`       |
| `server.plugins.list`   | `tools/server-plugins-list-arguments.schema.json`   | `tools/server-plugins-list-result.schema.json`   | `paper_api` / `authoritative`       |
| `server.recipe.lookup`  | `tools/server-recipe-lookup-arguments.schema.json`  | `tools/server-recipe-lookup-result.schema.json`  | `server_registry` / `authoritative` |
| `server.recipe.uses`    | `tools/server-recipe-uses-arguments.schema.json`    | `tools/server-recipe-uses-result.schema.json`    | `server_registry` / `authoritative` |
| `landmark.search`       | `tools/landmark-search-arguments.schema.json`       | `tools/landmark-search-result.schema.json`       | `paper_api` / `authoritative`       |
| `build.preview.create`  | `tools/build-preview-create-arguments.schema.json`  | `tools/build-preview-create-result.schema.json`  | `paper_api` / `authoritative`       |
| `server.docs.search`    | `tools/server-docs-search-arguments.schema.json`    | `tools/server-docs-search-result.schema.json`    | `server_docs` / `untrusted`         |
| `project.list`          | `tools/project-list-arguments.schema.json`          | `tools/project-list-result.schema.json`          | `runtime_storage` / `verified`      |
| `project.read`          | `tools/project-read-arguments.schema.json`          | `tools/project-read-result.schema.json`          | `runtime_storage` / `verified`      |
| `project.create`        | `tools/project-create-arguments.schema.json`        | `tools/project-create-result.schema.json`        | `runtime_storage` / `verified`      |
| `project.update`        | `tools/project-update-arguments.schema.json`        | `tools/project-update-result.schema.json`        | `runtime_storage` / `verified`      |

The player tools take an empty object: their target is always the player bound
to the active request. Recipe results use the shared typed values in
`tools/common.schema.json`; they preserve ItemStack fields, IngredientChoice,
recipe kind, and layout. A zero-match query returns an empty `recipes` array.
Unsupported custom layouts use the explicit `unsupported` representation and
are never converted to invented grids or plain text. Result construction must
also stay within the 64 KiB application-envelope limit; truncate only between
complete recipes and set `truncated`, never truncate one IngredientChoice.

Runtime executes documentation and project tools locally, but their wire-shaped
results use the same schema and bounded model continuation path. Documentation
matches retain a stable citation and remain untrusted even when their kind is
`server_rules`; that kind sorts before `local_docs`, not above Paper policy.
Project operations derive owner fields from the authenticated request rather
than arguments. Create starts at revision 1, update requires an exact positive
`expectedRevision`, and absent, foreign, or changed projects share a closed
non-disclosing outcome.

## Build preview transfer semantics

`build-preview.schema.json` validates bounded metadata and the shape of each
chunk. JSON Schema cannot prove chunk continuity, decoded lengths, hashes, or
cross-field geometry, so every consumer must also implement
`build-preview-transfer-v1` in this order:

1. Reject duplicate indexes, then require the index set to be exactly
   `0..chunkCount-1`. Array order is not significant. Do not wait indefinitely
   for a missing chunk.
2. Strictly base64-decode each chunk. Its decoded length must equal
   `byteLength`, and SHA-256 must equal the chunk's `sha256`.
3. Concatenate decoded chunks by index. The total must equal `compressedBytes`
   and remain within the configured compressed limit.
4. For `gzip+base64`, decompress one gzip member with a streaming output limit;
   reject the optional `FHCRC` header flag, trailing data, extra members, CRC
   errors, or expansion beyond `uncompressedBytes` and the configured limit.
   `identity+base64` skips this step. The resulting byte length must equal
   `uncompressedBytes`.
5. SHA-256 of the uncompressed bytes must equal `contentHash`. Parse them as
   strict UTF-8 JSON, reject duplicate keys, canonicalize with RFC 8785, and
   require the canonical bytes to equal the transferred bytes.
6. `minecraft-agent.palette-v1` content has exactly this logical shape:

   ```json
   {
     "blocks": [{ "state": 0, "x": 0, "y": 64, "z": 0 }],
     "version": 1
   }
   ```

   Only `version` and `blocks` are allowed. Every block has only integer
   `state`, `x`, `y`, and `z`. Records are uniquely positioned and sorted by
   `y`, then `z`, then `x`; `state` references a declared palette ID. The number
   of records equals `blockCount`.

7. RFC 8785 SHA-256 of `palette` must equal `paletteHash`. Palette IDs are
   unique and contiguous from zero. Bounds have `min <= max`; all blocks and the
   origin are in the declared dimension and bounds; the bounds volume and block
   count are within the server policy.
8. Every preview carries server-produced `baseRegionHash` and `changeSetHash`
   values. Added, replaced, and removed totals must not exceed the bounded
   region. Block entity data is absent from this format and must not be attached
   out of band.

`build-preview-transfer-v1` returns the first applicable stable error code in
this order:

| Error code                             | Rule                                                                                               |
| -------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `CHUNK_INDEX_DUPLICATE`                | More than one chunk declares the same index.                                                       |
| `CHUNK_SET_INCOMPLETE`                 | Indexes are not exactly `0..chunkCount-1`.                                                         |
| `CHUNK_BASE64_INVALID`                 | A chunk is not canonical, strictly decodable base64.                                               |
| `CHUNK_LENGTH_MISMATCH`                | Decoded chunk bytes differ from `byteLength`.                                                      |
| `CHUNK_HASH_MISMATCH`                  | A decoded chunk does not match its SHA-256.                                                        |
| `CONTENT_COMPRESSED_LENGTH_MISMATCH`   | Concatenated transfer bytes differ from `compressedBytes`.                                         |
| `CONTENT_DECOMPRESSION_FAILED`         | Gzip framing, CRC, trailing bytes, member count, or output limit is invalid.                       |
| `CONTENT_UNCOMPRESSED_LENGTH_MISMATCH` | Decoded content differs from `uncompressedBytes`.                                                  |
| `CONTENT_HASH_MISMATCH`                | Uncompressed content does not match `contentHash`.                                                 |
| `CONTENT_JSON_INVALID`                 | Content is not strict UTF-8 JSON or has duplicate keys.                                            |
| `CONTENT_CANONICAL_MISMATCH`           | Content bytes are not their RFC 8785 representation.                                               |
| `PALETTE_HASH_MISMATCH`                | The canonical palette does not match `paletteHash`.                                                |
| `PALETTE_ID_INVALID`                   | Palette IDs are missing or are not contiguous array indexes.                                       |
| `PALETTE_STATE_INVALID`                | A palette BlockState is malformed or explicitly names air.                                         |
| `PALETTE_STATE_DUPLICATE`              | More than one palette entry describes the same canonical BlockState.                               |
| `PALETTE_ORDER_INVALID`                | Canonical BlockStates are not sorted.                                                               |
| `BLOCK_GEOMETRY_INVALID`               | Bounds or a block position/palette reference is invalid.                                           |
| `BLOCK_COUNT_MISMATCH`                 | `blockCount` does not equal the complete non-air target list.                                      |
| `BLOCK_ORDER_INVALID`                  | Blocks are duplicated or are not sorted by `y`, then `z`, then `x`.                               |
| `DIFFERENCE_COUNT_INVALID`             | Difference counts are malformed or exceed the bounded region.                                      |

Schema-limit failures occur before this validator and therefore use the schema
validation error surface, not one of these semantic codes.

### Phase 11 implementation boundary

The Java and TypeScript shared validators implement the complete transfer and
logical validation sequence above, including out-of-order reassembly, strict
single-member gzip, duplicate-free strict JSON, RFC 8785 bytes, palette hashes,
geometry, target block count, and difference bounds. The Fabric decoder repeats
those checks, verifies every BlockState against its local Registry, and
deterministically converts the accepted target into a managed native Litematica
v7 file. Receiving a valid view registers that file but does not load it; the
player must explicitly request the load.

Paper is the only preview producer trusted for publication. Its bounded two-pass
world snapshot derives `baseRegionHash` with the
`minecraft-agent/region-state/v1` domain and `changeSetHash` with the
`minecraft-agent/change-set/v1` domain over RFC 8785 content. Runtime-originated
`build_preview` entries are removed before Paper appends the request/player-bound
one-shot artifact. Publication remains disabled unless the server process has
the exact value `MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true`.

This is still read-only preview behavior. Client ACKs, a local placement, and
material counts remain display signals only. The production write proposal
catalog is empty, so no world apply or rollback path exists.

## Recipe semantics

The legacy `recipe-view-v1` validator checks the selected index, distinct recipe
IDs, ingredient bounds, duplicate slots/coordinates, and row-major slot
mapping. `recipe-view-v2.schema.json` instead reuses the exact Recipe definition
from `tools/common.schema.json`. It therefore preserves grid, single-input,
smithing, transmute, and explicitly unsupported layouts; material, exact,
item-type, tag, and explicitly unsupported choices; nullable dynamic results;
and zero-tick cooking metadata without maintaining a second Recipe model.

The `recipe-view-v2` semantic validator additionally checks that:

- `selectedRecipe` is a valid index and recipe IDs remain distinct variants;
- ingredient slots and `(x,y)` coordinates are unique, inside the declared
  layout, and use the fixed layout for the recipe type;
- tag choices retain their tag ID and explicit alternatives rather than being
  reduced to one model-selected item;
- cooking recipes include processing data, and other recipe types reject
  nonsensical processing fields;
- provider metadata agrees with the source, remaining-item slots exist, and
  safe component values satisfy their Minecraft invariants, such as damage not
  exceeding max damage.

Item components are an explicit display-only allowlist. Arbitrary NBT, click
commands, texture paths, and model-produced component trees are not part of the
schema. A client that cannot resolve an otherwise valid namespaced item ID shows
the original ID with a missing-item placeholder.

`recipe-view-v2` returns only its first error in this stable order:

| Error code                           | Rule                                                                             |
| ------------------------------------ | -------------------------------------------------------------------------------- |
| `RECIPE_VIEW_STRUCTURE_INVALID`      | The v2 root or recipe collection is structurally unavailable.                    |
| `RECIPE_SELECTED_INDEX_OUT_OF_RANGE` | `selectedRecipe` is not an index in `recipes`.                                   |
| `RECIPE_RESULT_SUMMARY_INVALID`      | `totalMatches` or `truncated` contradicts the published recipe count.            |
| `RECIPE_ID_DUPLICATE`                | More than one variant has the same `recipeId`.                                   |
| `RECIPE_LAYOUT_INVALID`              | Width, height, ingredient count, or recipe-type-specific layout is inconsistent. |
| `RECIPE_INGREDIENT_OUT_OF_BOUNDS`    | An ingredient coordinate is outside the declared layout.                         |
| `RECIPE_INGREDIENT_DUPLICATE`        | A recipe repeats a logical slot or coordinate.                                   |
| `RECIPE_SLOT_COORDINATE_MISMATCH`    | `slot` is not the row-major index for `(x,y)`.                                   |
| `RECIPE_INGREDIENT_CHOICE_INVALID`   | Choice kind, tag presence, alternatives, or exact/material semantics conflict.   |
| `RECIPE_PROCESSING_INVALID`          | Cooking processing data is absent or appears on an incompatible recipe type.     |
| `RECIPE_SOURCE_INVALID`              | Provider identity does not agree with the declared source kind.                  |
| `RECIPE_REMAINING_ITEM_INVALID`      | A remaining item references no ingredient slot or repeats a slot.                |
| `RECIPE_COMPONENT_INVALID`           | Safe display components violate a cross-field Minecraft invariant.               |

Both shared implementations execute the complete v2 sequence. Manifest cases
assert the expected code is the first returned code, so Java and TypeScript
cannot silently choose different error precedence.

For grid slot numbering, `slot = y * width + x`. Shapeless inputs use a compact
row-major layout. Single-input layouts expose logical slot `0`; smithing exposes
template/base/addition as slots `0..2`; and transmute exposes input/material as
slots `0..1`. A provider ID is required for `plugin_provider` and null for
`server_registry`.

Runtime may create this view only from a successful recipe tool result whose
registered provenance is exactly `server_registry` / `authoritative`. It copies
the already validated result and derives the text fallback from that same
snapshot. A failed, rejected, differently sourced, or absent recipe result
produces a fixed neutral text fallback; model output cannot supply, merge, or
amend recipe IDs, layouts, choices, results, or source metadata.

## Proposal and capability semantics

- `proposal.create` establishes the `PENDING` state. Paper is the authority for
  the proposal ID, frozen arguments, risk, summary, expiration, and hashes; a
  model or client value does not establish any of them. The envelope
  `requestId`, authenticated `serverId`, and payload `proposalId`, `sessionId`,
  `playerUuid`, `tool`, and `argumentHash` form the correlation tuple.
- Proposal `argumentHash` is calculated over the exact frozen `arguments`
  object after tool-specific validation. Let `C` be its RFC 8785 canonical JSON
  encoded as UTF-8. The digest input is exactly the UTF-8 bytes of
  `minecraft-agent/proposal-arguments/v1`, one `0x00` byte, then `C`. Hash that
  byte string with SHA-256 and encode the 32-byte digest as exactly 64 lowercase
  hexadecimal characters without a prefix. Producers and consumers reject
  uppercase, `sha256:`-prefixed, base64, or non-canonical encodings rather than
  normalizing them.
- RFC 8785's I-JSON constraints apply before hashing. Numeric inputs are parsed
  as finite IEEE 754 binary64 values and serialized with the ECMAScript number
  algorithm; values that cannot survive that representation without violating a
  tool's type contract are rejected rather than hashed with arbitrary-precision
  or library-specific formatting.
- `proposal.confirmed` transitions one matching `PENDING` proposal to
  `CONFIRMED` only after the trusted Paper click handler has consumed its
  one-time confirmation and all live checks have passed. It means the write was
  authorized for immediate execution; the subsequent `tool.result` and audit
  event carry execution success or failure. Confirmation executes the frozen
  arguments without another LLM call.
- `proposal.cancelled` transitions one matching `PENDING` proposal to
  `CANCELLED`. Its `reason` is a closed machine-readable code; arbitrary detail
  is deliberately absent. Expiration, request cancellation, Offline, disconnect,
  permission or policy changes, hash mismatch, change-limit failure, invalid
  request context, unavailable audit persistence, failed execution admission,
  supersession, and shutdown all invalidate rather than authorize the proposal.
- Both terminal payloads echo the stored correlation fields and exact
  `argumentHash`. They are closed schemas and never contain `arguments`, a
  rendered command, credentials, free-form errors, or an execution result.
  Terminal events therefore cannot copy sensitive tool parameters into a
  confirmation result. Logs and user-visible messages must use the trusted
  bounded `summary` or field-name allowlists, never serialize frozen arguments.
- Confirmation rechecks expiration, active request binding, live player UUID
  and online state, OP status, Offline state, current tool policy, argument
  hash, region hash, change-set hash, and operation limits. A mismatch produces
  `proposal.cancelled` and no write. Terminal transitions are one-shot;
  duplicate clicks and late or conflicting terminal messages have no effect.
- JSON Schema limits only the outer proposal shape. Paper additionally owns the
  TTL and enforces bounded canonical-argument depth, node count, UTF-8 byte
  length, and the registered tool's closed argument schema before hashing or
  storing anything. `expiresAt` must be later than creation and no later than
  the server's configured maximum TTL; a model cannot extend it. The trusted
  `summary` rejects C0 and DEL control characters before it can reach Adventure,
  logs, or a client view.
- `fixtures/valid/proposal-argument-hash-v1.json` is the cross-language golden
  vector. Its numeric values intentionally cover RFC 8785's ECMAScript number
  serialization, including negative zero, exponent form, and binary64 rounding.
  Implementations must reproduce both `canonicalArguments` and `argumentHash`;
  merely hashing a JSON library's input spelling or insertion order is invalid.

Phase 8 makes these three payload schemas and the Paper-local authorization
state machine a shared contract. Schema registration does not install a
Runtime-Paper proposal transport dispatcher. A component without an explicitly
wired handler still rejects these message types as unsupported; schema validity
alone must never create, confirm, cancel, or execute a proposal.

- Capability template placeholders must each occupy one complete space-delimited
  command token and have a one-to-one match with declared arguments. A fixed
  command with `arguments: {}` and no placeholders is valid.
  Optional arguments are not valid in v1 because the format has no optional
  template segments. Descriptor ranges must be internally ordered. Values use
  type-specific encoders, control characters are rejected, `commandRoot` must
  resolve to the expected plugin, and the fully rendered command must pass the
  server parser without leftovers.
- Plugin requirements use only space-separated numeric comparators. Each
  comparator is one of `=`, `>`, `>=`, `<`, or `<=` followed by one to three
  numeric components, for example `=2.20.1` or `>=7.3 <8`. Bare versions,
  wildcards, pre-release labels, alternatives, and duplicate plugin names are
  invalid. One range contains at most 16 comparisons within the schema's
  128-character bound. This validates the range language only; Paper still
  compares it with the exact installed plugin identity and version. An
  installed version uses the same one-to-three numeric-component grammar
  without an operator. Missing components compare as zero; suffixes and leading
  zeroes are invalid and disable the capability rather than being normalized.
- Only `WRITE_WORLD` has a non-null `maximumBlocks`, and it must be bounded by
  the schema. The other effect categories use null. Every non-`READ` effect
  requires confirmation; a pack can require confirmation for a read as a
  stricter policy.
- The capability schema can represent `source: console` so manifests have a
  stable shape, but Paper policy rejects console source by default. A manifest
  being schema-valid never enables it. No `consolePolicy` or similar override
  exists in the closed schema, so pack content cannot relax that default. Write
  effects still require the local permission policy and confirmation.
- Optional `status` is exactly `example` or `draft`. Either value is an
  unconditional deny marker and can never enter the effective registry. An
  absent status means only that the manifest is a normal candidate; approval is
  separate Paper-owned `(id, version, hash)` state. Values such as `approved`
  are rejected by the schema.
- Capability v1 does not accept manifest-supplied regular expressions. A future
  schema may add a portable, bounded pattern language only after Java and
  TypeScript validators can prove identical behavior.

`capability-manifest-v1` scans the template without rendering values and returns
its first error in this order:

| Error code                                  | Rule                                                                                           |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED` | Braces do not contain one complete declared-name token, or the placeholder is not standalone.  |
| `CAPABILITY_TEMPLATE_ARGUMENT_UNDECLARED`   | A placeholder has no argument descriptor.                                                      |
| `CAPABILITY_TEMPLATE_ARGUMENT_DUPLICATE`    | One argument is substituted more than once.                                                    |
| `CAPABILITY_REQUIRED_ARGUMENT_UNUSED`       | A required argument descriptor has no template placeholder.                                    |
| `CAPABILITY_OPTIONAL_ARGUMENT_UNSUPPORTED`  | A command template argument is not required; optional template segments are not defined in v1. |
| `CAPABILITY_COMMAND_ROOT_MISMATCH`          | The template's first literal command token differs from `commandRoot`.                         |
| `CAPABILITY_ARGUMENT_RANGE_INVALID`         | Numeric or string descriptor minimum exceeds its maximum.                                      |
| `CAPABILITY_PLUGIN_REQUIREMENT_DUPLICATE`   | A plugin name appears more than once, compared case-insensitively.                             |
| `CAPABILITY_PLUGIN_VERSION_RANGE_INVALID`   | A plugin range is outside the closed numeric comparator grammar.                               |
| `CAPABILITY_EFFECT_CONSTRAINT_INVALID`      | Effect category, scope, and maximum-block constraints are inconsistent.                        |
| `CAPABILITY_CONFIRMATION_POLICY_INVALID`    | A write/admin effect does not require confirmation.                                            |
| `CAPABILITY_PACK_ID_DUPLICATE`              | A complete candidate set contains the same capability ID more than once.                       |
| `CAPABILITY_REVERSIBILITY_TARGET_INVALID`   | A referenced reversal capability is missing or incompatible in the loaded pack set.            |

The Phase 9 shared contract executes placeholder shape/coverage, required-only
template arguments, command-root equality, descriptor ranges, plugin range
syntax, effect limits, and confirmation consistency. `capability-pack-v1`
additionally resolves reversal targets over a complete candidate set. A target
must be an independently valid normal candidate, cannot refer to itself, and
must have the same execution source, effect category, scope, and plugin
requirement set. `fixtures/valid/capability-pack-reversal.json` and its missing
target counterpart keep this rule identical in TypeScript and Java. The golden
set is semantic test input, not a new pack-file or wire format.

`fixtures/valid/capability-plugin-version-v1.json` is the cross-language version
comparison vector. Its conjunction exercises all five comparison operators and
covers omitted zero components, ordinary mismatches, suffix rejection,
leading-zero rejection, and too many components. The validator proves
deterministic matching only; it does not prove that a plugin owns a command root
or approve the capability.

Resolving `commandRoot` to the expected installed plugin, checking its version,
enforcing `source: console`, rejecting `ANY` for writes, requiring `OWNER` for
server administration, rejecting reversal cycles/transitively unavailable
targets, and checking live permission are Paper policy and complete-publication
steps. They must not be reported as manifest structure success merely because
`capability-manifest-v1` passed. Likewise, neither semantic validator approves,
registers, renders, proposes, or executes a capability.

## Fixtures

`fixtures/manifest.json` is the cross-language contract-test index. Paths are
relative to `fixtures/`; schema names resolve in `schemas/`; and
`documentPointer` is an RFC 6901 JSON Pointer. Every implementation must produce
the declared `expectedValid` result with format assertions enabled.

Semantic fixture cases intentionally pass JSON Schema before failing the named
validator. In particular, missing, duplicate, and corrupt preview chunks prove
that an implementation did not mistake structural validation for complete
transport validation. Error precedence is duplicate index, incomplete set,
chunk decode/length/hash, aggregate size/decompression/content hash, then
logical content and geometry.
