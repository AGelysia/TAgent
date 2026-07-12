package dev.minecraftagent.paper.command;

import java.util.List;

public record AgentDiagnostics(State state, String failureCode, List<String> warningCodes) {
  public AgentDiagnostics {
    warningCodes = List.copyOf(warningCodes);
  }

  public static AgentDiagnostics starting() {
    return new AgentDiagnostics(State.STARTING, null, List.of());
  }

  public static AgentDiagnostics available(List<String> warningCodes) {
    return new AgentDiagnostics(
        warningCodes.isEmpty() ? State.ONLINE : State.DEGRADED, null, warningCodes);
  }

  public static AgentDiagnostics unavailable(String failureCode) {
    return new AgentDiagnostics(State.UNAVAILABLE, failureCode, List.of());
  }

  public enum State {
    STARTING,
    ONLINE,
    DEGRADED,
    UNAVAILABLE,
    STOPPED
  }
}
