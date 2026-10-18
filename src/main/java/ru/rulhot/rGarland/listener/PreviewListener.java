package ru.rulhot.rGarland.listener;

import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.service.PreviewService;

@RequiredArgsConstructor
public final class PreviewListener implements Listener {

    private final @NotNull PreviewService previews;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(@NotNull PlayerItemHeldEvent event) {
        previews.watch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(@NotNull PlayerSwapHandItemsEvent event) {
        previews.watch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        previews.watch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(@NotNull EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            previews.watch(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            previews.watch(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            previews.watch(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            previews.watch(player);
        }
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        previews.watch(event.getPlayer());
    }
}
