package dev.minecraftagent.paper.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minecraftagent.paper.request.AgentModule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReadToolRegistry {
  public static final Set<String> REQUIRED_TOOL_IDS =
      Set.of(
          "player.context.read",
          "player.held_item.read",
          "server.info.read",
          "server.plugins.list",
          "server.recipe.lookup",
          "server.recipe.uses");

  private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]{1,240}");
  private final Map<String, Descriptor> descriptors;

  public ReadToolRegistry() {
    var entries = new LinkedHashMap<String, Descriptor>();
    register(
        entries,
        "player.context.read",
        ArgumentKind.NONE,
        ReadToolResult.Source.PAPER_API,
        Set.of(
            AgentModule.GENERAL,
            AgentModule.GUIDE,
            AgentModule.LOCATE,
            AgentModule.BUILD,
            AgentModule.PROJECT));
    register(
        entries,
        "player.held_item.read",
        ArgumentKind.NONE,
        ReadToolResult.Source.PAPER_API,
        Set.of(AgentModule.GENERAL, AgentModule.RECIPE, AgentModule.GUIDE, AgentModule.BUILD));
    register(
        entries,
        "server.info.read",
        ArgumentKind.NONE,
        ReadToolResult.Source.PAPER_API,
        Set.of(
            AgentModule.GENERAL,
            AgentModule.GUIDE,
            AgentModule.LOCATE,
            AgentModule.BUILD,
            AgentModule.PROJECT));
    register(
        entries,
        "server.plugins.list",
        ArgumentKind.NONE,
        ReadToolResult.Source.PAPER_API,
        Set.of(AgentModule.GENERAL, AgentModule.GUIDE, AgentModule.PROJECT));
    register(
        entries,
        "server.recipe.lookup",
        ArgumentKind.ITEM_ID,
        ReadToolResult.Source.SERVER_REGISTRY,
        Set.of(AgentModule.GENERAL, AgentModule.RECIPE, AgentModule.GUIDE));
    register(
        entries,
        "server.recipe.uses",
        ArgumentKind.ITEM_ID,
        ReadToolResult.Source.SERVER_REGISTRY,
        Set.of(AgentModule.GENERAL, AgentModule.RECIPE, AgentModule.GUIDE));
    if (!entries.keySet().equals(REQUIRED_TOOL_IDS)) {
      throw new IllegalStateException("Core read tool registry is incomplete");
    }
    descriptors = Map.copyOf(entries);
  }

  public List<Descriptor> descriptors() {
    return descriptors.values().stream()
        .sorted(java.util.Comparator.comparing(Descriptor::id))
        .toList();
  }

  public Optional<Descriptor> find(String id) {
    return Optional.ofNullable(descriptors.get(id));
  }

  public Validation validate(String id, AgentModule module, JsonObject arguments) {
    Objects.requireNonNull(module);
    Objects.requireNonNull(arguments);
    var descriptor = descriptors.get(id);
    if (descriptor == null) {
      return Validation.rejected(
          ReadToolResult.rejected(
              ReadToolResult.Source.PAPER_POLICY,
              "TOOL_UNKNOWN",
              "The requested tool is not registered."));
    }
    if (!descriptor.modules().contains(module)) {
      return Validation.rejected(
          ReadToolResult.rejected(
              ReadToolResult.Source.PAPER_POLICY,
              "TOOL_NOT_ALLOWED",
              "The requested tool is not allowed for this module."));
    }
    if (!validArguments(descriptor.argumentKind(), arguments)) {
      return Validation.rejected(
          ReadToolResult.rejected(
              ReadToolResult.Source.PAPER_POLICY,
              "TOOL_ARGUMENTS_INVALID",
              "The tool arguments do not match its closed schema."));
    }
    return Validation.accepted(descriptor);
  }

  private static boolean validArguments(ArgumentKind kind, JsonObject arguments) {
    if (kind == ArgumentKind.NONE) {
      return arguments.isEmpty();
    }
    if (!arguments.keySet().equals(Set.of("itemId"))) {
      return false;
    }
    JsonElement value = arguments.get("itemId");
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      return false;
    }
    var itemId = value.getAsString();
    return itemId.length() <= 256 && ITEM_ID.matcher(itemId).matches();
  }

  private static void register(
      Map<String, Descriptor> target,
      String id,
      ArgumentKind argumentKind,
      ReadToolResult.Source source,
      Set<AgentModule> modules) {
    var descriptor = new Descriptor(id, argumentKind, source, modules);
    if (target.putIfAbsent(id, descriptor) != null) {
      throw new IllegalStateException("Duplicate read tool descriptor");
    }
  }

  public enum ArgumentKind {
    NONE,
    ITEM_ID
  }

  public record Descriptor(
      String id,
      ArgumentKind argumentKind,
      ReadToolResult.Source source,
      Set<AgentModule> modules) {
    public Descriptor {
      Objects.requireNonNull(id);
      Objects.requireNonNull(argumentKind);
      Objects.requireNonNull(source);
      modules = Set.copyOf(modules);
    }
  }

  public record Validation(Descriptor descriptor, ReadToolResult rejection) {
    private static Validation accepted(Descriptor descriptor) {
      return new Validation(Objects.requireNonNull(descriptor), null);
    }

    private static Validation rejected(ReadToolResult rejection) {
      return new Validation(null, Objects.requireNonNull(rejection));
    }

    public boolean accepted() {
      return descriptor != null;
    }
  }
}
