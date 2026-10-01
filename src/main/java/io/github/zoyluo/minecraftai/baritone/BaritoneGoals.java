package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The only door through which a goal reaches a bot's Baritone instance: <b>coordinate goals built from targets the mod's own
 * observation layer produced</b>. Baritone can also find targets itself (its mine, get-to-block, farm and explore processes scan
 * every loaded chunk), which would let a strict-survival bot walk to an ore it has never seen; those processes are refused by
 * {@link BaritoneBreakPlacePolicy#allowScanningProcess} and this class never starts them. What it does start is the
 * {@code CustomGoalProcess} with a {@link GoalBlock}, {@link GoalNear}, {@link GoalTwoBlocks}, {@link GoalXZ}, {@link GoalYLevel}
 * or a {@link #composite} of those; any other goal type is refused with {@code goal_type_not_allowed}.
 *
 * <p>Walking to a coordinate needs no proof: the caller chose the coordinate from what it observed, and the path is planned
 * over the loaded chunks like every path of the legacy navigator. Digging to a coordinate ({@link #mineAt}) does: the block
 * there must be observable to the bot right now, be something a bot may break, and the bot must be allowed to break.</p>
 */
public final class BaritoneGoals {
    private BaritoneGoals() {
    }

    /** @param reason null when accepted, otherwise why the goal was not given to Baritone */
    public record Outcome(boolean accepted, String reason) {
        static final Outcome ACCEPTED = new Outcome(true, null);

        static Outcome refused(String reason) {
            return new Outcome(false, reason);
        }
    }

    /** A composite whose members were checked by {@link #composite}: the only composite {@link #setGoal} accepts. */
    private static final class CoordinateComposite extends GoalComposite {
        CoordinateComposite(Goal... goals) {
            super(goals);
        }
    }

    /** Any one of {@code goals} satisfies it; every member must be an allowed coordinate goal (or the call throws). */
    public static Goal composite(Goal... goals) {
        for (Goal goal : goals) {
            if (!allowedType(goal)) {
                throw new IllegalArgumentException("goal_type_not_allowed: " + goal);
            }
        }
        return new CoordinateComposite(goals);
    }

    private static boolean allowedType(Goal goal) {
        return goal instanceof GoalBlock || goal instanceof GoalNear || goal instanceof GoalTwoBlocks || goal instanceof GoalXZ
                || goal instanceof GoalYLevel || goal instanceof CoordinateComposite;
    }

    /** Gives the bot's Baritone a coordinate goal and starts walking there. Server thread. */
    public static Outcome setGoal(AIPlayerEntity bot, Goal goal) {
        if (bot.getActionPack().baritoneControlBlocked()) {
            return Outcome.refused(ActionPack.GUARDED_STEP_FENCE);
        }
        if (goal == null || !allowedType(goal)) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, null, "goal_type_not_allowed", String.valueOf(goal));
            return Outcome.refused("goal_type_not_allowed");
        }
        IBaritone baritone = BaritoneRegistry.INSTANCE.get(bot);
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
        return Outcome.ACCEPTED;
    }

    public static Outcome walkTo(AIPlayerEntity bot, BlockPos pos) {
        return setGoal(bot, new GoalBlock(pos));
    }

    public static Outcome walkNear(AIPlayerEntity bot, BlockPos pos, int range) {
        return setGoal(bot, new GoalNear(pos, range));
    }

    /**
     * Digs to (and into) the block at {@code target}: refused unless the bot can observe that block right now (an ore behind rock
     * that the bot has not seen is not a target), it is a kind of block a bot may break, and the bot may break at all.
     */
    public static Outcome mineAt(AIPlayerEntity bot, BlockPos target) {
        if (bot.getActionPack().baritoneControlBlocked()) {
            return Outcome.refused(ActionPack.GUARDED_STEP_FENCE);
        }
        if (!BaritoneRegistry.INSTANCE.policy(bot).allowBreak()) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, target, "policy_no_break", "mine_at");
            return Outcome.refused("policy_no_break");
        }
        BlockState state = bot.level().getBlockState(target);
        if (state.isAir()) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, target, "nothing_to_mine", "mine_at");
            return Outcome.refused("nothing_to_mine");
        }
        String denial = BaritoneBreakPlacePolicy.breakDenialOf(state.getBlock());
        if (denial != null) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, target, denial, "mine_at");
            return Outcome.refused(denial);
        }
        if (!ObservableWorldQuery.canObserveBlock(bot, target) && !ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, target)) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, target, "target_not_observed", "mine_at");
            return Outcome.refused("target_not_observed");
        }
        return setGoal(bot, new GoalBlock(target));
    }

    /** For callers that build a set of stand cells around an observed target. */
    public static Goal nearAny(List<BlockPos> cells) {
        return composite(cells.stream().map(GoalBlock::new).toArray(Goal[]::new));
    }
}
