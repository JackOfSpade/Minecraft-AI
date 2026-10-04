package io.github.zoyluo.minecraftai.client;

import io.github.zoyluo.minecraftai.client.screen.BotInventoryScreen;
import io.github.zoyluo.minecraftai.client.screen.BotPanelScreen;
import io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler;
import io.github.zoyluo.minecraftai.network.payload.AIPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;

public final class MinecraftAiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BotInventoryScreenHandler.initialize();
        MenuScreens.register(BotInventoryScreenHandler.TYPE, BotInventoryScreen::new);
        AIPayloads.register();
        MinecraftAiKeyBindings.register();
        MinecraftAiClientNetworking.register();
        BotChatCapture.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        BotPanelScreen.Mode mode = MinecraftAiKeyBindings.pollToggle(client);
        if (mode == null) {
            return;
        }
        if (client.screen instanceof BotPanelScreen panel && panel.mode() == mode) {
            client.setScreen(null);
        } else {
            client.setScreen(new BotPanelScreen(mode));
        }
    }
}
