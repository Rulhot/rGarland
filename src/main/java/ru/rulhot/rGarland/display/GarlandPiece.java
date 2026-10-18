package ru.rulhot.rGarland.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.model.GarlandSettings.Blink;
import ru.rulhot.rGarland.model.Settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GarlandPiece {

    static final float WIRE_THICKNESS = 0.025f;
    static final int FULL = 15;

    private static final double END_GAP = 0.35;

    private static final double SAG = 0.08;
    private static final double BASE_SAG = 0.1;
    private static final double MAX_SAG = 1.5;
    private static final float HOOK_SIZE = 0.09f;
    static final float SOCKET_SCALE = 0.5f;
    private static final float SOCKET_CENTER = 0.25f;
    private static final float SOCKET_HEIGHT = 0.375f;
    private static final int CHASE_GAP = 3;
    private static final double WAVE_LENGTH = 8;
    private static final double BREATHE_STEPS = 8;
    private static final double TWINKLE_CHANCE = 0.15;
    private static final double TAU = Math.PI * 2;
    private static final double MIN_HORIZONTAL = 1.0E-4;
    private static final double MAX_SWING_SLOPE = 0.95;
    private static final int PHASE_BUCKETS = 1024;
    private static final double PHASE_SPREAD = 0.25;
    private static final long NIGHT_START = 13_000;
    private static final long NIGHT_END = 23_000;
    private static final int OFF = -1;
    private static final int DIM = 3;
    private static final long MILLIS_PER_TICK = 50;
    private static final @NotNull Vector3f UP = new Vector3f(0, 1, 0);

    public record Style(@NotNull GarlandSettings.Palette palette, @NotNull List<EntityData<?>> contents,
                        @NotNull Blink blink) {

        public Style {
            contents = List.copyOf(contents);
        }
    }

    private static class Part {

        protected final @NotNull PacketDisplay display;
        protected final @NotNull Location location;
        protected final @NotNull Vector3f translation;
        protected final @NotNull Quaternionf rotation;
        protected final @NotNull Vector3f relative;
        protected final boolean detail;

        private Part(@NotNull PacketDisplay display, @NotNull Location location, @NotNull Location anchor,
                     @NotNull Vector3f translation, @NotNull Quaternionf rotation, boolean detail) {
            this.display = display;
            this.location = location;
            this.translation = translation;
            this.rotation = rotation;
            this.relative = new Vector3f((float) (location.getX() - anchor.getX()),
                    (float) (location.getY() - anchor.getY()), (float) (location.getZ() - anchor.getZ()));
            this.detail = detail;
        }

        @NotNull DisplayEffects.Update swing(@NotNull Quaternionf turn, int ticks) {
            Vector3f moved = new Vector3f(relative).rotate(turn).sub(relative).add(new Vector3f(translation).rotate(turn));
            return new DisplayEffects.Update(display, List.of(
                    PacketDisplay.interpolationStart(),
                    PacketDisplay.interpolationDuration(ticks),
                    PacketDisplay.translation(moved),
                    PacketDisplay.rotation(new Quaternionf(turn).mul(rotation))));
        }
    }

    private static final class Block extends Part {

        private final @NotNull List<EntityData<?>> state;

        private Block(@NotNull PacketDisplay display, @NotNull Location location, @NotNull Location anchor,
                      @NotNull Vector3f translation, @NotNull Quaternionf rotation, boolean detail,
                      @NotNull List<EntityData<?>> state) {
            super(display, location, anchor, translation, rotation, detail);
            this.state = state;
        }
    }

    private static final class Bulb extends Part {

        private final int index;
        private final int lightSlot;
        private int content;
        private int brightness;

        private Bulb(@NotNull PacketDisplay display, @NotNull Location location, @NotNull Location anchor,
                     @NotNull Vector3f translation, @NotNull Quaternionf rotation, int index, int lightSlot) {
            super(display, location, anchor, translation, rotation, false);
            this.index = index;
            this.lightSlot = lightSlot;
        }
    }

    private final @NotNull GarlandSettings settings;
    private final @NotNull Settings.Performance view;
    private final @NotNull Style style;
    private final @NotNull DisplayEffects effects;
    private final @NotNull World world;
    private final @NotNull Location from;
    private final @NotNull Location to;
    private final @NotNull Location center;
    private final double radius;
    private final @NotNull Quaternionf turn;
    private final @Nullable Vector3f axis;
    private final double phase;
    private final long seed;
    private final @NotNull List<Block> blocks = new ArrayList<>();
    private final @NotNull List<Bulb> bulbs = new ArrayList<>();

    private @Nullable PacketLight light;
    private @NotNull Set<User> far = Set.of();
    private @NotNull Set<User> near = Set.of();
    private boolean lit = true;
    private int untilSync;
    private int untilSwing;
    private int untilLight;
    private volatile boolean removed;

    private GarlandPiece(@NotNull GarlandSettings settings, @NotNull Settings.Performance view, @NotNull Style style,
                         @NotNull DisplayEffects effects, @NotNull Location from, @NotNull Location to) {
        this.settings = settings;
        this.view = view;
        this.style = style;
        this.effects = effects;
        this.world = from.getWorld();
        this.from = from.clone();
        this.to = to.clone();
        this.center = offset(from, curve(from, to, 0.5));
        this.radius = from.distance(to) / 2;
        this.turn = yaw(from, to);
        Vector3f chord = new Vector3f((float) (to.getX() - from.getX()), (float) (to.getY() - from.getY()),
                (float) (to.getZ() - from.getZ()));
        boolean swings = chord.lengthSquared() > 0 && Math.abs(chord.normalize().y) < MAX_SWING_SLOPE;
        this.axis = swings ? chord : null;
        this.seed = from.hashCode() * 31L + to.hashCode();
        this.phase = Math.floorMod(seed, PHASE_BUCKETS) / (double) PHASE_BUCKETS * TAU * PHASE_SPREAD;
    }

    public static @NotNull GarlandPiece hang(@NotNull DisplayEffects effects, @NotNull GarlandSettings settings,
                                             @NotNull Settings.Performance view, @NotNull Style style,
                                             @NotNull Location from, @NotNull Location to) {
        GarlandPiece garland = new GarlandPiece(settings, view, style, effects, from, to);
        garland.spawn();
        garland.sync();
        effects.run(garland.center, garland::tick, garland::discard, view.animationTicks());
        return garland;
    }

    public void remove() {
        removed = true;
    }

    public void dispose() {
        discard();
    }

    public static @NotNull Vector3f curve(@NotNull Location from, @NotNull Location to, double t) {
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        double sag = Math.min(MAX_SAG, BASE_SAG + horizontal * SAG);
        return new Vector3f((float) ((to.getX() - from.getX()) * t),
                (float) ((to.getY() - from.getY()) * t - sag * 4 * t * (1 - t)),
                (float) ((to.getZ() - from.getZ()) * t));
    }

    static @NotNull Quaternionf yaw(@NotNull Location from, @NotNull Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        double alongX = horizontal < MIN_HORIZONTAL ? 1 : dx / horizontal;
        double alongZ = horizontal < MIN_HORIZONTAL ? 0 : dz / horizontal;
        return new Quaternionf().rotateY((float) Math.atan2(-alongZ, alongX));
    }

    static @NotNull Quaternionf along(@NotNull Vector3f delta) {
        return new Quaternionf().rotateTo(UP, new Vector3f(delta).normalize());
    }

    static @NotNull Vector3f wireCorner(@NotNull Quaternionf rotation) {
        return new Vector3f(-WIRE_THICKNESS / 2, 0, -WIRE_THICKNESS / 2).rotate(rotation);
    }

    static @NotNull Vector3f bulbCorner(@NotNull GarlandSettings.Palette palette, @NotNull Quaternionf yaw) {
        return palette.blocks()
                ? new Vector3f(-palette.sizeX() / 2, -palette.sizeY() / 2, -palette.sizeZ() / 2).rotate(yaw)
                : new Vector3f();
    }

    static int bulbCount(double length, double spacing) {
        return Math.max(1, (int) Math.floor(Math.max(0, length - END_GAP * 2) / spacing));
    }

    static double bulbT(int index, int count, double length) {
        double gap = Math.min(END_GAP, length / 4) / length;
        return gap + (index + 0.5) / count * (1 - gap * 2);
    }

    static @NotNull Vector3f socketCorner() {
        return new Vector3f(-SOCKET_CENTER * SOCKET_SCALE * 2, -SOCKET_HEIGHT * SOCKET_SCALE,
                -SOCKET_CENTER * SOCKET_SCALE * 2);
    }

    static @NotNull Vector3f bulbSize(@NotNull GarlandSettings.Palette palette) {
        return new Vector3f(palette.sizeX(), palette.sizeY(), palette.sizeZ());
    }

    private void spawn() {
        GarlandSettings.Palette palette = style.palette();
        lit = shouldLight();
        double length = radius * 2;
        int steps = Math.max(1, (int) Math.ceil(length / view.wireStep()));
        Location previous = at(0);
        for (int i = 1; i <= steps; i++) {
            Location next = at(i / (double) steps);
            Vector3f delta = new Vector3f((float) (next.getX() - previous.getX()),
                    (float) (next.getY() - previous.getY()), (float) (next.getZ() - previous.getZ()));
            Quaternionf rotation = along(delta);
            place(settings.wireBlock(), previous, rotation, wireCorner(rotation),
                    new Vector3f(WIRE_THICKNESS, delta.length(), WIRE_THICKNESS), false);
            previous = next;
        }
        for (Location end : List.of(from, to)) {
            place(settings.hookBlock(), end, new Quaternionf(), new Vector3f(-HOOK_SIZE / 2),
                    new Vector3f(HOOK_SIZE), true);
        }
        long now = now();
        int count = bulbCount(length, view.bulbSpacing());
        Map<Vector3i, Integer> lights = new LinkedHashMap<>();
        EntityType type = palette.blocks() ? EntityTypes.BLOCK_DISPLAY : EntityTypes.ITEM_DISPLAY;
        Vector3f corner = bulbCorner(palette, turn);
        for (int i = 0; i < count; i++) {
            Location hangs = at(bulbT(i, count, length));
            if (palette.socket()) {
                place(settings.socketBlock(), hangs, new Quaternionf(), socketCorner(), new Vector3f(SOCKET_SCALE),
                        true);
            }
            Location bulbAt = hangs.clone().subtract(0, palette.drop(), 0);
            int slot = lights.computeIfAbsent(new Vector3i(bulbAt.getBlockX(), bulbAt.getBlockY(), bulbAt.getBlockZ()),
                    position -> lights.size());
            Bulb bulb = new Bulb(effects.spawn(type, List.of(), bulbAt, List.of()), bulbAt, from, corner,
                    new Quaternionf(turn), i, slot);
            bulb.content = contentAt(i, now);
            bulb.brightness = brightnessAt(i, now);
            bulbs.add(bulb);
        }
        if (palette.light() > 0) {
            PacketLight created = effects.light(world, List.copyOf(lights.keySet()), palette.light());
            light = created;
            created.update(lightLevels(lights.size()));
        }
    }

    private boolean tick() {
        if (removed) {
            return false;
        }
        untilSync -= view.animationTicks();
        if (untilSync <= 0) {
            untilSync += view.viewerCheckTicks();
            sync();
        }
        if (far.isEmpty()) {
            return true;
        }
        boolean shouldLight = shouldLight();
        if (shouldLight != lit) {
            lit = shouldLight;
            PacketLight current = light;
            if (current != null) {
                effects.sync(current, lit ? near : Set.of());
            }
        }
        long now = now();
        List<DisplayEffects.Update> updates = new ArrayList<>();
        for (Bulb bulb : bulbs) {
            List<EntityData<?>> changes = new ArrayList<>(2);
            int content = contentAt(bulb.index, now);
            if (content != bulb.content) {
                bulb.content = content;
                changes.add(style.contents().get(content));
            }
            int brightness = brightnessAt(bulb.index, now);
            if (brightness != bulb.brightness) {
                bulb.brightness = brightness;
                changes.add(PacketDisplay.brightness(brightness));
            }
            if (!changes.isEmpty()) {
                updates.add(new DisplayEffects.Update(bulb.display, changes));
            }
        }
        effects.send(far, updates);
        untilLight -= view.animationTicks();
        PacketLight currentLight = light;
        if (untilLight <= 0 && currentLight != null && lit) {
            untilLight += view.lightUpdateTicks();
            currentLight.update(lightLevels(currentLight.size()));
        }
        untilSwing -= view.animationTicks();
        if (untilSwing <= 0) {
            untilSwing += view.swingTicks();
            if (axis != null && settings.wind().angle() > 0 && !near.isEmpty()) {
                swing(now + view.swingTicks());
            }
        }
        return true;
    }

    private int @NotNull [] lightLevels(int slots) {
        int[] levels = new int[slots];
        int light = style.palette().light();
        for (Bulb bulb : bulbs) {
            int level = bulb.brightness == OFF ? 0 : Math.round(light * bulb.brightness / (float) FULL);
            levels[bulb.lightSlot] = Math.max(levels[bulb.lightSlot], level);
        }
        return levels;
    }

    private boolean shouldLight() {
        if (!settings.nightOnly()) {
            return true;
        }
        long time = world.getTime();
        return time >= NIGHT_START && time < NIGHT_END;
    }

    private void swing(long at) {
        GarlandSettings.Wind wind = settings.wind();
        float angle = (float) (wind.angle() * Math.sin(TAU * at / wind.periodTicks() + phase));
        Quaternionf rotation = new Quaternionf().rotateAxis(angle, axis);
        List<DisplayEffects.Update> updates = new ArrayList<>(blocks.size() + bulbs.size());
        for (Block block : blocks) {
            updates.add(block.swing(rotation, view.swingTicks()));
        }
        for (Bulb bulb : bulbs) {
            updates.add(bulb.swing(rotation, view.swingTicks()));
        }
        effects.send(near, updates);
    }

    private void sync() {
        DisplayEffects.Viewers viewers = effects.viewers(center, view.viewDistance() + radius,
                view.detailDistance() + radius);
        Set<User> nowFar = viewers.far();
        Set<User> nowNear = viewers.near();
        for (Block block : blocks) {
            effects.sync(block.display, block.detail ? nowNear : nowFar, block.location, () -> block.state);
        }
        for (Bulb bulb : bulbs) {
            effects.sync(bulb.display, nowFar, bulb.location, () -> bulbState(bulb));
        }
        PacketLight current = light;
        if (current != null) {
            effects.sync(current, lit ? nowNear : Set.of());
        }
        Set<User> touched = new HashSet<>(far);
        touched.addAll(nowFar);
        effects.flush(touched);
        far = nowFar;
        near = nowNear;
    }

    private int contentAt(int index, long now) {
        int size = style.contents().size();
        if (!lit || style.blink() != Blink.SHIFT || size == 1) {
            return index % size;
        }
        return (int) Math.floorMod(index + now / settings.blinkTicks(), size);
    }

    private int brightnessAt(int index, long now) {
        if (!lit) {
            return OFF;
        }
        long step = now / settings.blinkTicks();
        double beat = now / (double) settings.blinkTicks();
        return switch (style.blink()) {
            case SHIFT, STEADY -> FULL;
            case CHASE -> Math.floorMod(index - step, CHASE_GAP) == 0 ? FULL : DIM;
            case WAVE -> level(Math.sin(TAU * (beat - index) / WAVE_LENGTH));
            case TWINKLE -> random(index, step) < TWINKLE_CHANCE ? DIM : FULL;
            case BREATHE -> level(Math.sin(TAU * beat / BREATHE_STEPS));
        };
    }

    private @NotNull List<EntityData<?>> bulbState(@NotNull Bulb bulb) {
        GarlandSettings.Palette palette = style.palette();
        List<EntityData<?>> state = new ArrayList<>(List.of(
                style.contents().get(bulb.content),
                PacketDisplay.brightness(bulb.brightness),
                PacketDisplay.scale(bulbSize(palette)),
                PacketDisplay.rotation(bulb.rotation),
                PacketDisplay.translation(bulb.translation)));
        if (!palette.blocks()) {
            state.add(PacketDisplay.itemTransformNone());
        }
        return state;
    }

    private double random(int index, long step) {
        long hash = seed ^ index * 0x9E3779B97F4A7C15L ^ step * 0xC2B2AE3D27D4EB4FL;
        hash = (hash ^ hash >>> 31) * 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 29;
        return (hash >>> 11) * 0x1.0p-53;
    }

    private static int level(double wave) {
        return DIM + (int) Math.round((FULL - DIM) * (wave + 1) / 2);
    }

    private static long now() {
        return System.currentTimeMillis() / MILLIS_PER_TICK;
    }

    private @NotNull Location at(double t) {
        return offset(from, curve(from, to, t));
    }

    static @NotNull Location offset(@NotNull Location base, @NotNull Vector3f shift) {
        return base.clone().add(shift.x, shift.y, shift.z);
    }

    private void place(@NotNull BlockData block, @NotNull Location at, @NotNull Quaternionf rotation,
                       @NotNull Vector3f translation, @NotNull Vector3f size, boolean detail) {
        List<EntityData<?>> state = List.of(
                PacketDisplay.block(block),
                PacketDisplay.translation(translation),
                PacketDisplay.scale(size),
                PacketDisplay.rotation(rotation));
        blocks.add(new Block(effects.spawn(EntityTypes.BLOCK_DISPLAY, List.of(), at, state), at, from,
                translation, rotation, detail, state));
    }

    private void discard() {
        removed = true;
        blocks.forEach(block -> effects.remove(block.display));
        bulbs.forEach(bulb -> effects.remove(bulb.display));
        blocks.clear();
        bulbs.clear();
        PacketLight current = light;
        light = null;
        if (current != null) {
            effects.remove(current);
        }
    }
}
