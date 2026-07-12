package dev.minecraftagent.paper.lifecycle;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.util.List;
import java.util.Objects;

public record CoreReadiness(RuntimeConnectionSettings runtimeSettings, List<String> warningCodes) {
  public CoreReadiness {
    Objects.requireNonNull(runtimeSettings);
    warningCodes = List.copyOf(warningCodes);
  }
}
