package ru.rulhot.rGarland.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.display.DisplayEffects;
import ru.rulhot.rGarland.display.GarlandPreview;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.util.SchedulerUtil;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public final class PreviewService {

    private final @NotNull SchedulerUtil scheduler;
    private final @NotNull ConfigManager config;
    private final @NotNull DisplayEffects effects;
    private final @NotNull ItemService items;
    private final @NotNull GarlandService garlands;
    private final @NotNull Set<UUID> watched = ConcurrentHashMap.newKeySet();

    public void watch(@NotNull Player player) {
        if (!watched.add(player.getUniqueId())) {
            return;
        }
        @Nullable GarlandPreview[] preview = {null};
        scheduler.runForWhile(player, () -> {
            if (!player.isValid() || !items.isGarland(player.getInventory().getItemInMainHand())) {
                return false;
            }
            if (preview[0] == null) {
                User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                if (user == null) {
                    return true;
                }
                preview[0] = new GarlandPreview(effects, user);
            }
            garlands.updatePreview(player, preview[0]);
            return true;
        }, () -> {
            if (preview[0] != null) {
                preview[0].clear();
            }
            watched.remove(player.getUniqueId());
        }, 1, config.performance().previewTicks());
    }
}
