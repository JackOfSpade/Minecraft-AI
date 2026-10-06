package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void theFindingExpiresWhenAnExcludedTargetCouldHaveRevived() {
        PillarSearchMemo memo = new PillarSearchMemo();
        memo.rememberEmpty(STANCE, HINT, 4L, 100);

        assertTrue(memo.knownEmpty(STANCE, HINT, 4L, 100 + EpisodeMemory.TTL_UNREACHABLE));
        assertFalse(memo.knownEmpty(STANCE, HINT, 4L, 101 + EpisodeMemory.TTL_UNREACHABLE),
                "an exclusion lasts at most TTL_UNREACHABLE ticks, so the answer may differ after it");
    }

    @Test
    void clearForgetsTheFinding() {
        PillarSearchMemo memo = new PillarSearchMemo();
        memo.rememberEmpty(STANCE, HINT, 4L, 100);
        memo.clear();

        assertFalse(memo.knownEmpty(STANCE, HINT, 4L, 101));
    }
}
