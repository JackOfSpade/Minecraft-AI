package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SurfaceColumnMemoTest {
    /** A column whose highest roof block is at {@code roofY}: a spot is on the surface iff y >= roofY. */
    private static boolean truth(int roofY, int y) {
        return y >= roofY;
    }

    @Test
    void answersMatchTheRealCheckForEveryOrderOfQueries() {
        int roofY = 10;
        for (int order = 0; order < 2; order++) {
            SurfaceColumnMemo memo = new SurfaceColumnMemo();
            for (int i = 0; i <= 20; i++) {
                int y = order == 0 ? i : 20 - i;
                assertEquals(truth(roofY, y), memo.isOnSurface(3, y, 4, () -> truth(roofY, y)), "y=" + y);
            }
        }
    }

    @Test
    void anOpenSpotDecidesEverythingAboveItAndARoofedSpotEverythingBelowIt() {
        SurfaceColumnMemo memo = new SurfaceColumnMemo();
        AtomicInteger reads = new AtomicInteger();
        // Lowest first: an open spot means nothing above it needs a read.
        memo.isOnSurface(0, 5, 0, () -> {
            reads.incrementAndGet();
            return true;
        });
        for (int y = 6; y <= 12; y++) {
            memo.isOnSurface(0, y, 0, () -> {
                reads.incrementAndGet();
                return true;
            });
        }
        assertEquals(1, reads.get());

        SurfaceColumnMemo roofed = new SurfaceColumnMemo();
        AtomicInteger roofedReads = new AtomicInteger();
        roofed.isOnSurface(0, 12, 0, () -> {
            roofedReads.incrementAndGet();
            return false;
        });
        for (int y = 5; y <= 11; y++) {
            roofed.isOnSurface(0, y, 0, () -> {
                roofedReads.incrementAndGet();
                return false;
            });
        }
        assertEquals(1, roofedReads.get());
    }

    @Test
    void columnsAreIndependent() {
        SurfaceColumnMemo memo = new SurfaceColumnMemo();
        memo.isOnSurface(1, 5, 1, () -> true);
        AtomicInteger reads = new AtomicInteger();
        memo.isOnSurface(1, 6, 2, () -> {
            reads.incrementAndGet();
            return false;
        });
        memo.isOnSurface(-1, 6, 1, () -> {
            reads.incrementAndGet();
            return false;
        });
        assertEquals(2, reads.get());
        assertEquals(3, memo.computedCount());
    }
}
