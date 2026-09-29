package dev.spawnbotswrapper.inhabitants.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Turns a marked-up line ({@link Markup}) into a styled {@link Component}. The only place markup meets Minecraft. */
final class ChatText {
    private ChatText() {
    }

    static Component of(String markedUpLine) {
        MutableComponent root = Component.empty();
        for (Markup.Span span : Markup.spans(markedUpLine)) {
            MutableComponent part = Component.literal(span.text());
            ChatFormatting color = span.color() == 0 ? null : ChatFormatting.getByCode(span.color());
            root.append(color == null ? part : part.withStyle(color));
        }
        return root;
    }
}
