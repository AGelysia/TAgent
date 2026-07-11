package dev.minecraftagent.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {
  @Test
  void descriptorDoesNotRegisterAgentCommandBeforeSelfCheckExists() throws IOException {
    try (var stream = getClass().getClassLoader().getResourceAsStream("paper-plugin.yml")) {
      assertNotNull(stream);
      var descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(descriptor.contains("main: dev.minecraftagent.paper.MinecraftAgentPlugin"));
      assertFalse(descriptor.contains("commands:"));
    }
  }
}
