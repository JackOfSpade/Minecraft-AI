package io.github.zoyluo.minecraftai.goal;

import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;

import java.util.List;
import java.util.Map;
import net.minecraft.text.Text;

/**
 * World-runtime coverage for the player-visible "start from nothing" tool chain, without an
 * LLM-crafted step list. Needs bootstrapped Minecraft registries (Items/Blocks), so it lives
 * here rather than in src/test.
 */
public final class GoalPlannerChainedCraftingGameTests {
    @GameTest(maxTicks = 20)
    public void twoStonePickaxesFromNothingPlansLogsWoodToolStoneAndFinalCraft(TestContext context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(
                null,
                new Goal.HaveItem(Items.STONE_PICKAXE, 2),
                Map.of(),
                24,
                64,
                false,
                false,
                false,
                true,
                ignored -> false,
                null);

        require(context, plan.success(), "unresolved: " + plan.unresolved());
        int logs = firstIndex(plan.steps(), GoalStep.Kind.GATHER, null, null);
        int table = firstIndex(plan.steps(), GoalStep.Kind.CRAFT, Items.CRAFTING_TABLE, null);
        int woodenPick = firstIndex(plan.steps(), GoalStep.Kind.CRAFT, Items.WOODEN_PICKAXE, null);
        int stone = firstIndex(plan.steps(), GoalStep.Kind.MINE, null, Blocks.STONE);
        int finalCraft = firstIndex(plan.steps(), GoalStep.Kind.CRAFT, Items.STONE_PICKAXE, null);

        require(context, logs >= 0, "the chain must acquire logs");
        require(context, table > logs, "the table must follow its wood prerequisites");
        require(context, woodenPick > table, "the wood pick must exist before mining stone");
        require(context, stone > woodenPick, "stone mining must use the wood pick");
        require(context, finalCraft > stone, "the stone pickaxes must be crafted after cobblestone exists");
        require(context, plan.steps().get(finalCraft).count() >= 2,
                "the final craft must cover the requested two pickaxes");
        context.complete();
    }

    private static int firstIndex(List<GoalStep> steps,
                                  GoalStep.Kind kind,
                                  Item item,
                                  net.minecraft.block.Block block) {
        for (int index = 0; index < steps.size(); index++) {
            GoalStep step = steps.get(index);
            if (step.kind() == kind && step.item() == item && step.block() == block) {
                return index;
            }
        }
        return -1;
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}
