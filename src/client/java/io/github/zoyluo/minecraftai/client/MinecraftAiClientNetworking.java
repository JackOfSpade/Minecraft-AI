package io.github.zoyluo.minecraftai.client;

import io.github.zoyluo.minecraftai.network.payload.BotChatS2C;
import io.github.zoyluo.minecraftai.network.payload.BotSnapshotS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class MinecraftAiClientNetworking {
    private MinecraftAiClientNetworking() {
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
