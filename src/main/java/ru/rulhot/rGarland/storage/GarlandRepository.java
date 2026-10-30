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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class GarlandRepository {

    private static final @NotNull String FILE = "data/garlands.yml";
    private static final @NotNull String SPANS = "spans";
    private static final @NotNull String SEPARATOR = ";";
    private static final @NotNull String REFUNDABLE = "1";
    private static final @NotNull String NOT_REFUNDABLE = "0";
    private static final int OLD_PARTS = 8;
    private static final int STYLED_PARTS = 10;
    private static final int NAMED_PARTS = 11;
    private static final int PARTS = 12;
    private static final int TEXT_PARTS = 13;
    private static final double PRECISION = 10_000;
    private static final long SAVE_DELAY_MILLIS = 1_000;
    private static final int FIRST_COORDINATE = 2;
    private static final int COORDINATES = 6;
    private static final double MAX_COORDINATE = 30_000_000;
    private static final double MAX_LENGTH = 64;

    private final @NotNull SchedulerUtil scheduler;
    private final @NotNull Path file;
    private final @NotNull Object writeLock = new Object();
    private final @NotNull AtomicLong version = new AtomicLong();
    private final @NotNull AtomicReference<Pending> pending = new AtomicReference<>();
    private final @NotNull AtomicBoolean scheduled = new AtomicBoolean();

    private volatile @NotNull List<Object> unparsed = List.of();

    private record Pending(@NotNull List<GarlandSpan> spans, long version) {
    }

    public GarlandRepository(@NotNull SchedulerUtil scheduler, @NotNull Path dataFolder) {
        this.scheduler = scheduler;
        this.file = dataFolder.resolve(FILE);
    }

    public @NotNull CompletableFuture<@Nullable List<GarlandSpan>> load() {
        CompletableFuture<@Nullable List<GarlandSpan>> result = new CompletableFuture<>();
        scheduler.runAsync(() -> {
            try {
                result.complete(read());
            } catch (RuntimeException exception) {
                Logger.error("Не удалось загрузить " + FILE + ", гирлянды не загружены", exception);
                result.complete(null);
            }
        });
        return result;
    }

    public void save(@NotNull List<GarlandSpan> spans) {
        pending.set(new Pending(spans, version.incrementAndGet()));
        if (scheduled.compareAndSet(false, true)) {
            scheduler.runAsyncLater(this::flush, SAVE_DELAY_MILLIS);
        }
    }

    private void flush() {
        scheduled.set(false);
        Pending latest = pending.getAndSet(null);
        if (latest != null) {
            write(latest.spans(), latest.version());
        }
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
            Logger.error("Не удалось прочитать " + FILE + ", гирлянды не загружены, вешать и снимать их нельзя, "
                    + "пока файл не исправлен и конфиг не перезагружен", exception);
            return null;
        }
        List<GarlandSpan> spans = new ArrayList<>();
        List<Object> broken = new ArrayList<>();
        List<?> entries = yaml.getList(SPANS, List.of());
        for (Object entry : entries) {
            GarlandSpan span = entry instanceof String text ? parse(text) : null;
            if (span == null) {
                Logger.warn("Неверная запись гирлянды в " + FILE + ": " + entry + ", не вешаю, но оставляю в файле");
                broken.add(entry);
                continue;
            }
            spans.add(span);
        }
        unparsed = List.copyOf(broken);
        return spans;
    }

    private void write(@NotNull List<GarlandSpan> spans, long expected) {
        synchronized (writeLock) {
            if (expected != version.get()) {
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set(SPANS, Stream.concat(spans.stream().<Object>map(GarlandRepository::format), unparsed.stream())
                    .toList());
            try {
                Files.createDirectories(file.getParent());
                ConfigFile.writeAtomically(file, yaml.saveToString());
            } catch (IOException exception) {
                Logger.error("Не удалось сохранить гирлянды в " + FILE, exception);
            }
        }
    }

    static @Nullable GarlandSpan parse(@NotNull String entry) {
        String[] parts = entry.split(SEPARATOR, -1);
        if (parts.length != TEXT_PARTS && parts.length != PARTS && parts.length != NAMED_PARTS
                && parts.length != STYLED_PARTS && parts.length != OLD_PARTS) {
            return null;
        }
        String palette = parts.length >= STYLED_PARTS ? parts[8] : "";
        String blink = parts.length >= STYLED_PARTS ? parts[9] : "";
        String ownerName = parts.length >= NAMED_PARTS ? parts[10] : "";
        if (parts.length >= PARTS && !REFUNDABLE.equals(parts[11]) && !NOT_REFUNDABLE.equals(parts[11])) {
            return null;
        }
        boolean refundable = parts.length < PARTS || REFUNDABLE.equals(parts[11]);
        double[] coordinates = new double[COORDINATES];
        try {
            for (int i = 0; i < COORDINATES; i++) {
                coordinates[i] = Double.parseDouble(parts[FIRST_COORDINATE + i]);
                if (!Double.isFinite(coordinates[i]) || Math.abs(coordinates[i]) > MAX_COORDINATE) {
                    return null;
                }
            }
            GarlandSpan span = new GarlandSpan(UUID.fromString(parts[0]), parts[1],
                    coordinates[0], coordinates[1], coordinates[2], coordinates[3], coordinates[4], coordinates[5],
                    palette, blink, ownerName, refundable);
            return span.length() <= MAX_LENGTH ? span : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static @NotNull String format(@NotNull GarlandSpan span) {
        return String.join(SEPARATOR, span.owner().toString(), span.world(),
                number(span.fromX()), number(span.fromY()), number(span.fromZ()),
                number(span.toX()), number(span.toY()), number(span.toZ()), span.palette(), span.blink(),
                span.ownerName(), span.refundable() ? REFUNDABLE : NOT_REFUNDABLE);
    }

    private static @NotNull String number(double value) {
        return Double.toString(Math.round(value * PRECISION) / PRECISION);
    }
}
