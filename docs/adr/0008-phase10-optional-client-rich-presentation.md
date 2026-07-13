# ADR 0008: Phase 10 optional client rich presentation

Date: 2026-07-13

## Status

Accepted for Phase 10.

## Context

Vanilla clients must retain the private text path, while an optional Fabric
client can present bounded text, item, list, and recipe views. The client is not
an authority: its feature claims, UI actions, acknowledgements, Litematica
state, selections, and material results cannot grant a permission or confirm a
server-side operation.

Minecraft plugin messages provide player binding through the actual connection
but do not provide the authenticated Runtime-Paper envelope. Large JSON views
also cannot be decoded, decompressed, or assembled on the render thread. A
client reconnect, dimension transition, duplicated chunk, stale ACK, or
malformed compressed body must not retain state across connection generations
or affect the Runtime-Paper link.

Litematica and MaLiLib expose version-sensitive client internals and are
optional. Eager linkage would prevent the base client from loading when either
mod is absent. Conversely, accepting an arbitrary version through reflection
would turn an unverified internal signature into a compatibility claim.

## Decision

Paper and Fabric use one Bukkit/Fabric Custom Payload channel,
`minecraftagent:client`. Its bytes are raw UTF-8 JSON, not a UTF-prefixed string
and not a Runtime-Paper envelope. Every closed document has
`clientPayloadVersion: "1.0"`, a UUID `messageId`, a direction-specific `type`,
and a closed `payload`. The channel never receives the Runtime token, provider
key, a player UUID, a command string, or execution policy. The receiver rejects
the outer frame before parsing above 16 KiB from client to Paper or 40 KiB from
Paper to client.

The closed message directions are:

```text
client -> Paper: client.hello, client.ack, client.error
Paper -> client: server.hello, view.begin, view.chunk, view.clear, ui.control
```

Paper creates a positive connection generation for the actual player on join
and keeps a per-player capability snapshot. `client.hello` advertises exact
protocol and feature versions plus detected dependency versions. Paper's own
view registry decides which schema versions it can publish; a client claim
cannot add a server version. A new generation, disconnect, Offline transition,
world change, or plugin disable discards connection-scoped transfers. Old
generation traffic is rejected.

Every completion retains non-empty `fallbackText`. Runtime may attach only
closed trusted `structuredViews`; Phase 10 produces a version `1.0` text view
from the same final answer when the final authenticated envelope stays within
64 KiB. Otherwise the encoder removes the structured view and retains the
fallback. Paper first validates that every retained view carries the same
fallback and correlated request. The array is an ordered alternatives list, and
Paper sends only the first exact schema and feature intersection. No handshake
or compatible view means private fallback text only and no protocol error for a
vanilla client.

The Phase 10 renderer accepts exactly version `1.0` Text, ItemStack, ItemList,
and RecipeGrid models. Item views resolve namespaced IDs through the local
Minecraft registry, render a real `ItemStack`, count, and vanilla tooltip, and
show a bounded missing-item state for an unknown ID. The client decoder is
closed and separately bounds bytes, JSON depth, nodes, strings, arrays, and item
counts; it does not materialize an arbitrary widget or component tree.

Paper frames a selected view with `view.begin` followed by contiguous
`view.chunk` messages. It uses identity or gzip encoding and binds the transfer
to connection generation, transfer, view, request, revision, mode, byte counts,
chunk count, and lowercase SHA-256. Production limits are:

| Limit                                  | Value      |
| -------------------------------------- | ---------- |
| Client-to-Paper outer frame            | 16 KiB     |
| Paper-to-client outer frame            | 40 KiB     |
| Decoded bytes per chunk                | 24 KiB     |
| Compressed bytes per view              | 1 MiB      |
| Uncompressed bytes per view            | 1 MiB      |
| Chunks per view                        | 64         |
| Paper pending transfers                | 8          |
| Paper pending uncompressed bytes       | 2 MiB      |
| Client active reassemblies             | 2          |
| Client reserved declared compressed bytes | 2 MiB   |
| Client protocol worker queue           | 256        |
| Pending client-thread action reservations | 128     |
| Transfer timeout                       | 15 seconds |

Paper serializes, optionally compresses, hashes, and reserves the selected view
on its worker. The timeout begins at that reservation. Its primary thread
rechecks the actual player, connection generation, and pending transfer before
sending bounded plugin frames; a stale or expired plan returns to the private
fallback.

The client reserves the declared compressed size before accepting chunks,
checks canonical base64, index uniqueness, decoded length, per-chunk hash,
aggregate length, bounded single-member gzip, exact uncompressed size, complete
content hash, and strict UTF-8 before view decoding. One bounded worker
serializes inbound protocol tasks. Reassembly and compression work run away from
the render loop; only the verified presentation update may reserve a bounded
client-thread action slot. A failure removes the affected transfer, and
disconnect clears all reserved bytes.

The overlay owns scroll, drag, bounded resize, pin/unpin, close, and clear
state. At most eight views are open. Its width is limited to 180-420 pixels and
height to 96-320 pixels, then clamped to the current screen. Position, size, and
pin preference are stored atomically in the client-local
`config/minecraftagent/overlay.json`; the file is bounded and contains no
server authority or conversation body. `/agent ui pin|unpin|clear`, client key
actions, and the transparent interaction surface reach only these presentation
operations.

`client.ack` and `client.error` correlate only the current generation and
transfer. An ACK records `DISPLAYED` or `REJECTED`; it does not confirm that the
player read a view, approve a proposal, own a region, or authorize execution.
The schema deliberately has no permission, risk, selection, proposal status,
or command field. `DISPLAYED` retires the correlated fallback record;
`REJECTED`, a transfer-scoped error, Paper-side timeout, or generation
replacement sends that private fallback once and cancels remaining correlated
transfers.

Litematica support is isolated behind an adapter and is unavailable unless this
exact matrix matches:

| Minecraft | Fabric Loader | Litematica | MaLiLib | Adapter                    |
| --------- | ------------- | ---------- | ------- | -------------------------- |
| 1.21.11   | 0.19.3        | 0.26.12    | 0.27.16 | `litematica-reflection-1` |

The [Fabric Meta tuple](https://meta.fabricmc.net/v2/versions/loader/1.21.11/0.19.3)
records the Minecraft/Loader pair. The released mods are recorded by the
[Litematica 0.26.12](https://modrinth.com/mod/litematica/version/b3dJnV8d)
and [MaLiLib 0.27.16](https://modrinth.com/mod/malilib/version/oaU4Ys3J)
version pages. Reflected signatures were checked against the locked
[Litematica source](https://github.com/sakura-ryoko/litematica/tree/a1fad824536c8d9d8e93c54fe7222999d4fe4a7a)
and [MaLiLib source](https://github.com/sakura-ryoko/malilib/tree/53babf779aa6ce1478de17e0ac6c249bd764f803).
The base client has no eager Litematica class reference. Missing dependencies,
any other version tuple, signature mismatch, or adapter-link failure leaves only
the Litematica capabilities unavailable. A later runtime adapter failure fails
that local operation without dynamically withdrawing the feature version
already advertised for the connection.

The minimal adapter exposes `litematica.preview.load`,
`litematica.preview.remove`, and `litematica.material_list.open`. Its controller
derives a managed `<view-uuid>.<revision>.<artifact-uuid>.litematica` path below a client-owned root,
rejects links, escapes, non-regular files, and files above 16 MiB. It reads and
hashes the file on the protocol worker, then performs a final metadata recheck
and every reflected load, remove, Material List, and HUD call on the Minecraft
client thread. It tracks only adapter-owned placements. The server cannot supply
a filesystem path. These results remain local presentation facts.

Phase 10 does not convert `minecraft-agent.palette-v1` into a native
`.litematica` file and does not publish an end-to-end build preview. That
was the Phase 10 delivery boundary. Phase 11's deterministic read-only preview
path is recorded in
[ADR 0009](0009-phase11-authoritative-knowledge-and-preview.md); world apply and
rollback remain outside both decisions.

## Consequences

- Vanilla players keep private fallback replies without registering or parsing
  the optional channel.
- Exact per-feature negotiation permits compatible old clients to receive only
  the views they actually support.
- Oversized, stale, incomplete, duplicate, gzip-invalid, or hash-invalid
  transfers fail locally and release their byte budget.
- Client UI state can improve presentation but cannot enter Paper's permission,
  proposal, Capability, or execution decisions.
- Real registry items and native tooltips are available without accepting
  arbitrary NBT, textures, click commands, or component trees.
- The base Fabric client remains usable without Litematica and MaLiLib.
- Supporting another Litematica tuple requires a separately verified matrix
  entry and adapter signatures; version strings alone are insufficient.
- Automated headless tests establish protocol, decoder, UI-state, and
  adapter-selection behavior. Registry icon/tooltip rendering, a graphical
  client, and the real supported mod tuple still require a separate integration
  lane.
