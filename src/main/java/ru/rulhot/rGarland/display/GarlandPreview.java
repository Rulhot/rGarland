package ru.rulhot.rGarland.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.model.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RequiredArgsConstructor
public final class GarlandPreview {

    private static final float MARKER_SIZE = 0.12f;
    private static final float MARKER_HOVER_SIZE = 0.2f;
    private static final int MARKER_COLOR = 0xFFD54A;
    private static final int MARKER_HOVER_COLOR = 0xFF5555;
    private static final int PREVIEW_COLOR = 0x55FF55;
    private static final int TOO_LONG_COLOR = 0xFF5555;
    private static final int SEGMENTS = 16;
    private static final int MAX_BULBS = 64;
    private static final @NotNull Vector3f HIDDEN = new Vector3f();

    private record Ghost(@NotNull Location from, @NotNull GarlandPiece.Style style, @NotNull List<PacketDisplay> wires,
                         @NotNull List<PacketDisplay> sockets, @NotNull List<PacketDisplay> bulbs) {
    }

    private final @NotNull DisplayEffects effects;
    private final @NotNull User viewer;
    private final @NotNull Map<Location, PacketDisplay> markers = new HashMap<>();

    private @Nullable Location hovered;
    private @Nullable Ghost ghost;
    private @Nullable Location ghostTo;
    private boolean ghostTooLong;

    public void update(@NotNull GarlandSettings settings, @NotNull Settings.Performance view, double maxLength,
                       @NotNull List<Location> nodes, @Nullable Location hover, @Nullable Location from,
                       @Nullable Location to, @Nullable GarlandPiece.Style style, boolean tooLong) {
        List<DisplayEffects.Update> updates = new ArrayList<>();
        updateMarkers(settings, view, nodes, hover, updates);
        if (from == null || to == null || style == null) {
            disposeGhost();
        } else {
            updateGhost(settings, view, maxLength, from, to, style, tooLong, updates);
        }
        effects.send(List.of(viewer), updates);
    }

    public void clear() {
        markers.values().forEach(effects::remove);
        markers.clear();
        hovered = null;
        disposeGhost();
    }

    private void updateMarkers(@NotNull GarlandSettings settings, @NotNull Settings.Performance view,
                               @NotNull List<Location> nodes, @Nullable Location hover,
                               @NotNull List<DisplayEffects.Update> updates) {
        Set<Location> wanted = new HashSet<>(nodes);
        markers.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) {
                return false;
            }
            effects.remove(entry.getValue());
            return true;
        });
        for (Location node : nodes) {
            if (!markers.containsKey(node)) {
                markers.put(node, effects.spawn(EntityTypes.BLOCK_DISPLAY, List.of(viewer), node,
                        marker(settings, node.equals(hover), 0)));
            }
        }
        if (Objects.equals(hover, hovered)) {
            return;
        }
        for (Location changed : new Location[]{hovered, hover}) {
            PacketDisplay display = changed == null ? null : markers.get(changed);
            if (display != null) {
                updates.add(new DisplayEffects.Update(display,
                        marker(settings, changed.equals(hover), view.previewTicks())));
            }
        }
        hovered = hover;
    }

    private static @NotNull List<EntityData<?>> marker(@NotNull GarlandSettings settings, boolean hover, int ticks) {
        float size = hover ? MARKER_HOVER_SIZE : MARKER_SIZE;
        return List.of(
                PacketDisplay.interpolationStart(),
                PacketDisplay.interpolationDuration(ticks),
                PacketDisplay.block(settings.hookBlock()),
                PacketDisplay.translation(new Vector3f(-size / 2)),
                PacketDisplay.scale(new Vector3f(size)),
                PacketDisplay.fullBright(),
                PacketDisplay.glowFlag(),
                PacketDisplay.glowing(hover ? MARKER_HOVER_COLOR : MARKER_COLOR));
    }

    private void updateGhost(@NotNull GarlandSettings settings, @NotNull Settings.Performance view, double maxLength,
                             @NotNull Location from, @NotNull Location to, @NotNull GarlandPiece.Style style,
                             boolean tooLong, @NotNull List<DisplayEffects.Update> updates) {
        Ghost current = ghost;
        if (current == null || !current.from().equals(from) || !current.style().equals(style)) {
            disposeGhost();
            current = spawnGhost(settings, view, maxLength, from, style);
            ghost = current;
        } else if (to.equals(ghostTo) && tooLong == ghostTooLong) {
            return;
        }
        ghostTo = to;
        ghostTooLong = tooLong;
        int ticks = view.previewTicks();
        EntityData<?> color = PacketDisplay.glowing(tooLong ? TOO_LONG_COLOR : PREVIEW_COLOR);
        for (int i = 0; i < SEGMENTS; i++) {
            Vector3f start = GarlandPiece.curve(from, to, i / (double) SEGMENTS);
            Vector3f delta = GarlandPiece.curve(from, to, (i + 1) / (double) SEGMENTS).sub(start);
            Quaternionf rotation = GarlandPiece.along(delta);
            updates.add(new DisplayEffects.Update(current.wires().get(i), List.of(
                    PacketDisplay.interpolationStart(),
                    PacketDisplay.interpolationDuration(ticks),
                    PacketDisplay.translation(start.add(GarlandPiece.wireCorner(rotation))),
                    PacketDisplay.rotation(rotation),
                    PacketDisplay.scale(new Vector3f(GarlandPiece.WIRE_THICKNESS, delta.length(),
                            GarlandPiece.WIRE_THICKNESS)),
                    color)));
        }
        GarlandSettings.Palette palette = style.palette();
        double length = from.distance(to);
        int count = Math.min(current.bulbs().size(), GarlandPiece.bulbCount(length, view.bulbSpacing()));
        Quaternionf yaw = GarlandPiece.yaw(from, to);
        Vector3f corner = GarlandPiece.bulbCorner(palette, yaw);
        for (int i = 0; i < current.bulbs().size(); i++) {
            boolean shown = i < count;
            Vector3f hangs = shown ? GarlandPiece.curve(from, to, GarlandPiece.bulbT(i, count, length)) : HIDDEN;
            if (!current.sockets().isEmpty()) {
                updates.add(new DisplayEffects.Update(current.sockets().get(i), List.of(
                        PacketDisplay.interpolationStart(),
                        PacketDisplay.interpolationDuration(ticks),
                        PacketDisplay.translation(new Vector3f(hangs).add(GarlandPiece.socketCorner())),
                        PacketDisplay.scale(shown ? new Vector3f(GarlandPiece.SOCKET_SCALE) : HIDDEN),
                        color)));
            }
            Vector3f position = shown ? new Vector3f(hangs).sub(0, palette.drop(), 0).add(corner) : HIDDEN;
            updates.add(new DisplayEffects.Update(current.bulbs().get(i), List.of(
                    PacketDisplay.interpolationStart(),
                    PacketDisplay.interpolationDuration(ticks),
                    PacketDisplay.translation(position),
                    PacketDisplay.rotation(yaw),
                    PacketDisplay.scale(shown ? GarlandPiece.bulbSize(palette) : HIDDEN),
                    color)));
        }
    }

    private @NotNull Ghost spawnGhost(@NotNull GarlandSettings settings, @NotNull Settings.Performance view,
                                      double maxLength, @NotNull Location from, @NotNull GarlandPiece.Style style) {
        List<PacketDisplay> wires = new ArrayList<>(SEGMENTS);
        for (int i = 0; i < SEGMENTS; i++) {
            wires.add(effects.spawn(EntityTypes.BLOCK_DISPLAY, List.of(viewer), from, List.of(
                    PacketDisplay.block(settings.wireBlock()),
                    PacketDisplay.scale(HIDDEN),
                    PacketDisplay.fullBright(),
                    PacketDisplay.glowFlag())));
        }
        GarlandSettings.Palette palette = style.palette();
        EntityType type = palette.blocks() ? EntityTypes.BLOCK_DISPLAY : EntityTypes.ITEM_DISPLAY;
        int max = Math.clamp((int) Math.floor(maxLength / view.bulbSpacing()), 1, MAX_BULBS);
        List<PacketDisplay> bulbs = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            List<EntityData<?>> state = new ArrayList<>(List.of(
                    style.contents().get(i % style.contents().size()),
                    PacketDisplay.scale(HIDDEN),
                    PacketDisplay.brightness(GarlandPiece.FULL),
                    PacketDisplay.glowFlag()));
            if (!palette.blocks()) {
                state.add(PacketDisplay.itemTransformNone());
            }
            bulbs.add(effects.spawn(type, List.of(viewer), from, state));
        }
        List<PacketDisplay> sockets = new ArrayList<>(palette.socket() ? max : 0);
        for (int i = 0; palette.socket() && i < max; i++) {
            sockets.add(effects.spawn(EntityTypes.BLOCK_DISPLAY, List.of(viewer), from, List.of(
                    PacketDisplay.block(settings.socketBlock()),
                    PacketDisplay.scale(HIDDEN),
                    PacketDisplay.fullBright(),
                    PacketDisplay.glowFlag())));
        }
        return new Ghost(from, style, wires, sockets, bulbs);
    }

    private void disposeGhost() {
        Ghost current = ghost;
        ghost = null;
        ghostTo = null;
        if (current != null) {
            current.wires().forEach(effects::remove);
            current.sockets().forEach(effects::remove);
            current.bulbs().forEach(effects::remove);
        }
    }
}
