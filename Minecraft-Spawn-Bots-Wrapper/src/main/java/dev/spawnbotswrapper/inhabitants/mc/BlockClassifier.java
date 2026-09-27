package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.spawn.Cell;

/**
 * The decision half of block classification: {@link BlockFacts} in, {@link Cell} out. Pure and total.
 * <p>
 * The rules are deliberately expressed through collision geometry and hazard/fluid flags rather than block
 * names, so a modded block is classified by how it behaves: a modded fence is "tall, blocks movement", a
 * modded slab "partial", a modded fluid "harmful until proven otherwise".
 * <ul>
 *   <li>Lava, unknown fluids and hazardous blocks are harmful: {@link Cell#HAZARD} when a player could walk
 *       into them, {@link Cell#SOLID_HAZARD} when they also block movement (cactus, magma, campfire).</li>
 *   <li>Water, including waterlogged blocks, is {@link Cell#WATER}, except a waterlogged block that still has
 *       a real (more-than-thin-cover) collision - a fence or wall, but just as much a slab or stairs, whose
 *       body only fills part of the column - which is {@link Cell#SOLID_OTHER}: a bot standing there would be
 *       pushed out of the solid part by vanilla collision, not resting exactly at the planned position.</li>
 *   <li>A collision no taller than {@link #THIN_COVER_MAX} (carpet, one or two snow layers) is treated as
 *       {@link Cell#EMPTY}: a player simply walks over it, and vanilla mobs spawn on it.</li>
 *   <li>Only a full-height block with a complete top face is {@link Cell#SOLID_STANDABLE}. Slabs and stairs
 *       are not: standing on them would put the feet off the block-grid, and the planner works in whole
 *       blocks. Everything else that collides is {@link Cell#SOLID_OTHER}.</li>
 * </ul>
 */
public final class BlockClassifier {
    /** Tallest collision that still counts as "walk over it" (two sixteenths, e.g. two snow layers). */
    public static final double THIN_COVER_MAX = 0.125 + 1.0E-6;
    /** Tolerance when comparing a collision top with a full block height. */
    public static final double FULL_HEIGHT_TOLERANCE = 1.0E-6;

    private BlockClassifier() {
    }

    public static Cell classify(BlockFacts facts) {
        boolean solid = facts.collides() && facts.collisionTop() > THIN_COVER_MAX;
        boolean harmful = facts.hazard()
                || facts.fluid() == BlockFacts.Fluid.LAVA
                || facts.fluid() == BlockFacts.Fluid.OTHER;
        if (harmful) {
            return solid ? Cell.SOLID_HAZARD : Cell.HAZARD;
        }
        if (facts.fluid() == BlockFacts.Fluid.WATER) {
            // Any real collision - not just a "tall" one - means part of the column is solid: a waterlogged
            // slab or stairs (collisionTop <= 1.0) is just as much an obstruction as a waterlogged fence or
            // wall (collisionTop > 1.0). `solid` already excludes thin covers (carpet, snow layers).
            return solid ? Cell.SOLID_OTHER : Cell.WATER;
        }
        if (!solid) {
            return Cell.EMPTY;
        }
        boolean fullHeight = Math.abs(facts.collisionTop() - 1.0) <= FULL_HEIGHT_TOLERANCE;
        return fullHeight && facts.topFaceFull() ? Cell.SOLID_STANDABLE : Cell.SOLID_OTHER;
    }
}
