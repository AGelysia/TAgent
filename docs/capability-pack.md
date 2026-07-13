# Capability Packs

## Purpose and current status

A Capability Pack is declarative data that maps one known, version-constrained
server capability to a typed tool. It is not a plugin system and cannot contain
Java, scripts, expressions, or arbitrary command text.

Phase 1 defines and validates `capability.schema.json`, fixtures, and this
format. Phase 8 supplies the Paper-owned proposal authorization boundary that a
future write capability must use, but its production write catalog is empty.
Pack discovery, approval, hot reload, model exposure, command parsing, and
execution remain Phase 9 work.

Unknown commands are not executable proposals. They may produce a
non-executable Capability draft for an owner to review in a future phase.

## Directory layout

Phase 1 validates one capability manifest per file. A minimal planned layout is:

```text
capability-packs/
  vanilla/
    waypoint.create.yml
  worldedit/
    worldedit.replace.yml
```

An installed server uses a configured Paper-owned directory. Paths are resolved
under that root, symlink escapes are rejected, and files are subject to count
and byte limits. The model never receives a file path and has no file-read tool.

Each document currently matches `protocol/schemas/capability.schema.json`.
Grouping metadata, publisher signatures, and a pack-level manifest are future
format additions and are not implied by the Phase 1 schema. Each capability is
validated independently so one invalid optional directory can be disabled
without changing the core tool catalog.

## Required capability fields

Every capability declares:

```text
id and format version
description
plugin and version requirements
command execution source, command root, and fixed template
closed argument definitions
effect category, scope, and maximum block count
minimum permission policy
whether confirmation is required
reversibility declaration
```

IDs use a lowercase dotted namespace and are stable across description changes.
A change to arguments, effects, permissions, or execution semantics requires a
new capability version and content hash.

## Example

The following is illustrative. It is not enabled by the current repository.

```yaml
id: worldedit.replace
version: 1
description: Replace blocks in a previously validated selection

requirements:
  plugins:
    - name: WorldEdit
      version: ">=7.3 <8"

execution:
  type: command
  source: player
  commandRoot: replace
  template: "/replace {from} {to}"

arguments:
  from:
    type: minecraft:block-pattern
    description: Block pattern to replace
    required: true
  to:
    type: minecraft:block-pattern
    description: Replacement block pattern
    required: true

effects:
  category: WRITE_WORLD
  scope: frozen_selection
  maximumBlocks: 5000

permissions:
  minimum: OP

confirmation:
  required: true

reversibility:
  type: capability
  capability: worldedit.undo
```

The example's selection must be frozen as coordinates and current-state hash by
Paper. Dispatching a third-party command against the player's mutable current
selection would not satisfy that requirement.

## Argument types

The Phase 1 schema has this closed argument type set:

- `string`, `integer`, `number`, `boolean`, and closed `enum`.
- `minecraft:block-pattern` and `minecraft:item`.
- `minecraft:player`, `minecraft:dimension`, and
  `minecraft:coordinates`.

Each type defines JSON representation, length/range limits, canonical form, and
renderer behavior. Arguments not declared by the manifest are rejected.
The schema bounds declarative definitions; a later semantic parser must also
reject control characters, line breaks, and ambiguous Unicode in actual tool
arguments where the target grammar does not define safe normalization.

## Load and approval pipeline

Paper is the loader and authority:

```text
discover under configured root
  -> enforce file and path limits
  -> parse without polymorphic object construction
  -> JSON Schema validation
  -> semantic validation
  -> plugin name and exact version-range match
  -> validate typed adapters and argument parsers
  -> calculate canonical content hash
  -> compare with owner approval
  -> build an immutable effective registry
  -> atomically publish a new catalog generation
```

Runtime sees only Paper's effective registry. It does not independently load
pack files. Each request and tool call binds the catalog generation, so a reload
cannot reinterpret an in-flight call under new rules.

External packs default to disabled until an owner approves their ID, version,
and hash. Bundled packs still pass the same schema and semantic validation. An
invalid pack reports a stable diagnostic; it does not partially register tools.

## Command-backed execution

Command execution is the less preferred capability kind. Typed Paper or plugin
API adapters are preferred because they provide an actual data model and can
freeze execution context.

For a command-backed capability:

1. The template is trusted pack data and contains only literals and declared
   placeholders.
2. Each argument is parsed and rendered by its semantic type, not generic string
   substitution.
3. The rendered command contains no newline and cannot add tokens outside the
   template grammar.
4. The command source is the requesting player. Paper never temporarily grants
   OP and console source is disabled by default.
5. Paper verifies current OP and permission immediately before execution.
6. The locked Paper/plugin combination must provide a complete parse without
   side effects before the proposal is offered.

Third-party Bukkit commands do not universally expose reliable Brigadier parse
semantics. Phase 9 therefore requires a compatibility spike. If complete
preflight parsing cannot be demonstrated, command-backed execution remains
disabled for that plugin and a typed adapter is required. `dispatchCommand`
returning a boolean is not proof that preflight validation happened.

## Risk and confirmation

Capabilities use these effect categories:

```text
READ
WRITE_TEMPORARY
WRITE_WORLD
WRITE_PLAYER
SERVER_ADMIN
```

The pack can make a policy stricter, never weaker than Paper's local policy.
World and player writes always require current live OP status and their typed
permission. An Owner-only local policy adds configured Owner UUID membership;
it never lets an Owner replace OP. Server administration is Owner-only and
requires its separate permission. Risky operations use Paper-owned,
server-expiring, single-use proposals and execute frozen arguments only after a
second authorization check.

Phase 8 implements that generic typed proposal boundary, its RFC 8785
domain-separated argument hash, fixed Adventure response actions, Offline/quit
invalidation, and redacted persistent audit events. It does not load this
manifest, publish a capability tool, or provide a production proposal-creation
route. A Phase 9 loader must supply a reviewed typed decoder/validator/executor;
it cannot point the proposal service at generic command dispatch.

A reversibility declaration is informational until the referenced undo
capability is independently validated and available. It does not justify a
larger default limit or skip confirmation.

## Testing requirements for the execution phase

- Valid and invalid schema fixtures in Java and TypeScript.
- Duplicate IDs, version mismatch, path traversal, oversized file, and symlink
  rejection.
- Atomic registry reload and catalog-generation binding.
- Argument boundary and property-based tests for every semantic type.
- Injection cases including newline, control characters, quotes, separators,
  greedy arguments, and unexpected Unicode normalization.
- Plugin absent, plugin version mismatch, permission lost, OP lost, Offline, and
  expired proposal cases.
- Proof that parsing has no side effect and that an unknown command remains a
  non-executable draft.

Phase 8 tests the proposal permission and single-use chain independently of a
capability. None of the pack discovery, approval, parsing, or execution tests
above is claimed until Phase 9 implements the loader and a reviewed adapter.
