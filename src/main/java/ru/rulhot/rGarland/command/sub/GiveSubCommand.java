package ru.rulhot.rGarland.command.sub;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.command.SubCommand;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.service.ItemService;
import ru.rulhot.rGarland.service.PreviewService;
import ru.rulhot.rGarland.util.SchedulerUtil;

import java.util.Map;

@RequiredArgsConstructor
public final class GiveSubCommand implements SubCommand {

    private static final @NotNull String NAME = "give";
    private static final @NotNull String PLAYER = "player";
    private static final @NotNull String AMOUNT = "amount";
    private static final int MAX_AMOUNT = 64;

    private final @NotNull ConfigManager config;
    private final @NotNull ItemService items;
    private final @NotNull SchedulerUtil scheduler;
    private final @NotNull PreviewService previews;

    @Override
    public @NotNull LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal(NAME)
                .executes(this::usage)
                .then(Commands.argument(PLAYER, StringArgumentType.word())
                        .suggests(SubCommand.suggest(context -> Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName).toList()))
                        .executes(context -> give(context, 1))
                        .then(Commands.argument(AMOUNT, IntegerArgumentType.integer(1, MAX_AMOUNT))
                                .executes(context -> give(context,
                                        IntegerArgumentType.getInteger(context, AMOUNT)))));
    }

    private int usage(@NotNull CommandContext<CommandSourceStack> context) {
        config.send(context.getSource().getSender(), "give.usage");
        return Command.SINGLE_SUCCESS;
    }

    private int give(@NotNull CommandContext<CommandSourceStack> context, int amount) {
        CommandSender sender = context.getSource().getSender();
        GarlandSettings settings = config.garland();
        if (settings == null) {
            config.send(sender, "not-ready");
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(context, PLAYER);
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            config.send(sender, "player-not-found", Map.of("player", name));
            return Command.SINGLE_SUCCESS;
        }
        scheduler.runFor(target, () -> {
            ItemStack item = items.create(settings.item(), amount);
            target.getInventory().addItem(item).values()
                    .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            config.send(target, "give.target", Map.of("item", item.effectiveName(), "amount", amount));
            previews.watch(target);
        });
        config.send(sender, "give.sender", Map.of("player", target.getName(), "amount", amount));
        return Command.SINGLE_SUCCESS;
    }
}
