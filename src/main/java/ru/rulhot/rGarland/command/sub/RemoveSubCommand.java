package ru.rulhot.rGarland.command.sub;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.command.SubCommand;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.model.GarlandSpan;
import ru.rulhot.rGarland.service.GarlandService;
import ru.rulhot.rGarland.util.logger.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@RequiredArgsConstructor
public final class RemoveSubCommand implements SubCommand {

    private static final @NotNull String NAME = "remove";
    private static final @NotNull String PLAYER = "player";
    private static final @NotNull String NUMBER = "number";

    private final @NotNull ConfigManager config;
    private final @NotNull GarlandService garlands;

    @Override
    public @NotNull LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal(NAME)
                .executes(this::usage)
                .then(Commands.argument(PLAYER, StringArgumentType.word())
                        .suggests(SubCommand.owners(garlands))
                        .executes(this::usage)
                        .then(Commands.argument(NUMBER, IntegerArgumentType.integer(1))
                                .suggests(SubCommand.suggest(context -> {
                                    UUID owner = SubCommand.owner(garlands,
                                            StringArgumentType.getString(context, PLAYER));
                                    int count = owner == null ? 0 : garlands.spans(owner).size();
                                    return IntStream.rangeClosed(1, count).mapToObj(String::valueOf).toList();
                                }))
                                .executes(this::remove)));
    }

    private int usage(@NotNull CommandContext<CommandSourceStack> context) {
        config.send(context.getSource().getSender(), "remove.usage");
        return Command.SINGLE_SUCCESS;
    }

    private int remove(@NotNull CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!garlands.isLoaded()) {
            config.send(sender, "not-ready");
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(context, PLAYER);
        UUID owner = SubCommand.owner(garlands, name);
        List<GarlandSpan> spans = owner == null ? List.of() : garlands.spans(owner);
        if (spans.isEmpty()) {
            config.send(sender, "list.empty", Map.of("player", name));
            return Command.SINGLE_SUCCESS;
        }
        int number = IntegerArgumentType.getInteger(context, NUMBER);
        if (number > spans.size()) {
            config.send(sender, "invalid-number", Map.of("value", number, "max", spans.size()));
            return Command.SINGLE_SUCCESS;
        }
        GarlandSpan span = spans.get(number - 1);
        String ownerName = SubCommand.name(garlands, owner);
        if (!garlands.remove(span)) {
            config.send(sender, "remove.gone");
            return Command.SINGLE_SUCCESS;
        }
        Logger.info(sender.getName() + " снял командой гирлянду " + number + " игрока " + ownerName + " в мире "
                + span.world());
        config.send(sender, "remove.done", Map.of("player", ownerName, "number", number));
        return Command.SINGLE_SUCCESS;
    }
}
