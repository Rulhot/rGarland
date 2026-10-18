package ru.rulhot.rGarland.util;

import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@UtilityClass
public class MessageUtil {

    private final @NotNull MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private final @NotNull String TAG_PREFIX = "rg_";

    public @NotNull Component parseItemText(@NotNull String text) {
        return MINI_MESSAGE.deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public @NotNull Component parseText(@NotNull String template, @NotNull Map<String, ?> placeholders) {
        if (placeholders.isEmpty()) {
            return MINI_MESSAGE.deserialize(template);
        }
        String prepared = template;
        List<TagResolver> resolvers = new ArrayList<>(placeholders.size());
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            String tag = TAG_PREFIX + entry.getKey().toLowerCase(Locale.ROOT);
            prepared = prepared.replace("{" + entry.getKey() + "}", "<" + tag + ">");
            resolvers.add(entry.getValue() instanceof ComponentLike component
                    ? Placeholder.component(tag, component)
                    : Placeholder.unparsed(tag, String.valueOf(entry.getValue())));
        }
        return MINI_MESSAGE.deserialize(prepared, TagResolver.resolver(resolvers));
    }
}
