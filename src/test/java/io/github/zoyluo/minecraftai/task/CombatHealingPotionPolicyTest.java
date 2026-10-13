package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the fast-combat and safe-shelter split for Instant Health potions. */
final class CombatHealingPotionPolicyTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void combatThrowsSplashHealingOnlyAfterFiveHeartsAreMissing() {
        assertFalse(CombatTask.shouldUseCombatSplashHealing(20.0F, 20.0F));
        assertFalse(CombatTask.shouldUseCombatSplashHealing(10.1F, 20.0F));
        assertTrue(CombatTask.shouldUseCombatSplashHealing(10.0F, 20.0F));
        assertTrue(CombatTask.shouldUseCombatSplashHealing(8.0F, 20.0F));
        assertFalse(CombatTask.shouldUseCombatSplashHealing(0.0F, 20.0F));
    }

    @Test
    void combatUsesOnlySplashInstantHealthWhileShelterMayUseEveryHealingPotion() throws IOException {
        String combat = read("task/CombatTask.java");
        String potion = read("task/HealingPotionTask.java");
        String shelter = read("task/EmergencyShelterTask.java");

        assertTrue(combat.contains("HealingPotionTask.splashOnlyForCombat()"));
        assertTrue(combat.contains("HealingPotionTask.hasSplashHealingPotion(bot)"));
        assertTrue(combat.contains("hostileWithinCombatSplashHealingRange(bot)"),
                "a combat splash must wait until it cannot heal a hostile too");
        assertTrue(combat.contains("CombatCore.hostileTo(bot, entity)"));
        assertTrue(combat.contains("COMBAT_SPLASH_HEAL_ENEMY_CLEARANCE = 4.0D"),
                "the guard must match the four-block vanilla splash effect radius");
        assertFalse(combat.contains("EatAction.startEating"),
                "food use is too slow for a live CombatTask and belongs to sealed-shelter recovery");
        assertTrue(potion.contains("effect.getEffect().is(MobEffects.INSTANT_HEALTH)"),
                "all Instant Health amplifier tiers must be recognized, rather than enumerating one potion id");
        assertTrue(potion.contains("static boolean isSplashHealingPotion"));
        int hold = shelter.indexOf("private void tickHold(AIPlayerEntity bot)");
        int potionBeforeFood = shelter.indexOf("if (tickHoldHealingPotion(bot))", hold);
        int foodAfterPotion = shelter.indexOf("if (tickHoldEating(bot))", potionBeforeFood);
        assertTrue(hold >= 0 && potionBeforeFood > hold && foodAfterPotion > potionBeforeFood,
                "a sealed shelter uses healing potions before the slower food recovery path");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
