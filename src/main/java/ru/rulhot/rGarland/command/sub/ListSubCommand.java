package ru.rulhot.rGarland.command.sub;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.rulhot.rGarland.command.SubCommand;
import ru.rulhot.rGarland.command.GarlandCommand;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.model.GarlandSpan;
import ru.rulhot.rGarland.service.GarlandService;
import ru.rulhot.rGarland.util.MessageUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public final class ListSubCommand implements SubCommand {

    private static final @NotNull String NAME = "list";
    private static final @NotNull String PLAYER = "player";
    private static final @NotNull String PAGE = "page";
    private static final int PAGE_SIZE = 10;
    private static final double HALF = 0.5;
    private static final @NotNull ClickCallback.Options CLICK = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .build();

    private final @NotNull ConfigManager config;
    private final @NotNull GarlandService garlands;

    @Override
    public @NotNull LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal(NAME)
                .executes(context -> {
                    config.send(context.getSource().getSender(), "list.usage");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument(PLAYER, StringArgumentType.word())
                        .suggests(SubCommand.owners(garlands))
                        .executes(context -> list(context, 1))
                        .then(Commands.argument(PAGE, IntegerArgumentType.integer(1))
                                .executes(context -> list(context, IntegerArgumentType.getInteger(context, PAGE)))));
    }

    private int list(@NotNull CommandContext<CommandSourceStack> context, int page) {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, PLAYER);
        UUID owner = SubCommand.owner(garlands, name);
        if (owner == null) {
            config.send(sender, "list.empty", Map.of("player", name));
            return Command.SINGLE_SUCCESS;
        }
        show(sender, owner, page);
        return Command.SINGLE_SUCCESS;
    }

    private void show(@NotNull CommandSender sender, @NotNull UUID owner, int requested) {
        GarlandSettings settings = config.garland();
        if (settings == null) {
            config.send(sender, "not-ready");
            return;
        }
        List<GarlandSpan> spans = garlands.spans(owner);
        String player = SubCommand.name(garlands, owner);
        if (spans.isEmpty()) {
            config.send(sender, "list.empty", Map.of("player", player));
            return;
        }
        int pages = (spans.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = Math.clamp(requested, 1, pages);
        config.send(sender, "list.header", Map.of("player", player, "count", spans.size()));
        int first = (page - 1) * PAGE_SIZE;
        for (int index = first; index < Math.min(first + PAGE_SIZE, spans.size()); index++) {
            line(sender, settings, spans.get(index), index + 1);
        }
        if (pages > 1) {
            sender.sendMessage(config.message("list.footer", Map.of(
                    "previous", arrow(owner, page - 1, page > 1, "list.previous"),
                    "next", arrow(owner, page + 1, page < pages, "list.next"),
                    "page", page,
                    "pages", pages)));
        }
    }

    private void line(@NotNull CommandSender sender, @NotNull GarlandSettings settings, @NotNull GarlandSpan span,
                      int number) {
        Map<String, Object> values = new HashMap<>();
        values.put("number", number);
        values.put("name", MessageUtil.parseText(settings.palette(span.palette()).name(), Map.of()));
        values.put("world", span.world());
        values.put("x", (int) Math.floor((span.fromX() + span.toX()) * HALF));
        values.put("y", (int) Math.floor((span.fromY() + span.toY()) * HALF));
        values.put("z", (int) Math.floor((span.fromZ() + span.toZ()) * HALF));
        World world = Bukkit.getWorld(span.world());
        if (world == null || !(sender instanceof Player)) {
            config.send(sender, "list.line", values);
            return;
        }
        Location target = span.from(world).add(span.to(world)).multiply(HALF);
        sender.sendMessage(config.message("list.line", values)
                .hoverEvent(HoverEvent.showText(config.message("list.hover")))
                .clickEvent(ClickEvent.callback(audience -> teleport(audience, target), CLICK)));
    }

    private @NotNull Component arrow(@NotNull UUID owner, int page, boolean active, @NotNull String key) {
        if (!active) {
            return config.message(key + "-off");
        }
        return config.message(key).clickEvent(ClickEvent.callback(audience -> {
            if (audience instanceof CommandSender clicker && clicker.hasPermission(GarlandCommand.ADMIN_PERMISSION)) {
                show(clicker, owner, page);
            }
        }, CLICK));
    }

    private static void teleport(@NotNull Audience audience, @NotNull Location target) {
        if (audience instanceof Player player && player.hasPermission(GarlandCommand.ADMIN_PERMISSION)) {
            player.teleportAsync(target);
        }
    }
}
