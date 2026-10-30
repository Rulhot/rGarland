package ru.rulhot.rGarland.service;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;
import ru.rulhot.rGarland.display.DisplayEffects;
import ru.rulhot.rGarland.display.GarlandPiece;
import ru.rulhot.rGarland.display.GarlandPreview;
import ru.rulhot.rGarland.display.PacketDisplay;
import ru.rulhot.rGarland.hook.ProtectionHook;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.model.GarlandSettings.Blink;
import ru.rulhot.rGarland.model.GarlandSettings.Palette;
import ru.rulhot.rGarland.model.GarlandSpan;
import ru.rulhot.rGarland.model.SpanGeometry;
import ru.rulhot.rGarland.storage.GarlandRepository;
import ru.rulhot.rGarland.util.MessageUtil;
import ru.rulhot.rGarland.util.SchedulerUtil;
import ru.rulhot.rGarland.util.logger.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class GarlandService {

    public static final @NotNull String PERMISSION_USE = "rgarland.use";
    public static final @NotNull String PERMISSION_OTHERS = "rgarland.others";

    private static final double SURFACE_OFFSET = 0.06;
    private static final double GRID = 16;
    private static final int REACH = 8;
    private static final double MIN_LENGTH = 0.5;
    private static final double POINT_REACH = 0.3;
    private static final double POINT_RADIUS = 24;
    private static final double MIN_ANGLE = 50;
    private static final double MIN_GAP = 0.6;
    private static final int GRAB_SAMPLES = 8;
    private static final double GRAB_DISTANCE = 0.35;
    private static final double WIRE_SAMPLE = 0.5;
    private static final @NotNull String LIMIT_PREFIX = "rgarland.limit.";
    private static final @NotNull String LENGTH_PREFIX = "rgarland.length.";
    private static final int MAX_SEGMENTS = 10_000;
    private static final int MAX_SPAN_LENGTH = 48;
    private static final int NO_RANK = -1;
    private static final @NotNull String WILDCARD = "*";
    private static final @NotNull Pattern RANK_NUMBER = Pattern.compile("\\d{1,18}");
    private static final double END_CLEARANCE = 0.25;
    private static final double MAX_SKIPPED = 0.25;
    private static final @NotNull String MODE_KEY = "modes.";

    public enum Use {
        NOTHING, STARTED, HUNG
    }

    private static final class Hung {

        private final @NotNull GarlandSpan span;
        private final @NotNull Location from;
        private final @NotNull Location to;
        private volatile @Nullable GarlandPiece piece;
        private volatile boolean removed;

        private Hung(@NotNull GarlandSpan span, @NotNull Location from, @NotNull Location to) {
            this.span = span;
            this.from = from;
            this.to = to;
        }

        private void attach(@NotNull GarlandPiece hanging) {
            piece = hanging;
            if (removed) {
                hanging.remove();
            }
        }

        private void remove() {
            removed = true;
            GarlandPiece current = piece;
            if (current != null) {
                current.remove();
            }
        }
    }

    private record Brush(@NotNull String palette, @NotNull Blink blink) {
    }

    private record BuildCheck(@NotNull Location point, boolean allowed) {
    }

    private record PathCheck(@NotNull Location from, @NotNull Location to, boolean blocked) {
    }

    static final class SpanIndex<T> {

        private static final int CELL_SHIFT = 5;

        private record Cell(@NotNull String world, int x, int z) {
        }

        private final @NotNull Map<Cell, Set<T>> cells = new ConcurrentHashMap<>();

        public void add(@NotNull T item, @NotNull String world, double minX, double minZ, double maxX, double maxZ) {
            forEachCell(world, minX, minZ, maxX, maxZ, cell -> cells.compute(cell, (key, items) -> {
                Set<T> target = items == null ? ConcurrentHashMap.newKeySet() : items;
                target.add(item);
                return target;
            }));
        }

        public void remove(@NotNull T item, @NotNull String world, double minX, double minZ, double maxX, double maxZ) {
            forEachCell(world, minX, minZ, maxX, maxZ, cell -> cells.computeIfPresent(cell, (key, items) -> {
                items.remove(item);
                return items.isEmpty() ? null : items;
            }));
        }

        public @NotNull Set<T> query(@NotNull String world, double minX, double minZ, double maxX, double maxZ) {
            Set<T> found = new LinkedHashSet<>();
            forEachCell(world, minX, minZ, maxX, maxZ, cell -> {
                Set<T> items = cells.get(cell);
                if (items != null) {
                    found.addAll(items);
                }
            });
            return found;
        }

        public void clear() {
            cells.clear();
        }

        private static void forEachCell(@NotNull String world, double minX, double minZ, double maxX, double maxZ,
                                        @NotNull Consumer<Cell> action) {
            int fromX = cell(Math.min(minX, maxX));
            int fromZ = cell(Math.min(minZ, maxZ));
            int toX = cell(Math.max(minX, maxX));
            int toZ = cell(Math.max(minZ, maxZ));
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    action.accept(new Cell(world, x, z));
                }
            }
        }

        private static int cell(double coordinate) {
            return (int) Math.floor(coordinate) >> CELL_SHIFT;
        }
    }

    private final @NotNull ConfigManager config;
    private final @NotNull DisplayEffects effects;
    private final @NotNull SchedulerUtil scheduler;
    private final @NotNull GarlandRepository repository;
    private final @NotNull ProtectionHook protection;
    private final @NotNull Object lock = new Object();
    private final @NotNull Map<UUID, Location> lastPoints = new ConcurrentHashMap<>();
    private final @NotNull Map<UUID, Brush> brushes = new ConcurrentHashMap<>();
    private final @NotNull Map<UUID, BuildCheck> buildChecks = new ConcurrentHashMap<>();
    private final @NotNull Map<UUID, PathCheck> pathChecks = new ConcurrentHashMap<>();
    private final @NotNull Map<Palette, List<EntityData<?>>> contents = new ConcurrentHashMap<>();
    private final @NotNull List<Hung> hung = new CopyOnWriteArrayList<>();
    private final @NotNull SpanIndex<Hung> index = new SpanIndex<>();
    private final @NotNull List<GarlandSpan> waiting = new CopyOnWriteArrayList<>();
    private final @NotNull AtomicBoolean restoring = new AtomicBoolean();

    private volatile boolean loaded;

    public GarlandService(@NotNull ConfigManager config, @NotNull DisplayEffects effects,
                          @NotNull SchedulerUtil scheduler, @NotNull GarlandRepository repository,
                          @NotNull ProtectionHook protection) {
        this.config = config;
        this.effects = effects;
        this.scheduler = scheduler;
        this.repository = repository;
        this.protection = protection;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void restore() {
        if (loaded || !restoring.compareAndSet(false, true)) {
            return;
        }
        repository.load().thenAccept(spans -> scheduler.runGlobal(() -> {
            try {
                if (spans == null) {
                    return;
                }
                synchronized (lock) {
                    spans.forEach(this::hang);
                    loaded = true;
                }
                Logger.info("Гирлянд загружено: " + spans.size());
            } finally {
                restoring.set(false);
            }
        }));
    }

    public void rebuild() {
        synchronized (lock) {
            contents.clear();
            List<GarlandSpan> spans = new ArrayList<>(waiting);
            waiting.clear();
            for (Hung current : hung) {
                current.remove();
                spans.add(current.span);
            }
            hung.clear();
            index.clear();
            spans.forEach(this::hang);
        }
    }

    public void loadWorld(@NotNull World world) {
        synchronized (lock) {
            List<GarlandSpan> ready = waiting.stream().filter(span -> span.world().equals(world.getName())).toList();
            if (ready.isEmpty()) {
                return;
            }
            waiting.removeAll(ready);
            ready.forEach(this::hang);
            Logger.debug("Мир " + world.getName() + " загружен, повешено пролётов: " + ready.size());
        }
    }

    public void unloadWorld(@NotNull World world) {
        synchronized (lock) {
            for (Hung current : hung) {
                if (current.span.world().equals(world.getName()) && drop(current)) {
                    waiting.add(current.span);
                }
            }
        }
    }

    public void shutdown() {
        synchronized (lock) {
            if (loaded) {
                repository.saveNow(spans());
            }
            hung.forEach(Hung::remove);
            hung.clear();
            index.clear();
            waiting.clear();
        }
        lastPoints.clear();
        brushes.clear();
        buildChecks.clear();
        pathChecks.clear();
    }

    public @NotNull Use use(@NotNull Player player, boolean refundable) {
        GarlandSettings settings = allowed(player);
        if (settings == null) {
            return Use.NOTHING;
        }
        UUID playerId = player.getUniqueId();
        Location node = hovered(player, nodes(player));
        Location point = node != null ? node : point(player);
        if (point == null) {
            config.sendActionBar(player, "garland.look-at-block");
            return Use.NOTHING;
        }
        if (!protection.canBuild(player, point)) {
            config.sendActionBar(player, "garland.protected");
            return Use.NOTHING;
        }
        Location last = lastPoints.get(playerId);
        if (last == null || last.getWorld() != point.getWorld()) {
            lastPoints.put(playerId, point);
            play(settings.placeSound(), point);
            config.sendActionBar(player, node != null ? "garland.continue" : "garland.first-point");
            return Use.STARTED;
        }
        if (point.equals(last)) {
            return Use.NOTHING;
        }
        double length = last.distance(point);
        double maxLength = lengthLimit(player, settings);
        if (length < MIN_LENGTH || length > maxLength) {
            config.send(player, "garland.bad-length", Map.of("max", (int) maxLength));
            return Use.NOTHING;
        }
        if (!Bukkit.isOwnedByCurrentRegion(last)) {
            config.sendActionBar(player, "garland.too-far");
            return Use.NOTHING;
        }
        if (throughBlocks(last, point)) {
            config.sendActionBar(player, "garland.through-block");
            return Use.NOTHING;
        }
        int spanLimit = spanLimit(player, settings);
        synchronized (lock) {
            if (blocked(last, point)) {
                config.sendActionBar(player, "garland.too-close");
                return Use.NOTHING;
            }
            if (owned(playerId) >= spanLimit) {
                config.send(player, "garland.limit", Map.of("max", spanLimit));
                return Use.NOTHING;
            }
            Brush brush = brush(playerId, settings);
            hang(GarlandSpan.of(playerId, player.getName(), last, point, brush.palette(), brush.blink().id(),
                    refundable));
            save();
        }
        lastPoints.remove(playerId);
        play(settings.placeSound(), point);
        config.sendActionBar(player, "garland.hung");
        Logger.debug(player.getName() + " повесил пролёт в мире " + point.getWorld().getName() + ", "
                + Math.round(length) + " бл., " + (refundable ? "из инвентаря" : "в креативе"));
        return Use.HUNG;
    }

    public void cancelStart(@NotNull Player player) {
        if (lastPoints.remove(player.getUniqueId()) != null) {
            config.sendActionBar(player, "garland.cancelled");
        }
    }

    private boolean throughBlocksCached(@NotNull Player player, @NotNull Location from, @NotNull Location to) {
        UUID playerId = player.getUniqueId();
        PathCheck cached = pathChecks.get(playerId);
        if (cached != null && cached.from().equals(from) && cached.to().equals(to)) {
            return cached.blocked();
        }
        boolean blocked = throughBlocks(from, to);
        pathChecks.put(playerId, new PathCheck(from, to, blocked));
        return blocked;
    }

    private static boolean throughBlocks(@NotNull Location from, @NotNull Location to) {
        double length = from.distance(to);
        double skipped = Math.min(MAX_SKIPPED, END_CLEARANCE / length);
        int steps = Math.max(1, (int) Math.ceil(length / WIRE_SAMPLE));
        Location previous = along(from, to, skipped);
        for (int i = 1; i <= steps; i++) {
            Location next = along(from, to, skipped + (1 - skipped * 2) * i / steps);
            if (!Bukkit.isOwnedByCurrentRegion(next)) {
                return true;
            }
            Vector direction = next.toVector().subtract(previous.toVector());
            double distance = direction.length();
            if (distance > 0 && solidHit(previous, direction, distance) != null) {
                return true;
            }
            previous = next;
        }
        return false;
    }

    private static @Nullable RayTraceResult solidHit(@NotNull Location start, @NotNull Vector direction,
                                                     double distance) {
        return start.getWorld().rayTraceBlocks(start, direction, distance, FluidCollisionMode.NEVER, false,
                block -> !block.isPassable());
    }

    private static @NotNull Location along(@NotNull Location from, @NotNull Location to, double fraction) {
        Vector3f shift = GarlandPiece.curve(from, to, fraction);
        return from.clone().add(shift.x, shift.y, shift.z);
    }

    public @Nullable GarlandSpan takeLooked(@NotNull Player player) {
        GarlandSettings settings = config.garland();
        if (settings == null || !loaded || !player.hasPermission(PERMISSION_USE)) {
            return null;
        }
        Hung best = looked(player);
        if (best == null) {
            return null;
        }
        synchronized (lock) {
            if (!drop(best)) {
                return null;
            }
            save();
        }
        play(settings.removeSound(), best.from.clone().add(best.to).multiply(0.5));
        Logger.debug(player.getName() + " снял пролёт игрока " + best.span.ownerName()
                + (best.span.refundable() ? "" : ", пролёт был повешен в креативе"));
        return best.span;
    }

    private @Nullable Hung looked(@NotNull Player player) {
        Location eye = player.getEyeLocation();
        Vector3d start = vector(eye);
        Vector direction = eye.getDirection();
        RayTraceResult wall = solidHit(eye, direction, REACH);
        double reach = wall == null ? REACH : wall.getHitPosition().distance(eye.toVector());
        Vector3d end = new Vector3d(direction.getX(), direction.getY(), direction.getZ()).mul(reach).add(start);
        Hung best = null;
        double nearest = Double.MAX_VALUE;
        for (Hung current : index.query(eye.getWorld().getName(), Math.min(start.x, end.x) - GRAB_DISTANCE,
                Math.min(start.z, end.z) - GRAB_DISTANCE, Math.max(start.x, end.x) + GRAB_DISTANCE,
                Math.max(start.z, end.z) + GRAB_DISTANCE)) {
            if (!editable(player, current)) {
                continue;
            }
            Vector3d origin = vector(current.from);
            Vector3d previous = origin;
            for (int i = 1; i <= GRAB_SAMPLES; i++) {
                Vector3f shift = GarlandPiece.curve(current.from, current.to, i / (double) GRAB_SAMPLES);
                Vector3d next = new Vector3d(origin).add(shift.x, shift.y, shift.z);
                if (SpanGeometry.segmentDistance(start, end, previous, next) < GRAB_DISTANCE) {
                    double distance = new Vector3d(previous).add(next).mul(0.5).distance(start);
                    if (distance < nearest) {
                        nearest = distance;
                        best = current;
                    }
                }
                previous = next;
            }
        }
        return best;
    }

    public @NotNull List<GarlandSpan> spans(@NotNull UUID owner) {
        return spans().stream()
                .filter(span -> span.owner().equals(owner))
                .sorted(Comparator.comparing(GarlandSpan::world)
                        .thenComparingDouble(GarlandSpan::fromX)
                        .thenComparingDouble(GarlandSpan::fromY)
                        .thenComparingDouble(GarlandSpan::fromZ))
                .toList();
    }

    public @NotNull Map<UUID, String> owners() {
        Map<UUID, String> owners = new HashMap<>();
        for (GarlandSpan span : spans()) {
            owners.merge(span.owner(), span.ownerName(), (known, next) -> known.isEmpty() ? next : known);
        }
        return owners;
    }

    public boolean remove(@NotNull GarlandSpan span) {
        synchronized (lock) {
            if (!loaded) {
                return false;
            }
            for (Hung current : hung) {
                if (current.span.equals(span) && drop(current)) {
                    save();
                    return true;
                }
            }
            if (waiting.remove(span)) {
                save();
                return true;
            }
            return false;
        }
    }

    public void cycle(@NotNull Player player, boolean blinkMode) {
        GarlandSettings settings = allowed(player);
        if (settings == null) {
            return;
        }
        Location node = hovered(player, nodes(player));
        List<Hung> chain = node == null ? List.of() : chain(player, node);
        if (chain.isEmpty()) {
            return;
        }
        Brush current = new Brush(settings.palette(chain.getFirst().span.palette()).id(),
                Blink.parse(chain.getFirst().span.blink()));
        Brush next = blinkMode
                ? new Brush(current.palette(), current.blink().next())
                : new Brush(settings.nextPalette(current.palette()).id(), current.blink());
        brushes.put(player.getUniqueId(), next);
        synchronized (lock) {
            for (Hung old : chain) {
                if (drop(old)) {
                    hang(old.span.styled(next.palette(), next.blink().id()));
                }
            }
            save();
        }
        play(settings.switchSound(), player.getLocation());
        if (blinkMode) {
            config.sendActionBar(player, "garland.mode-applied",
                    Map.of("name", config.message(MODE_KEY + next.blink().id())));
            return;
        }
        config.sendActionBar(player, "garland.palette-applied",
                Map.of("name", MessageUtil.parseText(settings.palette(next.palette()).name(), Map.of())));
    }

    public void updatePreview(@NotNull Player player, @NotNull GarlandPreview preview) {
        GarlandSettings settings = config.garland();
        if (settings == null) {
            preview.clear();
            return;
        }
        List<Location> nodes = nodes(player);
        Location node = hovered(player, nodes);
        Location last = lastPoints.get(player.getUniqueId());
        Location point = last == null ? null : node != null ? node : point(player);
        boolean drawn = point != null && point.getWorld() == last.getWorld() && last.distance(point) >= MIN_LENGTH;
        double maxLength = drawn ? lengthLimit(player, settings) : settings.maxLength();
        boolean bad = drawn && (last.distance(point) > maxLength || blocked(last, point)
                || throughBlocksCached(player, last, point)
                || !canBuildCached(player, point));
        GarlandPiece.Style style = null;
        if (drawn) {
            Brush brush = brush(player.getUniqueId(), settings);
            Palette palette = settings.palette(brush.palette());
            style = new GarlandPiece.Style(palette, contents(palette), brush.blink());
        }
        preview.update(settings, config.performance(), maxLength, nodes, node, drawn ? last : null,
                drawn ? point : null, style, bad);
    }

    private static int spanLimit(@NotNull Player player, @NotNull GarlandSettings settings) {
        return highest(granted(player), LIMIT_PREFIX, settings.maxSegments(), 1, MAX_SEGMENTS);
    }

    private static double lengthLimit(@NotNull Player player, @NotNull GarlandSettings settings) {
        int ranked = highest(granted(player), LENGTH_PREFIX, NO_RANK, 1, MAX_SPAN_LENGTH);
        return ranked == NO_RANK ? settings.maxLength() : ranked;
    }

    static int highest(@NotNull Iterable<String> granted, @NotNull String prefix, int fallback, int min, int max) {
        long best = NO_RANK;
        for (String permission : granted) {
            String lower = permission.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(prefix)) {
                continue;
            }
            String value = lower.substring(prefix.length());
            if (value.equals(WILDCARD)) {
                best = Math.max(best, max);
            } else if (RANK_NUMBER.matcher(value).matches()) {
                best = Math.max(best, Math.clamp(Long.parseLong(value), min, max));
            }
        }
        return best == NO_RANK ? fallback : (int) best;
    }

    private static @NotNull List<String> granted(@NotNull Player player) {
        return player.getEffectivePermissions().stream()
                .filter(PermissionAttachmentInfo::getValue)
                .map(PermissionAttachmentInfo::getPermission)
                .toList();
    }

    public void forget(@NotNull UUID playerId) {
        lastPoints.remove(playerId);
        brushes.remove(playerId);
        buildChecks.remove(playerId);
        pathChecks.remove(playerId);
    }

    private @Nullable GarlandSettings allowed(@NotNull Player player) {
        GarlandSettings settings = config.garland();
        if (settings == null) {
            return null;
        }
        if (!player.hasPermission(PERMISSION_USE)) {
            config.sendActionBar(player, "garland.no-permission");
            return null;
        }
        if (!loaded) {
            config.sendActionBar(player, "garland.storage-error");
            return null;
        }
        return settings;
    }

    private boolean canBuildCached(@NotNull Player player, @NotNull Location point) {
        UUID playerId = player.getUniqueId();
        BuildCheck cached = buildChecks.get(playerId);
        if (cached != null && cached.point().equals(point)) {
            return cached.allowed();
        }
        boolean allowed = protection.canBuild(player, point);
        buildChecks.put(playerId, new BuildCheck(point, allowed));
        return allowed;
    }

    private boolean blocked(@NotNull Location from, @NotNull Location to) {
        Vector3d start = vector(from);
        Vector3d end = vector(to);
        for (Hung current : index.query(from.getWorld().getName(), Math.min(start.x, end.x) - MIN_GAP,
                Math.min(start.z, end.z) - MIN_GAP, Math.max(start.x, end.x) + MIN_GAP,
                Math.max(start.z, end.z) + MIN_GAP)) {
            Location sharedFrom = shared(from, current);
            Location sharedTo = shared(to, current);
            if (sharedFrom != null && sharedTo != null) {
                return true;
            }
            if (sharedFrom != null) {
                if (SpanGeometry.angleDegrees(start, end, vector(sharedFrom)) < MIN_ANGLE) {
                    return true;
                }
                continue;
            }
            if (sharedTo != null) {
                if (SpanGeometry.angleDegrees(end, start, vector(sharedTo)) < MIN_ANGLE) {
                    return true;
                }
                continue;
            }
            if (SpanGeometry.segmentDistance(start, end, vector(current.from), vector(current.to)) < MIN_GAP) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Location shared(@NotNull Location node, @NotNull Hung current) {
        if (node.equals(current.from)) {
            return current.to;
        }
        return node.equals(current.to) ? current.from : null;
    }

    private void hang(@NotNull GarlandSpan span) {
        GarlandSettings settings = config.garland();
        World world = Bukkit.getWorld(span.world());
        if (settings == null || world == null) {
            waiting.add(span);
            return;
        }
        Hung added = new Hung(span, span.from(world), span.to(world));
        hung.add(added);
        index.add(added, span.world(), span.fromX(), span.fromZ(), span.toX(), span.toZ());
        scheduler.runAt(added.from, () -> {
            if (!added.removed) {
                added.attach(GarlandPiece.hang(effects, settings, config.performance(), style(settings, span),
                        added.from, added.to));
            }
        });
    }

    private boolean drop(@NotNull Hung current) {
        if (!hung.remove(current)) {
            return false;
        }
        GarlandSpan span = current.span;
        index.remove(current, span.world(), span.fromX(), span.fromZ(), span.toX(), span.toZ());
        current.remove();
        return true;
    }

    private @NotNull Set<Hung> touching(@NotNull Location node) {
        Set<Hung> found = index.query(node.getWorld().getName(), node.getX(), node.getZ(), node.getX(), node.getZ());
        found.removeIf(current -> !touches(current, node));
        return found;
    }

    private @NotNull Brush brush(@NotNull UUID playerId, @NotNull GarlandSettings settings) {
        Brush brush = brushes.get(playerId);
        if (brush == null) {
            return new Brush(settings.palettes().getFirst().id(), Blink.SHIFT);
        }
        return new Brush(settings.palette(brush.palette()).id(), brush.blink());
    }

    private @NotNull GarlandPiece.Style style(@NotNull GarlandSettings settings, @NotNull GarlandSpan span) {
        Palette palette = settings.palette(span.palette());
        return new GarlandPiece.Style(palette, contents(palette), Blink.parse(span.blink()));
    }

    private @NotNull List<EntityData<?>> contents(@NotNull Palette palette) {
        return contents.computeIfAbsent(palette, key -> key.bulbs().stream()
                .<EntityData<?>>map(bulb -> key.blocks()
                        ? PacketDisplay.block(Bukkit.createBlockData(bulb.toLowerCase(Locale.ROOT)))
                        : PacketDisplay.item(item(bulb)))
                .toList());
    }

    private static @NotNull ItemStack item(@NotNull String bulb) {
        if (GarlandSettings.TEXTURE.matcher(bulb).matches()) {
            return ItemService.head(bulb);
        }
        Material material = Material.matchMaterial(bulb);
        return ItemStack.of(material == null ? Material.GLOWSTONE_DUST : material);
    }

    private @NotNull List<Hung> chain(@NotNull Player player, @NotNull Location start) {
        Set<Hung> chain = new LinkedHashSet<>();
        Set<Location> seen = new HashSet<>(List.of(start));
        Deque<Location> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            Location node = queue.poll();
            for (Hung current : touching(node)) {
                if (!editable(player, current) || !chain.add(current)) {
                    continue;
                }
                Location other = node.equals(current.from) ? current.to : current.from;
                if (seen.add(other)) {
                    queue.add(other);
                }
            }
        }
        return List.copyOf(chain);
    }

    private static boolean touches(@NotNull Hung current, @NotNull Location node) {
        return node.equals(current.from) || node.equals(current.to);
    }

    private int owned(@NotNull UUID playerId) {
        int count = 0;
        for (Hung current : hung) {
            if (current.span.owner().equals(playerId)) {
                count++;
            }
        }
        for (GarlandSpan span : waiting) {
            if (span.owner().equals(playerId)) {
                count++;
            }
        }
        return count;
    }

    private void save() {
        if (loaded) {
            repository.save(spans());
        }
    }

    private @NotNull List<GarlandSpan> spans() {
        List<GarlandSpan> spans = new ArrayList<>(waiting);
        hung.forEach(current -> spans.add(current.span));
        return spans;
    }

    private static boolean editable(@NotNull Player player, @NotNull Hung current) {
        return current.span.owner().equals(player.getUniqueId()) || player.hasPermission(PERMISSION_OTHERS);
    }

    private @NotNull List<Location> nodes(@NotNull Player player) {
        Location eye = player.getEyeLocation();
        double radiusSquared = POINT_RADIUS * POINT_RADIUS;
        Set<Location> nodes = new LinkedHashSet<>();
        for (Hung current : index.query(eye.getWorld().getName(), eye.getX() - POINT_RADIUS,
                eye.getZ() - POINT_RADIUS, eye.getX() + POINT_RADIUS, eye.getZ() + POINT_RADIUS)) {
            if (!editable(player, current)) {
                continue;
            }
            for (Location node : List.of(current.from, current.to)) {
                if (node.distanceSquared(eye) <= radiusSquared) {
                    nodes.add(node);
                }
            }
        }
        return List.copyOf(nodes);
    }

    private static @Nullable Location hovered(@NotNull Player player, @NotNull List<Location> nodes) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        Location best = null;
        double nearest = REACH;
        for (Location node : nodes) {
            Vector toward = node.toVector().subtract(eye.toVector());
            double along = toward.dot(direction);
            if (along <= 0 || along >= nearest) {
                continue;
            }
            if (toward.lengthSquared() - along * along <= POINT_REACH * POINT_REACH) {
                nearest = along;
                best = node;
            }
        }
        return best;
    }

    private static @Nullable Location point(@NotNull Player player) {
        RayTraceResult hit = player.rayTraceBlocks(REACH);
        if (hit == null || hit.getHitBlockFace() == null) {
            return null;
        }
        Vector at = hit.getHitPosition().add(hit.getHitBlockFace().getDirection().multiply(SURFACE_OFFSET));
        return new Location(player.getWorld(), snap(at.getX()), snap(at.getY()), snap(at.getZ()));
    }

    private static double snap(double value) {
        return Math.round(value * GRID) / GRID;
    }

    private static @NotNull Vector3d vector(@NotNull Location location) {
        return new Vector3d(location.getX(), location.getY(), location.getZ());
    }

    private static void play(@Nullable Sound sound, @NotNull Location at) {
        if (sound != null) {
            at.getWorld().playSound(sound, at.getX(), at.getY(), at.getZ());
        }
    }
}
