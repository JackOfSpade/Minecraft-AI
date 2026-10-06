package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import io.github.zoyluo.minecraftai.task.GiveItemTask;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

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

    /**
     * "Make me a pickaxe" queued behind "make yourself a pickaxe" was snapshotted with the
     * inventory of the moment it was accepted.  Once the first mission has run, its pickaxe sits
     * in the inventory and that old boundary would let the bot hand it over without crafting.
     */
    @GameTest(maxTicks = 20)
    public void promotedFreshRequestIsMeasuredFromTheInventoryItStartsWith(GameTestHelper context) {
        Goal.Allocation delivery = new Goal.Allocation(Items.STONE_PICKAXE, 1, "Alex");
        Goal.Fulfill queued = new Goal.Fulfill(List.of(delivery), Map.of(Items.STONE_PICKAXE, 0));
        Map<Item, Integer> heldWhenPromoted = Map.of(Items.STONE_PICKAXE, 1);

        GoalPlanner.GoalPlan stale = plan(queued, heldWhenPromoted, null);
        require(context, stale.steps().stream().allMatch(step -> step.kind() == GoalStep.Kind.GIVE_ITEM),
                "setup: the request-time baseline should hand over the pickaxe already carried: "
                        + stale.steps());

        Goal.Fulfill promoted = queued.rebaselined(item -> heldWhenPromoted.getOrDefault(item, 0));
        require(context, promoted.initialItemCount(Items.STONE_PICKAXE) == 1
                        && promoted.allocations().equals(queued.allocations()),
                "promotion must move only the baseline, not the manifest: " + promoted);
        GoalPlanner.GoalPlan fresh = plan(promoted, heldWhenPromoted, null);
        require(context, fresh.success(), "promoted request did not plan: " + fresh.unresolved());
        require(context, fresh.steps().stream().anyMatch(step -> step.kind() == GoalStep.Kind.CRAFT
                        && step.item() == Items.STONE_PICKAXE),
                "promoted request must craft its own pickaxe instead of handing over the carried one: "
                        + fresh.steps());
        require(context, promoted.equals(MissionSpec.fromGoal(promoted).toGoal().orElse(null)),
                "the promoted baseline must be what a later checkpoint persists");

        Goal.Fulfill legacy = new Goal.Fulfill(List.of(delivery));
        require(context, legacy.rebaselined(item -> 5) == legacy,
                "a legacy absolute fulfillment has no freshness boundary to move");
        require(context, queued.rebaselined(item -> -3).initialItemCount(Items.STONE_PICKAXE) == 0,
                "a held count can never be negative");
        Goal.Fulfill enormous = new Goal.Fulfill(List.of(
                new Goal.Allocation(Items.STICK, Integer.MAX_VALUE - 5, "Alex")), Map.of(Items.STICK, 0));
        require(context, enormous.rebaselined(item -> 64) == enormous,
                "a boundary that overflows the planner's int counts must not throw on promotion");
        context.succeed();
    }

    /**
     * Baritone only places dirt, cobblestone, netherrack and stone, so a fresh handoff keeps off
     * the dig and pillar approach only while the request still owes one of them: a pickaxe
     * delivery to a player on a ledge must stay reachable, but a bread delivery must not spend
     * the cobblestone that is still reserved for a later delivery.
     */
    @GameTest(maxTicks = 20)
    public void freshHandoffKeepsOffTheRouteOnlyWhileAStackOfSupportBlocksIsStillOwed(
            GameTestHelper context) {
        Goal.Allocation bread = new Goal.Allocation(Items.BREAD, 3, "Alex");
        Goal.Allocation cobblestone = new Goal.Allocation(Items.COBBLESTONE, 32, "Alex");
        Goal.Fulfill mixed = new Goal.Fulfill(List.of(bread, cobblestone),
                Map.of(Items.BREAD, 0, Items.COBBLESTONE, 0));
        require(context, GoalExecutor.keepsDeliveryOffRoute(mixed, Set.of()),
                "handing over bread must not spend the cobblestone still reserved for Alex");
        require(context, !GoalExecutor.keepsDeliveryOffRoute(mixed, Set.of(cobblestone)),
                "once the cobblestone is delivered nothing reserved can be spent as scaffold");

        Goal.Fulfill retainedDirt = new Goal.Fulfill(List.of(
                bread, new Goal.Allocation(Items.DIRT, 16, "")), Map.of(Items.BREAD, 0, Items.DIRT, 0));
        require(context, GoalExecutor.keepsDeliveryOffRoute(retainedDirt, Set.of(bread)),
                "the bot's own retained dirt is reserved output too");

        Goal.Fulfill pickaxe = new Goal.Fulfill(List.of(
                new Goal.Allocation(Items.IRON_PICKAXE, 1, "Alex"),
                new Goal.Allocation(Items.OAK_LOG, 8, "Alex")),
                Map.of(Items.IRON_PICKAXE, 0, Items.OAK_LOG, 0));
        require(context, !GoalExecutor.keepsDeliveryOffRoute(pickaxe, Set.of()),
                "a tool and logs can never be spent as scaffold, so the ordinary approach stays");

        Goal.Fulfill legacy = new Goal.Fulfill(List.of(cobblestone));
        require(context, !GoalExecutor.keepsDeliveryOffRoute(legacy, Set.of()),
                "only a fresh request owns the reserved-output boundary");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void lockedWornPieceIsATerminalDeliveryFailure(GameTestHelper context) {
        GoalStep give = GoalStep.give(Items.IRON_CHESTPLATE, 1, "Alex");
        require(context, GoalExecutor.isLockedWornPieceFailure(give, GiveItemTask.WORN_PIECE_LOCKED),
                "an identical Give cannot succeed after the piece proved impossible to take off");
        require(context, !GoalExecutor.isLockedWornPieceFailure(give, "give_item_player_not_found")
                        && !GoalExecutor.isLockedWornPieceFailure(
                        GoalStep.gather(Items.OAK_LOG, 1), GiveItemTask.WORN_PIECE_LOCKED),
                "only that typed reason on a Give step ends the mission");
        context.succeed();
    }

    @GameTest(maxTicks = 120)
    public void queuedFreshRequestIsRebaselinedWhenTheMissionAheadFinishes(GameTestHelper context) {
        AIPlayerEntity bot = spawnBot(context, "FreshQueueGT");
        Goal.Fulfill queued = freshSticks(Map.of(Items.STICK, 0));
        requireOrCleanup(context, bot, GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.SWEET_BERRIES, 1)),
                "setup: the first mission was not accepted");
        requireOrCleanup(context, bot, GoalExecutor.INSTANCE.submit(bot, queued)
                        && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 1,
                "setup: the fresh request was not queued behind it");

        context.runAtTickTime(5, () -> {
            // The first mission finishes, and the sticks and planks it left behind are all the
            // second one will find when it starts.
            InventoryAction.giveItem(bot, new ItemStack(Items.SWEET_BERRIES, 1));
            InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
            InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 4));
        });
        // The promoted mission is short-lived here (nobody named Alex exists to take the sticks),
        // so watch every tick for the window in which it is the active one.
        boolean[] done = {false};
        String[] lastSeen = {"nothing was observed"};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            Goal.Fulfill active = activeFulfill(bot);
            List<String> steps = GoalExecutor.INSTANCE.activeGoalSteps(bot);
            lastSeen[0] = "active=" + active + " steps=" + steps;
            if (active != null && GoalExecutor.INSTANCE.isActiveGoal(bot, queued)
                    && active.initialItemCount(Items.STICK) == 2
                    && steps.stream().anyMatch(label -> label.startsWith("Craft"))) {
                done[0] = true;
                despawn(bot);
                context.succeed();
            }
        });
        context.runAtTickTime(110, () -> {
            if (!done[0]) {
                despawn(bot);
                context.fail(Component.nullToEmpty("the queued request was not promoted with a fresh "
                        + "baseline of 2 sticks and a plan that crafts its own: " + lastSeen[0]));
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void restoredActiveFreshMissionKeepsItsOwnBaselineButARestoredQueueEntryStartsFresh(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnBot(context, "FreshRestoreGT");
        Goal.Fulfill fresh = freshSticks(Map.of(Items.STICK, 0));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 4));
        requireOrCleanup(context, bot, GoalExecutor.INSTANCE.submit(bot, fresh),
                "setup: the fresh mission was not accepted");
        MissionRuntimeRecord persisted = GoalExecutor.INSTANCE.captureRuntime(bot);
        requireOrCleanup(context, bot, persisted.active() != null, "setup: no active mission was captured");
        GoalExecutor.INSTANCE.cancelAll(bot);

        // Sticks arrive while the server is down; the persisted ACTIVE mission is the same
        // mission afterwards and must still be measured from its own baseline.
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 3));
        GoalExecutor.INSTANCE.restoreRuntime(bot, persisted);
        Goal.Fulfill restored = activeFulfill(bot);
        requireOrCleanup(context, bot, restored != null && restored.initialItemCount(Items.STICK) == 0,
                "a restored active mission was re-baselined: " + restored);
        GoalExecutor.INSTANCE.cancelAll(bot);

        // With no mission ahead of it, a restored queue entry is promoted right away and so
        // starts from what the bot holds now.
        MissionRuntimeRecord queueOnly = new MissionRuntimeRecord(null,
                List.of(persisted.active().spec()), false);
        GoalExecutor.INSTANCE.restoreRuntime(bot, queueOnly);
        Goal.Fulfill promoted = activeFulfill(bot);
        requireOrCleanup(context, bot, promoted != null && promoted.initialItemCount(Items.STICK) == 3,
                "a promoted queue entry kept its stale baseline: " + promoted);
        despawn(bot);
        context.succeed();
    }

    /** One stick delivery to a named player; the bot crafts sticks from planks, so no world resources are needed. */
    private static Goal.Fulfill freshSticks(Map<Item, Integer> baseline) {
        return new Goal.Fulfill(List.of(new Goal.Allocation(Items.STICK, 1, "Alex")), baseline);
    }

    private static Goal.Fulfill activeFulfill(AIPlayerEntity bot) {
        MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
        return runtime.active() == null ? null
                : runtime.active().spec().toGoal()
                .filter(Goal.Fulfill.class::isInstance).map(Goal.Fulfill.class::cast).orElse(null);
    }

    private static AIPlayerEntity spawnBot(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos cell = context.absolutePos(new BlockPos(1, 2, 1));
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlock(cell.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
        world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(cell),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }

    private static void despawn(AIPlayerEntity bot) {
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
    }

    private static void requireOrCleanup(GameTestHelper context, AIPlayerEntity bot,
                                         boolean condition, String message) {
        if (!condition) {
            despawn(bot);
            context.fail(Component.nullToEmpty(message));
        }
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
