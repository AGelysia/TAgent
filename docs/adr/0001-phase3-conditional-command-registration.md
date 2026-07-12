# ADR 0001: Phase 3 conditional command registration

- Status: Accepted and exact-server command path verified
- Date: 2026-07-12
- Scope: Paper 1.21.11, Paper API `1.21.11-R0.1-SNAPSHOT`, Java 21

## Context

`/agent` must not exist unless the Phase 3 core startup check succeeds. That
check includes bounded filesystem work and an authenticated Runtime WebSocket
handshake, so it cannot block the Paper server thread. Static command
declarations and lifecycle-only registration happen before an asynchronous
result is available and therefore cannot express this readiness gate.

An initial failure also differs from a later Offline transition. If the command
was never registered, `/agent on` cannot be the recovery path because there is
no command to dispatch.

## Decision

Phase 3 runs the core self-check asynchronously. Only a successful result is
returned to the Paper primary thread for command registration. Registration
uses the public [`Server#getCommandMap`](<https://jd.papermc.io/paper/1.21.11/org/bukkit/Server.html#getCommandMap()>)
and [`CommandMap#register`](<https://jd.papermc.io/paper/1.21.11/org/bukkit/command/CommandMap.html#register(java.lang.String,java.lang.String,org.bukkit.command.Command)>)
APIs; it does not use reflection, NMS, or CraftBukkit internals.

The lifecycle is:

1. `onEnable` captures the Minecraft version and immutable startup inputs, then
   starts the bounded core self-check away from the primary thread.
2. The completion callback returns through the Bukkit scheduler. It checks that
   the plugin is still enabled and that its startup generation is still current.
3. Before registration, both `agent` and `minecraftagent:agent` must be absent.
   Either existing label produces `COMMAND_LABEL_CONFLICT` and leaves the
   command absent.
4. Paper calls `commandMap.register("agent", "minecraftagent", command)`. Success
   requires a true return value and the bare and namespaced lookups both mapping
   by object identity to that exact command instance.
5. A false return, exception, or failed identity postcondition removes every
   mapping whose value is the candidate instance, calls `Command#unregister`,
   and emits only a stable registration failure code.
6. After successful registration, every online player receives
   [`Player#updateCommands`](<https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Player.html#updateCommands()>)
   on the primary thread to request a refreshed client command tree. A refresh
   failure is a warning and does not invent a second registration.
7. `onDisable` invalidates the startup generation, closes any authenticated
   Runtime connection, and removes only mappings whose value is the registered
   command instance before calling `Command#unregister`. This identity cleanup
   must not remove a command subsequently owned by another plugin.

There is no in-process recovery command after an initial core failure. An
operator fixes the external cause and restarts the server. Phase 4 `/agent on`
applies only when initial startup succeeded, `/agent` was registered, and the
already-running service later entered Offline mode.

## Core tool boundary

Phase 3 `CoreToolRuntime` contains exactly six required readiness descriptors:

- `player.context.read`
- `player.held_item.read`
- `server.info.read`
- `server.plugins.list`
- `server.recipe.lookup`
- `server.recipe.uses`

They are closed-schema, read-only descriptors with `executionCapable=false`.
They prove that a safe core catalog can be constructed; they expose no invoke
method, Paper adapter, or Minecraft operation. Real typed tool implementations
and execution arrive in Phase 7. The optional capability directory check is
also not a loader: Capability Pack loading and effective registry construction
remain Phase 9 work. An unavailable optional capability directory may make
readiness `DEGRADED`, but it does not fail the core registration gate.

## Rejected alternatives

- Declaring `/agent` in `paper-plugin.yml`: the command would exist before the
  asynchronous readiness result and violate conditional registration.
- Calling `JavaPlugin#registerCommand` after startup: the public contract limits
  that helper to `onEnable`; it is not a general late-registration API.
- Treating Paper command lifecycle events as an arbitrary post-enable callback:
  their handlers belong to the supported bootstrap/enable lifecycle, not an
  asynchronous completion that occurs later.
- Waiting for Runtime, filesystem, or other core checks on the primary thread:
  bounded timeouts do not make server-thread blocking acceptable.
- Always registering a diagnostic or reduced `/agent` command: initial failure
  must leave the label absent, not expose a permanent not-ready shell.
- Reflection, direct Brigadier mutation, NMS, or CraftBukkit internals: those
  paths are unsupported and unnecessary while the public command map contract
  is available.

## Consequences

The command is a positive readiness signal rather than a recovery mechanism for
failed initial startup. Command ownership is explicit and cleanup is safe under
registration failure, disable, and stale asynchronous completion. The tradeoff
is operational: correcting an initial Runtime, token, protocol, policy, or state
failure requires a server restart.

Phase 3 exposes only readiness and doctor behavior through `/agent`; it does not
add model requests, Offline controls, tool execution, proposals, or capability
execution.

## Verification evidence

`scripts/paper-smoke.sh` ran under Java 21 against Paper `1.21.11-132` with JAR
SHA-256
`5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`.
It used `-Xms256M -Xmx512M`, a test-only provider-health adapter and token, and
left no child process running.

The real-server matrix demonstrated:

- A valid authenticated production Runtime handshake registered and executed
  both `/agent` and `minecraftagent:agent`; `/agent doctor` reported
  `DEGRADED` and `OPTIONAL_CAPABILITY_UNAVAILABLE`.
- Runtime unavailable, mismatched token, and incompatible selected protocol
  left both command labels absent; all attempted command paths were rejected by
  Paper.

Focused JVM tests provide the evidence that is easier to observe directly at
the API boundary: bare and namespaced conflict preflight, identity postconditions
and rollback, `Player#updateCommands()` invocation, identity-only disable
cleanup, late completion after disable, and connection-loss unregistration.
The smoke does not connect a real Minecraft client, so observing the refreshed
client-side command tree remains a later integration test gap. That gap does not
change the Phase 3 server-side registration decision.
