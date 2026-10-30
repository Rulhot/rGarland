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

import java.util.Map;
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
    private final @NotNull Map<UUID, SchedulerUtil.Task> tasks = new ConcurrentHashMap<>();

    public void watch(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (!watched.add(playerId)) {
            return;
        }
        @Nullable GarlandPreview[] preview = {null};
        SchedulerUtil.Task task = scheduler.runForWhile(player, () -> {
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
            tasks.remove(playerId);
            watched.remove(playerId);
        }, 1, config.performance().previewTicks());
        if (task != null && watched.contains(playerId)) {
            tasks.put(playerId, task);
        }
    }

    public void shutdown() {
        tasks.values().forEach(SchedulerUtil.Task::cancel);
        tasks.clear();
        watched.clear();
    }
}
