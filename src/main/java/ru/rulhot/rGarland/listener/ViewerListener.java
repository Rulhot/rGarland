package ru.rulhot.rGarland.listener;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.display.DisplayEffects;

@RequiredArgsConstructor
public final class ViewerListener implements Listener {

    private final @NotNull DisplayEffects effects;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        effects.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        effects.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(@NotNull PlayerPostRespawnEvent event) {
        effects.forget(event.getPlayer().getUniqueId());
    }
}
