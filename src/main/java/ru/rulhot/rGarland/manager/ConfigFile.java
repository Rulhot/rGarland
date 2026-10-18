package ru.rulhot.rGarland.manager;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.util.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConfigFile {

    private static final @NotNull String VERSION_KEY = "config-version";
    private static final @NotNull String PATH_SEPARATOR = ".";
    private static final @NotNull String TEMPORARY_SUFFIX = ".tmp";
    private static final @NotNull String BACKUP_SUFFIX = ".bak";
    private static final @NotNull String SOUND_KEY = "key";
    private static final @NotNull String SOUND_VOLUME = "volume";
    private static final @NotNull String SOUND_PITCH = "pitch";
    private static final float MIN_VOLUME = 0F;
    private static final float MAX_VOLUME = 1F;
    private static final float MIN_PITCH = 0.5F;
    private static final float MAX_PITCH = 2F;

    private final @NotNull String name;
    private final @NotNull YamlConfiguration yaml;

    private ConfigFile(@NotNull String name, @NotNull YamlConfiguration yaml) {
        this.name = name;
        this.yaml = yaml;
    }

    public static @Nullable ConfigFile load(@NotNull JavaPlugin plugin, @NotNull String name,
                                            @NotNull Set<String> preserved) {
        String defaults = bundled(plugin, name);
        if (defaults == null) {
            return null;
        }
        return load(plugin.getDataFolder().toPath().resolve(name), name, defaults, preserved);
    }

    static @Nullable ConfigFile load(@NotNull Path file, @NotNull String name, @NotNull String defaultsText,
                                     @NotNull Set<String> preserved) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, defaultsText, StandardCharsets.UTF_8);
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            defaults.loadFromString(defaultsText);
            YamlConfiguration current = new YamlConfiguration();
            current.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            if (isOutdated(current, defaults, preserved)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + BACKUP_SUFFIX),
                        StandardCopyOption.REPLACE_EXISTING);
                writeAtomically(file, merge(current, defaults, preserved).saveToString());
                Logger.info("Обновлён " + name + ": добавлены новые ключи, копия старого файла в " + file.getFileName()
                        + BACKUP_SUFFIX);
                current.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            }
            return new ConfigFile(name, current);
        } catch (InvalidConfigurationException exception) {
            Logger.error("Ошибка в " + name + ", исправь файл и перезагрузи конфиг: " + exception.getMessage());
            return null;
        } catch (IOException exception) {
            Logger.error("Не удалось прочитать или записать " + name, exception);
            return null;
        }
    }

    public static void writeAtomically(@NotNull Path file, @NotNull String text) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + TEMPORARY_SUFFIX);
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static @Nullable String bundled(@NotNull JavaPlugin plugin, @NotNull String name) {
        try (InputStream stream = plugin.getResource(name)) {
            if (stream == null) {
                Logger.error("В jar плагина нет файла " + name + ", пересобери плагин");
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            Logger.error("Не удалось прочитать " + name + " из jar плагина", exception);
            return null;
        }
    }

    public @NotNull Set<String> keys(@NotNull String path) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        return section == null ? Set.of() : section.getKeys(false);
    }

    public boolean isSection(@NotNull String path) {
        return yaml.isConfigurationSection(path);
    }

    public @NotNull String string(@NotNull String path, @NotNull String fallback) {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof ConfigurationSection || raw instanceof List<?>) {
            warn(path, "ожидается строка, беру \"" + fallback + "\"");
            return fallback;
        }
        return String.valueOf(raw);
    }

    public boolean bool(@NotNull String path, boolean fallback) {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        warn(path, "ожидается true или false, беру " + fallback);
        return fallback;
    }

    public boolean isList(@NotNull String path) {
        return yaml.isList(path);
    }

    public double decimal(@NotNull String path, double fallback, double min, double max) {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        Double value = parseDecimal(raw);
        if (value == null) {
            warn(path, "неверное число \"" + raw + "\", беру " + fallback);
            return fallback;
        }
        if (value >= min && value <= max) {
            return value;
        }
        warn(path, value + " вне диапазона " + min + ".." + max + ", беру " + fallback);
        return fallback;
    }

    public int integer(@NotNull String path, int fallback, int min, int max) {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        Double value = parseDecimal(raw);
        if (value == null || value != Math.rint(value)) {
            warn(path, "ожидается целое число, а не \"" + raw + "\", беру " + fallback);
            return fallback;
        }
        if (value >= min && value <= max) {
            return value.intValue();
        }
        warn(path, value.intValue() + " вне диапазона " + min + ".." + max + ", беру " + fallback);
        return fallback;
    }

    public @NotNull List<String> stringList(@NotNull String path, @NotNull List<String> fallback) {
        if (!yaml.isList(path)) {
            if (yaml.contains(path)) {
                warn(path, "ожидается список, беру " + fallback);
            }
            return fallback;
        }
        return List.copyOf(yaml.getStringList(path));
    }

    public @NotNull Material material(@NotNull String path, @NotNull Material fallback) {
        String raw = string(path, fallback.name()).trim();
        Material material = Material.matchMaterial(raw);
        if (material != null && material.isItem()) {
            return material;
        }
        warn(path, "неизвестный предмет \"" + raw + "\", беру " + fallback.name());
        return fallback;
    }

    public @NotNull BlockData block(@NotNull String path, @NotNull Material fallback) {
        String raw = string(path, fallback.name()).trim();
        Material material = Material.matchMaterial(raw);
        if (material != null && material.isBlock()) {
            return material.createBlockData();
        }
        warn(path, "неизвестный блок \"" + raw + "\", беру " + fallback.name());
        return fallback.createBlockData();
    }

    public @Nullable Sound sound(@NotNull String path) {
        String raw = string(join(path, SOUND_KEY), "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        float volume = (float) decimal(join(path, SOUND_VOLUME), MAX_VOLUME, MIN_VOLUME, MAX_VOLUME);
        float pitch = (float) decimal(join(path, SOUND_PITCH), 1D, MIN_PITCH, MAX_PITCH);
        try {
            return Sound.sound(Key.key(raw.toLowerCase(Locale.ROOT)), Sound.Source.MASTER, volume, pitch);
        } catch (InvalidKeyException exception) {
            warn(join(path, SOUND_KEY), "неверный ключ звука \"" + raw + "\", звук отключён");
            return null;
        }
    }

    public void warn(@NotNull String path, @NotNull String problem) {
        Logger.warn("[" + name + "] " + path + ": " + problem);
    }

    public static @NotNull String join(@NotNull String parent, @NotNull String child) {
        return parent + PATH_SEPARATOR + child;
    }

    static @Nullable Double parseDecimal(@NotNull Object raw) {
        if (raw instanceof Number number) {
            return Double.isFinite(number.doubleValue()) ? number.doubleValue() : null;
        }
        if (!(raw instanceof String text)) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isOutdated(@NotNull YamlConfiguration current, @NotNull YamlConfiguration defaults,
                                      @NotNull Set<String> preserved) {
        if (current.getInt(VERSION_KEY, 0) < defaults.getInt(VERSION_KEY, 0)) {
            return true;
        }
        for (String key : defaults.getKeys(true)) {
            if (!isInsidePreserved(key, preserved) && !current.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull YamlConfiguration merge(@NotNull YamlConfiguration current,
                                                    @NotNull YamlConfiguration defaults,
                                                    @NotNull Set<String> preserved) {
        YamlConfiguration merged = new YamlConfiguration();
        merged.options().parseComments(true);
        merged.options().width(Integer.MAX_VALUE);
        merged.options().setHeader(defaults.options().getHeader());
        for (String key : defaults.getKeys(true)) {
            if (key.equals(VERSION_KEY) || isInsidePreserved(key, preserved)) {
                continue;
            }
            if (preserved.contains(key)) {
                copyPreserved(current, defaults, merged, key);
            } else if (defaults.isConfigurationSection(key)) {
                merged.createSection(key);
            } else {
                boolean userValue = current.contains(key) && !current.isConfigurationSection(key);
                merged.set(key, userValue ? current.get(key) : defaults.get(key));
            }
            copyComments(defaults, merged, key);
        }
        merged.set(VERSION_KEY, defaults.getInt(VERSION_KEY, 0));
        copyComments(defaults, merged, VERSION_KEY);
        return merged;
    }

    private static boolean isInsidePreserved(@NotNull String key, @NotNull Set<String> preserved) {
        for (String root : preserved) {
            if (key.startsWith(root + PATH_SEPARATOR)) {
                return true;
            }
        }
        return false;
    }

    private static void copyPreserved(@NotNull YamlConfiguration current, @NotNull YamlConfiguration defaults,
                                      @NotNull YamlConfiguration merged, @NotNull String key) {
        YamlConfiguration source = current.contains(key) ? current : defaults;
        ConfigurationSection section = source.getConfigurationSection(key);
        if (section == null) {
            merged.set(key, source.get(key));
            return;
        }
        copySection(section, merged.createSection(key));
    }

    private static void copySection(@NotNull ConfigurationSection from, @NotNull ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            ConfigurationSection child = from.getConfigurationSection(key);
            if (child != null) {
                copySection(child, to.createSection(key));
            } else {
                to.set(key, from.get(key));
            }
            to.setComments(key, from.getComments(key));
            to.setInlineComments(key, from.getInlineComments(key));
        }
    }

    private static void copyComments(@NotNull YamlConfiguration from, @NotNull YamlConfiguration to,
                                     @NotNull String key) {
        to.setComments(key, from.getComments(key));
        to.setInlineComments(key, from.getInlineComments(key));
    }
}
