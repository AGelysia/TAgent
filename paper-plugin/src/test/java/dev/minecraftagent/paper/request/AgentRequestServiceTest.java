package dev.minecraftagent.paper.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentRequestServiceTest {
  private static final String SERVER_ID = "survival-main";

  @Test
  void requestUsesActualPlayerIdentityAndAllowsOnlyOneLiveRequestPerPlayer() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();

    assertEquals(
        AgentRequestGateway.Submission.ACCEPTED,
        fixture.service.submit(playerId, "Where should I build?"));
    assertEquals(
        AgentRequestGateway.Submission.ALREADY_ACTIVE,
        fixture.service.submit(playerId, "A second request"));

    var envelope = object(fixture.connection.sent.getFirst());
    assertEquals("agent.request", envelope.get("type").getAsString());
    assertEquals(SERVER_ID, envelope.get("serverId").getAsString());
    assertEquals(
        playerId.toString(), envelope.getAsJsonObject("payload").get("playerUuid").getAsString());
    assertEquals("general", envelope.getAsJsonObject("payload").get("module").getAsString());
    assertTrue(envelope.getAsJsonObject("payload").get("sessionId").isJsonNull());
    assertEquals(1, fixture.service.activeRequestCount());
  }

  @Test
  void completionIsDeliveredOnlyThroughMainThreadAndLateDuplicateIsIgnored() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Hello");
    var request = fixture.connection.sent.getFirst();
    var completion = completion(request, playerId, "Private answer", null);

    fixture.connection.deliver(completion);
    assertTrue(fixture.replies.isEmpty());
    assertEquals(0, fixture.service.activeRequestCount());
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Private answer"), fixture.replies);

    fixture.connection.deliver(completion(request, playerId, "Late duplicate", null));
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Private answer"), fixture.replies);
  }

  @Test
  void completionQueuedBeforeOfflineIsRejectedAtTheFinalReplyBoundary() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Hello");

    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Stale answer", null));
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.main.drain();

    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void wrongPlayerOrPhaseFiveSessionClosesConnectionWithoutReply() {
    for (var wrongPlayer : List.of(true, false)) {
      var fixture = new Fixture();
      var playerId = UUID.randomUUID();
      fixture.service.submit(playerId, "Hello");
      var responsePlayer = wrongPlayer ? UUID.randomUUID() : playerId;
      var sessionId = wrongPlayer ? null : UUID.randomUUID();

      fixture.connection.deliver(
          completion(
              fixture.connection.sent.getFirst(), responsePlayer, "Wrong binding", sessionId));
      fixture.main.drain();

      assertFalse(fixture.connection.isOpen());
      assertTrue(fixture.replies.isEmpty());
      assertEquals(List.of("RUNTIME_APPLICATION_BINDING_REJECTED"), fixture.events);
    }
  }

  @Test
  void timeoutCancelsRuntimeReleasesPlayerAndDropsLateCompletion() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Slow request");
    var request = fixture.connection.sent.getFirst();

    fixture.timeouts.fire();
    assertEquals(0, fixture.service.activeRequestCount());
    assertEquals("agent.cancel", object(fixture.connection.sent.get(1)).get("type").getAsString());
    assertEquals(
        "PAPER_TIMEOUT",
        object(fixture.connection.sent.get(1))
            .getAsJsonObject("payload")
            .get("reason")
            .getAsString());
    fixture.connection.deliver(completion(request, playerId, "Too late", null));
    fixture.main.drain();

    assertEquals(List.of(playerId + ":AI request timed out."), fixture.replies);
    assertEquals(
        AgentRequestGateway.Submission.ACCEPTED,
        fixture.service.submit(playerId, "A fresh request"));
  }

  @Test
  void offlineAndPlayerQuitCancelWithoutLeavingStaleDelivery() {
    var fixture = new Fixture();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    fixture.service.submit(first, "First");
    fixture.service.submit(second, "Second");

    fixture.service.cancelPlayer(first);
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.service.quiesce(fixture.gate.epoch(), OfflineReason.MANUAL);

    assertEquals(0, fixture.service.activeRequestCount());
    assertEquals(
        List.of("PLAYER_DISCONNECTED", "AGENT_OFFLINE"),
        fixture.connection.sent.stream()
            .skip(2)
            .map(AgentRequestServiceTest::object)
            .map(envelope -> envelope.getAsJsonObject("payload").get("reason").getAsString())
            .toList());
    fixture.main.drain();
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void sendFailureHasOneSafeReplyAndSynchronousThrowIsLeftToCommandBoundary() {
    var asynchronous = new Fixture();
    var playerId = UUID.randomUUID();
    asynchronous.connection.nextSend = CompletableFuture.failedFuture(new IllegalStateException());
    assertEquals(
        AgentRequestGateway.Submission.ACCEPTED,
        asynchronous.service.submit(playerId, "Will fail asynchronously"));
    asynchronous.main.drain();
    assertEquals(List.of(playerId + ":AI unavailable. Try again later."), asynchronous.replies);
    assertEquals(0, asynchronous.service.activeRequestCount());

    var synchronous = new Fixture();
    synchronous.connection.throwOnSend = true;
    assertEquals(
        AgentRequestGateway.Submission.RUNTIME_UNAVAILABLE,
        synchronous.service.submit(playerId, "Will throw synchronously"));
    synchronous.main.drain();
    assertTrue(synchronous.replies.isEmpty());
    assertEquals(0, synchronous.service.activeRequestCount());
  }

  @Test
  void invalidTextAndGlobalCapacityAreBounded() {
    var fixture = new Fixture();
    assertEquals(
        AgentRequestGateway.Submission.INVALID_MESSAGE,
        fixture.service.submit(UUID.randomUUID(), "\ud800"));
    for (var index = 0; index < 64; index++) {
      assertEquals(
          AgentRequestGateway.Submission.ACCEPTED,
          fixture.service.submit(UUID.randomUUID(), "request " + index));
    }
    assertEquals(
        AgentRequestGateway.Submission.RUNTIME_UNAVAILABLE,
        fixture.service.submit(UUID.randomUUID(), "one too many"));
  }

  private static JsonObject completion(
      String requestEnvelope, UUID playerId, String fallbackText, UUID sessionId) {
    var request = object(requestEnvelope);
    var payload = new JsonObject();
    if (sessionId == null) {
      payload.add("sessionId", JsonNull.INSTANCE);
    } else {
      payload.addProperty("sessionId", sessionId.toString());
    }
    payload.addProperty("playerUuid", playerId.toString());
    payload.addProperty("fallbackText", fallbackText);
    payload.add("structuredViews", new com.google.gson.JsonArray());

    var response = new JsonObject();
    response.addProperty("protocolVersion", "1.0");
    response.addProperty("messageId", UUID.randomUUID().toString());
    response.addProperty("requestId", request.get("requestId").getAsString());
    response.addProperty("serverId", SERVER_ID);
    response.addProperty("type", "agent.complete");
    response.addProperty("timestamp", Instant.now().toString());
    response.addProperty(
        "nonce",
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    response.add("payload", payload);
    return response;
  }

  private static JsonObject object(String source) {
    return JsonParser.parseString(source).getAsJsonObject();
  }

  private static final class Fixture {
    private final OperationalGate gate = new OperationalGate(AgentState.ONLINE);
    private final ManualTimeouts timeouts = new ManualTimeouts();
    private final QueuedMain main = new QueuedMain();
    private final List<String> replies = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final FakeConnection connection = new FakeConnection();
    private final AgentRequestService service =
        new AgentRequestService(
            gate,
            Duration.ofSeconds(30),
            timeouts,
            main::execute,
            (playerId, message) -> replies.add(playerId + ":" + message),
            events::add);

    private Fixture() {
      service.attach(connection, SERVER_ID);
    }
  }

  private static final class ManualTimeouts implements AgentRequestService.TimeoutScheduler {
    private final List<ScheduledTask> tasks = new ArrayList<>();

    @Override
    public AgentRequestService.Cancellable schedule(Duration delay, Runnable task) {
      var scheduled = new ScheduledTask(task);
      tasks.add(scheduled);
      return scheduled::cancel;
    }

    private void fire() {
      for (var task : List.copyOf(tasks)) {
        task.run();
      }
    }
  }

  private static final class ScheduledTask {
    private final Runnable task;
    private boolean cancelled;

    private ScheduledTask(Runnable task) {
      this.task = task;
    }

    private void cancel() {
      cancelled = true;
    }

    private void run() {
      if (!cancelled) {
        task.run();
      }
    }
  }

  private static final class QueuedMain {
    private final Queue<Runnable> tasks = new java.util.ArrayDeque<>();

    private void execute(Runnable task) {
      tasks.add(task);
    }

    private void drain() {
      while (!tasks.isEmpty()) {
        tasks.remove().run();
      }
    }
  }

  private static final class FakeConnection implements AuthenticatedRuntimeConnection {
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final List<String> sent = new ArrayList<>();
    private boolean open = true;
    private boolean throwOnSend;
    private CompletionStage<Void> nextSend = CompletableFuture.completedFuture(null);
    private Consumer<String> handler = ignored -> {};

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return closed;
    }

    @Override
    public CompletionStage<Void> sendApplication(String message) {
      if (throwOnSend) {
        throw new IllegalStateException("send failed");
      }
      sent.add(message);
      var result = nextSend;
      nextSend = CompletableFuture.completedFuture(null);
      return result;
    }

    @Override
    public void setApplicationHandler(Consumer<String> handler) {
      this.handler = handler;
    }

    @Override
    public void close() {
      open = false;
      closed.complete(null);
    }

    private void deliver(JsonObject message) {
      handler.accept(message.toString());
    }
  }
}
