package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Powder-snow self-rescue: a bot without leather boots sinks into powder snow, and neither its walker nor
 * its pathfinder (which treats powder snow as dangerous) will move it out again, while the freezing damage
 * keeps ticking. A player keeps jumping, walks toward the nearest firm ground, and punches the powder snow
 * that is in the way (it breaks in a few ticks by hand). Powder snow is the only block this task ever
 * breaks; it never digs solid terrain. Exempt from SurvivalGuard like the other self-rescue tasks.
 */
public final class PowderSnowEscapeTask extends AbstractTask {
    private static final int SEARCH_RADIUS = 5;
    private static final int MAX_ELAPSED = 240;
    /** Ticks without getting closer to the target before the next powder-snow block is broken. */
    private static final int STALL_TICKS = 12;
    /** Tight: the 0.6-wide body still overlaps the pit's edge cell (and stays "in powder snow") until it is well inside the target cell. */
    private static final double ARRIVAL_TOLERANCE = 0.15D;

    private final BlockMiner miner = new BlockMiner();
    private BlockPos target;
    private double bestDistance = Double.MAX_VALUE;
    private int lastProgressTick;
    private int groundedOutTicks;

    @Override
    public String name() {
        return "powder_snow_escape";
    }

    @Override
    public String describe() {
        return "Powder snow escape -> " + (target == null ? "(scan)" : target.toShortString());
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.9D, elapsed / 60.0D);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().setJumping(false);
        bot.getActionPack().stopAll();
    }

    /** Whether the bot's body is inside powder snow right now (the engine flag, or a powder-snow feet/head cell). */
    static boolean isSunkInPowderSnow(AIPlayerEntity bot) {
        if (bot.isInPowderSnow) {
            return true;
        }
        BlockPos feet = bot.blockPosition();
        return bot.level().getBlockState(feet).is(Blocks.POWDER_SNOW)
                || bot.level().getBlockState(feet.above()).is(Blocks.POWDER_SNOW);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            miner.cancel(bot);
            bot.getActionPack().setJumping(false);
            fail("powder_snow_escape_timeout");
            return;
        }
        if (!isSunkInPowderSnow(bot)) {
            miner.cancel(bot);
            bot.getActionPack().setJumping(false);
            bot.getActionPack().stopMovement();
            // Out of the snow; wait for footing so the first step does not drop straight back in.
            if (bot.onGround() || ++groundedOutTicks > 10) {
                BotLog.danger(bot, "powder_snow_escaped", "pos", bot.blockPosition().toShortString(),
                        "hp", (int) bot.getHealth());
                complete();
            }
            return;
        }
        groundedOutTicks = 0;
        // Holding jump rises inside powder snow, the way a player climbs out.
        bot.getActionPack().setJumping(true);
        if (target == null || !isFirmGround(bot, target)) {
            target = findFirmGround(bot).orElse(null);
            bestDistance = Double.MAX_VALUE;
            lastProgressTick = elapsed;
        }
        if (target != null) {
            Vec3 center = Vec3.atBottomCenterOf(target);
            LookAction.lookHorizontallyAt(bot, center);
            if (bot.getActionPack().isWalkToIdle()) {
                bot.getActionPack().startWalkTo(center, ARRIVAL_TOLERANCE);
            }
            double distance = bot.position().distanceTo(center);
            if (distance < bestDistance - 0.15D) {
                bestDistance = distance;
                lastProgressTick = elapsed;
            }
        }
        boolean stalled = elapsed - lastProgressTick >= STALL_TICKS;
        if (miner.target() != null) {
            BlockMiner.Status status = miner.tick(bot);
            if (status != BlockMiner.Status.MINING) {
                lastProgressTick = elapsed;
            }
            return;
        }
        if (stalled) {
            BlockPos toBreak = nextPowderToBreak(bot);
            if (toBreak != null) {
                bot.getActionPack().stopMovement();
                miner.begin(bot, toBreak);
                BotLog.danger(bot, "powder_snow_break", "at", toBreak.toShortString());
            }
            lastProgressTick = elapsed;
        }
    }

    /** The powder-snow block that most directly blocks the way out: the one toward the target, else overhead. */
    private BlockPos nextPowderToBreak(AIPlayerEntity bot) {
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (target != null) {
            int dx = Integer.compare(target.getX(), feet.getX());
            int dz = Integer.compare(target.getZ(), feet.getZ());
            BlockPos[] ahead = {
                    feet.offset(dx, 0, 0), feet.offset(0, 0, dz), feet.offset(dx, 0, dz),
                    feet.offset(dx, 1, 0), feet.offset(0, 1, dz), feet.offset(dx, 1, dz)};
            for (BlockPos pos : ahead) {
                if (!pos.equals(feet) && world.getBlockState(pos).is(Blocks.POWDER_SNOW)
                        && ObservableWorldQuery.canObserveBlock(bot, pos)) {
                    return pos.immutable();
                }
            }
        }
        for (BlockPos pos : new BlockPos[] {feet.above(2), feet.above(), feet}) {
            if (world.getBlockState(pos).is(Blocks.POWDER_SNOW) && ObservableWorldQuery.canObserveBlock(bot, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** Standable dry footing that is not powder snow itself or on top of it. */
    private static boolean isFirmGround(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.level();
        return Standability.isStandableFresh(world, pos)
                && !world.getBlockState(pos).is(Blocks.POWDER_SNOW)
                && !world.getBlockState(pos.above()).is(Blocks.POWDER_SNOW)
                && !world.getBlockState(pos.below()).is(Blocks.POWDER_SNOW);
    }

    private static Optional<BlockPos> findFirmGround(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-SEARCH_RADIUS, -1, -SEARCH_RADIUS),
                origin.offset(SEARCH_RADIUS, 2, SEARCH_RADIUS))) {
            double distance = pos.distSqr(origin);
            if (distance >= bestDistance || !isFirmGround(bot, pos)
                    || !ObservableWorldQuery.canObserveCell(bot, pos)) {
                continue;
            }
            best = pos.immutable();
            bestDistance = distance;
        }
        return Optional.ofNullable(best);
    }
}
