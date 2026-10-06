package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;

/**
 * What {@link OreDigTask} knows about the tower its pillars have built: the natural ground that the
 * tower rests on, and the one column it stands in. A pillar's height is counted from natural ground,
 * so that a second pillar built on top of the first cannot outgrow the fall the bot can step down
 * from; the ground has to be told apart from the top of a tower, or the first pillar's ground would
 * go on capping every later pillar of the task, on a hillside above it as well.
 */
final class OrePillarTower {
    /** The column of the most recent pillar's goal, or null before the first one is started. */
    private BlockPos column;
    private int groundY;

    /**
     * The height of the natural ground under {@code feet}: the ground the tower rests on while the bot
     * stands on that tower (in its column, between its ground and the tallest tower there can be), and
     * the height the bot stands at anywhere else, which is itself ground.
     *
     * @param maxHeight the tallest tower the bot can leave again ({@link io.github.zoyluo.minecraftai.action.HarvestCore#maxPillarSupports()})
     */
    int groundUnder(BlockPos feet, int maxHeight) {
        boolean onTower = column != null
                && feet.getX() == column.getX() && feet.getZ() == column.getZ()
                && feet.getY() >= groundY && feet.getY() <= groundY + maxHeight;
        if (!onTower) {
            groundY = feet.getY();
        }
        return groundY;
    }

    /** A pillar that ends on {@code goal} has been started: the tower the bot will stand on is the column of that goal. */
    void rises(BlockPos goal) {
        column = goal.immutable();
    }
}
