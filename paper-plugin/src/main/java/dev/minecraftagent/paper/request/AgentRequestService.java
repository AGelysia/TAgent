package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.lifecycle.OfflineCleanup;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.CancelReason;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentRequestService
    implements AgentRequestGateway,
        RuntimeApplicationLifecycle,
        OfflineCleanup.Control,
        AutoCloseable {
  @FunctionalInterface
  public interface MainThreadExecutor {
    void execute(Runnable task);
  }

  @FunctionalInterface
  public interface PlayerReplySink {
    void send(UUID playerId, String message);
  }

  @FunctionalInterface
  public interface EventSink {
    void event(String code);
  }

  @FunctionalInterface
  interface TimeoutScheduler {
    Cancellable schedule(Duration delay, Runnable task);
  }

  @FunctionalInterface
  interface Cancellable {
    void cancel();
  }

  private static final String TIMEOUT_MESSAGE = "AI request timed out.";
  private static final String UNAVAILABLE_MESSAGE = "AI unavailable. Try again later.";
  private static final int MAX_ACTIVE_REQUESTS = 64;

  private final Object lock = new Object();
  private final OperationalGate operationalGate;
  private final Duration requestTimeout;
  private final TimeoutScheduler timeoutScheduler;
  private final MainThreadExecutor mainThread;
  private final PlayerReplySink replies;
  private final EventSink events;
  private final AtomicReference<ConnectionBinding> connection = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Map<UUID, LiveRequest> requestsById = new HashMap<>();
  private final Map<UUID, LiveRequest> requestsByPlayer = new HashMap<>();

  public AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      ScheduledExecutorService scheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events) {
    this(
        operationalGate,
        requestTimeout,
        (delay, task) -> {
          var future = scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
          return () -> future.cancel(false);
        },
        mainThread,
        replies,
        events);
  }

  AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      TimeoutScheduler timeoutScheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events) {
    this.operationalGate = Objects.requireNonNull(operationalGate);
    this.requestTimeout = Objects.requireNonNull(requestTimeout);
    this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler);
    this.mainThread = Objects.requireNonNull(mainThread);
    this.replies = Objects.requireNonNull(replies);
    this.events = Objects.requireNonNull(events);
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("Request timeout must be positive");
    }
  }

  @Override
  public Submission submit(UUID playerId, String message) {
    Objects.requireNonNull(playerId);
    if (!validMessage(message)) {
      return Submission.INVALID_MESSAGE;
    }
    var permit = operationalGate.tryAcquire();
    if (permit.isEmpty()) {
      return Submission.OFFLINE;
    }
    var binding = connection.get();
    if (binding == null || !binding.connection().isOpen() || closed.get()) {
      return Submission.RUNTIME_UNAVAILABLE;
    }

    var request = new LiveRequest(UUID.randomUUID(), playerId, permit.orElseThrow(), binding);
    synchronized (lock) {
      if (closed.get()
          || connection.get() != binding
          || !binding.connection().isOpen()
          || !operationalGate.revalidate(request.permit())) {
        return Submission.RUNTIME_UNAVAILABLE;
      }
      if (requestsByPlayer.containsKey(playerId)) {
        return Submission.ALREADY_ACTIVE;
      }
      if (requestsById.size() >= MAX_ACTIVE_REQUESTS) {
        return Submission.RUNTIME_UNAVAILABLE;
      }
      requestsById.put(request.requestId(), request);
      requestsByPlayer.put(playerId, request);
      request.timeout(timeoutScheduler.schedule(requestTimeout, () -> timeout(request)));
    }

    try {
      binding
          .connection()
          .sendApplication(binding.codec().encodeRequest(request.requestId(), playerId, message))
          .whenComplete(
              (ignored, error) -> {
                if (error != null) {
                  sendFailed(request);
                }
              });
    } catch (RuntimeException error) {
      sendFailed(request, false);
      return Submission.RUNTIME_UNAVAILABLE;
    }
    return Submission.ACCEPTED;
  }

  @Override
  public void attach(AuthenticatedRuntimeConnection runtimeConnection, String serverId) {
    Objects.requireNonNull(runtimeConnection);
    if (closed.get() || !runtimeConnection.isOpen()) {
      throw new IllegalStateException("Cannot attach an unavailable Runtime connection");
    }
    var binding = new ConnectionBinding(runtimeConnection, new AgentProtocolCodec(serverId));
    if (!connection.compareAndSet(null, binding)) {
      throw new IllegalStateException("A Runtime application connection is already attached");
    }
    try {
      runtimeConnection.setApplicationHandler(message -> receive(binding, message));
    } catch (RuntimeException error) {
      connection.compareAndSet(binding, null);
      throw error;
    }
  }

  @Override
  public void detach(AuthenticatedRuntimeConnection runtimeConnection) {
    var binding = connection.get();
    if (binding != null && binding.connection() == runtimeConnection) {
      connection.compareAndSet(binding, null);
    }
  }

  public void cancelPlayer(UUID playerId) {
    Objects.requireNonNull(playerId);
    LiveRequest request;
    synchronized (lock) {
      request = requestsByPlayer.get(playerId);
      if (request == null || !remove(request)) {
        return;
      }
    }
    request.cancelTimeout();
    sendCancellation(request, CancelReason.PLAYER_DISCONNECTED);
  }

  @Override
  public void quiesce(long epoch, OfflineReason reason) {
    Objects.requireNonNull(reason);
    var cancellationReason =
        reason == OfflineReason.RUNTIME_UNAVAILABLE
            ? CancelReason.RUNTIME_DISCONNECTED
            : CancelReason.AGENT_OFFLINE;
    ArrayList<LiveRequest> pending;
    synchronized (lock) {
      pending = new ArrayList<>(requestsById.values());
      requestsById.clear();
      requestsByPlayer.clear();
    }
    for (var request : pending) {
      request.cancelTimeout();
      sendCancellation(request, cancellationReason);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    quiesce(operationalGate.epoch(), OfflineReason.MANUAL);
    connection.set(null);
  }

  int activeRequestCount() {
    synchronized (lock) {
      return requestsById.size();
    }
  }

  private void receive(ConnectionBinding source, String encoded) {
    if (closed.get() || connection.get() != source) {
      return;
    }

    AgentProtocolCodec.InboundMessage message;
    try {
      message = source.codec().decode(encoded);
    } catch (RuntimeException error) {
      events.event("RUNTIME_APPLICATION_MESSAGE_REJECTED");
      source.connection().close();
      return;
    }

    LiveRequest request;
    synchronized (lock) {
      request = requestsById.get(message.requestId());
      if (request == null) {
        return;
      }
      if (request.binding() != source || !request.playerId().equals(message.playerUuid())) {
        events.event("RUNTIME_APPLICATION_BINDING_REJECTED");
        source.connection().close();
        return;
      }
      if (message instanceof AgentProtocolCodec.Completion completion
          && completion.sessionId() != null) {
        events.event("RUNTIME_APPLICATION_BINDING_REJECTED");
        source.connection().close();
        return;
      }
      if (!operationalGate.revalidate(request.permit()) || !remove(request)) {
        return;
      }
    }
    request.cancelTimeout();
    dispatchBoundReply(request, fallbackText(message));
  }

  private void timeout(LiveRequest request) {
    synchronized (lock) {
      if (!remove(request)) {
        return;
      }
    }
    sendCancellation(request, CancelReason.PAPER_TIMEOUT);
    dispatchBoundReply(request, TIMEOUT_MESSAGE);
  }

  private void sendFailed(LiveRequest request) {
    sendFailed(request, true);
  }

  private void sendFailed(LiveRequest request, boolean notifyPlayer) {
    synchronized (lock) {
      if (!remove(request)) {
        return;
      }
    }
    request.cancelTimeout();
    events.event("RUNTIME_APPLICATION_SEND_FAILED");
    if (notifyPlayer) {
      dispatchBoundReply(request, UNAVAILABLE_MESSAGE);
    }
    request.binding().connection().close();
  }

  private boolean remove(LiveRequest request) {
    if (requestsById.get(request.requestId()) != request) {
      return false;
    }
    requestsById.remove(request.requestId());
    requestsByPlayer.remove(request.playerId(), request);
    return true;
  }

  private void sendCancellation(LiveRequest request, CancelReason reason) {
    var binding = request.binding();
    if (connection.get() != binding || !binding.connection().isOpen()) {
      return;
    }
    try {
      binding
          .connection()
          .sendApplication(
              binding.codec().encodeCancel(request.requestId(), request.playerId(), reason))
          .whenComplete(
              (ignored, error) -> {
                if (error != null) {
                  events.event("RUNTIME_APPLICATION_CANCEL_FAILED");
                }
              });
    } catch (RuntimeException error) {
      events.event("RUNTIME_APPLICATION_CANCEL_FAILED");
    }
  }

  private void dispatchBoundReply(LiveRequest request, String message) {
    try {
      mainThread.execute(
          () -> {
            if (!closed.get()
                && connection.get() == request.binding()
                && operationalGate.revalidate(request.permit())) {
              replies.send(request.playerId(), message);
            }
          });
    } catch (RuntimeException error) {
      events.event("PLAYER_REPLY_SCHEDULE_FAILED");
    }
  }

  private static boolean validMessage(String message) {
    return message != null
        && !message.isBlank()
        && message.codePointCount(0, message.length()) <= 4096
        && message.codePoints().noneMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff);
  }

  private static String fallbackText(AgentProtocolCodec.InboundMessage message) {
    return switch (message) {
      case AgentProtocolCodec.Completion completion -> completion.fallbackText();
      case AgentProtocolCodec.AgentError error -> error.fallbackText();
    };
  }

  private record ConnectionBinding(
      AuthenticatedRuntimeConnection connection, AgentProtocolCodec codec) {}

  private static final class LiveRequest {
    private final UUID requestId;
    private final UUID playerId;
    private final OperationalGate.Permit permit;
    private final ConnectionBinding binding;
    private final AtomicReference<Cancellable> timeout = new AtomicReference<>();

    private LiveRequest(
        UUID requestId, UUID playerId, OperationalGate.Permit permit, ConnectionBinding binding) {
      this.requestId = requestId;
      this.playerId = playerId;
      this.permit = permit;
      this.binding = binding;
    }

    UUID requestId() {
      return requestId;
    }

    UUID playerId() {
      return playerId;
    }

    OperationalGate.Permit permit() {
      return permit;
    }

    ConnectionBinding binding() {
      return binding;
    }

    void timeout(Cancellable scheduled) {
      if (!timeout.compareAndSet(null, scheduled)) {
        scheduled.cancel();
      }
    }

    void cancelTimeout() {
      var scheduled = timeout.getAndSet(null);
      if (scheduled != null) {
        scheduled.cancel();
      }
    }
  }
}
