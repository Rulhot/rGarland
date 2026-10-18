package ru.rulhot.rGarland.hook;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class WorldGuardHook implements ProtectionHook {

    private final @NotNull WorldGuardPlugin plugin = WorldGuardPlugin.inst();
    private final @NotNull WorldGuardPlatform platform = WorldGuard.getInstance().getPlatform();

    @Override
    public boolean canBuild(@NotNull Player player, @NotNull Location at) {
        LocalPlayer local = plugin.wrapPlayer(player);
        if (platform.getSessionManager().hasBypass(local, BukkitAdapter.adapt(at.getWorld()))) {
            return true;
        }
        return platform.getRegionContainer().createQuery().testBuild(BukkitAdapter.adapt(at), local);
    }
}
