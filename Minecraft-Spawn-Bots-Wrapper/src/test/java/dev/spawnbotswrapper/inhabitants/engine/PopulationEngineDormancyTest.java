package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The always-on, reversible mechanism described on {@link dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig.Dormancy}. */
class PopulationEngineDormancyTest {

    private static Rig freshlyPopulated(StructureSnapshot s, double distanceBlocks, int delayTicks, int scanIntervalTicks) {
        Rig rig = new Rig();
        rig.cfg.dormancy.distanceBlocks = distanceBlocks;
        rig.cfg.dormancy.delayTicks = delayTicks;
        rig.cfg.dormancy.scanIntervalTicks = scanIntervalTicks;
        rig.engine.submit(s);
        rig.run(10);
        assertEquals(3, Rig.count(rig.record(s.key()), BotState.SPAWNED), "setup: the structure must be fully populated");
        return rig;
    }

    @Test
    void closeToARealPlayerNeverGoesDormant() {
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        Rig rig = freshlyPopulated(s, 100.0, 10, 5);
        for (BotRecord b : rig.record(s.key()).bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 10.0);
        }
        rig.run(100);
        assertEquals(3, Rig.count(rig.record(s.key()), BotState.SPAWNED));
        assertTrue(rig.bots.removes.isEmpty());
    }

    @Test
    void farForLessThanTheDelayStaysSpawned() {
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        Rig rig = freshlyPopulated(s, 100.0, 100, 5);
        for (BotRecord b : rig.record(s.key()).bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0);
        }
        rig.run(50); // less than delayTicks (100)
        assertEquals(3, Rig.count(rig.record(s.key()), BotState.SPAWNED));
        assertTrue(rig.bots.removes.isEmpty());
    }

    @Test
    void farForAtLeastTheDelayGoesDormantAndIsRemembered() {
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        Rig rig = freshlyPopulated(s, 100.0, 20, 5);
        List<BotRecord> bots = rig.record(s.key()).bots;
        for (BotRecord b : bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0);
        }
        rig.runUntil(() -> Rig.count(rig.record(s.key()), BotState.DORMANT) == 3, 60);

        for (BotRecord b : bots) {
            assertEquals(BotState.DORMANT, b.state);
            assertTrue(rig.bots.removes.contains(b.name));
            assertTrue(rig.bots.forgets.contains(b.name));
            assertFalse(rig.bots.online.contains(FakeBots.key(b.name)), "the entity must actually be gone");
            // Remembered exactly: nothing here erases its earlier position or profile.
            assertTrue(b.x != 0 || b.y != 0 || b.z != 0);
        }
    }

    @Test
    void movingBackWithinDistanceResetsTheClockInsteadOfAccumulating() {
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        Rig rig = freshlyPopulated(s, 100.0, 20, 5);
        List<BotRecord> bots = rig.record(s.key()).bots;
        for (BotRecord b : bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0);
        }
        rig.run(15); // far, but under the 20-tick delay
        assertEquals(3, Rig.count(rig.record(s.key()), BotState.SPAWNED));

        for (BotRecord b : bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 10.0); // back close
        }
        rig.run(10); // long enough for a scan to see "close" and clear the clock

        for (BotRecord b : bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0); // far again
        }
        rig.run(15); // the same 15 ticks as before; would have crossed 20 if the clock had not reset

        assertEquals(3, Rig.count(rig.record(s.key()), BotState.SPAWNED),
                "moving back within distance must reset the idle clock, not pause it");
    }

    @Test
    void restoringOnRevisitUsesTheExactStoredPositionAndProfileWithoutReroll() {
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        Rig rig = freshlyPopulated(s, 100.0, 20, 5);
        List<BotRecord> bots = rig.record(s.key()).bots;
        BotRecord target = bots.get(0);
        double x = target.x;
        double y = target.y;
        double z = target.z;
        float yaw = target.yaw;
        var originalProfile = target.profile;
        int callsBefore = rig.profiles.calls.size();

        for (BotRecord b : bots) {
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0);
        }
        rig.runUntil(() -> target.state == BotState.DORMANT, 60);
        assertEquals(BotState.DORMANT, target.state);

        // The player wanders back: the chunk loads again, so the structure is detected again.
        rig.engine.submit(s);
        rig.run(5);

        assertEquals(BotState.SPAWNED, target.state, "a dormant bot must be restored, not left behind forever");
        assertEquals(x, target.x);
        assertEquals(y, target.y);
        assertEquals(z, target.z);
        assertEquals(yaw, target.yaw);
        assertSame(originalProfile, target.profile, "the exact same profile must be reused, never regenerated");
        assertEquals(callsBefore, rig.profiles.calls.size(), "restoring a dormant bot must not draw a new profile");

        boolean reappliedSameProfile = rig.bots.applied.stream()
                .anyMatch(a -> a.name().equalsIgnoreCase(target.name) && a.profile() == originalProfile);
        assertTrue(reappliedSameProfile, "the restored bot's exact original profile must be re-applied to the live entity");
    }
}
