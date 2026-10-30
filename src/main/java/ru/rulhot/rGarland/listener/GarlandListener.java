package ru.rulhot.rGarland.listener;

import lombok.RequiredArgsConstructor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.model.GarlandSpan;
import ru.rulhot.rGarland.service.GarlandService;
import ru.rulhot.rGarland.service.ItemService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public final class GarlandListener implements Listener {

    private static final long CUT_COOLDOWN_MILLIS = 250;

    private final @NotNull ConfigManager config;
    private final @NotNull ItemService items;
    private final @NotNull GarlandService garlands;
    private final @NotNull Map<UUID, Long> lastCuts = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(@NotNull PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.HAND && event.getAction().isRightClick()
                && item != null && item.getType() == Material.SHEARS) {
            if (!recentlyCut(player)) {
                cut(event);
            }
            return;
        }
        if (event.getHand() == null || !items.isGarland(item)) {
            return;
        }
        if (config.garland() == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND && recentlyCut(player)) {
            return;
        }
        if (player.hasCooldown(item)) {
            return;
        }
        boolean survival = player.getGameMode() != GameMode.CREATIVE;
        if (event.getAction().isLeftClick()) {
            garlands.cancelStart(player);
        } else {
            GarlandService.Use result = garlands.use(player, survival);
            if (result == GarlandService.Use.NOTHING) {
                return;
            }
            if (result == GarlandService.Use.HUNG && survival) {
                player.getInventory().getItem(event.getHand()).subtract();
            }
        }
        player.setCooldown(item, ItemService.COOLDOWN_TICKS);
    }

    private boolean recentlyCut(@NotNull Player player) {
        Long last = lastCuts.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < CUT_COOLDOWN_MILLIS;
    }

    private void cut(@NotNull PlayerInteractEvent event) {
        Player player = event.getPlayer();
        GarlandSpan span = garlands.takeLooked(player);
        if (span == null) {
            return;
        }
        event.setCancelled(true);
        lastCuts.put(player.getUniqueId(), System.currentTimeMillis());
        GarlandSettings settings = config.garland();
        if (settings != null && span.owner().equals(player.getUniqueId()) && span.refundable()) {
            player.getInventory().addItem(items.create(settings.item(), 1)).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            config.sendActionBar(player, "garland.taken-back");
            return;
        }
        config.sendActionBar(player, "garland.taken");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(@NotNull PlayerSwapHandItemsEvent event) {
        if (!items.isGarland(event.getOffHandItem())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        garlands.cycle(player, player.isSneaking());
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        garlands.forget(event.getPlayer().getUniqueId());
        lastCuts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(@NotNull WorldLoadEvent event) {
        garlands.loadWorld(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(@NotNull WorldUnloadEvent event) {
        garlands.unloadWorld(event.getWorld());
    }
}
