package dev.minecraftagent.paper;

import org.bukkit.plugin.java.JavaPlugin;

public final class MinecraftAgentPlugin extends JavaPlugin {
  @Override
  public void onEnable() {
    getLogger().info("Minecraft Agent Paper scaffold " + getPluginMeta().getVersion() + " loaded");
  }
}
