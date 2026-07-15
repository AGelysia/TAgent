# Security Policy

## Supported scope

Minecraft Agent is currently a preview-only assistant for Minecraft/Paper
`1.21.11`. Paper is the authoritative security boundary. The optional Fabric
client, Litematica, model output, server documentation, signs, player input, and
all client acknowledgements are untrusted inputs.

The production write catalog is empty. This release has no production proposal
creator, arbitrary command executor, world apply or rollback operation, Easy
Place integration, printer, automatic placement, shell, script, or unrestricted
file tool. A generated or loaded `.litematica` file is a local presentation
artifact and never authorizes a Minecraft state change.

The exact server and client compatibility policy is documented in
[`CLIENT-COMPATIBILITY.md`](CLIENT-COMPATIBILITY.md). Security fixes are applied
to the current development line. No support is implied for modified builds or
unlisted dependency combinations.

## Reporting a vulnerability

Do not disclose a suspected vulnerability in a public issue, discussion, chat,
server log, or Minecraft message. Use this GitHub repository's enabled
[`Report a vulnerability`](https://github.com/AGelysia/TAgent/security/advisories/new)
private security advisory form. Do not substitute a public channel if GitHub is
temporarily unavailable.

Include only the information needed to reproduce and assess the issue:

- affected commit, artifact version, and component;
- exact supported Minecraft/Paper/Fabric dependency versions;
- minimal reproduction steps and observed impact;
- whether authentication, permissions, Offline state, proposal state, storage,
  client payloads, or cost accounting are involved; and
- sanitized diagnostics that contain no API key, Runtime token, player UUID,
  prompt, completion, private document, local path, or world data.

Never attach a real provider key or Runtime server token. Rotate any credential
that may have been exposed before submitting the report. Maintainers will
coordinate validation and disclosure privately; this policy does not promise a
fixed response or release deadline.

## Deployment boundary

The intended deployment keeps the Runtime and Paper on the same trusted machine
or VM. Runtime transport is restricted to `127.0.0.1`; do not publish its port,
health endpoint, SQLite database, logs, or configuration directory. Paper and
Runtime authenticate with the same high-entropy
`MINECRAFT_AGENT_SERVER_TOKEN`. The provider key is available only to Runtime.
Neither secret belongs in Git, a JAR, `.env.example`, a Minecraft command, a
screen capture, or a support bundle.

For any networked test or deployment:

- keep `online-mode=true`, enable the whitelist, and whitelist only intended
  authenticated accounts;
- expose only the Minecraft port required by the client, preferably over a
  private LAN, VPN, or temporary SSH tunnel;
- restrict a public VM firewall to the tester's source address and remove the
  rule or port forward after testing;
- do not expose RCON, query, Runtime, database, or management ports; and
- use a separate test world and current backups. This preview-only release does
  not need write access to prove its client presentation features.

A VM with a public address is an Internet server even when it is described as a
local test VM. A whitelist limits Minecraft logins but does not replace a host
firewall, operating-system updates, SSH hardening, or provider credential
controls.

## Configuration and local data

Use Java 21, Node.js 22.16 or newer in the 22.x line, and the repository's locked
build inputs. Start from `agent-runtime/config.example.yml`; the current Runtime
format is `configVersion: 2`. Keep configurations and existing state private,
with directories mode `0700` and files mode `0600` on supported POSIX systems.
Do not bypass ownership, mode, symlink, hard-link, or path checks to recover from
a startup failure.

Conversation retention is controlled by `privacy.storeConversations` and
`privacy.retentionDays`. Logs must keep `logMessageContent: false`. Review the
configured model price and use limits before allowing players to submit requests.
The monthly value is a conservative reservation-based admission bound, not a
provider billing cap.

Build preview publication is disabled by default. Enable
`MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true` only for an isolated test using the
exact supported Fabric/Litematica tuple. Disable it again after testing until the
manual release lane in [`docs/phase13-manual-test.md`](docs/phase13-manual-test.md)
has recorded acceptable evidence.

## Authority rules

- Initial readiness must complete before Paper registers `/agent`. Do not work
  around a failed readiness gate by editing command maps or permissions.
- `/agent off` closes admission. While the Agent is not Online, every non-toggle
  command must return `AI offline`.
- Paper derives player identity from the live connection. A payload cannot
  choose a player UUID or increase a permission.
- Client capability claims select presentation formats only. Client ACK,
  selection, Litematica placement, Material List, and local files never confirm a
  proposal or authorize execution.
- Model output can call only the fixed typed tool catalog. It cannot create an
  arbitrary Bukkit command, shell command, script, URL fetch, or path.
- Status, doctor, capabilities, costs, reload, toggle, and proposal responses
  retain their independent Paper permissions and owner checks.
- Capability examples and drafts are non-effective. A manifest cannot approve
  itself, and no current Capability record contains an executor.

Do not weaken a schema, byte limit, timeout, permission, Offline gate, exact
version check, or final Paper validation to make an integration test pass.
