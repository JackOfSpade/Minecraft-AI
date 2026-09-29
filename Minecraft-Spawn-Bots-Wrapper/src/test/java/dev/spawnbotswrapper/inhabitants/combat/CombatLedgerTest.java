package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.CombatLedger.Actor;
import dev.spawnbotswrapper.inhabitants.combat.CombatLedger.Death;
import dev.spawnbotswrapper.inhabitants.combat.CombatLedger.Hit;
import dev.spawnbotswrapper.inhabitants.combat.CombatLedger.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coalescing, throttling and formatting of the combat log, with no Minecraft involved. */
class CombatLedgerTest {

    private static final Actor BOT = new Actor("DuskRaven", Kind.INHABITANT);
    private static final Actor PLAYER = new Actor("Steve", Kind.PLAYER);
    private static final Actor ZOMBIE = new Actor("zombie", Kind.MOB);

    private final List<String> lines = new ArrayList<>();
    private int coalesce = 100;
    private int budget = 30;
    private final CombatLedger ledger = new CombatLedger(lines::add, () -> coalesce, () -> budget);

    private static Hit hit(Actor attacker, Actor victim, float damage, float healthAfter) {
        return new Hit(attacker, victim, damage, damage, false, "player_attack", "iron_sword", 2.4, healthAfter);
    }

    @Test
    void aBurstOfHitsBetweenTheSamePairIsOneSummaryLineAfterTheWindow() {
        ledger.hit(0, hit(BOT, PLAYER, 5f, 15f), false);
        ledger.hit(10, hit(BOT, PLAYER, 7f, 8f), false);
        ledger.hit(30, hit(BOT, PLAYER, 4f, 4f), false);
        ledger.tick(99);
        assertTrue(lines.isEmpty(), "still inside the window: " + lines);
        ledger.tick(100);
        assertEquals(1, lines.size(), lines.toString());
        String line = lines.get(0);
        assertTrue(line.startsWith("Combat: DuskRaven (inhabitant) hit Steve (player) 3 times for 16.0 damage"), line);
        assertTrue(line.contains("(largest 7.0)"), line);
        assertTrue(line.contains("with iron_sword [player_attack]"), line);
        assertTrue(line.contains("last at 2.4 blocks"), line);
        assertTrue(line.contains("Steve health now 4.0"), line);
        assertTrue(line.contains("(over 1.5 s)"), line);
        assertEquals(0, ledger.pendingPairs());
    }

    @Test
    void aLongFightIsOneLinePerWindowNotOnePerHit() {
        for (int tick = 0; tick < 1000; tick += 5) { // a hit every quarter second for 50 s, both ways
            ledger.hit(tick, hit(BOT, PLAYER, 1f, 20f), false);
            ledger.hit(tick, hit(PLAYER, BOT, 1f, 20f), false);
            ledger.tick(tick);
        }
        ledger.tick(1100);
        // two directions x one line per 100-tick window over ~1000 ticks, instead of 400 hits
        assertTrue(lines.size() <= 22, "too chatty: " + lines.size());
        assertTrue(lines.size() >= 18, "a fight must still be visible: " + lines.size());
    }

    @Test
    void eachAttackerVictimPairIsSummarisedSeparately() {
        ledger.hit(0, hit(BOT, PLAYER, 5f, 15f), false);
        ledger.hit(1, hit(PLAYER, BOT, 6f, 14f), false);
        ledger.hit(2, hit(ZOMBIE, BOT, 3f, 11f), false);
        ledger.tick(200);
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("Steve (player) hit DuskRaven (inhabitant) 1 time for 6.0 damage")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("zombie (mob) hit DuskRaven (inhabitant)")), lines.toString());
    }

    @Test
    void theSameNameAsPlayerAndMobAreDifferentPairs() {
        Actor mobNamedSteve = new Actor("Steve", Kind.MOB);
        ledger.hit(0, hit(PLAYER, BOT, 1f, 19f), false);
        ledger.hit(0, hit(mobNamedSteve, BOT, 1f, 18f), false);
        ledger.tick(500);
        assertEquals(2, lines.size());
    }

    @Test
    void unattributedDamageIsSilentInNormalModeAndFullyLoggedInDetailMode() {
        Hit fall = new Hit(null, BOT, 3f, 3f, false, "fall", "none", -1, 17f);
        ledger.hit(0, fall, false);
        ledger.tick(1000);
        assertTrue(lines.isEmpty());
        ledger.hit(5, fall, true);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("(no attacker) -> DuskRaven (inhabitant): 3.0 damage via fall with none"), lines.get(0));
    }

    @Test
    void detailModeLogsEveryHitImmediatelyWithAllTheDetail() {
        Hit h = new Hit(PLAYER, BOT, 4.5f, 9.0f, true, "player_attack", "diamond_sword", 3.14, 11.5f);
        ledger.hit(0, h, true);
        ledger.hit(1, h, true);
        assertEquals(2, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("Combat hit: Steve (player) -> DuskRaven (inhabitant): 4.5 damage (9.0 before armor) [blocked]"), line);
        assertTrue(line.contains("via player_attack with diamond_sword at 3.1 blocks"), line);
        assertTrue(line.endsWith("DuskRaven health now 11.5"), line);
        assertEquals(0, ledger.pendingPairs(), "nothing is held back in detail mode");
    }

    @Test
    void theLineBudgetCapsTheOutputAndReportsWhatWasSuppressedOnce() {
        budget = 3;
        for (int i = 0; i < 10; i++) {
            Actor attacker = new Actor("Mob" + i, Kind.MOB);
            ledger.hit(0, hit(attacker, BOT, 1f, 19f), false);
        }
        ledger.tick(100); // all ten windows elapse together
        assertEquals(3, lines.size(), lines.toString());
        ledger.tick(1300); // the minute (started at the first tick seen, 100) rolls over
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(3).contains("7 more combat line(s) were suppressed"), lines.get(3));
        assertTrue(lines.get(3).contains("limit 3 per minute"), lines.get(3));
        ledger.tick(2500);
        assertEquals(4, lines.size(), "the suppression notice is written once");
    }

    @Test
    void theBudgetRefillsEachMinute() {
        budget = 2;
        for (int minute = 0; minute < 3; minute++) {
            long base = minute * 1300L;
            ledger.tick(base);
            ledger.hit(base, hit(new Actor("A", Kind.MOB), BOT, 1f, 19f), false);
            ledger.hit(base, hit(new Actor("B", Kind.MOB), BOT, 1f, 19f), false);
            ledger.tick(base + 100);
        }
        assertEquals(6, lines.size(), lines.toString());
    }

    @Test
    void aKillIsAlwaysLoggedAndBypassesTheBudget() {
        budget = 1;
        ledger.hit(0, hit(PLAYER, BOT, 5f, 1f), false);
        ledger.hit(0, hit(ZOMBIE, new Actor("Alex", Kind.PLAYER), 2f, 18f), false);
        ledger.tick(100); // one summary fits the budget of 1, the other is suppressed
        assertEquals(1, lines.size(), lines.toString());
        int before = lines.size();
        ledger.hit(200, hit(PLAYER, BOT, 5f, 1f), false);
        ledger.death(210, new Death(BOT, PLAYER, "player_attack", "iron_sword", 1.9));
        assertEquals(before + 1, lines.size(), "the pending summary is over budget but the death is not: " + lines);
        assertEquals("Combat: DuskRaven (inhabitant) was killed by Steve (player) (player_attack, iron_sword, 1.9 blocks away)",
                lines.get(lines.size() - 1));
    }

    @Test
    void aDeathFlushesThePendingSummaryOfItsVictimFirstSoTheLogReadsInOrder() {
        ledger.hit(0, hit(PLAYER, BOT, 6f, 2f), false);
        ledger.hit(1, hit(BOT, ZOMBIE, 3f, 10f), false);
        ledger.death(5, new Death(BOT, PLAYER, "player_attack", "iron_sword", 2.0));
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("Steve (player) hit DuskRaven"), lines.get(0));
        assertTrue(lines.get(1).contains("DuskRaven (inhabitant) hit zombie (mob)"), lines.get(1));
        assertTrue(lines.get(2).contains("was killed by Steve"), lines.get(2));
        assertEquals(0, ledger.pendingPairs());
    }

    @Test
    void killsByAnInhabitantAndEnvironmentalDeathsReadNaturally() {
        ledger.death(0, new Death(ZOMBIE, BOT, "player_attack", "netherite_axe", 1.2));
        ledger.death(1, new Death(BOT, null, "fall", "none", -1));
        ledger.death(2, new Death(PLAYER, BOT, "arrow", "bow", 20.04));
        assertEquals("Combat: DuskRaven (inhabitant) killed zombie (mob) (player_attack, netherite_axe, 1.2 blocks away)", lines.get(0));
        assertEquals("Combat: DuskRaven (inhabitant) died (fall)", lines.get(1));
        assertEquals("Combat: DuskRaven (inhabitant) killed Steve (player) (arrow, bow, 20.0 blocks away)", lines.get(2));
    }

    @Test
    void theNumberOfPendingPairsIsBounded() {
        for (int i = 0; i < CombatLedger.MAX_PENDING_PAIRS + 50; i++) {
            ledger.hit(0, hit(new Actor("M" + i, Kind.MOB), BOT, 1f, 19f), false);
        }
        assertEquals(CombatLedger.MAX_PENDING_PAIRS, ledger.pendingPairs());
    }

    @Test
    void flushAllWritesEverythingPendingAtOnce() {
        ledger.hit(0, hit(BOT, PLAYER, 5f, 15f), false);
        ledger.hit(0, hit(ZOMBIE, BOT, 2f, 18f), false);
        ledger.flushAll(10);
        assertEquals(2, lines.size());
        assertEquals(0, ledger.pendingPairs());
    }

    @Test
    void aSecondBurstAfterTheFirstWasWrittenStartsANewWindow() {
        ledger.hit(0, hit(BOT, PLAYER, 5f, 15f), false);
        ledger.tick(100);
        ledger.hit(150, hit(BOT, PLAYER, 5f, 10f), false);
        ledger.tick(249);
        assertEquals(1, lines.size());
        ledger.tick(250);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("1 time for 5.0 damage"), lines.get(1));
    }

    @Test
    void aNewServerWhoseTicksRestartAtZeroLogsImmediatelyAndTheBudgetRefills() {
        budget = 2;
        // Old server, long-running: its budget is used up, then it stops (flushAll rolls the window at tick 500000).
        ledger.tick(500_000);
        ledger.hit(500_000, hit(new Actor("A", Kind.MOB), BOT, 1f, 19f), false);
        ledger.hit(500_000, hit(new Actor("B", Kind.MOB), BOT, 1f, 19f), false);
        ledger.hit(500_000, hit(new Actor("C", Kind.MOB), BOT, 1f, 19f), false);
        ledger.tick(500_100);
        assertEquals(2, lines.size(), lines.toString());
        ledger.flushAll(500_150);
        lines.clear();

        // Same ledger object, new server: ticks are back near zero. It must not stay in the old window.
        for (int i = 0; i < 2; i++) {
            ledger.hit(10, hit(new Actor("N" + i, Kind.MOB), BOT, 1f, 19f), false);
        }
        ledger.tick(110);
        assertEquals(2, lines.size(), "logging works right after the restart: " + lines);

        // ...and the budget refills a minute later on the new clock, not 500000 ticks later.
        ledger.hit(1400, hit(new Actor("M", Kind.MOB), BOT, 1f, 19f), false);
        ledger.tick(1500);
        assertEquals(3, lines.size(), lines.toString());
    }

    @Test
    void resetDropsPendingSummariesAndBudgetStateWithoutWritingAnything() {
        budget = 1;
        ledger.tick(9_000);
        ledger.hit(9_000, hit(new Actor("A", Kind.MOB), BOT, 1f, 19f), false);
        ledger.hit(9_000, hit(new Actor("B", Kind.MOB), BOT, 1f, 19f), false);
        ledger.tick(9_100);
        assertEquals(1, lines.size());
        ledger.hit(9_100, hit(BOT, PLAYER, 5f, 15f), false);
        ledger.reset();
        assertEquals(0, ledger.pendingPairs());
        assertEquals(1, lines.size(), "reset writes nothing, not even the suppression notice");
        ledger.hit(5, hit(BOT, PLAYER, 5f, 15f), false);
        ledger.tick(105);
        assertEquals(2, lines.size(), lines.toString());
    }
}
