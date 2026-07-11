package dev.minecraftagent.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftAgentClient implements ClientModInitializer {
  public static final String MOD_ID = "minecraftagent";
  private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitializeClient() {
    LOGGER.info("Minecraft Agent client scaffold loaded");
  }
}
