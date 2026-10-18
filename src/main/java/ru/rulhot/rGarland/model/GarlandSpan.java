package ru.rulhot.rGarland.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record GarlandSpan(@NotNull UUID owner, @NotNull String world, double fromX, double fromY, double fromZ,
                          double toX, double toY, double toZ, @NotNull String palette, @NotNull String blink,
                          @NotNull String ownerName) {

    public static @NotNull GarlandSpan of(@NotNull UUID owner, @NotNull String ownerName, @NotNull Location from,
                                          @NotNull Location to, @NotNull String palette, @NotNull String blink) {
        return new GarlandSpan(owner, from.getWorld().getName(), from.getX(), from.getY(), from.getZ(),
                to.getX(), to.getY(), to.getZ(), palette, blink, ownerName);
    }

    public @NotNull GarlandSpan styled(@NotNull String newPalette, @NotNull String newBlink) {
        return new GarlandSpan(owner, world, fromX, fromY, fromZ, toX, toY, toZ, newPalette, newBlink, ownerName);
    }

    public @NotNull Location from(@NotNull World world) {
        return new Location(world, fromX, fromY, fromZ);
    }

    public @NotNull Location to(@NotNull World world) {
        return new Location(world, toX, toY, toZ);
    }
}
