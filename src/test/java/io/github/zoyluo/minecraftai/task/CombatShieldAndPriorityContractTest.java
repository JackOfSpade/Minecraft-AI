package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the shield/projectile reaction, the creeper-fuse shield trigger, the melee-vs-ranged target
 * priority rule, and the peekaboo cover tactic to their intended source shape.
 */
class CombatShieldAndPriorityContractTest {
    private static final Path COMBAT_TASK = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/CombatTask.java");
    private static final Path SHIELD_GUARD = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/ShieldGuard.java");
    private static final Path DANGER_WATCHER = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/DangerWatcher.java");

    @Test
    void theCombatTaskYieldsToTheShieldGuardOnlyInMeleePhasesNeverWhileDrawingABow() throws IOException {
        String combat = read(COMBAT_TASK);
        assertTrue(combat.contains("isMeleeOrientedPhase() && ShieldGuard.INSTANCE.holding(bot)"),
                "the reaction must be gated to melee phases so it never fights the bow draw for "
                        + "the single active hand");
        int predicate = combat.indexOf("private boolean isMeleeOrientedPhase()");
        int predicateEnd = combat.indexOf('}', predicate);
        String body = combat.substring(predicate, predicateEnd);
        assertTrue(body.contains("Phase.ACQUIRE") && body.contains("Phase.APPROACH")
                && body.contains("Phase.STRIKE") && body.contains("Phase.REPOSITION")
                && body.contains("Phase.BLOCK"));
        assertFalse(body.contains("Phase.RANGED") || body.contains("Phase.COVER"),
                "ranged/peekaboo phases must never be treated as melee-oriented");
        int exchange = combat.indexOf("boolean isRangedExchange()");
        String exchangeBody = combat.substring(exchange, combat.indexOf('}', exchange));
        assertTrue(exchangeBody.contains("rangedLoadout != null") && exchangeBody.contains("Phase.RANGED")
                        && exchangeBody.contains("Phase.COVER_PEEK"),
                "a ranged exchange (bow out, arrow in the offhand) is what the guard leaves alone");
        assertTrue(read(SHIELD_GUARD).contains("combat.isRangedExchange()"),
                "the guard must not take the hand from a ranged exchange");
    }

    @Test
    void theShieldGuardTurnsToFaceTheThreatBeforeRaisingIt() throws IOException {
        String guard = read(SHIELD_GUARD);
        int engage = guard.indexOf("private void engage(");
        int face = guard.indexOf("faceIntoArc(bot, threat.facePoint(), threat.halfArcDeg())", engage);
        int raise = guard.indexOf("raise(bot, Owner.REACTIVE)", engage);
        assertTrue(engage >= 0 && face > engage && raise > face,
                "the bot must turn to face the incoming projectile/creeper before raising the shield");
        assertTrue(guard.contains("ProjectileThreat.incoming(bot, shield)"));
        assertTrue(guard.contains("creeper.getSwelling(1.0F) >= CREEPER_FUSE_THRESHOLD"));
        assertTrue(guard.contains("HumanAim.lookToward(bot, source)"), "the turn is a human-speed turn, never an instant spin");
    }

    @Test
    void meleeModePrioritizesClosestAndRangedModePrioritizesRangedAttackersFirst() throws IOException {
        String watcher = read(DANGER_WATCHER);
        assertTrue(watcher.contains("bot.distanceTo(mob) <= CombatCore.MELEE_ENGAGEMENT_RANGE"));
        assertTrue(watcher.contains(
                ".thenComparing(mob -> meleeModeActive || CombatCore.isRangedThreat(mob) ? 0 : 1)"),
                "ranged attackers must outrank melee-only mobs specifically when nothing is already "
                        + "close enough to be a melee exchange");
        int rangedKey = watcher.indexOf(".thenComparing(mob -> meleeModeActive");
        int distanceKey = watcher.indexOf(".thenComparingDouble(bot::distanceTo)");
        assertTrue(rangedKey >= 0 && distanceKey > rangedKey,
                "distance must remain the final tiebreaker, applied after the ranged-priority key");
    }

    @Test
    void peekabooOnlyTriggersWithAtLeastTwoObservableRangedThreats() throws IOException {
        String combat = read(COMBAT_TASK);
        assertTrue(combat.contains(
                "CombatCore.rangedThreatsAround(bot, PEEKABOO_SCAN_RANGE).size()\n"
                        + "                >= PEEKABOO_MIN_RANGED_THREATS"));
        assertTrue(combat.contains("PEEKABOO_MIN_RANGED_THREATS = 2"));
        assertTrue(combat.contains("MaterialPalette.pickSacrificialBlockSlot(bot)"),
                "the cover column must be built from the least valuable available block");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
