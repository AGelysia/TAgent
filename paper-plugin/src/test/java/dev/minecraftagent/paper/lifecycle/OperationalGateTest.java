package dev.minecraftagent.paper.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationalGateTest {
  @Test
  void issuesPermitsOnlyWhileOnlineAndInvalidatesThemAcrossEveryTransition() {
    var gate = new OperationalGate();
    assertEquals(AgentState.UNREGISTERED, gate.state());
    assertEquals(0, gate.epoch());
    assertTrue(gate.tryAcquire().isEmpty());

    assertEquals(1, gate.transitionTo(AgentState.ONLINE));
    var first = gate.tryAcquire().orElseThrow();
    assertEquals(1, first.epoch());
    assertTrue(gate.revalidate(first));

    assertEquals(2, gate.transitionTo(AgentState.STOPPING));
    assertFalse(gate.revalidate(first));
    assertTrue(gate.tryAcquire().isEmpty());

    assertEquals(3, gate.transitionTo(AgentState.OFFLINE));
    assertEquals(4, gate.transitionTo(AgentState.STARTING));
    assertEquals(5, gate.transitionTo(AgentState.ONLINE));
    var second = gate.tryAcquire().orElseThrow();

    assertFalse(gate.revalidate(first));
    assertTrue(gate.revalidate(second));
  }

  @Test
  void rejectsAPermitIssuedByAnotherGateEvenWhenEpochsMatch() {
    var firstGate = new OperationalGate(AgentState.ONLINE);
    var secondGate = new OperationalGate(AgentState.ONLINE);
    var permit = firstGate.tryAcquire().orElseThrow();

    assertFalse(secondGate.revalidate(permit));
  }

  @Test
  void evenAnOnlineToOnlineTransitionRotatesTheEpoch() {
    var gate = new OperationalGate(AgentState.ONLINE);
    var permit = gate.tryAcquire().orElseThrow();

    assertEquals(1, gate.transitionTo(AgentState.ONLINE));

    assertFalse(gate.revalidate(permit));
    assertTrue(gate.tryAcquire().isPresent());
  }
}
