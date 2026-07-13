package dev.minecraftagent.paper.request;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class AgentPlayerListener implements Listener {
  private final AgentRequestService requests;

  public AgentPlayerListener(AgentRequestService requests) {
    this.requests = Objects.requireNonNull(requests);
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    requests.cancelPlayer(event.getPlayer().getUniqueId());
  }
}
