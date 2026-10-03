package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.util.List;
import java.util.Map;

/**
 * World-runtime coverage for the player-visible "start from nothing" tool chain, without an
 * LLM-crafted step list. Needs bootstrapped Minecraft registries (Items/Blocks), so it lives
 * here rather than in src/test.
 */
public final class GoalPlannerChainedCraftingGameTests {
    @GameTest(maxTicks = 20)
    public void twoStonePickaxesFromNothingPlansLogsWoodToolStoneAndFinalCraft(GameTestHelper context) {
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
        int logs = firstLogGather(plan.steps());
        int table = firstIndex(plan.steps(), GoalStep.Kind.CRAFT, Items.CRAFTING_TABLE, null);
        int woodenPick = firstIndex(plan.steps(), GoalStep.Kind.CRAFT, Items.WOODEN_PICKAXE, null);
        int cobblestone = firstIndex(plan.steps(), GoalStep.Kind.GATHER, Items.COBBLESTONE, null);
        int finalCraft = firstIndex(plan.steps(), GoalStep.Kind.CRAFT, Items.STONE_PICKAXE, null);

        require(context, logs >= 0, "the chain must acquire logs");
        require(context, table > logs, "the table must follow its wood prerequisites");
        require(context, woodenPick > table, "the wood pick must exist before mining stone");
        require(context, cobblestone > woodenPick,
                "cobblestone gathering must use the wood pick before safely searching for stone");
        require(context, finalCraft > cobblestone,
                "the stone pickaxes must be crafted after cobblestone exists");
        require(context, plan.steps().get(finalCraft).count() >= 2,
                "the final craft must cover the requested two pickaxes");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void compoundStoneToolAllocationsShareProductionThenQueueOnlyNamedHandoffs(
            GameTestHelper context) {
        Goal.Fulfill goal = new Goal.Fulfill(List.of(
                new Goal.Allocation(Items.STONE_PICKAXE, 1, ""),
                new Goal.Allocation(Items.STONE_AXE, 1, ""),
                new Goal.Allocation(Items.STONE_SHOVEL, 1, ""),
                new Goal.Allocation(Items.STONE_HOE, 1, ""),
                new Goal.Allocation(Items.STONE_SWORD, 1, ""),
                new Goal.Allocation(Items.STONE_PICKAXE, 1, "Alex"),
                new Goal.Allocation(Items.STONE_AXE, 1, "Alex"),
                new Goal.Allocation(Items.STONE_SHOVEL, 1, "Alex"),
                new Goal.Allocation(Items.STONE_HOE, 1, "Alex"),
                new Goal.Allocation(Items.STONE_SWORD, 1, "Alex")));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(
                null, goal, Map.of(), 40, 64,
                false, false, false, true, ignored -> false, null);

        require(context, plan.success(), "unresolved: " + plan.unresolved());
        require(context, firstIndex(plan.steps(), GoalStep.Kind.CRAFT,
                        Items.WOODEN_PICKAXE, null) >= 0,
                "one shared wooden pickaxe prerequisite is required before cobblestone");
        for (Item tool : List.of(Items.STONE_PICKAXE, Items.STONE_AXE,
                Items.STONE_SHOVEL, Items.STONE_HOE, Items.STONE_SWORD)) {
            int craft = firstIndex(plan.steps(), GoalStep.Kind.CRAFT, tool, null);
            require(context, craft >= 0 && plan.steps().get(craft).count() >= 2,
                    "shared production must make both copies of " + tool);
        }
        int firstGive = firstKindIndex(plan.steps(), GoalStep.Kind.GIVE_ITEM);
        require(context, firstGive >= 0, "the named player's tool set must be handed off");
        require(context, plan.steps().subList(firstGive, plan.steps().size()).stream()
                        .allMatch(step -> step.kind() == GoalStep.Kind.GIVE_ITEM
                                && "Alex".equals(step.giveRecipient())),
                "handoffs must be queued only after all shared production and target Alex");
        require(context, plan.steps().stream()
                        .filter(step -> step.kind() == GoalStep.Kind.GIVE_ITEM).count() == 5,
                "only the five named allocations are handed off; the bot retains its five tools");
        context.succeed();
    }

    /** Gather steps name the concrete log they collect (e.g. oak_log), so match any log item. */
    private static int firstLogGather(List<GoalStep> steps) {
        for (int index = 0; index < steps.size(); index++) {
            GoalStep step = steps.get(index);
            if (step.kind() == GoalStep.Kind.GATHER && RecipeRegistry.LOGS.contains(step.item())) {
                return index;
            }
        }
        return -1;
    }

    private static int firstIndex(List<GoalStep> steps,
                                  GoalStep.Kind kind,
                                  Item item,
                                  net.minecraft.world.level.block.Block block) {
        for (int index = 0; index < steps.size(); index++) {
            GoalStep step = steps.get(index);
            if (step.kind() == kind && step.item() == item && step.block() == block) {
                return index;
            }
        }
        return -1;
    }

    private static int firstKindIndex(List<GoalStep> steps, GoalStep.Kind kind) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).kind() == kind) {
                return index;
            }
        }
        return -1;
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
