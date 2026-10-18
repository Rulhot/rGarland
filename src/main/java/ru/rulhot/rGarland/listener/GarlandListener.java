package ru.rulhot.rGarland.listener;

import lombok.RequiredArgsConstructor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.service.GarlandService;
import ru.rulhot.rGarland.service.ItemService;

import java.util.UUID;

@RequiredArgsConstructor
public final class GarlandListener implements Listener {

    private final @NotNull ConfigManager config;
    private final @NotNull ItemService items;
    private final @NotNull GarlandService garlands;

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(@NotNull PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        ItemStack item = event.getItem();
        if (event.getHand() == EquipmentSlot.HAND && event.getAction().isLeftClick()
                && event.getPlayer().isSneaking() && !items.isGarland(item)) {
            takeBack(event);
            return;
        }
        if (event.getHand() == null || !items.isGarland(item)) {
            return;
        }
        if (config.garland() == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.hasCooldown(item)) {
            return;
        }
        boolean survival = player.getGameMode() != GameMode.CREATIVE;
        if (event.getAction().isLeftClick()) {
            int refund = garlands.leftClick(player);
            if (survival && refund > 0) {
                refund(player, refund);
            }
        } else {
            GarlandService.Use result = garlands.use(player);
            if (result == GarlandService.Use.NOTHING) {
                return;
            }
            if (result == GarlandService.Use.HUNG && survival) {
                player.getInventory().getItem(event.getHand()).subtract();
            }
        }
        player.setCooldown(item, ItemService.COOLDOWN_TICKS);
    }

    private void takeBack(@NotNull PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID owner = garlands.takeLooked(player);
        if (owner == null) {
            return;
        }
        event.setCancelled(true);
        if (owner.equals(player.getUniqueId()) && player.getGameMode() != GameMode.CREATIVE) {
            refund(player, 1);
            config.sendActionBar(player, "garland.taken-back");
            return;
        }
        config.sendActionBar(player, "garland.taken");
    }

    private void refund(@NotNull Player player, int amount) {
        GarlandSettings settings = config.garland();
        if (settings == null) {
            return;
        }
        player.getInventory().addItem(items.create(settings.item(), amount)).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
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
    }
}
