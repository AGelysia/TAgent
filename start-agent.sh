#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$ROOT/agent-runtime"
if [[ ! -d node_modules ]]; then
  printf '%s\n' "Runtime dependencies are missing. Run: npm ci --omit=dev" >&2
  exit 1
fi
exec npm start
