package ru.rulhot.rGarland.listener;

import io.papermc.paper.event.player.PlayerTradeEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.service.ItemService;

@RequiredArgsConstructor
public final class ItemProtectionListener implements Listener {

    private final @NotNull ItemService items;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (items.isGarland(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        if (items.isGarland(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(@NotNull PrepareItemCraftEvent event) {
        if (containsGarland(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(@NotNull CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof Crafter crafter
                && containsGarland(crafter.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(@NotNull PlayerTradeEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory() instanceof MerchantInventory merchant
                && (items.isGarland(merchant.getItem(0)) || items.isGarland(merchant.getItem(1)))) {
            event.setCancelled(true);
        }
    }

    private boolean containsGarland(@Nullable ItemStack @NotNull [] contents) {
        for (ItemStack item : contents) {
            if (items.isGarland(item)) {
                return true;
            }
        }
        return false;
    }
}
