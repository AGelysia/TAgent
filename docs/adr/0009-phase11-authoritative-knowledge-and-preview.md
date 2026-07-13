# ADR 0009: Phase 11 authoritative knowledge and build preview

## Status

Accepted for Phase 11.

## Context

The guide, locate, recipe, project, and build modules need richer data without
turning model text, local documents, Runtime storage, or client presentation
state into Minecraft authority. A build preview also crosses three different
trust domains: Runtime proposes a closed plan, Paper reads the live world, and
the optional Fabric client renders a local projection.

The existing proposal domain is synchronous, its production catalog is empty,
and no reviewed world-write adapter provides conflict, partial-failure, and
rollback behavior. Enabling a write merely because a preview exists would bypass
that boundary.

## Decision

- Runtime loads Markdown only from configured private roots. Search results keep
  citations, prefer `server_rules` over `local_docs`, and remain explicitly
  untrusted data in model context.
- Runtime stores project plans in its SQLite schema v2. Every operation is
  scoped by authenticated server ID and actual player UUID; updates require the
  exact current revision, and one owner may have at most 20 active projects.
- Paper owns the private `landmarks.yml` catalog. Permission filtering happens
  before result counts and truncation, and the live player position determines
  same-dimension distance ordering.
- Recipe view v2 is built only from a successful
  `server_registry`/`authoritative` tool result. The same source also produces
  the text fallback; model output cannot add or amend recipe facts.
- `build.preview.create` is a read-only Paper operation. Paper checks the live
  player, permission, current dimension, loaded chunks, world bounds, block
  states, block entities, and bounded geometry; performs a two-pass sliced
  snapshot; and derives canonical Palette v1 content plus base-region,
  change-set, palette, and content hashes. It never changes a block.
- Runtime-originated `build_preview` views are discarded. Paper may attach only
  the one-shot preview artifact bound to the same request and player. Publishing
  remains operator opt-in through the exact environment value
  `MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true`.
- Fabric validates the complete preview, resolves BlockStates against its local
  registry, generates a managed native `.litematica` file atomically, and waits
  for an explicit load action. Placement and the native Material List HUD remain
  client-local presentation signals with no server authority.
- The production proposal/write catalog stays empty. World apply and rollback
  remain behind the existing next gate for a typed asynchronous proposal path,
  live reauthorization, conflict policy, bounded mutation, durable audit, and a
  real-player integration test.

## Consequences

Phase 11 can answer from private server documentation, retain player-owned
plans, locate visible landmarks, render authoritative recipe variants, and show
a deterministic local build preview without expanding the execution surface.
Vanilla or incompatible clients continue to receive private text fallback.

A successful tool result, preview hash, `.litematica` load, material count,
client ACK, or project revision does not prove that a world change was approved
or applied. The headless development host does not validate graphical rendering
or the real Litematica/MaLiLib UI; that remains a separate manual client lane.
