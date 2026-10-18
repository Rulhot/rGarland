package ru.rulhot.rGarland.hook;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public interface ProtectionHook {

    @NotNull ProtectionHook NONE = (player, at) -> true;

    boolean canBuild(@NotNull Player player, @NotNull Location at);
}
