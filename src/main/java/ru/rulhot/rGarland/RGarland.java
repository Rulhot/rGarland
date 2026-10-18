package ru.rulhot.rGarland;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.command.GarlandCommand;
import ru.rulhot.rGarland.command.sub.GiveSubCommand;
import ru.rulhot.rGarland.command.sub.HelpSubCommand;
import ru.rulhot.rGarland.command.sub.ListSubCommand;
import ru.rulhot.rGarland.command.sub.ReloadSubCommand;
import ru.rulhot.rGarland.command.sub.RemoveSubCommand;
import ru.rulhot.rGarland.display.DisplayEffects;
import ru.rulhot.rGarland.hook.CakeRegionsHook;
import ru.rulhot.rGarland.hook.GriefPreventionHook;
import ru.rulhot.rGarland.hook.ProtectionHook;
import ru.rulhot.rGarland.hook.WorldGuardHook;
import ru.rulhot.rGarland.listener.GarlandListener;
import ru.rulhot.rGarland.listener.ItemProtectionListener;
import ru.rulhot.rGarland.listener.PreviewListener;
import ru.rulhot.rGarland.listener.ViewerListener;
import ru.rulhot.rGarland.manager.ConfigManager;
import ru.rulhot.rGarland.model.Settings;
import ru.rulhot.rGarland.service.GarlandService;
import ru.rulhot.rGarland.service.ItemService;
import ru.rulhot.rGarland.service.PreviewService;
import ru.rulhot.rGarland.service.ReloadService;
import ru.rulhot.rGarland.storage.GarlandRepository;
import ru.rulhot.rGarland.util.Metrics;
import ru.rulhot.rGarland.util.SchedulerUtil;
import ru.rulhot.rGarland.util.UpdateChecker;
import ru.rulhot.rGarland.util.logger.Logger;

import java.util.ArrayList;
import java.util.List;

public final class RGarland extends JavaPlugin {

    private static final int BSTATS_ID = 34794;

    private static final @NotNull List<Permission> PERMISSIONS = List.of(
            new Permission(GarlandCommand.ADMIN_PERMISSION, PermissionDefault.OP),
            new Permission(GarlandService.PERMISSION_USE, PermissionDefault.TRUE),
            new Permission(GarlandService.PERMISSION_OTHERS, PermissionDefault.OP));

    private @Nullable Metrics metrics;
    private @Nullable DisplayEffects effects;
    private @Nullable GarlandService garlands;

    @Override
    public void onEnable() {
        Logger.setup(this);
        SchedulerUtil scheduler = new SchedulerUtil(this);

        ConfigManager config = new ConfigManager(this);
        DisplayEffects effects = new DisplayEffects(scheduler);
        this.effects = effects;
        ItemService items = new ItemService(this);
        GarlandService garlands = new GarlandService(config, effects, scheduler,
                new GarlandRepository(scheduler, getDataFolder().toPath()), hookProtection());
        this.garlands = garlands;
        ReloadService reloads = new ReloadService(scheduler, config, garlands);

        PluginManager pluginManager = getServer().getPluginManager();
        for (Permission permission : PERMISSIONS) {
            if (pluginManager.getPermission(permission.getName()) == null) {
                pluginManager.addPermission(permission);
            }
        }
        pluginManager.registerEvents(new GarlandListener(config, items, garlands), this);
        pluginManager.registerEvents(new ItemProtectionListener(items), this);
        pluginManager.registerEvents(new ViewerListener(effects), this);
        PreviewService previews = new PreviewService(scheduler, config, effects, items, garlands);
        pluginManager.registerEvents(new PreviewListener(previews), this);

        GarlandCommand command = new GarlandCommand(config, List.of(
                new HelpSubCommand(config),
                new GiveSubCommand(config, items, scheduler, previews),
                new ListSubCommand(config, garlands),
                new RemoveSubCommand(config, garlands),
                new ReloadSubCommand(config, reloads, scheduler)));
        Settings startup = config.readStartup();
        if (startup.metrics() && BSTATS_ID > 0) {
            metrics = new Metrics(this, BSTATS_ID);
        }
        if (startup.updateCheck()) {
            new UpdateChecker(getPluginMeta().getVersion()).check();
        }
        Settings.Command settings = startup.command();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(command.build(settings.name()), settings.aliases()));

        reloads.start();
    }

    private @NotNull ProtectionHook hookProtection() {
        List<ProtectionHook> hooks = new ArrayList<>();
        PluginManager plugins = getServer().getPluginManager();
        if (plugins.isPluginEnabled("WorldGuard")) {
            try {
                hooks.add(new WorldGuardHook());
            } catch (Throwable throwable) {
                Logger.error("Не удалось подключить WorldGuard, его регионы не проверяются", throwable);
            }
        }
        if (plugins.isPluginEnabled("GriefPrevention")) {
            try {
                hooks.add(new GriefPreventionHook());
            } catch (Throwable throwable) {
                Logger.error("Не удалось подключить GriefPrevention, его приваты не проверяются", throwable);
            }
        }
        if (plugins.isPluginEnabled("CakeRegionsPlus")) {
            try {
                hooks.add(new CakeRegionsHook());
            } catch (Throwable throwable) {
                Logger.error("Не удалось подключить CakeRegionsPlus, его регионы не проверяются", throwable);
            }
        }
        return switch (hooks.size()) {
            case 0 -> ProtectionHook.NONE;
            case 1 -> hooks.getFirst();
            default -> (player, at) -> hooks.stream().allMatch(hook -> hook.canBuild(player, at));
        };
    }

    @Override
    public void onDisable() {
        Metrics currentMetrics = metrics;
        metrics = null;
        if (currentMetrics != null) {
            currentMetrics.shutdown();
        }
        GarlandService currentGarlands = garlands;
        garlands = null;
        if (currentGarlands != null) {
            currentGarlands.shutdown();
        }
        DisplayEffects currentEffects = effects;
        effects = null;
        if (currentEffects != null) {
            currentEffects.clear();
        }
        PERMISSIONS.forEach(permission -> getServer().getPluginManager().removePermission(permission));
    }
}
