package dev.minecraftagent.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClientMetadataTest {
  @Test
  void litematicaIsNotARequiredDependency() throws IOException {
    try (var stream = getClass().getClassLoader().getResourceAsStream("fabric.mod.json")) {
      assertNotNull(stream);
      var metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(metadata.contains("\"environment\": \"client\""));
      assertFalse(metadata.contains("litematica"));
      assertFalse(metadata.contains("malilib"));
    }
  }
}
