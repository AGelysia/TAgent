package dev.minecraftagent.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {
  @Test
  void descriptorLeavesAgentCommandForConditionalRuntimeRegistration() throws IOException {
    try (var stream = getClass().getClassLoader().getResourceAsStream("paper-plugin.yml")) {
      assertNotNull(stream);
      var descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(descriptor.contains("main: dev.minecraftagent.paper.MinecraftAgentPlugin"));
      assertTrue(descriptor.contains("minecraftagent.use:"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.use:\n"
                  + "    description: Allows a player to ask Minecraft Agent private questions\n"
                  + "    default: true"));
      assertTrue(descriptor.contains("minecraftagent.command.agent:"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.module:\n"
                  + "    description: Allows a player to route one request through an explicit Agent module\n"
                  + "    default: true"));
      assertTrue(descriptor.contains("minecraftagent.admin.toggle:"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.admin.toggle:\n"
                  + "    description: Allows authorized administrators to toggle Minecraft Agent\n"
                  + "    default: op"));
      assertFalse(descriptor.contains("commands:"));
    }
  }
}
