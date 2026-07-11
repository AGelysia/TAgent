#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"

"$ROOT/scripts/test.sh"
rm -rf "$DIST"
mkdir -p \
  "$DIST/agent-runtime/scripts" \
  "$DIST/default-capability-packs" \
  "$DIST/protocol"

PAPER_JAR="$(find "$ROOT/paper-plugin/build/libs" -maxdepth 1 -name 'minecraft-agent-paper-*.jar' ! -name '*-sources.jar' -print -quit)"
CLIENT_JAR="$(find "$ROOT/client-mod/build/libs" -maxdepth 1 -name 'minecraft-agent-client-*.jar' ! -name '*-sources.jar' -print -quit)"
cp "$PAPER_JAR" "$DIST/MinecraftAgent-Paper.jar"
cp "$CLIENT_JAR" "$DIST/MinecraftAgent-Client-Fabric.jar"
cp -R "$ROOT/agent-runtime/dist" "$DIST/agent-runtime/dist"
cp "$ROOT/agent-runtime/scripts/version.mjs" "$DIST/agent-runtime/scripts/"
cp "$ROOT/agent-runtime/package.json" "$ROOT/agent-runtime/package-lock.json" "$DIST/agent-runtime/"
cp "$ROOT/agent-runtime/config.example.yml" "$DIST/agent-runtime/"
chmod 0644 "$DIST/agent-runtime/config.example.yml"
cp -R "$ROOT/capability-packs/." "$DIST/default-capability-packs/"
cp -R "$ROOT/protocol/schemas" "$DIST/protocol/schemas"
cp "$ROOT/protocol/README.md" "$DIST/protocol/"
cp "$ROOT/.env.example" "$DIST/"
cp "$ROOT/README.md" "$ROOT/LICENSE" "$DIST/"
cp "$ROOT/start-agent.sh" "$ROOT/start-agent.ps1" "$DIST/"
