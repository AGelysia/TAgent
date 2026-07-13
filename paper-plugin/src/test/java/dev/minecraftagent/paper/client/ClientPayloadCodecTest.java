package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPayloadCodecTest {
  private static final UUID MESSAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void decodesClosedHelloWithoutPlayerIdentity() {
    var message =
        assertInstanceOf(ClientInboundMessage.Hello.class, codec().decodeInbound(hello()));

    assertEquals(MESSAGE, message.messageId());
    assertEquals("1.2.3", message.handshake().modVersion());
    assertEquals(1, message.handshake().capabilities().version(ClientFeature.OVERLAY));
    assertTrue(message.handshake().dependencyVersion("litematica").isEmpty());
  }

  @Test
  void decodesAckAndRejectsOutboundTypesOrNonClosedJson() {
    var ack =
        assertInstanceOf(
            ClientInboundMessage.Ack.class,
            codec()
                .decodeInbound(
                    json(
                        """
                        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.ack","payload":{"transferId":"44444444-4444-4444-8444-444444444444","generation":7,"status":"DISPLAYED","code":"VIEW_DISPLAYED"}}
                        """)));
    assertEquals(7, ack.generation());
    assertEquals("VIEW_DISPLAYED", ack.code());

    assertCode(
        "CLIENT_MESSAGE_DIRECTION_INVALID",
        """
        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"view.clear","payload":{}}
        """);
    assertCode(
        "CLIENT_ENVELOPE_INVALID",
        """
        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.hello","playerUuid":"11111111-1111-4111-8111-111111111111","payload":{}}
        """);
    assertCode(
        "CLIENT_MESSAGE_INVALID",
        """
        {"clientPayloadVersion":"1.0","clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.hello","payload":{}}
        """);
  }

  @Test
  void rejectsOversizeMalformedUtf8AndFalseDependencyClaims() {
    var oversized = new byte[ClientPayloadCodec.MAX_INBOUND_BYTES + 1];
    assertEquals(
        "CLIENT_MESSAGE_TOO_LARGE",
        assertThrows(ClientProtocolException.class, () -> codec().decodeInbound(oversized)).code());
    assertEquals(
        "CLIENT_MESSAGE_INVALID",
        assertThrows(
                ClientProtocolException.class,
                () -> codec().decodeInbound(new byte[] {(byte) 0xc3, (byte) 0x28}))
            .code());

    var falseClaim =
        new String(hello(), StandardCharsets.UTF_8)
            .replace("\"litematicaPreview\":0", "\"litematicaPreview\":1");
    assertCode("CLIENT_DEPENDENCY_CLAIM_INVALID", falseClaim);
  }

  @Test
  void rejectsNonCanonicalUuidSpellingsAcceptedByJavaUuidParser() {
    assertCode(
        "CLIENT_MESSAGE_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace("33333333-3333-4333-8333-333333333333", "3-3-3-3-3"));
    assertCode(
        "CLIENT_MESSAGE_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace(
                "33333333-3333-4333-8333-333333333333", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"));
  }

  @Test
  void encodesOnlyBoundedServerDirectionsAndTransferMetadata() {
    var manager = ClientTransferManager.withProductionLimits();
    manager.open(UUID.randomUUID(), 9);
    var player = UUID.randomUUID();
    manager.open(player, 9);
    var plan =
        manager.prepare(player, 9, ClientViewSelectorTest.view(ClientViewType.TEXT), Instant.EPOCH);

    var hello = object(codec().encodeServerHello(MESSAGE, 9));
    assertEquals("server.hello", hello.get("type").getAsString());
    assertEquals(9, hello.getAsJsonObject("payload").get("generation").getAsLong());

    var begin = object(codec().encodeViewBegin(MESSAGE, plan));
    assertEquals("view.begin", begin.get("type").getAsString());
    assertEquals(
        plan.contentSha256(), begin.getAsJsonObject("payload").get("contentSha256").getAsString());

    var chunk = object(codec().encodeViewChunk(MESSAGE, 9, plan.chunks().getFirst()));
    assertEquals("view.chunk", chunk.get("type").getAsString());
    assertTrue(chunk.getAsJsonObject("payload").get("data").getAsString().length() > 0);
  }

  private static ClientPayloadCodec codec() {
    return new ClientPayloadCodec();
  }

  private static byte[] hello() {
    return json(
        """
        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.hello","payload":{"clientProtocolVersion":"1.0","modVersion":"1.2.3","capabilities":{"overlay":1,"itemIcons":1,"recipeView":1,"litematicaPreview":0,"litematicaMaterialList":0},"dependencies":{"litematica":null,"malilib":null}}}
        """);
  }

  private static byte[] json(String value) {
    return value.strip().getBytes(StandardCharsets.UTF_8);
  }

  private static com.google.gson.JsonObject object(byte[] bytes) {
    return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
  }

  private static void assertCode(String code, String json) {
    assertEquals(
        code,
        assertThrows(ClientProtocolException.class, () -> codec().decodeInbound(json(json)))
            .code());
  }
}
