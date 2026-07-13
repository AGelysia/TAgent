package dev.minecraftagent.paper.command;

import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.request.AgentModule;
import dev.minecraftagent.paper.request.AgentRequestGateway;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class AgentCommand extends Command implements PluginIdentifiableCommand {
  public static final String PERMISSION = "minecraftagent.command.agent";
  public static final String USE_PERMISSION = "minecraftagent.use";
  public static final String MODULE_PERMISSION = "minecraftagent.module";
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
  private static final String PLAYER_ONLY_MESSAGE = "This command can only be used by a player.";
  private static final String REQUEST_ACTIVE_MESSAGE = "AI request already in progress.";
  private static final String REQUEST_UNAVAILABLE_MESSAGE = "AI unavailable. Try again later.";
  private static final String MESSAGE_INVALID_MESSAGE =
      "AI message must contain 1 to 4096 characters.";
  private static final String SESSION_INVALID_MESSAGE = "AI session ID must be a canonical UUID.";
  private static final String MODULE_UNKNOWN_MESSAGE = "Unknown AI module. Use /agent module list.";
  private static final String MODULE_LIST_PREFIX = "AI modules: ";

  private final Plugin plugin;
  private final Supplier<AgentStatus> status;
  private final AgentControl control;
  private final AgentRequestGateway requests;
  private final ToggleAuthorizer toggleAuthorizer;
  private final Consumer<Runnable> replyDispatcher;

  public AgentCommand(
      Plugin plugin,
      Supplier<AgentStatus> status,
      AgentControl control,
      ToggleAuthorizer toggleAuthorizer,
      Consumer<Runnable> replyDispatcher) {
    this(
        plugin,
        status,
        control,
        (playerId, message) -> AgentRequestGateway.Submission.RUNTIME_UNAVAILABLE,
        toggleAuthorizer,
        replyDispatcher);
  }

  public AgentCommand(
      Plugin plugin,
      Supplier<AgentStatus> status,
      AgentControl control,
      AgentRequestGateway requests,
      ToggleAuthorizer toggleAuthorizer,
      Consumer<Runnable> replyDispatcher) {
    super(
        "agent",
        "Controls Minecraft Agent readiness",
        "/agent [say <message>|resume [session]|module list|module <name> <message>|doctor|off|on]",
        List.of());
    this.plugin = Objects.requireNonNull(plugin);
    this.status = Objects.requireNonNull(status);
    this.control = Objects.requireNonNull(control);
    this.requests = Objects.requireNonNull(requests);
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

    if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("say")) {
      say(sender, arguments);
      return true;
    }
    if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("resume")) {
      resume(sender, arguments);
      return true;
    }
    if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("module")) {
      module(sender, arguments);
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
    var online = status.get().state() == AgentState.ONLINE;
    if (arguments.length == 2
        && online
        && arguments[0].equalsIgnoreCase("module")
        && sender.hasPermission(MODULE_PERMISSION)) {
      var prefix = arguments[1].toLowerCase(Locale.ROOT);
      return java.util.stream.Stream.concat(
              java.util.stream.Stream.of("list"), AgentModule.protocolNames().stream())
          .filter(candidate -> candidate.startsWith(prefix))
          .toList();
    }
    if (arguments.length != 1) {
      return List.of();
    }

    var candidates = new ArrayList<String>(6);
    if (online) {
      if (sender.hasPermission(USE_PERMISSION)) {
        candidates.add("say");
        candidates.add("resume");
      }
      if (sender.hasPermission(MODULE_PERMISSION)) {
        candidates.add("module");
      }
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

  private void say(CommandSender sender, String[] arguments) {
    if (!sender.hasPermission(USE_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(PLAYER_ONLY_MESSAGE);
      return;
    }
    if (arguments.length < 2) {
      sender.sendMessage(getUsage());
      return;
    }

    var message = String.join(" ", Arrays.copyOfRange(arguments, 1, arguments.length));
    handleSubmission(sender, requests.submit(player.getUniqueId(), message));
  }

  private void resume(CommandSender sender, String[] arguments) {
    if (!sender.hasPermission(USE_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(PLAYER_ONLY_MESSAGE);
      return;
    }
    if (arguments.length > 2) {
      sender.sendMessage(getUsage());
      return;
    }

    UUID sessionId = null;
    if (arguments.length == 2) {
      sessionId = canonicalUuid(arguments[1]);
      if (sessionId == null) {
        sender.sendMessage(SESSION_INVALID_MESSAGE);
        return;
      }
    }
    handleSubmission(sender, requests.resume(player.getUniqueId(), sessionId));
  }

  private void module(CommandSender sender, String[] arguments) {
    if (!sender.hasPermission(MODULE_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (arguments.length == 2 && arguments[1].equalsIgnoreCase("list")) {
      sender.sendMessage(MODULE_LIST_PREFIX + String.join(", ", AgentModule.protocolNames()));
      return;
    }
    if (!sender.hasPermission(USE_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(PLAYER_ONLY_MESSAGE);
      return;
    }
    if (arguments.length < 3) {
      sender.sendMessage(getUsage());
      return;
    }

    var selected = AgentModule.fromProtocolName(arguments[1]);
    if (selected.isEmpty()) {
      sender.sendMessage(MODULE_UNKNOWN_MESSAGE);
      return;
    }
    var message = String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length));
    handleSubmission(
        sender, requests.submitModule(player.getUniqueId(), selected.orElseThrow(), message));
  }

  private static void handleSubmission(
      CommandSender sender, AgentRequestGateway.Submission submission) {
    switch (submission) {
      case ACCEPTED -> {
        // The terminal response is delivered privately by AgentRequestService.
      }
      case ALREADY_ACTIVE -> sender.sendMessage(REQUEST_ACTIVE_MESSAGE);
      case OFFLINE -> sender.sendMessage(OFFLINE_MESSAGE);
      case RUNTIME_UNAVAILABLE -> sender.sendMessage(REQUEST_UNAVAILABLE_MESSAGE);
      case INVALID_MESSAGE -> sender.sendMessage(MESSAGE_INVALID_MESSAGE);
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      var parsed = UUID.fromString(value);
      return parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException error) {
      return null;
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
