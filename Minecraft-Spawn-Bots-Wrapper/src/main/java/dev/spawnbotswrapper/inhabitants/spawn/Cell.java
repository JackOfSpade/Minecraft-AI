package dev.spawnbotswrapper.inhabitants.spawn;

/**
 * Classification of ONE block position for the purpose of "can a player stand here", produced by a
 * {@link BlockProbe}. Keeping this a tiny enum lets the position-finding logic be pure and
 * unit-testable while the Minecraft-specific block inspection lives in one glue class.
 */
public enum Cell {
    /** The chunk is not loaded (or the position is outside the world's build height). Never load it to find out. */
    UNLOADED,
    /** Nothing to collide with, no fluid, not harmful: air, grass, flowers, open doors, carpets, ... */
    EMPTY,
    /** Water (any level, including waterlogged blocks). */
    WATER,
    /** Passable but harmful or trapping: lava, fire, cobweb, sweet berry bush, powder snow, wither rose, portals ... */
    HAZARD,
    /** A solid block whose top surface is safe to stand on. */
    SOLID_STANDABLE,
    /** A solid block that must not be stood on or inside: magma, cactus, campfire, ... */
    SOLID_HAZARD,
    /** Solid, but not a valid floor (fence, wall, partial shapes) - it still blocks movement. */
    SOLID_OTHER
}
