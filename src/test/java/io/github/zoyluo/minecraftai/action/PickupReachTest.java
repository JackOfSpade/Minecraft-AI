package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** How high a pillar has to be built for the bot to collect an item that rests beside or above its head. */
class PickupReachTest {
    private static final double WIDTH = 0.6D;
    private static final double HEIGHT = 1.8D;

    /** A resting item entity: a quarter of a block across, standing on a surface at {@code y}. */
    private static AABB item(double x, double y, double z) {
        return new AABB(x - 0.125D, y, z - 0.125D, x + 0.125D, y + 0.25D, z + 0.125D);
    }

    @Test
    void thePickupBoxIsTheBodyGrownByABlockSidewaysAndHalfABlockUpAndDown() {
        AABB box = PickupReach.pickupBox(new AABB(0.0D, 10.0D, 0.0D, 0.6D, 11.8D, 0.6D));

        assertEquals(-1.0D, box.minX, 1.0E-9D);
        assertEquals(9.5D, box.minY, 1.0E-9D);
        assertEquals(-1.0D, box.minZ, 1.0E-9D);
        assertEquals(1.6D, box.maxX, 1.0E-9D);
        assertEquals(12.3D, box.maxY, 1.0E-9D);
        assertEquals(1.6D, box.maxZ, 1.0E-9D);
    }

    @Test
    void anItemOnALedgeBesideTheColumnIsCollectedFromTheLevelWhoseBoxFirstReachesItsSurface() {
        // The column is centred on 0.5; the ledge cell beside it holds the item at x 1.5, resting at y 148.
        OptionalInt level = PickupReach.lowestMeetingLevel(0.5D, 0.5D, 143, 160, WIDTH, HEIGHT, item(1.5D, 148.0D, 0.5D));

        assertEquals(146, level.orElseThrow(),
                "the box reaches 2.3 above the feet: from 145 it ends at 147.3, from 146 at 148.3");
    }

    @Test
    void anItemAtTheFarEdgeOfTheNextCellIsStillCollectedWithoutLeavingTheColumn() {
        OptionalInt level = PickupReach.lowestMeetingLevel(0.5D, 0.5D, 143, 160, WIDTH, HEIGHT, item(1.875D, 148.0D, 0.5D));

        assertTrue(level.isPresent(), "1.375 blocks from the column's centre is inside the 1.3 + 0.125 of the box and the item");
    }

    @Test
    void anItemTwoCellsOffIsOutOfReachAtEveryLevel() {
        OptionalInt level = PickupReach.lowestMeetingLevel(0.5D, 0.5D, 143, 160, WIDTH, HEIGHT, item(2.5D, 148.0D, 0.5D));

        assertTrue(level.isEmpty(), "no height of this column brings an item that far to the side into reach");
    }

    @Test
    void anItemBelowTheStartingLevelIsNeverClimbedTo() {
        OptionalInt level = PickupReach.lowestMeetingLevel(0.5D, 0.5D, 143, 160, WIDTH, HEIGHT, item(1.5D, 138.0D, 0.5D));

        assertTrue(level.isEmpty());
    }

    @Test
    void anItemAlreadyInReachIsFoundAtTheStartingLevel() {
        OptionalInt level = PickupReach.lowestMeetingLevel(0.5D, 0.5D, 143, 160, WIDTH, HEIGHT, item(1.0D, 143.5D, 0.5D));

        assertEquals(143, level.orElseThrow());
    }

    @Test
    void anItemDroppedOntoTheSurfaceItIsOnWaitsOnlyForTheVanillaPickupDelay() {
        assertEquals(PickupReach.PICKUP_DELAY_TICKS, PickupReach.settledTicks(0.0D));
    }

    @Test
    void anItemTakesAsLongToLandAsVanillasGravityAndDragSay() {
        int five = PickupReach.settledTicks(5.0D);
        int twenty = PickupReach.settledTicks(20.0D);

        assertTrue(five >= 15 && five <= 30, "five blocks under 0.04 a tick, after the 0.2 pop upward: " + five);
        assertTrue(twenty >= 25 && twenty <= 45, "twenty blocks: " + twenty);
        assertTrue(twenty > five);
    }

    @Test
    void theLevelsAskedForAreTheOnesTheBotCanAffordToBuild() {
        OptionalInt level = PickupReach.lowestMeetingLevel(0.5D, 0.5D, 143, 145, WIDTH, HEIGHT, item(1.5D, 148.0D, 0.5D));

        assertTrue(level.isEmpty(), "three more levels are needed and only two were offered");
    }
}
