package io.github.zoyluo.minecraftai.baritone;

/**
 * What one bot's Baritone instance is allowed to do to the world. Baritone's own switches ({@code Settings#allowBreak},
 * {@code Settings#allowPlace}) are one value for the whole JVM; this is the per-bot version, answered through
 * {@link ServerPlayerContext#allowBreak()} and {@link ServerPlayerContext#allowPlace()} (patch 0014), so the cost model
 * neither plans a move that needs a broken or a placed block nor executes one when the bot is not allowed to.
 *
 * <p>The default of a new instance is {@link #UNRESTRICTED} (Baritone's own default); whoever hands the bot a goal decides.</p>
 *
 * @param allowBreak the bot may break blocks that are in its way (doors, walls, ore)
 * @param allowPlace the bot may place blocks it carries (bridging over gaps, pillaring up)
 */
public record BaritonePolicy(boolean allowBreak, boolean allowPlace) {
    public static final BaritonePolicy UNRESTRICTED = new BaritonePolicy(true, true);
    /** Walks, climbs and opens doors, but leaves the terrain as it is. */
    public static final BaritonePolicy WALK_ONLY = new BaritonePolicy(false, false);
    public static final BaritonePolicy NO_PLACING = new BaritonePolicy(true, false);
    public static final BaritonePolicy NO_BREAKING = new BaritonePolicy(false, true);
}
