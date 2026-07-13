package dev.minecraftagent.paper.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.minecraftagent.paper.request.AgentModule;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import dev.minecraftagent.paper.transport.StrictJson;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Encodes Paper-originated requests and validates terminal Runtime responses. */
public final class AgentProtocolCodec {
  public static final int MAX_APPLICATION_BYTES = 64 * 1024;

  private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
  private static final Duration REPLAY_RETENTION = Duration.ofSeconds(60);
  private static final int MAX_REPLAY_ENTRIES = 2048;
  private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "protocolVersion",
          "messageId",
          "requestId",
          "serverId",
          "type",
          "timestamp",
          "nonce",
          "payload");
  private static final Set<String> COMPLETION_FIELDS =
      Set.of("sessionId", "playerUuid", "fallbackText", "structuredViews");
  private static final Set<String> ERROR_FIELDS =
      Set.of("playerUuid", "code", "fallbackText", "retryable");
  private static final Set<String> SESSION_RESUMED_FIELDS = Set.of("sessionId", "playerUuid");

  private final String serverId;
  private final SecureRandom secureRandom;
  private final Supplier<Instant> now;
  private final ReplayWindow replayWindow = new ReplayWindow();

  public AgentProtocolCodec(String serverId) {
    this(serverId, new SecureRandom(), Instant::now);
  }

  AgentProtocolCodec(String serverId, SecureRandom secureRandom, Supplier<Instant> now) {
    this.serverId = Objects.requireNonNull(serverId);
    this.secureRandom = Objects.requireNonNull(secureRandom);
    this.now = Objects.requireNonNull(now);
    if (!SERVER_ID.matcher(serverId).matches()) {
      throw new IllegalArgumentException("Invalid server ID");
    }
  }

  public String encodeRequest(UUID requestId, UUID playerUuid, String message) {
    return encodeRequest(requestId, playerUuid, null, AgentModule.GENERAL, message);
  }

  public String encodeRequest(
      UUID requestId, UUID playerUuid, UUID sessionId, AgentModule module, String message) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(module);
    requireText(message, 4096);

    var features = new JsonObject();
    features.addProperty("overlay", 0);
    features.addProperty("itemIcons", 0);
    features.addProperty("recipeView", 0);
    features.addProperty("litematicaPreview", 0);
    features.addProperty("litematicaMaterialList", 0);

    var clientCapabilities = new JsonObject();
    clientCapabilities.addProperty("connected", false);
    clientCapabilities.add("clientProtocolVersion", JsonNull.INSTANCE);
    clientCapabilities.add("features", features);

    var payload = new JsonObject();
    if (sessionId == null) {
      payload.add("sessionId", JsonNull.INSTANCE);
    } else {
      payload.addProperty("sessionId", sessionId.toString());
    }
    payload.addProperty("playerUuid", playerUuid.toString());
    payload.addProperty("module", module.protocolName());
    payload.addProperty("message", message);
    payload.add("clientCapabilities", clientCapabilities);

    return encodeEnvelope(requestId, requestId, "agent.request", payload);
  }

  public String encodeResume(UUID requestId, UUID playerUuid, UUID sessionId) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(playerUuid);

    var payload = new JsonObject();
    payload.addProperty("playerUuid", playerUuid.toString());
    if (sessionId == null) {
      payload.add("sessionId", JsonNull.INSTANCE);
    } else {
      payload.addProperty("sessionId", sessionId.toString());
    }
    return encodeEnvelope(requestId, requestId, "session.resume", payload);
  }

  public String encodeCancel(UUID requestId, UUID playerUuid, CancelReason reason) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(reason);

    var payload = new JsonObject();
    payload.addProperty("playerUuid", playerUuid.toString());
    payload.addProperty("reason", reason.name());
    return encodeEnvelope(newMessageId(requestId), requestId, "agent.cancel", payload);
  }

  public InboundMessage decode(String source) {
    requireWireText(source);
    var root = StrictJson.parseObject(source, "PROTOCOL_MESSAGE_INVALID", "application-protocol");
    requireFields(root, ENVELOPE_FIELDS);
    if (!"1.0".equals(string(root, "protocolVersion"))) {
      throw failure("PROTOCOL_INCOMPATIBLE");
    }

    var messageId = uuid(root, "messageId");
    var requestId = uuid(root, "requestId");
    if (messageId.equals(requestId)) {
      throw invalid();
    }
    if (!serverId.equals(string(root, "serverId"))) {
      throw failure("SERVER_ID_MISMATCH");
    }

    var timestamp = instant(root, "timestamp");
    var current = now.get();
    if (Duration.between(current, timestamp).abs().compareTo(CLOCK_SKEW) > 0) {
      throw failure("PROTOCOL_MESSAGE_STALE");
    }
    var nonce = randomValue(root, "nonce");
    if (!replayWindow.accept(messageId.toString(), nonce, current)) {
      throw failure("PROTOCOL_MESSAGE_REPLAYED");
    }

    var payload = object(root, "payload");
    return switch (string(root, "type")) {
      case "agent.complete" -> decodeCompletion(messageId, requestId, payload);
      case "agent.error" -> decodeError(messageId, requestId, payload);
      case "session.resumed" -> decodeSessionResumed(messageId, requestId, payload);
      default -> throw failure("UNSUPPORTED_MESSAGE_TYPE");
    };
  }

  private Completion decodeCompletion(UUID messageId, UUID requestId, JsonObject payload) {
    requireFields(payload, COMPLETION_FIELDS);
    var sessionId = nullableUuid(payload, "sessionId");
    var playerUuid = uuid(payload, "playerUuid");
    var fallbackText = boundedText(payload, "fallbackText", 8192);
    var views = array(payload, "structuredViews");
    if (!views.isEmpty()) {
      throw failure("STRUCTURED_VIEWS_UNSUPPORTED");
    }
    return new Completion(messageId, requestId, sessionId, playerUuid, fallbackText);
  }

  private AgentError decodeError(UUID messageId, UUID requestId, JsonObject payload) {
    requireFields(payload, ERROR_FIELDS);
    var playerUuid = uuid(payload, "playerUuid");
    AgentErrorCode code;
    try {
      code = AgentErrorCode.valueOf(string(payload, "code"));
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
    var fallbackText = boundedText(payload, "fallbackText", 512);
    return new AgentError(
        messageId, requestId, playerUuid, code, fallbackText, bool(payload, "retryable"));
  }

  private SessionResumed decodeSessionResumed(UUID messageId, UUID requestId, JsonObject payload) {
    requireFields(payload, SESSION_RESUMED_FIELDS);
    return new SessionResumed(
        messageId, requestId, uuid(payload, "sessionId"), uuid(payload, "playerUuid"));
  }

  private String encodeEnvelope(UUID messageId, UUID requestId, String type, JsonObject payload) {
    var root = new JsonObject();
    root.addProperty("protocolVersion", "1.0");
    root.addProperty("messageId", messageId.toString());
    root.addProperty("requestId", requestId.toString());
    root.addProperty("serverId", serverId);
    root.addProperty("type", type);
    root.addProperty("timestamp", now.get().toString());
    root.addProperty("nonce", newNonce());
    root.add("payload", payload);
    var encoded = root.toString();
    requireWireText(encoded);
    return encoded;
  }

  private UUID newMessageId(UUID requestId) {
    UUID messageId;
    do {
      messageId = UUID.randomUUID();
    } while (messageId.equals(requestId));
    return messageId;
  }

  private String newNonce() {
    var bytes = new byte[16];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void requireWireText(String source) {
    Objects.requireNonNull(source);
    if (source.length() > MAX_APPLICATION_BYTES) {
      throw failure("APPLICATION_MESSAGE_TOO_LARGE");
    }
    try {
      var bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(source))
              .remaining();
      if (bytes > MAX_APPLICATION_BYTES) {
        throw failure("APPLICATION_MESSAGE_TOO_LARGE");
      }
    } catch (CharacterCodingException error) {
      throw invalid();
    }
  }

  private static void requireText(String value, int maximumCodePoints) {
    Objects.requireNonNull(value);
    var codePoints = value.codePointCount(0, value.length());
    if (value.isBlank()
        || codePoints > maximumCodePoints
        || value.codePoints().anyMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff)) {
      throw new IllegalArgumentException("Invalid Agent text");
    }
  }

  private static String boundedText(JsonObject object, String name, int maximumCodePoints) {
    var value = string(object, name);
    if (value.isBlank()
        || value.codePointCount(0, value.length()) > maximumCodePoints
        || value.codePoints().anyMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff)) {
      throw invalid();
    }
    return value;
  }

  private static void requireFields(JsonObject object, Set<String> expected) {
    if (!object.keySet().equals(expected)) {
      throw invalid();
    }
  }

  private static JsonObject object(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonObject()) {
      throw invalid();
    }
    return value.getAsJsonObject();
  }

  private static JsonArray array(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonArray()) {
      throw invalid();
    }
    return value.getAsJsonArray();
  }

  private static String string(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw invalid();
    }
    return value.getAsString();
  }

  private static boolean bool(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw invalid();
    }
    return value.getAsBoolean();
  }

  private static UUID uuid(JsonObject object, String name) {
    return canonicalUuid(string(object, name));
  }

  private static UUID nullableUuid(JsonObject object, String name) {
    JsonElement value = object.get(name);
    if (value == null) {
      throw invalid();
    }
    if (value.isJsonNull()) {
      return null;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw invalid();
    }
    return canonicalUuid(value.getAsString());
  }

  private static UUID canonicalUuid(String value) {
    try {
      var parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw invalid();
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static Instant instant(JsonObject object, String name) {
    try {
      return Instant.parse(string(object, name));
    } catch (DateTimeParseException error) {
      throw invalid();
    }
  }

  private static String randomValue(JsonObject object, String name) {
    var value = string(object, name);
    if (value.length() < 22 || value.length() > 128 || !BASE64_URL.matcher(value).matches()) {
      throw invalid();
    }
    try {
      var decoded = Base64.getUrlDecoder().decode(value);
      if (decoded.length < 16
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
        throw invalid();
      }
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
    return value;
  }

  private static RuntimeConnectionFailure invalid() {
    return failure("PROTOCOL_MESSAGE_INVALID");
  }

  private static RuntimeConnectionFailure failure(String code) {
    return new RuntimeConnectionFailure(code, "application-protocol");
  }

  public enum CancelReason {
    PLAYER_DISCONNECTED,
    PAPER_TIMEOUT,
    AGENT_OFFLINE,
    RUNTIME_DISCONNECTED
  }

  public enum AgentErrorCode {
    MODEL_TIMEOUT,
    MODEL_UNAVAILABLE,
    MODEL_AUTHENTICATION_FAILED,
    MODEL_RESPONSE_INVALID,
    REQUEST_CANCELLED,
    REQUEST_LIMITED,
    SESSION_NOT_FOUND,
    CONVERSATION_STORAGE_DISABLED,
    RUNTIME_INTERNAL_ERROR
  }

  public sealed interface InboundMessage permits Completion, AgentError, SessionResumed {
    UUID messageId();

    UUID requestId();

    UUID playerUuid();
  }

  public record Completion(
      UUID messageId, UUID requestId, UUID sessionId, UUID playerUuid, String fallbackText)
      implements InboundMessage {}

  public record AgentError(
      UUID messageId,
      UUID requestId,
      UUID playerUuid,
      AgentErrorCode code,
      String fallbackText,
      boolean retryable)
      implements InboundMessage {}

  public record SessionResumed(UUID messageId, UUID requestId, UUID sessionId, UUID playerUuid)
      implements InboundMessage {}

  private static final class ReplayWindow {
    private final Map<String, Instant> entries = new LinkedHashMap<>();

    synchronized boolean accept(String messageId, String nonce, Instant now) {
      removeExpired(now);
      var messageKey = "message:" + messageId;
      var nonceKey = "nonce:" + nonce;
      if (entries.containsKey(messageKey) || entries.containsKey(nonceKey)) {
        return false;
      }
      if (entries.size() + 2 > MAX_REPLAY_ENTRIES) {
        return false;
      }
      var expires = now.plus(REPLAY_RETENTION);
      entries.put(messageKey, expires);
      entries.put(nonceKey, expires);
      return true;
    }

    private void removeExpired(Instant now) {
      Iterator<Map.Entry<String, Instant>> iterator = entries.entrySet().iterator();
      while (iterator.hasNext()) {
        if (!iterator.next().getValue().isAfter(now)) {
          iterator.remove();
        }
      }
    }
  }
}
