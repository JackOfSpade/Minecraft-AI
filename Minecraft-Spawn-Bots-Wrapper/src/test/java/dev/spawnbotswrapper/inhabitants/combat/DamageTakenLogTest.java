package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.DamageTakenLog.Taken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "damage taken" line format and its per-attacker throttle, with no Minecraft involved. */
class DamageTakenLogTest {

    private static Taken taken() {
        return new Taken("DuskRaven", "minecraft:overworld|shipwreck|4,-7", "Steve", "player", null,
                "player_attack", "iron_sword", 4.0f, 6.0f, false, 16.0f, 10.54, 64.0, -3.25,
                "minecraft:overworld", "slot=0 main=crossbow(unloaded)");
    }

    @Test
    void aPlayerHitLineNamesEverythingTheOperatorNeeds() {
        assertEquals("Combat taken: DuskRaven (inhabitant of minecraft:overworld|shipwreck|4,-7) took 4.0 damage"
                + " (6.0 before armor) from Steve (player) via player_attack with iron_sword;"
                + " DuskRaven health now 16.0 at 10.5 64.0 -3.3 in minecraft:overworld"
                + " | state: slot=0 main=crossbow(unloaded)", DamageTakenLog.line(taken(), 0));
    }

    @Test
    void aProjectileNamesTheShooterAndTheProjectileType() {
        Taken t = new Taken("Bob", null, "skeleton", "mob", "arrow", "arrow", "bow", 3.0f, 3.0f, true, 9.0f,
                0, 70, 0, "minecraft:the_nether", null);
        assertEquals("Combat taken: Bob (inhabitant) took 3.0 damage [blocked] from skeleton (mob) via arrow"
                + " [arrow] with bow; Bob health now 9.0 (+2 hits since last line) at 0.0 70.0 0.0"
                + " in minecraft:the_nether", DamageTakenLog.line(t, 2));
    }

    @Test
    void environmentalDamageHasNoAttacker() {
        Taken t = new Taken("Bob", "p", null, null, null, "fall", "none", 2.0f, 2.0f, false, 18.0f,
                1, 2, 3, "minecraft:overworld", null);
        assertTrue(DamageTakenLog.line(t, 1).contains("from no attacker via fall with none"));
        assertTrue(DamageTakenLog.line(t, 1).contains("(+1 hit since last line)"));
    }

    @Test
    void theFirstHitOfAPairIsAlwaysLoggedImmediately() {
        assertEquals(0, new DamageTakenLog(10).admit(500, "bob", "player:steve"));
        assertEquals(0, new DamageTakenLog(10).admit(0, "bob", "player:steve"));
    }

    @Test
    void rapidRepeatsAreFoldedIntoTheNextLine() {
        DamageTakenLog log = new DamageTakenLog(10);
        assertEquals(0, log.admit(100, "bob", "player:steve"));
        assertEquals(-1, log.admit(103, "bob", "player:steve"));
        assertEquals(-1, log.admit(109, "bob", "player:steve"));
        assertEquals(2, log.admit(110, "bob", "player:steve"), "the next allowed hit reports what was folded");
        assertEquals(0, log.admit(125, "bob", "player:steve"));
    }

    @Test
    void differentAttackersAndVictimsAreIndependent() {
        DamageTakenLog log = new DamageTakenLog(10);
        assertEquals(0, log.admit(100, "bob", "player:steve"));
        assertEquals(0, log.admit(101, "bob", "mob:zombie"));
        assertEquals(0, log.admit(101, "alice", "player:steve"));
        assertEquals(-1, log.admit(102, "bob", "player:steve"));
    }

    @Test
    void aServerClockRestartCountsAsAFreshPair() {
        DamageTakenLog log = new DamageTakenLog(10);
        assertEquals(0, log.admit(5000, "bob", "player:steve"));
        assertEquals(0, log.admit(3, "bob", "player:steve"));
    }

    @Test
    void theMemoryOfPairsIsBounded() {
        DamageTakenLog log = new DamageTakenLog(10);
        for (int i = 0; i < DamageTakenLog.MAX_PAIRS * 3; i++) {
            log.admit(i, "bob", "mob:m" + i);
        }
        assertTrue(log.pairs() <= DamageTakenLog.MAX_PAIRS, "pairs=" + log.pairs());
    }
}
