# Capability Packs

## Purpose and current status

A Capability Pack is declarative data that describes one known,
version-constrained server capability with closed arguments, risk, permission,
and confirmation metadata. It is not a plugin system and cannot contain Java,
scripts, expressions, or arbitrary command text.

Phase 9 implements the fail-closed Paper-side loader, typed argument codecs,
fixed command-template compiler, parse-only Brigadier preflight boundary, and
immutable effective registry. This is validation and publication
infrastructure, not a production command executor. The Runtime does not load
pack files, no generic dispatch operation exists, and no production route turns
a Capability into a Phase 8 proposal.

Unknown commands remain Proposal Only. They may be analyzed or represented as
non-executable draft material for owner review, but they cannot become an
executable proposal or bypass the manifest and approval pipeline.

## Installed directory

Paper resolves `capabilities.directory` below its plugin data directory. The
loader performs a bounded traversal under that root and rejects unsafe root or
entry state. Its default limits bound directory entries, manifest count,
individual and total bytes, nesting depth, YAML aliases, and YAML depth.
Symlinks, hard-linked files, path escapes, non-regular files, unsafe write
modes, invalid UTF-8, and incomplete discovery fail closed with stable
value-free diagnostics.

Every relative path component must match the bounded ASCII component grammar;
`.` and `..` are rejected. Manifest reads compare regular-file type, file key,
size, owner, mode, and link count before and after a final-component
`NOFOLLOW_LINKS` read. After parsing, compatibility, hashing, and approval, the
loader repeats the complete bounded discovery and compares a sorted fingerprint
of entry name, type, file key, size, owner, permissions, link count, modification
time, and change time. An ordinary concurrent change produces `ROOT_CHANGED`
and makes the candidate non-publishable.

In the production composition, this root is below the plugin data directory
that `StateDirectoryProbe` has already required to be single-owner `0700` and
writable by Paper. The loader then treats the capability root's owner as its
authority and requires discovered descendants to keep that owner and reject
group/world write access. The loader does not independently compare that owner
with the process effective UID; embedding it outside this startup composition
requires an equivalent trusted-ancestor check.

This implementation still uses path-based traversal. Final-component
`NOFOLLOW_LINKS` checks do not eliminate every intermediate-component symlink
swap or a deliberately restored ABA state by a concurrent writer running as the
same OS UID or root. That writer is outside the current local threat model.
Operators must stop Paper before modifying the capability tree or approvals.
Before adding online reload, capability execution, or treating same-UID writers
as hostile, the authority owner must become an explicit loader input and
traversal must move to descriptor-relative `SecureDirectoryStream` operations.

Only case-insensitive `.json`, `.yml`, and `.yaml` names are treated as installed
manifests. Other regular files are ignored, although they still count toward
the bounded directory-entry walk. JSON follows the same SafeConstructor and
closed typed parse as YAML; it is not a separate permissive path.

YAML is constructed with SnakeYAML's `SafeConstructor`, then converted through
a closed manual typed parser. Unknown keys, missing keys, aliases, unexpected
node types, invalid values, duplicate IDs, and inconsistent policy disable the
affected manifest. A global discovery, inventory, approval-source, or
content-hash failure makes the whole load non-publishable; ordinary
per-manifest failures remain visible as disabled drafts in a complete evaluated
snapshot. Pack data never selects a class or executable implementation.

Runtime and the model never receive an installed file path and have no tool for
reading arbitrary pack files.

## Manifest status

The optional top-level `status` has exactly two values:

- `example` marks protocol and documentation material.
- `draft` marks owner-review material.

Both are permanent deny markers. A file with either status is never effective,
even when an approval record happens to contain the same ID, version, and hash.
An absent status only makes the manifest an approval candidate; it does not
self-approve the file.

Every repository example under `capability-packs/` uses `example` or `draft`.
Nothing in that directory is approved or executable. Its JSON files can be
discovered directly and remain denied by their status.

## Required fields

Every capability declares:

```text
id and positive integer format version
description
required plugin names and numeric version ranges
player or console command source, command root, and fixed template
closed argument definitions
effect category, scope, and maximum block count
minimum permission policy
whether confirmation is required
reversibility declaration
```

IDs use a stable lowercase dotted namespace. A change to arguments, effects,
permissions, requirements, or execution semantics changes the canonical
content hash and therefore invalidates the old approval. Operators should also
increment the positive integer version for a reviewed contract change.

The schema allows `source: console` so unsafe input has an explicit contract
and fixture, but Phase 9 Paper policy rejects it by default. A pack contains no
field that can override that local deny policy. Paper never temporarily grants
OP.

## Non-executable example

This manifest is documentation only because it declares `status: example`:

```yaml
id: example.server_version
version: 1
status: example
description: Display the server version to the requesting player

requirements:
  plugins: []

execution:
  type: command
  source: player
  commandRoot: version
  template: "/version"

arguments: {}

effects:
  category: READ
  scope: request
  maximumBlocks: null

permissions:
  minimum: ANY

confirmation:
  required: false

reversibility:
  type: none
```

An example becoming suitable for a server requires a separately reviewed
status-free manifest, exact plugin compatibility, an exact owner approval, and
a trusted execution adapter. Removing `status` alone is not sufficient.

## Plugin version matching

Plugin names are matched against a point-in-time Paper-owned inventory.
Duplicate or ambiguous installed names, a missing or disabled plugin, an
unparseable installed version, and a range mismatch disable the capability.

Version ranges use a deliberately small grammar: one to sixteen
space-separated comparisons using `=`, `>`, `>=`, `<`, or `<=` and numeric
versions with one to three dot-separated components. For example:

```text
>=2.20 <3
```

Comparison is explicit numeric component comparison, padded to three
components. It does not use lexical ordering or an implementation-dependent
SemVer library. Wildcards, prerelease labels, build metadata, implicit bare
versions, OR expressions, and leading-zero components are rejected. A plugin
that reports a non-numeric version cannot satisfy a v1 manifest.

## Argument types

The closed type set is:

- `string`, `integer`, `number`, `boolean`, and closed `enum`.
- `minecraft:block-pattern` and `minecraft:item`.
- `minecraft:player`, `minecraft:dimension`, and
  `minecraft:coordinates`.

Phase 9 execution codecs support required arguments only. A descriptor with
`required: false` is semantically invalid even though the shared JSON Schema
retains the boolean field. At render time every declared argument must be
present, every supplied argument must be declared, and the JSON value must
match its type and bounds.

Each codec emits a canonical command token. It rejects line breaks, controls,
whitespace-bearing generic strings, unsafe normalization, out-of-range numbers,
invalid resource locations, invalid player names, malformed block patterns,
and invalid coordinate combinations. Coordinates are the only type that may
intentionally render three fixed grammar tokens from one structured value.

Before an approval identity is hashed, every `number` descriptor minimum and
maximum must survive an exact decimal round trip through IEEE-754 binary64 and
JCS number serialization. The normalized input decimal, the binary64 value's
shortest decimal form, and the decimal parsed from the JCS output must compare
equal; negative zero normalizes to zero. Values such as
`0.10000000000000001` and `9007199254740993` are rejected before an identity
exists, preventing distinct manifest numbers from collapsing to one JCS hash.

## Fixed template and preflight

The trusted manifest template and command root are compiled before use:

1. The template is printable ASCII, begins with exactly `/<commandRoot>`, and
   is at most 1024 characters.
2. Quotes, backslashes, command separators, shell-style operators, repeated
   spaces, unmatched braces, duplicate placeholders, and undeclared or unused
   arguments are rejected.
3. A placeholder occupies a complete template token. Model output cannot add,
   remove, reorder, or edit fixed literal text.
4. Typed codecs render the closed argument object. The final command is bounded
   to 1024 characters and must retain the exact root.
5. The Brigadier boundary removes the one trusted leading slash and calls only
   `CommandDispatcher.parse`. It requires a known root, full reader
   consumption, a non-empty parse context, and a resolved command.

The preflight implementation never calls `execute` or Bukkit command dispatch.
A top-level Brigadier node exposed by Paper can be only a wrapper around a
third-party Bukkit command; it is not by itself proof that the target plugin
parsed every argument or that parsing was side-effect free. Each production
command target therefore still needs an explicitly reviewed, locked parser or
typed API adapter. Without that proof, the target remains disabled.

## Approval and registry publication

Paper evaluates a candidate in this order:

```text
bounded discovery and strict UTF-8
  -> SafeConstructor and closed typed parse
  -> manifest semantic and policy validation
  -> exact plugin inventory and numeric version-range match
  -> typed argument and fixed-template compilation
  -> exact IEEE-754/JCS decimal round-trip for number bounds
  -> RFC 8785 canonical typed manifest
  -> SHA-256 content hash
  -> exact owner approval lookup
  -> reversal graph validation
  -> complete immutable candidate registry
  -> atomic generation publication
```

An approval is a Paper-owned port keyed by the complete tuple
`(capability ID, manifest version, lowercase SHA-256)`. Pack content cannot
declare or weaken approval. Editing any hashed manifest field makes the old
approval miss. `example` and `draft` remain denied before approval lookup.

The production Paper composition supplies this port from the strict
`capabilities.approvals` configuration snapshot. The list is bounded to 128
exact, duplicate-free ID/version/hash records; absence means an empty approval
set. For an otherwise valid unapproved candidate, Paper logs only its validated
ID, positive version, and computed hash in a fixed approval-required event so an
operator can review the exact tuple.

The registry publishes immutable snapshots. A complete candidate can be
previewed as `added`, `removed`, `changed`, and `unchanged` IDs. Publication
atomically swaps the full snapshot and advances its generation; incomplete
discovery or a global authority failure cannot publish a partial traversal.
Per-manifest rejections are retained as disabled drafts while the complete
evaluated replacement is published. Requests and proposals must bind the exact
effective generation before execution is added.

The fixed catalog event exposes the publication status, generation, and the
four diff ID sets. A `PUBLISHED` event uses `added`, `removed`, `changed`, and
`unchanged`. A `STALE` or `REJECTED` event uses `proposed_added`,
`proposed_removed`, `proposed_changed`, and `proposed_unchanged`, while
`generation` identifies the still-active snapshot, so an unapplied preview
cannot be mistaken for the active catalog.
Draft-manifest failures are reduced to stable code/count records. Global load
diagnostics use one separate `capability_catalog_diagnostic` event per stable
code and are not double-counted as disabled manifests. When the optional root is
unavailable before loading, Paper emits `RETAINED` and keeps the current
generation rather than interpreting unreadable state as an empty replacement.

Catalog loading occurs only after the desired-state store and private proposal
audit path pass their local safety checks, but the result remains an unpublished
candidate. The coordinator publishes it only after the authenticated Runtime
handshake, application attachment, connection revalidation, and current-attempt
validation. A failed or stale attempt cannot replace the active catalog.

An effective registry entry currently contains validated and approved data but
no invocation operation. Registry membership is neither command-execution
authority nor proof that a required plugin is still enabled at the approved
version.

## Risk, permission, and reversal

The closed effects are `READ`, `WRITE_TEMPORARY`, `WRITE_WORLD`,
`WRITE_PLAYER`, and `SERVER_ADMIN`. The manifest declares a compatible minimum
permission and confirmation rule; Paper rejects combinations that would
understate risk. Local policy can make a capability stricter and can never be
weakened by pack data.

An effective record maps that category one-for-one to the Phase 8 proposal
`RiskLevel` and retains the declared permission minimum/node, confirmation flag,
and optional maximum block count as typed policy metadata. This mapping does not
attach an executor or create a proposal.

World and player writes still require current live OP status and the typed
permission at final authorization. An Owner-only local policy adds configured
Owner UUID membership and never substitutes for OP. Server administration is
Owner-only and requires its separate permission. Console source remains denied
by default regardless of declared risk.

A `reversibility.type: capability` reference is accepted only when the target
is separately valid and available and has the same command source, effect
category, scope, and normalized plugin requirements. Missing, unavailable,
incompatible, self-referential, or cyclic reversal graphs are disabled.
Reversibility does not reduce risk, raise limits, or skip confirmation.

## Production execution boundary

Phase 9 deliberately has no generic `dispatchCommand`, no generic console
executor, no Capability proposal-creation route, and no Runtime tool exposure
for pack commands. Unknown commands are Proposal Only. An approved registry
entry therefore cannot mutate Minecraft in the current package.

Before the first production write adapter is registered, all of the following
remain mandatory:

- A reviewed typed API adapter or target-specific parser with demonstrated
  side-effect-free complete validation.
- Binding to a live Paper request, player, session, module, connection, Online
  epoch, and exact capability generation.
- Rechecking current catalog availability and generation plus every required
  plugin's live enabled state and version at proposal creation and immediately
  before final execution. A load-time inventory snapshot or registry membership
  is insufficient.
- Phase 8 proposal creation with frozen canonical arguments, current risk and
  permission checks, expiry, and single-use confirmation.
- A split proposal path that performs durable audit persistence and
  `force(true)` on the worker, then returns to the Paper primary thread for the
  final live authorization, `EXECUTING` transition, and Bukkit mutation.

No adapter may block the primary thread on durable storage or treat a Bukkit
dispatch boolean as parse validation.

## Verification scope

Phase 9 focused tests cover strict manifest parsing, bounded unsafe filesystem
cases, pre/post fingerprint changes, deterministic versions, JCS number
collisions, approvals, reversal graphs, immutable registry diffs, required-only
typed arguments, injection attempts, fixed-template rendering, and parse-only
Brigadier behavior. Shared Java and TypeScript contract fixtures cover schema
and cross-manifest semantics.

The pinned Paper smoke also installs the bundled `server-version.example.json`
with mode `0600` after an initial missing-directory case. A restart publishes
`status=PUBLISHED generation=1`, reports `EXAMPLE_ONLY` and
`CAPABILITY_PACK_DISABLED`, and exposes no pack command or Agent execution
entry point.

These checks prove the fail-closed broker components. They do not claim a real
third-party command execution, a production proposal creation, or a connected
player confirmation flow.
