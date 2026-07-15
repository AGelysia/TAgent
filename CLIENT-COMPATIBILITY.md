# Client Compatibility

## Release boundary

The optional Minecraft Agent Fabric mod is a presentation client. Players
without it retain private chat fallback. The client can render structured views
and, with the one exact optional dependency tuple below, create and display a
local Litematica preview. It cannot apply blocks, confirm a proposal, grant a
permission, or provide authoritative world state.

The production write catalog is empty. There is no world apply or rollback,
Easy Place, printer, or automatic placement support in this release.

## Locked versions

| Component | Supported release target |
| --- | --- |
| Minecraft client | `1.21.11` |
| Java | `21` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.141.4+1.21.11` |
| Minecraft Agent Client | the JAR from the same server release |
| Litematica | `0.26.12` (optional) |
| MaLiLib | `0.27.16` (optional) |
| Litematica adapter | `litematica-reflection-1` |

The server target is Minecraft/Paper `1.21.11`; the real-server smoke artifact
is pinned separately in the repository verification scripts. A newer-looking
version is not assumed compatible. Supporting another Minecraft, Loader,
Litematica, or MaLiLib combination requires a reviewed matrix entry, adapter
signature review, contract coverage, and a real graphical client lane.

The Fabric metadata permits Loader `0.19.3` or newer so dependency resolution can
fail clearly, but the documented release target and the Litematica adapter matrix
remain the exact versions above. This document makes no compatibility claim for
other Loader versions.

## Client combinations

| Combination | Installed client components | Expected presentation |
| --- | --- | --- |
| Vanilla | Minecraft `1.21.11` only | Private chat fallback; no Agent overlay or Litematica features |
| Agent client | Minecraft, Loader, Fabric API, and matching Agent Client JAR | Structured overlay, item icons/tooltips, recipe view v2; Litematica capabilities unavailable |
| Agent client + Litematica | Agent client combination plus Litematica `0.26.12` and MaLiLib `0.27.16` | Base overlay plus managed preview load/remove lifecycle and native Litematica Material List HUD |

Keep the combinations in separate game directories or launcher profiles. Do not
move optional JARs in and out of one running client. Restart the client between
matrix cases so Fabric metadata, connection generation, managed artifacts, and
diagnostics cannot leak between cases.

Litematica and MaLiLib are not compile-time requirements of the Agent client.
The base Agent client must start when both are absent. Installing Litematica
without its required MaLiLib dependency may cause Fabric itself to reject the
profile before Minecraft Agent can report `MISSING_DEPENDENCY`; do not bypass
Fabric dependency checks to manufacture a diagnostic.

## Protocol compatibility

The raw Custom Payload channel is `minecraftagent:client`. The outer client
payload version is `1.0`. The current Agent Client hello protocol is `1.1`, with
closed, path-free Litematica diagnostics. Paper also accepts the legacy `1.0`
hello without diagnostics and records it as `LEGACY_UNREPORTED`; this preserves
only the exact feature versions negotiated for that connection.

Feature versions are intersected independently. A connected client does not
receive a structured view unless its exact advertised feature and view versions
match Paper's registry. An absent, legacy-incompatible, malformed, or rejected
client continues on private text fallback and must not take the Runtime-Paper
service Offline.

Current presentation feature versions are:

| Feature | Version |
| --- | --- |
| Overlay | `1` |
| Item icons | `1` |
| Recipe view | `2` |
| Litematica preview | `1` when the exact adapter is ready, otherwise `0` |
| Litematica Material List | `1` when the exact adapter is ready, otherwise `0` |

## Litematica diagnostics

The client reports one of these bounded states for operator diagnostics:

- `READY`: exact versions matched, preview storage initialized, and the adapter
  linked;
- `NOT_INSTALLED`: Litematica was not detected;
- `MISSING_DEPENDENCY`: Litematica was detected but MaLiLib was not;
- `UNSUPPORTED_VERSION`: both optional mods were detected but the complete tuple
  did not exactly match;
- `ADAPTER_LINKAGE_FAILED`: the exact tuple matched but reviewed reflective
  signatures could not link; or
- `PREVIEW_STORAGE_UNAVAILABLE`: the client-owned managed preview store could not
  be initialized safely.

Every non-`READY` state advertises both Litematica feature versions as zero. Base
overlay and recipe presentation remain available. `/agent doctor` shows only
anonymous grouped protocol, feature, dependency, adapter, and diagnostic facts;
it must not show player identity, connection generation, or client-local paths.

Do not rename a mod, edit its metadata, change a capability payload, loosen the
decoder, or raise transfer limits to force `READY`.

## Preview behavior

Paper preview publication is disabled unless the Paper process starts with the
exact value:

```text
MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true
```

This flag does not enable a write tool. Paper still creates only a bounded,
read-only preview from its own world snapshot. The client validates and stores a
connection-scoped native Litematica v7 file under its managed root.

Receiving a build view must not load a placement. Loading requires an explicit
action for the view UUID:

```text
/agent ui preview <view-id>
/agent ui materials <view-id>
/agent ui remove <view-id>
```

The first command requests placement load at the Paper-provided origin. The
second requests Litematica's native Material List HUD for an already loaded
placement. The third explicitly removes that Agent-owned placement and its
managed artifact. Explicit removal and disconnect cleanup must affect only
Agent-owned placements and files. The Phase 13 manual lane must exercise the
remove command directly; disconnect cleanup or overlay dismissal is separate
evidence.

Closing an overlay, receiving an ACK, loading a placement, or opening Material
List has no server authority. Preview files are bounded managed artifacts, not a
general client file interface.

## Verification record

This file defines the compatibility target; it is not a graphical test report.
Record release-specific client evidence in
[`docs/phase13-manual-test.md`](docs/phase13-manual-test.md) and final automated
results and artifact hashes in the repository-only
[`docs/progress.md`](https://github.com/AGelysia/TAgent/blob/main/docs/progress.md).
