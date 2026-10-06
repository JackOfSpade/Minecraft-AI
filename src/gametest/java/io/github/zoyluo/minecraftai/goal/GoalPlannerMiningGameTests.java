package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningChain;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.task.EmergencyShelterTask;
import io.github.zoyluo.minecraftai.task.MiningServiceTask;
import io.github.zoyluo.minecraftai.task.ServicePolicy;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** World-runtime coverage for mining plans that need bootstrapped Minecraft registries. */
public final class GoalPlannerMiningGameTests {
    @GameTest(maxTicks = 20)
    public void mixedLogFuelInventoryOnlyPlansTheFamilyDeficit(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.of(
                Items.OAK_LOG, 1,
                Items.BIRCH_LOG, 2,
                Items.FURNACE, 1,
                Items.WOODEN_SWORD, 1);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.Food(8), prepared, 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        List<GoalStep> logGathers = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.GATHER
                        && (step.item() == Items.OAK_LOG || step.item() == Items.BIRCH_LOG))
                .toList();
        require(context, logGathers.size() == 1 && logGathers.getFirst().count() == 3,
                "3 mixed logs should need only the 3-log family fuel deficit: "
                        + plan.describeSteps());
        int firstHunt = indexOf(plan, step -> step.kind() == GoalStep.Kind.HUNT);
        int lastHunt = lastIndexOf(plan, step -> step.kind() == GoalStep.Kind.HUNT);
        int gather = indexOf(plan, logGathers.getFirst()::equals);
        int cook = indexOf(plan, step -> step.kind() == GoalStep.Kind.COOK_FOOD);
        // Capture one local animal batch, provision fuel while still near its habitat, then allow
        // the second bounded hunt to range farther before cooking.
        require(context, firstHunt >= 0 && firstHunt < gather
                        && gather < lastHunt && lastHunt < cook,
                "surface hunt/gather/cook order regressed: " + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void preparedObsidianExpeditionAcquiresWaterBeforeMining(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.of(
                Items.BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 4,
                Items.STONE_SWORD, 1,
                Items.COBBLESTONE, 76,
                Items.STICK, 40,
                Items.CRAFTING_TABLE, 1,
                Items.COOKED_BEEF, 24,
                Items.TORCH, 8,
                Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), prepared, 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, plan.steps().size() == 3
                        && plan.steps().get(0).kind() == GoalStep.Kind.ACQUIRE_WATER
                        && plan.steps().get(1).isObsidianPreflight()
                        && plan.steps().get(2).kind() == GoalStep.Kind.MAKE_OBSIDIAN,
                "prepared empty-bucket plan must physically acquire water first: " + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void missingStoneSwordHasAnIndependentPreWaterBudget(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.BUCKET, 1),
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.COBBLESTONE, 78),
                Map.entry(Items.STICK, 41),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, 24),
                Map.entry(Items.TORCH, 8),
                Map.entry(Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), prepared, 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        int sword = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.STONE_SWORD && step.count() == 1);
        int acquireWater = indexOf(plan, step -> step.kind() == GoalStep.Kind.ACQUIRE_WATER);
        require(context, sword == 0 && acquireWater == 1,
                "+2 stone/+1 stick weapon margin did not craft before water: "
                        + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.MINE
                                || step.kind() == GoalStep.Kind.GATHER
                                || step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.STICK),
                "stone-sword margin spent the original service reserve: "
                        + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void preparedWaterBucketSkipsDuplicateAcquisition(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.of(
                Items.WATER_BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 4,
                Items.STONE_SWORD, 1,
                Items.COBBLESTONE, 76,
                Items.STICK, 40,
                Items.CRAFTING_TABLE, 1,
                Items.COOKED_BEEF, 24,
                Items.TORCH, 8,
                Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), prepared, 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, plan.steps().size() == 2
                        && plan.steps().getFirst().isObsidianPreflight()
                        && plan.steps().get(1).kind() == GoalStep.Kind.MAKE_OBSIDIAN,
                "prepared water bucket must not be refilled: " + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void preparedObsidianKitStillPlansItsMissingCarriedCraftingTable(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.of(
                Items.WATER_BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 4,
                Items.STONE_SWORD, 1,
                Items.COBBLESTONE, 76,
                Items.STICK, 40,
                Items.COOKED_BEEF, 8,
                Items.TORCH, 8);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), prepared, 64, 64,
                true, false, false, true, ignored -> true, null);

        require(context, plan.success(),
                "prepared kit without table became unresolved: " + plan.unresolved());
        int table = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.CRAFTING_TABLE);
        int preflight = indexOf(plan, GoalStep::isObsidianPreflight);
        require(context, table >= 0 && table < preflight,
                "obsidian readiness did not explicitly restore the carried table: "
                        + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void obsidianReadinessUsesAggregateDiamondPickDurability(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.of(
                Items.WATER_BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 4,
                Items.STONE_SWORD, 1,
                Items.COBBLESTONE, 76,
                Items.STICK, 42,
                Items.CRAFTING_TABLE, 1,
                Items.COOKED_BEEF, 8,
                Items.TORCH, 8);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        GoalPlanner.GoalPlan raw32 = GoalPlanner.planFromState(null, goal, prepared,
                Map.of(Items.DIAMOND_PICKAXE, 31), 64, 64,
                true, false, false, true, ignored -> true, null);
        GoalPlanner.GoalPlan raw33 = GoalPlanner.planFromState(null, goal, prepared,
                Map.of(Items.DIAMOND_PICKAXE, 32), 64, 64,
                true, false, false, true, ignored -> true, null);

        require(context, raw32.success(),
                "raw32 diamond pick could not plan its replacement: " + raw32.unresolved());
        require(context, raw32.steps().stream().anyMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.DIAMOND_PICKAXE),
                "raw32 incorrectly satisfied the 32-break usable durability contract: "
                        + raw32.describeSteps());
        require(context, raw32.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.IRON_PICKAXE),
                "raw32 has enough durability to acquire three replacement diamonds: "
                        + raw32.describeSteps());
        require(context, raw32.steps().stream().anyMatch(step ->
                        isDiamondStep(step) && step.count() == 3),
                "raw32 replacement did not acquire exactly three diamonds: "
                        + raw32.describeSteps());
        require(context, raw33.success(),
                "raw33 exact usable durability was rejected: " + raw33.unresolved());
        require(context, raw33.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.DIAMOND_PICKAXE),
                "raw33 planned an unnecessary replacement diamond pick: "
                        + raw33.describeSteps());
        require(context, raw33.steps().stream().noneMatch(step ->
                        (step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.IRON_PICKAXE)
                                || isDiamondStep(step)),
                "raw33 planned an unnecessary replacement acquisition chain: "
                        + raw33.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void rawTwoDiamondPickUsesLooseDiamondsBeforeAddingAnAcquisitionPick(
            GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> noLooseDiamonds = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.IRON_INGOT, 3),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 44),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, 8),
                Map.entry(Items.TORCH, 8));
        Map<net.minecraft.world.item.Item, Integer> twoLooseDiamonds = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.DIAMOND, 2),
                Map.entry(Items.IRON_INGOT, 3),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 42),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, 8),
                Map.entry(Items.TORCH, 8));
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        Map<net.minecraft.world.item.Item, Integer> rawTwoDurability =
                Map.of(Items.DIAMOND_PICKAXE, 1);
        GoalPlanner.GoalPlan withoutLoose = GoalPlanner.planFromState(null, goal,
                noLooseDiamonds, rawTwoDurability, 64, 64,
                true, false, false, true, ignored -> true, null);
        GoalPlanner.GoalPlan withTwoLoose = GoalPlanner.planFromState(null, goal,
                twoLooseDiamonds, rawTwoDurability, 64, 64,
                true, false, false, true, ignored -> true, null);

        require(context, withoutLoose.success(),
                "raw2 replacement with iron materials became unresolved: "
                        + withoutLoose.unresolved());
        int ironPick = indexOf(withoutLoose, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.IRON_PICKAXE);
        int threeDiamonds = indexOf(withoutLoose,
                step -> isDiamondStep(step) && step.count() == 3);
        int replacement = indexOf(withoutLoose, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.DIAMOND_PICKAXE && step.count() == 1);
        int preflight = indexOf(withoutLoose, GoalStep::isObsidianPreflight);
        require(context, ironPick >= 0 && ironPick < threeDiamonds
                        && threeDiamonds < replacement && replacement < preflight,
                "raw2 must provision iron before mining all three replacement diamonds: "
                        + withoutLoose.describeSteps());
        require(context, withoutLoose.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK),
                "exact 36-stick raw2 kit was replenished twice: "
                        + withoutLoose.describeSteps());

        require(context, withTwoLoose.success(),
                "raw2 plus two loose diamonds became unresolved: "
                        + withTwoLoose.unresolved());
        require(context, withTwoLoose.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.IRON_PICKAXE),
                "one safe diamond break should not manufacture an acquisition iron pick: "
                        + withTwoLoose.describeSteps());
        int oneDiamond = indexOf(withTwoLoose,
                step -> isDiamondStep(step) && step.count() == 1);
        int looseReplacement = indexOf(withTwoLoose,
                step -> step.kind() == GoalStep.Kind.CRAFT
                        && step.item() == Items.DIAMOND_PICKAXE && step.count() == 1);
        require(context, oneDiamond >= 0 && oneDiamond < looseReplacement,
                "two loose diamonds must reduce the acquisition quota to exactly one: "
                        + withTwoLoose.describeSteps());
        require(context, withTwoLoose.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK),
                "exact 34-stick loose-diamond kit was replenished twice: "
                        + withTwoLoose.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void lowAndExactNetheriteDurabilityUseTheSameObsidianContract(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> lowKit = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.NETHERITE_PICKAXE, 1),
                Map.entry(Items.IRON_INGOT, 3),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 44),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, MiningBudget.obsidianExpeditionFoodTarget(32)),
                Map.entry(Items.TORCH, 8),
                Map.entry(Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS));
        Map<net.minecraft.world.item.Item, Integer> exactKit = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.NETHERITE_PICKAXE, 1),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 40),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, MiningBudget.obsidianExpeditionFoodTarget(32)),
                Map.entry(Items.TORCH, 8),
                Map.entry(Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS));
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        GoalPlanner.GoalPlan low = GoalPlanner.planFromState(null, goal, lowKit,
                Map.of(Items.NETHERITE_PICKAXE, 1), 64, 64,
                true, false, false, true, ignored -> true, null);
        GoalPlanner.GoalPlan exact = GoalPlanner.planFromState(null, goal, exactKit,
                Map.of(Items.NETHERITE_PICKAXE, 32), 64, 64,
                true, false, false, true, ignored -> true, null);

        require(context, low.success(), "low netherite replacement failed: " + low.unresolved());
        int ironPick = indexOf(low, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.IRON_PICKAXE);
        int diamondOre = indexOf(low, step -> isDiamondStep(step) && step.count() == 3);
        int diamondPick = indexOf(low, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.DIAMOND_PICKAXE && step.count() == 1);
        require(context, ironPick >= 0 && ironPick < diamondOre && diamondOre < diamondPick,
                "raw2 netherite must use iron to acquire its diamond replacement: "
                        + low.describeSteps());
        require(context, low.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.NETHERITE_PICKAXE),
                "planner attempted an unsupported netherite replacement recipe: "
                        + low.describeSteps());

        require(context, exact.success(), "exact netherite durability failed: " + exact.unresolved());
        require(context, exact.steps().size() == 2
                        && exact.steps().getFirst().isObsidianPreflight()
                        && exact.steps().get(1).kind() == GoalStep.Kind.MAKE_OBSIDIAN,
                "usable32 netherite should directly satisfy the target-tool contract: "
                        + exact.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void mixedDiamondAndNetheriteDurabilityUsesTheAggregateBoundary(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> exactKit = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.NETHERITE_PICKAXE, 1),
                Map.entry(Items.IRON_INGOT, 3),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 40),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, MiningBudget.obsidianExpeditionFoodTarget(32)),
                Map.entry(Items.TORCH, 8),
                Map.entry(Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS));
        Map<net.minecraft.world.item.Item, Integer> belowKit = new java.util.HashMap<>(exactKit);
        belowKit.put(Items.STICK, 42);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        GoalPlanner.GoalPlan exact = GoalPlanner.planFromState(null, goal, exactKit,
                Map.of(Items.DIAMOND_PICKAXE, 15, Items.NETHERITE_PICKAXE, 17),
                64, 64, true, false, false, true, ignored -> true, null);
        GoalPlanner.GoalPlan below = GoalPlanner.planFromState(null, goal, belowKit,
                Map.of(Items.DIAMOND_PICKAXE, 15, Items.NETHERITE_PICKAXE, 16),
                64, 64, true, false, false, true, ignored -> true, null);

        require(context, exact.success(), "mixed usable32 failed: " + exact.unresolved());
        require(context, exact.steps().size() == 2
                        && exact.steps().getFirst().isObsidianPreflight()
                        && exact.steps().get(1).kind() == GoalStep.Kind.MAKE_OBSIDIAN,
                "mixed aggregate usable32 planned an unnecessary tool chain: "
                        + exact.describeSteps());

        require(context, below.success(), "mixed usable31 failed: " + below.unresolved());
        require(context, below.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.IRON_PICKAXE),
                "mixed usable31 can safely mine all three replacement diamonds: "
                        + below.describeSteps());
        int diamondOre = indexOf(below, step -> isDiamondStep(step) && step.count() == 3);
        int diamondPick = indexOf(below, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.DIAMOND_PICKAXE && step.count() == 1);
        require(context, diamondOre >= 0 && diamondOre < diamondPick,
                "mixed usable31 did not add exactly one diamond replacement: "
                        + below.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void undergroundObsidianReplanUsesBirchLogsForMissingStickPlanks(GameTestHelper context) {
        // seed 3000 evidence inventory at the hostile-cave interruption: one stray oak plank must
        // not bind the eight-stick readiness contract to oak when nine carried birch logs can make
        // the complete missing plank quota without any surface acquisition.
        Map<net.minecraft.world.item.Item, Integer> carried = Map.ofEntries(
                Map.entry(Items.BIRCH_LOG, 9),
                Map.entry(Items.OAK_PLANKS, 1),
                Map.entry(Items.STICK, 2),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.STONE_PICKAXE, 3),
                Map.entry(Items.COBBLESTONE, 239),
                Map.entry(Items.COAL, 1),
                Map.entry(Items.TORCH, 2),
                Map.entry(Items.COOKED_MUTTON, 6),
                Map.entry(Items.COOKED_CHICKEN, 2));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), carried, 64, 18,
                false, false, false, false, ignored -> true,
                GoalSnapshotCollector.Context.at(new BlockPos(0, 80, 0)));

        require(context, plan.success(),
                "carried birch stick reserve became unresolved: "
                        + plan.unresolved() + " " + plan.describeSteps());
        require(context, plan.unresolved().stream().noneMatch(
                        reason -> reason.contains("minecraft:oak_log")),
                "planner still bound the plank family to unavailable oak: " + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "birch-backed underground replan emitted surface work: " + plan.describeSteps());
        require(context, plan.steps().stream().anyMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK),
                "missing stick reserve did not retain its craft step: " + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void mixedLogFamiliesAggregateBeforePlanningOneRemainingPlankGap(
            GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> mixedLogs = Map.of(
                Items.OAK_LOG, 2,
                Items.BIRCH_LOG, 6,
                Items.OAK_PLANKS, 2,
                Items.STICK, 2);
        GoalPlanner.GoalPlan exact = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.STICK, 58), mixedLogs, 64, 64,
                false, false, false, ignored -> false, null);

        require(context, exact.success(),
                "mixed-family stick plan became unresolved: " + exact.unresolved());
        require(context, exact.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.GATHER),
                "two carried log families planned redundant gathering: "
                        + exact.describeSteps());
        require(context, exact.steps().stream().anyMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.STICK
                                && step.count() == 56),
                "mixed-family capacity did not retain the final stick craft: "
                        + exact.describeSteps());

        GoalPlanner.GoalPlan oneLogShort = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.STICK, 74), mixedLogs, 64, 64,
                false, false, false, ignored -> false, null);
        List<GoalStep> gathers = oneLogShort.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.GATHER)
                .toList();
        require(context, oneLogShort.success()
                        && gathers.size() == 1
                        && gathers.getFirst().count() == 1
                        && (gathers.getFirst().item() == Items.OAK_LOG
                        || gathers.getFirst().item() == Items.BIRCH_LOG),
                "remaining plank-family gap was not planned exactly once: "
                        + oneLogShort.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void undergroundRawMeatCannotMasqueradeAsMiningFoodReserve(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> carried = Map.of(
                Items.BEEF, 64,
                Items.BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 2,
                Items.COBBLESTONE, 16,
                Items.STICK, 8);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), carried, 64, -40,
                false, false, false, false, ignored -> false, null);

        require(context, !plan.success(), "raw meat must not satisfy the deep-mine reserve");
        require(context, plan.unresolved().stream().anyMatch(
                        reason -> reason.startsWith("deep_mining_food_reserve_depleted")),
                "missing typed reserve failure: " + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "failed underground plan emitted surface work: " + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void netheriteTierDoesNotSilentlyDowngrade(GameTestHelper context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.HavePickaxeTier(ToolTier.NETHERITE));
        require(context, !plan.success(), "netherite acquisition is not implemented and must remain explicit");
        require(context, plan.unresolved().stream().anyMatch(reason -> reason.contains("minecraft:netherite_pickaxe")),
                "wrong tier selected: " + plan.unresolved());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void surfaceDiamondStackAddsExactlyFourteenRawLogShelterReserve(
            GameTestHelper context) {
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        // Keep every non-shelter dependency identical. A raw from-zero comparison lets the
        // carried wood change recipe-family rounding elsewhere in the bootstrap and does not
        // isolate this reserve contract.
        GoalPlanner.GoalPlan empty = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of()),
                64, 64, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan carried = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of(
                        Items.OAK_LOG,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, empty.success() && carried.success(),
                "diamond reserve fixtures did not plan: empty=" + empty.unresolved()
                        + " carried=" + carried.unresolved());
        int emptyGather = plannedLogGatherCount(empty);
        int carriedGather = plannedLogGatherCount(carried);
        require(context, emptyGather - carriedGather
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "diamond raw-log reserve delta was not exactly "
                        + EmergencyShelterTask.MAX_PLACEMENT_BLOCKS
                        + ": empty=" + emptyGather + " carried=" + carriedGather);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void surfaceDiamondStackTopsUpThirteenButNotFourteenShelterBlocks(
            GameTestHelper context) {
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        GoalPlanner.GoalPlan empty = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of()),
                64, 64, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan thirteen = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of(
                        Items.OAK_LOG,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS - 1)),
                64, 64, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan fourteen = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of(
                        Items.OAK_LOG,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, empty.success() && thirteen.success() && fourteen.success(),
                "prepared diamond reserve fixtures did not plan: empty="
                        + empty.unresolved() + " thirteen=" + thirteen.unresolved()
                        + " fourteen=" + fourteen.unresolved());
        int unrelatedWoodBaseline = plannedLogGatherCount(fourteen);
        require(context, plannedLogGatherCount(thirteen) - unrelatedWoodBaseline == 1,
                "thirteen carried shelter blocks did not add exactly one reserve log: "
                        + thirteen.describeSteps());
        require(context, plannedLogGatherCount(empty) - unrelatedWoodBaseline
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "fourteen carried shelter blocks did not eliminate the whole reserve top-up: "
                        + fourteen.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void surfaceDiamondStackDoesNotTreatPlanksAsHardShelterReserve(
            GameTestHelper context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64),
                preparedDiamondContract(Map.of(
                        Items.OAK_PLANKS,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, plan.success(),
                "plank-only reserve fixture did not plan: " + plan.unresolved());
        require(context, plannedLogGatherCount(plan)
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "craft-spendable planks incorrectly satisfied the raw-log reserve: "
                        + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void undergroundDiamondStackResumeDoesNotGatherShelterWood(
            GameTestHelper context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64),
                preparedDiamondContract(Map.of()),
                64, -59, false, false, false, false,
                ignored -> true, null);

        require(context, plan.success(),
                "prepared underground diamond resume did not plan: " + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.GATHER),
                "underground diamond resume attempted surface shelter gathering");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void surfaceObsidianStackAlsoReservesFourteenShelterBlocks(
            GameTestHelper context) {
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        GoalPlanner.GoalPlan empty = GoalPlanner.planFromState(null, goal,
                preparedObsidianContract(Map.of()),
                64, 64, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan carried = GoalPlanner.planFromState(null, goal,
                preparedObsidianContract(Map.of(
                        Items.OAK_LOG,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, empty.success() && carried.success(),
                "obsidian reserve fixtures did not plan: empty=" + empty.unresolved()
                        + " carried=" + carried.unresolved());
        require(context, plannedLogGatherCount(empty)
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "prepared obsidian plan did not reserve fourteen shelter blocks: "
                        + plannedLogGatherCount(empty));
        require(context, plannedLogGatherCount(carried) == 0,
                "prepared obsidian plan borrowed its fourteen carried shelter blocks: "
                        + plannedLogGatherCount(carried));
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void partialSurfaceObsidianStackRetainsThirtyTwoItemShelterContract(
            GameTestHelper context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32),
                preparedObsidianContract(Map.of(Items.OBSIDIAN, 1)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, plan.success(),
                "partial obsidian reserve fixture did not plan: " + plan.unresolved());
        require(context, plannedLogGatherCount(plan)
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "31 remaining obsidian lost the original half-stack shelter contract: "
                        + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void diamondStackReplansExactNetHuntAfterShelterConsumesRawMeat(
            GameTestHelper context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(
                null,
                new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 64),
                Map.of(Items.BEEF, 37),
                64, 64,
                false, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        List<GoalStep> hunts = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.HUNT)
                .toList();
        int cook = indexOf(plan, step -> step.kind() == GoalStep.Kind.COOK_FOOD);
        require(context, hunts.stream().mapToInt(GoalStep::count).sum()
                        == MiningBudget.RARE_BOOTSTRAP_FOOD - 37
                        && hunts.stream().allMatch(step -> step.count() <= 4),
                "37 carried raw meat must leave an exact "
                        + (MiningBudget.RARE_BOOTSTRAP_FOOD - 37)
                        + "-meat hunt deficit: " + plan.describeSteps());
        require(context, cook >= 0
                        && plan.steps().get(cook).count()
                        == MiningBudget.RARE_BOOTSTRAP_FOOD
                        && !plan.steps().get(cook).bestEffort(),
                "carried raw meat must still cook the full "
                        + MiningBudget.RARE_BOOTSTRAP_FOOD + "-unit hard reserve: "
                        + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void runningKitRestoreKeepsOnlyServiceAndProvenDescentTail(GameTestHelper context) {
        Set<net.minecraft.world.level.block.Block> diamonds = Set.of(Blocks.DIAMOND_ORE);
        MiningBudget rareBudget = MiningBudget.forQuota(64, true, ToolTier.IRON);
        int preKitStoneLike = rareBudget.emergencyBlocks()
                + rareBudget.tunnelingPickaxes() * MiningBudget.STONE_PICKAXE_HEAD_COST + 2;
        int preKitSticks = rareBudget.spareToolSticks()
                + rareBudget.tunnelingPickaxes() * MiningBudget.STONE_PICKAXE_STICK_COST;
        GoalStep kit = GoalStep.rareDescentKitService(diamonds, 64);
        GoalStep descent = GoalStep.descendToY(MiningChain.bestY(diamonds));
        GoalStep boundaryZero = GoalStep.rareOreService(diamonds, 0, 64);
        GoalStep firstBatch = GoalStep.mineOre(diamonds, 8);
        GoalStep laterService = GoalStep.rareOreService(diamonds, 8, 64);
        List<GoalStep> fresh = List.of(
                GoalStep.mine(Blocks.STONE, preKitStoneLike),
                GoalStep.craft(Items.STICK, preKitSticks),
                kit,
                descent,
                boundaryZero,
                firstBatch,
                laterService);
        List<GoalStep> tail = GoalExecutor.rareDescentTail(fresh, diamonds);

        require(context, tail.size() == 4
                        && tail.getFirst().equals(descent)
                        && tail.get(1).equals(boundaryZero)
                        && tail.get(2).equals(firstBatch),
                "restore retained bootstrap work between KIT and DESCEND: " + tail);
        require(context, GoalExecutor.rareDescentTail(
                        List.of(GoalStep.mine(Blocks.STONE, preKitStoneLike), kit, firstBatch),
                        diamonds)
                        .isEmpty(),
                "restore accepted a tail without the exact DESCEND->boundary0->batch proof");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void sixteenDiamondsUseExactlyTwoBatchesWithOneCumulativeCheckpoint(GameTestHelper context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 16));

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        requireDiamondExpeditionSequence(context, plan, 2, true);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void preparedAtMineLayerStartsDirectlyWithDiamondBatch(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.of(
                Items.IRON_PICKAXE, 5,
                Items.STONE_PICKAXE, 4,
                Items.IRON_INGOT, 12,
                Items.COBBLESTONE, MiningBudget.RARE_BOOTSTRAP_STONE_LIKE,
                Items.STICK, MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS,
                Items.TORCH, MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES,
                Items.COOKED_BEEF, MiningBudget.RARE_BOOTSTRAP_FOOD,
                Items.CRAFTING_TABLE, 1);
        int occupiedSlots = occupiedInventorySlots(prepared);
        int serviceFreeSlots = ServicePolicy
                .rareOreBatch(64, 0).freeSlotsMin();
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared, 64, -59,
                true, false, false, ignored -> true, null);

        require(context, occupiedSlots + serviceFreeSlots <= Inventory.INVENTORY_SIZE,
                "prepared rare contract does not fit the factual main-inventory capacity: occupied="
                        + occupiedSlots + " required_free=" + serviceFreeSlots);
        require(context, plan.success(), "unresolved=" + plan.unresolved());
        int firstDiamond = indexOf(plan, step -> step.kind() == GoalStep.Kind.MINE_ORE
                && isDiamondStep(step));
        require(context, plan.steps().getFirst().kind() == GoalStep.Kind.MINING_SERVICE
                        && plan.steps().getFirst().isRareOreService()
                        && plan.steps().getFirst().count() == 0
                        && plan.steps().getFirst().rareOreMissionTarget() == 64
                        && plan.steps().getFirst().maintainsTunnelingTools()
                        && firstDiamond == 1,
                "prepared mine-layer inventory must service its live kit before mining: "
                        + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(GoalStep::bestEffort),
                "prepared plan should contain only required diamond batches/checkpoints: " + plan.steps());
        requireDiamondExpeditionSequence(context, plan, 8, true);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void partialDiamondStackRetainsLongMissionServiceIdentity(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.DIAMOND, 55),
                Map.entry(Items.IRON_PICKAXE, 3),
                Map.entry(Items.IRON_INGOT, 6),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.COBBLESTONE, 28),
                Map.entry(Items.STICK, 20),
                Map.entry(Items.TORCH, 96),
                Map.entry(Items.COOKED_BEEF, 4),
                Map.entry(Items.CRAFTING_TABLE, 1));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared, 64, -59,
                false, false, false, false, ignored -> true, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        List<GoalStep> mining = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE_ORE
                        || step.kind() == GoalStep.Kind.MINING_SERVICE)
                .toList();
        require(context, mining.size() == 3
                        && mining.get(0).kind() == GoalStep.Kind.MINE_ORE
                        && mining.get(0).count() == 1
                        && mining.get(1).kind() == GoalStep.Kind.MINING_SERVICE
                        && mining.get(1).isRareOreService()
                        && mining.get(1).count() == 56
                        && mining.get(1).rareOreMissionTarget() == 64
                        && mining.get(1).maintainsTunnelingTools()
                        && mining.get(2).kind() == GoalStep.Kind.MINE_ORE
                        && mining.get(2).count() == 8,
                "55/64 resume lost long-expedition identity: " + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "deep diamond resume emitted surface work: " + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void fourDeliveredDiamondsResumeToTheNextEightBoundary(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.DIAMOND, 4),
                Map.entry(Items.IRON_PICKAXE, 3),
                Map.entry(Items.IRON_INGOT, 6),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.COBBLESTONE, 28),
                Map.entry(Items.STICK, MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS),
                Map.entry(Items.TORCH, MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES),
                Map.entry(Items.COOKED_BEEF, 4),
                Map.entry(Items.CRAFTING_TABLE, 1));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared, 64, -59,
                false, false, false, false, ignored -> true, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        List<GoalStep> mining = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE_ORE
                        || step.kind() == GoalStep.Kind.MINING_SERVICE)
                .toList();
        List<GoalStep> mineSteps = mining.stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE_ORE)
                .toList();
        List<GoalStep> services = mining.stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINING_SERVICE)
                .toList();
        require(context, mining.size() == 15
                        && mining.getFirst().kind() == GoalStep.Kind.MINE_ORE
                        && mining.getFirst().count() == 4
                        && mineSteps.size() == 8
                        && mineSteps.getFirst().count() == 4
                        && mineSteps.subList(1, mineSteps.size()).stream()
                        .allMatch(step -> step.count() == 8)
                        && services.size() == 7
                        && services.getFirst().count() == 8
                        && services.getLast().count() == 56
                        && services.stream().allMatch(step -> step.isRareOreService()
                        && step.rareOreMissionTarget() == 64),
                "4/64 plan did not close the first logical batch before seven full batches: "
                        + plan.describeSteps());
        require(context, mineSteps.stream().mapToInt(GoalStep::count).sum() == 60
                        && mineSteps.getLast().count() == 8,
                "4/64 plan emitted a duplicate four-item tail: " + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void finalDiamondAtMineLayerServicesMissingChannelToolsBeforeOreDig(GameTestHelper context) {
        Map<net.minecraft.world.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.DIAMOND, 63),
                Map.entry(Items.IRON_PICKAXE, 3),
                Map.entry(Items.IRON_INGOT, 6),
                Map.entry(Items.COBBLESTONE, 40),
                Map.entry(Items.STICK, 12),
                Map.entry(Items.COOKED_BEEF, 4),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.TORCH, 40));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared, 64, -59,
                false, false, false, false, ignored -> true, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, plan.steps().size() == 1
                        && plan.steps().getFirst().kind() == GoalStep.Kind.MINE_ORE
                        && plan.steps().getFirst().count() == 1,
                "63/64 resume split its final open batch with a synthetic service: "
                        + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "deep 63/64 resume emitted surface work: " + plan.describeSteps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void emptyInventoryBootstrapIsNotMistakenForOptionalProvisioning(GameTestHelper context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.HaveItem(Items.DIAMOND, 64));
        int ironPickaxe = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.IRON_PICKAXE);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, ironPickaxe >= 0, "missing iron-pickaxe bootstrap: " + plan.describeSteps());
        require(context, !plan.steps().get(ironPickaxe).bestEffort(),
                "required iron-pickaxe bootstrap was marked optional: " + plan.steps());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void directFoodGoalDoesNotInheritExpeditionBestEffortFlag(GameTestHelper context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.Food(4), Map.of(), 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(GoalStep::bestEffort),
                "direct Goal.Food steps must not inherit mining expedition flags: " + plan.steps());
        require(context, plan.steps().stream().anyMatch(step -> step.kind() == GoalStep.Kind.COOK_FOOD),
                "direct Goal.Food must retain its required final cooking step: " + plan.steps());
        context.succeed();
    }

    private static void requireDiamondExpeditionSequence(GameTestHelper context,
                                                         GoalPlanner.GoalPlan plan,
                                                         int expectedBatches,
                                                         boolean expectTunnelingService) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < plan.steps().size(); i++) {
            GoalStep step = plan.steps().get(i);
            if (step.kind() == GoalStep.Kind.MINE_ORE && isDiamondStep(step)) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        require(context, first >= 0 && last >= first, "missing diamond expedition: " + plan.describeSteps());
        List<GoalStep> expedition = plan.steps().subList(first, last + 1);
        int expectedSteps = expectedBatches * 2 - 1;
        require(context, expedition.size() == expectedSteps,
                "expected " + expectedSteps + " alternating expedition steps, got " + expedition);

        for (int batch = 0; batch < expectedBatches; batch++) {
            GoalStep mine = expedition.get(batch * 2);
            require(context, mine.kind() == GoalStep.Kind.MINE_ORE && isDiamondStep(mine) && mine.count() == 8,
                    "batch " + batch + " must be MINE_ORE(8), got " + mine);
            if (batch + 1 < expectedBatches) {
                GoalStep service = expedition.get(batch * 2 + 1);
                int cumulative = (batch + 1) * 8;
                require(context, service.kind() == GoalStep.Kind.MINING_SERVICE
                                && isDiamondStep(service)
                                && service.count() == cumulative
                                && service.isRareOreService()
                                && service.rareOreMissionTarget() == expectedBatches * 8
                                && service.maintainsTunnelingTools() == expectTunnelingService,
                        "checkpoint after batch " + batch + " must be MINING_SERVICE(" + cumulative
                                + "), got " + service);
            }
        }
    }

    private static boolean isDiamondStep(GoalStep step) {
        return step.ores().contains(Blocks.DIAMOND_ORE)
                || step.ores().contains(Blocks.DEEPSLATE_DIAMOND_ORE);
    }

    private static boolean isSurfaceAcquisitionStep(GoalStep step) {
        return step.kind() == GoalStep.Kind.GATHER
                || step.kind() == GoalStep.Kind.HUNT
                || step.kind() == GoalStep.Kind.COOK_FOOD
                || step.kind() == GoalStep.Kind.FARM
                || step.kind() == GoalStep.Kind.MILK_COW;
    }

    private static int roundUpToTorchRecipe(int target) {
        return ((Math.max(0, target) + 3) / 4) * 4;
    }

    private static int plannedLogGatherCount(GoalPlanner.GoalPlan plan) {
        return plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.GATHER
                        && RecipeRegistry.LOGS.contains(step.item()))
                .mapToInt(GoalStep::count)
                .sum();
    }

    private static Map<Item, Integer> preparedDiamondContract(
            Map<Item, Integer> shelterWood) {
        Map<Item, Integer> prepared = new HashMap<>();
        prepared.put(Items.IRON_PICKAXE, 3);
        prepared.put(Items.IRON_INGOT, 6);
        prepared.put(Items.STONE_PICKAXE, 5);
        prepared.put(Items.CHEST, 1);
        prepared.put(Items.CRAFTING_TABLE, 1);
        prepared.put(Items.COBBLESTONE, 512);
        prepared.put(Items.STICK, 512);
        // Keep the planned torch-craft deficit at its pre-margin scale (~150) so the shelter
        // raw-log accounting these fixtures pin stays unchanged by the margin-funded pool.
        prepared.put(Items.TORCH, 512 + MiningBudget.DIAMOND_STACK_EPOCH_MARGIN
                * MiningBudget.RARE_BATCH_TORCH_LIMIT);
        prepared.put(Items.COOKED_BEEF, MiningBudget.RARE_BOOTSTRAP_FOOD);
        prepared.putAll(shelterWood);
        return Map.copyOf(prepared);
    }

    private static Map<Item, Integer> preparedObsidianContract(
            Map<Item, Integer> shelterWood) {
        Map<Item, Integer> prepared = new HashMap<>();
        prepared.put(Items.WATER_BUCKET, 1);
        prepared.put(Items.DIAMOND_PICKAXE, 1);
        prepared.put(Items.STONE_PICKAXE, 4);
        prepared.put(Items.STONE_SWORD, 1);
        prepared.put(Items.CRAFTING_TABLE, 1);
        prepared.put(Items.COBBLESTONE, 512);
        prepared.put(Items.STICK, 512);
        prepared.put(Items.COAL, 64);
        prepared.put(Items.TORCH, 512);
        prepared.put(Items.COOKED_BEEF, 64);
        prepared.putAll(shelterWood);
        return Map.copyOf(prepared);
    }

    private static int occupiedInventorySlots(
            Map<net.minecraft.world.item.Item, Integer> inventory) {
        return inventory.entrySet().stream()
                .mapToInt(entry -> {
                    int count = Math.max(0, entry.getValue());
                    int stackSize = Math.max(1, entry.getKey().getDefaultMaxStackSize());
                    return (count + stackSize - 1) / stackSize;
                })
                .sum();
    }

    private static GoalPlanner.GoalPlan plan(Goal goal) {
        return GoalPlanner.planFromState(null, goal, Map.of(), 64, 64,
                false, false, false, ignored -> false, null);
    }

    private static int indexOf(GoalPlanner.GoalPlan plan,
                               java.util.function.Predicate<GoalStep> predicate) {
        return indexOfFrom(plan, 0, predicate);
    }

    private static int indexOfFrom(GoalPlanner.GoalPlan plan,
                                   int from,
                                   java.util.function.Predicate<GoalStep> predicate) {
        for (int i = Math.max(0, from); i < plan.steps().size(); i++) {
            if (predicate.test(plan.steps().get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(GoalPlanner.GoalPlan plan,
                                   java.util.function.Predicate<GoalStep> predicate) {
        for (int i = plan.steps().size() - 1; i >= 0; i--) {
            if (predicate.test(plan.steps().get(i))) {
                return i;
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
