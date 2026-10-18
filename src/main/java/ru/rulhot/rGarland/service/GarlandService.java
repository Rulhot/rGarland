package ru.rulhot.rGarland.service;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
    private static final double PARALLEL = 1.0E-9;
    private static final int GRAB_SAMPLES = 8;
    private static final double GRAB_DISTANCE = 0.35;

    public enum Use {
        NOTHING, STARTED, HUNG
    }
    private static final @NotNull String MODE_KEY = "modes.";
    private static final @NotNull String APPLIED_SUFFIX = "-applied";

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

        private void dispose() {
            removed = true;
            GarlandPiece current = piece;
            if (current != null) {
                current.dispose();
            }
        }
    }

    private record Brush(@NotNull String palette, @NotNull Blink blink) {
    }

    private final @NotNull ConfigManager config;
    private final @NotNull DisplayEffects effects;
    private final @NotNull SchedulerUtil scheduler;
    private final @NotNull GarlandRepository repository;
    private final @NotNull ProtectionHook protection;
    private final @NotNull Map<UUID, Location> lastPoints = new ConcurrentHashMap<>();
    private final @NotNull Map<UUID, Brush> brushes = new ConcurrentHashMap<>();
    private final @NotNull Map<Palette, List<EntityData<?>>> contents = new ConcurrentHashMap<>();
    private final @NotNull List<Hung> hung = new CopyOnWriteArrayList<>();
    private final @NotNull List<GarlandSpan> waiting = new CopyOnWriteArrayList<>();

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

    public void restore() {
        repository.load().thenAccept(spans -> scheduler.runGlobal(() -> {
            if (spans == null) {
                return;
            }
            spans.forEach(this::hang);
            loaded = true;
            Logger.info("Гирлянд загружено: " + spans.size());
        }));
    }

    public void rebuild() {
        contents.clear();
        List<GarlandSpan> spans = new ArrayList<>(waiting);
        waiting.clear();
        for (Hung current : hung) {
            current.remove();
            spans.add(current.span);
        }
        hung.clear();
        spans.forEach(this::hang);
    }

    public void shutdown() {
        if (loaded) {
            repository.saveNow(spans());
        }
        hung.forEach(Hung::dispose);
        hung.clear();
        waiting.clear();
        lastPoints.clear();
        brushes.clear();
    }

    public @NotNull Use use(@NotNull Player player) {
        GarlandSettings settings = allowed(player);
        if (settings == null) {
            return Use.NOTHING;
        }
        UUID playerId = player.getUniqueId();
        Location node = hovered(player, settings);
        Location point = node != null ? node : point(player, settings);
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
        if (length < MIN_LENGTH || length > settings.maxLength()) {
            config.send(player, "garland.bad-length", Map.of("max", (int) settings.maxLength()));
            return Use.NOTHING;
        }
        if (blocked(last, point)) {
            config.sendActionBar(player, "garland.too-close");
            return Use.NOTHING;
        }
        if (owned(playerId) >= settings.maxSegments()) {
            config.send(player, "garland.limit", Map.of("max", settings.maxSegments()));
            return Use.NOTHING;
        }
        Brush brush = brush(playerId, settings);
        GarlandSpan span = GarlandSpan.of(playerId, player.getName(), last, point, brush.palette(),
                brush.blink().id());
        Hung added = new Hung(span, last, point);
        hung.add(added);
        added.attach(GarlandPiece.hang(effects, settings, config.performance(), style(settings, span), last, point));
        save();
        lastPoints.remove(playerId);
        play(settings.placeSound(), point);
        config.sendActionBar(player, "garland.hung");
        return Use.HUNG;
    }

    public int leftClick(@NotNull Player player) {
        GarlandSettings settings = allowed(player);
        if (settings == null) {
            return 0;
        }
        if (lastPoints.remove(player.getUniqueId()) != null) {
            config.sendActionBar(player, "garland.cancelled");
            return 0;
        }
        Location node = hovered(player, settings);
        if (node == null) {
            config.sendActionBar(player, "garland.nothing");
            return 0;
        }
        int removed = 0;
        int own = 0;
        for (Hung current : hung) {
            if (editable(player, current) && touches(current, node)) {
                hung.remove(current);
                current.remove();
                removed++;
                if (current.span.owner().equals(player.getUniqueId())) {
                    own++;
                }
            }
        }
        if (removed > 0) {
            save();
        }
        play(settings.removeSound(), node);
        config.sendActionBar(player, "garland.removed", Map.of("count", removed));
        return own;
    }

    private boolean blocked(@NotNull Location from, @NotNull Location to) {
        double reach = from.distance(to) / 2 + MIN_GAP;
        Location middle = from.clone().add(to).multiply(0.5);
        for (Hung current : hung) {
            if (current.from.getWorld() != from.getWorld()) {
                continue;
            }
            double other = current.from.distance(current.to) / 2;
            if (current.from.clone().add(current.to).multiply(0.5).distanceSquared(middle)
                    > (reach + other) * (reach + other)) {
                continue;
            }
            Location sharedFrom = shared(from, current);
            Location sharedTo = shared(to, current);
            if (sharedFrom != null && sharedTo != null) {
                return true;
            }
            if (sharedFrom != null) {
                if (angle(from, to, sharedFrom) < MIN_ANGLE) {
                    return true;
                }
                continue;
            }
            if (sharedTo != null) {
                if (angle(to, from, sharedTo) < MIN_ANGLE) {
                    return true;
                }
                continue;
            }
            if (gap(from, to, current.from, current.to) < MIN_GAP) {
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

    private static double angle(@NotNull Location node, @NotNull Location first, @NotNull Location second) {
        Vector a = first.toVector().subtract(node.toVector());
        Vector b = second.toVector().subtract(node.toVector());
        return Math.toDegrees(a.angle(b));
    }

    private static double gap(@NotNull Location firstStart, @NotNull Location firstEnd,
                              @NotNull Location secondStart, @NotNull Location secondEnd) {
        return gap(firstStart.toVector(), firstEnd.toVector(), secondStart.toVector(), secondEnd.toVector());
    }

    private static double gap(@NotNull Vector p, @NotNull Vector firstEnd, @NotNull Vector q,
                              @NotNull Vector secondEnd) {
        Vector d1 = firstEnd.clone().subtract(p);
        Vector d2 = secondEnd.clone().subtract(q);
        Vector r = p.clone().subtract(q);
        double a = d1.dot(d1);
        double e = d2.dot(d2);
        double f = d2.dot(r);
        double c = d1.dot(r);
        double b = d1.dot(d2);
        double denominator = a * e - b * b;
        double s = denominator > PARALLEL ? Math.clamp((b * f - c * e) / denominator, 0, 1) : 0;
        double t = (b * s + f) / e;
        if (t < 0) {
            t = 0;
            s = Math.clamp(-c / a, 0, 1);
        } else if (t > 1) {
            t = 1;
            s = Math.clamp((b - c) / a, 0, 1);
        }
        Vector closestFirst = p.clone().add(d1.clone().multiply(s));
        Vector closestSecond = q.clone().add(d2.clone().multiply(t));
        return closestFirst.distance(closestSecond);
    }

    public @Nullable UUID takeLooked(@NotNull Player player) {
        GarlandSettings settings = config.garland();
        if (settings == null || !player.hasPermission(PERMISSION_USE)) {
            return null;
        }
        Location eye = player.getEyeLocation();
        Vector start = eye.toVector();
        Vector end = start.clone().add(eye.getDirection().multiply(REACH));
        Hung best = null;
        double nearest = Double.MAX_VALUE;
        for (Hung current : hung) {
            if (current.from.getWorld() != eye.getWorld() || !editable(player, current)) {
                continue;
            }
            double half = current.from.distance(current.to) / 2;
            Location middle = current.from.clone().add(current.to).multiply(0.5);
            if (middle.distance(eye) > REACH + half) {
                continue;
            }
            Vector previous = current.from.toVector();
            for (int i = 1; i <= GRAB_SAMPLES; i++) {
                Vector next = current.from.toVector()
                        .add(Vector.fromJOML(GarlandPiece.curve(current.from, current.to, i / (double) GRAB_SAMPLES)));
                if (gap(start, end, previous, next) < GRAB_DISTANCE) {
                    double distance = previous.clone().add(next).multiply(0.5).distance(start);
                    if (distance < nearest) {
                        nearest = distance;
                        best = current;
                    }
                }
                previous = next;
            }
        }
        if (best == null) {
            return null;
        }
        hung.remove(best);
        best.remove();
        save();
        play(settings.removeSound(), best.from.clone().add(best.to).multiply(0.5));
        return best.span.owner();
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
        for (Hung current : hung) {
            if (current.span.equals(span) && hung.remove(current)) {
                current.remove();
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

    public void cycle(@NotNull Player player, boolean blinkMode) {
        GarlandSettings settings = allowed(player);
        if (settings == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Location node = hovered(player, settings);
        List<Hung> chain = node == null ? List.of() : chain(player, node);
        Brush current = chain.isEmpty() ? brush(playerId, settings) : new Brush(
                settings.palette(chain.getFirst().span.palette()).id(), Blink.parse(chain.getFirst().span.blink()));
        Brush next = blinkMode
                ? new Brush(current.palette(), current.blink().next())
                : new Brush(settings.nextPalette(current.palette()).id(), current.blink());
        brushes.put(playerId, next);
        for (Hung old : chain) {
            hung.remove(old);
            old.remove();
            hang(old.span.styled(next.palette(), next.blink().id()));
        }
        if (!chain.isEmpty()) {
            save();
        }
        play(settings.switchSound(), player.getLocation());
        String suffix = chain.isEmpty() ? "" : APPLIED_SUFFIX;
        if (blinkMode) {
            config.sendActionBar(player, "garland.mode" + suffix,
                    Map.of("name", config.message(MODE_KEY + next.blink().id())));
            return;
        }
        config.sendActionBar(player, "garland.palette" + suffix,
                Map.of("name", MessageUtil.parseText(settings.palette(next.palette()).name(), Map.of())));
    }

    public void updatePreview(@NotNull Player player, @NotNull GarlandPreview preview) {
        GarlandSettings settings = config.garland();
        if (settings == null) {
            preview.clear();
            return;
        }
        List<Location> nodes = nodes(player, settings);
        Location node = hovered(player, settings, nodes);
        Location last = lastPoints.get(player.getUniqueId());
        Location point = last == null ? null : node != null ? node : point(player, settings);
        boolean drawn = point != null && point.getWorld() == last.getWorld() && last.distance(point) >= MIN_LENGTH;
        boolean bad = drawn && (last.distance(point) > settings.maxLength() || blocked(last, point)
                || !protection.canBuild(player, point));
        GarlandPiece.Style style = null;
        if (drawn) {
            Brush brush = brush(player.getUniqueId(), settings);
            Palette palette = settings.palette(brush.palette());
            style = new GarlandPiece.Style(palette, contents(palette), brush.blink());
        }
        preview.update(settings, config.performance(), nodes, node, drawn ? last : null, drawn ? point : null, style,
                bad);
    }

    public void forget(@NotNull UUID playerId) {
        lastPoints.remove(playerId);
        brushes.remove(playerId);
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
        return settings;
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
        scheduler.runAt(added.from, () -> {
            if (!added.removed) {
                added.attach(GarlandPiece.hang(effects, settings, config.performance(), style(settings, span),
                        added.from, added.to));
            }
        });
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
        List<Hung> chain = new ArrayList<>();
        Set<Location> seen = new HashSet<>(List.of(start));
        Deque<Location> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            Location node = queue.poll();
            for (Hung current : hung) {
                if (chain.contains(current) || !editable(player, current) || !touches(current, node)) {
                    continue;
                }
                chain.add(current);
                Location other = node.equals(current.from) ? current.to : current.from;
                if (seen.add(other)) {
                    queue.add(other);
                }
            }
        }
        return chain;
    }

    private static boolean touches(@NotNull Hung current, @NotNull Location node) {
        return node.equals(current.from) || node.equals(current.to);
    }

    private int owned(@NotNull UUID playerId) {
        int count = 0;
        for (GarlandSpan span : spans()) {
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

    private @NotNull List<Location> nodes(@NotNull Player player, @NotNull GarlandSettings settings) {
        Location eye = player.getEyeLocation();
        double radiusSquared = POINT_RADIUS * POINT_RADIUS;
        Set<Location> nodes = new LinkedHashSet<>();
        for (Hung current : hung) {
            if (!editable(player, current)) {
                continue;
            }
            for (Location node : List.of(current.from, current.to)) {
                if (node.getWorld() == eye.getWorld() && node.distanceSquared(eye) <= radiusSquared) {
                    nodes.add(node);
                }
            }
        }
        return List.copyOf(nodes);
    }

    private @Nullable Location hovered(@NotNull Player player, @NotNull GarlandSettings settings) {
        return hovered(player, settings, nodes(player, settings));
    }

    private static @Nullable Location hovered(@NotNull Player player, @NotNull GarlandSettings settings,
                                              @NotNull List<Location> nodes) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        Location best = null;
        double nearest = REACH;
        double reach = POINT_REACH;
        for (Location node : nodes) {
            Vector toward = node.toVector().subtract(eye.toVector());
            double along = toward.dot(direction);
            if (along <= 0 || along >= nearest) {
                continue;
            }
            if (toward.lengthSquared() - along * along <= reach * reach) {
                nearest = along;
                best = node;
            }
        }
        return best;
    }

    private static @Nullable Location point(@NotNull Player player, @NotNull GarlandSettings settings) {
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

    private static void play(@Nullable Sound sound, @NotNull Location at) {
        if (sound != null) {
            at.getWorld().playSound(sound, at.getX(), at.getY(), at.getZ());
        }
    }
}
