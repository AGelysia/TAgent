# Security Model

## Scope

This is the normative security design for the staged implementation. Phase 0
and Phase 1 provide scaffolding and schemas. Phase 2 implements local Runtime
configuration and readiness controls. Phase 3 adds Paper-side strict startup
checks, authenticated hello handling, initial non-executable core descriptors, and a
conditional command-registration boundary. Phase 4 adds persistent Offline
state, epoch-based admission, explicit cleanup ports, and authorized recovery.
Phase 5 adds bounded private model requests, literal text replies, rate limits,
timeout, and cancellation. Phase 6 adds Runtime-owned sessions, owner-filtered
resume, bounded model context, and explicit one-shot modules. Phase 7 enables
only six fixed typed read tools through a bounded, doubly validated loop. Phase
8 adds the Paper-owned proposal, confirmation, dynamic authorization, and
redacted audit boundary while deliberately registering no production write
tool or proposal transport handler. Phase 9 adds strict Capability Pack
discovery, deterministic compatibility checks, exact content approval,
required-only typed rendering, parse-only command preflight, and atomic
registry generations while retaining that empty production execution surface.

The governing rule is:

```text
Describe capabilities openly; never expose arbitrary execution.
```

## Trust boundary

The following inputs are untrusted:

- Player text and any player-selected coordinates.
- Books, signs, chat, server documents, and retrieved document content.
- Model output and every Runtime-originated tool call.
- Fabric client packets, capability claims, acknowledgements, and selections.
- Litematica state, preview acknowledgements, and material counts.
- External Capability Packs until explicitly approved and validated.

Paper is the final execution boundary. A Runtime check, model decision, client
preview, or player click cannot replace Paper-side validation.

## Non-negotiable invariants

1. There is no generic console, shell, script, reflection, filesystem-write, or
   unrestricted HTTP tool.
2. A tool must exist in Paper's current effective catalog and have a closed
   argument schema.
3. Every execution is bound to a live Paper-originated request, server ID,
   player UUID, session, module, and catalog generation.
4. Paper checks the player's current permissions at execution time.
5. `WRITE_WORLD` and `WRITE_PLAYER` require the player to be OP even if a
   permission plugin grants a similarly named node.
6. Risky operations use a server-owned, expiring, single-use proposal with
   frozen canonical arguments.
7. Confirmation executes the frozen operation directly. It never asks the
   model to recreate arguments.
8. Client data cannot raise risk limits, permissions, or trust.
9. Paper revalidates bounds, current region state, block count, and allowed
   BlockStates immediately before a world change.
10. Offline gating applies at both command dispatch and tool execution.
11. Capability registry membership is metadata, not invocation authority; a
    reviewed adapter and the complete proposal chain remain mandatory.

The effective permission is the intersection of local policy, current player
permission, module allowlist, tool policy, live request context, and proposal
state. No term in that intersection can grant what another term denies.

## Authentication and replay defense

The Runtime transport is local-only and binds to `127.0.0.1`. Runtime and Paper
use a server token stored outside committed configuration. The token is not
sent to the Fabric client.

Protocol 1.0 defines an HMAC-SHA-256 challenge/proof handshake. Every envelope
also carries a UUID `messageId`, UUID `requestId`, timestamp, and unpredictable
nonce. The schema validates their shape. Paper and Runtime apply these
behavioral controls before dispatch:

- Verify the HMAC proof without logging secret material.
- Enforce a small clock-skew window.
- Reject a handshake or application nonce/message ID already present in the
  bounded replay cache.
- Bind the Runtime reply to Paper's request ID, challenge, server ID, selected
  protocol, and expected component direction.
- Bound message size before parsing, require strict UTF-8 and duplicate-key
  rejection, and validate closed fields afterward.

After authentication, Phase 8 still permits `agent.request`, `agent.cancel`,
`session.resume`, and `tool.result` from Paper and `agent.complete`,
`agent.error`, `session.resumed`, and `tool.call` from Runtime. Each message is bound to the active
connection, server ID, request ID, actual player UUID, expected session
relationship, and issuing Offline epoch. Resume lookup uses server ID and player
UUID in the same repository query; it does not reveal whether a rejected ID
belongs to another owner. Tool traffic additionally binds the unique call ID,
module, tool, and zero-based sequence. The proposal payload schemas are shared
contracts, but every proposal/view transport type remains rejected until a
direction-specific handler is explicitly wired.

Loopback binding limits remote exposure but does not protect against another
local process. Token file permissions, log redaction, and host account security
remain required. If later threat analysis requires message integrity after the
handshake, add a canonical-envelope MAC and monotonic connection sequence as a
new protocol version rather than changing 1.0 implicitly.

## Initial command gate

Phase 3 registers `/agent` only after every core check and authenticated Runtime
hello succeeds. Registration returns to the Paper primary thread and uses the
public command map API. Both the bare and namespaced labels are checked before
registration and verified by command-object identity afterward. A conflict,
false registration result, exception, or failed identity postcondition leaves
the command absent.

Failure and disable cleanup remove only mappings whose value is the exact
candidate or registered command instance. This prevents cleanup from deleting a
label another plugin owns. Static `paper-plugin.yml` command declaration,
permanently registered not-ready commands, server-thread waits, reflection, NMS,
and internal command APIs are prohibited. The complete decision and
real-server verification matrix are in
[ADR 0001](adr/0001-phase3-conditional-command-registration.md).

If initial self-check fails, there is no authorized command surface to recover
through. The operator fixes the external cause and restarts. Phase 4 `/agent on`
is reachable only after successful initial registration and a later Offline
transition.

## Offline behavior

Manual Offline is persisted by Paper. The strict state file stores only desired
mode, not transient health or failure state. Runtime loss therefore closes
admission without silently converting a temporary failure into a persistent
manual preference. Existing malformed or unsafe state fails closed rather than
defaulting to enabled.

The command entry point checks state
before dispatching every subcommand. While Offline, every command other than
`/agent on` and `/agent off` returns exactly:

```text
AI offline
```

The same epoch permit must be revalidated immediately before any Paper tool
execution. On an
`off` transition Paper rejects new requests, invalidates proposals, cancels
queued work, ignores late Runtime calls, and clears transient client transfers.
Cancellation is cooperative: a world mutation already in progress needs an
operation-specific consistency and recovery policy and cannot be assumed to
vanish atomically.

Authorization for `on` and `off` remains mandatory while Offline. The command
gate exception makes those subcommands reachable; it does not make them public.
The local console and configured Owner UUIDs are authorized. Ordinary OPs are
authorized only when `allow-op-toggle` is true and the dedicated permission is
present. Other sender types cannot acquire toggle authority.

This Offline command behavior is implemented in Phase 4. It does not weaken the Phase 3
rule that an initially failed and therefore unregistered agent exposes no
command at all.

## Proposal integrity

Paper creates proposals only after tool, schema, risk, permission, and live
request-context validation. Proposal IDs are opaque and server-owned. The Phase
8 domain record stores:

- Server, request, session, and player identity.
- Tool ID and effective catalog generation.
- Detached RFC 8785 canonical arguments and a domain-separated SHA-256 argument
  hash.
- Risk, expiry, and single-use status.
- Future build tools must additionally bind an immutable artifact ID,
  base-region hash, and change-set hash.

Arguments use RFC 8785 canonical JSON and bounded depth, node count, and UTF-8
size. The digest input is the UTF-8 domain
`minecraft-agent/proposal-arguments/v1`, one zero byte, then the canonical JSON;
the result is lowercase SHA-256 hex. A shared golden covers RFC 8785 number and
property ordering behavior. Paper freezes a detached value and recomputes its
hash at confirmation, so a caller cannot rely on a mutable JSON object or a
library's incidental serialization.

Production expiry is server-fixed at 60 seconds. The reusable service rejects a
non-positive TTL or one above ten minutes, and neither model arguments nor a
wire timestamp can extend it. The in-memory repository atomically transitions
one matching entry from `PENDING` to `CLAIMED`, so concurrent clicks cannot
execute it twice. After claiming it, Paper repeats the Online epoch, server,
request, session, actual UUID, live player, current permission and policy,
request context, tool/catalog generation, expiry, and frozen hash checks. A
future build executor must additionally repeat bounds, size, region hash,
change-set hash, and allowed-state checks.

Risk authorization is deliberately asymmetric:

- `READ` never creates a proposal.
- `WRITE_TEMPORARY` requires its typed tool permission and current online
  player; the production permission defaults to false.
- `WRITE_WORLD` and `WRITE_PLAYER` require current online status, live OP, and
  the matching tool permission. If local policy says `OWNER`, configured Owner
  UUID membership is an additional requirement, never an OP substitute.
- `SERVER_ADMIN` requires current online status, configured Owner UUID, and the
  typed permission; its production permission defaults to false.

The policy supplier is read at creation and again at confirmation. Removing OP,
Owner membership, a permission, the tool, the catalog generation, or the live
request context makes the old proposal terminally unavailable. Confirmation
invokes only the registered typed executor with the frozen arguments and never
returns to the model.

Vanilla clickable confirmation uses fixed namespaced server commands:
`/minecraftagent:agent confirm <proposal-id>` and
`/minecraftagent:agent reject <proposal-id>`. Paper builds both Adventure click
events from the opaque UUID; model text cannot provide a command, and proposal
IDs are not tab-completed. The command rederives the actual `Player` UUID and
requires `minecraftagent.proposal.respond`. A structured client action may
later carry only an action kind and proposal ID; it cannot supply command text.

Player quit invalidates that player's pending or claimed entries. `/agent off`,
Runtime loss, and plugin disable rotate the epoch and invalidate all entries.
An entry that has completed final admission is first moved to `EXECUTING`;
cleanup cannot rewrite that state while its typed side effect reaches a
terminal audit result. Paper-thread serialization defines the ordering between
final admission and player/Offline lifecycle events.
Audit events append and force to `<state>/audit/security-audit-v1.jsonl` under a
`0700` directory and `0600` file. The fixed JSONL record contains trusted IDs,
timestamp, tool, risk, catalog generation, event type, and a stable outcome
code. It has no argument, summary, prompt, credential, rendered command,
exception, or other free-text field. Audit setup or pre-execution append failure
fails closed.

Phase 8's production write catalog is empty and the synchronous proposal domain
has no production `create` caller. These checks are an execution-admission
foundation, not evidence that a Minecraft write is currently reachable.
The first production write adapter must split audit persistence onto the worker
and return to the primary thread for the last live checks and mutation; it must
not block the Paper thread on `force(true)`.

## Core read-tool boundary

`CoreToolRuntime` validates exactly six required descriptors:
`player.context.read`, `player.held_item.read`, `server.info.read`,
`server.plugins.list`, `server.recipe.lookup`, and `server.recipe.uses`. Every
descriptor must use read access, declare a closed schema, and set
`executionCapable=true` in the current Phase 7 registry.

These records do not create a generic invocation API. Runtime publishes only
the current module intersection and validates the full shared argument schema.
Paper repeats catalog, schema, module, permission, request, session, player,
sequence, connection, and epoch checks before its fixed adapter. Rejected calls
use Paper policy provenance; successful server snapshots use fixed authoritative
provenance. Cancellation or an Offline/connection generation change cancels or
drops pending work and late results never reenter model context.

All Bukkit access is scheduled on the primary thread without making the
transport thread wait. Recipe scans use bounded per-tick slices and preserve
typed recipe, layout, `IngredientChoice`, `ItemStack`, processing, and remaining
item data. Byte and structural-token budgets replace oversized successful
results with a typed failure.

## Capability safety

Capability Packs are data, not plugins. Loading a pack must never load Java,
scripts, or arbitrary classes. Unknown server commands produce a non-executable
Capability draft, not an executable proposal.

Phase 9 traversal is bounded by entry, file, byte, depth, alias, and YAML-depth
limits. It rejects unsafe roots, escapes, links, hard links, non-regular
manifest files, unsafe write modes, invalid UTF-8, and incomplete discovery.
Only `.json`, `.yml`, and `.yaml` names are installed manifests. Each passes
through SnakeYAML `SafeConstructor` and then a closed manual typed parser.
Unknown keys, unexpected nodes, invalid values, duplicate IDs, and inconsistent
policy disable the affected manifest.

Production first requires the plugin data ancestor to be single-owner `0700`
and proves Paper can write it. The loader inherits that composition boundary,
uses the capability root owner as its authority, and requires descendants to
match. It does not independently resolve the process effective UID. A future
executor or reuse outside this startup composition must pass an explicit
trusted owner/ancestor into the loader rather than infer authority from the
candidate root.

Relative path components are closed and bounded. Final entries use
`NOFOLLOW_LINKS`, regular files are checked around each read, directories are
checked around enumeration, and parsing/approval is followed by a complete
second discovery whose sorted entry fingerprint must equal the first. Ordinary
concurrent modification is a non-publishable `ROOT_CHANGED` failure.

This does not extend the local threat model to a malicious writer running as
the Paper OS UID or root. Path-based checks cannot prove the absence of every
intermediate-component symlink race or deliberately restored ABA state.
Capability maintenance must occur while Paper is stopped. Before execution,
online reload, or same-UID writers become in-scope adversaries, the loader must
take the trusted owner explicitly and use `SecureDirectoryStream` with
descriptor-relative traversal instead of path re-resolution.

`status: example` and `status: draft` are permanent deny markers. A status-free
manifest remains disabled until all required plugins match a deterministic
numeric version range and a Paper-owned approval port matches its complete
capability ID, positive integer manifest version, and lowercase SHA-256 of the
RFC 8785 canonical typed content. A pack cannot declare its own approval. Any
content change invalidates the old hash approval. Production obtains the exact
lookup set from the strict, bounded `capabilities.approvals` configuration.

Manifest `number` minima and maxima are validated before hashing. Their
normalized decimal must survive conversion to IEEE-754 binary64, JCS number
serialization, and decimal parsing without changing value. Negative zero is
normalized to zero; `0.10000000000000001` and `9007199254740993` are rejected.
This prevents distinct source decimals from receiving one approval identity
after JCS rounding.

Console source is default-deny and no manifest field can override that policy.
Risk, permission, confirmation, maximum-block, and reversal declarations are
checked as an intersection: pack data can narrow authority but cannot raise it.
A reversal target is separately validated and approved; missing, unavailable,
self-referential, or cyclic targets are denied. It must also match the source
capability's command source, effect category, scope, and normalized plugin
requirements. Effective entries map risk, permission, confirmation, and block
limits into typed Phase 8 policy metadata without acquiring an executor.

The effective registry is an immutable snapshot. A complete candidate produces
a bounded `added`, `removed`, `changed`, and `unchanged` diff. Publication is one
atomic snapshot and generation change; incomplete discovery or a global
authority failure cannot publish a partial traversal. Ordinary per-manifest
rejection is retained as a disabled draft in the complete evaluated snapshot;
it does not hide independent valid entries. Each future request and proposal
must bind the exact generation that defined it.

An unavailable optional root retains the prior snapshot rather than publishing
an empty interpretation of unreadable state. Catalog logs allow only validated
capability IDs, generation/status, fixed diff classes, stable diagnostic counts,
and the exact version/hash tuple needed for approval. Manifest descriptions,
arguments, templates, paths, parser exceptions, and other raw values are not
logged.

Global load failures use a separate fixed `capability_catalog_diagnostic` code
event. `capability_manifest_disabled` counts only diagnostics attached to draft
manifests, so one global condition is not presented twice as both catalog and
manifest failure.

Only `PUBLISHED` catalog events use the unprefixed `added`, `removed`,
`changed`, and `unchanged` fields. `STALE` and `REJECTED` events label the
unapplied preview as `proposed_added`, `proposed_removed`, `proposed_changed`,
and `proposed_unchanged`, while their generation field remains the active
generation. Local desired-state and audit-path safety checks occur before
publication; Runtime authentication occurs afterward. A failed handshake may
therefore update inert metadata but cannot register the command or make a
capability executable.

Typed argument compilation is closed and required-only. Every declared argument
must be present and an undeclared argument is rejected. Each semantic codec
renders only its bounded canonical grammar. The fixed template is printable
ASCII, begins with an exact trusted root, and is at most 1024 characters;
placeholders cannot modify literal text or introduce a separator, quote,
newline, control character, or extra command structure. The rendered command is
again bounded and root-checked.

Brigadier preflight calls parse only, requires the exact registered root and
full input consumption with a resolved command, and never calls execute or
Bukkit dispatch. A third-party Bukkit command may appear under a Paper
top-level Brigadier wrapper without exposing the plugin's real parser. Such a
node is not proof of complete or side-effect-free validation. Execution remains
disabled unless the locked target provides a separately reviewed parser or a
typed API adapter.

Load-time plugin compatibility can become stale after a plugin disable, enable,
or version change. Every future executor must repeat current catalog/generation
availability and each required plugin's live enabled/version check at proposal
creation and immediately before final execution. An approved effective entry is
not a live authorization fact.

Phase 9 intentionally exposes no generic dispatch operation, no pack-backed
Runtime tool, and no Capability proposal-creation route. An effective record has
no executor. Before the first write adapter, Phase 8 audit persistence and
`force(true)` must run on a worker and return to the primary thread for the last
live checks, `EXECUTING` transition, and Bukkit mutation. A synchronous audit
call on the Paper thread or a simple render-and-dispatch path is prohibited.

## Client and view safety

Paper binds a client payload to the player represented by the actual network
connection. It ignores any UUID claimed inside the payload. The client never
receives the Runtime token or model key.

Structured views use fixed view types and schemas. They do not contain model
supplied click commands, texture paths, arbitrary components, or unrestricted
NBT. Item data uses a documented component allowlist and byte limits. Unknown
registry IDs render as explicit missing values.

Chunked payloads enforce chunk count, contiguous indices, encoded size,
decompressed size, per-chunk hash, whole-content hash, timeout, and one active
byte budget per connection. Duplicate, conflicting, incomplete, or oversized
transfers are discarded. A client ACK proves only that a packet path responded;
it does not prove that a player inspected a preview.

## Build safety

Natural-language intent is not a change set. A deterministic planner consumes a
normalized, versioned intent and an authoritative Paper snapshot. Paper stores
or retains the exact validated artifact referenced by a proposal.

Large multi-tick updates create a time-of-check/time-of-use problem. A single
base-region hash checked before a 100,000-block write is not atomic protection.
The first write implementation must use conservative limits, compare expected
old state before each change or bounded batch, stop on conflict, and define
rollback or partial-failure reporting. Block entity NBT remains rejected unless
a separate allowlisted schema is designed.

Litematica is a preview integration only. Its material list, local world view,
and success ACK are never used for authorization or server-side size checks.

## Data and privacy

Paper security records and Runtime conversation records use separate stores and
separate migrations. Neither process opens the other's SQLite file. Audit events
are written by Paper before or with the authoritative state transition; a
Runtime copy is informational.

Logs redact API keys, server tokens, HMAC proofs, sensitive capability
arguments, and message content by default. Session access always includes both
server ID and player UUID. Session identifiers are never offered through command
completion.

Phase 6 persists prompts and completions only when
`privacy.storeConversations` is enabled. Each successful exchange is written
atomically to Runtime's private SQLite store; failed, timed-out, or cancelled
work leaves no partial turn. Session lookup always uses the authenticated server
ID and actual player UUID in the query, and absent or foreign sessions share one
safe response. Disabling conversation storage writes no prompt or completion and
disables resume. In both modes, the OpenAI request sets `store: false`, Runtime
logs only fixed event/error metadata, and Paper renders the validated fallback as
literal text rather than parsing formatting or click events. Server operators
must separately disable or protect Paper's global
player-command logging if questions are sensitive: the server may log the full
`/agent say <message>` command before plugin code can selectively redact it.
Client command history is likewise outside the plugin's control.

Document retrieval is confined to configured roots, rejects traversal and
symlink escapes, and marks document text as untrusted content in model context.
Live unrestricted web access is not part of the design.

## Runtime startup controls

Phase 2 treats the local configuration as structured but potentially malformed
input. YAML is size/depth bounded, aliases and duplicate keys are rejected, and
environment references are expanded only after parsing and only when the full
scalar is `${NAME}`. Unknown key text and received values never enter diagnostics.
Group/world-writable configuration is rejected; broader read permissions and
inline secrets separately produce warnings as required by the product plan, but
the two may not be combined. Operators should use environment injection and mode
`0600` configuration.

Runtime data and log paths are relative to the canonical configuration directory
and must use private subdirectories. Path traversal, symlinks, hard-linked
databases, unsafe ownership/modes, and state directly in the configuration root
are rejected. Created directories are `0700`; SQLite and probe files are `0600`.

Startup logs serialize only fixed event names, stable codes, stages, and known
field paths. Provider exceptions and configuration values are discarded. The
loopback `/health` response is a cached readiness snapshot with no model name,
path, secret, raw error, or repeated external check. Phase 6's production
provider performs a bounded model lookup during readiness and a bounded
Responses request only for admitted player work.

## Current enforcement gap

At the Phase 9 boundary, Paper-side startup, authenticated application channel,
conditional registration, persistent Offline state, epoch gate, private
conversation, request cancellation, Runtime-owned sessions, owner-filtered
resume, one-shot modules, bounded context, Runtime provider limits, and six
typed read-only tools exist. Paper also has a proposal repository, response
command boundary, dynamic authorizer, Offline/quit invalidation, and private
persistent audit sink. It now also has a bounded Capability Pack loader,
deterministic version and approval checks, immutable registry generations,
typed command rendering, and parse-only Brigadier preflight.

The production proposal catalog is still empty, the synchronous service has no
production creation path, and no capability record has an executor. Proposal
WebSocket handlers, write-operation and client-state producers, durable usage
accounting, trusted third-party command adapters, client payload handling, and
world mutation remain absent. Protocol schemas and an effective capability
record remain necessary contracts, not evidence that execution is reachable.
