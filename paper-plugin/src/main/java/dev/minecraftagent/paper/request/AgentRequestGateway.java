package dev.minecraftagent.paper.request;

import java.util.UUID;

@FunctionalInterface
public interface AgentRequestGateway {
  enum Submission {
    ACCEPTED,
    ALREADY_ACTIVE,
    OFFLINE,
    RUNTIME_UNAVAILABLE,
    INVALID_MESSAGE
  }

  Submission submit(UUID playerId, String message);
}
