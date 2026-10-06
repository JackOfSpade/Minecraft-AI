package io.github.zoyluo.minecraftai.goal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/** Registry-backed accounting coverage for fresh public fulfillments. */
public final class GoalFreshFulfillmentGameTests {
    @GameTest(maxTicks = 20)
    public void freshDeliveryRequiresBaselinePlusNewQuotaThenReceiptLeavesOnlyBaseline(
            GameTestHelper context) {
        Goal.Allocation delivery = new Goal.Allocation(Items.OAK_LOG, 32, "Alex");
        Goal.Fulfill goal = new Goal.Fulfill(List.of(delivery), Map.of(Items.OAK_LOG, 32));

        require(context, goal.inventoryRequired(Set.of()).get(Items.OAK_LOG) == 64,
                "fresh delivery must hold baseline 32 plus 32 newly produced logs before Give");
        GoalPlanner.GoalPlan beforeGive = plan(goal, Map.of(Items.OAK_LOG, 32), null);
        require(context, beforeGive.success(), "fresh delivery did not plan: " + beforeGive.unresolved());
        require(context, beforeGive.steps().stream().anyMatch(step -> step.kind() == GoalStep.Kind.GATHER
                        && step.item() == Items.OAK_LOG && step.count() == 32),
                "baseline 32 delivery 32 did not plan an additional exact 32-log gather");
        require(context, beforeGive.steps().stream().anyMatch(step -> step.kind() == GoalStep.Kind.GIVE_ITEM
                        && step.item() == Items.OAK_LOG && step.count() == 32
                        && "Alex".equals(step.giveRecipient())),
                "fresh delivery did not preserve its declared Give step");

        GoalSnapshotCollector.Context receipt = new GoalSnapshotCollector.Context(
                BlockPos.ZERO, Set.of(), null, null, 0, 0, Set.of(delivery));
        require(context, goal.inventoryRequired(Set.of(delivery)).get(Items.OAK_LOG) == 32,
                "completed receipt must leave only the immutable baseline requirement");
        GoalPlanner.GoalPlan replanned = plan(goal, Map.of(Items.OAK_LOG, 32), receipt);
        require(context, replanned.success(), "receipt replan did not succeed: " + replanned.unresolved());
        require(context, replanned.steps().stream().noneMatch(step -> step.kind() == GoalStep.Kind.GATHER
                        || step.kind() == GoalStep.Kind.GIVE_ITEM),
                "receipt replan regathered or redelivered an already committed allocation: " + replanned.steps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void freshRetainedAllocationRequiresGrowthWhileLegacyFulfillmentStaysAbsolute(
            GameTestHelper context) {
        Goal.Fulfill fresh = new Goal.Fulfill(List.of(
                new Goal.Allocation(Items.OAK_LOG, 5, "")), Map.of(Items.OAK_LOG, 10));
        GoalPredicate freshPredicate = new GoalPredicate.Fulfillment(fresh, Set.of());
        GoalSnapshot heldBaseline = snapshot(10);
        GoalSnapshot freshEnough = snapshot(15);
        require(context, freshPredicate.evaluate(heldBaseline).state() == GoalEvaluation.State.UNSATISFIED,
                "the preexisting 10 logs incorrectly satisfied a fresh retained request for 5");
        require(context, freshPredicate.evaluate(freshEnough).state() == GoalEvaluation.State.SATISFIED,
                "15 logs should satisfy baseline 10 plus fresh retained 5");

        Goal.Fulfill legacy = new Goal.Fulfill(List.of(new Goal.Allocation(Items.OAK_LOG, 5, "")));
        GoalPredicate legacyPredicate = new GoalPredicate.Fulfillment(legacy, Set.of());
        require(context, legacyPredicate.evaluate(snapshot(5)).state() == GoalEvaluation.State.SATISFIED,
                "legacy one-argument Fulfill must keep its absolute inventory semantics");
        GoalPlanner.GoalPlan legacyPlan = plan(legacy, Map.of(Items.OAK_LOG, 5), null);
        require(context, legacyPlan.success() && legacyPlan.steps().isEmpty(),
                "legacy fulfillment should still reuse already held inventory: " + legacyPlan.steps());
        context.succeed();
    }

    private static GoalPlanner.GoalPlan plan(Goal goal,
                                             Map<net.minecraft.world.item.Item, Integer> inventory,
                                             GoalSnapshotCollector.Context receipt) {
        return GoalPlanner.planFromState(null, goal, inventory, 40, 64,
                false, false, false, true, ignored -> false, receipt);
    }

    private static GoalSnapshot snapshot(int logs) {
        return new GoalSnapshot(Map.of("minecraft:oak_log", logs), 0, Set.of(),
                Map.of(), Map.of(), 0, java.util.Optional.empty());
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
