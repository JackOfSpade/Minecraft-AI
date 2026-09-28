package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.network.payload.BotChatS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public final class BotChatCapture {
    private BotChatCapture() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (ClientPlayNetworking.canSend(BotChatS2C.ID)) {
                return;
            }
            String text = message.getString();
            String target = BotClientState.INSTANCE.targetBot();
            String botPrefix = "<" + target + "> ";
            if (text.startsWith("[AIBot] ")) {
                BotClientState.INSTANCE.addTranscript("system", text.substring("[AIBot] ".length()));
            } else if (text.contains(target + " is thinking") || text.contains("brain error:")) {
                BotClientState.INSTANCE.addTranscript("system", text);
            } else if (text.startsWith(botPrefix)) {
                BotClientState.INSTANCE.addTranscript("bot", text.substring(botPrefix.length()));
            }
        });
    }
}
