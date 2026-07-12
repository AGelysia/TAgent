package dev.minecraftagent.paper.lifecycle;

import dev.minecraftagent.paper.command.AgentDiagnostics;
import dev.minecraftagent.paper.command.CommandRegistrationFailure;
import dev.minecraftagent.paper.startup.StartupFailure;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import dev.minecraftagent.paper.transport.RuntimeConnector;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class PaperStartupCoordinator implements AutoCloseable {
  @FunctionalInterface
  public interface CoreSelfCheck {
    CoreReadiness run() throws StartupFailure;
  }

  @FunctionalInterface
  public interface MainThreadExecutor {
    void execute(Runnable task);
  }

  public interface CommandGate {
    void register() throws CommandRegistrationFailure;

    void unregister();
  }

  public interface EventSink {
    void failure(String stage, String code);

    void warning(String stage, String code);

    void available(AgentDiagnostics.State state);
  }

  private final ExecutorService worker;
  private final CoreSelfCheck coreSelfCheck;
  private final RuntimeConnector runtimeConnector;
  private final MainThreadExecutor mainThread;
  private final CommandGate commandGate;
  private final BooleanSupplier pluginEnabled;
  private final EventSink events;
  private final AtomicBoolean active = new AtomicBoolean();
  private final AtomicLong generation = new AtomicLong();
  private final AtomicReference<AuthenticatedRuntimeConnection> candidateConnection =
      new AtomicReference<>();
  private final AtomicReference<AgentDiagnostics> diagnostics =
      new AtomicReference<>(AgentDiagnostics.starting());

  private volatile CompletableFuture<?> pipeline;
  private AuthenticatedRuntimeConnection activeConnection;

  public PaperStartupCoordinator(
      ExecutorService worker,
      CoreSelfCheck coreSelfCheck,
      RuntimeConnector runtimeConnector,
      MainThreadExecutor mainThread,
      CommandGate commandGate,
      BooleanSupplier pluginEnabled,
      EventSink events) {
    this.worker = Objects.requireNonNull(worker);
    this.coreSelfCheck = Objects.requireNonNull(coreSelfCheck);
    this.runtimeConnector = Objects.requireNonNull(runtimeConnector);
    this.mainThread = Objects.requireNonNull(mainThread);
    this.commandGate = Objects.requireNonNull(commandGate);
    this.pluginEnabled = Objects.requireNonNull(pluginEnabled);
    this.events = Objects.requireNonNull(events);
  }

  public void start() {
    if (!active.compareAndSet(false, true)) {
      throw new IllegalStateException("Paper startup has already begun");
    }
    diagnostics.set(AgentDiagnostics.starting());
    var currentGeneration = generation.incrementAndGet();

    pipeline =
        CompletableFuture.supplyAsync(this::runCoreCheck, worker)
            .thenCompose(
                readiness ->
                    runtimeConnector
                        .connect(readiness.runtimeSettings())
                        .thenApply(
                            connection ->
                                prepareCandidate(currentGeneration, readiness, connection)))
            .whenComplete(
                (candidate, error) -> {
                  if (!isCurrent(currentGeneration)) {
                    closeCandidate(candidate);
                    return;
                  }
                  if (error != null) {
                    schedule(currentGeneration, () -> finishFailure(error));
                  } else {
                    schedule(currentGeneration, () -> finishSuccess(candidate));
                  }
                })
            .toCompletableFuture();
  }

  public AgentDiagnostics diagnostics() {
    return diagnostics.get();
  }

  public boolean startupComplete() {
    var current = pipeline;
    return current != null && current.isDone();
  }

  @Override
  public void close() {
    active.set(false);
    generation.incrementAndGet();
    commandGate.unregister();

    var candidate = candidateConnection.getAndSet(null);
    if (candidate != null) {
      candidate.close();
    }
    if (activeConnection != null) {
      activeConnection.close();
      activeConnection = null;
    }
    diagnostics.set(
        new AgentDiagnostics(AgentDiagnostics.State.STOPPED, null, java.util.List.of()));
    worker.shutdownNow();
  }

  private CoreReadiness runCoreCheck() {
    try {
      return coreSelfCheck.run();
    } catch (StartupFailure failure) {
      throw new CompletionException(failure);
    } catch (RuntimeException error) {
      throw new CompletionException(
          new RuntimeConnectionFailure("SELF_CHECK_INTERNAL_ERROR", "lifecycle"));
    }
  }

  private Candidate prepareCandidate(
      long currentGeneration, CoreReadiness readiness, AuthenticatedRuntimeConnection connection) {
    Objects.requireNonNull(connection);
    candidateConnection.set(connection);
    connection
        .whenClosed()
        .whenComplete((ignored, error) -> scheduleConnectionLost(currentGeneration, connection));
    if (!isCurrent(currentGeneration)) {
      candidateConnection.compareAndSet(connection, null);
      connection.close();
      throw new CompletionException(
          new RuntimeConnectionFailure("SELF_CHECK_CANCELLED", "lifecycle"));
    }
    return new Candidate(readiness, connection);
  }

  private void finishSuccess(Candidate candidate) {
    var connection = candidate.connection();
    if (!pluginEnabled.getAsBoolean() || !connection.isOpen()) {
      candidateConnection.compareAndSet(connection, null);
      connection.close();
      finishFailure(new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect"));
      return;
    }

    var nextDiagnostics = AgentDiagnostics.available(candidate.readiness().warningCodes());
    diagnostics.set(nextDiagnostics);
    try {
      commandGate.register();
    } catch (CommandRegistrationFailure failure) {
      candidateConnection.compareAndSet(connection, null);
      connection.close();
      finishFailure(failure);
      return;
    } catch (RuntimeException error) {
      candidateConnection.compareAndSet(connection, null);
      connection.close();
      finishFailure(new CommandRegistrationFailure("COMMAND_REGISTRATION_FAILED"));
      return;
    }

    if (!connection.isOpen()) {
      commandGate.unregister();
      candidateConnection.compareAndSet(connection, null);
      connection.close();
      finishFailure(new RuntimeConnectionFailure("RUNTIME_CONNECTION_LOST", "runtime-connect"));
      return;
    }

    candidateConnection.compareAndSet(connection, null);
    activeConnection = connection;
    for (var warning : candidate.readiness().warningCodes()) {
      events.warning("optional-capabilities", warning);
    }
    events.available(nextDiagnostics.state());
  }

  private void finishFailure(Throwable error) {
    var failure = unwrap(error);
    String stage;
    String code;
    if (failure instanceof StartupFailure startupFailure) {
      stage = startupFailure.stage().name();
      code = startupFailure.code().name();
    } else if (failure instanceof RuntimeConnectionFailure connectionFailure) {
      stage = connectionFailure.stage();
      code = connectionFailure.code();
    } else if (failure instanceof CommandRegistrationFailure registrationFailure) {
      stage = "command-registration";
      code = registrationFailure.code();
    } else {
      stage = "lifecycle";
      code = "SELF_CHECK_INTERNAL_ERROR";
    }
    diagnostics.set(AgentDiagnostics.unavailable(code));
    events.failure(stage, code);
  }

  private void scheduleConnectionLost(
      long currentGeneration, AuthenticatedRuntimeConnection connection) {
    if (!isCurrent(currentGeneration)) {
      return;
    }
    schedule(
        currentGeneration,
        () -> {
          if (activeConnection != connection) {
            return;
          }
          activeConnection = null;
          commandGate.unregister();
          diagnostics.set(AgentDiagnostics.unavailable("RUNTIME_CONNECTION_LOST"));
          events.failure("runtime-connect", "RUNTIME_CONNECTION_LOST");
        });
  }

  private void schedule(long expectedGeneration, Runnable task) {
    if (!isCurrent(expectedGeneration)) {
      return;
    }
    try {
      mainThread.execute(
          () -> {
            if (isCurrent(expectedGeneration)) {
              task.run();
            }
          });
    } catch (RuntimeException error) {
      var candidate = candidateConnection.getAndSet(null);
      if (candidate != null) {
        candidate.close();
      }
    }
  }

  private boolean isCurrent(long expectedGeneration) {
    return active.get() && generation.get() == expectedGeneration;
  }

  private static void closeCandidate(Candidate candidate) {
    if (candidate != null) {
      candidate.connection().close();
    }
  }

  private static Throwable unwrap(Throwable error) {
    var current = error;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private record Candidate(CoreReadiness readiness, AuthenticatedRuntimeConnection connection) {}
}
