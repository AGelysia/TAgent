# Phase 14 Final Acceptance Record

This repository record contains sanitized release facts only. Raw working
evidence remains outside the repository. It intentionally contains no provider
URL, credential, model identifier, prompt, response, tool payload, raw log,
player identity, source address, VM address, or client-local path.

## Overall decision

- Strict checklist status: `REJECTED`
- Maintainer publication decision: `ACCEPTED_FOR_PRERELEASE_EXCEPTION`
- Release channel: `PRE_RELEASE` (controlled public preview)
- Final candidate version: `0.2.0`
- Test date (UTC): `2026-07-16`
- Sanitized external evidence reference: `phase14-0.2.0-3fd0959`
- Observed mandatory test failures: `0`
- Mandatory incomplete checks: `1` (native Litematica projection lifecycle)
- Strict evidence gaps: no named live-provider profile has a retained exact CLI
  transcript; native projection execution was not observed.
- Unapproved publication blockers after the maintainer exception: `0`
- Approved release limitation: exact-Litematica native projection execution was
  not observed against this payload.

The maintainer reported that every planned cloud, real-provider Minecraft, and
two-physical-client check passed except native Litematica projection. That
statement is the source for the manual outcomes below because the test system's
raw files were not migrated back to this workstation. The repository-external
checklist therefore remains an unfilled template and is not represented as raw
field-by-field evidence.

The missing projection observation is not relabelled `PASS`. The empty named
provider claim set also does not satisfy the original requirement for one
`CLAIMED` deployment profile. The strict gate therefore has the terminal result
`REJECTED`. Separately, the maintainer explicitly accepted both evidence gaps as
limitations for this first public preview. This exception authorizes a
prerelease; it does not redefine the strict result. GitHub must mark `v0.2.0` as
a prerelease and state both limitations prominently.

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
- Initial release-manifest SHA-256:
  `3a9e34bebc2641becdc214d46e805f7a26cf1524910955d83a5cd4a8256a8656`
- Pinned Paper build: `1.21.11-132`
- Pinned Paper SHA-256:
  `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`

The uploaded archive hash, extracted package manifest, internal checksums, dist
manifest, and pinned Paper hash were reported `PASS`. No payload or archive
change is permitted between this binding and the release tag.

## Automated release evidence

- Initial clean release check: `PASS`
- Runtime: `26` suites, `287` tests, `0` failures/errors/skips
- Paper: `59` suites, `463` tests, `0` failures/errors/skips
- Client: `17` suites, `210` tests, `0` failures/errors/skips
- Pinned Paper smoke: `PASS`
- Extracted production Runtime and provider-check entry: `PASS`
- Production dependency install: `PASS`
- npm high-severity audit findings: `0`
- Two uncached Linux package builds: identical complete manifests
- GitHub Verify on the tested candidate and evidence-only successor: Linux and
  PowerShell jobs `PASS`
- Publication-stage final check and fingerprint: executed only after this
  evidence decision is committed; the authoritative outcomes are the append-only
  external `final-fingerprint.txt` and commit-bound GitHub Actions run.

The final comparison is a mechanical publication condition, not a result
asserted in advance by this record. If it does not produce
`final-validation mode=compare result=passed`, the maintainer exception is void
and no tag or Release may be created.

## Provider claims

All five production adapters are implemented and covered by offline contract
tests. No named profile is claimed as independently live-validated for this
release because the exact billable CLI identity/status transcript was not
retained in the migrated evidence.

| Provider profile | Live claim | Released adapter |
| --- | --- | --- |
| `openai` | `NOT_CLAIMED` | OpenAI Responses |
| `anthropic` | `NOT_CLAIMED` | Anthropic Messages |
| `deepseek` | `NOT_CLAIMED` | DeepSeek Chat Completions |
| `gemini` | `NOT_CLAIMED` | Gemini `generateContent` |
| `openai-compatible` | `NOT_CLAIMED` | reviewed Chat Completions protocol |

- Declared public live-provider claim set: `EMPTY`
- Operator-configured real-provider Minecraft response path: `PASS` by
  maintainer observation
- Exact deployed provider profile: `NOT_RETAINED`; therefore not a public claim
- Live timeout claim: `NOT_CLAIMED`
- Provider pricing/account correctness claim: `NOT_CLAIMED`
- Automatic fallback, retry, rotation, or protocol discovery: `ABSENT`

Release notes may say these adapters are available. They must not say that every
listed provider, model, account, custom URL, or compatible endpoint was live
certified. Operators remain responsible for validating their chosen endpoint,
model capabilities, current prices, and conservative reservations.

## Cloud and Minecraft acceptance

The maintainer attested the following outcomes for the exact bound payload:

- Split Runtime/Paper service identities and private environment boundaries:
  `PASS`
- Runtime loopback binding, restricted Minecraft ingress, online-mode,
  whitelist enforcement, and disabled RCON/query: `PASS`
- Runtime-before-Paper readiness and authenticated command registration: `PASS`
- Two distinct physical licensed clients joined and were simultaneously online:
  `PASS`
- Private `/agent say` response with no observer broadcast: `PASS`
- Real read-tool continuation and correct AI response: `PASS`
- Usage/cost settlement and zero final active reservation exposure: `PASS`
- Disconnect cancellation, Offline gate, explicit recovery, and stale-generation
  suppression: `PASS`
- Shutdown, temporary-access removal, secret rotation/removal, and sanitized
  evidence review: `PASS`
- Bare `/agent` required OP: `EXPECTED`; it aliases the administrative status
  surface. Ordinary permitted `/agent say`, module, and UI paths did not require
  OP.

Sanitized test-host facts: Ubuntu kernel `6.8.0-48-generic`, OpenJDK
`21.0.11`, Node.js `22.23.1`, npm `10.9.8`, x86_64, 8 virtual CPU cores,
12 GiB assigned memory. No host or player identifier is retained.

## Client channels

| Channel | Outcome | Release interpretation |
| --- | --- | --- |
| Vanilla `1.21.11` | `PASS` | supported fallback client |
| Agent client without Litematica | `PASS` | accepted preview client path |
| Exact Litematica `0.26.12` / MaLiLib `0.27.16` | `INCOMPLETE` | adapter ships as experimental |

For the exact-Litematica channel, native placement creation/loading,
placement-derived bounds and origin, Material List, explicit removal,
changed-position fresh preview, and disconnect cleanup were not directly
observed against the `0.2.0` payload. A correct AI response and server dispatch
do not prove client execution. The older Phase 13 observation belongs to commit
`3735c5ef7fe55f04fff499b257e72e71707c47c0` and is not substituted here.

The base Fabric client remains optional and preview loading remains off by
default. The exact dependency tuple is included only to identify the implemented
reflection adapter; this prerelease makes no stable Litematica compatibility
claim.

The frozen archive's `CLIENT-COMPATIBILITY.md` table calls this tuple a supported
release target and describes the intended preview lifecycle. For `v0.2.0`, that
wording identifies the implemented matrix target, not a completed physical-test
certification. This record and the GitHub prerelease limitation supersede that
certification implication without changing the already tested payload.

## Publication controls

Publication is authorized only when all of these conditions hold:

1. `scripts/final-validation.sh compare` passes from a clean evidence-only final
   commit and reproduces every fixed payload hash above.
2. The manual `Release candidate` workflow passes for that commit and its
   downloaded assets match the fixed Paper, Client, archive, and release-manifest
   hashes.
3. The annotated `v0.2.0` tag targets that verified final commit.
4. The GitHub Release is marked prerelease and uploads only the complete archive,
   standalone Paper JAR, standalone Fabric JAR, and `SHA256SUMS`.
5. Release notes disclose the unverified Litematica lifecycle, empty live-provider
   claim set, absent native Windows equivalence, preview-only/no-world-write
   boundary, and bare `/agent` OP behavior.

Any payload mismatch, CI failure, secret exposure, or broader compatibility
claim voids this acceptance. Resolving the Litematica limitation later requires
a new physical-client observation against the exact released or successor
fingerprint; it must not be retroactively recorded as a `0.2.0` pass.
