package ru.rulhot.rGarland.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.util.SchedulerUtil;
import ru.rulhot.rGarland.util.logger.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public final class ReloadService {

    private final @NotNull SchedulerUtil scheduler;
    private final @NotNull ConfigManager config;
    private final @NotNull GarlandService garlands;

    private final @NotNull AtomicBoolean reloading = new AtomicBoolean();

    public void start() {
        load().thenAccept(result -> {
            if (result instanceof Result.Failed) {
                Logger.error("Гирлянды не запущены: ошибка в конфиге. Исправь её и перезагрузи конфиг");
            }
        });
    }

    public @NotNull CompletableFuture<Result> reload() {
        return load();
    }

    private @NotNull CompletableFuture<Result> load() {
        if (!reloading.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new Result.Busy());
        }
        CompletableFuture<Result> result = new CompletableFuture<>();
        scheduler.runAsync(() -> {
            ConfigManager.Snapshot snapshot = read();
            if (snapshot == null) {
                finish(result, new Result.Failed());
                return;
            }
            scheduler.runGlobal(() -> {
                try {
                    config.apply(snapshot);
                    if (garlands.isLoaded()) {
                        garlands.rebuild();
                    } else {
                        garlands.restore();
                    }
                    finish(result, new Result.Success());
                } catch (RuntimeException exception) {
                    Logger.error("Не удалось применить конфигурацию", exception);
                    finish(result, new Result.Failed());
                }
            });
        });
        return result;
    }

    private @Nullable ConfigManager.Snapshot read() {
        try {
            return config.read();
        } catch (RuntimeException exception) {
            Logger.error("Не удалось загрузить конфигурацию, оставляю прежнюю", exception);
            return null;
        }
    }

    private void finish(@NotNull CompletableFuture<Result> future, @NotNull Result result) {
        reloading.set(false);
        future.complete(result);
    }

    public sealed interface Result {

        record Success() implements Result {
        }

        record Busy() implements Result {
        }

        record Failed() implements Result {
        }
    }
}
