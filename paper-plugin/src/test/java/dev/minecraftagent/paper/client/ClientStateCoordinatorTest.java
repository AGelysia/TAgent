package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientStateCoordinatorTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void projectsExactRequestCapabilitiesAndClearsTransfersWithState() {
    var connections = new ClientConnectionRegistry();
    var transfers = ClientTransferManager.withProductionLimits();
    var state = new ClientStateCoordinator(connections, transfers);

    state.join(PLAYER);
    var vanilla = state.capabilitySnapshot(PLAYER);
    assertFalse(vanilla.connected());
    assertTrue(vanilla.toAgentRequestJson().get("clientProtocolVersion").isJsonNull());

    var negotiated = state.negotiate(PLAYER, ClientConnectionRegistryTest.handshake(1, 1, 0));
    var snapshot = state.capabilitySnapshot(PLAYER);
    assertTrue(snapshot.connected());
    assertEquals(negotiated.generation(), snapshot.generation());
    assertEquals(
        1, snapshot.toAgentRequestJson().getAsJsonObject("features").get("recipeView").getAsInt());

    transfers.prepare(
        PLAYER,
        negotiated.generation(),
        ClientViewSelectorTest.view(ClientViewType.TEXT),
        Instant.EPOCH);
    state.clearTransientState();
    assertTrue(connections.lookup(PLAYER).isEmpty());
    assertEquals(0, transfers.pendingCount(PLAYER));
  }

  @Test
  void rejectionMovesToAFreshVanillaGenerationAndDropsPendingTransfers() {
    var connections = new ClientConnectionRegistry();
    var transfers = ClientTransferManager.withProductionLimits();
    var state = new ClientStateCoordinator(connections, transfers);
    state.join(PLAYER);
    var negotiated = state.negotiate(PLAYER, ClientConnectionRegistryTest.handshake(1, 1, 0));
    transfers.prepare(
        PLAYER,
        negotiated.generation(),
        ClientViewSelectorTest.view(ClientViewType.TEXT),
        Instant.EPOCH);

    var rejected = state.reject(PLAYER).orElseThrow();

    assertTrue(rejected.generation() > negotiated.generation());
    assertFalse(rejected.negotiated());
    assertFalse(state.capabilitySnapshot(PLAYER).connected());
    assertEquals(0, transfers.pendingCount(PLAYER));
  }
}
