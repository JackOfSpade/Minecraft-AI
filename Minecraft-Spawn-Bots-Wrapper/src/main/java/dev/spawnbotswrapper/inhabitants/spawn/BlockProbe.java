package dev.spawnbotswrapper.inhabitants.spawn;

/**
 * Read-only, NON-LOADING view of the blocks of one dimension.
 * <p>
 * Implementations must never force a chunk to load or generate (that would stall the server, and
 * inside a chunk-load callback could even deadlock); a position in an unloaded chunk answers
 * {@link Cell#UNLOADED}. Server thread only.
 */
public interface BlockProbe {
    Cell cell(int x, int y, int z);

    /** Lowest valid block Y of the dimension (inclusive). */
    int minY();

    /** Highest valid block Y of the dimension (inclusive). */
    int maxY();

    /** Whether the horizontal position is inside the world border. Default: no border. */
    default boolean insideBorder(int x, int z) {
        return true;
    }
}
