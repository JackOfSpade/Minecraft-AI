package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.List;

/**
 * Port to the geometry logic: where can bots stand inside a structure, and what patrol can each walk.
 * Implemented by {@code DefaultSpawnPlanner}; the engine only sees this interface.
 * Both operations are pure functions of their inputs (given the same {@code rng} state).
 */
public interface SpawnPlanner {

    /** A standing position: feet coordinates (block centre in x/z) and a yaw in degrees. */
    record Position(double x, double y, double z, float yaw) {
    }

    /**
     * @param positions                   distinct valid standing positions found (at most the number requested)
     * @param incompleteBecauseUnloaded   true when fewer positions than requested were found AND some
     *                                    candidate columns were skipped because their chunk is not loaded;
     *                                    the engine then retries later WITHOUT consuming an attempt
     * @param candidatesTried             columns examined, for diagnostics
     */
    record PositionResult(List<Position> positions, boolean incompleteBecauseUnloaded, int candidatesTried) {
        public PositionResult {
            positions = List.copyOf(positions);
        }
    }

    /**
     * Finds up to {@code count} standing positions inside the structure's pieces that are valid (solid
     * floor, headroom, no hazards, not submerged unless allowed, inside the world border), pairwise at
     * least the configured separation apart and also apart from {@code alreadyTaken}.
     */
    PositionResult findPositions(StructureSnapshot structure, BlockProbe probe, int count,
                                 List<Position> alreadyTaken, SplitMix64 rng);

    /**
     * Turns the abstract behaviour requested by a profile (stance, radius, waypoint count) into concrete,
     * verified-walkable waypoints around {@code home}. Waypoints must be pairwise connected by an open,
     * straight, walkable line (PvP BOT steers in straight lines without pathfinding). When the requested
     * patrol cannot be realised the stance degrades gracefully (PATROL -> GUARD_POST -> STAND) and the
     * returned Behavior says so; it never returns unwalkable waypoints.
     */
    BotProfile.Behavior planBehavior(BlockProbe probe, Position home, BotProfile.Behavior requested,
                                     StructureSnapshot structure, SplitMix64 rng);
}
