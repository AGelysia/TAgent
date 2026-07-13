package dev.minecraftagent.paper.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.request.AgentModule;
import org.junit.jupiter.api.Test;

class ReadToolRegistryTest {
  private final ReadToolRegistry registry = new ReadToolRegistry();

  @Test
  void containsExactlyTheSixClosedReadToolsWithFixedSources() {
    assertEquals(
        ReadToolRegistry.REQUIRED_TOOL_IDS,
        registry.descriptors().stream()
            .map(ReadToolRegistry.Descriptor::id)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(
        ReadToolResult.Source.SERVER_REGISTRY,
        registry.find("server.recipe.lookup").orElseThrow().source());
    assertEquals(
        ReadToolResult.Source.PAPER_API,
        registry.find("player.context.read").orElseThrow().source());
  }

  @Test
  void rejectsUnknownToolsExtraArgumentsAndModuleEscapes() {
    assertFalse(
        registry
            .validate("server.command.execute", AgentModule.GENERAL, new JsonObject())
            .accepted());

    var extra = new JsonObject();
    extra.addProperty("playerUuid", java.util.UUID.randomUUID().toString());
    assertFalse(registry.validate("player.context.read", AgentModule.GENERAL, extra).accepted());

    assertFalse(
        registry
            .validate("server.recipe.lookup", AgentModule.BUILD, item("minecraft:stone"))
            .accepted());
    assertTrue(
        registry
            .validate("server.recipe.lookup", AgentModule.RECIPE, item("minecraft:stone"))
            .accepted());
  }

  @Test
  void recipeItemIdSchemaIsCanonicalAndClosed() {
    assertFalse(
        registry.validate("server.recipe.uses", AgentModule.RECIPE, item("STONE")).accepted());
    assertFalse(
        registry
            .validate("server.recipe.uses", AgentModule.RECIPE, item("minecraft:stone;op me"))
            .accepted());
    var extra = item("minecraft:stone");
    extra.addProperty("limit", 1000000);
    assertFalse(registry.validate("server.recipe.uses", AgentModule.RECIPE, extra).accepted());
  }

  private static JsonObject item(String itemId) {
    var result = new JsonObject();
    result.addProperty("itemId", itemId);
    return result;
  }
}
