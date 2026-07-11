# Progress

Last updated: 2026-07-11

## Current status

Phase 0, Phase 1, and the bounded Phase 2 Runtime-readiness scope are complete.
Phase 3 has not started.

## Locked decisions

- Minecraft, Paper, and Fabric target: 1.21.11.
- Paper API: 1.21.11-R0.1-SNAPSHOT.
- Java toolchain: 21.
- Runtime: Node.js 22 and TypeScript.
- Runtime-Paper protocol: 1.0.
- Runtime and Paper will have separate authoritative stores; they will never
  share-write one SQLite file.
- Every Runtime-Paper envelope has `messageId`, `requestId`, timestamp, and
  nonce. `requestId` is required even for standalone control/health exchanges.
- `DEGRADED` is a health dimension, not an additional operational state.
- Build-preview chunks are framing data inside the protocol 1.0 preview schema;
  semantic validation treats reassembly as a separate validation layer.
- The optional Fabric client must load without Litematica or MaLiLib.
- Gradle and npm work is run serially on this constrained host.
- Phase 2 uses an injectable fake provider adapter for readiness tests. Production
  provider network calls remain fail closed until request state, policy, and
  request binding are testable.

## Phase 0: repository scaffold

Completed:

- [x] Root Gradle build, locked version catalog, and constrained worker/memory
      settings are complete and verified.
- [x] Paper Java 21 scaffold builds a descriptor-valid JAR. A live Paper server
      launch is intentionally outside the headless Phase 0 verification lane.
- [x] Runtime Node.js 22/TypeScript scaffold builds, tests, formats, and reports
      its version without listening on a network port.
- [x] Fabric Java 21 scaffold builds a client-only JAR and has no Litematica or
      MaLiLib dependency. A graphical client launch is not claimed on this host.
- [x] Checksummed Gradle wrapper and npm lockfile are present.
- [x] Repository ignore, editor, license, and example environment files are
      reviewed.
- [x] Development scripts and top-level README describe the verified commands.

## Phase 1: protocol contracts

Completed:

- [x] Protocol 1.0 Envelope schema includes a nonce and required correlation
      fields with closed bounds.
- [x] Runtime-Paper handshake and agent request schemas are complete.
- [x] Tool call, tool result, and proposal schemas are complete.
- [x] Client handshake and fixed structured-view schemas are complete.
- [x] Recipe view and build preview schemas preserve structured semantics.
- [x] Capability manifest schema is declarative and closed.
- [x] Build-preview transfer metadata and chunks have bounded schema fields and
      separate reassembly/hash semantic validation.
- [x] Minimal valid and focused invalid fixtures cover every contract and local
      cross-schema reference.
- [x] Paper Java, Client Java, and Runtime TypeScript validate the same manifest
      entries and agree on results.
- [x] Semantic tests cover protocol mismatch, index-based chunk reassembly,
      byte limits, bounded gzip expansion, canonical base64, and SHA-256 checks.
- [x] View negotiation rejects a structured view when the client did not
      declare every required feature version.
- [x] Protocol documentation matches the final landed schemas, fixtures, and
      explicitly limited Phase 1 semantic implementation boundary.

## Phase 2: Runtime configuration and readiness

Completed:

- [x] Strict, versioned YAML configuration is capped at 64 KiB, rejects aliases,
      duplicate/unknown fields, unsafe paths, unsafe writable permissions, and
      dangerous secret combinations.
- [x] Environment substitution occurs after YAML parsing and only for complete
      `${NAME}` scalar values; missing API key and server token have stable errors.
- [x] Startup diagnostics contain stable codes and known field paths without
      serializing configuration values, provider exceptions, or unknown key names.
- [x] Log and SQLite state are confined below the configuration directory. New
      directories/files use `0700`/`0600`, and symlinks, hard-linked databases,
      broad state permissions, and root-directory state paths are rejected.
- [x] Runtime-owned SQLite readiness performs read, integrity, and real
      rollback-write probes through Node's built-in SQLite API, then retains one
      handle until shutdown. No migration or repository is implied.
- [x] Capability Schema loading and an abortable model-provider health interface
      are part of the fixed startup gate. Tests use a fake adapter; the default
      production adapter returns `PROVIDER_UNSUPPORTED` and never reports READY.
- [x] Fastify binds only after every injected core check passes. `/health` is
      loopback-only, cached, `no-store`, secret-free, and does not rerun checks.
- [x] Failed provider checks, occupied ports, and post-bind startup failures close
      the Fastify listener and SQLite handle without a transient READY state.
- [x] The packaged configuration template and top-level Bash/PowerShell launchers
      carry and forward explicit `--config` arguments.

## Verification

Verified serially on 2026-07-11:

```bash
./scripts/package.sh
# exit 0

cd agent-runtime
npm audit
npm audit --omit=dev
npm run version

cd dist/agent-runtime
npm ci --omit=dev --prefer-offline
npm audit --omit=dev
cd ..
# with temporary non-placeholder OPENAI_API_KEY and MINECRAFT_AGENT_SERVER_TOKEN exported
./start-agent.sh --config config.example.yml
# expected exit 1: PROVIDER_UNSUPPORTED
```

Results:

- Runtime: 6 Vitest files, 42 tests passed; TypeScript build, ESLint, and
  Prettier passed; full and production-only npm audits reported 0
  vulnerabilities.
- Paper: build and Spotless passed; 36 shared dynamic contract cases plus one
  descriptor test passed.
- Fabric: remapped JAR build and Spotless passed; the same 36 shared contract
  cases plus one client metadata test passed.
- Both JVM reports contain no remote Schema load, `UnknownHostException`,
  invalid-schema error, skipped test, or failed test.
- Packaged Runtime preserved its compiled layout, configuration template, and
  protocol schemas; production dependencies installed with 0 vulnerabilities.
- The packaged startup smoke used temporary environment secrets, completed the
  local checks, and failed closed with `PROVIDER_UNSUPPORTED`. Its structured
  output contained neither secret and no listener remained running.
- All protocol JSON files parse successfully; Bash scripts pass `bash -n`.

PowerShell scripts were reviewed for native exit-code propagation but were not
executed because `pwsh` is unavailable on this Linux host. A real Paper server,
graphical Fabric client, and Litematica were not started; those integration
lanes remain intentionally deferred. Gradle reports Loom-originated deprecation
warnings for future Gradle 10 compatibility, but both builds pass on the locked
Gradle 9.5.1 wrapper. Node emits its documented ExperimentalWarning when the
built-in SQLite module is loaded; it is not suppressed.

## Explicitly not implemented

- Production model-provider network health adapter or any model request.
- Runtime-Paper WebSocket server, token enforcement, replay cache, or heartbeat.
- Paper command registration, Offline state machine, `/agent on`, or
  `/agent off`.
- SQLite migrations or repositories in either process; Phase 2 only owns the
  Runtime startup-readiness file and connection.
- Session resume, module routing, rate limiting, or cost accounting.
- Tool registry/execution, policy enforcement, proposals, confirmation, or
  audit persistence.
- Capability Pack discovery, approval, reload, or command execution.
- Client custom payload networking, overlay UI, item rendering, or preferences.
- Recipe, guide, locate, project, build, world mutation, or Litematica behavior.
- Full build-preview logical validation: strict single-member gzip framing,
  RFC 8785 content/Palette canonicalization, geometry, block count,
  base-region hash, and change-policy checks. These remain mandatory Phase 10
  gates before a preview network handler can be enabled.

The presence of a schema does not mark the corresponding feature implemented.

## Known design risks

### Provider boundary

Phase 2 proves the provider health interface and ordering with a fake adapter but
does not perform a production provider request. The CLI intentionally refuses
READY with `PROVIDER_UNSUPPORTED`; a fake provider cannot be selected through
configuration. A real adapter must map authentication, model availability,
unreachable, and timeout failures without logging request or response bodies.

### Node SQLite stability

Node 22's built-in synchronous SQLite API avoids a native addon on this small
host, but Node still labels it active development and emits an ExperimentalWarning.
Phase 2 confines it to bounded startup work. The repository layer must reassess
the driver before adding request-path database operations.

### Conditional command registration

The baseline requires `/agent` to be absent when the initial core self-check
fails, while recovery is described through `/agent on`. Paper command
registration is lifecycle-bound and the self-check includes asynchronous work.
Phase 3 must spike the exact Paper 1.21.11 behavior and select a supported
recovery design before command implementation.

### Litematica compatibility

Litematica and MaLiLib internals are exact-version sensitive. A future adapter
must lock both versions and prove optional class loading, preview lifecycle, and
native Material List HUD integration. No compatibility is assumed now.

### Large world writes

A preflight region hash does not make a multi-tick update atomic. The first
write implementation must use conservative limits and define conflict and
partial-failure behavior before raising block limits.

### Third-party command parsing

Paper plugins do not universally expose a side-effect-free Brigadier validation
path. A command-backed Capability remains disabled unless complete preflight
parsing is demonstrated for the locked plugin version; otherwise use a typed
adapter.

## Next gates

1. Complete the Phase 3 command-registration spike against Paper 1.21.11 before
   promising in-game recovery from an initial self-check failure.
2. Add the authenticated Runtime-Paper connection and token/protocol checks with
   a fake Runtime; keep production provider calls out of that integration lane.
3. Preserve the Phase 2 startup gate when the production provider health adapter
   is introduced for the first model-backed phase.
4. Add Gradle dependency-verification metadata before calling a release
   byte-for-byte reproducible; Paper 1.21.11 is available only through an
   upstream mutable snapshot coordinate.
