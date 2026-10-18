package ru.rulhot.rGarland.display;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.util.SchedulerUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class DisplayEffects {

    private static final int BUNDLE_LIMIT = 4000;

    public record Update(@NotNull PacketDisplay display, @NotNull List<EntityData<?>> state) {
    }

    public record Viewers(@NotNull Set<User> far, @NotNull Set<User> near) {
    }

    private final @NotNull SchedulerUtil scheduler;
    private final @NotNull Set<PacketDisplay> displays = ConcurrentHashMap.newKeySet();
    private final @NotNull Set<PacketLight> lights = ConcurrentHashMap.newKeySet();

    public @NotNull Viewers viewers(@NotNull Location center, double farRadius, double nearRadius) {
        Set<User> far = new HashSet<>();
        Set<User> near = new HashSet<>();
        double farSquared = farRadius * farRadius;
        double nearSquared = nearRadius * nearRadius;
        for (Player player : center.getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(center);
            if (distance > farSquared) {
                continue;
            }
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) {
                continue;
            }
            far.add(user);
            if (distance <= nearSquared) {
                near.add(user);
            }
        }
        return new Viewers(far, near);
    }

    public @NotNull PacketDisplay spawn(@NotNull EntityType type, @NotNull List<User> viewers,
                                        @NotNull Location location, @NotNull List<EntityData<?>> state) {
        PacketDisplay display = new PacketDisplay(type, viewers, location, state);
        displays.add(display);
        flush(viewers);
        return display;
    }

    public @NotNull PacketLight light(@NotNull World world, @NotNull List<Vector3i> positions, int level) {
        PacketLight light = new PacketLight(world, positions, level);
        lights.add(light);
        return light;
    }

    public void sync(@NotNull PacketDisplay display, @NotNull Set<User> viewers, @NotNull Location location,
                     @NotNull Supplier<List<EntityData<?>>> state) {
        display.sync(viewers, location, state);
    }

    public void sync(@NotNull PacketLight light, @NotNull Set<User> viewers) {
        light.sync(viewers);
    }

    public void send(@NotNull Collection<User> users, @NotNull List<Update> updates) {
        if (updates.isEmpty() || users.isEmpty()) {
            return;
        }
        List<WrapperPlayServerEntityMetadata> packets = new ArrayList<>(updates.size());
        for (Update update : updates) {
            packets.add(new WrapperPlayServerEntityMetadata(update.display().entityId(), update.state()));
        }
        for (User user : users) {
            int bundled = 0;
            for (int i = 0; i < packets.size(); i++) {
                if (!updates.get(i).display().isViewer(user)) {
                    continue;
                }
                if (bundled == 0) {
                    user.writePacket(new WrapperPlayServerBundle());
                }
                user.writePacket(packets.get(i));
                if (++bundled == BUNDLE_LIMIT) {
                    user.writePacket(new WrapperPlayServerBundle());
                    bundled = 0;
                }
            }
            if (bundled > 0) {
                user.writePacket(new WrapperPlayServerBundle());
            }
            user.flushPackets();
        }
    }

    public void flush(@NotNull Collection<User> users) {
        users.forEach(User::flushPackets);
    }

    public void run(@NotNull Location location, @NotNull BooleanSupplier tick, @NotNull Runnable finished,
                    long periodTicks) {
        scheduler.runAtWhile(location, tick, finished, periodTicks, periodTicks);
    }

    public void remove(@NotNull PacketDisplay display) {
        if (displays.remove(display)) {
            display.destroy();
        }
    }

    public void remove(@NotNull PacketLight light) {
        if (lights.remove(light)) {
            light.destroy();
        }
    }

    public void forget(@NotNull UUID viewer) {
        displays.forEach(display -> display.forget(viewer));
        lights.forEach(light -> light.forget(viewer));
    }

    public void clear() {
        displays.forEach(PacketDisplay::destroy);
        displays.clear();
        lights.forEach(PacketLight::destroy);
        lights.clear();
    }
}
