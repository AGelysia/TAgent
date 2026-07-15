#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
RELEASE="$ROOT/release"

"$ROOT/scripts/verify-dist.sh" "$DIST" >/dev/null
VERSION="$(node -p "require('$ROOT/agent-runtime/package.json').version")"
ARCHIVE="$RELEASE/MinecraftAgent-${VERSION}.tar.gz"
[[ -f "$ARCHIVE" && ! -L "$ARCHIVE" ]] \
  || { printf 'candidate-fingerprint: release archive is missing\n' >&2; exit 1; }

hash_file() {
  sha256sum "$1" | cut -d ' ' -f 1
}

hash_manifest_subset() {
  local prefix=$1
  local matches
  matches="$(sed -n "\\#  ./$prefix/#p" "$DIST/SHA256SUMS")"
  [[ -n "$matches" ]] \
    || { printf 'candidate-fingerprint: manifest subset is empty: %s\n' "$prefix" >&2; exit 1; }
  printf '%s\n' "$matches" | sha256sum | cut -d ' ' -f 1
}

printf 'candidate.commit=%s\n' "$(git -C "$ROOT" rev-parse --verify HEAD)"
printf 'candidate.version=%s\n' "$VERSION"
printf 'candidate.paper.sha256=%s\n' "$(hash_file "$DIST/MinecraftAgent-Paper.jar")"
printf 'candidate.client.sha256=%s\n' "$(hash_file "$DIST/MinecraftAgent-Client-Fabric.jar")"
printf 'candidate.dist-manifest.sha256=%s\n' "$(hash_file "$DIST/SHA256SUMS")"
printf 'candidate.runtime-manifest.sha256=%s\n' "$(hash_manifest_subset agent-runtime)"
printf 'candidate.protocol-manifest.sha256=%s\n' "$(hash_manifest_subset protocol)"
printf 'candidate.archive.sha256=%s\n' "$(hash_file "$ARCHIVE")"
