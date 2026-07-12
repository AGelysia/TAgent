package dev.minecraftagent.paper.transport;

import java.util.concurrent.CompletionStage;

public interface AuthenticatedRuntimeConnection extends AutoCloseable {
  boolean isOpen();

  CompletionStage<Void> whenClosed();

  @Override
  void close();
}
