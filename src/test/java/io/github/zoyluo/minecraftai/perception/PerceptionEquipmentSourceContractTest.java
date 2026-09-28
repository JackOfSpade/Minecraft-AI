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
        assertTrue(source.contains("bot.getMainHandStack()"));
        assertTrue(source.contains("bot.getOffHandStack()"));
        assertTrue(source.contains("bot.getEquippedStack(EquipmentSlot.HEAD)"));
        assertTrue(source.contains("bot.getEquippedStack(EquipmentSlot.CHEST)"));
        assertTrue(source.contains("bot.getEquippedStack(EquipmentSlot.LEGS)"));
        assertTrue(source.contains("bot.getEquippedStack(EquipmentSlot.FEET)"));
    }
}
