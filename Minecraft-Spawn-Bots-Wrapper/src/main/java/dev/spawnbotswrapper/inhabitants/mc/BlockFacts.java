package dev.spawnbotswrapper.inhabitants.mc;

/**
 * The handful of block properties that decide how a block is classified for "can a bot stand here",
 * extracted from a Minecraft {@code BlockState} by {@link McBlockProbe} and turned into a
 * {@link dev.spawnbotswrapper.inhabitants.spawn.Cell} by {@link BlockClassifier}.
 * <p>
 * Keeping the decision a pure function of this record means it can be unit-tested exhaustively without a
 * game, while the Minecraft-specific part shrinks to reading these five values.
 *
 * @param fluid        what fluid, if any, occupies the block (waterlogged blocks report {@link Fluid#WATER})
 * @param hazard       true when merely being inside or on the block hurts, traps or teleports (fire, cobweb,
 *                     berry bush, cactus, magma, campfire, portals, ...)
 * @param collides     true when the collision shape has any volume
 * @param collisionTop height of the collision shape's top above the block's base, 0 when it does not collide
 *                     (1.0 = full block, 1.5 = fence, 0.0625 = carpet)
 * @param topFaceFull  true when the top of the collision shape is a complete 1x1 square at exactly the block's
 *                     top (full blocks, but not slabs, stairs or cauldrons)
 */
public record BlockFacts(Fluid fluid, boolean hazard, boolean collides, double collisionTop, boolean topFaceFull) {

    /** Kind of fluid occupying a block. Modded fluids are neither water nor lava and stay {@link #OTHER}. */
    public enum Fluid {
        NONE,
        WATER,
        LAVA,
        OTHER
    }

    /** A block with no collision, no fluid and no hazard: air, grass, flowers, torches, open doors. */
    public static final BlockFacts NOTHING = new BlockFacts(Fluid.NONE, false, false, 0.0, false);

    public BlockFacts {
        if (fluid == null) {
            throw new IllegalArgumentException("fluid");
        }
        if (!collides) {
            collisionTop = 0.0;
            topFaceFull = false;
        }
    }
}
