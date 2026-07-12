package dev.minecraftagent.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class AgentCommandTest {
  @Test
  void doctorReportsOnlyStableHealthAndWarningCodes() {
    var messages = new ArrayList<String>();
    var command =
        new AgentCommand(
            plugin(), () -> AgentDiagnostics.available(List.of("OPTIONAL_CAPABILITY_UNAVAILABLE")));
    var sender = sender(messages);

    assertTrue(command.execute(sender, "agent", new String[] {"doctor"}));

    assertEquals(
        List.of("Minecraft Agent health: DEGRADED", "Warning: OPTIONAL_CAPABILITY_UNAVAILABLE"),
        messages);
    assertEquals(List.of("doctor"), command.tabComplete(sender, "agent", new String[] {"do"}));
  }

  private static Plugin plugin() {
    return (Plugin)
        Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static CommandSender sender(List<String> messages) {
    return (CommandSender)
        Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("hasPermission")) {
                return true;
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
}
