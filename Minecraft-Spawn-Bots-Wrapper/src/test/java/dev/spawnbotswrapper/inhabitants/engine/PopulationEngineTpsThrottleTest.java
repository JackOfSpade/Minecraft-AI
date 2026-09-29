package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reactive, TPS-driven despawn described on {@link dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig.TpsThrottle}. */
class PopulationEngineTpsThrottleTest {

    @Test
    void healthyTpsNeverDespawnsAnyone() {
        Rig rig = new Rig();
        rig.cfg.tpsThrottle.checkIntervalTicks = 5;
        rig.tps.millis = 30.0; // well under healthyMillis (60)
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        rig.engine.submit(s);
        rig.run(10);
        assertEquals(3, Rig.count(rig.record(s.key()), BotState.SPAWNED));

        rig.run(50);
        assertEquals(3, Rig.count(rig.record(s.key()), BotState.SPAWNED), "healthy TPS must never despawn anyone");
        assertTrue(rig.bots.removes.isEmpty());
    }

    @Test
    void degradedTpsDespawnsFarthestFromTheNearestRealPlayerFirst() {
        Rig rig = new Rig();
        rig.cfg.tpsThrottle.checkIntervalTicks = 5;
        rig.cfg.tpsThrottle.despawnBatchSize = 2;
        rig.tps.millis = 30.0; // healthy while the structure populates
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        rig.engine.submit(s);
        rig.run(10);
        List<BotRecord> bots = rig.record(s.key()).bots;
        assertEquals(3, bots.size());

        // Closest survives; the two farthest are shed.
        rig.bots.distanceToPlayer.put(FakeBots.key(bots.get(0).name), 50.0);
        rig.bots.distanceToPlayer.put(FakeBots.key(bots.get(1).name), 500.0);
        rig.bots.distanceToPlayer.put(FakeBots.key(bots.get(2).name), 200.0);

        rig.tps.millis = 150.0; // above degradedMillis (100)
        // Stop at the very first shed round: any further ticks could trigger a second round (the two-strongest
        // survivor set shrinks each round) and this test is only about which bots the FIRST round picks.
        rig.runUntil(() -> !rig.bots.removes.isEmpty(), 20);

        assertTrue(rig.bots.removes.contains(bots.get(1).name), "the farthest bot must be shed");
        assertTrue(rig.bots.removes.contains(bots.get(2).name), "the second-farthest bot must be shed");
        assertFalse(rig.bots.removes.contains(bots.get(0).name), "the closest bot must survive");
        assertFalse(rig.bots.online.contains(FakeBots.key(bots.get(1).name)));
        assertFalse(rig.bots.online.contains(FakeBots.key(bots.get(2).name)));

        // A TPS despawn is permanent, exactly like death: never DORMANT, and the record is left SPAWNED
        // (nothing here is ever expected to come back on its own).
        assertEquals(BotState.SPAWNED, bots.get(1).state);
        assertEquals(BotState.SPAWNED, bots.get(2).state);
    }

    /**
     * Deliberately submits the second structure only AFTER the server is healthy again: a structure discovered
     * WHILE capacity is genuinely exhausted is abandoned outright by the (pre-existing, unrelated) capacity-full
     * roll-time rule, not held pending -- that rule is unaffected by this feature and is not what this test is
     * about. What this test is about is the two things that must survive a full despawn-then-recover cycle: the
     * ceiling lifting (so the server can populate again at all) and the shed bots never coming back.
     */
    @Test
    void recoveryNeverRespawnsShedBotsButLetsNewStructuresPopulateNormallyAfterward() {
        Rig rig = new Rig();
        rig.cfg.tpsThrottle.checkIntervalTicks = 5;
        rig.cfg.tpsThrottle.despawnBatchSize = 3; // enough to shed the whole structure
        rig.tps.millis = 30.0;
        StructureSnapshot first = Rig.structure("minecraft:pillager_outpost", 0, 0);
        rig.engine.submit(first);
        rig.run(10);
        List<BotRecord> firstBots = rig.record(first.key()).bots;
        for (BotRecord b : firstBots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0);
        }

        rig.tps.millis = 150.0;
        rig.runUntil(() -> !rig.bots.removes.isEmpty(), 20);
        assertEquals(3, rig.bots.removes.size(), "setup: all three must be shed with despawnBatchSize=3");

        rig.tps.millis = 30.0; // healthy again
        // The governor's own suppressed ceiling only lifts on ITS next scheduled check (mutating rig.tps.millis
        // does not reset it immediately) -- let one check interval pass before the second structure is even
        // discovered, so its roll sees an already-open ceiling instead of the stale suppressed one.
        rig.run(6);
        StructureSnapshot second = Rig.structure("minecraft:mansion", 5, 5);
        rig.engine.submit(second);
        rig.run(10);

        assertEquals(3, Rig.count(rig.record(second.key()), BotState.SPAWNED),
                "a structure discovered after recovery must populate normally");
        for (BotRecord b : firstBots) {
            assertEquals(1, rig.bots.requestsFor(b.name), "a bot shed for TPS must never be re-requested");
            assertEquals(BotState.SPAWNED, b.state);
        }
    }

    /**
     * The bug this locks in: a NUMERIC ceiling has a hole in it. Right after a shed round, live count equals
     * the ceiling exactly (no headroom) -- but the moment anything reduces live count for an unrelated reason
     * (a death, a dormancy cycle), a sliver of capacity opens up, and exploration discovering a brand new
     * structure during that sliver would get bots through, undoing the shed. A flat "still degraded" flag has
     * no such hole: capacity must be hard 0 for as long as the server is degraded, regardless of how much
     * numeric headroom bookkeeping would otherwise suggest.
     */
    @Test
    void degradedTpsHardBlocksEvenAFreshlyDiscoveredStructureRegardlessOfNumericHeadroom() {
        Rig rig = new Rig();
        rig.cfg.tpsThrottle.checkIntervalTicks = 5;
        rig.cfg.tpsThrottle.despawnBatchSize = 3;
        rig.tps.millis = 30.0;
        StructureSnapshot first = Rig.structure("minecraft:pillager_outpost", 0, 0);
        rig.engine.submit(first);
        rig.run(10);
        for (BotRecord b : rig.record(first.key()).bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0);
        }

        rig.tps.millis = 150.0;
        rig.runUntil(() -> !rig.bots.removes.isEmpty(), 20);
        assertEquals(3, rig.bots.removes.size(), "setup: the first structure must be fully shed");

        // Still degraded (tps.millis was never restored to healthy): a structure discovered right now must be
        // abandoned outright, exactly like the existing static-maxLiveBots capacity-full rule -- never left
        // pending for a "someday" that would mean bots appearing somewhere the player already walked away from.
        StructureSnapshot second = Rig.structure("minecraft:mansion", 5, 5);
        rig.engine.submit(second);
        rig.run(10);

        assertTrue(rig.hasStatus(second.key(), StructureStatus.ABANDONED),
                "a structure discovered while still degraded must be abandoned, not queued for later");
        assertEquals(0, rig.record(second.key()).bots.size());
    }

    /**
     * A single bad check sheds one base batch; a persistently bad server should shed increasingly more each
     * further consecutive bad check, converging faster than a flat, fixed-rate response ever could.
     */
    @Test
    void shedBatchEscalatesTheLongerDegradedPersists() {
        Rig rig = new Rig();
        rig.cfg.tpsThrottle.checkIntervalTicks = 5;
        rig.cfg.tpsThrottle.despawnBatchSize = 2;
        rig.tps.millis = 30.0;
        // Five structures x 3 bots = 15 live, comfortably more than 2+4+6=12 needed for three escalating rounds.
        for (int i = 0; i < 5; i++) {
            StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", i * 3, 0);
            rig.engine.submit(s);
        }
        rig.run(10);
        for (Map.Entry<StructureKey, StructureRecord> e : rig.store.nonAbandoned()) {
            for (BotRecord b : e.getValue().bots) {
                rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0);
            }
        }
        assertEquals(15, rig.bots.online.size(), "setup: all fifteen must be live before degrading");

        rig.tps.millis = 150.0;
        rig.runUntil(() -> rig.bots.removes.size() >= 2, 20);
        assertEquals(2, rig.bots.removes.size(), "round 1 (1x base): sheds 2");

        rig.runUntil(() -> rig.bots.removes.size() >= 6, 20);
        assertEquals(6, rig.bots.removes.size(), "round 2 (2x base): sheds 4 more, 6 total");

        rig.runUntil(() -> rig.bots.removes.size() >= 12, 20);
        assertEquals(12, rig.bots.removes.size(), "round 3 (3x base): sheds 6 more, 12 total");
    }

    // ------------------------------------------------------------------ the redesigned trigger (default levels, real timing)

    /** The shipped defaults, exactly as a user gets them: sustain window, dwell, adaptive levels, 100-tick checks. */
    private static Rig rigWithShippedGovernorDefaults() {
        Rig rig = new Rig();
        rig.cfg.tpsThrottle = new InhabitantsConfig.TpsThrottle();
        return rig;
    }

    @Test
    void aServerThatIsMerelyBusyAt53To59MillisNeverShedsAnyoneOrBlocksSpawning() {
        Rig rig = rigWithShippedGovernorDefaults();
        rig.tps.millis = 57.0;
        StructureSnapshot first = Rig.structure("minecraft:pillager_outpost", 0, 0);
        rig.engine.submit(first);
        rig.run(10);
        assertEquals(3, Rig.count(rig.record(first.key()), BotState.SPAWNED));

        double[] wobble = {53.0, 55.5, 59.0, 57.2, 58.8, 54.1};
        for (int i = 0; i < 200; i++) { // ~55 minutes of server time in 100-tick checks
            rig.tps.millis = wobble[i % wobble.length];
            rig.run(100);
        }
        assertTrue(rig.bots.removes.isEmpty(), "normal busy operation must never shed: " + rig.bots.removes);

        StructureSnapshot second = Rig.structure("minecraft:mansion", 5, 5);
        rig.engine.submit(second);
        rig.run(10);
        assertEquals(3, Rig.count(rig.record(second.key()), BotState.SPAWNED), "and spawning is never blocked");
    }

    @Test
    void sustainedOverloadShedsOnlyAfterTheSustainWindowAndRecoveryUnblocksSpawning() {
        Rig rig = rigWithShippedGovernorDefaults();
        rig.tps.millis = 55.0;
        StructureSnapshot first = Rig.structure("minecraft:pillager_outpost", 0, 0);
        rig.engine.submit(first);
        rig.run(10);
        for (BotRecord b : rig.record(first.key()).bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 100.0); // inside the dormancy distance, so only the governor can remove it
        }
        rig.run(1000); // the governor learns this server's baseline (55 ms)
        assertTrue(rig.bots.removes.isEmpty());

        rig.tps.millis = 90.0; // genuinely overloaded
        rig.run(400);
        assertTrue(rig.bots.removes.isEmpty(), "a bad 400 ticks is inside the sustain window (600): nothing is shed yet");
        rig.run(500);
        assertFalse(rig.bots.removes.isEmpty(), "sustained overload sheds");

        // Degraded: a structure discovered now is not populated.
        StructureSnapshot blocked = Rig.structure("minecraft:mansion", 5, 5);
        rig.engine.submit(blocked);
        rig.run(10);
        assertTrue(rig.hasStatus(blocked.key(), StructureStatus.ABANDONED));

        // The load eases to a normal 54 ms: recovery needs the sustain window AND the minimum dwell, then unblocks.
        rig.tps.millis = 54.0;
        rig.run(300);
        StructureSnapshot stillBlocked = Rig.structure("minecraft:igloo", 9, 9);
        rig.engine.submit(stillBlocked);
        rig.run(10);
        assertTrue(rig.hasStatus(stillBlocked.key(), StructureStatus.ABANDONED), "not recovered yet (dwell and window)");
        rig.run(3000);
        StructureSnapshot open = Rig.structure("minecraft:mansion", 13, 13);
        rig.engine.submit(open);
        rig.run(10);
        assertEquals(3, Rig.count(rig.record(open.key()), BotState.SPAWNED), "recovered: spawning works again");
    }

    @Test
    void whileDegradedButBelowTheEnterLevelNothingMoreIsShed() {
        Rig rig = rigWithShippedGovernorDefaults();
        rig.cfg.tpsThrottle.despawnBatchSize = 1;
        rig.tps.millis = 55.0;
        for (int i = 0; i < 4; i++) {
            rig.engine.submit(Rig.structure("minecraft:pillager_outpost", i * 3, 0));
        }
        rig.run(20);
        for (Map.Entry<StructureKey, StructureRecord> e : rig.store.nonAbandoned()) {
            for (BotRecord b : e.getValue().bots) {
                rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 100.0); // inside the dormancy distance, so only the governor can remove it
            }
        }
        rig.run(1000);
        rig.tps.millis = 95.0;
        rig.runUntil(() -> !rig.bots.removes.isEmpty(), 2000);
        int shedAtEntry = rig.bots.removes.size();

        rig.tps.millis = 62.5; // eased: between the exit (60.5) and enter (68.8) levels
        rig.run(2000);
        assertEquals(shedAtEntry, rig.bots.removes.size(), "the dead zone holds the state without shedding more");
    }
}
