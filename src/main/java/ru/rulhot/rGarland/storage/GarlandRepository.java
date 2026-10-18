package ru.rulhot.rGarland.storage;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.manager.ConfigFile;
import ru.rulhot.rGarland.model.GarlandSpan;
import ru.rulhot.rGarland.util.SchedulerUtil;
import ru.rulhot.rGarland.util.logger.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class GarlandRepository {

    private static final @NotNull String FILE = "data/garlands.yml";
    private static final @NotNull String SPANS = "spans";
    private static final @NotNull String SEPARATOR = ";";
    private static final int OLD_PARTS = 8;
    private static final int STYLED_PARTS = 10;
    private static final int PARTS = 11;
    private static final double PRECISION = 10_000;

    private final @NotNull SchedulerUtil scheduler;
    private final @NotNull Path file;
    private final @NotNull Object writeLock = new Object();
    private final @NotNull AtomicLong version = new AtomicLong();

    public GarlandRepository(@NotNull SchedulerUtil scheduler, @NotNull Path dataFolder) {
        this.scheduler = scheduler;
        this.file = dataFolder.resolve(FILE);
    }

    public @NotNull CompletableFuture<@Nullable List<GarlandSpan>> load() {
        CompletableFuture<@Nullable List<GarlandSpan>> result = new CompletableFuture<>();
        scheduler.runAsync(() -> result.complete(read()));
        return result;
    }

    public void save(@NotNull List<GarlandSpan> spans) {
        long current = version.incrementAndGet();
        scheduler.runAsync(() -> write(spans, current));
    }

    public void saveNow(@NotNull List<GarlandSpan> spans) {
        write(spans, version.incrementAndGet());
    }

    private @Nullable List<GarlandSpan> read() {
        if (Files.notExists(file)) {
            return List.of();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException exception) {
            Logger.error("Не удалось прочитать " + FILE + ", гирлянды не загружены и не сохраняются до рестарта",
                    exception);
            return null;
        }
        List<GarlandSpan> spans = new ArrayList<>();
        for (String entry : yaml.getStringList(SPANS)) {
            GarlandSpan span = parse(entry);
            if (span == null) {
                Logger.warn("Неверная запись гирлянды в " + FILE + ": " + entry + ", пропускаю");
                continue;
            }
            spans.add(span);
        }
        return spans;
    }

    private void write(@NotNull List<GarlandSpan> spans, long expected) {
        synchronized (writeLock) {
            if (expected != version.get()) {
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set(SPANS, spans.stream().map(GarlandRepository::format).toList());
            try {
                Files.createDirectories(file.getParent());
                ConfigFile.writeAtomically(file, yaml.saveToString());
            } catch (IOException exception) {
                Logger.error("Не удалось сохранить гирлянды в " + FILE, exception);
            }
        }
    }

    static @Nullable GarlandSpan parse(@NotNull String entry) {
        String[] parts = entry.split(SEPARATOR);
        if (parts.length != PARTS && parts.length != STYLED_PARTS && parts.length != OLD_PARTS) {
            return null;
        }
        String palette = parts.length >= STYLED_PARTS ? parts[8] : "";
        String blink = parts.length >= STYLED_PARTS ? parts[9] : "";
        String ownerName = parts.length == PARTS ? parts[10] : "";
        try {
            return new GarlandSpan(UUID.fromString(parts[0]), parts[1],
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]), Double.parseDouble(parts[6]), Double.parseDouble(parts[7]),
                    palette, blink, ownerName);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static @NotNull String format(@NotNull GarlandSpan span) {
        return String.join(SEPARATOR, span.owner().toString(), span.world(),
                number(span.fromX()), number(span.fromY()), number(span.fromZ()),
                number(span.toX()), number(span.toY()), number(span.toZ()), span.palette(), span.blink(),
                span.ownerName());
    }

    private static @NotNull String number(double value) {
        return Double.toString(Math.round(value * PRECISION) / PRECISION);
    }
}
