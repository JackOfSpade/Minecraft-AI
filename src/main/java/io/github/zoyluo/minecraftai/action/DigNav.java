package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;

/**
 * Historical dig-navigation compatibility surface.
 *
 * <p>Shipping movement is Baritone-only.  The old "dig one block, walk one block" loop could
 * discover a tunnel by excavating it, so {@link #digStep} now delegates its request to the
 * observed-terrain Baritone boundary.  The geometric helpers remain for old checkpoint readers
 * and dormant recovery code, but they do not authorise a physical action.</p>
 */
public final class DigNav {
    private DigNav() {
    }

    /**
     * Requests an observed Baritone route to {@code target}.  This method deliberately preserves
     * its old boolean shape for checkpoint compatibility, but it never inspects or excavates a
     * next cell itself.  A hidden target is rejected by route admission before terrain changes.
     */
    public static boolean digStep(AIPlayerEntity bot, BlockMiner miner, BlockPos target) {
        miner.cancel(bot);
        if (target == null) {
            return false;
        }
        ActionPack pack = bot.getActionPack();
        if (pack.hasBaritoneRoute()) {
            return true;
        }
        ActionResult route = pack.startPathTo(target);
        if (route.isInProgress()) {
            return true;
        }
        BotLog.action(bot, "legacy_dig_nav_refused", "target", target, "reason", route.reason());
        return false;
    }

    /** A refusal caused by live collision occupancy, not an observed terrain/hazard verdict. */
    static boolean isTransientDescentRefusal(String refusal) {
        return "occupied".equals(refusal) || "entity_occupied".equals(refusal);
    }

    /** The next block toward the target: vertical takes priority (dig down if the target is lower and horizontally aligned), otherwise the larger horizontal component (avoids cutting diagonally through wall corners). */
    public static BlockPos stepToward(BlockPos from, BlockPos target) {
        int dy = target.getY() - from.getY();
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        if (dy < 0 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            return from.below();
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return from.relative(dx > 0 ? Direction.EAST : Direction.WEST);
        }
        if (dz != 0) {
            return from.relative(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        if (dy < 0) {
            return from.below();
        }
        if (dy > 0) {
            return from.above();
        }
        return null;
    }

    // Compatibility hazard query: it remains state-free until the requested cell itself is
    // visible, and its neighboring fluid observations are individually visibility-gated.
    public static boolean adjacentHazardFluid(AIPlayerEntity bot, BlockPos pos) {
        if (!ObservableWorldQuery.canObserveCell(bot, pos)) {
            return false;
        }
        FluidState here = bot.level().getFluidState(pos);
        if (here.is(FluidTags.LAVA) || here.is(FluidTags.WATER)) {
            return true;
        }
        for (Direction d : Direction.values()) {
            if (isObservedHazardFluid(bot, pos.relative(d))) {
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
