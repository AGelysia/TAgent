#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAPER_BUILD=132
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PAPER_URL="https://fill-data.papermc.io/v1/objects/${PAPER_SHA256}/paper-1.21.11-${PAPER_BUILD}.jar"
CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/minecraft-agent-paper-smoke"
PAPER_JAR="${CACHE_ROOT}/paper-1.21.11-${PAPER_BUILD}.jar"
PLUGIN_JAR="${ROOT}/paper-plugin/build/libs/minecraft-agent-paper-0.1.0-SNAPSHOT.jar"
TEST_TOKEN=phase-3-paper-smoke-token-0123456789abcdef
WRONG_TOKEN=phase-3-paper-smoke-wrong-0123456789abcdef
SELECTED_CASES=" ${PAPER_SMOKE_CASES:-authenticated unavailable wrong-token incompatible} "
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/minecraft-agent-paper-smoke.XXXXXX")"
RUNTIME_PID=""
PAPER_PID=""

cleanup() {
  if [[ -n "$PAPER_PID" ]] && kill -0 "$PAPER_PID" 2>/dev/null; then
    kill "$PAPER_PID" 2>/dev/null || true
    wait_with_timeout "$PAPER_PID" 10 2>/dev/null || true
  fi
  if [[ -n "$RUNTIME_PID" ]] && kill -0 "$RUNTIME_PID" 2>/dev/null; then
    kill "$RUNTIME_PID" 2>/dev/null || true
    wait_with_timeout "$RUNTIME_PID" 10 2>/dev/null || true
  fi
  if [[ "${PAPER_SMOKE_KEEP_WORK:-0}" == 1 ]]; then
    printf 'paper-smoke work-root=%s\n' "$WORK_ROOT" >&2
  else
    rm -rf "$WORK_ROOT"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

validate_selected_cases() {
  local selected_count=0
  local selected
  for selected in $SELECTED_CASES; do
    case "$selected" in
      authenticated | unavailable | wrong-token | incompatible) selected_count=$((selected_count + 1)) ;;
      *)
        printf 'Unknown Paper smoke case: %s\n' "$selected" >&2
        return 1
        ;;
    esac
  done
  if ((selected_count == 0)); then
    printf 'At least one Paper smoke case is required\n' >&2
    return 1
  fi
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
  printf 'Timed out waiting for %s in %s\n' "$pattern" "$log_file" >&2
  [[ -f "$log_file" ]] && tail -n 120 "$log_file" >&2
  return 1
}

wait_with_timeout() {
  local process_id=$1
  local timeout_seconds=$2
  (
    sleep "$timeout_seconds"
    kill -TERM "$process_id" 2>/dev/null || exit 0
    sleep 5
    kill -KILL "$process_id" 2>/dev/null || true
  ) &
  local watchdog=$!
  local status=0
  wait "$process_id" || status=$?
  kill "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true
  return "$status"
}

stop_runtime() {
  if [[ -n "$RUNTIME_PID" ]] && kill -0 "$RUNTIME_PID" 2>/dev/null; then
    kill -TERM "$RUNTIME_PID"
    wait_with_timeout "$RUNTIME_PID" 15
  fi
  RUNTIME_PID=""
}

case_enabled() {
  [[ "$SELECTED_CASES" == *" $1 "* ]]
}

start_runtime() {
  local mode=$1
  local runtime_token=$2
  local case_root=$3
  local runtime_root="${case_root}/runtime"
  local runtime_log="${case_root}/runtime.log"
  mkdir -m 700 -p "$runtime_root"

  if [[ "$mode" == actual ]]; then
    local config_path="${runtime_root}/config.local.yml"
    printf '%s\n' \
      'configVersion: 1' \
      'server:' \
      '  id: survival-main' \
      'transport:' \
      '  host: 127.0.0.1' \
      '  port: 38127' \
      '  serverToken: ${MINECRAFT_AGENT_SERVER_TOKEN}' \
      'model:' \
      '  provider: openai' \
      '  apiKey: ${OPENAI_API_KEY}' \
      '  model: phase-3-smoke-model' \
      '  timeoutSeconds: 2' \
      'storage:' \
      '  sqlitePath: ./data/runtime.db' \
      'logging:' \
      '  directory: ./logs' \
      '  level: info' \
      'limits:' \
      '  maxConcurrentRequests: 1' \
      '  maxQueuedRequests: 1' \
      '  maxToolRounds: 1' \
      '  perPlayerCooldownSeconds: 0' \
      '  dailyRequestsPerPlayer: 10' \
      '  monthlyBudgetUsd: 1' \
      'privacy:' \
      '  storeConversations: false' \
      '  retentionDays: 0' \
      '  logMessageContent: false' \
      '  logToolCalls: false' >"$config_path"
    chmod 600 "$config_path"
    (
      cd "$ROOT/agent-runtime"
      MINECRAFT_AGENT_SERVER_TOKEN="$runtime_token" \
        OPENAI_API_KEY=phase-3-paper-smoke-api-key-0123456789 \
        node scripts/smoke-runtime.mjs "$config_path"
    ) >"$runtime_log" 2>&1 &
  elif [[ "$mode" == incompatible ]]; then
    (
      cd "$ROOT/agent-runtime"
      MINECRAFT_AGENT_SERVER_TOKEN="$runtime_token" \
        node scripts/incompatible-smoke-runtime.mjs
    ) >"$runtime_log" 2>&1 &
  else
    printf 'Unknown Runtime smoke mode: %s\n' "$mode" >&2
    return 1
  fi
  RUNTIME_PID=$!
  wait_for_log "$runtime_log" 'PAPER_SMOKE_RUNTIME_READY' 20
}

run_paper_case() {
  local case_name=$1
  local paper_token=$2
  local expected_startup=$3
  local expect_command=$4
  local case_root="${WORK_ROOT}/${case_name}"
  local server_root="${case_root}/server"
  local server_log="${case_root}/paper.log"
  local input_fifo="${case_root}/console.input"
  mkdir -p "${server_root}/plugins"
  cp "$PLUGIN_JAR" "${server_root}/plugins/"
  printf 'eula=true\n' >"${server_root}/eula.txt"
  printf '%s\n' \
    'online-mode=false' \
    'max-players=1' \
    'view-distance=2' \
    'simulation-distance=2' \
    'spawn-protection=0' \
    'level-type=minecraft:flat' \
    'generate-structures=false' \
    'sync-chunk-writes=false' >"${server_root}/server.properties"
  mkfifo "$input_fifo"
  exec 9<>"$input_fifo"

  (
    cd "$server_root"
    MINECRAFT_AGENT_SERVER_TOKEN="$paper_token" \
      java -Xms256M -Xmx512M -Dpaper.disableStartupVersionCheck=true \
      -jar "$PAPER_JAR" --nogui <"$input_fifo"
  ) >"$server_log" 2>&1 &
  PAPER_PID=$!

  wait_for_log "$server_log" 'Done \(' 180
  wait_for_log "$server_log" "$expected_startup" 20
  local previous_size
  previous_size=$(wc -c <"$server_log")
  printf 'agent\nminecraftagent:agent\nagent doctor\n' >&9
  sleep 3
  local command_output="${case_root}/command-output.log"
  tail -c "+$((previous_size + 1))" "$server_log" >"$command_output"

  if [[ "$expect_command" == present ]]; then
    local responses
    responses=$(rg -c 'Minecraft Agent: (ONLINE|DEGRADED)' "$command_output" || true)
    if ((responses < 2)); then
      printf 'Expected both command labels to execute in %s\n' "$case_name" >&2
      cat "$command_output" >&2
      return 1
    fi
    if ! rg -q 'Warning: OPTIONAL_CAPABILITY_UNAVAILABLE' "$command_output"; then
      printf 'Expected doctor to report the optional capability warning in %s\n' "$case_name" >&2
      cat "$command_output" >&2
      return 1
    fi
  else
    if rg -q 'Minecraft Agent: (ONLINE|DEGRADED)' "$command_output"; then
      printf 'Command unexpectedly existed in %s\n' "$case_name" >&2
      cat "$command_output" >&2
      return 1
    fi
    local unknown_responses
    unknown_responses=$(rg -c 'Unknown or incomplete command' "$command_output" || true)
    if ((unknown_responses < 3)); then
      printf 'Paper did not reject every absent command path in %s\n' "$case_name" >&2
      cat "$command_output" >&2
      return 1
    fi
  fi

  printf 'stop\n' >&9
  wait_with_timeout "$PAPER_PID" 30
  PAPER_PID=""
  exec 9>&-
  exec 9<&-
  printf 'paper-smoke case=%s result=passed\n' "$case_name"
}

validate_selected_cases
mkdir -p "$CACHE_ROOT"
if [[ -f "$PAPER_JAR" ]] && ! printf '%s  %s\n' "$PAPER_SHA256" "$PAPER_JAR" | sha256sum --check --status; then
  rm -f "$PAPER_JAR"
fi
if [[ ! -f "$PAPER_JAR" ]]; then
  curl --fail --location --retry 3 --output "${PAPER_JAR}.tmp" "$PAPER_URL"
  printf '%s  %s\n' "$PAPER_SHA256" "${PAPER_JAR}.tmp" | sha256sum --check --status
  mv "${PAPER_JAR}.tmp" "$PAPER_JAR"
fi
printf '%s  %s\n' "$PAPER_SHA256" "$PAPER_JAR" | sha256sum --check --status

(
  cd "$ROOT/agent-runtime"
  npm run build --silent
)
(
  cd "$ROOT"
  ./gradlew --no-daemon --max-workers=1 :paper-plugin:jar >/dev/null
)

if case_enabled authenticated; then
  case_root="${WORK_ROOT}/authenticated"
  mkdir -p "$case_root"
  start_runtime actual "$TEST_TOKEN" "$case_root"
  run_paper_case authenticated "$TEST_TOKEN" 'event=startup_ready health=DEGRADED' present
  stop_runtime
fi

if case_enabled unavailable; then
  run_paper_case unavailable "$TEST_TOKEN" 'code=RUNTIME_UNREACHABLE' absent
fi

if case_enabled wrong-token; then
  case_root="${WORK_ROOT}/wrong-token"
  mkdir -p "$case_root"
  start_runtime actual "$TEST_TOKEN" "$case_root"
  run_paper_case wrong-token "$WRONG_TOKEN" 'code=TOKEN_AUTH_FAILED' absent
  stop_runtime
fi

if case_enabled incompatible; then
  case_root="${WORK_ROOT}/incompatible"
  mkdir -p "$case_root"
  start_runtime incompatible "$TEST_TOKEN" "$case_root"
  run_paper_case incompatible "$TEST_TOKEN" 'code=PROTOCOL_INCOMPATIBLE' absent
  stop_runtime
fi

printf 'paper-smoke build=%s sha256=%s cases=%s result=passed\n' \
  "$PAPER_BUILD" "$PAPER_SHA256" "${SELECTED_CASES:1:${#SELECTED_CASES}-2}"
