package dev.minecraftagent.paper.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.command.AgentDiagnostics;
import dev.minecraftagent.paper.command.CommandRegistrationFailure;
import dev.minecraftagent.paper.startup.StartupFailure;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import dev.minecraftagent.paper.transport.RuntimeConnector;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PaperStartupCoordinatorTest {
  @Test
  void returnsImmediatelyAndRegistersExactlyOnceAfterAuthentication() throws Exception {
    var connector = new FakeConnector();
    var main = new QueuedMainThread();
    var commands = new FakeCommandGate();
    var events = new RecordingEvents();
    var releaseCheck = new CompletableFuture<Void>();
    var coordinator =
        coordinator(
            () -> {
              releaseCheck.join();
              return readiness(List.of());
            },
            connector,
            main,
            commands,
            () -> true,
            events);

    coordinator.start();
    assertEquals(0, commands.registerCalls);
    assertEquals(AgentDiagnostics.State.STARTING, coordinator.diagnostics().state());

    releaseCheck.complete(null);
    var connection = new FakeConnection();
    await(() -> connector.connectCalls == 1);
    connector.result.complete(connection);
    await(main::hasTasks);
    main.drain();

    assertEquals(1, commands.registerCalls);
    assertEquals(AgentDiagnostics.State.ONLINE, coordinator.diagnostics().state());
    assertEquals(List.of("ONLINE"), events.availableStates);
    coordinator.close();
  }

  @Test
  void coreFailureNeverAttemptsRuntimeOrRegistration() throws Exception {
    var connector = new FakeConnector();
    var main = new QueuedMainThread();
    var commands = new FakeCommandGate();
    var events = new RecordingEvents();
    var coordinator =
        coordinator(
            () -> {
              throw new StartupFailure(
                  StartupFailure.Code.STATE_DIRECTORY_UNSAFE, StartupFailure.Stage.STATE);
            },
            connector,
            main,
            commands,
            () -> true,
            events);

    coordinator.start();
    await(main::hasTasks);
    main.drain();

    assertEquals(0, connector.connectCalls);
    assertEquals(0, commands.registerCalls);
    assertEquals("STATE_DIRECTORY_UNSAFE", coordinator.diagnostics().failureCode());
    assertEquals(List.of("STATE:STATE_DIRECTORY_UNSAFE"), events.failures);
    coordinator.close();
  }

  @Test
  void runtimeAuthenticationFailureNeverRegisters() throws Exception {
    var connector = new FakeConnector();
    var main = new QueuedMainThread();
    var commands = new FakeCommandGate();
    var events = new RecordingEvents();
    var coordinator =
        coordinator(() -> readiness(List.of()), connector, main, commands, () -> true, events);

    coordinator.start();
    await(() -> connector.connectCalls == 1);
    connector.result.completeExceptionally(
        new RuntimeConnectionFailure("TOKEN_AUTH_FAILED", "authentication"));
    await(main::hasTasks);
    main.drain();

    assertEquals(0, commands.registerCalls);
    assertEquals("TOKEN_AUTH_FAILED", coordinator.diagnostics().failureCode());
    coordinator.close();
  }

  @Test
  void optionalCapabilityFailureRegistersInDegradedHealth() throws Exception {
    var connector = new FakeConnector();
    var main = new QueuedMainThread();
    var commands = new FakeCommandGate();
    var events = new RecordingEvents();
    var coordinator =
        coordinator(
            () -> readiness(List.of("OPTIONAL_CAPABILITY_UNAVAILABLE")),
            connector,
            main,
            commands,
            () -> true,
            events);

    coordinator.start();
    await(() -> connector.connectCalls == 1);
    connector.result.complete(new FakeConnection());
    await(main::hasTasks);
    main.drain();

    assertEquals(1, commands.registerCalls);
    assertEquals(AgentDiagnostics.State.DEGRADED, coordinator.diagnostics().state());
    assertEquals(List.of("optional-capabilities:OPTIONAL_CAPABILITY_UNAVAILABLE"), events.warnings);
    coordinator.close();
  }

  @Test
  void disableDuringHandshakeClosesLateSuccessWithoutRegistration() throws Exception {
    var connector = new FakeConnector();
    var main = new QueuedMainThread();
    var commands = new FakeCommandGate();
    var coordinator =
        coordinator(
            () -> readiness(List.of()),
            connector,
            main,
            commands,
            () -> false,
            new RecordingEvents());

    coordinator.start();
    await(() -> connector.connectCalls == 1);
    coordinator.close();
    var connection = new FakeConnection();
    connector.result.complete(connection);
    await(() -> connection.closeCalls > 0);
    main.drain();

    assertEquals(0, commands.registerCalls);
    assertFalse(connection.isOpen());
    assertEquals(AgentDiagnostics.State.STOPPED, coordinator.diagnostics().state());
  }

  @Test
  void commandConflictClosesTheAuthenticatedConnection() throws Exception {
    var connector = new FakeConnector();
    var main = new QueuedMainThread();
    var commands = new FakeCommandGate();
    commands.registrationFailure = new CommandRegistrationFailure("COMMAND_LABEL_CONFLICT");
    var coordinator =
        coordinator(
            () -> readiness(List.of()),
            connector,
            main,
            commands,
            () -> true,
            new RecordingEvents());
    var connection = new FakeConnection();

    coordinator.start();
    await(() -> connector.connectCalls == 1);
    connector.result.complete(connection);
    await(main::hasTasks);
    main.drain();

    assertFalse(connection.isOpen());
    assertEquals("COMMAND_LABEL_CONFLICT", coordinator.diagnostics().failureCode());
    assertFalse(commands.registered);
    coordinator.close();
  }

  @Test
  void anAuthenticatedConnectionLossRemovesTheCommand() throws Exception {
    var connector = new FakeConnector();
    var main = new QueuedMainThread();
    var commands = new FakeCommandGate();
    var coordinator =
        coordinator(
            () -> readiness(List.of()),
            connector,
            main,
            commands,
            () -> true,
            new RecordingEvents());
    var connection = new FakeConnection();

    coordinator.start();
    await(() -> connector.connectCalls == 1);
    connector.result.complete(connection);
    await(main::hasTasks);
    main.drain();
    assertTrue(commands.registered);

    connection.remoteClose();
    await(main::hasTasks);
    main.drain();

    assertFalse(commands.registered);
    assertEquals("RUNTIME_CONNECTION_LOST", coordinator.diagnostics().failureCode());
    coordinator.close();
  }

  private static PaperStartupCoordinator coordinator(
      PaperStartupCoordinator.CoreSelfCheck check,
      RuntimeConnector connector,
      QueuedMainThread main,
      FakeCommandGate commands,
      BooleanSupplier enabled,
      RecordingEvents events) {
    return new PaperStartupCoordinator(
        Executors.newSingleThreadExecutor(), check, connector, main, commands, enabled, events);
  }

  private static CoreReadiness readiness(List<String> warnings) {
    return new CoreReadiness(
        new RuntimeConnectionSettings(
            URI.create("ws://127.0.0.1:38127/agent"),
            "survival-main",
            "phase-3-public-test-token-32-characters",
            "0.1.0",
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)),
        warnings);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  private static final class FakeConnector implements RuntimeConnector {
    private final CompletableFuture<AuthenticatedRuntimeConnection> result =
        new CompletableFuture<>();
    private volatile int connectCalls;

    @Override
    public CompletionStage<AuthenticatedRuntimeConnection> connect(
        RuntimeConnectionSettings settings) {
      connectCalls++;
      return result;
    }
  }

  private static final class FakeConnection implements AuthenticatedRuntimeConnection {
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile int closeCalls;

    @Override
    public boolean isOpen() {
      return open.get();
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return closed;
    }

    @Override
    public void close() {
      closeCalls++;
      remoteClose();
    }

    void remoteClose() {
      open.set(false);
      closed.complete(null);
    }
  }

  private static final class QueuedMainThread
      implements PaperStartupCoordinator.MainThreadExecutor {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable task) {
      tasks.add(task);
    }

    synchronized boolean hasTasks() {
      return !tasks.isEmpty();
    }

    void drain() {
      while (true) {
        Runnable task;
        synchronized (this) {
          task = tasks.poll();
        }
        if (task == null) {
          return;
        }
        task.run();
      }
    }
  }

  private static final class FakeCommandGate implements PaperStartupCoordinator.CommandGate {
    private int registerCalls;
    private boolean registered;
    private CommandRegistrationFailure registrationFailure;

    @Override
    public void register() {
      registerCalls++;
      if (registrationFailure != null) {
        throw registrationFailure;
      }
      registered = true;
    }

    @Override
    public void unregister() {
      registered = false;
    }
  }

  private static final class RecordingEvents implements PaperStartupCoordinator.EventSink {
    private final List<String> failures = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> availableStates = new ArrayList<>();

    @Override
    public void failure(String stage, String code) {
      failures.add(stage + ":" + code);
    }

    @Override
    public void warning(String stage, String code) {
      warnings.add(stage + ":" + code);
    }

    @Override
    public void available(AgentDiagnostics.State state) {
      availableStates.add(state.name());
    }
  }
}
