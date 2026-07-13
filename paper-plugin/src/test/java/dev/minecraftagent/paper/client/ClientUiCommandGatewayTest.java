package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientUiCommandGatewayTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void sendsOnlyToNegotiatedOverlayAndCarriesCurrentGeneration() {
    var registry = new ClientConnectionRegistry();
    var sent = new ArrayList<ClientUiCommandGateway.Control>();
    var gateway = new ClientUiCommandGateway(registry, (player, control) -> sent.add(control));

    assertEquals(
        ClientUiCommandGateway.Result.CLIENT_UNAVAILABLE,
        gateway.invoke(PLAYER, ClientUiCommandGateway.Action.PIN));
    registry.join(PLAYER);
    registry.replace(PLAYER, ClientConnectionRegistryTest.handshake(0, 0, 0));
    assertEquals(
        ClientUiCommandGateway.Result.CLIENT_UNAVAILABLE,
        gateway.invoke(PLAYER, ClientUiCommandGateway.Action.PIN));

    var current = registry.replace(PLAYER, ClientConnectionRegistryTest.handshake(1, 0, 0));
    assertEquals(
        ClientUiCommandGateway.Result.SENT,
        gateway.invoke(PLAYER, ClientUiCommandGateway.Action.CLEAR));
    assertEquals(
        new ClientUiCommandGateway.Control(
            current.generation(), ClientUiCommandGateway.Action.CLEAR, null),
        sent.getFirst());
  }

  @Test
  void litematicaControlsRequireAnExplicitViewIdentity() {
    assertEquals(
        "CLIENT_UI_VIEW_ID_REQUIRED",
        assertThrows(
                ClientProtocolException.class,
                () ->
                    new ClientUiCommandGateway.Control(
                        1, ClientUiCommandGateway.Action.LITEMATICA_PREVIEW_LOAD, null))
            .code());
  }
}
