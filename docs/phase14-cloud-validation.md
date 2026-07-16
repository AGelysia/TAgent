# Phase 14 Cloud Validation

## Purpose and decision boundary

This is the maintainer checklist for the final `0.2.0` cloud validation. It
covers one isolated Paper deployment, real provider credentials, two physical
licensed Minecraft clients, and the complete Phase 13 three-configuration
rerun. It is not a production deployment guide and does not authorize a tag,
Release, public server, write catalog, or Minecraft mutation.

Run this checklist only from the copy made in an evidence directory outside the
Git worktree. Leave the repository template unchanged while testing. An item is
`PASS` only after direct observation. A missing path is never a pass. Except for
the explicitly optional live-timeout claim, any mandatory `FAIL`, fingerprint
mismatch, or unapproved blocker makes final acceptance impossible.

The historical Phase 13 acceptance record remains evidence for its own older
candidate only. Do not edit it or reuse its result for `0.2.0`.

## Evidence boundary and candidate preparation

- [ ] Start from the final clean candidate commit. Confirm
  `candidate.version=0.2.0`; a prerelease, `0.1.0`, or locally modified tree is
  not this candidate.
- [ ] On the trusted build host, create a new canonical evidence directory that
  is outside the repository and is neither a link nor a reused directory:

  ```bash
  ./scripts/final-validation.sh prepare /absolute/private/phase14-evidence
  ```

- [ ] Confirm `prepare` passed the canonical release check and created at least
  `tested-fingerprint.txt`, `release.SHA256SUMS`, `release-check/`, this copied
  checklist, and the copied final-record template. Keep directories `0700`,
  editable checklist/record files `0600`, and generated evidence anchors `0400`.
- [ ] Bind every session below to the exact seven values in
  `tested-fingerprint.txt`: `candidate.version`, `candidate.paper.sha256`,
  `candidate.client.sha256`, `candidate.dist-manifest.sha256`,
  `candidate.runtime-manifest.sha256`, `candidate.protocol-manifest.sha256`, and
  `candidate.archive.sha256`. Record the tested commit separately. Never mix
  artifacts from two commits or two `prepare` runs.
- [ ] Retain the expected archive hash in the private evidence directory and
  convey it to the cloud operator through a trusted channel independent of the
  uploaded archive. Do not derive the expected value from the cloud copy or an
  adjacent checksum uploaded through the same untrusted path.

Evidence may retain only exact hashes, public component versions, fixed status
codes, PASS/FAIL/BLOCKED or CLAIMED/NOT_CLAIMED decisions, and sanitized
observations. Evidence must not contain any provider or custom endpoint URL,
API key, Runtime token, model identifier, prompt, response body, tool arguments
or result body, raw Paper/Runtime/client/provider logs, player UUID, account
name, source IP, VM public address, or client-local path. The public pinned
Paper download URL in this checklist is not a private endpoint. Do not preserve
a terminal transcript. Transcribe only the allowed fixed lines.

## Uploaded candidate verification

- [ ] Upload exactly `release/MinecraftAgent-0.2.0.tar.gz` to a new private
  staging directory on the cloud host. Do not install loose local JARs or a
  Runtime tree from the source checkout.
- [ ] Compare the uploaded archive against the externally trusted hash before
  extraction. Substitute the value from `tested-fingerprint.txt`, not a value
  calculated on the cloud host:

  ```bash
  EXPECTED_ARCHIVE_SHA256='<trusted candidate.archive.sha256>'
  printf '%s  %s\n' "$EXPECTED_ARCHIVE_SHA256" MinecraftAgent-0.2.0.tar.gz \
    | sha256sum --check --strict -
  ```

- [ ] Extract into a new `0700` staging directory. From the extracted
  `MinecraftAgent-0.2.0` root, verify every packaged file against the archive's
  internal manifest and then compare the manifest file itself with the trusted
  candidate value:

  ```bash
  sha256sum --check --strict SHA256SUMS
  sha256sum SHA256SUMS
  ```

  The second output must equal `candidate.dist-manifest.sha256`. Any missing,
  extra, malformed, or mismatched result rejects the upload. Do not repair the
  extracted tree in place.
- [ ] Confirm the extracted Paper JAR and Fabric client JAR match
  `candidate.paper.sha256` and `candidate.client.sha256`. Install both only from
  this verified extraction.

## Pinned Paper and host baseline

The only Paper server accepted by this lane is build `1.21.11-132`:

```text
URL=https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar
SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
```

- [ ] Download over TLS directly on the isolated host, or transfer a previously
  verified private copy. Check the SHA-256 before installation. A matching file
  name without this hash is insufficient.
- [ ] Confirm system-level `/usr/bin/java`, `/usr/bin/node`, `/usr/bin/npm`,
  `/usr/bin/curl`, and `/usr/bin/jq` exist. Node must be 22.16 or newer in the
  22.x line, npm must be 10 or newer, and Java must be 21. Resolve every symlink
  and confirm the executable is root-owned, not group/other-writable, and not
  under `/home` or `/root`. nvm, SDKMAN, and user-home JDK paths are incompatible
  with the unit hardening. Confirm the service identities can execute the
  binaries after those users are created:

  ```bash
  sudo -u tagent /usr/bin/node --version
  sudo -u minecraft /usr/bin/java -version
  ```

  Record only sanitized version facts and the host image identifier.
- [ ] Create separate unprivileged OS users and groups: `tagent` owns only the
  Runtime state, and `minecraft` owns only the Paper server state. Neither user
  is root or belongs to the other service's group.

  ```bash
  sudo useradd --system --user-group --no-create-home \
    --home-dir /var/lib/tagent --shell /usr/sbin/nologin tagent
  sudo useradd --system --user-group --no-create-home \
    --home-dir /srv/minecraft --shell /usr/sbin/nologin minecraft
  ```
- [ ] Keep the verified application under `/opt/tagent/current`, Runtime state
  under `/var/lib/tagent/runtime`, and Paper state under `/srv/minecraft`.
  Private state directories are `0700`; private configuration, SQLite, token,
  environment, and log files are `0600`.
- [ ] Install `/etc/tagent/runtime.env` and `/etc/tagent/paper.env` as separate
  root-owned `0600` files. Runtime receives one selected provider key plus the
  shared Runtime token. Paper receives only the matching shared Runtime token
  and the preview opt-in. A provider key must never enter the Paper environment.
- [ ] Generate a new high-entropy Runtime token for this deployment. Use a
  dedicated, least-privilege, spending-limited test provider key and an
  operator-reviewed current price sheet. Configure actual integer micro-USD
  input/output rates and a conservative per-round reservation; example prices
  are not evidence.
- [ ] Install and review the examples under `deploy/systemd/` and
  `deploy/paper/`. Preserve their service hardening. Runtime configuration must
  bind `transport.host` to `127.0.0.1`, and Paper must use only
  `ws://127.0.0.1:<port>/agent`.

## Installation layout

The following layout matches the packaged service templates. Run it from the
private staging directory that contains the verified extracted
`MinecraftAgent-0.2.0/` directory and verified Paper JAR, after creating the two
service users. The examples deliberately use the system-level paths verified
above; do not weaken the user, environment, listener, or filesystem separation.

- [ ] Install the immutable application as root, install only production npm
  dependencies from its verified lockfile, and point `current` at this one
  release:

  ```bash
  sudo install -d -m 0755 /opt/tagent/releases
  sudo mv MinecraftAgent-0.2.0 /opt/tagent/releases/0.2.0
  sudo chown -R root:root /opt/tagent/releases/0.2.0
  (
    cd /opt/tagent/releases/0.2.0/agent-runtime
    sudo /usr/bin/npm ci --omit=dev --ignore-scripts --no-audit --no-fund
  )
  sudo ln -s /opt/tagent/releases/0.2.0 /opt/tagent/current
  ```

  `current` must be new for this disposable deployment. Do not replace an
  existing service through this checklist. Confirm the application and
  `node_modules` remain root-owned and are not writable by either service user.
- [ ] Create private Runtime state, copy the strict template, and edit only the
  private copy. Keep both secrets as whole-value environment references; set
  `privacy.logMessageContent: false` and `privacy.logToolCalls: false`:

  ```bash
  sudo install -d -o tagent -g tagent -m 0700 /var/lib/tagent/runtime
  sudo install -o tagent -g tagent -m 0600 \
    /opt/tagent/current/config.example.yml \
    /var/lib/tagent/runtime/config.yml
  sudo install -d -o root -g root -m 0700 /etc/tagent
  sudo install -o root -g root -m 0600 \
    /opt/tagent/current/deploy/systemd/runtime.env.example \
    /etc/tagent/runtime.env
  ```

  Replace every example price and placeholder in the private files. Remove all
  unused provider variables. An inline key/token, group-readable config, either
  privacy logging switch set to `true`, or a warning other than the expected
  custom-base-URL review signal makes the billable Provider check fail closed.
- [ ] Install the verified Paper server, candidate plugin, private plugin
  configuration, Paper environment, server properties, and EULA under the
  separate service user:

  ```bash
  sudo install -d -o minecraft -g minecraft -m 0700 \
    /srv/minecraft /srv/minecraft/plugins /srv/minecraft/plugins/MinecraftAgent
  sudo install -o minecraft -g minecraft -m 0644 \
    paper-1.21.11-132.jar /srv/minecraft/paper-1.21.11-132.jar
  sudo install -o minecraft -g minecraft -m 0644 \
    /opt/tagent/current/MinecraftAgent-Paper.jar \
    /srv/minecraft/plugins/MinecraftAgent-Paper.jar
  sudo install -o minecraft -g minecraft -m 0600 \
    /opt/tagent/current/deploy/paper/config.yml.example \
    /srv/minecraft/plugins/MinecraftAgent/config.yml
  sudo install -o minecraft -g minecraft -m 0600 \
    /opt/tagent/current/deploy/paper/server.properties.example \
    /srv/minecraft/server.properties
  printf 'eula=true\n' | sudo tee /srv/minecraft/eula.txt >/dev/null
  sudo chown minecraft:minecraft /srv/minecraft/eula.txt
  sudo chmod 0600 /srv/minecraft/eula.txt
  sudo install -o root -g root -m 0600 \
    /opt/tagent/current/deploy/systemd/paper.env.example \
    /etc/tagent/paper.env
  ```

  Set one explicit private cloud interface in `server.properties`, add only the
  intended Owner UUID when needed, and configure exactly the two whitelist
  accounts before accepting ingress. Keep `ops.json` empty for normal testing.
- [ ] While Paper is stopped, create its initial whitelist with `jq`, never with
  ad hoc JSON string assembly. Enter the two canonical online UUIDs and account
  names locally; they must never enter evidence:

  ```bash
  (
  set -euo pipefail
  read -r -p 'Client A UUID: ' UUID_A
  read -r -p 'Client A account name: ' NAME_A
  read -r -p 'Client B UUID: ' UUID_B
  read -r -p 'Client B account name: ' NAME_B
  [[ "$UUID_A" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]
  [[ "$UUID_B" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]
  [[ "$NAME_A" =~ ^[A-Za-z0-9_]{3,16}$ && "$NAME_B" =~ ^[A-Za-z0-9_]{3,16}$ ]]
  [[ "$UUID_A" != "$UUID_B" && "$NAME_A" != "$NAME_B" ]]
  seed_dir="$(mktemp -d)"
  trap 'rm -rf -- "$seed_dir"' EXIT
  chmod 0700 "$seed_dir"
  /usr/bin/jq -n \
    --arg uuid_a "$UUID_A" --arg name_a "$NAME_A" \
    --arg uuid_b "$UUID_B" --arg name_b "$NAME_B" \
    '[{uuid:$uuid_a,name:$name_a},{uuid:$uuid_b,name:$name_b}]' \
    >"$seed_dir/whitelist.json"
  /usr/bin/jq -n '[]' >"$seed_dir/ops.json"
  sudo install -o minecraft -g minecraft -m 0600 \
    "$seed_dir/whitelist.json" /srv/minecraft/whitelist.json
  sudo install -o minecraft -g minecraft -m 0600 \
    "$seed_dir/ops.json" /srv/minecraft/ops.json
  rm -rf "$seed_dir"
  trap - EXIT
  )
  ```

  Before each Paper start, use `jq` locally to confirm `whitelist.json` is an
  array of exactly two distinct entries and `ops.json` is the intended empty or
  temporary-management set. Do not copy either output into evidence.
- [ ] Install reviewed copies of all three unit examples, verify their absolute
  `node`, `java`, and `curl` paths, then reload systemd. The Provider unit has no
  `[Install]` section by design:

  ```bash
  sudo install -o root -g root -m 0644 \
    /opt/tagent/current/deploy/systemd/tagent-runtime.service.example \
    /etc/systemd/system/tagent-runtime.service
  sudo install -o root -g root -m 0644 \
    /opt/tagent/current/deploy/systemd/tagent-provider-check.service.example \
    /etc/systemd/system/tagent-provider-check.service
  sudo install -o root -g root -m 0644 \
    /opt/tagent/current/deploy/systemd/tagent-paper.service.example \
    /etc/systemd/system/tagent-paper.service
  sudo systemctl daemon-reload
  sudo systemd-analyze verify \
    tagent-runtime.service tagent-provider-check.service tagent-paper.service
  ```

  Do not start either long-running service until the network and configuration
  checks below are complete.

## Network and authenticated clients

- [ ] Before Paper starts, restrict cloud security-group ingress for TCP
  `25565` to exactly two current physical-tester source IPs. No other test
  source, CIDR range, or world-open rule is allowed.
- [ ] Apply the same exact two-source allowlist in the host firewall, with
  default-deny behavior for other inbound traffic. Record only that the two
  independently administered layers matched; do not record the IP values.
- [ ] Do not expose Runtime `38127`, RCON, query, database, debug, JMX, or
  provider-check ports through either layer. Confirm with a local socket
  inventory that Runtime listens only on loopback and Paper listens only on the
  intended explicit cloud interface, never `0.0.0.0`.
- [ ] Set and recheck these exact Paper properties before startup:

  ```properties
  online-mode=true
  white-list=true
  enforce-whitelist=true
  enable-rcon=false
  enable-query=false
  broadcast-rcon-to-ops=false
  ```

- [ ] Whitelist exactly two legitimate authenticated test accounts, one on each
  physical client. Do not use offline mode, an authentication bypass, shared
  launcher state, or a proxy that replaces Paper identity. Keep `ops.json`
  empty except for a short, separately observed management case.
- [ ] If live `/agent off` and `/agent on` testing needs an Owner, configure only
  the primary tester's canonical UUID, keep it out of evidence, and remove it at
  cleanup. Do not enable RCON or broaden OP toggle as a shortcut.

## Fixed startup and shutdown order

- [ ] Install the three units but do not enable or schedule
  `tagent-provider-check.service`. It is a manual billable one-shot only.
- [ ] Validate the selected provider profile with the billable one-shot as
  described below. Stop after one bounded run per profile; repeated runs create
  additional provider calls and possible charges.
- [ ] Start `tagent-runtime.service` and wait for its loopback `/health` response
  to be `READY`. Confirm no Paper process is running before this point.
- [ ] Start `tagent-paper.service`. Its `Requires`/`After` dependency and
  `ExecStartPre` loopback health probe must ensure Runtime readiness precedes
  Paper. Confirm `/agent` is registered only after the authenticated hello.
- [ ] Stop in reverse order: disconnect clients, stop Paper cleanly and wait for
  world save/exit, then stop Runtime. Never stop Runtime first during ordinary
  closeout.

## Billable live-provider profile check

The production CLI intentionally performs provider readiness, one text
generation, one exact function call, and its continuation. These calls can be
billed. The required `--confirm-billable` flag is the operator's explicit
authorization; running without it must not contact a provider.

For each profile that will be marked `CLAIMED`, install only that profile's
private configuration/environment and manually run:

```bash
(
set -euo pipefail
sudo systemctl reset-failed tagent-provider-check.service
sudo journalctl --sync
journal_cursor="$(
  sudo journalctl -n 0 --show-cursor --no-pager \
    | sed -n 's/^-- cursor: //p'
)"
[[ -n "$journal_cursor" ]]
if sudo systemctl start tagent-provider-check.service; then
  provider_start_status=0
else
  provider_start_status=$?
fi
sudo journalctl --sync
mapfile -t invocation_ids < <(
  sudo journalctl -u tagent-provider-check.service \
    --after-cursor="$journal_cursor" -o json --no-pager \
    | /usr/bin/jq -r \
      'select((.MESSAGE // "") | test("^(profile|confirmation|config|health|text|tool_call|continuation|result)=")) | ._SYSTEMD_INVOCATION_ID // empty' \
    | sort -u
)
[[ "${#invocation_ids[@]}" == 1 ]]
invocation_id="${invocation_ids[0]}"
[[ "$invocation_id" =~ ^[0-9a-f]{32}$ ]]
mapfile -t provider_lines < <(
  sudo journalctl _SYSTEMD_INVOCATION_ID="$invocation_id" -o cat --no-pager
)
unit_result="$(sudo systemctl show tagent-provider-check.service --value --property=Result)"
exec_main_status="$(sudo systemctl show tagent-provider-check.service --value --property=ExecMainStatus)"
[[ "$provider_start_status" == 0 && "$unit_result" == success && "$exec_main_status" == 0 ]]
[[ "${#provider_lines[@]}" == 6 ]]
[[ "${provider_lines[0]}" =~ ^profile=(openai|anthropic|deepseek|gemini|openai-compatible)[[:space:]]endpoint=(DEFAULT|CUSTOM)[[:space:]]model_hmac_sha256=[0-9a-f]{64}$ ]]
[[ "${provider_lines[1]}" == 'health=PASS' ]]
[[ "${provider_lines[2]}" == 'text=PASS usage=REPORTED' ]]
[[ "${provider_lines[3]}" == 'tool_call=PASS usage=REPORTED' ]]
[[ "${provider_lines[4]}" == 'continuation=PASS usage=REPORTED' ]]
[[ "${provider_lines[5]}" == 'result=PASS' ]]
printf '%s\n' "${provider_lines[@]}"
)
```

Require the captured start status and `ExecMainStatus` to be `0`, `Result` to be
`success`, and the filtered invocation to contain exactly the six fixed lines
below in order. Obtain a new invocation ID for every profile. Do not select lines
from a previous run and do not retain the journal output.

The unit directly executes the packaged JavaScript, without TypeScript sources
or development dependencies:

```text
/usr/bin/node /opt/tagent/current/agent-runtime/dist/validation/live-provider-check.js --confirm-billable --config /var/lib/tagent/runtime/config.yml
```

- [ ] Transcribe only these fixed status forms into evidence; do not retain the
  journal or complete command output:

  ```text
  profile=openai|anthropic|deepseek|gemini|openai-compatible endpoint=DEFAULT|CUSTOM model_hmac_sha256=<64-lowercase-hex>
  health=PASS
  text=PASS usage=REPORTED
  tool_call=PASS usage=REPORTED
  continuation=PASS usage=REPORTED
  result=PASS
  ```

- [ ] Treat any `FAIL`, nonzero unit result, unknown line, unexpected body, or
  missing `result=PASS` as not claimed and investigate outside the evidence
  set. `usage=MISSING` or `usage=INVALID` fails this strict profile check; it is
  never rewritten as reported. The domain-separated model HMAC may be retained,
  but the model identifier and its HMAC key may not. The HMAC is keyed by the
  deployment's high-entropy Runtime token and cannot be reproduced after that
  token is rotated at cleanup.
- [ ] Mark an official profile `CLAIMED` only after its own configured native
  adapter passes. A result from another profile, model, key, or endpoint cannot
  be reused. Every untested profile remains `NOT_CLAIMED` and must be excluded
  from public compatibility claims.
- [ ] For `openai-compatible`, additionally confirm the reviewed service
  implements the exact bounded `GET models` and `POST chat/completions` contract
  with serial function continuation. The claim applies only to that reviewed
  fixture, not arbitrary compatible endpoints.
- [ ] Select exactly one already `CLAIMED` profile as the deployment provider
  for the real Minecraft path. Do not configure fallback, retry, key rotation,
  or protocol discovery.

## Real Minecraft end-to-end path

Run this section through a real whitelisted player, the deployed candidate
Paper JAR, the deployed candidate Runtime, and the selected real provider. Use a
dedicated harmless prompt locally, but do not record it or the response.

- [ ] **Readiness:** observe Runtime `READY`, Paper startup after Runtime,
  authenticated hello, conditional `/agent` registration, and bounded
  `/agent status`, `/agent doctor`, and `/agent costs` output with no private
  values.
- [ ] **Private text:** from physical client A, submit one ordinary
  `/agent say` request. Confirm client A receives one literal private response
  and simultaneously connected physical client B receives no response or chat
  broadcast. Do not use server logs as substitute evidence.
- [ ] **Real tool continuation:** use a fixed module request that requires an
  authoritative live-server read, such as a recipe lookup, and observe the
  final private fallback or structured authoritative view after the tool result
  is returned to the real provider. A provider-only CLI result does not replace
  this Minecraft path.
- [ ] **Usage and cost ledger:** compare sanitized `/agent costs` snapshots
  before and after the completed requests. Confirm admitted requests and
  provider-call counts advance; reported token usage is charged by the
  configured formula, while missing/unknown usage is recorded as `ESTIMATED`
  at the reservation. Do not claim equality with the provider invoice.
- [ ] **Idle reservation:** after every response settles, wait for no live
  request and confirm `/agent costs` reports active reservation exposure `0`.
- [ ] **Cancellation:** begin one bounded request and immediately disconnect the
  requesting player. Confirm no stale private reply appears after reconnect,
  new work still completes, and the cost ledger eventually has active
  reservation `0`. A started provider round may conservatively settle as
  estimated; it must not disappear or remain active.
- [ ] **Offline control:** as the configured Owner, start a bounded request and
  run exact `/agent off`. Confirm admission closes, the request is cancelled,
  all non-toggle forms return exactly `AI offline`, and active reservation
  exposure returns to `0` after settlement.
- [ ] **Recovery:** run `/agent on` while Runtime is healthy. Confirm a fresh
  authenticated connection/generation, ONLINE state, and a new private request.
  Then use
  `sudo systemctl kill --kill-whom=main --signal=SIGKILL tagent-runtime.service`
  for one controlled abnormal termination, allow
  `Restart=on-abnormal` to restore Runtime, confirm Paper remains Offline
  without automatic reconnect, and run `/agent on` again.
  Verify the new generation accepts work and previous-generation replies or UI
  actions remain stale and ineffective.
- [ ] **Final ledger:** with both clients idle, confirm admitted/call/usage
  outcomes are internally consistent and active reservation exposure is
  exactly `0` before shutdown.

### Live timeout claim

A live provider timeout is not a mandatory acceptance test. The automated
release gate already covers configured timeout abort, cancellation, late-result
suppression, and reservation settlement with injected offline providers. Real
provider latency is nondeterministic, deliberate live delay may incur charges,
and changing `timeoutSeconds` alone does not create a controlled fixture.

- [ ] Leave live timeout `NOT_CLAIMED` unless a reviewed provider/account fault
  control can deterministically delay the response beyond the configured bound
  without exposing content, weakening TLS, using a packet proxy, or affecting
  unrelated users.
- [ ] Only under that controlled fault may live timeout be marked `CLAIMED`.
  Then require the fixed timeout result, no late player reply, correct
  reported-or-estimated usage treatment, and active reservation `0`. Record no
  request, response, endpoint, model, or raw log.
- [ ] Record the clean automated release-check result separately. An offline
  automated pass supports the mandatory timeout gate but must not be relabeled
  as a live timeout claim.

## Phase 13 three-channel rerun on the same fingerprint

Repeat the complete `docs/phase13-manual-test.md` checklist. Its historical
record is not reusable. Every fresh harness invocation must print fingerprints
that exactly match the `0.2.0` values in `tested-fingerprint.txt`.

- [ ] **Vanilla channel:** Minecraft `1.21.11` with no Fabric or Agent client.
- [ ] **Agent client channel:** Fabric Loader `0.19.3`, Fabric API
  `0.141.4+1.21.11`, and the exact candidate Fabric JAR, without Litematica or
  MaLiLib.
- [ ] **Exact Litematica channel:** the same Agent client plus Litematica
  `0.26.12` and MaLiLib `0.27.16`, with adapter
  `litematica-reflection-1` and preview enabled only for that fresh session.
- [ ] Use the two physical licensed clients required above. Both must join the
  exact candidate at least once, and the second must be simultaneously present
  for the private-broadcast observation. Use distinct launcher/game directories
  but do not record their paths.
- [ ] Complete all mandatory Phase 13 readiness, fallback, overlay, recipe,
  preview, Material List, remove, disconnect, Offline, recovery, and cleanup
  observations. Only the exact non-core BLOCKED allowlist in that checklist is
  permitted; any other blocker or any failure rejects the candidate.
- [ ] Compare the Paper JAR, client JAR, dist manifest, Runtime manifest,
  protocol manifest, archive hash, and version after every channel. All three
  channels and the real-provider deployment must be `MATCH` to the same tested
  fingerprint.

## Cleanup, rotation, and final comparison

- [ ] Disconnect both clients and stop Paper, then Runtime. Confirm no candidate
  Java/Node process or validation listener remains.
- [ ] Remove both security-group source rules and both host-firewall source
  rules. Confirm TCP `25565` and all test-only ingress are closed.
- [ ] Revoke or rotate every provider key used by a billable check or deployment
  and rotate the Runtime token. Remove the private environment files, Owner,
  whitelist, extracted archive, Paper world, Runtime SQLite/state, generated
  configuration, and local raw logs. Terminate the disposable VM when possible.
- [ ] Review screenshots and notes manually. Preserve only sanitized hashes,
  fixed status lines, version facts, and outcome decisions in the external
  `0700` evidence directory. Delete rejected or unsanitized captures; rotate a
  credential again if there is any doubt that it appeared.
- [ ] Fill `docs/phase14-final-acceptance-record.md` only with sanitized
  outcomes. Keep overall status `PENDING` until every strict condition in that
  record is satisfied; use `REJECTED` for a mandatory failure.
- [ ] After updating and committing only
  `docs/phase14-final-acceptance-record.md` and optionally `docs/progress.md`, use
  a clean trusted checkout and the original append-only evidence directory. The
  compare command rejects any other post-candidate path:

  ```bash
  ./scripts/final-validation.sh compare /absolute/private/phase14-evidence
  ```

- [ ] Require `final-validation mode=compare result=passed`, the newly written
  `final-fingerprint.txt`, and exact equality of all seven candidate payload
  fields. The final file also records the final release-manifest hash and its
  atomic pass marker. A mismatch requires a new cloud, provider, and
  three-channel run; it cannot be waived by notes.
- [ ] Confirm the final record is `ACCEPTED` before any tag or public Release.
