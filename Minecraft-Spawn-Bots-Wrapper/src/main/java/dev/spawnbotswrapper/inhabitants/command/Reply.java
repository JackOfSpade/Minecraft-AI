package dev.spawnbotswrapper.inhabitants.command;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Where a command's answer goes. The actions only talk to this, so they run (and are tested) without a
 * server; the real implementation is the command source.
 */
interface Reply {

    /** Sends marked-up lines ({@link Markup}) as informational output. */
    void lines(List<String> lines);

    /** Reports a failure (plain text); shown in red and never broadcast. */
    void error(String message);

    /**
     * Answers through the command source. Feedback is NOT broadcast to other operators: these commands are
     * diagnostics for whoever ran them, and a listing must not spam every op's chat.
     */
    static Reply to(ServerCommandSource source) {
        return new Reply() {
            @Override
            public void lines(List<String> lines) {
                for (String line : lines) {
                    Text text = ChatText.of(line);
                    source.sendFeedback(() -> text, false);
                }
            }

            @Override
            public void error(String message) {
                source.sendError(Text.literal(Markup.strip(message)));
            }
        };
    }
}
