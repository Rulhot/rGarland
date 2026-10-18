package ru.rulhot.rGarland.model;

import net.kyori.adventure.sound.Sound;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record GarlandSettings(@NotNull Item item, @NotNull BlockData wireBlock, @NotNull BlockData socketBlock,
                              @NotNull BlockData hookBlock, @NotNull List<Palette> palettes, int blinkTicks,
                              double maxLength, int maxSegments, @NotNull Wind wind, boolean nightOnly,
                              @Nullable Sound placeSound, @Nullable Sound removeSound, @Nullable Sound switchSound) {

    public static final @NotNull String ID = "garland";
    public static final @NotNull String FILE = "items.yml";
    public static final @NotNull Material DEFAULT_MATERIAL = Material.STRING;
    public static final @NotNull Pattern TEXTURE = Pattern.compile("[0-9a-fA-F]{32,64}");
    public static final @NotNull Palette DEFAULT_PALETTE = new Palette("1", "1", List.of(
            "c0dac2501c9e756767f6943323ad5955b5a975bb09be12364ef80ea3f1ca7b06",
            "2bc65b659eafa37df6c4b11dde89073db71a5f5b22513882013e101fe466399",
            "86f901bbde8cd67520fa0c7ae461ee7ca66ce3c4c481cd5c12fb43712eea6b63",
            "b605c66347f327c5eed27672d7a0e347f4e36c006353c91b78935ba28116504"),
            false, 0.3f, 0.3f, 0.3f, 0.2f, 10, true);

    public GarlandSettings {
        palettes = palettes.isEmpty() ? List.of(DEFAULT_PALETTE) : List.copyOf(palettes);
    }

    public @NotNull Palette palette(@NotNull String id) {
        for (Palette palette : palettes) {
            if (palette.id().equals(id)) {
                return palette;
            }
        }
        return palettes.getFirst();
    }

    public @NotNull Palette nextPalette(@NotNull String id) {
        for (int i = 0; i < palettes.size(); i++) {
            if (palettes.get(i).id().equals(id)) {
                return palettes.get((i + 1) % palettes.size());
            }
        }
        return palettes.getFirst();
    }

    public record Item(@NotNull Material material, @NotNull String name, @NotNull List<String> lore) {

        public Item {
            lore = List.copyOf(lore);
        }
    }

    public record Palette(@NotNull String id, @NotNull String name, @NotNull List<String> bulbs, boolean blocks,
                          float sizeX, float sizeY, float sizeZ, float drop, int light, boolean socket) {

        public Palette {
            bulbs = List.copyOf(bulbs);
        }
    }

    public record Wind(double angle, int periodTicks) {
    }

    public enum Blink {
        SHIFT, CHASE, WAVE, TWINKLE, BREATHE, STEADY;

        public @NotNull String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public @NotNull Blink next() {
            Blink[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public static @NotNull Blink parse(@NotNull String raw) {
            for (Blink blink : values()) {
                if (blink.id().equals(raw)) {
                    return blink;
                }
            }
            return SHIFT;
        }
    }
}
