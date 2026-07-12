package dev.minecraftagent.paper.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

public final class AgentCommand extends Command implements PluginIdentifiableCommand {
  public static final String PERMISSION = "minecraftagent.command.agent";

  private final Plugin plugin;
  private final Supplier<AgentDiagnostics> diagnostics;

  public AgentCommand(Plugin plugin, Supplier<AgentDiagnostics> diagnostics) {
    super("agent", "Shows Minecraft Agent readiness", "/agent [doctor]", List.of());
    this.plugin = Objects.requireNonNull(plugin);
    this.diagnostics = Objects.requireNonNull(diagnostics);
    setPermission(PERMISSION);
  }

  @Override
  public Plugin getPlugin() {
    return plugin;
  }

  @Override
  public boolean execute(CommandSender sender, String commandLabel, String[] arguments) {
    if (!testPermission(sender)) {
      return true;
    }
    var snapshot = diagnostics.get();
    if (arguments.length == 0) {
      sender.sendMessage("Minecraft Agent: " + snapshot.state().name());
      return true;
    }
    if (arguments.length == 1 && arguments[0].equalsIgnoreCase("doctor")) {
      sender.sendMessage("Minecraft Agent health: " + snapshot.state().name());
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
    if (!testPermissionSilent(sender) || arguments.length != 1) {
      return List.of();
    }
    var prefix = arguments[0].toLowerCase(Locale.ROOT);
    return "doctor".startsWith(prefix) ? List.of("doctor") : List.of();
  }
}
