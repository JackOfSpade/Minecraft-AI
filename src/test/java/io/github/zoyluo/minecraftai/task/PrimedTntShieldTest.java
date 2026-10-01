package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ShieldRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused contract for a visible default-vanilla PrimedTnt explosion in the reactive shield guard. */
class PrimedTntShieldTest {
    @Test
    void defaultVanillaTntUsesItsActualEightBlockDamageEnvelopeAndFuseBudget() {
        // PrimedTnt's verified default power is 4, while vanilla explosion damage ends at twice the radius.
        assertEquals(8.0D, ShieldGuard.primedTntBlastReach(), 1.0E-9D);
        assertEquals(57.0F, ShieldGuard.primedTntWorstCaseDamage(0.0D));
        assertEquals(1.0F, ShieldGuard.primedTntWorstCaseDamage(8.0D));
        assertTrue(ShieldGuard.primedTntWorstCaseDamage(7.5D) < 6.0F,
                "a distant observed TNT must not use its impossible zero-distance damage to interrupt a healthy bot's item use");

        // The shared timing rule includes its one-tick impact slack: a five-tick shield delay needs six fuse ticks in front.
        assertTrue(ShieldGuard.primedTntCanBeActiveInTime(6, 0, 5, 0));
        assertFalse(ShieldGuard.primedTntCanBeActiveInTime(5, 0, 5, 0));
        // A main-hand switch costs its own tick before the five-tick delay; a non-positive fuse is already too late.
        assertTrue(ShieldGuard.primedTntCanBeActiveInTime(7, 0, 5, ShieldRules.HOTBAR_SWITCH_TICKS));
        assertFalse(ShieldGuard.primedTntCanBeActiveInTime(6, 0, 5, ShieldRules.HOTBAR_SWITCH_TICKS));
        assertFalse(ShieldGuard.primedTntCanBeActiveInTime(0, 0, 0, 0));
    }

    @Test
    void runtimeRequiresAVisibleTntVanillaExplosionSourceAndLeavesSprintingTasksAlone() throws IOException {
        String guard = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/task/ShieldGuard.java"));
        int tnt = guard.indexOf("List<PrimedTnt> tnts = imminentTnt(bot);");
        assertTrue(tnt > 0, "the reactive guard considers primed TNT");
        assertTrue(guard.indexOf("active instanceof FollowTask", 0) < tnt
                        && guard.indexOf("active instanceof EvadeTask", 0) < tnt,
                "follow and evade return after in-flight projectiles, before a TNT fuse can make them stop sprinting");
        int tntBranch = guard.lastIndexOf("// 3. A visible primed TNT block", tnt);
        String branch = guard.substring(tntBranch, guard.indexOf("// 4. A guardian", tnt));
        assertTrue(guard.contains("isVanillaPrimedTnt(tnt)"),
                "the default TNT envelope is never applied to a modded PrimedTnt subclass");
        assertTrue(branch.contains("level.getGameRules().get(GameRules.TNT_EXPLODES)")
                        && branch.contains("Explosion.getDefaultDamageSource(level, tnt)")
                        && branch.contains("ShieldBlockability.blocks(shield, blast)")
                        && branch.contains("canBlockPrimedTntInTime")
                        && branch.contains("primedTntWorstCaseDamage(bot.distanceTo(tnt))")
                        && branch.contains("reactedToVisibleTnt(bot, tnt, exposed)"),
                "only the registered vanilla TNT's real explosion, shield data, human reaction and synced-fuse timing gate a raise");
        assertTrue(guard.contains("ObservableWorldQuery.canObserveEntity(bot, tnt)"),
                "TNT is an object: it must be visibly observable, never read through a wall as a tracked creature");
        assertTrue(guard.contains("tntInViewField(bot, tnt)") && guard.contains("state.primedTnt.sighted(tnt.getId(), now)")
                        && guard.contains("state.primedTnt.retain(inFieldTnt)") && guard.contains("ShieldRules.reacted(true, false"),
                "only actual in-field TNT exposure earns the shared human reaction; an out-of-view near TNT cannot mask a farther visible one");
    }
}
