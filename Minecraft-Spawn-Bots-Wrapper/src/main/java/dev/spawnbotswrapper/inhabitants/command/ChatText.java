package dev.spawnbotswrapper.inhabitants.command;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Turns a marked-up line ({@link Markup}) into a styled {@link Text}. The only place markup meets Minecraft. */
final class ChatText {
    private ChatText() {
    }

    static Text of(String markedUpLine) {
        MutableText root = Text.empty();
        for (Markup.Span span : Markup.spans(markedUpLine)) {
            MutableText part = Text.literal(span.text());
            Formatting color = span.color() == 0 ? null : Formatting.byCode(span.color());
            root.append(color == null ? part : part.formatted(color));
        }
        return root;
    }
}
