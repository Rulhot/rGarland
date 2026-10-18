package ru.rulhot.rGarland.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PacketDisplay {

    private static final int FLAGS_BASE = 0;
    private static final byte GLOWING_FLAG = 0x40;
    private static final int GLOW_COLOR = 22;
    private static final int INTERPOLATION_DELAY = 8;
    private static final int INTERPOLATION_DURATION = 9;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int LEFT_ROTATION = 13;
    private static final int BRIGHTNESS = 16;
    private static final int CONTENT = 23;
    private static final int ITEM_TRANSFORM = 24;

    private static final byte ITEM_TRANSFORM_NONE = 0;
    private static final int BLOCK_LIGHT_SHIFT = 4;
    private static final int SKY_LIGHT_SHIFT = 20;
    private static final int FULL_BRIGHT = 15;
    private static final int NATURAL_LIGHT = -1;

    private final @NotNull EntityType type;
    private final int entityId;
    private final @NotNull Set<User> viewers = ConcurrentHashMap.newKeySet();

    PacketDisplay(@NotNull EntityType type, @NotNull Collection<User> viewers, @NotNull Location location,
                  @NotNull List<EntityData<?>> state) {
        this.type = type;
        this.entityId = SpigotReflectionUtil.generateEntityId();
        viewers.forEach(viewer -> show(viewer, location, state));
    }

    int entityId() {
        return entityId;
    }

    boolean isViewer(@NotNull User user) {
        return viewers.contains(user);
    }

    void sync(@NotNull Collection<User> current, @NotNull Location location,
              @NotNull Supplier<List<EntityData<?>>> state) {
        List<EntityData<?>> shown = null;
        for (User viewer : current) {
            if (viewers.contains(viewer)) {
                continue;
            }
            if (shown == null) {
                shown = state.get();
            }
            show(viewer, location, shown);
        }
        WrapperPlayServerDestroyEntities[] destroy = {null};
        viewers.removeIf(viewer -> {
            if (current.contains(viewer)) {
                return false;
            }
            if (destroy[0] == null) {
                destroy[0] = new WrapperPlayServerDestroyEntities(entityId);
            }
            viewer.writePacket(destroy[0]);
            return true;
        });
    }

    void forget(@NotNull UUID viewer) {
        viewers.removeIf(user -> viewer.equals(user.getUUID()));
    }

    void destroy() {
        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(entityId);
        viewers.forEach(viewer -> viewer.sendPacket(destroy));
        viewers.clear();
    }

    private void show(@NotNull User viewer, @NotNull Location location, @NotNull List<EntityData<?>> state) {
        viewer.writePacket(new WrapperPlayServerSpawnEntity(
                entityId,
                UUID.randomUUID(),
                type,
                new com.github.retrooper.packetevents.protocol.world.Location(location.getX(), location.getY(),
                        location.getZ(), 0f, 0f),
                0f,
                0,
                null));
        viewer.writePacket(new WrapperPlayServerEntityMetadata(entityId, state));
        viewers.add(viewer);
    }

    public static @NotNull EntityData<?> glowing(int color) {
        return new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, color);
    }

    public static @NotNull EntityData<?> glowFlag() {
        return new EntityData<>(FLAGS_BASE, EntityDataTypes.BYTE, GLOWING_FLAG);
    }

    public static @NotNull EntityData<?> translation(@NotNull Vector3f translation) {
        return new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F,
                new com.github.retrooper.packetevents.util.Vector3f(translation.x, translation.y, translation.z));
    }

    public static @NotNull EntityData<?> scale(@NotNull Vector3f scale) {
        return new EntityData<>(SCALE, EntityDataTypes.VECTOR3F,
                new com.github.retrooper.packetevents.util.Vector3f(scale.x, scale.y, scale.z));
    }

    public static @NotNull EntityData<?> rotation(@NotNull Quaternionf rotation) {
        return new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION,
                new Quaternion4f(rotation.x, rotation.y, rotation.z, rotation.w));
    }

    public static @NotNull EntityData<?> fullBright() {
        return brightness(FULL_BRIGHT);
    }

    public static @NotNull EntityData<?> brightness(int level) {
        if (level < 0) {
            return new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, NATURAL_LIGHT);
        }
        return new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, level << BLOCK_LIGHT_SHIFT | level << SKY_LIGHT_SHIFT);
    }

    public static @NotNull EntityData<?> interpolationStart() {
        return new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0);
    }

    public static @NotNull EntityData<?> interpolationDuration(int ticks) {
        return new EntityData<>(INTERPOLATION_DURATION, EntityDataTypes.INT, ticks);
    }

    public static @NotNull EntityData<?> block(@NotNull BlockData block) {
        return new EntityData<>(CONTENT, EntityDataTypes.BLOCK_STATE,
                SpigotConversionUtil.fromBukkitBlockData(block).getGlobalId());
    }

    public static @NotNull EntityData<?> item(@NotNull ItemStack item) {
        return new EntityData<>(CONTENT, EntityDataTypes.ITEMSTACK, SpigotConversionUtil.fromBukkitItemStack(item));
    }

    public static @NotNull EntityData<?> itemTransformNone() {
        return new EntityData<>(ITEM_TRANSFORM, EntityDataTypes.BYTE, ITEM_TRANSFORM_NONE);
    }
}
