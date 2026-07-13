package dev.minecraftagent.client.litematica;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Selects a single exact adapter before attempting to resolve any optional-mod class. */
public final class LitematicaAdapterResolver {
  private LitematicaAdapterResolver() {}

  public static LitematicaCompatibility resolve(
      String minecraftVersion,
      String fabricLoaderVersion,
      ModInventory mods,
      ClassLoader modClassLoader,
      Path managedPreviewRoot,
      BooleanSupplier clientThreadCheck) {
    Objects.requireNonNull(minecraftVersion);
    Objects.requireNonNull(fabricLoaderVersion);
    Objects.requireNonNull(mods);
    Objects.requireNonNull(modClassLoader);
    Objects.requireNonNull(managedPreviewRoot);
    Objects.requireNonNull(clientThreadCheck);

    var litematica = safeVersion(mods, "litematica");
    var malilib = safeVersion(mods, "malilib");
    var detected =
        new LitematicaCompatibility.DetectedVersions(
            minecraftVersion, fabricLoaderVersion, litematica, malilib);
    if (litematica.isEmpty()) {
      return unavailable(LitematicaCompatibility.Status.NOT_INSTALLED, detected);
    }
    if (malilib.isEmpty()) {
      return unavailable(LitematicaCompatibility.Status.DEPENDENCY_MISSING, detected);
    }

    var supported =
        LitematicaSupportMatrix.findExact(
            minecraftVersion, fabricLoaderVersion, litematica.orElseThrow(), malilib.orElseThrow());
    if (supported.isEmpty()) {
      return unavailable(LitematicaCompatibility.Status.UNSUPPORTED_VERSION, detected);
    }

    try {
      var adapter =
          ReflectiveLitematicaAdapter.link(
              supported.orElseThrow(), modClassLoader, managedPreviewRoot, clientThreadCheck);
      return new LitematicaCompatibility(
          LitematicaCompatibility.Status.AVAILABLE, detected, supported, Optional.of(adapter));
    } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
      return new LitematicaCompatibility(
          LitematicaCompatibility.Status.ADAPTER_LINKAGE_FAILED,
          detected,
          supported,
          Optional.empty());
    }
  }

  private static Optional<String> safeVersion(ModInventory mods, String modId) {
    try {
      return mods.version(modId).filter(version -> !version.isBlank());
    } catch (RuntimeException | LinkageError exception) {
      return Optional.empty();
    }
  }

  private static LitematicaCompatibility unavailable(
      LitematicaCompatibility.Status status, LitematicaCompatibility.DetectedVersions versions) {
    return new LitematicaCompatibility(status, versions, Optional.empty(), Optional.empty());
  }
}
