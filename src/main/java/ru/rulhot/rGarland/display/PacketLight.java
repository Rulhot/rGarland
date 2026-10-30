package ru.rulhot.rGarland.display;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Levelled;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketLight {

    private static final int CHUNK_SHIFT = 4;
    private static final int MAX_LEVEL = 15;
    private static final int @NotNull [] LIGHT_IDS = new int[MAX_LEVEL + 1];
    private static final int AIR_ID = SpigotConversionUtil.fromBukkitBlockData(Material.AIR.createBlockData())
            .getGlobalId();

    static {
        Levelled light = (Levelled) Material.LIGHT.createBlockData();
        for (int level = 0; level <= MAX_LEVEL; level++) {
            light.setLevel(level);
            LIGHT_IDS[level] = SpigotConversionUtil.fromBukkitBlockData(light).getGlobalId();
        }
    }

    private final @NotNull World world;
    private final @NotNull List<Vector3i> positions;
    private final int @NotNull [] levels;
    private final @NotNull Set<User> viewers = ConcurrentHashMap.newKeySet();
    private boolean @NotNull [] air;

    PacketLight(@NotNull World world, @NotNull List<Vector3i> positions, int level) {
        this.world = world;
        this.positions = List.copyOf(positions);
        this.levels = new int[positions.size()];
        this.air = new boolean[positions.size()];
        Arrays.fill(levels, Math.clamp(level, 0, MAX_LEVEL));
    }

    void sync(@NotNull Collection<User> current) {
        boolean checked = false;
        for (User viewer : current) {
            if (viewers.contains(viewer)) {
                continue;
            }
            if (!checked) {
                air = air();
                checked = true;
            }
            for (int i = 0; i < positions.size(); i++) {
                if (air[i]) {
                    viewer.writePacket(new WrapperPlayServerBlockChange(positions.get(i), LIGHT_IDS[levels[i]]));
                }
            }
            viewer.flushPackets();
            viewers.add(viewer);
        }
        viewers.removeIf(viewer -> {
            if (current.contains(viewer)) {
                return false;
            }
            restore(viewer);
            return true;
        });
    }

    int size() {
        return levels.length;
    }

    void update(int @NotNull [] next) {
        List<WrapperPlayServerBlockChange> changes = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            int level = Math.clamp(next[i], 0, MAX_LEVEL);
            if (level == levels[i]) {
                continue;
            }
            levels[i] = level;
            if (air[i]) {
                changes.add(new WrapperPlayServerBlockChange(positions.get(i), LIGHT_IDS[level]));
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        for (User viewer : viewers) {
            changes.forEach(viewer::writePacket);
            viewer.flushPackets();
        }
    }

    void forget(@NotNull UUID viewer) {
        viewers.removeIf(user -> viewer.equals(user.getUUID()));
    }

    void destroy() {
        viewers.forEach(this::restore);
        viewers.clear();
    }

    private boolean @NotNull [] air() {
        boolean[] air = new boolean[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            Vector3i position = positions.get(i);
            air[i] = readable(position) && world.getBlockAt(position.x, position.y, position.z).getType().isAir();
        }
        return air;
    }

    private void restore(@NotNull User viewer) {
        try {
            for (int i = 0; i < positions.size(); i++) {
                Vector3i position = positions.get(i);
                if (readable(position)) {
                    int real = SpigotConversionUtil.fromBukkitBlockData(
                            world.getBlockData(position.x, position.y, position.z)).getGlobalId();
                    viewer.writePacket(new WrapperPlayServerBlockChange(position, real));
                } else if (air[i]) {
                    viewer.writePacket(new WrapperPlayServerBlockChange(position, AIR_ID));
                }
            }
            viewer.flushPackets();
        } catch (IllegalStateException exception) {
            viewers.remove(viewer);
        }
    }

    private boolean readable(@NotNull Vector3i position) {
        int chunkX = position.x >> CHUNK_SHIFT;
        int chunkZ = position.z >> CHUNK_SHIFT;
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ) && world.isChunkLoaded(chunkX, chunkZ);
    }
}
