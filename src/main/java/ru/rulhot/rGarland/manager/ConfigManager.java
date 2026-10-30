package ru.rulhot.rGarland.manager;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.model.Settings;
import ru.rulhot.rGarland.util.MessageUtil;
import ru.rulhot.rGarland.util.logger.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ru.rulhot.rGarland.manager.ConfigFile.join;

@RequiredArgsConstructor
public final class ConfigManager {

    private static final @NotNull String MESSAGES_FILE = "messages.yml";
    private static final @NotNull String MESSAGE_PREFIX = "prefix";
    private static final @NotNull String PREFIX_TOKEN = "{prefix}";
    private static final @NotNull String COMMAND_TOKEN = "{command}";
    private static final @NotNull String ITEM = "item";
    private static final @NotNull String SOUNDS = "sounds";
    private static final @NotNull String BLOCKS = "blocks";
    private static final @NotNull String PALETTES = "list";
    private static final @NotNull String GARLAND = "garland";
    private static final @NotNull String LINE_BREAK = "\n";
    private static final @NotNull String LIMITS = "limits";
    private static final @NotNull String BLOCK_STATE_START = "[";
    private static final int SIZE_AXES = 3;
    private static final float DEFAULT_SIZE = 0.3f;
    private static final @NotNull String TEXTURE_PATH = "textures.minecraft.net/texture/";
    private static final @NotNull Pattern PALETTE_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final int TICKS_PER_SECOND = 20;
    private static final @NotNull Pattern HEX_TAG = Pattern.compile("</?#[0-9a-fA-F]{6}>");
    private static final @NotNull Map<String, String> CONSOLE_SYMBOLS = Map.of("»", ">", "•", "*");

    private final @NotNull JavaPlugin plugin;

    private volatile @Nullable Snapshot snapshot;
    private volatile @NotNull Messages messages = Messages.EMPTY;
    private volatile @NotNull String command = Settings.Command.DEFAULT_NAME;
    private final @NotNull Set<String> missingKeys = ConcurrentHashMap.newKeySet();

    public @Nullable Snapshot read() {
        ConfigFile settings = ConfigFile.load(plugin, Settings.FILE, Set.of());
        ConfigFile garland = ConfigFile.load(plugin, GarlandSettings.FILE, Set.of(PALETTES));
        ConfigFile messagesFile = ConfigFile.load(plugin, MESSAGES_FILE, Set.of());
        if (settings == null || garland == null || messagesFile == null) {
            return null;
        }
        return new Snapshot(parseSettings(settings), parseGarland(garland, settings), parseMessages(messagesFile));
    }

    public void apply(@NotNull Snapshot loaded) {
        Logger.setDebugEnabled(loaded.settings().debug());
        messages = loaded.messages();
        missingKeys.clear();
        snapshot = loaded;
    }

    public @NotNull Settings readStartup() {
        ConfigFile file = ConfigFile.load(plugin, Settings.FILE, Set.of());
        if (file == null) {
            return Settings.DEFAULT;
        }
        Settings parsed = parseSettings(file);
        command = parsed.command().name();
        Logger.setDebugEnabled(parsed.debug());
        return parsed;
    }

    static @NotNull Settings parseSettings(@NotNull ConfigFile file) {
        return new Settings(
                file.bool(Settings.METRICS, true),
                file.bool(Settings.UPDATE_CHECK, true),
                file.bool(Settings.DEBUG, false),
                new Settings.Command(
                        file.string(Settings.Command.NAME, Settings.Command.DEFAULT_NAME),
                        file.stringList(Settings.Command.ALIASES, Settings.Command.DEFAULT_ALIASES)),
                new Settings.Performance(
                        file.decimal(Settings.Performance.VIEW_DISTANCE, 48, 8, 128),
                        file.decimal(Settings.Performance.DETAIL_DISTANCE, 24, 0, 128),
                        file.decimal(Settings.Performance.WIRE_STEP, 0.35, 0.1, 4),
                        file.decimal(Settings.Performance.BULB_SPACING, 0.5, 0.2, 4),
                        file.integer(Settings.Performance.ANIMATION_TICKS, 4, 1, 40),
                        file.integer(Settings.Performance.SWING_TICKS, 8, 2, 100),
                        file.integer(Settings.Performance.VIEWER_CHECK_TICKS, 20, 5, 200),
                        file.integer(Settings.Performance.PREVIEW_TICKS, 4, 1, 40),
                        file.integer(Settings.Performance.LIGHT_UPDATE_TICKS, 8, 2, 200)));
    }

    public @Nullable GarlandSettings garland() {
        Snapshot current = snapshot;
        return current == null ? null : current.garland();
    }

    public @NotNull Settings.Performance performance() {
        Snapshot current = snapshot;
        return current == null ? Settings.Performance.DEFAULT : current.settings().performance();
    }

    public @NotNull Component message(@NotNull String key) {
        return message(key, Map.of());
    }

    public @NotNull Component message(@NotNull String key, @NotNull Map<String, ?> placeholders) {
        Messages current = messages;
        String raw = raw(current, key).replace(COMMAND_TOKEN, command).replace(PREFIX_TOKEN, current.prefix());
        return MessageUtil.parseText(raw, placeholders);
    }

    private @NotNull String raw(@NotNull Messages current, @NotNull String key) {
        String value = current.entries().get(key);
        if (value != null) {
            return value;
        }
        if (missingKeys.add(key)) {
            Logger.warn("В " + MESSAGES_FILE + " нет ключа '" + key + "'");
        }
        return "";
    }

    public void send(@NotNull Audience audience, @NotNull String key) {
        send(audience, key, Map.of());
    }

    public void send(@NotNull Audience audience, @NotNull String key, @NotNull Map<String, ?> placeholders) {
        Messages current = messages;
        String raw = raw(current, key).replace(COMMAND_TOKEN, command);
        if (raw.isEmpty()) {
            return;
        }
        if (!(audience instanceof ConsoleCommandSender)) {
            audience.sendMessage(MessageUtil.parseText(raw.replace(PREFIX_TOKEN, current.prefix()), placeholders));
            return;
        }
        String console = HEX_TAG.matcher(raw.replace(PREFIX_TOKEN, "[" + plugin.getName() + "]")).replaceAll("");
        for (Map.Entry<String, String> symbol : CONSOLE_SYMBOLS.entrySet()) {
            console = console.replace(symbol.getKey(), symbol.getValue());
        }
        audience.sendMessage(MessageUtil.parseText(console, placeholders));
    }

    public void sendActionBar(@NotNull Audience audience, @NotNull String key) {
        sendActionBar(audience, key, Map.of());
    }

    public void sendActionBar(@NotNull Audience audience, @NotNull String key, @NotNull Map<String, ?> placeholders) {
        String raw = raw(messages, key).replace(COMMAND_TOKEN, command).replace(PREFIX_TOKEN, "");
        if (!raw.isEmpty()) {
            audience.sendActionBar(MessageUtil.parseText(raw, placeholders));
        }
    }

    static @NotNull GarlandSettings parseGarland(@NotNull ConfigFile file, @NotNull ConfigFile settings) {
        return new GarlandSettings(
                new GarlandSettings.Item(
                        file.material(join(ITEM, "material"), GarlandSettings.DEFAULT_MATERIAL),
                        file.string(join(ITEM, "name"), GarlandSettings.ID),
                        file.stringList(join(ITEM, "lore"), List.of())),
                file.block(join(BLOCKS, "wire"), Material.BLACK_CONCRETE),
                file.block(join(BLOCKS, "socket"), Material.GRAY_CANDLE),
                file.block(join(BLOCKS, "hook"), Material.GRAY_CONCRETE),
                palettes(file),
                (int) Math.round(settings.decimal(join(GARLAND, "blink-seconds"), 0.5, 0.1, 10) * TICKS_PER_SECOND),
                settings.decimal(join(LIMITS, "max-length"), 16, 1, 48),
                settings.integer(join(LIMITS, "per-player"), 100, 1, 10_000),
                new GarlandSettings.Wind(
                        Math.toRadians(settings.decimal(join(GARLAND, "wind.angle-degrees"), 6, 0, 45)),
                        (int) Math.round(settings.decimal(join(GARLAND, "wind.period-seconds"), 4, 0.5, 30)
                                * TICKS_PER_SECOND)),
                settings.bool(join(GARLAND, "night-only"), false),
                settings.sound(join(SOUNDS, "hang")),
                settings.sound(join(SOUNDS, "remove")),
                settings.sound(join(SOUNDS, "switch")));
    }

    private static @NotNull List<GarlandSettings.Palette> palettes(@NotNull ConfigFile file) {
        List<GarlandSettings.Palette> palettes = new ArrayList<>();
        for (String id : file.keys(PALETTES)) {
            String path = join(PALETTES, id);
            if (!PALETTE_ID.matcher(id).matches()) {
                file.warn(path, "id набора только из латиницы, цифр, _ и -, пропускаю");
                continue;
            }
            GarlandSettings.Palette palette = palette(file, id, path);
            if (palette != null) {
                palettes.add(palette);
            }
        }
        if (palettes.isEmpty()) {
            file.warn(PALETTES, "нет ни одного верного набора ламп, беру набор по умолчанию");
        }
        return palettes;
    }

    private static @Nullable GarlandSettings.Palette palette(@NotNull ConfigFile file, @NotNull String id,
                                                            @NotNull String path) {
        String bulbsPath = join(path, "bulbs");
        List<String> bulbs = new ArrayList<>();
        Boolean blocks = null;
        for (String raw : bulbEntries(file, bulbsPath)) {
            String head = headTexture(raw.trim());
            String entry = head != null ? head : raw.trim();
            Boolean block = head != null ? Boolean.FALSE : bulbKind(entry);
            if (block == null) {
                file.warn(bulbsPath, "\"" + entry + "\" не текстура головы, не предмет и не блок, пропускаю");
                continue;
            }
            if (blocks == null) {
                blocks = block;
            } else if (!blocks.equals(block)) {
                file.warn(bulbsPath, "\"" + entry + "\": в одном наборе только блоки или только предметы, пропускаю");
                continue;
            }
            bulbs.add(entry);
        }
        if (blocks == null) {
            file.warn(bulbsPath, "в наборе нет ни одной лампы, пропускаю набор");
            return null;
        }
        float[] size = size(file, join(path, "size"));
        return new GarlandSettings.Palette(id, file.string(join(path, "name"), id), bulbs, blocks,
                size[0], size[1], size[2],
                (float) file.decimal(join(path, "drop"), 0.2, 0, 2),
                file.integer(join(path, "light"), 0, 0, 15),
                file.bool(join(path, "socket"), true));
    }

    private static @Nullable Material bulbMaterial(@NotNull String bulb) {
        int state = bulb.indexOf(BLOCK_STATE_START);
        return Material.matchMaterial(state < 0 ? bulb : bulb.substring(0, state));
    }

    private static @NotNull List<String> bulbEntries(@NotNull ConfigFile file, @NotNull String path) {
        if (!file.isSection(path)) {
            return file.stringList(path, List.of());
        }
        List<String> entries = new ArrayList<>();
        for (String label : file.keys(path)) {
            entries.add(file.string(join(path, label), ""));
        }
        return entries;
    }

    static @Nullable String headTexture(@NotNull String raw) {
        if (GarlandSettings.TEXTURE.matcher(raw).matches()) {
            return raw;
        }
        String text = raw;
        if (!text.contains(TEXTURE_PATH)) {
            try {
                text = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        int start = text.indexOf(TEXTURE_PATH);
        if (start < 0) {
            return null;
        }
        Matcher id = GarlandSettings.TEXTURE.matcher(text.substring(start + TEXTURE_PATH.length()));
        return id.lookingAt() ? id.group() : null;
    }

    private static @Nullable Boolean bulbKind(@NotNull String entry) {
        int state = entry.indexOf(BLOCK_STATE_START);
        Material material = bulbMaterial(entry);
        if (material == null) {
            return null;
        }
        if (material.isBlock()) {
            try {
                Bukkit.createBlockData(entry.toLowerCase(Locale.ROOT));
                return true;
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        return state < 0 && material.isItem() ? false : null;
    }

    private static float @NotNull [] size(@NotNull ConfigFile file, @NotNull String path) {
        if (!file.isList(path)) {
            float single = (float) file.decimal(path, DEFAULT_SIZE, 0.01, 2);
            return new float[]{single, single, single};
        }
        List<String> raw = file.stringList(path, List.of());
        float[] size = new float[SIZE_AXES];
        for (int axis = 0; axis < SIZE_AXES; axis++) {
            Double value = axis < raw.size() ? ConfigFile.parseDecimal(raw.get(axis)) : null;
            if (value == null || value < 0.01 || value > 2) {
                file.warn(path, "ожидается [x, y, z] от 0.01 до 2, беру " + DEFAULT_SIZE);
                return new float[]{DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_SIZE};
            }
            size[axis] = value.floatValue();
        }
        return size;
    }

    static @NotNull Messages parseMessages(@NotNull ConfigFile file) {
        Map<String, String> entries = new HashMap<>();
        collectMessages(file, "", entries);
        return new Messages(file.string(MESSAGE_PREFIX, ""), entries);
    }

    private static void collectMessages(@NotNull ConfigFile file, @NotNull String path,
                                        @NotNull Map<String, String> sink) {
        for (String key : file.keys(path)) {
            String child = path.isEmpty() ? key : join(path, key);
            if (file.isSection(child)) {
                collectMessages(file, child, sink);
                continue;
            }
            sink.put(child, file.isList(child)
                    ? String.join(LINE_BREAK, file.stringList(child, List.of()))
                    : file.string(child, ""));
        }
    }

    public record Snapshot(@NotNull Settings settings, @NotNull GarlandSettings garland,
                           @NotNull Messages messages) {
    }

    public record Messages(@NotNull String prefix, @NotNull Map<String, String> entries) {

        private static final @NotNull Messages EMPTY = new Messages("", Map.of());

        public Messages {
            entries = Map.copyOf(entries);
        }
    }
}
