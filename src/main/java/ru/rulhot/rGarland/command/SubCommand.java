package ru.rulhot.rGarland.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.service.GarlandService;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public interface SubCommand {

    @NotNull LiteralArgumentBuilder<CommandSourceStack> node();

    static @NotNull SuggestionProvider<CommandSourceStack> suggest(
            @NotNull Function<CommandContext<CommandSourceStack>, Collection<String>> options) {
        return (context, builder) -> {
            String prefix = builder.getRemainingLowerCase();
            for (String option : options.apply(context)) {
                if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    builder.suggest(option);
                }
            }
            return builder.buildFuture();
        };
    }

    static @NotNull SuggestionProvider<CommandSourceStack> owners(@NotNull GarlandService garlands) {
        return suggest(context -> garlands.owners().entrySet().stream()
                .map(entry -> name(entry.getKey(), entry.getValue())).toList());
    }

    static @Nullable UUID owner(@NotNull GarlandService garlands, @NotNull String name) {
        for (Map.Entry<UUID, String> entry : garlands.owners().entrySet()) {
            if (name(entry.getKey(), entry.getValue()).equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    static @NotNull String name(@NotNull GarlandService garlands, @NotNull UUID owner) {
        return name(owner, garlands.owners().getOrDefault(owner, ""));
    }

    private static @NotNull String name(@NotNull UUID owner, @NotNull String stored) {
        if (!stored.isEmpty()) {
            return stored;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            return online.getName();
        }
        PlayerProfile profile = Bukkit.createProfile(owner);
        profile.completeFromCache(false, false);
        String name = profile.getName();
        return name == null ? owner.toString() : name;
    }
}
