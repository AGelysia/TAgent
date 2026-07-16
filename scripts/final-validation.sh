#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

fail() {
  printf 'final-validation: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    'usage:' \
    '  final-validation.sh prepare <absolute-new-evidence-directory>' \
    '  final-validation.sh compare <absolute-existing-evidence-directory>' >&2
  exit 2
}

require_clean_worktree() {
  local status
  status="$(git -C "$ROOT" status --porcelain)" \
    || fail "Git worktree status could not be determined"
  [[ -z "$status" ]] \
    || fail "a clean Git worktree is required"
}

validate_evidence_path() {
  local path=$1
  [[ "$path" == /* && "$path" != / && "$path" != *$'\n'* ]] \
    || fail "the evidence path must be a non-root absolute path"
  [[ "$(realpath -m -- "$path")" == "$path" ]] \
    || fail "the evidence path must be canonical"
  [[ "$path" != "$ROOT" && "$path" != "$ROOT"/* ]] \
    || fail "the evidence directory must remain outside the repository"
}

write_environment() {
  local destination=$1
  local temporary="$destination.tmp"
  {
    date -u '+recorded_at=%Y-%m-%dT%H:%M:%SZ'
    printf 'kernel='
    uname -srm
    printf 'java='
    "$JAVA_BIN" -version 2>&1 | tr '\n' ' ' | sed 's/[[:space:]]*$//'
    printf '\nnode=%s\n' "$(node --version)"
    printf 'npm=%s\n' "$(npm --version)"
  } >"$temporary"
  mv "$temporary" "$destination"
}

fingerprint_value() {
  local file=$1
  local key=$2
  local count
  count="$(awk -F= -v expected="$key" '$1 == expected { count += 1 } END { print count + 0 }' "$file")"
  [[ "$count" == 1 ]] || fail "fingerprint key is missing or duplicated: $key"
  awk -F= -v expected="$key" '$1 == expected { sub(/^[^=]*=/, ""); print }' "$file"
}

compare_payload_fingerprints() {
  local tested=$1
  local current=$2
  local -a keys=(
    candidate.version
    candidate.paper.sha256
    candidate.client.sha256
    candidate.dist-manifest.sha256
    candidate.runtime-manifest.sha256
    candidate.protocol-manifest.sha256
    candidate.archive.sha256
  )
  local key
  for key in "${keys[@]}"; do
    [[ "$(fingerprint_value "$tested" "$key")" == "$(fingerprint_value "$current" "$key")" ]] \
      || fail "final candidate fingerprint mismatch: $key"
  done
}

compare_complete_fingerprints() {
  local tested=$1
  local current=$2
  [[ "$(fingerprint_value "$tested" candidate.commit)" == \
    "$(fingerprint_value "$current" candidate.commit)" ]] \
    || fail "prepared evidence commit does not match its release-check marker"
  compare_payload_fingerprints "$tested" "$current"
}

validate_fingerprint() {
  local fingerprint=$1
  [[ "$(wc -l <"$fingerprint")" == 8 ]] \
    || fail "candidate fingerprint must contain exactly eight lines"
  [[ "$(fingerprint_value "$fingerprint" candidate.commit)" =~ ^[0-9a-f]{40}$ ]] \
    || fail "candidate commit is malformed"
  [[ "$(fingerprint_value "$fingerprint" candidate.version)" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] \
    || fail "candidate version is malformed"
  local key
  for key in \
    candidate.paper.sha256 \
    candidate.client.sha256 \
    candidate.dist-manifest.sha256 \
    candidate.runtime-manifest.sha256 \
    candidate.protocol-manifest.sha256 \
    candidate.archive.sha256; do
    [[ "$(fingerprint_value "$fingerprint" "$key")" =~ ^[0-9a-f]{64}$ ]] \
      || fail "candidate hash is malformed: $key"
  done
}

require_private_file() {
  local path=$1
  local label=$2
  [[ -f "$path" && ! -L "$path" ]] || fail "$label is missing or unsafe"
  [[ "$(stat -c '%u' "$path")" == "$EUID" ]] || fail "$label has an unexpected owner"
  local mode
  mode="$(stat -c '%a' "$path")"
  (((8#$mode & 8#077) == 0)) || fail "$label is accessible by group or other users"
}

require_private_directory() {
  local path=$1
  local label=$2
  [[ -d "$path" && ! -L "$path" ]] || fail "$label is missing or unsafe"
  [[ "$(stat -c '%u' "$path")" == "$EUID" ]] || fail "$label has an unexpected owner"
  local mode
  mode="$(stat -c '%a' "$path")"
  (((8#$mode & 8#077) == 0)) || fail "$label is accessible by group or other users"
}

verify_prepared_evidence() {
  local evidence=$1
  local tested="$evidence/tested-fingerprint.txt"
  local initial_release="$evidence/release.SHA256SUMS"
  local passed="$evidence/release-check/release-check.passed"

  require_private_directory "$evidence" "evidence directory"
  require_private_directory "$evidence/release-check" "release-check evidence directory"
  [[ ! -e "$evidence/release-check/release-check.failed" \
    && ! -L "$evidence/release-check/release-check.failed" ]] \
    || fail "release-check evidence contains a failure marker"
  require_private_file "$tested" "tested-fingerprint.txt"
  require_private_file "$initial_release" "release.SHA256SUMS"
  require_private_file "$passed" "release-check.passed"
  require_private_file "$evidence/environment.txt" "environment.txt"
  require_private_file "$evidence/cloud-validation-checklist.md" "cloud validation checklist"
  require_private_file \
    "$evidence/final-acceptance-record.template.md" \
    "final acceptance record template"

  validate_fingerprint "$tested"
  [[ "$(grep -Fxc 'release-check result=passed' "$passed")" == 1 \
    && "$(wc -l <"$passed")" == 9 ]] \
    || fail "release-check.passed is incomplete or malformed"
  compare_complete_fingerprints "$tested" "$passed"

  [[ "$(wc -l <"$initial_release")" == 3 ]] \
    || fail "release.SHA256SUMS must contain exactly three artifacts"
  local version
  version="$(fingerprint_value "$tested" candidate.version)"
  local -a manifest_entries=(
    "$(fingerprint_value "$tested" candidate.client.sha256)  MinecraftAgent-Client-Fabric.jar"
    "$(fingerprint_value "$tested" candidate.paper.sha256)  MinecraftAgent-Paper.jar"
    "$(fingerprint_value "$tested" candidate.archive.sha256)  MinecraftAgent-${version}.tar.gz"
  )
  local entry
  for entry in "${manifest_entries[@]}"; do
    [[ "$(grep -Fxc -- "$entry" "$initial_release")" == 1 ]] \
      || fail "release.SHA256SUMS does not match tested-fingerprint.txt"
  done
}

verify_post_candidate_changes() {
  local tested_commit=$1
  git -C "$ROOT" cat-file -e "${tested_commit}^{commit}" 2>/dev/null \
    || fail "tested candidate commit is unavailable"
  git -C "$ROOT" merge-base --is-ancestor "$tested_commit" HEAD \
    || fail "final HEAD does not descend from the tested candidate commit"
  local merge_count
  merge_count="$(git -C "$ROOT" rev-list --count --merges "${tested_commit}..HEAD")" \
    || fail "post-candidate commit history could not be inspected"
  [[ "$merge_count" == 0 ]] \
    || fail "post-candidate evidence history must remain linear"
  local changes
  changes="$(git -C "$ROOT" -c core.quotePath=true log --format= --name-status \
    --no-renames "${tested_commit}..HEAD")" \
    || fail "post-candidate changes could not be inspected"
  local line
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    case "$line" in
      $'M\tdocs/phase14-final-acceptance-record.md' | $'M\tdocs/progress.md')
        ;;
      *)
        fail "post-candidate commit changed a non-evidence path: $line"
        ;;
    esac
  done <<<"$changes"
}

prepare() {
  local evidence=$1
  validate_evidence_path "$evidence"
  [[ ! -e "$evidence" && ! -L "$evidence" ]] \
    || fail "the prepare evidence directory must not already exist"
  require_clean_worktree
  command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "Java was not found"
  command -v node >/dev/null 2>&1 || fail "Node.js was not found"
  command -v npm >/dev/null 2>&1 || fail "npm was not found"

  mkdir -m 0700 "$evidence"
  write_environment "$evidence/environment.txt"
  MINECRAFT_AGENT_RELEASE_EVIDENCE_DIR="$evidence/release-check" \
    "$ROOT/scripts/release-check.sh"
  "$ROOT/scripts/candidate-fingerprint.sh" >"$evidence/tested-fingerprint.txt.tmp"
  mv "$evidence/tested-fingerprint.txt.tmp" "$evidence/tested-fingerprint.txt"
  cp "$ROOT/release/SHA256SUMS" "$evidence/release.SHA256SUMS"
  cp "$ROOT/docs/phase14-cloud-validation.md" "$evidence/cloud-validation-checklist.md"
  cp "$ROOT/docs/phase14-final-acceptance-record.md" \
    "$evidence/final-acceptance-record.template.md"
  find "$evidence" -type d -exec chmod 0700 {} +
  find "$evidence" -type f -exec chmod 0600 {} +
  find "$evidence/release-check" -type f -exec chmod 0400 {} +
  chmod 0400 \
    "$evidence/environment.txt" \
    "$evidence/release.SHA256SUMS" \
    "$evidence/tested-fingerprint.txt"
  verify_prepared_evidence "$evidence"

  printf 'final-validation mode=prepare result=passed\n'
  printf 'Next: deploy the exact release archive and complete the copied checklist.\n'
}

compare() {
  local evidence=$1
  validate_evidence_path "$evidence"
  [[ -d "$evidence" && ! -L "$evidence" ]] \
    || fail "the compare evidence directory must be a real directory"
  local tested="$evidence/tested-fingerprint.txt"
  local initial_release="$evidence/release.SHA256SUMS"
  local final="$evidence/final-fingerprint.txt"
  [[ ! -e "$final" && ! -L "$final" ]] \
    || fail "final-fingerprint.txt already exists; evidence is append-only"
  verify_prepared_evidence "$evidence"
  verify_post_candidate_changes "$(fingerprint_value "$tested" candidate.commit)"
  require_clean_worktree

  local tested_snapshot
  local release_snapshot
  local temporary
  tested_snapshot="$(mktemp "$evidence/.tested-fingerprint.XXXXXX")"
  release_snapshot="$(mktemp "$evidence/.release-manifest.XXXXXX")"
  temporary="$(mktemp "$evidence/.final-fingerprint.XXXXXX")"
  trap 'rm -f -- "$tested_snapshot" "$release_snapshot" "$temporary"' EXIT
  cp "$tested" "$tested_snapshot"
  cp "$initial_release" "$release_snapshot"
  chmod 0400 "$tested_snapshot" "$release_snapshot"

  "$ROOT/scripts/release-check.sh"
  cmp -s "$tested_snapshot" "$tested" \
    || fail "tested-fingerprint.txt changed during final verification"
  cmp -s "$release_snapshot" "$initial_release" \
    || fail "release.SHA256SUMS changed during final verification"
  verify_prepared_evidence "$evidence"
  verify_post_candidate_changes "$(fingerprint_value "$tested_snapshot" candidate.commit)"
  "$ROOT/scripts/candidate-fingerprint.sh" >"$temporary"
  compare_payload_fingerprints "$tested_snapshot" "$temporary"
  cmp -s "$release_snapshot" "$ROOT/release/SHA256SUMS" \
    || fail "final release manifest differs from the tested candidate"
  printf 'final.release-manifest.sha256=%s\n' \
    "$(sha256sum "$ROOT/release/SHA256SUMS" | cut -d ' ' -f 1)" >>"$temporary"
  printf 'final-validation.result=passed\n' >>"$temporary"
  chmod 0400 "$temporary"
  ln "$temporary" "$final" \
    || fail "final-fingerprint.txt appeared concurrently; evidence was not overwritten"
  rm -f -- "$tested_snapshot" "$release_snapshot" "$temporary"
  trap - EXIT

  printf 'final-validation mode=compare result=passed\n'
  printf 'All version and payload fingerprints match the tested candidate.\n'
}

(($# == 2)) || usage
MODE=$1
EVIDENCE=$2
case "$MODE" in
  prepare)
    prepare "$EVIDENCE"
    ;;
  compare)
    compare "$EVIDENCE"
    ;;
  *)
    usage
    ;;
esac
