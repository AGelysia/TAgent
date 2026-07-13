package dev.minecraftagent.client.network;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Client presentation features. These values never convey server authority. */
public record ClientHandshakeAdvertisement(
    String modVersion,
    int overlay,
    int itemIcons,
    int recipeView,
    int litematicaPreview,
    int litematicaMaterialList,
    Optional<String> litematicaVersion,
    Optional<String> malilibVersion) {
  private static final Pattern VERSION =
      Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?");

  public ClientHandshakeAdvertisement {
    if (modVersion == null || modVersion.length() > 64 || !VERSION.matcher(modVersion).matches()) {
      throw new IllegalArgumentException("Invalid client Mod version");
    }
    requireFeatureVersion(overlay);
    requireFeatureVersion(itemIcons);
    requireFeatureVersion(recipeView, 2);
    requireFeatureVersion(litematicaPreview);
    requireFeatureVersion(litematicaMaterialList);
    litematicaVersion = Objects.requireNonNull(litematicaVersion);
    malilibVersion = Objects.requireNonNull(malilibVersion);
    litematicaVersion.ifPresent(ClientHandshakeAdvertisement::requireDependencyVersion);
    malilibVersion.ifPresent(ClientHandshakeAdvertisement::requireDependencyVersion);
    if ((litematicaPreview == 1 || litematicaMaterialList == 1)
        && (litematicaVersion.isEmpty() || malilibVersion.isEmpty())) {
      throw new IllegalArgumentException(
          "Litematica capabilities require both dependency versions");
    }
    if (litematicaMaterialList == 1 && litematicaPreview != 1) {
      throw new IllegalArgumentException("Material List requires preview support");
    }
  }

  private static void requireFeatureVersion(int version) {
    requireFeatureVersion(version, 1);
  }

  private static void requireFeatureVersion(int version, int maximum) {
    if (version < 0 || version > maximum) {
      throw new IllegalArgumentException("Unsupported client feature version");
    }
  }

  private static void requireDependencyVersion(String version) {
    if (version.isEmpty()
        || version.length() > 64
        || version.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
      throw new IllegalArgumentException("Invalid dependency version");
    }
  }
}
