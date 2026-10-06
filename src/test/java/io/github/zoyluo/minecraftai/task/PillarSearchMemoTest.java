package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A pillar search that found nothing is not repeated from the stance it came back empty at. */
class PillarSearchMemoTest {
    private static final BlockPos STANCE = new BlockPos(17, 123, -50);
    private static final long HINT = new BlockPos(17, 130, -50).asLong();

    @Test
    void nothingIsKnownBeforeASearchCameBackEmpty() {
        assertFalse(new PillarSearchMemo().knownEmpty(STANCE, HINT, 4L, 100));
    }

    @Test
    void anEmptyResultHoldsForTheSameStanceTargetAndTerrain() {
        PillarSearchMemo memo = new PillarSearchMemo();
        memo.rememberEmpty(STANCE, HINT, 4L, 100);

        for (int tick = 100; tick < 400; tick++) {
            assertTrue(memo.knownEmpty(STANCE, HINT, 4L, tick),
                    "a bot that stays put does not search again at tick " + tick);
        }
    }

    @Test
    void movingChangesWhatIsInViewSoTheSearchRunsAgain() {
        PillarSearchMemo memo = new PillarSearchMemo();
        memo.rememberEmpty(STANCE, HINT, 4L, 100);

        assertFalse(memo.knownEmpty(STANCE.east(), HINT, 4L, 101));
        assertFalse(memo.knownEmpty(STANCE.above(), HINT, 4L, 101));
    }

    @Test
    void aBotMadeBlockChangeInvalidatesTheFinding() {
        PillarSearchMemo memo = new PillarSearchMemo();
        memo.rememberEmpty(STANCE, HINT, 4L, 100);

        assertFalse(memo.knownEmpty(STANCE, HINT, 5L, 101),
                "the path-cache version moved: the terrain may be different");
    }

    @Test
    void aDifferentTargetOrExtentIsAnotherQuestion() {
        PillarSearchMemo memo = new PillarSearchMemo();
        memo.rememberEmpty(STANCE, HINT, 4L, 100);

        assertFalse(memo.knownEmpty(STANCE, new BlockPos(18, 130, -50).asLong(), 4L, 101));
    }

    @Test
    void aFindingIsKeptNoLongerThanAnUnreachableVerdict() {
        PillarSearchMemo memo = new PillarSearchMemo();
        memo.rememberEmpty(STANCE, HINT, 4L, 100);

        assertTrue(memo.knownEmpty(STANCE, HINT, 4L, 100 + EpisodeMemory.TTL_UNREACHABLE));
        assertFalse(memo.knownEmpty(STANCE, HINT, 4L, 101 + EpisodeMemory.TTL_UNREACHABLE),
                "a change the bot did not make can only go unnoticed for as long as an unreachable exclusion lasts");
    }

    @Test
    void theFindingEndsWhenTheFirstTargetTheScanSkippedAsExcludedRevives() {
        UUID bot = UUID.randomUUID();
        BlockPos skipped = new BlockPos(20, 128, -50);
        BlockPos other = new BlockPos(22, 129, -49);
        // Excluded at tick 100 for 600 ticks, so it is available again from tick 701; a second one lasts longer.
        EpisodeMemory.INSTANCE.exclude(bot, skipped, 100, EpisodeMemory.TTL_SHORT);
        EpisodeMemory.INSTANCE.exclude(bot, other, 500, EpisodeMemory.TTL_UNREACHABLE);
        try {
            PillarSearchMemo memo = new PillarSearchMemo();
            Predicate<BlockPos> filter = memo.scanFilter(bot, 650);
            assertFalse(filter.test(other), "an excluded target is held back");
            assertFalse(filter.test(skipped), "an excluded target is held back");
            assertTrue(filter.test(new BlockPos(30, 128, -50)), "any other target is a candidate");
            memo.rememberEmpty(STANCE, HINT, 4L, 660);

            // 660 + TTL_UNREACHABLE would keep the finding until 1860, long after the first skipped target is back.
            assertTrue(memo.knownEmpty(STANCE, HINT, 4L, 700));
            assertFalse(memo.knownEmpty(STANCE, HINT, 4L, 701),
                    "the skipped target is searchable again, so the answer may be different");
        } finally {
            EpisodeMemory.INSTANCE.reset(bot);
        }
    }

    @Test
    void aScanThatSkippedNothingKeepsTheFindingForTheFullUnreachableLifetime() {
        UUID bot = UUID.randomUUID();
        PillarSearchMemo memo = new PillarSearchMemo();
        assertTrue(memo.scanFilter(bot, 650).test(new BlockPos(20, 128, -50)));
        memo.rememberEmpty(STANCE, HINT, 4L, 660);

        assertTrue(memo.knownEmpty(STANCE, HINT, 4L, 660 + EpisodeMemory.TTL_UNREACHABLE));
        assertFalse(memo.knownEmpty(STANCE, HINT, 4L, 661 + EpisodeMemory.TTL_UNREACHABLE));
    }

    @Test
    void theExclusionsOfAnEarlierScanDoNotShortenTheFindingOfALaterOne() {
        UUID bot = UUID.randomUUID();
        BlockPos skipped = new BlockPos(20, 128, -50);
        EpisodeMemory.INSTANCE.exclude(bot, skipped, 100, 10);
        try {
            PillarSearchMemo memo = new PillarSearchMemo();
            assertFalse(memo.scanFilter(bot, 105).test(skipped));
            // The next scan starts from a clean slate: by tick 300 nothing is excluded any more.
            assertTrue(memo.scanFilter(bot, 300).test(skipped));
            memo.rememberEmpty(STANCE, HINT, 4L, 310);

            assertTrue(memo.knownEmpty(STANCE, HINT, 4L, 400));
        } finally {
            EpisodeMemory.INSTANCE.reset(bot);
        }
    }

    @Test
    void clearForgetsTheFinding() {
        PillarSearchMemo memo = new PillarSearchMemo();
        memo.rememberEmpty(STANCE, HINT, 4L, 100);
        memo.clear();

        assertFalse(memo.knownEmpty(STANCE, HINT, 4L, 101));
    }
}
