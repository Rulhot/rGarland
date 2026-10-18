package ru.rulhot.rGarland.service;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.rulhot.rGarland.model.GarlandSettings;
import ru.rulhot.rGarland.util.MessageUtil;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class ItemService {

    public static final int COOLDOWN_TICKS = 5;

    private static final float TICKS_PER_SECOND = 20f;
    private static final @NotNull String TEXTURES = "textures";
    private static final @NotNull String TEXTURE_JSON =
            "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/%s\"}}}";

    private final @NotNull NamespacedKey itemKey;
    private final @NotNull NamespacedKey cooldownGroup;

    public ItemService(@NotNull JavaPlugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "item");
        this.cooldownGroup = new NamespacedKey(plugin, GarlandSettings.ID);
    }

    public @NotNull ItemStack create(@NotNull GarlandSettings.Item settings, int amount) {
        ItemStack item = ItemStack.of(settings.material(), amount);
        item.editMeta(meta -> {
            meta.displayName(MessageUtil.parseItemText(settings.name()));
            meta.lore(settings.lore().stream().map(MessageUtil::parseItemText).toList());
            UseCooldownComponent cooldown = meta.getUseCooldown();
            cooldown.setCooldownSeconds(COOLDOWN_TICKS / TICKS_PER_SECOND);
            cooldown.setCooldownGroup(cooldownGroup);
            meta.setUseCooldown(cooldown);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, GarlandSettings.ID);
        });
        return item;
    }

    public boolean isGarland(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        return GarlandSettings.ID.equals(item.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING));
    }

    public static @NotNull ItemStack head(@NotNull String texture) {
        ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, skull -> skull.setPlayerProfile(profile(texture)));
        return head;
    }

    private static @NotNull PlayerProfile profile(@NotNull String texture) {
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)));
        String json = TEXTURE_JSON.replace("%s", texture.trim());
        profile.setProperty(new ProfileProperty(TEXTURES,
                Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        return profile;
    }
}
