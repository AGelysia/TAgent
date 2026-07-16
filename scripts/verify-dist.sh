#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  printf 'verify-dist: %s\n' "$*" >&2
  exit 1
}

if (($# > 1)); then
  fail "usage: $0 [dist-directory]"
fi

DIST_INPUT="${1:-$ROOT/dist}"
[[ -e "$DIST_INPUT" ]] || fail "release directory does not exist: $DIST_INPUT"
[[ ! -L "$DIST_INPUT" ]] || fail "release directory must not be a symlink: $DIST_INPUT"
[[ -d "$DIST_INPUT" ]] || fail "release path is not a directory: $DIST_INPUT"
DIST="$(cd "$DIST_INPUT" && pwd -P)"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
else
  fail "java was not found; set JAVA_HOME or add Java 21 to PATH"
fi

for program in cmp diff grep node sha256sum sort stat; do
  command -v "$program" >/dev/null 2>&1 || fail "required program was not found: $program"
done

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/minecraft-agent-verify-dist.XXXXXX")"
cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

relative_path() {
  local path=$1
  if [[ "$path" == "$DIST" ]]; then
    printf '.'
  else
    printf '%s' "${path#"$DIST"/}"
  fi
}

require_file() {
  local path=$1
  [[ -f "$DIST/$path" && ! -L "$DIST/$path" ]] || fail "required file is missing: $path"
  [[ -s "$DIST/$path" ]] || fail "required file is empty: $path"
}

require_directory() {
  local path=$1
  [[ -d "$DIST/$path" && ! -L "$DIST/$path" ]] || fail "required directory is missing: $path"
}

require_exact_children() {
  local directory=$1
  shift
  local label=${directory:-release root}
  local actual="$TMP_ROOT/exact-${directory//\//_}.actual"
  local expected="$TMP_ROOT/exact-${directory//\//_}.expected"
  find "$DIST${directory:+/$directory}" -mindepth 1 -maxdepth 1 -printf '%f\n' \
    | LC_ALL=C sort >"$actual"
  printf '%s\n' "$@" | LC_ALL=C sort >"$expected"
  if ! cmp -s "$expected" "$actual"; then
    diff -u "$expected" "$actual" >&2 || true
    fail "$label contains an unexpected or missing top-level entry"
  fi
}

required_root_files=(
  .env.example
  CLIENT-COMPATIBILITY.md
  LICENSE
  MinecraftAgent-Client-Fabric.jar
  MinecraftAgent-Paper.jar
  README.md
  SECURITY.md
  SHA256SUMS
  config.example.yml
  start-agent.ps1
  start-agent.sh
)
required_root_directories=(
  agent-runtime
  default-capability-packs
  deploy
  docs
  protocol
)

for path in "${required_root_files[@]}"; do
  require_file "$path"
done
for path in "${required_root_directories[@]}"; do
  require_directory "$path"
done
require_exact_children "" "${required_root_files[@]}" "${required_root_directories[@]}"
require_exact_children agent-runtime \
  config.example.yml dist package-lock.json package.json scripts
require_exact_children agent-runtime/scripts version.mjs
require_exact_children deploy paper systemd
require_exact_children deploy/paper config.yml.example server.properties.example
require_exact_children deploy/systemd \
  paper.env.example \
  runtime.env.example \
  tagent-paper.service.example \
  tagent-provider-check.service.example \
  tagent-runtime.service.example
require_exact_children docs operations.md phase13-manual-test.md phase14-cloud-validation.md
require_exact_children protocol README.md schemas

required_runtime_files=(
  agent-runtime/config.example.yml
  agent-runtime/dist/bootstrap/index.js
  agent-runtime/dist/health/runtime-lock.js
  agent-runtime/dist/index.js
  agent-runtime/dist/storage/migrations.js
  agent-runtime/dist/usage/usage-accounting.js
  agent-runtime/dist/validation/live-provider-check.js
  agent-runtime/package-lock.json
  agent-runtime/package.json
  agent-runtime/scripts/version.mjs
)
for path in "${required_runtime_files[@]}"; do
  require_file "$path"
done
require_file docs/operations.md
require_file docs/phase13-manual-test.md
require_file docs/phase14-cloud-validation.md
grep -Eq '"start"[[:space:]]*:[[:space:]]*"node dist/bootstrap/index\.js"' \
  "$DIST/agent-runtime/package.json" \
  || fail "agent-runtime/package.json does not start the packaged bootstrap"
grep -Eq '"validate:provider"[[:space:]]*:[[:space:]]*"node dist/validation/live-provider-check\.js"' \
  "$DIST/agent-runtime/package.json" \
  || fail "agent-runtime/package.json does not run the packaged provider validator directly"
cmp -s "$DIST/config.example.yml" "$DIST/agent-runtime/config.example.yml" \
  || fail "root and Runtime configuration templates differ"
for source_path in \
  .env.example \
  CLIENT-COMPATIBILITY.md \
  LICENSE \
  README.md \
  SECURITY.md \
  start-agent.ps1 \
  start-agent.sh \
  docs/operations.md \
  docs/phase13-manual-test.md \
  docs/phase14-cloud-validation.md \
  protocol/README.md; do
  cmp -s "$ROOT/$source_path" "$DIST/$source_path" \
    || fail "packaged source-controlled file differs: $source_path"
done
for runtime_path in config.example.yml package-lock.json package.json scripts/version.mjs; do
  cmp -s "$ROOT/agent-runtime/$runtime_path" "$DIST/agent-runtime/$runtime_path" \
    || fail "packaged Runtime file differs: $runtime_path"
done
diff -qr "$ROOT/capability-packs" "$DIST/default-capability-packs" >/dev/null \
  || fail "default Capability Packs differ from the source release set"
diff -qr "$ROOT/deploy" "$DIST/deploy" >/dev/null \
  || fail "deployment templates differ from the source release set"
diff -qr "$ROOT/protocol/schemas" "$DIST/protocol/schemas" >/dev/null \
  || fail "packaged protocol schemas differ from the source release set"

find "$ROOT/agent-runtime/src" -type f -name '*.ts' -printf '%P\n' \
  | while IFS= read -r source; do
      stem=${source%.ts}
      printf '%s\n' "$stem.d.ts" "$stem.d.ts.map" "$stem.js" "$stem.js.map"
    done \
  | LC_ALL=C sort >"$TMP_ROOT/runtime-expected-files"
find "$DIST/agent-runtime/dist" -type f -printf '%P\n' \
  | LC_ALL=C sort >"$TMP_ROOT/runtime-actual-files"
if ! cmp -s "$TMP_ROOT/runtime-expected-files" "$TMP_ROOT/runtime-actual-files"; then
  diff -u "$TMP_ROOT/runtime-expected-files" "$TMP_ROOT/runtime-actual-files" >&2 || true
  fail "packaged Runtime output does not match the exact TypeScript source surface"
fi
[[ -d "$ROOT/agent-runtime/dist" && ! -L "$ROOT/agent-runtime/dist" ]] \
  || fail "trusted Runtime build output is missing"
diff -qr "$ROOT/agent-runtime/dist" "$DIST/agent-runtime/dist" >/dev/null \
  || fail "packaged Runtime bytes differ from the trusted build output"

VERSION="$(
  node -e '
    const fs = require("node:fs");
    const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).version;
    if (typeof value !== "string") process.exit(1);
    process.stdout.write(value);
  ' "$DIST/agent-runtime/package.json"
)" || fail "agent-runtime/package.json has no string version"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] \
  || fail "Runtime package version is not release-safe"
[[ "${VERSION^^}" != *SNAPSHOT* ]] || fail "SNAPSHOT versions cannot be released"

LOCK_VERSION="$(
  node -e '
    const fs = require("node:fs");
    const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).packages?.[""]?.version;
    if (typeof value !== "string") process.exit(1);
    process.stdout.write(value);
  ' "$DIST/agent-runtime/package-lock.json"
)" || fail "agent-runtime/package-lock.json has no root package version"
[[ "$LOCK_VERSION" == "$VERSION" ]] || fail "Runtime package and lockfile versions differ"

link_path="$(find "$DIST" -type l -print -quit)"
[[ -z "$link_path" ]] || fail "symlinks are forbidden: $(relative_path "$link_path")"
special_path="$(find "$DIST" ! -type d ! -type f ! -type l -print -quit)"
[[ -z "$special_path" ]] || fail "special filesystem entries are forbidden: $(relative_path "$special_path")"

while IFS= read -r -d '' path; do
  rel="$(relative_path "$path")"
  [[ "$rel" != *$'\n'* && "$rel" != *'\'* ]] \
    || fail "release paths must not contain newlines or backslashes: $rel"

  mode="$(stat -c '%a' "$path")"
  if [[ -d "$path" ]]; then
    [[ "$mode" == 755 ]] || fail "directory mode must be 0755: $rel (found $mode)"
  elif [[ "$rel" == start-agent.sh ]]; then
    [[ "$mode" == 755 ]] || fail "start-agent.sh mode must be 0755 (found $mode)"
  else
    [[ "$mode" == 644 ]] || fail "ordinary file mode must be 0644: $rel (found $mode)"
  fi
done < <(find "$DIST" -print0)

while IFS= read -r -d '' path; do
  rel="$(relative_path "$path")"
  base="${rel##*/}"
  lower_base="${base,,}"

  [[ "/$rel/" != */node_modules/* ]] || fail "node_modules must not be packaged: $rel"

  case "$lower_base" in
    .env)
      fail "private environment file is forbidden: $rel"
      ;;
    .env.*)
      [[ "$lower_base" == .env.example ]] || fail "private environment file is forbidden: $rel"
      ;;
    config.local.yml | config.local.yaml)
      fail "private Runtime configuration is forbidden: $rel"
      ;;
    log | logs | *.log | *.log.*)
      fail "log data is forbidden: $rel"
      ;;
    *.db | *.db3 | *.db-journal | *.db-shm | *.db-wal | *.sqlite | *.sqlite3 | *.sqlite-journal | *.sqlite-shm | *.sqlite-wal)
      fail "database state is forbidden: $rel"
      ;;
    *.jks | *.key | *.keystore | *.p12 | *.pem | *.pfx | credentials | credentials.* | id_ed25519 | id_ed25519.* | id_rsa | id_rsa.* | secret | secrets | secrets.*)
      fail "credential or private-key file is forbidden: $rel"
      ;;
  esac
done < <(find "$DIST" -mindepth 1 -print0)

secret_matches="$TMP_ROOT/secret-matches"
if grep -a -R -l -E \
  -- '-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----|(^|[^[:alnum:]_])sk-(proj-)?[[:alnum:]_-]{20,}|(^|[^[:alnum:]_])gh[pousr]_[[:alnum:]]{20,}|(^|[^[:alnum:]_])AKIA[0-9A-Z]{16}' \
  "$DIST" >"$secret_matches"; then
  fail "release files contain a private-key or credential pattern"
fi

for environment_template in \
  "$DIST/.env.example" \
  "$DIST/deploy/systemd/runtime.env.example" \
  "$DIST/deploy/systemd/paper.env.example"; do
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^[[:space:]]*#?[[:space:]]*([A-Z0-9_]*(KEY|TOKEN|SECRET)[A-Z0-9_]*)=(.*)$ ]]; then
      value="${BASH_REMATCH[3]}"
      [[ "$value" == replace-with-* || "$value" =~ ^\$\{[A-Z0-9_]+\}$ ]] \
        || fail "environment example contains a non-placeholder credential value for ${BASH_REMATCH[1]}"
    fi
  done <"$environment_template"
done
grep -Eq '^MINECRAFT_AGENT_SERVER_TOKEN=replace-with-' \
  "$DIST/deploy/systemd/runtime.env.example" \
  || fail "Runtime environment example is missing its token placeholder"
grep -Eq '^MINECRAFT_AGENT_SERVER_TOKEN=replace-with-' \
  "$DIST/deploy/systemd/paper.env.example" \
  || fail "Paper environment example is missing its token placeholder"
grep -Eq '^MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=(false|true)$' \
  "$DIST/deploy/systemd/paper.env.example" \
  || fail "Paper environment example is missing its preview switch"
if grep -Eq '(API_KEY|BASE_URL)=' "$DIST/deploy/systemd/paper.env.example"; then
  fail "Paper environment example must not contain provider credentials or endpoints"
fi

while IFS= read -r -d '' yaml; do
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^[[:space:]]*apiKey:[[:space:]]*(.*)$ ]]; then
      [[ "${BASH_REMATCH[1]}" == '${OPENAI_API_KEY}' ]] \
        || fail "YAML apiKey must use the OPENAI_API_KEY environment placeholder: $(relative_path "$yaml")"
    elif [[ "$line" =~ ^[[:space:]]*(serverToken|server-token):[[:space:]]*(.*)$ ]]; then
      [[ "${BASH_REMATCH[2]}" == '${MINECRAFT_AGENT_SERVER_TOKEN}' ]] \
        || fail "YAML serverToken must use the MINECRAFT_AGENT_SERVER_TOKEN environment placeholder: $(relative_path "$yaml")"
    fi
  done <"$yaml"
done < <(find "$DIST" -type f \
  \( -name '*.yml' -o -name '*.yaml' -o -name '*.yml.example' -o -name '*.yaml.example' \) \
  -print0)

SCHEMA_ROOT="$DIST/protocol/schemas"
require_directory protocol/schemas
find "$SCHEMA_ROOT" -type f -name '*.schema.json' \
  -printf 'protocol/schemas/%P\n' | LC_ALL=C sort >"$TMP_ROOT/schemas"
schema_count="$(wc -l <"$TMP_ROOT/schemas")"
[[ "$schema_count" == 50 ]] || fail "protocol/schemas must contain exactly 50 schemas (found $schema_count)"

required_schemas=(
  protocol/schemas/build-preview.schema.json
  protocol/schemas/management-costs-request.schema.json
  protocol/schemas/management-costs-response.schema.json
  protocol/schemas/tools/build-preview-create-arguments.schema.json
  protocol/schemas/tools/build-preview-create-result.schema.json
)
for path in "${required_schemas[@]}"; do
  grep -Fxq "$path" "$TMP_ROOT/schemas" || fail "required protocol schema is missing: $path"
done

inspect_jar() {
  local jar_path=$1
  local descriptor=$2
  local main_class=$3
  local kind=$4
  local entries="$TMP_ROOT/$kind.entries"
  local schemas="$TMP_ROOT/$kind.schemas"
  local extract_root="$TMP_ROOT/$kind.extract"

  "$JAVA_BIN" "$ROOT/scripts/VerifyReleaseJar.java" \
    "$jar_path" "$kind" "$extract_root" "$entries" \
    || fail "$kind JAR contains an unsafe or unexpected entry"
  grep -Fxq "$descriptor" "$entries" || fail "$kind JAR is missing $descriptor"
  grep -Fxq "$main_class" "$entries" || fail "$kind JAR is missing $main_class"
  grep -E '^protocol/schemas/.+\.schema\.json$' "$entries" | LC_ALL=C sort >"$schemas"
  cmp -s "$TMP_ROOT/schemas" "$schemas" || fail "$kind JAR does not contain the exact 50-schema release set"

  if grep -a -R -l -E \
    -- '-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----|(^|[^[:alnum:]_])sk-(proj-)?[[:alnum:]_-]{20,}|(^|[^[:alnum:]_])gh[pousr]_[[:alnum:]]{20,}|(^|[^[:alnum:]_])AKIA[0-9A-Z]{16}' \
    "$extract_root" >"$TMP_ROOT/$kind.secret-matches"; then
    fail "$kind JAR contains a private-key or credential pattern"
  fi

  if [[ "$kind" == Paper ]]; then
    grep -Eq '^name:[[:space:]]*MinecraftAgent[[:space:]]*$' "$extract_root/$descriptor" \
      || fail "Paper descriptor has the wrong plugin name"
    grep -Eq '^main:[[:space:]]*dev\.minecraftagent\.paper\.MinecraftAgentPlugin[[:space:]]*$' \
      "$extract_root/$descriptor" || fail "Paper descriptor has the wrong main class"
    grep -Eq "^version:[[:space:]]*['\"]?${VERSION//./\\.}['\"]?[[:space:]]*$" \
      "$extract_root/$descriptor" || fail "Paper and Runtime versions differ"
    grep -Eq "^Implementation-Version:[[:space:]]*${VERSION//./\\.}[[:space:]]*\r?$" \
      "$extract_root/META-INF/MANIFEST.MF" || fail "Paper manifest version differs"
  else
    grep -Eq '"id"[[:space:]]*:[[:space:]]*"minecraftagent"' "$extract_root/$descriptor" \
      || fail "Fabric descriptor has the wrong mod id"
    grep -Eq '"environment"[[:space:]]*:[[:space:]]*"client"' "$extract_root/$descriptor" \
      || fail "Fabric descriptor is not client-only"
    grep -Fq 'dev.minecraftagent.client.MinecraftAgentClient' "$extract_root/$descriptor" \
      || fail "Fabric descriptor has the wrong client entrypoint"
    node -e '
      const fs = require("node:fs");
      const descriptor = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
      const depends = descriptor.depends;
      if (
        descriptor.version !== process.argv[2] ||
        depends?.fabricloader !== ">=0.19.3" ||
        depends?.["fabric-api"] !== "0.141.4+1.21.11" ||
        depends?.minecraft !== "1.21.11" ||
        depends?.java !== ">=21"
      ) process.exit(1);
    ' "$extract_root/$descriptor" "$VERSION" || fail "Fabric and Runtime versions differ"
  fi
}

inspect_jar \
  "$DIST/MinecraftAgent-Paper.jar" \
  paper-plugin.yml \
  dev/minecraftagent/paper/MinecraftAgentPlugin.class \
  Paper
inspect_jar \
  "$DIST/MinecraftAgent-Client-Fabric.jar" \
  fabric.mod.json \
  dev/minecraftagent/client/MinecraftAgentClient.class \
  Fabric

manifest_paths="$TMP_ROOT/manifest-paths"
: >"$manifest_paths"
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ "$line" =~ ^([0-9a-f]{64})\ \ (.+)$ ]] || fail "SHA256SUMS contains a malformed line"
  manifest_path="${BASH_REMATCH[2]}"
  [[ "$manifest_path" != /* ]] || fail "SHA256SUMS contains an unsafe path: $manifest_path"
  path="${manifest_path#./}"
  [[ -n "$path" && "$path" != ./* && "$path" != *'/../'* && "$path" != ../* && "$path" != *'/./'* ]] \
    || fail "SHA256SUMS contains an unsafe path: $manifest_path"
  [[ "$path" != SHA256SUMS ]] || fail "SHA256SUMS must not include itself"
  [[ -f "$DIST/$path" && ! -L "$DIST/$path" ]] || fail "SHA256SUMS references a missing file: $path"
  printf '%s\n' "$path" >>"$manifest_paths"
done <"$DIST/SHA256SUMS"

duplicate_manifest_path="$(LC_ALL=C sort "$manifest_paths" | uniq -d | sed -n '1p')"
[[ -z "$duplicate_manifest_path" ]] || fail "SHA256SUMS contains a duplicate path: $duplicate_manifest_path"

find "$DIST" -type f ! -path "$DIST/SHA256SUMS" -printf '%P\n' \
  | LC_ALL=C sort >"$TMP_ROOT/actual-files"
LC_ALL=C sort "$manifest_paths" >"$TMP_ROOT/manifest-files"
if ! cmp -s "$TMP_ROOT/actual-files" "$TMP_ROOT/manifest-files"; then
  diff -u "$TMP_ROOT/manifest-files" "$TMP_ROOT/actual-files" >&2 || true
  fail "SHA256SUMS must cover every ordinary release file except itself"
fi

(
  cd "$DIST"
  sha256sum --check --strict --quiet SHA256SUMS
) || fail "SHA256SUMS verification failed"

printf 'verify-dist: directory=%s schemas=%s result=passed\n' "$DIST" "$schema_count"
