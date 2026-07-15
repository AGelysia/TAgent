# ADR 0011: Gated Phase 13 Release Candidate

## Status

Accepted for the `0.1.0` preview-only candidate.

## Context

The implemented product can answer privately, expose bounded read tools, render
structured client views, and create a Paper-authoritative Litematica preview. It
still has an empty production write catalog. Unit and headless integration tests
cannot establish real registry rendering, a real Fabric handshake, or compatibility
with Litematica's released UI artifacts.

The Paper API coordinate is a mutable snapshot. Old JVM and TypeScript output can
also remain in build directories after a version or source change. A package that
merely copies the first matching JAR cannot be treated as a release candidate.

## Decision

Phase 13 has two independent gates.

The automated gate:

- requires one clean commit at entry and confirms the worktree remains clean at
  exit;
- pins Gradle-resolved downloaded artifacts by SHA-256, including the resolved
  Paper API snapshot, while using one exact four-field trust entry for each of the
  48 current Loom-local generated JARs whose bytes are not reproducible; their POMs
  and Gradle-resolved inputs stay pinned, while Loom verifies direct Minecraft and
  mappings downloads against Mojang manifest SHA-1 values;
- starts from clean TypeScript and JVM output and disables Gradle build caching;
- runs Runtime, Paper, Fabric, shared contract, MockBukkit, deterministic
  Capability fuzz, formatting, pinned real-Paper smoke, and npm audit checks,
  with required-suite and minimum-test inventory assertions;
- requires Runtime, lockfile, Paper descriptor/manifest, and Fabric metadata to
  use one non-SNAPSHOT version;
- builds twice in the same pinned lane and compares the complete installation and
  upload-artifact checksums;
- rejects links, special files, unsafe modes, private state, credential patterns,
  missing schemas, unexpected JAR contents, and incomplete checksums; and
- uploads only a temporary GitHub Actions artifact. No workflow creates a tag or
  GitHub Release.

The manual gate uses three isolated client profiles: Vanilla, Agent Client only,
and Agent Client with the exact supported Litematica/MaLiLib tuple. A repository-only
deterministic provider drives fixed text, authoritative recipe, project, and build
preview flows. Each invocation rebuilds the same clean commit, stages the complete
candidate in a fresh session, then injects the provider beside that staged
Runtime. The provider remains excluded from the release package. The manual
server remains authenticated and exactly whitelisted; Runtime stays on loopback.

Manual acceptance is deterministic: any observed failure, fingerprint mismatch,
or blocked core privacy/base-client/exact-Litematica/recovery item rejects the
candidate. Only predeclared non-core fixture gaps may remain `BLOCKED`: recipe
processing/unsupported layouts, unknown Item ID, multi-chunk transfer, and safely
unreachable optional-dependency/adapter failure diagnostics. No free-form status
can waive a core row.

Linux `scripts/release-check.sh` is the canonical candidate builder. The PowerShell
path is exercised as development orchestration on the POSIX security boundary but
does not establish native Windows release equivalence.

## Consequences

- An upstream snapshot change fails closed. If the pinned snapshot disappears, a
  cold build is unavailable until an explicitly reviewed dependency update; it
  may not silently accept the replacement.
- Two uncached builds prove determinism in the recorded toolchain and host lane,
  not across arbitrary future JDK or operating-system versions.
- The preview-only candidate may proceed without adding any world-write adapter.
  Its absence must remain explicit in release notes and security documentation.
- Phase 13 and public release remain incomplete until the graphical checklist has
  acceptable evidence. A blocked fixture remains `BLOCKED`, not a pass.
