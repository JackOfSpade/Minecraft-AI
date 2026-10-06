package io.github.zoyluo.minecraftai.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Compatibility helpers for callers that once submitted raw Baritone goals. Strict-survival
 * navigation now enters only through {@link ActionPack}, which creates an immutable observed-cell
 * fence before Baritone can plan or execute. Generic {@link Goal} submission is therefore refused;
 * the coordinate helpers below translate to the audited ActionPack API instead.
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

    /**
     * Raw Baritone-goal submission is retired: its general goal types cannot all be translated to
     * an observation-backed route. Use {@link #walkTo}, {@link #walkNear}, or {@link #mineAt}.
     */
    public static Outcome setGoal(AIPlayerEntity bot, Goal goal) {
        if (bot.getActionPack().baritoneControlBlocked()) {
            return Outcome.refused(ActionPack.GUARDED_STEP_FENCE);
        }
        if (goal == null || !allowedType(goal)) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, null, "goal_type_not_allowed", String.valueOf(goal));
            return Outcome.refused("goal_type_not_allowed");
        }
        BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, null,
                "direct_goal_api_retired", String.valueOf(goal));
        return Outcome.refused("direct_goal_api_retired");
    }

    public static Outcome walkTo(AIPlayerEntity bot, BlockPos pos) {
        return fromRoute(bot.getActionPack().startPathTo(pos));
    }

    public static Outcome walkNear(AIPlayerEntity bot, BlockPos pos, int range) {
        if (!ObservableWorldQuery.canObserveCell(bot, pos)) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, pos,
                    "target_not_observed", "walk_near");
            return Outcome.refused("target_not_observed");
        }
        return fromRoute(bot.getActionPack().startApproachTo(pos, Math.max(0, range), false, false));
    }

    /**
     * Starts a normal, immediately reachable mining action for an exposed target. Navigation to a
     * farther block must be requested through the task-level observed-route API so a successful
     * return cannot falsely imply that Baritone will mine the target after it arrives.
     */
    public static Outcome mineAt(AIPlayerEntity bot, BlockPos target) {
        if (bot.getActionPack().baritoneControlBlocked()) {
            return Outcome.refused(ActionPack.GUARDED_STEP_FENCE);
        }
        if (!BaritoneRegistry.INSTANCE.policy(bot).allowBreak()) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, target, "policy_no_break", "mine_at");
            return Outcome.refused("policy_no_break");
        }
        // The shape-aware observation helpers read the target state to derive its outline. Earn
        // that read with a shape-free current-cell ray first, as this direct public seam has no
        // active route fence yet. A partial block such as a snow layer may not touch a unit-cell
        // face, so its ordinary state-free cell ray is an equally valid preliminary proof. These are
        // the strict (vanilla clip) predicates, like the miner's own gate: a log seen through a leaf is
        // not a target a hand can mine from here.
        if (!(ObservableWorldQuery.canObserveBlockCellFaceStrict(bot, target)
                || ObservableWorldQuery.canObserveCellStrict(bot, target))
                || (!ObservableWorldQuery.canObserveBlockStrict(bot, target)
                && !ObservableWorldQuery.canObserveBlockWithInsetFacesStrict(bot, target))) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, target, "target_not_observed", "mine_at");
            return Outcome.refused("target_not_observed");
        }
        // The observation proof above authorises this one live read of the exact target, not a
        // path scan or any neighbouring terrain read.
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
        if (!HarvestCore.canDirectMine(bot, target)) {
            BaritoneBreakPlacePolicy.refuse(bot, BaritoneRefusals.Op.GOAL, target,
                    "target_out_of_interaction_range", "mine_at");
            return Outcome.refused("target_out_of_interaction_range");
        }
        Direction face = Direction.getApproximateNearest(bot.getEyePosition().subtract(target.getCenter()));
        return fromRoute(bot.getActionPack().startMining(target, face));
    }

    /** For callers that build a set of stand cells around an observed target. */
    public static Goal nearAny(List<BlockPos> cells) {
        return composite(cells.stream().map(GoalBlock::new).toArray(Goal[]::new));
    }

    private static Outcome fromRoute(ActionResult result) {
        return result.isFailed() ? Outcome.refused(result.reason()) : Outcome.ACCEPTED;
    }
}
