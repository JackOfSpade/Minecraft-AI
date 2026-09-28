package io.github.zoyluo.minecraftai.client;

import io.github.zoyluo.minecraftai.client.screen.BotInventoryScreen;
import io.github.zoyluo.minecraftai.client.screen.BotPanelScreen;
import io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler;
import io.github.zoyluo.minecraftai.network.payload.AIPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public final class MinecraftAiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BotInventoryScreenHandler.initialize();
        HandledScreens.register(BotInventoryScreenHandler.TYPE, BotInventoryScreen::new);
        AIPayloads.register();
        MinecraftAiKeyBindings.register();
        MinecraftAiClientNetworking.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        BotPanelScreen.Mode mode = MinecraftAiKeyBindings.pollToggle(client);
        if (mode == null) {
            return;
        }
        if (client.currentScreen instanceof BotPanelScreen panel && panel.mode() == mode) {
            client.setScreen(null);
        } else {
            client.setScreen(new BotPanelScreen(mode));
        }
    }
}
