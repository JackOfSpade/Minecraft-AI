package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which way a bot stuck in the dark digs first ({@link DigOutTask}): toward the brightest thing it has actually seen, since a lit or
 * open cell in sight is where the dark ends. It does not know where the sky is. Like a player in a pocket it reacts to what its eyes
 * show it, and when nothing it sees is brighter than the cell it stands in, it digs the way it always did.
 *
 * <p><b>What counts as seen:</b> a cell is seen when {@link ObservableWorldQuery#canObserveCell} holds for it, an unobstructed line
 * from the bot's eyes (or those of the player it is linked to) to the cell, through glass, leaves and water but not through rock or
 * lava, inside the distance it can see ({@link ObservableWorldQuery#visibleRangeBlocks}). Only for such a cell is anything read:
 * the block in it, and when it is open air its light, the brighter of the block light and the sky light the way a lit cave or an
 * opening to the sky looks to the eye. The cells scanned are those at foot and head height around the bot, and a cell in the dark
 * behind a wall is never asked about. A cell holding any fluid does not count as bright, and a direction in which lava is seen is
 * never preferred, so a glow of lava is not followed. The scan is made when the task starts and each time a rise has landed, not
 * every tick.</p>
 *
 * <p>Each cell belongs to the direction its offset from the bot points to most: the brightest cell seen in a direction is that
 * direction's light, and a direction is preferred only when that light is above the light of the bot's own cell.</p>
 */
final class DigOutBearing {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private DigOutBearing() {
    }

    /** The brightest open cell seen in one direction: its light and how far away it is (squared, on the level). */
    record Seen(int light, int distanceSquared) {
        boolean beats(Seen other) {
            return light > other.light || light == other.light && distanceSquared < other.distanceSquared;
        }
    }

    /** The way to dig first, the light that was seen there, and the light of the bot's own cell. */
    record Bearing(Direction direction, int seen, int own) {
    }

    /**
     * The direction that shows the most light above {@code ownLight}, ties to the nearer cell and then to {@code current}, or null
     * when no direction shows more than the bot's own cell (or only a direction in {@code hazard} does).
     */
    static Direction brighter(Direction current, int ownLight, Map<Direction, Seen> seen, Set<Direction> hazard) {
        Direction best = null;
        Seen bestSeen = null;
        for (Direction direction : orderedFrom(current)) {
            Seen candidate = seen.get(direction);
            if (candidate == null || hazard.contains(direction) || candidate.light() <= ownLight) {
                continue;
            }
            if (bestSeen == null || candidate.beats(bestSeen)) {
                best = direction;
                bestSeen = candidate;
            }
        }
        return best;
    }

    private static Direction[] orderedFrom(Direction current) {
        Direction[] ordered = new Direction[HORIZONTAL.length];
        ordered[0] = current;
        int next = 1;
        for (Direction direction : HORIZONTAL) {
            if (direction != current) {
                ordered[next++] = direction;
            }
        }
        return ordered;
    }

    /**
     * What the bot standing in {@code feet} sees of light around it, as a bearing; null when nothing in sight is brighter than the
     * cell it stands in. Every cell is asked of the observation gate before it is read.
     */
    static Bearing toward(AIPlayerEntity bot, ServerLevel world, BlockPos feet, Direction current) {
        int range = ObservableWorldQuery.visibleRangeBlocks(bot);
        int own = lightOf(world, feet);
        Map<Direction, Seen> seen = new EnumMap<>(Direction.class);
        Set<Direction> hazard = EnumSet.noneOf(Direction.class);
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                int flat = dx * dx + dz * dz;
                if (flat == 0 || flat > range * range) {
                    continue;
                }
                Direction direction = Direction.getApproximateNearest(dx, 0.0D, dz);
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos cell = feet.offset(dx, dy, dz);
                    if (!ObservableWorldQuery.canObserveCell(bot, cell)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(cell);
                    if (state.getFluidState().is(FluidTags.LAVA)) {
                        hazard.add(direction);
                    } else if (state.getFluidState().isEmpty() && state.getCollisionShape(world, cell).isEmpty()) {
                        seen.merge(direction, new Seen(lightOf(world, cell), flat),
                                (known, found) -> found.beats(known) ? found : known);
                    }
                }
            }
        }
        Direction toward = brighter(current, own, seen, hazard);
        return toward == null ? null : new Bearing(toward, seen.get(toward).light(), own);
    }

    /** The light of a cell as the eye reads it: the brighter of its block light and its sky light, whatever the hour. */
    private static int lightOf(ServerLevel world, BlockPos cell) {
        return Math.max(world.getBrightness(LightLayer.BLOCK, cell), world.getBrightness(LightLayer.SKY, cell));
    }
}
