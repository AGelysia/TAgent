# Phase 14 Final Acceptance Record

This repository-only record is a deliberately uncompleted template. Raw working
evidence belongs in the private external directory created by
`scripts/final-validation.sh prepare`. Do not place a provider/custom endpoint
URL, key, token, model identifier, prompt, response, tool payload, raw log,
player UUID/account name, source IP, VM address, or client-local path here.

The historical Phase 13 acceptance record must remain unchanged. Its outcome
does not apply to this candidate.

## Overall decision

- Status: `PENDING` (`PENDING`, `ACCEPTED`, or `REJECTED` only)
- Final candidate version: `0.2.0`
- Test date (UTC): `<PENDING>`
- Sanitized external evidence reference: `phase14-0.2.0-3fd0959`
- Mandatory failures: `<PENDING>`
- Unapproved blockers: `<PENDING>`

This file must remain `PENDING` until all strict conditions at the end are
satisfied. Notes cannot turn a missing test, `FAIL`, mismatch, or unsafe evidence
into a pass.

## Fixed candidate binding

- Tested `candidate.commit`: `3fd09598f61f6223504ad997a6135373523c8e69`
- Tested `candidate.version`: `0.2.0`
- Tested `candidate.paper.sha256`:
  `213acda1974d39a65d0fcc9ac8902816284019d01bef8b0c37b9c95c75263d53`
- Tested `candidate.client.sha256`:
  `f823309e9f22ba505d5fcccc7f107c0f3d9dcee84edf24341b83eb03c9bd38e2`
- Tested `candidate.dist-manifest.sha256`:
  `4c7369864c3ff842c117af9e24c147f06543c5a9e850057663ac67c112c09dad`
- Tested `candidate.runtime-manifest.sha256`:
  `904c2a17907287fb97910e00aef045e69c7c23e857eb5602da2e0ea7a5286b89`
- Tested `candidate.protocol-manifest.sha256`:
  `8911de23fd119adc6c424c27e6f1b598fd35948c32fa7ab1eb3629fc3bdb2f8e`
- Tested `candidate.archive.sha256`:
  `2326361b0ad606adf96e3d104174822786118b2811a1d38b88a6c3a6a0638a75`
- Trusted external archive-hash check: `PENDING`
- Extracted package `SHA256SUMS` check: `PENDING`
- Initial release-manifest SHA-256:
  `3a9e34bebc2641becdc214d46e805f7a26cf1524910955d83a5cd4a8256a8656`
- Extracted manifest hash equals `candidate.dist-manifest.sha256`: `PENDING`
- Pinned Paper build: `1.21.11-132`
- Pinned Paper SHA-256:
  `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`
- Pinned Paper hash check: `PENDING`
- Initial clean release check: `PASS`

All placeholders must be replaced with the exact values from the one external
`tested-fingerprint.txt`. Do not combine values from separate candidate runs.

## Provider claims

`CLAIMED` and `NOT_CLAIMED` are the only profile-claim values. A profile remains
`NOT_CLAIMED` until its own explicitly billable live CLI run ends in the exact
fixed PASS statuses, including `usage=REPORTED` for all three generation rounds.
`usage=MISSING` or `usage=INVALID` makes the CLI result fail. A profile that is
`NOT_CLAIMED` must be excluded from release compatibility claims.

| Provider profile | Claim | Billable fixed-status CLI | Usage decision | Notes |
| --- | --- | --- | --- | --- |
| `openai` | `NOT_CLAIMED` | `PENDING` | `PENDING` | |
| `anthropic` | `NOT_CLAIMED` | `PENDING` | `PENDING` | |
| `deepseek` | `NOT_CLAIMED` | `PENDING` | `PENDING` | cache-miss input rate required |
| `gemini` | `NOT_CLAIMED` | `PENDING` | `PENDING` | tool/thinking usage included |
| `openai-compatible` | `NOT_CLAIMED` | `PENDING` | `PENDING` | reviewed fixture only |

- Declared public provider claim set: `<PENDING: profile IDs only>`
- Deployment provider profile: `<PENDING: exactly one CLAIMED profile ID>`
- Each claimed CLI identity line matched its profile, DEFAULT/CUSTOM endpoint
  mode, and private token-keyed model HMAC-SHA-256: `PENDING`
- Deployment profile pricing reviewed against current account price sheet:
  `PENDING`
- Deployment per-round reservation reviewed as conservative: `PENDING`
- No fallback, retry, rotation, or protocol discovery used: `PENDING`
- Reviewed `openai-compatible` exact models/chat-completions fixture, when
  claimed: `PENDING` (`PASS`, `FAIL`, or `NOT_CLAIMED`)

Do not record the deployed model name, endpoint, account, key, prices, prompt,
response, or provider error body.

## Cloud deployment

| Item | Result |
| --- | --- |
| Separate `tagent` and `minecraft` OS users/groups | `PENDING` |
| Separate root-owned `0600` Runtime/Paper environment files | `PENDING` |
| Provider key present only in Runtime environment | `PENDING` |
| Runtime listener restricted to loopback | `PENDING` |
| Security group allows `25565` from exactly two tester sources | `PENDING` |
| Host firewall independently allows the same two sources | `PENDING` |
| Runtime/RCON/query/database/debug ports not exposed | `PENDING` |
| `online-mode`, whitelist, and enforce-whitelist enabled | `PENDING` |
| RCON and query disabled | `PENDING` |
| Exactly two authenticated accounts whitelisted | `PENDING` |
| Runtime READY before Paper start | `PENDING` |
| Paper systemd dependency and health precheck observed | `PENDING` |
| `/agent` registered only after authenticated readiness | `PENDING` |

Sanitized host image, Java, Node, npm, and systemd version facts:
`<PENDING>`

## Real-provider Minecraft path

| Mandatory observation | Result | Sanitized outcome only |
| --- | --- | --- |
| Runtime/Paper readiness and bounded management output | `PENDING` | |
| Real `/agent say` private response to requester | `PENDING` | |
| Simultaneous second client observed no broadcast | `PENDING` | |
| Real authoritative read-tool call and provider continuation | `PENDING` | |
| Completed requests advanced admitted/call ledger counts | `PENDING` | |
| Usage settled as reported or conservatively estimated | `PENDING` | |
| Idle active reservation exposure equals `0` | `PENDING` | |
| Player-disconnect cancellation suppressed stale reply | `PENDING` | |
| Cancellation ledger settled and active exposure equals `0` | `PENDING` | |
| `/agent off` closed admission and returned exact `AI offline` | `PENDING` | |
| `/agent on` created a fresh authenticated generation | `PENDING` | |
| Controlled Runtime loss stayed Offline until explicit recovery | `PENDING` | |
| Post-recovery new work passed; old generation stayed stale | `PENDING` | |
| Final active reservation exposure equals `0` | `PENDING` | |
| No prompt, response, private identity, path, or secret leaked | `PENDING` | |

## Timeout and usage decision

- Automated offline timeout/cancellation/late-result gate in the clean release
  check: `PENDING`
- Live provider timeout claim: `NOT_CLAIMED` (`CLAIMED` or `NOT_CLAIMED` only)
- Reviewed deterministic provider/account fault control used: `NO`
- Live timeout result: `NOT_CLAIMED`
- Late player reply under a claimed live timeout: `NOT_CLAIMED`
- Timeout usage settlement: `NOT_CLAIMED`
- Timeout active reservation exposure returned to `0`: `NOT_CLAIMED`
- Deployment usage outcome: `PENDING` (`REPORTED`, `MISSING_ESTIMATED`,
  `MIXED_REPORTED_ESTIMATED`, or `FAIL`)
- Ledger decision matches CLI usage status and Runtime settlement rules:
  `PENDING`

Live timeout is optional and may remain `NOT_CLAIMED` without blocking
acceptance. It may be changed to `CLAIMED` only when the controlled-fault rules
in `phase14-cloud-validation.md` were followed and every associated row passes.
Changing a timeout value or relying on ordinary provider latency is not a
controlled claim.

## Two physical licensed clients

Do not record account names, UUIDs, source IPs, public addresses, or launcher
paths.

| Physical client | Licensed authenticated account | Sanitized platform facts | Role | Result |
| --- | --- | --- | --- | --- |
| A | `PENDING` | `<PENDING>` | primary three-profile tester | `PENDING` |
| B | `PENDING` | `<PENDING>` | independent observer/tester | `PENDING` |

- Both devices joined the exact final candidate at least once: `PENDING`
- Both were simultaneously online for private non-broadcast evidence: `PENDING`
- Distinct physical devices and launcher/game directories confirmed: `PENDING`
- Authentication remained online and whitelist enforced throughout: `PENDING`

## Phase 13 three-channel rerun

Every row is a new run against this record's exact `0.2.0` fingerprint. The
historical Phase 13 result is not referenced as a substitute.

| Channel | Complete checklist | Fingerprint | Outcome |
| --- | --- | --- | --- |
| Vanilla `1.21.11` | `PENDING` | `PENDING` | `PENDING` |
| Agent client, no Litematica | `PENDING` | `PENDING` | `PENDING` |
| Exact Litematica `0.26.12` / MaLiLib `0.27.16` | `PENDING` | `PENDING` | `PENDING` |

- Mandatory Phase 13 core items: `PENDING`
- Allowlisted non-core BLOCKED fixtures only: `<PENDING>`
- Phase 13 failures: `<PENDING>`
- All three channels match the tested Paper/client/dist/Runtime/protocol/archive
  hashes and `0.2.0` version: `PENDING`

## Cleanup and redaction

| Item | Result |
| --- | --- |
| Clients disconnected; Paper stopped before Runtime | `PENDING` |
| No candidate Java/Node process or listener remains | `PENDING` |
| Security-group and host-firewall test rules removed | `PENDING` |
| Provider keys revoked or rotated | `PENDING` |
| Runtime token rotated | `PENDING` |
| Private env/config/world/SQLite/log/staging data removed | `PENDING` |
| Temporary Owner, OP entries, and whitelist removed | `PENDING` |
| Evidence contains only allowed sanitized fields | `PENDING` |
| URL/key/model/prompt/response/tool payload/raw-log scan passed | `PENDING` |

## Final comparison

- Final repository commit: `<PENDING: 40 lowercase hex>`
- Final clean release check: `PENDING`
- `final-validation mode=compare result=passed`: `PENDING`
- `final-fingerprint.txt` created append-only: `PENDING`
- Final `candidate.version`: `<PENDING; must be 0.2.0>`
- Final Paper JAR hash: `<PENDING: 64 lowercase hex>`
- Final Client JAR hash: `<PENDING: 64 lowercase hex>`
- Final dist-manifest hash: `<PENDING: 64 lowercase hex>`
- Final Runtime-manifest hash: `<PENDING: 64 lowercase hex>`
- Final protocol-manifest hash: `<PENDING: 64 lowercase hex>`
- Final archive hash: `<PENDING: 64 lowercase hex>`
- Final release-manifest hash: `<PENDING: 64 lowercase hex>`
- Tested-to-final seven-field comparison: `PENDING` (`MATCH` required)

The final repository commit may modify only
`docs/phase14-final-acceptance-record.md` and optionally `docs/progress.md` after
the tested candidate. The compare script rejects any other committed path. Any
payload or archive change requires a new prepare, cloud deployment,
live-provider path, physical-client rerun, and final comparison.

## Strict `ACCEPTED` conditions

Set overall Status to `ACCEPTED` only when all of the following are true:

1. The candidate is exactly `0.2.0`; every fixed placeholder is complete; the
   trusted upload, internal `SHA256SUMS`, pinned Paper, initial release check,
   and final release check all pass.
2. The final compare reports `MATCH` for version, Paper JAR, Client JAR, dist
   manifest, Runtime manifest, protocol manifest, and archive. No tested payload
   changed.
3. The two-user deployment, split secret environments, loopback Runtime,
   two-source security group and host firewall, online authentication, exact
   whitelist, disabled RCON/query, and Runtime-before-Paper order all pass.
4. The deployment provider is `CLAIMED`. Every profile in the declared public
   claim set is `CLAIMED` by its own exact billable CLI PASS; every other profile
   is `NOT_CLAIMED` and excluded from public compatibility claims. A claimed
   `openai-compatible` result is limited to its reviewed fixture.
5. Every mandatory real-provider Minecraft row passes, including private
   delivery, real tool continuation, cancellation, Offline/recovery, correct
   usage settlement, and final active reservation exposure `0`.
6. The clean automated timeout gate passes. Live timeout is either validly
   `CLAIMED` with every controlled-fault row passing or remains honestly
   `NOT_CLAIMED`; no uncontrolled live timeout claim is permitted.
7. Both independent physical licensed clients pass their required observations,
   and the complete Phase 13 Vanilla, Agent-client, and exact-Litematica lanes
   use the same final fingerprint. All mandatory core items pass, with BLOCKED
   permitted only for the exact Phase 13 non-core allowlist.
8. Cleanup, credential rotation, ingress removal, and evidence-redaction review
   all pass. The record and external evidence contain none of the prohibited
   values or raw logs.
9. There is no mandatory failure, fingerprint mismatch, unresolved security
   concern, or blocker outside the explicit non-core allowlist.

Any other terminal combination is `REJECTED`, not `ACCEPTED`. Keep `PENDING`
only while work remains genuinely in progress. A tag or public Release is
forbidden until this record is `ACCEPTED`.
