package dev.minecraftagent.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.lifecycle.DesiredMode;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class AgentCommandTest {
  @Test
  void rootCommandHasNoFrameworkPermissionThatCanHideAuthorizedToggles() {
    var command = command(offline(), new RecordingControl(), ignored -> true, Runnable::run);

    assertNull(command.getPermission());
  }

  @Test
  void offlineGatePrecedesPermissionsDoctorAndUsage() {
    var messages = new ArrayList<String>();
    var control = new RecordingControl();
    var authorizationCalls = new int[1];
    var command =
        command(
            offline(),
            control,
            ignored -> {
              authorizationCalls[0]++;
              return false;
            },
            Runnable::run);
    var sender = sender(messages, false);

    assertTrue(command.execute(sender, "agent", new String[0]));
    assertTrue(command.execute(sender, "agent", new String[] {"doctor"}));
    assertTrue(command.execute(sender, "agent", new String[] {"unknown"}));
    assertTrue(command.execute(sender, "agent", new String[] {"on", "extra"}));

    assertEquals(List.of("AI offline", "AI offline", "AI offline", "AI offline"), messages);
    assertEquals(0, authorizationCalls[0]);
    assertEquals(0, control.offCalls);
    assertEquals(0, control.onCalls);
  }

  @Test
  void toggleAuthorizationIsStillRequiredWhileOffline() {
    var messages = new ArrayList<String>();
    var control = new RecordingControl();
    var command = command(offline(), control, ignored -> false, Runnable::run);
    var sender = sender(messages, true);

    command.execute(sender, "agent", new String[] {"off"});
    command.execute(sender, "agent", new String[] {"on"});

    assertEquals(
        List.of(
            "You do not have permission to use this command.",
            "You do not have permission to use this command."),
        messages);
    assertEquals(0, control.offCalls);
    assertEquals(0, control.onCalls);
  }

  @Test
  void turnOffIsImmediateAndIdempotentAtTheCommandBoundary() {
    var messages = new ArrayList<String>();
    var control = new RecordingControl();
    var command = command(offline(), control, ignored -> true, Runnable::run);

    command.execute(sender(messages, false), "agent", new String[] {"OFF"});

    assertEquals(1, control.offCalls);
    assertEquals(List.of("AI offline"), messages);
  }

  @Test
  void synchronousRecoveryDispositionsHaveStableMessages() {
    assertRecoveryMessage(AgentControl.RecoveryDisposition.ALREADY_ONLINE, "AI online");
    assertRecoveryMessage(
        AgentControl.RecoveryDisposition.ALREADY_STARTING, "AI startup check already in progress.");
    assertRecoveryMessage(
        AgentControl.RecoveryDisposition.UNAVAILABLE,
        "AI remains offline. Check the server console.");
  }

  @Test
  void startedRecoveryDispatchesSuccessReply() {
    var messages = new ArrayList<String>();
    var completion = new CompletableFuture<Boolean>();
    var control = new RecordingControl();
    control.recovery = recovery(AgentControl.RecoveryDisposition.STARTED, completion);
    var replies = new QueuedReplies();
    var command = command(offline(), control, ignored -> true, replies::dispatch);

    command.execute(sender(messages, false), "agent", new String[] {"on"});
    assertEquals(List.of("AI startup check started."), messages);

    completion.complete(true);
    assertEquals(List.of("AI startup check started."), messages);
    replies.drain();

    assertEquals(List.of("AI startup check started.", "AI online"), messages);
  }

  @Test
  void failedOrExceptionalRecoveryDispatchesTheSameSafeReply() {
    for (var exceptional : List.of(false, true)) {
      var messages = new ArrayList<String>();
      var completion = new CompletableFuture<Boolean>();
      var control = new RecordingControl();
      control.recovery = recovery(AgentControl.RecoveryDisposition.STARTED, completion);
      var replies = new QueuedReplies();
      var command = command(offline(), control, ignored -> true, replies::dispatch);

      command.execute(sender(messages, false), "agent", new String[] {"on"});
      if (exceptional) {
        completion.completeExceptionally(new IllegalStateException("sensitive detail"));
      } else {
        completion.complete(false);
      }
      replies.drain();

      assertEquals(
          List.of("AI startup check started.", "AI remains offline. Check the server console."),
          messages);
    }
  }

  @Test
  void onlineCommandsRequireTheOrdinaryPermission() {
    var messages = new ArrayList<String>();
    var command = command(healthy(), new RecordingControl(), ignored -> false, Runnable::run);

    command.execute(sender(messages, false), "agent", new String[0]);
    command.execute(sender(messages, false), "agent", new String[] {"doctor"});

    assertEquals(
        List.of(
            "You do not have permission to use this command.",
            "You do not have permission to use this command."),
        messages);
  }

  @Test
  void doctorMapsHealthAndReportsOnlyStableCodes() {
    var healthyMessages = new ArrayList<String>();
    command(healthy(), new RecordingControl(), ignored -> false, Runnable::run)
        .execute(sender(healthyMessages, true), "agent", new String[] {"doctor"});
    assertEquals(List.of("Minecraft Agent health: ONLINE"), healthyMessages);

    var degradedMessages = new ArrayList<String>();
    var degraded =
        new AgentStatus(
            AgentState.ONLINE,
            DesiredMode.ENABLED,
            AgentHealth.DEGRADED,
            null,
            null,
            List.of("OPTIONAL_CAPABILITY_UNAVAILABLE"));
    command(degraded, new RecordingControl(), ignored -> false, Runnable::run)
        .execute(sender(degradedMessages, true), "agent", new String[] {"doctor"});

    assertEquals(
        List.of("Minecraft Agent health: DEGRADED", "Warning: OPTIONAL_CAPABILITY_UNAVAILABLE"),
        degradedMessages);
  }

  @Test
  void onlineRootAndInvalidArgumentsUseStableOutput() {
    var messages = new ArrayList<String>();
    var command = command(healthy(), new RecordingControl(), ignored -> false, Runnable::run);
    var sender = sender(messages, true);

    command.execute(sender, "agent", new String[0]);
    command.execute(sender, "agent", new String[] {"unknown"});

    assertEquals(List.of("Minecraft Agent: ONLINE", "/agent [doctor|off|on]"), messages);
  }

  @Test
  void tabCompletionIsStatePermissionAndAuthorizationAware() {
    var permitted = sender(new ArrayList<>(), true);
    var denied = sender(new ArrayList<>(), false);

    var onlineAuthorized =
        command(healthy(), new RecordingControl(), ignored -> true, Runnable::run);
    assertEquals(
        List.of("doctor", "off"),
        onlineAuthorized.tabComplete(permitted, "agent", new String[] {""}));
    assertEquals(List.of("off"), onlineAuthorized.tabComplete(denied, "agent", new String[] {"o"}));

    var onlineUnauthorized =
        command(healthy(), new RecordingControl(), ignored -> false, Runnable::run);
    assertEquals(
        List.of("doctor"), onlineUnauthorized.tabComplete(permitted, "agent", new String[] {"d"}));
    assertEquals(List.of(), onlineUnauthorized.tabComplete(denied, "agent", new String[] {""}));

    var offlineAuthorized =
        command(offline(), new RecordingControl(), ignored -> true, Runnable::run);
    assertEquals(
        List.of("on", "off"), offlineAuthorized.tabComplete(denied, "agent", new String[] {"O"}));
    assertEquals(
        List.of(), offlineAuthorized.tabComplete(denied, "agent", new String[] {"on", "extra"}));
  }

  private static void assertRecoveryMessage(
      AgentControl.RecoveryDisposition disposition, String expected) {
    var messages = new ArrayList<String>();
    var control = new RecordingControl();
    control.recovery = recovery(disposition, CompletableFuture.completedFuture(false));
    var command = command(offline(), control, ignored -> true, Runnable::run);

    command.execute(sender(messages, false), "agent", new String[] {"on"});

    assertEquals(1, control.onCalls);
    assertEquals(List.of(expected), messages);
  }

  private static AgentControl.RecoveryRequest recovery(
      AgentControl.RecoveryDisposition disposition, CompletionStage<Boolean> completion) {
    return new AgentControl.RecoveryRequest(disposition, completion);
  }

  private static AgentCommand command(
      AgentStatus status,
      AgentControl control,
      ToggleAuthorizer authorizer,
      java.util.function.Consumer<Runnable> dispatcher) {
    return new AgentCommand(plugin(), () -> status, control, authorizer, dispatcher);
  }

  private static AgentStatus healthy() {
    return new AgentStatus(
        AgentState.ONLINE, DesiredMode.ENABLED, AgentHealth.HEALTHY, null, null, List.of());
  }

  private static AgentStatus offline() {
    return new AgentStatus(
        AgentState.OFFLINE,
        DesiredMode.DISABLED,
        AgentHealth.UNAVAILABLE,
        OfflineReason.MANUAL,
        null,
        List.of());
  }

  private static Plugin plugin() {
    return (Plugin)
        Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static CommandSender sender(List<String> messages, boolean permission) {
    return (CommandSender)
        Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("hasPermission")) {
                return permission;
              }
              if (method.getName().equals("sendMessage") && arguments != null) {
                for (var argument : arguments) {
                  if (argument instanceof String message) {
                    messages.add(message);
                  } else if (argument instanceof String[] batch) {
                    messages.addAll(List.of(batch));
                  }
                }
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class || type == short.class || type == int.class || type == long.class) {
      return 0;
    }
    if (type == float.class || type == double.class) {
      return 0.0;
    }
    return null;
  }

  private static final class RecordingControl implements AgentControl {
    private int offCalls;
    private int onCalls;
    private RecoveryRequest recovery =
        recovery(RecoveryDisposition.UNAVAILABLE, CompletableFuture.completedFuture(false));

    @Override
    public void turnOff() {
      offCalls++;
    }

    @Override
    public RecoveryRequest turnOn() {
      onCalls++;
      return recovery;
    }
  }

  private static final class QueuedReplies {
    private final Queue<Runnable> replies = new ArrayDeque<>();

    void dispatch(Runnable reply) {
      replies.add(reply);
    }

    void drain() {
      while (!replies.isEmpty()) {
        replies.remove().run();
      }
    }
  }
}
