# Phase 13 Acceptance Record

This maintainer record is intentionally excluded from the player package. Use
`phase13-manual-test.md` as the immutable checklist template and keep raw working
evidence outside the Git worktree. Add only sanitized outcomes here after every
graphical session for one tested candidate has stopped.

## Candidate binding

- Status: `PENDING` (`PENDING`, `ACCEPTED`, or `REJECTED` only)
- Tested candidate commit:
- Tested `candidate.paper.sha256`:
- Tested `candidate.client.sha256`:
- Tested `candidate.dist-manifest.sha256`:
- Tested `candidate.runtime-manifest.sha256`:
- Tested `candidate.protocol-manifest.sha256`:
- Tested `candidate.archive.sha256`:
- Final fingerprint comparison: `PENDING`

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

- Isolated setup and network: `PENDING`
- Vanilla lane: `PENDING`
- Agent Client lane: `PENDING`
- Exact Litematica/MaLiLib lane: `PENDING`
- Failure diagnostics: `PENDING`
- Runtime disconnect/recovery: `PENDING`
- Cleanup and redaction review: `PENDING`
- Allowlisted blocked fixtures:
- Mandatory core failures or blockers:
- Unresolved failures:
- Sanitized evidence reference:
