package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the deterministic bow/melee boundary and the physical-ammunition selection invariant. */
final class CombatSmartBowSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void combatUsesBowOnlyBeyondOneAndAHalfMeleeRangesAndReturnsToBestMelee() throws IOException {
        String combat = read("task/CombatTask.java");

        assertTrue(combat.contains("BOW_MELEE_SWITCH_DISTANCE = CombatCore.ATTACK_RANGE * 1.5D"));
        assertTrue(combat.contains("bot.distanceTo(target) > BOW_MELEE_SWITCH_DISTANCE"));
        assertTrue(combat.contains("EquipAction.bestRangedSlot(bot, target).isPresent()"));
        assertTrue(combat.contains("finishRangedLoadout(bot);\n        CombatCore.ensureMeleeWeapon(bot, target);"),
                "crossing back inside the range boundary must restore the offhand and equip melee");
        assertTrue(combat.contains("bot.stopUsingItem();"),
                "leaving ranged mode must cancel, rather than release, an in-progress bow shot");
        assertTrue(combat.contains("protected void onAbort(AIPlayerEntity bot)"));
        assertTrue(combat.contains("protected void onPause(AIPlayerEntity bot)"));
    }

    @Test
    void rangedLoadoutRanksAllVanillaArrowFamiliesBeforeMakingTheHeldProjectileObservable()
            throws IOException {
        String equip = read("action/EquipAction.java");

        assertTrue(equip.contains("stack.getItem() instanceof ArrowItem"),
                "ArrowItem is the shared base for normal, spectral, and tipped vanilla arrows");
        assertTrue(equip.contains("DataComponents.POTION_CONTENTS"));
        assertTrue(equip.contains("PotionContents.EMPTY"));
        assertTrue(equip.contains("MobEffects.INSTANT_DAMAGE"));
        assertTrue(equip.contains("target.getType().is(EntityTypeTags.UNDEAD)"));
        assertTrue(equip.contains("MobEffects.INSTANT_HEALTH"));
        assertTrue(equip.indexOf("candidate.damageScore()")
                        < equip.indexOf("candidate.enemyEffectScore()"),
                "direct damage must be the first arrow ranking key, before harmful effects");
        assertTrue(equip.contains("bot.setItemSlot(EquipmentSlot.OFFHAND, ammunition.copy())"),
                "the selected arrow must be held because vanilla resolves held projectiles first");
        assertTrue(equip.contains("does not let an Infinity bow invent ammunition"));
    }

    @Test
    void rangedOffhandSwapNeverDropsOrOverwritesAnUnverifiedStack() throws IOException {
        String equip = read("action/EquipAction.java");

        assertTrue(equip.contains("inventory.getNonEquipmentItems().set(choice.mainSlot(), displacedOffhand)"));
        assertTrue(equip.contains("ItemStack.matches(inventory.getNonEquipmentItems().get(restoreSlot), storedOffhand)"));
        assertTrue(equip.contains("ItemStack.isSameItemSameComponents(currentOffhand, ammunition)"));
        assertFalse(equip.contains("drop("),
                "ranged preparation/restoration must be an inventory swap, never a drop");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
