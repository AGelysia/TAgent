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

The Phase 1 payload registry is:

| Envelope `type`                | Payload schema                |
| ------------------------------ | ----------------------------- |
| `runtime.hello`, `paper.hello` | `handshake.schema.json`       |
| `agent.request`                | `agent-request.schema.json`   |
| `tool.call`                    | `tool-call.schema.json`       |
| `tool.result`                  | `tool-result.schema.json`     |
| `proposal.create`              | `proposal.schema.json`        |
| `view.publish`                 | `structured-view.schema.json` |

The envelope reserves the other protocol message types listed in its enum. A
consumer must reject one with `UNSUPPORTED_MESSAGE_TYPE` until a concrete
payload schema is added to its local registry. A generic object payload is not
permission to accept an unimplemented message.

Paper-client Custom Payload messages do not use the authenticated WebSocket
envelope. `client.hello` uses `client-handshake.schema.json`; `view.show` and
`view.update` use `structured-view.schema.json`; and a Litematica preview uses
`build-preview.schema.json`. The network connection, rather than a payload
field, supplies the player UUID.

`structured-view.schema.json` has its own type-discriminated content. Recipe,
build preview, proposal, and ItemStack references resolve to the corresponding
local schemas. The client only accepts a view type and version it advertised.

Phase 1 makes that last rule executable as `view-negotiation-v1`. Text,
selection, and proposal views require `overlay: 1`; item views also require
`itemIcons: 1`; recipe views additionally require `recipeView: 1`; and build
previews require `litematicaPreview: 1`. The shared fixtures prove that a view
is rejected when one required version was not declared. Phase 10 must call this
rule before actual Custom Payload publication; Phase 1 does not install a
network handler.

## Envelope and authentication semantics

- `messageId` is unique for every sent message. `requestId` is copied to every
  response, delta, tool call, result, and view belonging to one request. For an
  uncorrelated initiating message, use the same new UUID for both fields.
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

Tool call `sequence` starts at zero and cannot exceed the protocol maximum of
eight rounds. The Runtime may configure a lower limit.

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
   reject trailing data, extra members, CRC errors, or expansion beyond
   `uncompressedBytes` and the configured limit. `identity+base64` skips this
   step. The resulting byte length must equal `uncompressedBytes`.
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
8. A `modify` preview requires a non-null server-produced `baseRegionHash`; a
   `create` preview requires null. Added, replaced, and removed totals are
   checked against the server's change limit. Block entity data is absent from
   this format and must not be attached out of band.

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
| `CONTENT_NOT_CANONICAL`                | Content bytes are not their RFC 8785 representation.                                               |
| `CONTENT_SHAPE_INVALID`                | Palette-v1 root or block records have missing, extra, or mistyped fields.                          |
| `PALETTE_HASH_MISMATCH`                | The canonical palette does not match `paletteHash`.                                                |
| `PALETTE_INVALID`                      | Palette IDs or BlockState properties are non-contiguous, duplicated, or invalid.                   |
| `BOUNDS_INVALID`                       | Bounds, origin, volume, dimension, or coordinate order is invalid.                                 |
| `BLOCK_CONTENT_INVALID`                | Blocks are unsorted, duplicated, out of bounds, use an unknown state, or differ from `blockCount`. |
| `BASE_REGION_HASH_INVALID`             | `operation` and `baseRegionHash` do not form the required create/modify pair.                      |
| `CHANGE_LIMIT_EXCEEDED`                | Difference totals exceed the active server limit.                                                  |

Schema-limit failures occur before this validator and therefore use the schema
validation error surface, not one of these semantic codes.

### Phase 1 implementation boundary

The cross-language Phase 1 harness implements duplicate/completeness checks,
canonical base64, per-chunk length and hash, incremental enforcement of the
16 MiB compressed limit, bounded gzip expansion to the declared size and
64 MiB hard limit, uncompressed length, and whole-content hash. It intentionally
does not claim the remaining production preview semantics in steps 4-8:
single-member/trailing-byte gzip framing, strict duplicate-key JSON parsing,
RFC 8785 canonicalization, Palette hash and continuity, content shape, geometry,
block count, base-region hash, or change-policy validation.

Those remaining rules and their reserved error codes are mandatory Phase 10
gates before any untrusted client payload handler or build preview publisher is
enabled. A Phase 1 helper returning success is evidence only that framing and
hashing passed; it is not permission to render a preview or modify a world.

The client additionally checks every `blockId` and property against its local
Registry before creating a preview. Client ACKs and material counts are display
signals only; Paper rechecks permissions, region and change-set hashes before a
world write.

## Recipe semantics

The Phase 1 shared semantic validator checks the selected index, distinct recipe
IDs, ingredient bounds, duplicate slots/coordinates, and row-major slot
mapping. The Schema independently enforces the closed structural fields,
bounded collections, display-component allowlist, tag presence, and the
presence of processing data for cooking recipes.

Before the Phase 11 recipe adapter publishes server data, the full validator
must additionally check that:

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

`recipe-view-v1` returns its first error in this order:

| Error code                           | Rule                                                                             |
| ------------------------------------ | -------------------------------------------------------------------------------- |
| `RECIPE_SELECTED_INDEX_OUT_OF_RANGE` | `selectedRecipe` is not an index in `recipes`.                                   |
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

Phase 1 executes the codes through `RECIPE_SLOT_COORDINATE_MISMATCH`; later
codes are reserved until the server recipe adapter supplies the required
registry and provider context.

For slot numbering, `slot = y * width + x`. Shapeless inputs use a compact
row-major layout. Cooking and stonecutting use `1x1`; smithing uses `3x1` in
template/base/addition order. A provider ID is required for `plugin_provider`
and null for `server_registry`; other source adapters define a stable provider
ID rather than embedding a URL.

## Proposal and capability semantics

- Proposal `argumentHash` is SHA-256 of RFC 8785 canonical frozen arguments.
  Expiration, active request binding, live player UUID/OP status, Offline state,
  current tool policy, region hash, and change-set hash are rechecked at
  confirmation. Confirmation executes the frozen arguments without another LLM
  call.
- Capability template placeholders must have a one-to-one match with declared
  arguments. Descriptor ranges must be internally ordered. Values use
  type-specific encoders, control characters are rejected, `commandRoot` must
  resolve to the expected plugin, and the fully rendered command must pass the
  server parser without leftovers.
- The capability schema can represent `source: console` so manifests have a
  stable shape, but Paper policy rejects console source by default. A manifest
  being schema-valid never enables it. Write effects still require the local
  permission policy and confirmation.
- Capability v1 does not accept manifest-supplied regular expressions. A future
  schema may add a portable, bounded pattern language only after Java and
  TypeScript validators can prove identical behavior.

`capability-manifest-v1` scans the template without rendering values and returns
its first error in this order:

| Error code                                  | Rule                                                                                           |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED` | Braces do not contain one complete declared-name token.                                        |
| `CAPABILITY_TEMPLATE_ARGUMENT_UNDECLARED`   | A placeholder has no argument descriptor.                                                      |
| `CAPABILITY_TEMPLATE_ARGUMENT_DUPLICATE`    | One argument is substituted more than once.                                                    |
| `CAPABILITY_REQUIRED_ARGUMENT_UNUSED`       | A required argument descriptor has no template placeholder.                                    |
| `CAPABILITY_OPTIONAL_ARGUMENT_UNSUPPORTED`  | A command template argument is not required; optional template segments are not defined in v1. |
| `CAPABILITY_COMMAND_ROOT_MISMATCH`          | The template's first literal command token differs from `commandRoot`.                         |
| `CAPABILITY_ARGUMENT_RANGE_INVALID`         | Numeric or string descriptor minimum exceeds its maximum.                                      |
| `CAPABILITY_EFFECT_CONSTRAINT_INVALID`      | Effect category, scope, and maximum-block constraints are inconsistent.                        |
| `CAPABILITY_CONFIRMATION_POLICY_INVALID`    | A write/admin effect does not require confirmation.                                            |
| `CAPABILITY_REVERSIBILITY_TARGET_INVALID`   | A referenced reversal capability is missing or incompatible in the loaded pack set.            |

Phase 1 executes placeholder shape/coverage, required-only template arguments,
command-root equality, and descriptor range ordering. Effect/confirmation
consistency, reversal target resolution, plugin ownership, and rendered-command parsing require Paper policy
and the effective registry in Phase 9; their codes remain reserved until then.

Resolving `commandRoot` to the expected installed plugin, checking its version,
enforcing `source: console`, and checking the live permission are later Paper
policy steps. They must not be reported as manifest structure success merely
because `capability-manifest-v1` passed.

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
