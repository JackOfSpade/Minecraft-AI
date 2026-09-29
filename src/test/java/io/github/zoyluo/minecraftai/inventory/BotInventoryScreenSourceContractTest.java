package io.github.zoyluo.minecraftai.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BotInventoryScreenSourceContractTest {
    @Test
    void inventoryMenuUsesOnlyRealBotSlotsAndRegistersItsClientScreen() throws IOException {
        String entity = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/entity/AIPlayerEntity.java"));
        String factory = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/inventory/BotInventoryScreenFactory.java"));
        String handler = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/inventory/BotInventoryScreenHandler.java"));
        String client = Files.readString(Path.of(
                "src/client/java/io/github/zoyluo/minecraftai/client/MinecraftAiClient.java"));
        String screen = Files.readString(Path.of(
                "src/client/java/io/github/zoyluo/minecraftai/client/screen/BotInventoryScreen.java"));

        assertTrue(entity.contains("BotAuthorizationPolicy.Operation.INVENTORY"));
        assertTrue(entity.contains("viewer.openMenu(new BotInventoryScreenFactory(this, viewer))"));
        assertTrue(factory.contains("+ \" gear\""));
        assertTrue(factory.contains("MAX_TITLE_NAME_LENGTH = 10"));

        assertTrue(handler.contains("BOT_SLOT_COUNT = OFFHAND_SLOT + 1"));
        assertTrue(handler.contains("new SimpleContainer(BOT_SLOT_COUNT)"));
        assertTrue(handler.contains("return HOTBAR_SIZE + slot"));
        assertTrue(handler.contains("return slot - BOT_HOTBAR_START"));
        assertTrue(handler.contains("EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET"));
        assertTrue(handler.contains("bot.getInventory().getSelectedSlot()"));
        assertTrue(handler.contains("insertIntoPreferredEquipment"));
        assertTrue(handler.contains("class BotEquipmentSlot extends BotInventorySlot"));
        assertTrue(handler.contains("bot.setItemSlot(EquipmentSlot.OFFHAND, value)"));
        assertTrue(handler.contains("TaskManager.INSTANCE.pauseFor(bot, \"inventory_screen_open\")"));
        assertTrue(handler.contains("TaskManager.INSTANCE.resumeFromPause(bot)"));
        assertFalse(handler.contains("GENERIC_9x5"));
        assertFalse(handler.contains("ChestMenu"));

        assertTrue(client.contains("MenuScreens.register(BotInventoryScreenHandler.TYPE, BotInventoryScreen::new)"));
        assertTrue(screen.contains("Hotbar (gold = main hand)"));
        assertTrue(screen.contains("Armor (H C L B)"));
        assertTrue(screen.contains("Offhand"));
    }
}
