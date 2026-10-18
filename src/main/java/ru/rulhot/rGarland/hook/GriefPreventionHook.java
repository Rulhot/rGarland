package ru.rulhot.rGarland.hook;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class GriefPreventionHook implements ProtectionHook {

    private final @NotNull GriefPrevention plugin = GriefPrevention.instance;

    @Override
    public boolean canBuild(@NotNull Player player, @NotNull Location at) {
        Claim claim = plugin.dataStore.getClaimAt(at, false, null);
        return claim == null || claim.checkPermission(player, ClaimPermission.Build, null) == null;
    }
}
