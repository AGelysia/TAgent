# ADR 0007: Phase 9 fail-closed capability broker

Date: 2026-07-13

## Status

Accepted for Phase 9.

## Context

Servers expose commands through Minecraft, Paper, and third-party plugins, but
their names, arguments, permissions, version syntax, parser integration, and
side effects are not a stable common API. Giving a model a command string or a
generic dispatch operation would let untrusted output choose grammar outside a
reviewed policy. Simple placeholder replacement cannot prove that the result
remains one command or that the target plugin parsed it completely.

Phase 8 established Paper-owned proposals, live reauthorization, single-use
confirmation, and durable audit, but deliberately left the production write
catalog and proposal-creation surface empty. Capability loading must not bypass
that boundary merely because a manifest validates or a Brigadier root exists.

Pack files and installed-plugin metadata are also untrusted operational input.
Traversal, YAML construction, duplicate IDs, plugin version ambiguity, stale
approvals, partial reload, console source, underdeclared risk, and unsafe
reversal graphs all need deterministic fail-closed behavior.

## Decision

Paper owns a data-only Capability broker with four distinct stages:

```text
strict discovery and validation
  -> exact compatibility and owner approval
  -> immutable registry publication
  -> separately reviewed execution adapter
```

Phase 9 implements the first three stages and the parse-only part of adapter
validation. It does not implement generic execution.

Discovery is confined to the configured capability root and bounded by entry,
file, byte, directory-depth, alias, and YAML-depth limits. Unsafe roots, path
escapes, symbolic links, hard-linked or non-regular manifest files, unsafe
write modes, invalid UTF-8, and incomplete walks fail closed. Only `.json`,
`.yml`, and `.yaml` names are installed manifests; other regular files are
ignored while still counting toward traversal limits. Every supported extension
passes through SnakeYAML `SafeConstructor`, which creates only basic data
values. A second closed manual parser rejects unknown or missing fields,
unexpected node types, invalid values, duplicate plugin requirements, and
inconsistent policy.

Relative components use a closed bounded ASCII grammar. The loader checks
directories around enumeration and files around final-component
`NOFOLLOW_LINKS` reads. After semantic validation and approval it repeats the
whole discovery and compares the sorted entry fingerprint, including name,
type, file key, size, owner, permissions, link count, modification time, and
change time. An ordinary concurrent change makes the load non-publishable.

The traversal is still path based. It does not claim descriptor-relative
protection from every intermediate-component symlink swap or restored ABA state
that a concurrent same-UID/root writer can create. That writer is outside the
current local threat model, so pack changes require Paper to be stopped. Online
reload or a broader local-adversary model first requires
`SecureDirectoryStream` descriptor-relative traversal.

The optional manifest status is exactly `example` or `draft`. Either is an
unconditional non-effective state and cannot be overridden by approval. A
missing status means only that the manifest may proceed to compatibility and
approval checks. Pack content has no approval or console-policy override.

Plugin requirements are matched against one Paper-owned inventory snapshot.
The v1 range grammar uses only explicit `=`, `>`, `>=`, `<`, and `<=`
comparisons over one to three numeric components. Comparison is numeric and
deterministic. Missing, disabled, ambiguous, non-numeric, or mismatched plugins
disable the manifest rather than falling back to lexical or vendor-specific
version behavior.

The inventory snapshot is not a permanent execution fact. A future executor
must repeat current registry/generation availability and every required
plugin's enabled/version check at proposal creation and immediately before
final execution.

Paper converts the closed typed manifest to RFC 8785 canonical JSON and hashes
its UTF-8 bytes with SHA-256. Owner approval is an exact lookup port over the
capability ID, positive integer manifest version, and lowercase content hash.
Changing any hashed field invalidates the old approval. This approval is
necessary for registry membership and is not execution authorization.
Production implements the lookup from the strict, bounded, duplicate-free
`capabilities.approvals` configuration snapshot.

Before that hash is created, every `number` minimum and maximum must round-trip
from its normalized decimal through IEEE-754 binary64 and JCS serialization to
the same decimal. Negative zero becomes zero. Values such as
`0.10000000000000001` and `9007199254740993` are rejected so distinct source
numbers cannot collapse to one approved JCS hash.

Risk, permission, confirmation, maximum-block, console-source, and reversal
rules are validated before publication. Console source is default-deny.
Reversal targets must be separately available and cannot be missing,
self-referential, or cyclic. The target must have the same command source,
effect category, scope, and normalized plugin requirements. Effective records
map risk, permission, confirmation, and block limits into typed Phase 8 policy
metadata but contain no executor.

A complete candidate load builds an immutable map. Registry preview reports
`added`, `removed`, `changed`, and `unchanged` IDs. Publication atomically swaps
the entire map and advances its generation. Incomplete discovery cannot expose
effective entries, and a global inventory, approval-source, or hash failure is
also non-publishable. An ordinary per-manifest rejection is retained as a
disabled draft in the complete evaluated replacement, allowing independent
valid entries without publishing a partial traversal.

Publication uses compare-and-set against the exact preview base. If the optional
root cannot be inspected, production retains the prior generation. Fixed events
may expose validated diff IDs, generation/status, stable diagnostic counts, and
an otherwise valid candidate's exact ID/version/hash approval tuple. They do
not expose descriptions, arguments, templates, paths, or raw exceptions.

Only a `PUBLISHED` event labels the applied diff `added`, `removed`, `changed`,
and `unchanged`. `STALE` and `REJECTED` label the unapplied preview
`proposed_added`, `proposed_removed`, `proposed_changed`, and
`proposed_unchanged` while retaining the active snapshot generation in the
event. Global failures use separate
`capability_catalog_diagnostic` code events; `capability_manifest_disabled`
counts only draft-manifest diagnostics and does not duplicate the global error.

Production performs desired-state and private proposal-audit path safety checks
before catalog publication, then authenticates Runtime afterward. A failed
handshake may therefore leave a new inert metadata generation without making
startup or recovery successful. Metadata publication is not rolled back as an
executed Minecraft operation.

Argument compilation uses a closed type set and requires every descriptor to be
required. A call must contain every declared name and no undeclared name. Each
codec validates JSON type, range, grammar, and canonical rendering. The fixed
template is printable ASCII, has an exact command root, and is bounded to 1024
characters. Placeholders occupy complete tokens, cannot change literals, and
the rendered result is bounded and root-checked again.

The generic command preflight is parse only. It removes one trusted leading
slash, resolves the exact dispatcher root, requires complete Brigadier reader
consumption, context nodes, and a resolved command, and never invokes dispatcher
execution or Bukkit command dispatch.

A Paper Brigadier root for a third-party Bukkit command may only be a top-level
compatibility wrapper. Its presence does not prove that the target plugin
exposed its real parser, consumed all target arguments, or avoided parse-time
side effects. Therefore a production target also requires a locked,
target-specific parser with demonstrated behavior or a typed plugin/Paper API
adapter. A Bukkit dispatch return value is not preflight evidence.

Effective registry entries contain manifest and identity data but no invocation
operation. Phase 9 exposes no generic `dispatchCommand`, console executor,
pack-backed Runtime tool, or Capability proposal-creation route. Unknown
commands remain Proposal Only and can yield only non-executable draft material.

Before the first production write adapter is registered, the Phase 8 proposal
path must be split. Durable audit persistence and `force(true)` run on an I/O
worker. Control then returns to the Paper primary thread for the last live
request/player/permission/epoch/generation checks, the `EXECUTING` transition,
and Bukkit mutation. No adapter may block the primary thread on durable storage.

## Consequences

- Model output cannot select command text outside trusted template literals and
  typed argument positions.
- Missing and undeclared arguments, unsafe tokens, root changes, and incomplete
  Brigadier parses are rejected before any future execution adapter.
- A refresh observes plugin version and manifest content changes and disables
  incompatible or unapproved candidates. A future executor must still repeat
  live plugin and catalog checks because an older immutable snapshot can outlive
  a plugin event.
- Example, draft, console-source, unknown-command, unsafe-parser, and incomplete
  reload cases remain non-executable by construction.
- Operators can review a deterministic registry diff without exposing a
  partially loaded catalog or changing the meaning of the current generation.
- An unreadable optional root cannot silently erase the prior effective
  generation, and a stale preview cannot overwrite a newer publication.
- Ordinary concurrent filesystem changes are detected, while same-UID/root
  path-race protection remains an explicit prerequisite for online reload.
- IEEE-754/JCS decimal collisions cannot obtain an approval identity.
- Registry effectiveness and Brigadier parse success are intentionally weaker
  claims than execution eligibility. Each real target still pays the cost of a
  reviewed adapter and end-to-end proposal integration.
- The repository can validate and package example Capability data without
  claiming that a third-party command, proposal creation, or Minecraft write is
  reachable.
- The first write adapter remains blocked on the durable-audit worker/main-thread
  bridge identified by ADR 0006.
