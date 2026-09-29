package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code HARMFUL_FOODS} is the list of hunger-only last-resort foods. Poison foods never reach it because
 * {@code isEatableFood} excludes them through their consume effects, so listing them there is dead weight
 * that misleads the next reader into thinking they can still be eaten as a fallback.
 */
final class InventoryActionHarmfulFoodsSourceContractTest {
    @Test
    void harmfulFoodsListsOnlyTheHungerOnlyLastResortFoods() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/action/InventoryAction.java"));
        int start = source.indexOf("HARMFUL_FOODS = java.util.Set.of(");
        assertTrue(start >= 0, "the HARMFUL_FOODS declaration moved");
        String declaration = source.substring(start, source.indexOf(");", start));
        assertTrue(declaration.contains("Items.CHICKEN"), declaration);
        assertTrue(declaration.contains("Items.ROTTEN_FLESH"), declaration);
        for (String poison : new String[] {"PUFFERFISH", "SPIDER_EYE", "POISONOUS_POTATO"}) {
            assertFalse(declaration.contains(poison), poison + " is excluded by isEatableFood and must not be listed");
        }
        assertTrue(source.contains("!isPoisonFood(stack)"), "isEatableFood must keep excluding poison foods");
    }
}
