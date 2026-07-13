package dev.minecraftagent.paper.client;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validated client hello. It contains no player identity or server authority. */
public record ClientHandshake(
    String clientProtocolVersion,
    String modVersion,
    ClientCapabilities capabilities,
    Map<String, String> dependencies) {
  public static final String PROTOCOL_VERSION = "1.0";

  private static final Pattern MOD_VERSION =
      Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?");

  public ClientHandshake {
    if (!PROTOCOL_VERSION.equals(clientProtocolVersion)) {
      throw new ClientProtocolException("CLIENT_PROTOCOL_INCOMPATIBLE");
    }
    if (modVersion == null
        || modVersion.length() > 64
        || !MOD_VERSION.matcher(modVersion).matches()) {
      throw new ClientProtocolException("CLIENT_MOD_VERSION_INVALID");
    }
    Objects.requireNonNull(capabilities);
    dependencies = validateDependencies(dependencies);
    if ((capabilities.version(ClientFeature.LITEMATICA_PREVIEW) == 1
            || capabilities.version(ClientFeature.LITEMATICA_MATERIAL_LIST) == 1)
        && (!dependencies.containsKey("litematica") || !dependencies.containsKey("malilib"))) {
      throw new ClientProtocolException("CLIENT_DEPENDENCY_CLAIM_INVALID");
    }
    if (capabilities.version(ClientFeature.LITEMATICA_MATERIAL_LIST) == 1
        && capabilities.version(ClientFeature.LITEMATICA_PREVIEW) != 1) {
      throw new ClientProtocolException("CLIENT_DEPENDENCY_CLAIM_INVALID");
    }
  }

  public Optional<String> dependencyVersion(String dependency) {
    return Optional.ofNullable(dependencies.get(dependency));
  }

  private static Map<String, String> validateDependencies(Map<String, String> dependencies) {
    Objects.requireNonNull(dependencies);
    if (!dependencies.keySet().equals(java.util.Set.of("litematica", "malilib"))) {
      throw new ClientProtocolException("CLIENT_DEPENDENCIES_INVALID");
    }
    var result = new java.util.LinkedHashMap<String, String>();
    for (var name : java.util.List.of("litematica", "malilib")) {
      var value = dependencies.get(name);
      if (value != null) {
        if (value.isEmpty()
            || value.length() > 64
            || value.chars().anyMatch(ClientHandshake::unsafe)) {
          throw new ClientProtocolException("CLIENT_DEPENDENCY_VERSION_INVALID");
        }
        result.put(name, value);
      }
    }
    return Map.copyOf(result);
  }

  private static boolean unsafe(int value) {
    return value < 0x20 || value > 0x7e;
  }
}
