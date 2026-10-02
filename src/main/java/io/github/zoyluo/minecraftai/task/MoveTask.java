package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Coordinate movement owned exclusively by the observed-terrain Baritone route.
 *
 * <p>The former raw waypoint and dig-through fallbacks could inspect a heightmap or excavate
 * toward an arbitrary coordinate after a route refusal. A refusal now remains a refusal: the
 * caller can move the bot until it has real line-of-sight evidence, then submit a fresh route.</p>
 */
public final class MoveTask extends AbstractTask {
    private static final double ARRIVE_SQUARED = 2.25D;

    private final BlockPos goal;
    private final double startDistance;
    private BlockPos resolvedGoal;

    public MoveTask(BlockPos start, BlockPos goal) {
        this.goal = goal.immutable();
        this.startDistance = Math.sqrt(start.distSqr(goal));
    }

    public MoveTask(AIPlayerEntity bot, BlockPos goal) {
        this(bot.blockPosition(), goal);
    }

    @Override
    public String name() {
        return "move";
    }

    @Override
    public String describe() {
        return "Moving with Baritone to " + BlockPosText.compact(goal);
    }

    @Override
    public double progress() {
        if (startDistance <= 0.1D || state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, elapsed / Math.max(20.0D, startDistance * 12.0D));
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        int bottom = world.getMinY();
        int top = bottom + world.getHeight();
        if (goal.getY() < bottom || goal.getY() >= top) {
            fail("goal_out_of_world y=" + goal.getY());
            return;
        }
        startBaritoneRoute(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        startBaritoneRoute(bot);
    }

    private void startBaritoneRoute(AIPlayerEntity bot) {
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (result.isFailed()) {
            BotLog.action(bot, "move_baritone_refused",
                    "goal", BlockPosText.compact(goal), "reason", result.reason());
            fail("baritone_route_unavailable:" + result.reason());
            return;
        }
        resolvedGoal = bot.getActionPack().activePathGoal();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (bot.blockPosition().distSqr(currentGoal()) <= ARRIVE_SQUARED) {
            complete();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 5) {
            BotLog.action(bot, "move_baritone_ended_short",
                    "goal", BlockPosText.compact(goal));
            fail("baritone_route_ended_short");
            return;
        }
        if (elapsed > 1200) {
            fail("move_timeout");
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    private BlockPos currentGoal() {
        return resolvedGoal == null ? goal : resolvedGoal;
    }
}
