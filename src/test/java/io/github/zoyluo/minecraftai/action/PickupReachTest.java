package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** The vanilla pickup box and the time an item takes to land. */
class PickupReachTest {
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
}
