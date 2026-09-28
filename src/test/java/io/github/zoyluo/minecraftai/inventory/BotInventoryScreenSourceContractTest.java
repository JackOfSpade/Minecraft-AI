package io.github.zoyluo.aibot.inventory;

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
                "src/main/java/io/github/zoyluo/aibot/entity/AIPlayerEntity.java"));
        String factory = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/inventory/BotInventoryScreenFactory.java"));
        String handler = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/inventory/BotInventoryScreenHandler.java"));
        String client = Files.readString(Path.of(
                "src/client/java/io/github/zoyluo/aibot/client/AIBotClient.java"));
        String screen = Files.readString(Path.of(
                "src/client/java/io/github/zoyluo/aibot/client/screen/BotInventoryScreen.java"));

        assertTrue(entity.contains("BotAuthorizationPolicy.Operation.INVENTORY"));
        assertTrue(entity.contains("viewer.openHandledScreen(new BotInventoryScreenFactory(this, viewer))"));
        assertTrue(factory.contains("+ \" gear\""));
        assertTrue(factory.contains("MAX_TITLE_NAME_LENGTH = 10"));

        assertTrue(handler.contains("BOT_SLOT_COUNT = OFFHAND_SLOT + 1"));
        assertTrue(handler.contains("new SimpleInventory(BOT_SLOT_COUNT)"));
        assertTrue(handler.contains("return HOTBAR_SIZE + slot"));
        assertTrue(handler.contains("return slot - BOT_HOTBAR_START"));
        assertTrue(handler.contains("EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET"));
        assertTrue(handler.contains("bot.getInventory().getSelectedSlot()"));
        assertTrue(handler.contains("insertIntoPreferredEquipment"));
        assertTrue(handler.contains("class BotEquipmentSlot extends BotInventorySlot"));
        assertTrue(handler.contains("bot.equipStack(EquipmentSlot.OFFHAND, value)"));
        assertTrue(handler.contains("TaskManager.INSTANCE.pauseFor(bot, \"inventory_screen_open\")"));
        assertTrue(handler.contains("TaskManager.INSTANCE.resumeFromPause(bot)"));
        assertFalse(handler.contains("GENERIC_9X5"));
        assertFalse(handler.contains("GenericContainerScreenHandler"));

        assertTrue(client.contains("HandledScreens.register(BotInventoryScreenHandler.TYPE, BotInventoryScreen::new)"));
        assertTrue(screen.contains("Hotbar (gold = main hand)"));
        assertTrue(screen.contains("Armor (H C L B)"));
        assertTrue(screen.contains("Offhand"));
    }
}
