package dev.minecraftagent.paper.startup;

import java.util.Objects;

public record StartupWarning(Code code, Stage stage) {
  public enum Code {
    OPTIONAL_CAPABILITY_UNAVAILABLE
  }

  public enum Stage {
    OPTIONAL_CAPABILITIES
  }

  public StartupWarning {
    Objects.requireNonNull(code);
    Objects.requireNonNull(stage);
  }
}
