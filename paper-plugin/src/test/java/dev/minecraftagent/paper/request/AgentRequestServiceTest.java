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
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolExecutor;
import dev.minecraftagent.paper.tool.ReadToolRegistry;
import dev.minecraftagent.paper.tool.ReadToolResult;
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
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Hello");
    var request = fixture.connection.sent.getFirst();
    var completion = completion(request, playerId, "Private answer", sessionId);

    fixture.connection.deliver(completion);
    assertTrue(fixture.replies.isEmpty());
    assertEquals(0, fixture.service.activeRequestCount());
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Private answer"), fixture.replies);
    assertEquals(sessionId, fixture.service.currentSession(playerId));

    fixture.connection.deliver(completion(request, playerId, "Late duplicate", sessionId));
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Private answer"), fixture.replies);
  }

  @Test
  void completionQueuedBeforeOfflineIsRejectedAtTheFinalReplyBoundary() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Hello");

    fixture.connection.deliver(
        completion(
            fixture.connection.sent.getFirst(), playerId, "Stale answer", UUID.randomUUID()));
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.main.drain();

    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void wrongPlayerClosesConnectionWithoutReply() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Hello");

    fixture.connection.deliver(
        completion(
            fixture.connection.sent.getFirst(),
            UUID.randomUUID(),
            "Wrong binding",
            UUID.randomUUID()));
    fixture.main.drain();

    assertFalse(fixture.connection.isOpen());
    assertTrue(fixture.replies.isEmpty());
    assertEquals(List.of("RUNTIME_APPLICATION_BINDING_REJECTED"), fixture.events);
  }

  @Test
  void selectedSessionMustMatchContinuationCompletion() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var selectedSession = UUID.randomUUID();
    fixture.service.submit(playerId, "First");
    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "First answer", selectedSession));
    fixture.service.submit(playerId, "Second");

    fixture.connection.deliver(
        completion(fixture.connection.sent.get(1), playerId, "Wrong session", UUID.randomUUID()));
    fixture.main.drain();

    assertFalse(fixture.connection.isOpen());
    assertEquals(List.of("RUNTIME_APPLICATION_BINDING_REJECTED"), fixture.events);
    assertEquals(List.of(playerId + ":First answer"), fixture.replies);
  }

  @Test
  void nullableCompletionRemainsValidWhenConversationStorageIsDisabled() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Ephemeral question");

    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Ephemeral answer", null));
    fixture.main.drain();

    assertTrue(fixture.connection.isOpen());
    assertEquals(List.of(playerId + ":Ephemeral answer"), fixture.replies);
    assertEquals(null, fixture.service.currentSession(playerId));
  }

  @Test
  void explicitModuleIsOneShotAndKeepsTheSelectedSession() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Start");
    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Started", sessionId));

    assertEquals(
        AgentRequestGateway.Submission.ACCEPTED,
        fixture.service.submitModule(playerId, AgentModule.RECIPE, "Comparator recipe"));
    var moduleRequest = object(fixture.connection.sent.get(1)).getAsJsonObject("payload");
    assertEquals(sessionId.toString(), moduleRequest.get("sessionId").getAsString());
    assertEquals("recipe", moduleRequest.get("module").getAsString());
    fixture.connection.deliver(
        completion(fixture.connection.sent.get(1), playerId, "Recipe answer", sessionId));

    fixture.service.submit(playerId, "Continue normally");
    var nextRequest = object(fixture.connection.sent.get(2)).getAsJsonObject("payload");
    assertEquals(sessionId.toString(), nextRequest.get("sessionId").getAsString());
    assertEquals("general", nextRequest.get("module").getAsString());
  }

  @Test
  void resumeLatestIsBoundAndFeedsTheNextGeneralRequest() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();

    assertEquals(AgentRequestGateway.Submission.ACCEPTED, fixture.service.resume(playerId, null));
    assertEquals(
        AgentRequestGateway.Submission.ALREADY_ACTIVE,
        fixture.service.submit(playerId, "Cannot overlap"));
    var resume = object(fixture.connection.sent.getFirst());
    assertEquals("session.resume", resume.get("type").getAsString());
    assertTrue(resume.getAsJsonObject("payload").get("sessionId").isJsonNull());

    fixture.connection.deliver(
        sessionResumed(fixture.connection.sent.getFirst(), playerId, sessionId));
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Resumed AI session " + sessionId + "."), fixture.replies);

    fixture.service.submit(playerId, "Continue");
    var query = object(fixture.connection.sent.get(1)).getAsJsonObject("payload");
    assertEquals(sessionId.toString(), query.get("sessionId").getAsString());
    assertEquals("general", query.get("module").getAsString());
  }

  @Test
  void storageDisabledClearsOnlyTheStaleSelectedSession() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Start");
    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Started", sessionId));
    fixture.service.submit(playerId, "Continue");

    fixture.connection.deliver(
        error(
            fixture.connection.sent.get(1),
            playerId,
            "CONVERSATION_STORAGE_DISABLED",
            "Conversation storage is disabled."));
    fixture.service.submit(playerId, "Fresh ephemeral request");

    assertTrue(
        object(fixture.connection.sent.get(2))
            .getAsJsonObject("payload")
            .get("sessionId")
            .isJsonNull());
  }

  @Test
  void runtimeReattachmentRetainsTheSelectedSession() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Start");
    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Started", sessionId));

    fixture.service.detach(fixture.connection);
    var replacement = new FakeConnection();
    fixture.service.attach(replacement, SERVER_ID);
    fixture.service.submit(playerId, "After Runtime restart");

    assertEquals(
        sessionId.toString(),
        object(replacement.sent.getFirst())
            .getAsJsonObject("payload")
            .get("sessionId")
            .getAsString());
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

  @Test
  void toolCallIsAnIntermediateBoundRoundAndCompletionMayFollowImmediately() {
    var tools = new ManualTools();
    var fixture = new Fixture(tools);
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Where am I?");
    var request = fixture.connection.sent.getFirst();

    fixture.connection.deliver(
        toolCall(
            request, playerId, sessionId, "general", "player.context.read", new JsonObject(), 0));
    assertEquals(1, tools.calls.size());
    assertEquals(1, fixture.connection.sent.size());

    var result = new JsonObject();
    result.addProperty("online", true);
    tools.complete(ReadToolResult.succeeded(ReadToolResult.Source.PAPER_API, result));
    assertEquals("tool.result", object(fixture.connection.sent.get(1)).get("type").getAsString());

    fixture.connection.deliver(completion(request, playerId, "You are online.", sessionId));
    fixture.main.drain();
    assertTrue(fixture.connection.isOpen());
    assertEquals(List.of(playerId + ":You are online."), fixture.replies);
  }

  @Test
  void unknownToolIsRejectedWithoutExecutionAndConsumesTheRound() {
    var tools = new ManualTools();
    var fixture = new Fixture(tools);
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Run something");
    var request = fixture.connection.sent.getFirst();

    fixture.connection.deliver(
        toolCall(
            request,
            playerId,
            sessionId,
            "general",
            "server.command.execute",
            new JsonObject(),
            0));

    assertTrue(tools.calls.isEmpty());
    var result = object(fixture.connection.sent.get(1));
    assertEquals("tool.result", result.get("type").getAsString());
    assertEquals("rejected", result.getAsJsonObject("payload").get("status").getAsString());
    assertEquals(
        "TOOL_UNKNOWN",
        result.getAsJsonObject("payload").getAsJsonObject("error").get("code").getAsString());
  }

  @Test
  void timeoutDropsAQueuedToolResultAndItsLateCompletion() {
    var tools = new ManualTools();
    var fixture = new Fixture(tools);
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Slow tool");
    var request = fixture.connection.sent.getFirst();
    fixture.connection.deliver(
        toolCall(request, playerId, sessionId, "general", "server.info.read", new JsonObject(), 0));

    fixture.timeouts.fire();
    tools.complete(ReadToolResult.succeeded(ReadToolResult.Source.PAPER_API, new JsonObject()));

    assertEquals(
        List.of("agent.request", "agent.cancel"),
        fixture.connection.sent.stream()
            .map(AgentRequestServiceTest::object)
            .map(message -> message.get("type").getAsString())
            .toList());
  }

  @Test
  void offlineCancelsAnActiveToolExecutionAndDropsItsLateCompletion() {
    var tools = new ManualTools();
    var fixture = new Fixture(tools);
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Slow tool");
    var request = fixture.connection.sent.getFirst();
    fixture.connection.deliver(
        toolCall(request, playerId, sessionId, "general", "server.info.read", new JsonObject(), 0));

    assertFalse(tools.pending.isCancelled());
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.service.quiesce(fixture.gate.epoch(), OfflineReason.MANUAL);

    assertTrue(tools.pending.isCancelled());
    assertEquals(
        List.of("agent.request", "agent.cancel"),
        fixture.connection.sent.stream()
            .map(AgentRequestServiceTest::object)
            .map(message -> message.get("type").getAsString())
            .toList());
    assertFalse(
        tools.pending.complete(
            ReadToolResult.succeeded(ReadToolResult.Source.PAPER_API, new JsonObject())));
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

  private static JsonObject toolCall(
      String requestEnvelope,
      UUID playerId,
      UUID sessionId,
      String module,
      String tool,
      JsonObject arguments,
      int sequence) {
    var payload = new JsonObject();
    payload.addProperty("toolCallId", UUID.randomUUID().toString());
    payload.addProperty("sessionId", sessionId.toString());
    payload.addProperty("playerUuid", playerId.toString());
    payload.addProperty("module", module);
    payload.addProperty("tool", tool);
    payload.add("arguments", arguments);
    payload.addProperty("sequence", sequence);
    return response(requestEnvelope, "tool.call", payload);
  }

  private static JsonObject sessionResumed(String requestEnvelope, UUID playerId, UUID sessionId) {
    var payload = new JsonObject();
    payload.addProperty("playerUuid", playerId.toString());
    payload.addProperty("sessionId", sessionId.toString());
    return response(requestEnvelope, "session.resumed", payload);
  }

  private static JsonObject error(
      String requestEnvelope, UUID playerId, String code, String fallbackText) {
    var payload = new JsonObject();
    payload.addProperty("playerUuid", playerId.toString());
    payload.addProperty("code", code);
    payload.addProperty("fallbackText", fallbackText);
    payload.addProperty("retryable", false);
    return response(requestEnvelope, "agent.error", payload);
  }

  private static JsonObject response(String requestEnvelope, String type, JsonObject payload) {
    var request = object(requestEnvelope);
    var response = new JsonObject();
    response.addProperty("protocolVersion", "1.0");
    response.addProperty("messageId", UUID.randomUUID().toString());
    response.addProperty("requestId", request.get("requestId").getAsString());
    response.addProperty("serverId", SERVER_ID);
    response.addProperty("type", type);
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
    private final AgentRequestService service;

    private Fixture() {
      this(
          call ->
              CompletableFuture.completedFuture(
                  ReadToolResult.failed(
                      ReadToolResult.Source.PAPER_POLICY,
                      "TOOL_EXECUTION_UNAVAILABLE",
                      "Unavailable.",
                      true)));
    }

    private Fixture(ReadToolExecutor tools) {
      service =
          new AgentRequestService(
              gate,
              Duration.ofSeconds(30),
              timeouts,
              main::execute,
              (playerId, message) -> replies.add(playerId + ":" + message),
              events::add,
              new ReadToolRegistry(),
              tools,
              Runnable::run);
      service.attach(connection, SERVER_ID);
    }
  }

  private static final class ManualTools implements ReadToolExecutor {
    private final List<ReadToolCall> calls = new ArrayList<>();
    private CompletableFuture<ReadToolResult> pending;

    @Override
    public CompletionStage<ReadToolResult> execute(ReadToolCall call) {
      calls.add(call);
      pending = new CompletableFuture<>();
      return pending;
    }

    private void complete(ReadToolResult result) {
      pending.complete(result);
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
