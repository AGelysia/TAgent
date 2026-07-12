package dev.minecraftagent.paper.transport;

import java.util.concurrent.CompletionStage;

public interface RuntimeConnector {
  CompletionStage<AuthenticatedRuntimeConnection> connect(RuntimeConnectionSettings settings);
}
