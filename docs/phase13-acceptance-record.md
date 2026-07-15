# Phase 13 Acceptance Record

This maintainer record is intentionally excluded from the player package. Use
`phase13-manual-test.md` as the immutable checklist template and keep raw working
evidence outside the Git worktree. Add only sanitized outcomes here after every
graphical session for one tested candidate has stopped.

## Candidate binding

- Status: `ACCEPTED` (`PENDING`, `ACCEPTED`, or `REJECTED` only)
- Tested candidate commit: `3735c5ef7fe55f04fff499b257e72e71707c47c0`
- Tested `candidate.paper.sha256`: `468a54dba0cd1cbc79d68831b2e78290a38614fd58438241f2e9b1927f16629f`
- Tested `candidate.client.sha256`: `44b3048e3cc2163008579485f5121e03b0ef78889f680f24f67a899a287d308a`
- Tested `candidate.dist-manifest.sha256`: `34fa1378a1cc46ca6e69332abe59d49cec5712016ed129e151aae2d734cb6a70`
- Tested `candidate.runtime-manifest.sha256`: `17632450b6dfe9f7e8c3014a126753169c05ce0733971d6025f6e31d511b8915`
- Tested `candidate.protocol-manifest.sha256`: `8911de23fd119adc6c424c27e6f1b598fd35948c32fa7ab1eb3629fc3bdb2f8e`
- Tested `candidate.archive.sha256`: `4776c7a11d80a9558df82a620f21bcb7422a43fd91b60c0583413d1f7bd3f136`
- Final fingerprint comparison: `MATCH`

The evidence commit may change only package-excluded acceptance/progress evidence.
Run the canonical release check and `scripts/candidate-fingerprint.sh` again; all
payload and archive hashes above must match the tested candidate exactly. A
mismatch requires a new graphical run. The final commit is bound afterward by
the release-check evidence and tag/Release metadata; this tracked file must not
attempt to contain its own commit SHA.

`ACCEPTED` requires every mandatory core item named in
`phase13-manual-test.md` to pass, no observed failure, exact final fingerprints,
and `BLOCKED` only for that document's explicit non-core allowlist. Any other
combination is `REJECTED`; free-form notes cannot override this rule.

## Outcomes

- Isolated setup and network: `PASS`
- Vanilla lane: `PASS`
- Agent Client lane: `PASS`
- Exact Litematica/MaLiLib lane: `PASS`
- Failure diagnostics: `PASS`
- Runtime disconnect/recovery: `PASS`
- Cleanup and redaction review: `PASS`
- Allowlisted blocked fixtures: processing/unsupported recipe, unknown Item ID,
  multi-chunk structured view, Fabric-rejected missing-dependency profile,
  absence of a safely launchable alternate-version profile, and absence of a
  controlled adapter-linkage or preview-storage failure fixture.
- Mandatory core failures or blockers: none. Bare `/agent` correctly required
  operator authority because it is the status query; ordinary-player
  `/agent say`, `/agent module`, and `/agent ui` flows passed without OP.
- Unresolved failures: none.
- Sanitized evidence reference: maintainer-attested migrated cloud-host session
  on 2026-07-15 UTC using two physical clients. Raw working evidence remained
  outside this migrated Git workspace; the sanitized environment transcript,
  exact candidate fingerprint, and PASS/BLOCKED outcomes were retained in the
  development record.
