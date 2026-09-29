package io.github.zoyluo.minecraftai.perception;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures the selected bot's planner snapshot continues to include worn and held equipment. */
final class PerceptionEquipmentSourceContractTest {
    @Test
    void selfStateCapturesBothHandsAndAllArmorSlots() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/perception/PerceptionCollector.java"));

        assertTrue(source.contains("equipment(bot)"));
        assertTrue(source.contains("bot.getMainHandItem()"));
        assertTrue(source.contains("bot.getOffhandItem()"));
        assertTrue(source.contains("bot.getItemBySlot(EquipmentSlot.HEAD)"));
        assertTrue(source.contains("bot.getItemBySlot(EquipmentSlot.CHEST)"));
        assertTrue(source.contains("bot.getItemBySlot(EquipmentSlot.LEGS)"));
        assertTrue(source.contains("bot.getItemBySlot(EquipmentSlot.FEET)"));
    }
}
