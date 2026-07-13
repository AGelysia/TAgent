package dev.minecraftagent.paper.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.AgentError;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.AgentErrorCode;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.CancelReason;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.Completion;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.SessionResumed;
import dev.minecraftagent.paper.request.AgentModule;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentProtocolCodecTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SERVER_ID = "survival-main";
  private static final Instant NOW = Instant.parse("2026-07-11T08:00:01Z");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYER_UUID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID SESSION_ID = UUID.fromString("71717171-7171-4171-8171-717171717171");

  @Test
  void encodesSchemaValidGeneralRequestWithTrustedIdentityAndNoClient() throws Exception {
    var codec = codec();

    var source = codec.encodeRequest(REQUEST_ID, PLAYER_UUID, "How do I craft a table?");
    var document = JSON.readTree(source);

    assertSchema("envelope.schema.json", document);
    assertSchema("agent-request.schema.json", document.path("payload"));
    assertEquals(document.path("messageId"), document.path("requestId"));
    assertEquals(SERVER_ID, document.path("serverId").asText());
    assertEquals("agent.request", document.path("type").asText());
    assertEquals(PLAYER_UUID.toString(), document.path("payload").path("playerUuid").asText());
    assertEquals("general", document.path("payload").path("module").asText());
    assertTrue(document.path("payload").path("sessionId").isNull());
    assertEquals(
        false, document.path("payload").path("clientCapabilities").path("connected").asBoolean());
    assertTrue(
        document.path("payload").path("clientCapabilities").path("clientProtocolVersion").isNull());
  }

  @Test
  void encodesSelectedSessionAndOneShotModule() throws Exception {
    var source =
        codec()
            .encodeRequest(
                REQUEST_ID, PLAYER_UUID, SESSION_ID, AgentModule.RECIPE, "Comparator recipe");
    var document = JSON.readTree(source);

    assertSchema("envelope.schema.json", document);
    assertSchema("agent-request.schema.json", document.path("payload"));
    assertEquals(SESSION_ID.toString(), document.path("payload").path("sessionId").asText());
    assertEquals("recipe", document.path("payload").path("module").asText());
  }

  @Test
  void encodesSchemaValidLatestAndExplicitResumeRequests() throws Exception {
    var latest = JSON.readTree(codec().encodeResume(REQUEST_ID, PLAYER_UUID, null));
    assertSchema("envelope.schema.json", latest);
    assertSchema("session-resume.schema.json", latest.path("payload"));
    assertEquals("session.resume", latest.path("type").asText());
    assertTrue(latest.path("payload").path("sessionId").isNull());

    var explicit = JSON.readTree(codec().encodeResume(REQUEST_ID, PLAYER_UUID, SESSION_ID));
    assertSchema("session-resume.schema.json", explicit.path("payload"));
    assertEquals(SESSION_ID.toString(), explicit.path("payload").path("sessionId").asText());
  }

  @Test
  void encodesSchemaValidCorrelatedCancellation() throws Exception {
    var source = codec().encodeCancel(REQUEST_ID, PLAYER_UUID, CancelReason.PLAYER_DISCONNECTED);
    var document = JSON.readTree(source);

    assertSchema("envelope.schema.json", document);
    assertSchema("agent-cancel.schema.json", document.path("payload"));
    assertNotEquals(document.path("messageId"), document.path("requestId"));
    assertEquals(REQUEST_ID.toString(), document.path("requestId").asText());
    assertEquals("PLAYER_DISCONNECTED", document.path("payload").path("reason").asText());
  }

  @Test
  void decodesStrictCompletionAndErrorFixtures() throws Exception {
    var codec = codec();

    var completion =
        assertInstanceOf(Completion.class, codec.decode(fixture("envelope-agent-complete.json")));
    assertEquals(REQUEST_ID, completion.requestId());
    assertEquals(PLAYER_UUID, completion.playerUuid());
    assertNull(completion.sessionId());
    assertEquals("Place four planks in a 2x2 crafting grid.", completion.fallbackText());

    var error =
        assertInstanceOf(AgentError.class, codec.decode(fixture("envelope-agent-error.json")));
    assertEquals(REQUEST_ID, error.requestId());
    assertEquals(PLAYER_UUID, error.playerUuid());
    assertEquals(AgentErrorCode.MODEL_TIMEOUT, error.code());
    assertTrue(error.retryable());
  }

  @Test
  void decodesStrictSessionResumedFixture() throws Exception {
    var source =
        fixture("envelope-session-resumed.json").replace("2026-07-13T08:00:02Z", NOW.toString());
    var resumed = assertInstanceOf(SessionResumed.class, codec().decode(source));

    assertEquals(PLAYER_UUID, resumed.playerUuid());
    assertEquals(SESSION_ID, resumed.sessionId());
  }

  @Test
  void rejectsWrongServerIdentity() throws Exception {
    var source = fixture("envelope-agent-complete.json").replace(SERVER_ID, "other-server");

    assertFailure(codec(), source, "SERVER_ID_MISMATCH");
  }

  @Test
  void rejectsReplayedMessageIdOrNonce() throws Exception {
    var codec = codec();
    var source = fixture("envelope-agent-complete.json");
    codec.decode(source);

    assertFailure(codec, source, "PROTOCOL_MESSAGE_REPLAYED");
  }

  @Test
  void rejectsNonemptyStructuredViewsUntilTheClientChannelExists() throws Exception {
    var source =
        fixture("envelope-agent-complete.json")
            .replace("\"structuredViews\": []", "\"structuredViews\": [{}]");

    assertFailure(codec(), source, "STRUCTURED_VIEWS_UNSUPPORTED");
  }

  @Test
  void rejectsAnUnpairedSurrogateFallback() throws Exception {
    var source =
        fixture("envelope-agent-complete.json")
            .replace("Place four planks in a 2x2 crafting grid.", "\\ud800");

    assertFailure(codec(), source, "PROTOCOL_MESSAGE_INVALID");
  }

  @Test
  void rejectsDuplicateFieldsStaleMessagesAndRuntimeToolCalls() throws Exception {
    var valid = fixture("envelope-agent-complete.json");
    assertFailure(
        codec(),
        valid.replaceFirst("\\{", "{\"protocolVersion\":\"1.0\","),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        valid.replace(NOW.toString(), NOW.minusSeconds(31).toString()),
        "PROTOCOL_MESSAGE_STALE");
    assertFailure(
        codec(), valid.replace("agent.complete", "tool.call"), "UNSUPPORTED_MESSAGE_TYPE");
  }

  private static AgentProtocolCodec codec() {
    return new AgentProtocolCodec(SERVER_ID, new SecureRandom(), () -> NOW);
  }

  private static String fixture(String name) throws Exception {
    var path = protocolRoot().resolve("fixtures/valid/" + name);
    return Files.readString(path);
  }

  private static void assertFailure(AgentProtocolCodec codec, String source, String expectedCode) {
    try {
      codec.decode(source);
      throw new AssertionError("message unexpectedly decoded");
    } catch (RuntimeConnectionFailure failure) {
      assertEquals(expectedCode, failure.code());
      assertEquals("application-protocol", failure.stage());
    }
  }

  private static void assertSchema(String name, JsonNode document) throws Exception {
    var schemaNode = JSON.readTree(Files.newInputStream(protocolRoot().resolve("schemas/" + name)));
    var schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(
                schemaNode, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    var errors = schema.validate(document);
    assertTrue(errors.isEmpty(), () -> name + " errors: " + errors);
  }

  private static Path protocolRoot() {
    return Path.of(System.getProperty("minecraftAgent.protocolDir")).toAbsolutePath().normalize();
  }
}
