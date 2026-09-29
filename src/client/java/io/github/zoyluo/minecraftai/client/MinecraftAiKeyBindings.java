package io.github.zoyluo.minecraftai.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import io.github.zoyluo.minecraftai.client.screen.BotPanelScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class MinecraftAiKeyBindings {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minecraftai", "main"));
    private static KeyMapping openPanel;
    private static KeyMapping openActions;
    private static boolean altZeroDown;
    private static boolean altNineDown;

    private MinecraftAiKeyBindings() {
    }

    public static void register() {
        openPanel = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.minecraftai.open_panel",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY));
        openActions = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.minecraftai.open_actions",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY));
    }

    public static BotPanelScreen.Mode pollToggle(Minecraft client) {
        boolean chatPressed = false;
        while (openPanel.consumeClick()) {
            chatPressed = true;
        }
        boolean actionsPressed = false;
        while (openActions.consumeClick()) {
            actionsPressed = true;
        }
        Window handle = client.getWindow();
        boolean altPressed = InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean zeroPressed = InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_0);
        boolean altZeroPressed = altPressed && zeroPressed;
        boolean chatComboOpened = altZeroPressed && !altZeroDown;
        altZeroDown = altZeroPressed;
        boolean ninePressed = InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_9);
        boolean altNinePressed = altPressed && ninePressed;
        boolean actionsComboOpened = altNinePressed && !altNineDown;
        altNineDown = altNinePressed;
        if (!(client.screen == null || client.screen instanceof BotPanelScreen)) {
            return null;
        }
        if (actionsPressed || actionsComboOpened) {
            return BotPanelScreen.Mode.ACTIONS;
        }
        if (chatPressed || chatComboOpened) {
            return BotPanelScreen.Mode.CHAT_STATUS;
        }
        return null;
    }
}
