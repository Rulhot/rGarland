package ru.rulhot.rGarland.util.logger.impl;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.util.logger.ILogger;

@RequiredArgsConstructor
public final class LegacyLogger implements ILogger {

    private final @NotNull ComponentLogger logger;

    @Override
    public void debug(@NotNull String message) {
        logger.info(Component.text("[debug] " + message));
    }

    @Override
    public void info(@NotNull String message) {
        logger.info(Component.text(message));
    }

    @Override
    public void warn(@NotNull String message) {
        logger.warn(Component.text(message));
    }

    @Override
    public void error(@NotNull String message) {
        logger.error(Component.text(message));
    }

    @Override
    public void error(@NotNull String message, @NotNull Throwable throwable) {
        logger.error(Component.text(message), throwable);
    }
}
