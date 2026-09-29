package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

/** One physical start re-snap per origin cell per stall: no snap yo-yo against a walk fallback. */
class SnapRepeatGuardTest {
    private static final BlockPos A = new BlockPos(179, 67, 220);
    private static final BlockPos B = new BlockPos(180, 67, 220);

    @Test
    void firstSnapIsAlwaysAllowed() {
        assertTrue(new SnapRepeatGuard().allows(A, 0));
    }

    @Test
    void secondSnapOutOfTheSameCellInsideTheWindowIsRefused() {
        SnapRepeatGuard guard = new SnapRepeatGuard();
        guard.record(A, 100);
        assertFalse(guard.allows(A, 101), "snap A->B, walked back into A: the next failed search must not snap again");
        assertFalse(guard.allows(A, 100 + SnapRepeatGuard.WINDOW_TICKS));
    }

    @Test
    void aDifferentCellOrALaterStallIsAllowedAgain() {
        SnapRepeatGuard guard = new SnapRepeatGuard();
        guard.record(A, 100);
        assertTrue(guard.allows(B, 101), "a different bad cell is a different stall");
        assertTrue(guard.allows(A, 101 + SnapRepeatGuard.WINDOW_TICKS), "after the window it is a new stall");
        assertTrue(guard.allows(A, 5), "a rewound server tick clock (world reload) must not lock snaps out");
    }
}
