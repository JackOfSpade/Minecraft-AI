package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** How long a block's drop needs to come to rest, by vanilla's item physics. */
class ItemDropSettleTest {
    @Test
    void aDropThatDoesNotFallWaitsOutVanillasPickupDelay() {
        assertEquals(ItemDropSettle.PICKUP_DELAY_TICKS, ItemDropSettle.ticksToSettle(0.0D));
        assertEquals(10, ItemDropSettle.PICKUP_DELAY_TICKS, "ItemEntity.setDefaultPickUpDelay");
        assertEquals(ItemDropSettle.PICKUP_DELAY_TICKS, ItemDropSettle.ticksToSettle(-5.0D), "a floor above the block does not matter");
    }

    @Test
    void aDropFromOneBlockUpLandsAfterAboutThirteenTicks() {
        // v = (v - 0.04) * 0.98 from the 0.2 pop velocity: back at the floor on the thirteenth tick.
        assertEquals(13, ItemDropSettle.ticksToSettle(1.0D));
    }

    @Test
    void aLogInTheCanopySevenBlocksUpNeedsTwoToThreeSeconds() {
        int ticks = ItemDropSettle.ticksToSettle(8.0D);

        assertTrue(ticks >= 20 && ticks <= 30, "settled after " + ticks + " ticks");
    }

    @Test
    void aHigherDropNeedsLongerButNeverMoreThanTheTerminalVelocityAllows() {
        int previous = 0;
        for (double height = 0.0D; height <= 300.0D; height += 5.0D) {
            int ticks = ItemDropSettle.ticksToSettle(height);
            assertTrue(ticks >= previous, "falling from " + height + " blocks took fewer ticks than from less");
            previous = ticks;
        }
        // Terminal velocity is 1.96 blocks a tick: a 300 block drop is ten seconds, not minutes.
        assertTrue(previous < 250, "300 blocks took " + previous + " ticks");
    }

    @Test
    void aNonNumberIsTreatedAsNoFallAtAll() {
        assertEquals(ItemDropSettle.PICKUP_DELAY_TICKS, ItemDropSettle.ticksToSettle(Double.NaN));
        assertEquals(ItemDropSettle.PICKUP_DELAY_TICKS, ItemDropSettle.ticksToSettle(Double.POSITIVE_INFINITY));
    }
}
