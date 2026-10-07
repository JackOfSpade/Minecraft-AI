package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.MiningController;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeMemoryTest {
    @Test
    void purposeScopedTrailsDoNotCrossContaminateAndUseHorizontalDistance() {
        UUID bot = UUID.randomUUID();
        EpisodeMemory memory = EpisodeMemory.INSTANCE;
        memory.reset(bot);

        memory.recordTrail(bot, "gather", new BlockPos(10, 64, 20));
        assertTrue(memory.nearTrail(bot, "gather", new BlockPos(12, -40, 22), 4.0D));
        assertFalse(memory.nearTrail(bot, "hunt", new BlockPos(10, 64, 20), 4.0D));

        memory.recordTrail(bot, "hunt", new BlockPos(-8, 70, 3));
        assertTrue(memory.nearTrail(bot, "hunt", new BlockPos(-8, 5, 3), 1.0D));
        memory.reset(bot);
        assertFalse(memory.nearTrail(bot, "gather", new BlockPos(10, 64, 20), 4.0D));
        assertFalse(memory.nearTrail(bot, "hunt", new BlockPos(-8, 70, 3), 4.0D));
    }

    @Test
    void aRouteRefusedForWantOfSightKeepsItsTargetOutBrieflyAndAVerdictKeepsItOutLong() {
        for (String reason : new String[] {"navigation_goal_without_observed_stance", "navigation_goal_unobserved",
                "navigation_observed_corridor_unavailable", "navigation_observation_fence_insufficient"}) {
            assertEquals(EpisodeMemory.TTL_SHORT, EpisodeMemory.ttlAfterRouteRefusal(reason), reason);
        }
        for (String reason : new String[] {"pathfinding_failed: GOAL_UNREACHABLE", "policy_refused", "missing_path_support", ""}) {
            assertEquals(EpisodeMemory.TTL_UNREACHABLE, EpisodeMemory.ttlAfterRouteRefusal(reason), reason);
        }
        assertEquals(EpisodeMemory.TTL_UNREACHABLE, EpisodeMemory.ttlAfterRouteRefusal(null));
    }

    @Test
    void aFullTableShedsItsOlderHalfAndSaysSo() {
        UUID bot = UUID.randomUUID();
        EpisodeMemory memory = EpisodeMemory.INSTANCE;
        memory.reset(bot);
        for (int index = 0; index < 128; index++) {
            memory.exclude(bot, new BlockPos(index, 70, 0), 100, 100 + index);
        }
        long before = memory.earlyRevivals();

        memory.exclude(bot, new BlockPos(500, 70, 0), 100, 1000);

        assertTrue(memory.earlyRevivals() > before, "targets were revived before their time");
        assertFalse(memory.isExcluded(bot, new BlockPos(0, 70, 0), 101), "the one that ended first is gone");
        assertTrue(memory.isExcluded(bot, new BlockPos(127, 70, 0), 101), "the one that ends last stays");
        assertTrue(memory.isExcluded(bot, new BlockPos(500, 70, 0), 101));
        memory.reset(bot);
    }

    @Test
    void aTableThatIsNotFullAndOrdinaryExpiryNeverMoveTheCounter() {
        UUID bot = UUID.randomUUID();
        EpisodeMemory memory = EpisodeMemory.INSTANCE;
        memory.reset(bot);
        long before = memory.earlyRevivals();

        memory.exclude(bot, new BlockPos(1, 70, 0), 100, 10);
        assertFalse(memory.isExcluded(bot, new BlockPos(1, 70, 0), 500), "expired on its own");

        assertEquals(before, memory.earlyRevivals(), "an exclusion that ended when it was due is not an early revival");
        memory.reset(bot);
    }

    @Test
    void anExclusionReportsTheLastTickItHoldsAndRevivesTheNextOne() {

        UUID bot = UUID.randomUUID();
        EpisodeMemory memory = EpisodeMemory.INSTANCE;
        BlockPos log = new BlockPos(3, 70, 9);
        memory.reset(bot);
        assertEquals(-1, memory.excludedUntil(bot, log, 50), "nothing is excluded yet");

        memory.exclude(bot, log, 100, EpisodeMemory.TTL_SHORT);

        assertEquals(100 + EpisodeMemory.TTL_SHORT, memory.excludedUntil(bot, log, 400));
        assertTrue(memory.isExcluded(bot, log, 100 + EpisodeMemory.TTL_SHORT), "excluded through its last tick");
        assertFalse(memory.isExcluded(bot, log, 101 + EpisodeMemory.TTL_SHORT), "revived on the next one");
        assertEquals(-1, memory.excludedUntil(bot, log, 101 + EpisodeMemory.TTL_SHORT));
        memory.reset(bot);
    }

    @Test
    void aTargetSeenOnlyThroughBlocksTheBotMayNotBreakStaysOutLongerThanOneThatLeftItsSight() {
        assertEquals(EpisodeMemory.TTL_UNREACHABLE, EpisodeMemory.ttlAfterMiningRefusal(MiningController.TARGET_OBSTRUCTED),
                "every stance that looks through the pane is refused alike");
        assertEquals(EpisodeMemory.TTL_SHORT, EpisodeMemory.ttlAfterMiningRefusal(MiningController.TARGET_NOT_OBSERVED),
                "another stance may show a block that left sight");
        assertEquals(EpisodeMemory.TTL_SHORT, EpisodeMemory.ttlAfterMiningRefusal("timeout"));
        assertEquals(EpisodeMemory.TTL_SHORT, EpisodeMemory.ttlAfterMiningRefusal(null));
        assertTrue(EpisodeMemory.TTL_UNREACHABLE > EpisodeMemory.TTL_SHORT);
    }

    @Test
    void onlyTheTwoTypedSightRefusalsAreSightRefusals() {
        assertTrue(MiningController.isSightRefusal(MiningController.TARGET_OBSTRUCTED));
        assertTrue(MiningController.isSightRefusal(MiningController.TARGET_NOT_OBSERVED));
        assertFalse(MiningController.isSightRefusal("mine_timeout"));
        assertFalse(MiningController.isSightRefusal("out_of_reach"));
        assertFalse(MiningController.isSightRefusal(BlockMiner.MINING_PREEMPTED));
        assertFalse(MiningController.isSightRefusal(null));
    }
}
