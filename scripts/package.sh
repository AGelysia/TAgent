#!/usr/bin/env bash
set -euo pipefail
umask 022

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
RELEASE="$ROOT/release"
VERSION="$(node -p "require('$ROOT/agent-runtime/package.json').version")"
SKIP_TESTS="${MINECRAFT_AGENT_PACKAGE_SKIP_TESTS:-0}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  printf 'Runtime package version is not release-safe: %s\n' "$VERSION" >&2
  exit 1
fi
if [[ "$SKIP_TESTS" != 0 && "$SKIP_TESTS" != 1 ]]; then
  printf 'MINECRAFT_AGENT_PACKAGE_SKIP_TESTS must be 0 or 1\n' >&2
  exit 1
fi

cd "$ROOT"
rm -rf "$ROOT/agent-runtime/dist"
"$ROOT/gradlew" --no-daemon --max-workers=1 --no-build-cache clean
if [[ "$SKIP_TESTS" == 1 ]]; then
  (
    cd "$ROOT/agent-runtime"
    npm ci --prefer-offline
    npm run build
  )
  "$ROOT/gradlew" --no-daemon --max-workers=1 --no-build-cache :paper-plugin:assemble
  "$ROOT/gradlew" --no-daemon --max-workers=1 --no-build-cache :client-mod:assemble
else
  MINECRAFT_AGENT_NO_BUILD_CACHE=1 "$ROOT/scripts/test.sh"
fi

rm -rf "$DIST" "$RELEASE"
mkdir -p \
  "$DIST/agent-runtime/scripts" \
  "$DIST/default-capability-packs" \
  "$DIST/docs" \
  "$DIST/protocol" \
  "$RELEASE"

PAPER_JAR="$ROOT/paper-plugin/build/libs/minecraft-agent-paper-${VERSION}.jar"
CLIENT_JAR="$ROOT/client-mod/build/libs/minecraft-agent-client-${VERSION}.jar"
if [[ ! -s "$PAPER_JAR" || ! -s "$CLIENT_JAR" ]]; then
  printf 'Expected versioned release JARs were not produced for %s\n' "$VERSION" >&2
  exit 1
fi
cp "$PAPER_JAR" "$DIST/MinecraftAgent-Paper.jar"
cp "$CLIENT_JAR" "$DIST/MinecraftAgent-Client-Fabric.jar"
cp -R "$ROOT/agent-runtime/dist" "$DIST/agent-runtime/dist"
cp "$ROOT/agent-runtime/scripts/version.mjs" "$DIST/agent-runtime/scripts/"
cp "$ROOT/agent-runtime/package.json" "$ROOT/agent-runtime/package-lock.json" "$DIST/agent-runtime/"
cp "$ROOT/agent-runtime/config.example.yml" "$DIST/agent-runtime/"
cp "$ROOT/agent-runtime/config.example.yml" "$DIST/config.example.yml"
chmod 0644 "$DIST/agent-runtime/config.example.yml"
cp -R "$ROOT/capability-packs/." "$DIST/default-capability-packs/"
cp -R "$ROOT/protocol/schemas" "$DIST/protocol/schemas"
cp "$ROOT/protocol/README.md" "$DIST/protocol/"
cp "$ROOT/.env.example" "$DIST/"
cp "$ROOT/docs/operations.md" "$ROOT/docs/phase13-manual-test.md" "$DIST/docs/"
cp \
  "$ROOT/README.md" \
  "$ROOT/LICENSE" \
  "$ROOT/SECURITY.md" \
  "$ROOT/CLIENT-COMPATIBILITY.md" \
  "$DIST/"
cp "$ROOT/start-agent.sh" "$ROOT/start-agent.ps1" "$DIST/"

find "$DIST" -type d -exec chmod 0755 {} +
find "$DIST" -type f -exec chmod 0644 {} +
chmod 0755 "$DIST/start-agent.sh"

(
  cd "$DIST"
  find . -type f ! -name SHA256SUMS -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum >SHA256SUMS
)

"$ROOT/scripts/verify-dist.sh" "$DIST"

cp "$DIST/MinecraftAgent-Paper.jar" "$RELEASE/"
cp "$DIST/MinecraftAgent-Client-Fabric.jar" "$RELEASE/"
tar \
  --sort=name \
  --mtime='UTC 1980-01-01' \
  --owner=0 \
  --group=0 \
  --numeric-owner \
  --transform="s,^\.,MinecraftAgent-${VERSION}," \
  -cf - \
  -C "$DIST" . \
  | gzip -n >"$RELEASE/MinecraftAgent-${VERSION}.tar.gz"
(
  cd "$RELEASE"
  LC_ALL=C sha256sum \
    MinecraftAgent-Client-Fabric.jar \
    MinecraftAgent-Paper.jar \
    "MinecraftAgent-${VERSION}.tar.gz" >SHA256SUMS
)
