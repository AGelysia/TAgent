package dev.minecraftagent.paper.command;

import java.util.UUID;

/** Sends a fixed presentation-only action to the calling player's negotiated client. */
@FunctionalInterface
public interface AgentUiControl {
  Result invoke(UUID playerUuid, Action action);

  static AgentUiControl unavailable() {
    return (playerUuid, action) -> Result.CLIENT_UNAVAILABLE;
  }

  enum Action {
    PIN,
    UNPIN,
    CLEAR
  }

  enum Result {
    SENT,
    CLIENT_UNAVAILABLE
  }
}
