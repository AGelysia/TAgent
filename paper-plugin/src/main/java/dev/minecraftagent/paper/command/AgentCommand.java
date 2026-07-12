package dev.minecraftagent.paper.command;

import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

public final class AgentCommand extends Command implements PluginIdentifiableCommand {
  public static final String PERMISSION = "minecraftagent.command.agent";
  public static final String TOGGLE_PERMISSION = "minecraftagent.admin.toggle";

  private static final String OFFLINE_MESSAGE = "AI offline";
  private static final String ONLINE_MESSAGE = "AI online";
  private static final String RECOVERY_STARTED_MESSAGE = "AI startup check started.";
  private static final String RECOVERY_ALREADY_STARTED_MESSAGE =
      "AI startup check already in progress.";
  private static final String RECOVERY_FAILED_MESSAGE =
      "AI remains offline. Check the server console.";
  private static final String PERMISSION_DENIED_MESSAGE =
      "You do not have permission to use this command.";

  private final Plugin plugin;
  private final Supplier<AgentStatus> status;
  private final AgentControl control;
  private final ToggleAuthorizer toggleAuthorizer;
  private final Consumer<Runnable> replyDispatcher;

  public AgentCommand(
      Plugin plugin,
      Supplier<AgentStatus> status,
      AgentControl control,
      ToggleAuthorizer toggleAuthorizer,
      Consumer<Runnable> replyDispatcher) {
    super("agent", "Controls Minecraft Agent readiness", "/agent [doctor|off|on]", List.of());
    this.plugin = Objects.requireNonNull(plugin);
    this.status = Objects.requireNonNull(status);
    this.control = Objects.requireNonNull(control);
    this.toggleAuthorizer = Objects.requireNonNull(toggleAuthorizer);
    this.replyDispatcher = Objects.requireNonNull(replyDispatcher);
  }

  @Override
  public Plugin getPlugin() {
    return plugin;
  }

  @Override
  public boolean execute(CommandSender sender, String commandLabel, String[] arguments) {
    var snapshot = status.get();
    var exactToggle = arguments.length == 1 && isToggle(arguments[0]);
    if (snapshot.state() != AgentState.ONLINE && !exactToggle) {
      sender.sendMessage(OFFLINE_MESSAGE);
      return true;
    }

    if (exactToggle) {
      if (!toggleAuthorizer.canToggle(sender)) {
        sender.sendMessage(PERMISSION_DENIED_MESSAGE);
        return true;
      }
      if (arguments[0].equalsIgnoreCase("off")) {
        control.turnOff();
        sender.sendMessage(OFFLINE_MESSAGE);
      } else {
        turnOn(sender);
      }
      return true;
    }

    if (!sender.hasPermission(PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return true;
    }
    if (arguments.length == 0) {
      sender.sendMessage("Minecraft Agent: " + snapshot.state().name());
      return true;
    }
    if (arguments.length == 1 && arguments[0].equalsIgnoreCase("doctor")) {
      sender.sendMessage("Minecraft Agent health: " + healthLabel(snapshot.health()));
      if (snapshot.failureCode() != null) {
        sender.sendMessage("Core: " + snapshot.failureCode());
      }
      for (var warning : snapshot.warningCodes()) {
        sender.sendMessage("Warning: " + warning);
      }
      return true;
    }
    sender.sendMessage(getUsage());
    return true;
  }

  @Override
  public List<String> tabComplete(CommandSender sender, String alias, String[] arguments)
      throws IllegalArgumentException {
    if (arguments.length != 1) {
      return List.of();
    }

    var candidates = new ArrayList<String>(2);
    if (status.get().state() == AgentState.ONLINE) {
      if (sender.hasPermission(PERMISSION)) {
        candidates.add("doctor");
      }
      if (toggleAuthorizer.canToggle(sender)) {
        candidates.add("off");
      }
    } else if (toggleAuthorizer.canToggle(sender)) {
      candidates.add("on");
      candidates.add("off");
    }

    var prefix = arguments[0].toLowerCase(Locale.ROOT);
    return candidates.stream().filter(candidate -> candidate.startsWith(prefix)).toList();
  }

  private void turnOn(CommandSender sender) {
    var request = control.turnOn();
    switch (request.disposition()) {
      case ALREADY_ONLINE -> sender.sendMessage(ONLINE_MESSAGE);
      case ALREADY_STARTING -> sender.sendMessage(RECOVERY_ALREADY_STARTED_MESSAGE);
      case UNAVAILABLE -> sender.sendMessage(RECOVERY_FAILED_MESSAGE);
      case STARTED -> {
        sender.sendMessage(RECOVERY_STARTED_MESSAGE);
        request
            .completion()
            .whenComplete(
                (online, error) ->
                    replyDispatcher.accept(
                        () ->
                            sender.sendMessage(
                                error == null && Boolean.TRUE.equals(online)
                                    ? ONLINE_MESSAGE
                                    : RECOVERY_FAILED_MESSAGE)));
      }
    }
  }

  private static boolean isToggle(String argument) {
    return argument.equalsIgnoreCase("on") || argument.equalsIgnoreCase("off");
  }

  private static String healthLabel(AgentHealth health) {
    return switch (health) {
      case HEALTHY -> "ONLINE";
      case DEGRADED -> "DEGRADED";
      case UNAVAILABLE -> "UNAVAILABLE";
    };
  }
}
