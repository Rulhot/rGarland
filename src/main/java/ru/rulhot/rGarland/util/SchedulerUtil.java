package ru.rulhot.rGarland.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.util.logger.Logger;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@RequiredArgsConstructor
public final class SchedulerUtil {

    private final @NotNull Plugin plugin;

    public record Task(@NotNull ScheduledTask handle) {

        public void cancel() {
            handle.cancel();
        }
    }

    public void runAsync(@NotNull Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, handle -> guarded(task));
    }

    public void runAsyncLater(@NotNull Runnable task, long delayMillis) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, handle -> guarded(task), Math.max(1L, delayMillis),
                TimeUnit.MILLISECONDS);
    }

    public void runGlobal(@NotNull Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> guarded(task));
    }

    public void runFor(@NotNull Entity entity, @NotNull Runnable task) {
        entity.getScheduler().run(plugin, handle -> guarded(task), null);
    }

    public void runFor(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        if (entity.getScheduler().run(plugin, handle -> guarded(task), () -> guarded(retired)) == null) {
            guarded(retired);
        }
    }

    public @Nullable Task runForWhile(@NotNull Entity entity, @NotNull BooleanSupplier step,
                                      @NotNull Runnable finished, long delayTicks, long periodTicks) {
        ScheduledTask handle = entity.getScheduler().runAtFixedRate(plugin, task -> {
            if (keepRunning(step)) {
                return;
            }
            task.cancel();
            guarded(finished);
        }, () -> guarded(finished), ticks(delayTicks), ticks(periodTicks));
        if (handle == null) {
            guarded(finished);
            return null;
        }
        return new Task(handle);
    }

    public void runAt(@NotNull Location location, @NotNull Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, () -> guarded(task));
    }

    public void runAtWhile(@NotNull Location location, @NotNull BooleanSupplier step, @NotNull Runnable finished,
                           long delayTicks, long periodTicks) {
        Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, task -> {
            if (keepRunning(step)) {
                return;
            }
            task.cancel();
            guarded(finished);
        }, ticks(delayTicks), ticks(periodTicks));
    }

    private static long ticks(long value) {
        return Math.max(1L, value);
    }

    private static boolean keepRunning(@NotNull BooleanSupplier step) {
        try {
            return step.getAsBoolean();
        } catch (RuntimeException exception) {
            Logger.error("Ошибка в задаче планировщика, останавливаю её", exception);
            return false;
        }
    }

    private static void guarded(@NotNull Runnable task) {
        try {
            task.run();
        } catch (RuntimeException exception) {
            Logger.error("Ошибка в задаче планировщика", exception);
        }
    }
}
