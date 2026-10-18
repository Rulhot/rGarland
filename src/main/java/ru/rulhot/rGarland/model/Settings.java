package ru.rulhot.rGarland.model;

import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.util.logger.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record Settings(boolean metrics, boolean updateCheck, @NotNull Command command,
                       @NotNull Performance performance) {

    public static final @NotNull String METRICS = "settings.metrics";
    public static final @NotNull String UPDATE_CHECK = "settings.update-check";
    public static final @NotNull Settings DEFAULT = new Settings(true, true, Command.DEFAULT, Performance.DEFAULT);

    public static final @NotNull String FILE = "config.yml";

    public record Command(@NotNull String name, @NotNull List<String> aliases) {

        private static final @NotNull Pattern LABEL = Pattern.compile("[a-z0-9_-]{1,32}");

        public static final @NotNull String NAME = "command.main";
        public static final @NotNull String ALIASES = "command.aliases";

        public static final @NotNull String DEFAULT_NAME = "garland";
        public static final @NotNull List<String> DEFAULT_ALIASES = List.of("garlands");
        public static final @NotNull Command DEFAULT = new Command(DEFAULT_NAME, DEFAULT_ALIASES);

        public Command {
            name = name.trim().toLowerCase(Locale.ROOT);
            if (!LABEL.matcher(name).matches()) {
                warn(NAME, "\"" + name + "\" не подходит (латиница, цифры, _ и -), беру " + DEFAULT_NAME);
                name = DEFAULT_NAME;
            }
            List<String> valid = new ArrayList<>();
            for (String alias : aliases) {
                String label = alias.trim().toLowerCase(Locale.ROOT);
                if (!LABEL.matcher(label).matches()) {
                    warn(ALIASES, "\"" + alias + "\" не подходит (латиница, цифры, _ и -), пропускаю");
                    continue;
                }
                if (!label.equals(name) && !valid.contains(label)) {
                    valid.add(label);
                }
            }
            aliases = List.copyOf(valid);
        }
    }

    public record Performance(double viewDistance, double detailDistance, double wireStep, double bulbSpacing,
                              int animationTicks, int swingTicks, int viewerCheckTicks, int previewTicks,
                              int lightUpdateTicks) {

        public static final @NotNull String VIEW_DISTANCE = "performance.view-distance";
        public static final @NotNull String DETAIL_DISTANCE = "performance.detail-distance";
        public static final @NotNull String WIRE_STEP = "performance.wire-step";
        public static final @NotNull String BULB_SPACING = "performance.bulb-spacing";
        public static final @NotNull String ANIMATION_TICKS = "performance.animation-ticks";
        public static final @NotNull String SWING_TICKS = "performance.swing-ticks";
        public static final @NotNull String VIEWER_CHECK_TICKS = "performance.viewer-check-ticks";
        public static final @NotNull String PREVIEW_TICKS = "performance.preview-ticks";
        public static final @NotNull String LIGHT_UPDATE_TICKS = "performance.light-update-ticks";
        public static final @NotNull Performance DEFAULT = new Performance(48, 24, 0.35, 0.5, 4, 8, 20, 4, 8);

        public Performance {
            if (detailDistance > viewDistance) {
                warn(DETAIL_DISTANCE, detailDistance + " больше view-distance, беру " + viewDistance);
                detailDistance = viewDistance;
            }
        }
    }

    private static void warn(@NotNull String path, @NotNull String problem) {
        Logger.warn("[" + FILE + "] " + path + ": " + problem);
    }
}
