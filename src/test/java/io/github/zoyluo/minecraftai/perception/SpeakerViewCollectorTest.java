package io.github.zoyluo.minecraftai.perception;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SpeakerViewCollectorTest {
    @Test
    void exposesOnlyConcreteVisibleFeaturesForTheLanguageModel() {
        assertEquals(List.of("door", "glass", "lighting", "stairs", "water"),
                SpeakerViewCollector.featuresForTest(List.of(
                        "minecraft:oak_door",
                        "minecraft:glass_pane",
                        "minecraft:lantern",
                        "minecraft:stone_stairs",
                        "minecraft:water")));
    }
}
