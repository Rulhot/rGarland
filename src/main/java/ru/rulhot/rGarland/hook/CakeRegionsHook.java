package ru.rulhot.rGarland.hook;

import dev.cakestudio.cakeregionsapi.CakeRegionsAPI;
import dev.cakestudio.cakeregionsapi.api.IRegionsAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class CakeRegionsHook implements ProtectionHook {

    private final @NotNull IRegionsAPI api = CakeRegionsAPI.getApi();

    @Override
    public boolean canBuild(@NotNull Player player, @NotNull Location at) {
        return api.canBuild(player, at);
    }
}
