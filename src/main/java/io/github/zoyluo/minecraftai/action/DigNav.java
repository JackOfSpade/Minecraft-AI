package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.task.TerrainProbe;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Dig-navigation: when pure pathfinding (A*) can't get through (blocked by walls / complex terrain /
 * self-dug tunnels / SEARCH_LIMIT), force a path toward the target by "digging one block, walking one block."
 *
 * This is the capability an AI player holding a pickaxe should always have: "if blocked, dig through and
 * walk past it" — it fixes the recurring "stuck with no way out" problem (observed in testing: in jungle
 * biomes plus self-dug tunnels, move-pathfinding hits SEARCH_LIMIT and the bot freezes, only able to rely
 * on the brain manually issuing mine_block one block at a time until it runs out of turns).
 *
 * Pure function {@link #stepToward} (the next block toward the target) + stateful {@link #digStep}
 * (the caller holds the {@link BlockMiner}); runs entirely on the main thread (G2).
 */
public final class DigNav {
    private DigNav() {
    }

    /**
     * Advance one block toward target by digging: clear the facing blocks (feet position + head position) ->
     * once clear, walk into it (descend actively if it's lower, since the bot has no passive gravity).
     * Returns true = progress was made this tick (currently digging or already stepped); false = that
     * direction is blocked (e.g. adjacent lava), the caller should reroute or fail.
     */
    public static boolean digStep(AIPlayerEntity bot, BlockMiner miner, BlockPos target) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos feet = bot.getBlockPos();
        BlockPos step = stepToward(feet, target);
        if (step == null) {
            return false;
        }
        if (adjacentHazardFluid(bot, step)) {
            return false; // The facing block is adjacent to an observed hazardous fluid (lava/water) -> don't dig, hand control back to the caller
        }
        BlockPos solid = TerrainProbe.firstNonAir(world, step, step.up());
        if (solid == null) {
            // The facing block is already air -> step into it (descend if lower, walk if level/higher).
            miner.cancel(bot);
            if (step.getY() < feet.getY()) {
                bot.getActionPack().descendInto(step);
            } else {
                bot.getActionPack().startWalkTo(step.toCenterPos());
            }
            return true;
        }
        BlockMiner.Status st = miner.target() != null && miner.target().equals(solid)
                ? miner.tick(bot)
                : begin(bot, miner, solid);
        // Reactive re-check (same pattern as NeighborEnumerator/PathExecutor breakthrough digging):
        // once solid has just been dug into real air, its neighboring blocks become genuinely visible
        // to the bot's eyes for the first time. The pre-dig adjacentHazardFluid check only rejects
        // "observed" hazards; unobserved hidden neighbors are honestly allowed through — here we add
        // a re-check safety net at the moment of breaking through, instead of blindly continuing to
        // dig/walk in.
        if (st == BlockMiner.Status.DONE && adjacentHazardFluid(bot, solid)) {
            return false; // Breaking through immediately exposes an observed hazardous fluid -> hand back to the caller to reroute, consistent with the pre-check contract
        }
        return st == BlockMiner.Status.DONE || st == BlockMiner.Status.MINING;
    }

    private static BlockMiner.Status begin(AIPlayerEntity bot, BlockMiner miner, BlockPos pos) {
        miner.begin(bot, pos);
        return miner.tick(bot);
    }

    /** The next block toward the target: vertical takes priority (dig down if the target is lower and horizontally aligned), otherwise the larger horizontal component (avoids cutting diagonally through wall corners). */
    public static BlockPos stepToward(BlockPos from, BlockPos target) {
        int dy = target.getY() - from.getY();
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        if (dy < 0 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            return from.down();
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return from.offset(dx > 0 ? Direction.EAST : Direction.WEST);
        }
        if (dz != 0) {
            return from.offset(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        if (dy < 0) {
            return from.down();
        }
        if (dy > 0) {
            return from.up();
        }
        return null;
    }

    // Gated version (replaces the original adjacentLava): pos itself is directly adjacent and visible,
    // so reading it truthfully is legitimate (same handling Standability applies to the next physical
    // step); but pos's six neighbors may still be hidden behind undug solid blocks, and only count as
    // hazardous once the bot has actually observed them — unobserved hidden neighbors are honestly
    // allowed through, left to the reactive re-check at the moment of breaking through (see the DONE
    // branch inside digStep) as a safety net, rather than reading neighbor fluid state ungated like the
    // old version did (which could "see" lava/water through rock that had never been dug).
    public static boolean adjacentHazardFluid(AIPlayerEntity bot, BlockPos pos) {
        FluidState here = bot.getEntityWorld().getFluidState(pos);
        if (here.isIn(FluidTags.LAVA) || here.isIn(FluidTags.WATER)) {
            return true;
        }
        for (Direction d : Direction.values()) {
            if (isObservedHazardFluid(bot, pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isObservedHazardFluid(AIPlayerEntity bot, BlockPos pos) {
        return io.github.zoyluo.minecraftai.mining.OreScan.observeDangerFluid(bot, pos)
                == io.github.zoyluo.minecraftai.mining.OreScan.Observation.OBSERVED_PRESENT;
    }
}
