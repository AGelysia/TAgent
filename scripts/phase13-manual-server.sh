#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_ROOT="$ROOT/run/phase13-manual"
BIND_ADDRESS="${PHASE13_BIND_ADDRESS:-127.0.0.1}"
SERVER_PORT="${PHASE13_SERVER_PORT:-25565}"
PLAYER_NAMES="${PHASE13_PLAYER_NAMES:-}"
PREVIEW_ENABLED="${PHASE13_PREVIEW_ENABLED:-false}"
JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
PAPER_BUILD=132
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PAPER_URL="https://fill-data.papermc.io/v1/objects/${PAPER_SHA256}/paper-1.21.11-${PAPER_BUILD}.jar"
CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/minecraft-agent-phase13"
PAPER_JAR="$CACHE_ROOT/paper-1.21.11-${PAPER_BUILD}.jar"
SESSION_ROOT=""
SERVER_ROOT=""
RUNTIME_ROOT=""
CANDIDATE_ROOT=""
RUNTIME_APP=""
RUNTIME_PID=""
RUNTIME_LOG=""
RUNTIME_GENERATION=0
TOKEN=""
PAPER_PID=""
TAIL_PID=""
CONSOLE_OPEN=0
SHUTDOWN_REQUESTED=0

fail() {
  printf 'phase13-manual-server: %s\n' "$*" >&2
  exit 1
}

valid_ipv4() {
  local address=$1
  local -a octets
  IFS=. read -r -a octets <<<"$address"
  ((${#octets[@]} == 4)) || return 1
  local octet
  for octet in "${octets[@]}"; do
    [[ "$octet" =~ ^[0-9]{1,3}$ ]] || return 1
    ((10#$octet <= 255)) || return 1
  done
}

wait_for_log() {
  local log_file=$1
  local pattern=$2
  local timeout_seconds=$3
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    if [[ -f "$log_file" ]] && rg -q "$pattern" "$log_file"; then
      return 0
    fi
    sleep 1
  done
  printf 'Timed out waiting for %s\n' "$pattern" >&2
  [[ -f "$log_file" ]] && tail -n 120 "$log_file" >&2
  return 1
}

stop_process() {
  local process_id=$1
  local timeout_seconds=$2
  local deadline=$((SECONDS + timeout_seconds))
  while kill -0 "$process_id" 2>/dev/null && ((SECONDS < deadline)); do
    sleep 1
  done
  if kill -0 "$process_id" 2>/dev/null; then
    kill -TERM "$process_id" 2>/dev/null || true
    sleep 2
  fi
  if kill -0 "$process_id" 2>/dev/null; then
    kill -KILL "$process_id" 2>/dev/null || true
  fi
  wait "$process_id" 2>/dev/null || true
}

wait_for_clean_exit() {
  local process_id=$1
  local timeout_seconds=$2
  local deadline=$((SECONDS + timeout_seconds))
  while kill -0 "$process_id" 2>/dev/null && ((SECONDS < deadline)); do
    sleep 1
  done
  if kill -0 "$process_id" 2>/dev/null; then
    kill -TERM "$process_id" 2>/dev/null || true
    sleep 2
    kill -KILL "$process_id" 2>/dev/null || true
    wait "$process_id" 2>/dev/null || true
    return 124
  fi
  local status=0
  wait "$process_id" 2>/dev/null || status=$?
  return "$status"
}

start_runtime() {
  if [[ -n "$RUNTIME_PID" ]] && kill -0 "$RUNTIME_PID" 2>/dev/null; then
    fail "the candidate Runtime is already running"
  fi
  ((++RUNTIME_GENERATION))
  RUNTIME_LOG="$RUNTIME_ROOT/runtime.$RUNTIME_GENERATION.log"
  : >"$RUNTIME_LOG"
  MINECRAFT_AGENT_SERVER_TOKEN="$TOKEN" \
    OPENAI_API_KEY=phase13-manual-provider-key-not-transmitted \
    node "$RUNTIME_APP/scripts/phase13-manual-runtime.mjs" \
      --config "$RUNTIME_ROOT/config.local.yml" >"$RUNTIME_LOG" 2>&1 &
  RUNTIME_PID=$!
  wait_for_log "$RUNTIME_LOG" '^PHASE13_MANUAL_RUNTIME_READY$' 30 \
    || fail "the candidate Runtime did not become ready"
}

stop_runtime() {
  if [[ -n "$RUNTIME_PID" ]] && kill -0 "$RUNTIME_PID" 2>/dev/null; then
    kill -TERM "$RUNTIME_PID" 2>/dev/null || true
    stop_process "$RUNTIME_PID" 15
  fi
  RUNTIME_PID=""
}

access_lists_are_exact() {
  PHASE13_EXPECTED_PLAYER_NAMES="$PLAYER_NAMES" \
    node - "$SERVER_ROOT/whitelist.json" "$SERVER_ROOT/ops.json" <<'NODE'
const fs = require("node:fs");
const expected = process.env.PHASE13_EXPECTED_PLAYER_NAMES
  .split(",")
  .map((name) => name.toLowerCase())
  .sort();
const whitelist = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const ops = fs.existsSync(process.argv[3])
  ? JSON.parse(fs.readFileSync(process.argv[3], "utf8"))
  : [];
const actual = whitelist.map((entry) => entry.name.toLowerCase()).sort();
if (
  new Set(expected).size !== expected.length ||
  actual.length !== expected.length ||
  actual.some((name, index) => name !== expected[index]) ||
  !Array.isArray(ops) ||
  ops.length !== 0
) {
  process.exit(1);
}
NODE
}

cleanup() {
  if [[ -n "$TAIL_PID" ]] && kill -0 "$TAIL_PID" 2>/dev/null; then
    kill "$TAIL_PID" 2>/dev/null || true
    wait "$TAIL_PID" 2>/dev/null || true
  fi
  if [[ -n "$PAPER_PID" ]] && kill -0 "$PAPER_PID" 2>/dev/null; then
    if ((CONSOLE_OPEN == 1)); then
      printf 'stop\n' >&9 2>/dev/null || true
    fi
    stop_process "$PAPER_PID" 20
  fi
  if ((CONSOLE_OPEN == 1)); then
    exec 9>&- 2>/dev/null || true
    exec 9<&- 2>/dev/null || true
  fi
  stop_runtime
  if [[ -n "$SESSION_ROOT" && "$SESSION_ROOT" == "$WORK_ROOT"/session.* ]]; then
    rm -rf "$SESSION_ROOT"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "Java was not found; set JAVA_HOME to JDK 21"
command -v node >/dev/null 2>&1 || fail "Node.js was not found"
command -v npm >/dev/null 2>&1 || fail "npm was not found"
command -v openssl >/dev/null 2>&1 || fail "openssl was not found"
command -v rg >/dev/null 2>&1 || fail "rg was not found"
command -v diff >/dev/null 2>&1 || fail "diff was not found"
JAVA_SPECIFICATION="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^[[:space:]]*java\.specification\.version = //p' \
  | tail -n 1)"
[[ "$JAVA_SPECIFICATION" == 21 ]] || fail "the manual lane requires Java 21"
node -e '
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major !== 22 || minor < 16) process.exit(1);
' || fail "the manual lane requires Node.js >=22.16.0 and <23"
node - "$(npm --version)" <<'NODE' \
  || fail "the manual lane requires npm 10 or newer"
const major = Number(process.argv[2].split(".")[0]);
if (!Number.isSafeInteger(major) || major < 10) process.exit(1);
NODE
valid_ipv4 "$BIND_ADDRESS" || fail "PHASE13_BIND_ADDRESS must be one explicit IPv4 address"
[[ "$BIND_ADDRESS" != 0.0.0.0 ]] || fail "wildcard network binding is forbidden"
[[ "$SERVER_PORT" =~ ^[0-9]{1,5}$ ]] \
  && ((10#$SERVER_PORT >= 1024 && 10#$SERVER_PORT <= 65535)) \
  || fail "PHASE13_SERVER_PORT must be between 1024 and 65535"
[[ "$PREVIEW_ENABLED" == true || "$PREVIEW_ENABLED" == false ]] \
  || fail "PHASE13_PREVIEW_ENABLED must be true or false"
[[ -n "$PLAYER_NAMES" ]] || fail "PHASE13_PLAYER_NAMES is required"
if [[ "$BIND_ADDRESS" != 127.0.0.1 \
  && "${PHASE13_CONFIRM_NETWORK_EXPOSURE:-}" != I_HAVE_RESTRICTED_THE_FIREWALL ]]; then
  fail "set PHASE13_CONFIRM_NETWORK_EXPOSURE=I_HAVE_RESTRICTED_THE_FIREWALL after restricting TCP access"
fi

IFS=, read -r -a WHITELIST_NAMES <<<"$PLAYER_NAMES"
for player_name in "${WHITELIST_NAMES[@]}"; do
  [[ "$player_name" =~ ^[A-Za-z0-9_]{3,16}$ ]] \
    || fail "every PHASE13_PLAYER_NAMES entry must be a canonical Minecraft account name"
done

git -C "$ROOT" ls-files --error-unmatch \
  scripts/phase13-manual-server.sh \
  agent-runtime/scripts/phase13-manual-runtime.mjs >/dev/null \
  || fail "the manual harness must be tracked by Git"
[[ -z "$(git -C "$ROOT" status --porcelain)" ]] \
  || fail "the manual harness requires a clean Git worktree"
"$ROOT/scripts/release-check.sh"
"$ROOT/scripts/verify-dist.sh" "$ROOT/dist"
CANDIDATE_COMMIT="$(git -C "$ROOT" rev-parse --verify HEAD)"
[[ ! -e "$ROOT/run" || -d "$ROOT/run" && ! -L "$ROOT/run" ]] \
  || fail "the repository run path must be a real directory"
[[ ! -e "$WORK_ROOT" || -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] \
  || fail "the Phase 13 work path must be a real directory"
[[ ! -e "$CACHE_ROOT" || -d "$CACHE_ROOT" && ! -L "$CACHE_ROOT" ]] \
  || fail "the Phase 13 cache path must be a real directory"
mkdir -p "$WORK_ROOT" "$CACHE_ROOT"
chmod 0700 "$WORK_ROOT" "$CACHE_ROOT"
SESSION_ROOT="$(mktemp -d "$WORK_ROOT/session.XXXXXX")"
SERVER_ROOT="$SESSION_ROOT/server"
RUNTIME_ROOT="$SESSION_ROOT/runtime"
mkdir -p "$SERVER_ROOT/plugins/MinecraftAgent" "$RUNTIME_ROOT"
[[ ! -L "$WORK_ROOT" \
  && ! -L "$SESSION_ROOT" \
  && ! -L "$SERVER_ROOT" \
  && ! -L "$SERVER_ROOT/plugins" \
  && ! -L "$SERVER_ROOT/plugins/MinecraftAgent" \
  && ! -L "$RUNTIME_ROOT" ]] \
  || fail "manual test roots must not be symlinks"
for managed_file in \
  "$SERVER_ROOT/plugins/MinecraftAgent-Paper.jar" \
  "$SERVER_ROOT/plugins/MinecraftAgent/config.yml" \
  "$SERVER_ROOT/eula.txt" \
  "$SERVER_ROOT/server.properties" \
  "$SESSION_ROOT/console.input" \
  "$SESSION_ROOT/paper.log" \
  "$PAPER_JAR" \
  "$PAPER_JAR.tmp"; do
  [[ ! -L "$managed_file" ]] || fail "managed manual-test files must not be symlinks"
done
chmod 0700 \
  "$SESSION_ROOT" \
  "$SERVER_ROOT" \
  "$SERVER_ROOT/plugins" \
  "$SERVER_ROOT/plugins/MinecraftAgent" \
  "$RUNTIME_ROOT"
RUNTIME_APP="$RUNTIME_ROOT/app"
CANDIDATE_ROOT="$RUNTIME_ROOT/candidate"
cp -R "$ROOT/dist" "$CANDIDATE_ROOT"
diff -qr "$ROOT/dist" "$CANDIDATE_ROOT" >/dev/null \
  || fail "the copied candidate package differs before installation"
CANDIDATE_FINGERPRINT="$("$ROOT/scripts/candidate-fingerprint.sh")"
diff -qr "$ROOT/dist" "$CANDIDATE_ROOT" >/dev/null \
  || fail "the staged candidate changed while its fingerprint was recorded"
RUNTIME_APP="$CANDIDATE_ROOT/agent-runtime"
cp "$ROOT/agent-runtime/scripts/phase13-manual-runtime.mjs" \
  "$RUNTIME_APP/scripts/phase13-manual-runtime.mjs"
chmod 0600 "$RUNTIME_APP/scripts/phase13-manual-runtime.mjs"
(
  cd "$RUNTIME_APP"
  npm ci --omit=dev --ignore-scripts --no-audit --no-fund --prefer-offline
)
diff -qr "$ROOT/dist/agent-runtime/dist" "$RUNTIME_APP/dist" >/dev/null \
  || fail "npm installation changed the candidate Runtime code"
diff -qr "$ROOT/dist/protocol" "$CANDIDATE_ROOT/protocol" >/dev/null \
  || fail "npm installation changed the candidate protocol tree"
cmp -s "$ROOT/dist/agent-runtime/package.json" "$RUNTIME_APP/package.json" \
  || fail "npm installation changed the candidate package manifest"
cmp -s "$ROOT/dist/agent-runtime/package-lock.json" "$RUNTIME_APP/package-lock.json" \
  || fail "npm installation changed the candidate package lock"

if [[ -f "$PAPER_JAR" ]] \
  && ! printf '%s  %s\n' "$PAPER_SHA256" "$PAPER_JAR" | sha256sum --check --status; then
  fail "cached Paper JAR failed its pinned SHA-256"
fi
if [[ ! -f "$PAPER_JAR" ]]; then
  curl --fail --location --retry 3 --output "$PAPER_JAR.tmp" "$PAPER_URL"
  printf '%s  %s\n' "$PAPER_SHA256" "$PAPER_JAR.tmp" | sha256sum --check --status \
    || fail "downloaded Paper JAR failed its pinned SHA-256"
  mv "$PAPER_JAR.tmp" "$PAPER_JAR"
  chmod 0600 "$PAPER_JAR"
fi

find "$SERVER_ROOT/plugins" -maxdepth 1 -type f ! -name MinecraftAgent-Paper.jar -print -quit \
  | grep -q . \
  && fail "the manual server plugin directory contains an unexpected JAR"
find "$SERVER_ROOT/plugins" -maxdepth 1 -type l -print -quit | grep -q . \
  && fail "the manual server plugin directory contains a symlink"
cp "$CANDIDATE_ROOT/MinecraftAgent-Paper.jar" \
  "$SERVER_ROOT/plugins/MinecraftAgent-Paper.jar"
chmod 0600 "$SERVER_ROOT/plugins/MinecraftAgent-Paper.jar"
cat >"$SERVER_ROOT/plugins/MinecraftAgent/config.yml" <<'CONFIG'
config-version: 1
server:
  id: survival-main
owners: []
runtime:
  url: ws://127.0.0.1:38127/agent
  server-token: ${MINECRAFT_AGENT_SERVER_TOKEN}
  connect-timeout-millis: 2000
  handshake-timeout-millis: 2000
state:
  directory: state
security:
  policy-version: 1
  world-write: OP
  player-write: OP
  server-admin: OWNER
  allow-op-toggle: false
capabilities:
  directory: capabilities
  approvals: []
CONFIG
chmod 0600 "$SERVER_ROOT/plugins/MinecraftAgent/config.yml"
printf 'eula=true\n' >"$SERVER_ROOT/eula.txt"
printf '%s\n' \
  "server-ip=$BIND_ADDRESS" \
  "server-port=$SERVER_PORT" \
  'online-mode=true' \
  'white-list=true' \
  'enforce-whitelist=true' \
  'enable-rcon=false' \
  'enable-query=false' \
  'broadcast-rcon-to-ops=false' \
  'max-players=4' \
  'view-distance=4' \
  'simulation-distance=4' \
  'spawn-protection=0' \
  'gamemode=creative' \
  'force-gamemode=true' \
  'difficulty=peaceful' \
  'level-type=minecraft:flat' \
  'generate-structures=false' \
  'sync-chunk-writes=false' \
  'motd=Minecraft Agent Phase 13 isolated acceptance' >"$SERVER_ROOT/server.properties"

cat >"$RUNTIME_ROOT/config.local.yml" <<'CONFIG'
configVersion: 2
server:
  id: survival-main
transport:
  host: 127.0.0.1
  port: 38127
  serverToken: ${MINECRAFT_AGENT_SERVER_TOKEN}
model:
  provider: openai
  apiKey: ${OPENAI_API_KEY}
  model: phase13-manual-provider
  timeoutSeconds: 30
  inputMicroUsdPerMillionTokens: 1
  outputMicroUsdPerMillionTokens: 1
storage:
  sqlitePath: ./data/runtime.db
logging:
  directory: ./logs
  level: info
knowledge:
  roots: []
limits:
  maxConcurrentRequests: 1
  maxQueuedRequests: 4
  maxToolRounds: 4
  maxContextMessages: 8
  maxContextCharacters: 8192
  perPlayerCooldownSeconds: 0
  dailyRequestsPerPlayer: 100
  monthlyBudgetUsd: 1
  providerRoundReservationMicroUsd: 1000
privacy:
  storeConversations: false
  retentionDays: 0
  logMessageContent: false
  logToolCalls: false
CONFIG
chmod 0600 "$RUNTIME_ROOT/config.local.yml"

TOKEN="$(openssl rand -hex 32)"
start_runtime

CONSOLE_FIFO="$SESSION_ROOT/console.input"
rm -f "$CONSOLE_FIFO"
mkfifo -m 0600 "$CONSOLE_FIFO"
exec 9<>"$CONSOLE_FIFO"
CONSOLE_OPEN=1
PAPER_LOG="$SESSION_ROOT/paper.log"
(
  cd "$SERVER_ROOT"
  MINECRAFT_AGENT_SERVER_TOKEN="$TOKEN" \
    MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED="$PREVIEW_ENABLED" \
    "$JAVA_BIN" -Xms512M -Xmx1024M -Dpaper.disableStartupVersionCheck=true \
      -jar "$PAPER_JAR" --nogui <"$CONSOLE_FIFO"
) >"$PAPER_LOG" 2>&1 &
PAPER_PID=$!
wait_for_log "$PAPER_LOG" 'Done \(' 180
wait_for_log "$PAPER_LOG" 'event=startup_ready' 30

for player_name in "${WHITELIST_NAMES[@]}"; do
  printf 'whitelist add %s\n' "$player_name" >&9
done
printf 'whitelist on\n' >&9
ACCESS_LIST_DEADLINE=$((SECONDS + 30))
while ((SECONDS < ACCESS_LIST_DEADLINE)); do
  if access_lists_are_exact >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
access_lists_are_exact >/dev/null 2>&1 \
  || fail "Paper did not establish the exact whitelist with an empty operator list"

printf 'phase13-manual-server: ready commit=%s address=%s:%s preview=%s\n' \
  "$CANDIDATE_COMMIT" "$BIND_ADDRESS" "$SERVER_PORT" "$PREVIEW_ENABLED"
printf '%s\n' "$CANDIDATE_FINGERPRINT"
printf '%s\n' \
  'Deterministic player commands:' \
  '  /agent say phase13 text' \
  '  /agent module recipe phase13 recipe' \
  '  /agent module project save phase13 acceptance project' \
  '  /agent module build phase13 build preview' \
  'Harness controls: :runtime-stop, :runtime-start' \
  'Type Paper console commands here; use stop or EOF to end the harness.'

tail --pid="$PAPER_PID" -n 0 -F "$PAPER_LOG" &
TAIL_PID=$!
while kill -0 "$PAPER_PID" 2>/dev/null; do
  if IFS= read -r -t 1 command; then
    case "$command" in
      :runtime-stop)
        stop_runtime
        printf 'phase13-manual-server: candidate Runtime stopped\n'
        ;;
      :runtime-start)
        start_runtime
        printf 'phase13-manual-server: candidate Runtime restarted\n'
        ;;
      *)
        printf '%s\n' "$command" >&9
        if [[ "$command" == stop ]]; then
          SHUTDOWN_REQUESTED=1
          break
        fi
        ;;
    esac
  else
    read_status=$?
    if ((read_status == 1)); then
      SHUTDOWN_REQUESTED=1
      break
    fi
  fi
done

if kill -0 "$PAPER_PID" 2>/dev/null; then
  printf 'stop\n' >&9 2>/dev/null || true
fi
PAPER_STATUS=0
wait_for_clean_exit "$PAPER_PID" 30 || PAPER_STATUS=$?
PAPER_PID=""
((PAPER_STATUS == 0)) || fail "Paper exited unexpectedly during the manual lane"
((SHUTDOWN_REQUESTED == 1)) \
  || fail "Paper stopped without an explicit harness stop or console EOF"
printf 'phase13-manual-server: stopped\n'
