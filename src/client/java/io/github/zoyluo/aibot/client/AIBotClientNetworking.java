package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.network.payload.BotChatS2C;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class AIBotClientNetworking {
    private AIBotClientNetworking() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BotSnapshotS2C.ID, (payload, context) ->
                context.client().execute(() -> BotClientState.INSTANCE.setSnapshot(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BotChatS2C.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (BotClientState.INSTANCE.matchesTarget(payload.botName())) {
                        BotClientState.INSTANCE.addTranscript(payload.role(), payload.text());
                    }
                }));
    }
}
