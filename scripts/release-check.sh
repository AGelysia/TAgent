#!/usr/bin/env bash
set -euo pipefail
umask 022

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIRST_BUILD="$(mktemp -d "${TMPDIR:-/tmp}/minecraft-agent-release-check.XXXXXX")"
TEST_RESULTS="$FIRST_BUILD/test-results"
DIRTY_OVERRIDE="${MINECRAFT_AGENT_ALLOW_DIRTY_RELEASE_CHECK:-}"
EVIDENCE_DIR="${MINECRAFT_AGENT_RELEASE_EVIDENCE_DIR:-}"
EVIDENCE_ACTIVE=0
JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
STARTED_CLEAN=1
TESTS_STARTED=0

copy_test_results() {
  [[ "$EVIDENCE_ACTIVE" == 1 && "$TESTS_STARTED" == 1 && -d "$EVIDENCE_DIR" ]] \
    || return 0
  if [[ -d "$ROOT/paper-plugin/build/test-results/test" ]]; then
    mkdir -p "$EVIDENCE_DIR/paper"
    cp -R "$ROOT/paper-plugin/build/test-results/test/." "$EVIDENCE_DIR/paper/"
  fi
  if [[ -d "$ROOT/client-mod/build/test-results/test" ]]; then
    mkdir -p "$EVIDENCE_DIR/client"
    cp -R "$ROOT/client-mod/build/test-results/test/." "$EVIDENCE_DIR/client/"
  fi
}

cleanup() {
  local status=$?
  copy_test_results 2>/dev/null || true
  if [[ "$EVIDENCE_ACTIVE" == 1 && -d "$EVIDENCE_DIR" && "$status" != 0 ]]; then
    printf 'release-check result=failed exit_code=%s\n' "$status" \
      >"$EVIDENCE_DIR/release-check.failed"
  fi
  rm -rf "$FIRST_BUILD"
  return "$status"
}
trap cleanup EXIT

cd "$ROOT"

if [[ -n "$EVIDENCE_DIR" ]]; then
  [[ "$EVIDENCE_DIR" == /* && "$EVIDENCE_DIR" != / && "$EVIDENCE_DIR" != *$'\n'* ]] \
    || { printf 'Release evidence path must be a non-root absolute path\n' >&2; exit 1; }
  [[ "$(realpath -m -- "$EVIDENCE_DIR")" == "$EVIDENCE_DIR" \
    && "$EVIDENCE_DIR" != "$ROOT" \
    && "$EVIDENCE_DIR" != "$ROOT"/* ]] \
    || { printf 'Release evidence path must be canonical and outside the repository\n' >&2; exit 1; }
  [[ ! -e "$EVIDENCE_DIR" && ! -L "$EVIDENCE_DIR" ]] \
    || { printf 'Release evidence path must not already exist\n' >&2; exit 1; }
  mkdir -m 0700 "$EVIDENCE_DIR"
  EVIDENCE_ACTIVE=1
  TEST_RESULTS="$EVIDENCE_DIR"
fi

command -v "$JAVA_BIN" >/dev/null 2>&1 \
  || { printf 'Release verification requires Java 21\n' >&2; exit 1; }
command -v node >/dev/null 2>&1 \
  || { printf 'Release verification requires Node.js 22\n' >&2; exit 1; }
command -v npm >/dev/null 2>&1 \
  || { printf 'Release verification requires npm 10 or newer\n' >&2; exit 1; }
JAVA_SPECIFICATION="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^[[:space:]]*java\.specification\.version = //p' \
  | tail -n 1)"
[[ "$JAVA_SPECIFICATION" == 21 ]] \
  || { printf 'Release verification requires Java 21\n' >&2; exit 1; }
node -e '
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major !== 22 || minor < 16) process.exit(1);
' || { printf 'Release verification requires Node.js >=22.16.0 and <23\n' >&2; exit 1; }
node - "$(npm --version)" <<'NODE' \
  || { printf 'Release verification requires npm 10 or newer\n' >&2; exit 1; }
const major = Number(process.argv[2].split(".")[0]);
if (!Number.isSafeInteger(major) || major < 10) process.exit(1);
NODE

if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
  STARTED_CLEAN=0
  if [[ "$DIRTY_OVERRIDE" != I_UNDERSTAND_THIS_IS_NOT_A_RELEASE ]]; then
    printf 'Release verification requires a clean Git worktree\n' >&2
    exit 1
  fi
  printf 'release-check warning=dirty-development-only\n' >&2
fi
if [[ "$STARTED_CLEAN" == 0 && -n "$EVIDENCE_DIR" ]]; then
  printf 'Dirty development checks cannot write release evidence\n' >&2
  exit 1
fi
while IFS= read -r -d '' script; do
  bash -n "$script"
done < <(find "$ROOT/scripts" -maxdepth 1 -type f -name '*.sh' -print0 | sort -z)

mapfile -d '' JSON_FILES < <(
  cd "$ROOT"
  rg --files -0 -g '*.json' -g '!dist/**' -g '!release/**' | sort -z
)
node -e '
  const fs = require("node:fs");
  for (const file of process.argv.slice(1)) {
    JSON.parse(fs.readFileSync(file, "utf8"));
  }
' "${JSON_FILES[@]/#/$ROOT/}"

mkdir -p "$TEST_RESULTS"
TESTS_STARTED=1
MINECRAFT_AGENT_VITEST_JUNIT="$TEST_RESULTS/runtime.xml" \
  "$ROOT/scripts/package.sh"
cp -R "$ROOT/paper-plugin/build/test-results/test" "$TEST_RESULTS/paper"
cp -R "$ROOT/client-mod/build/test-results/test" "$TEST_RESULTS/client"
"$JAVA_BIN" "$ROOT/scripts/VerifyTestResults.java" \
  "$TEST_RESULTS/runtime.xml" \
  "$TEST_RESULTS/paper" \
  "$TEST_RESULTS/client" | tee "$TEST_RESULTS/inventory.txt"
cp "$ROOT/dist/SHA256SUMS" "$FIRST_BUILD/dist.SHA256SUMS"
cp "$ROOT/release/SHA256SUMS" "$FIRST_BUILD/release.SHA256SUMS"
VERSION="$(node -p "require('$ROOT/agent-runtime/package.json').version")"
ARCHIVE="$ROOT/release/MinecraftAgent-${VERSION}.tar.gz"
mkdir "$FIRST_BUILD/archive"
tar -xzf "$ARCHIVE" -C "$FIRST_BUILD/archive"
"$ROOT/scripts/verify-dist.sh" "$FIRST_BUILD/archive/MinecraftAgent-${VERSION}"
cmp \
  "$ROOT/dist/SHA256SUMS" \
  "$FIRST_BUILD/archive/MinecraftAgent-${VERSION}/SHA256SUMS"

"$ROOT/scripts/paper-smoke.sh"
(
  cd "$ROOT/agent-runtime"
  npm audit --audit-level=high
)

MINECRAFT_AGENT_PACKAGE_SKIP_TESTS=1 "$ROOT/scripts/package.sh"
cmp "$FIRST_BUILD/dist.SHA256SUMS" "$ROOT/dist/SHA256SUMS"
cmp "$FIRST_BUILD/release.SHA256SUMS" "$ROOT/release/SHA256SUMS"
git -C "$ROOT" diff --check
if [[ "$STARTED_CLEAN" == 1 && -n "$(git -C "$ROOT" status --porcelain)" ]]; then
  printf 'Git worktree changed while release verification was running\n' >&2
  exit 1
fi
if [[ "$EVIDENCE_ACTIVE" == 1 ]]; then
  PASSED_MARKER="$EVIDENCE_DIR/.release-check.passed.tmp"
  {
    "$ROOT/scripts/candidate-fingerprint.sh"
    printf 'release-check result=passed\n'
  } >"$PASSED_MARKER"
  mv "$PASSED_MARKER" "$EVIDENCE_DIR/release-check.passed"
fi

printf 'release-check result=passed\n'
cat "$ROOT/release/SHA256SUMS"
