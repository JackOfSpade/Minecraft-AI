package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * "Gather 32 logs, craft a table and give it to me" as one fulfill_items call, through the real tool: the raw
 * quota is its own allocation without a recipient, so it is measured above the logs already carried and has
 * to be collected, while the crafted table is what the player receives. Without that allocation the same
 * call would craft the table from carried logs and silently drop the collection.
 */
public final class FulfillRawQuotaGameTests {
    @GameTest(maxTicks = 40)
    public void theRawQuotaIsNewLogsAboveWhatIsCarriedAndTheTableIsHandedOver(GameTestHelper context) {
        AIPlayerEntity bot = spawnBot(context, "RawQuotaGT");
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 10));

        List<String> steps = submit(context, bot, quotaAndTable());
        Goal.Fulfill goal = activeGoal(bot);
        despawn(bot);

        require(context, goal != null, "no fulfillment mission was started: " + steps);
        require(context, goal.initialItemCount(Items.OAK_LOG) == 10,
                "the 32 logs must be measured above the 10 carried: " + goal.initialItemCounts());
        require(context, goal.initialItemCount(Items.CRAFTING_TABLE) == 0,
                "no table is carried, so the one handed over is a new one: " + goal.initialItemCounts());
        // The table takes one carried log, so the planner collects the quota and that one: the bot ends up with
        // the 10 it carried plus 32 new logs, whatever the table consumed.
        require(context, steps.stream().anyMatch(step -> step.startsWith("Gather") && step.contains("oak log x33")),
                "the plan must gather the 32 new logs plus the one the table uses: " + steps);
        int gather = indexWhere(steps, "Gather");
        int craft = indexWhere(steps, "Craft");
        int give = indexWhere(steps, "Give");
        require(context, gather >= 0 && gather < give && craft >= 0 && craft < give,
                "gather and craft come before the handoff: " + steps);
        require(context, steps.get(give).contains("crafting table x1") && steps.get(give).contains("Alex"),
                "the table is what is handed over: " + steps);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void withNothingCarriedTheQuotaIsTheSameGatherAndTheTableIsStillCrafted(GameTestHelper context) {
        AIPlayerEntity bot = spawnBot(context, "RawQuotaEmptyGT");

        List<String> steps = submit(context, bot, quotaAndTable());
        despawn(bot);

        require(context, steps.stream().anyMatch(step -> step.startsWith("Gather") && step.contains("oak log x33")),
                "32 new logs and the one the table needs are collected in one batch: " + steps);
        require(context, steps.stream().anyMatch(step -> step.startsWith("Craft") && step.contains("crafting table")),
                "the table is crafted: " + steps);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void withoutTheRawAllocationCarriedLogsStandInForTheCollection(GameTestHelper context) {
        // The control that the routing's manifest check exists for: the table alone is crafted from carried
        // logs and nothing is gathered, so the "gather 32 logs" half of the request would be lost.
        AIPlayerEntity bot = spawnBot(context, "RawQuotaDroppedGT");
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 10));
        JsonObject args = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(allocation("minecraft:crafting_table", 1, "Alex"));
        args.add("items", items);

        List<String> steps = submit(context, bot, args);
        despawn(bot);

        require(context, steps.stream().noneMatch(step -> step.startsWith("Gather")),
                "setup: the table alone needs no collection: " + steps);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aToolKeptForTheBotIsNotCraftedAgainButOneHandedOverIs(GameTestHelper context) {
        AIPlayerEntity bot = spawnBot(context, "KeptToolGT");
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));

        // "Make yourself a pickaxe": the one already carried is the bot's own, nothing is crafted.
        List<String> own = submit(context, bot, manifest(allocation("minecraft:iron_pickaxe", 1, null)));
        require(context, own.stream().noneMatch(step -> step.startsWith("Craft")),
                "a pickaxe kept on the bot that already carries one crafts nothing: " + own);
        GoalExecutor.INSTANCE.cancelAll(bot);

        // "Make me a pickaxe": the carried one is the bot's, so the player's is crafted.
        List<String> theirs = submit(context, bot, manifest(allocation("minecraft:iron_pickaxe", 1, "Alex")));
        despawn(bot);
        require(context, theirs.stream().anyMatch(step -> step.startsWith("Craft") && step.contains("iron pickaxe")),
                "a pickaxe handed over is never the one the bot carries: " + theirs);
        context.succeed();
    }

    private static JsonObject manifest(JsonObject... allocations) {
        JsonObject args = new JsonObject();
        JsonArray items = new JsonArray();
        for (JsonObject allocation : allocations) {
            items.add(allocation);
        }
        args.add("items", items);
        return args;
    }

    private static JsonObject quotaAndTable() {
        JsonObject args = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(allocation("minecraft:oak_log", 32, null));
        items.add(allocation("minecraft:crafting_table", 1, "Alex"));
        args.add("items", items);
        return args;
    }

    private static JsonObject allocation(String item, int count, String recipient) {
        JsonObject allocation = new JsonObject();
        allocation.addProperty("item", item);
        allocation.addProperty("count", count);
        if (recipient != null) {
            allocation.addProperty("recipient", recipient);
        }
        return allocation;
    }

    /** Calls the real fulfill_items tool and returns the planned step labels of the mission it started. */
    private static List<String> submit(GameTestHelper context, AIPlayerEntity bot, JsonObject args) {
        ToolDefinition tool = new ToolRegistry().get("fulfill_items").orElseThrow();
        ToolDefinition.ToolResult result = tool.handler().invoke(bot, args);
        if (!result.ok()) {
            despawn(bot);
            context.fail(Component.nullToEmpty("fulfill_items refused the manifest: " + result.message()));
        }
        return List.copyOf(GoalExecutor.INSTANCE.activeGoalSteps(bot));
    }

    private static Goal.Fulfill activeGoal(AIPlayerEntity bot) {
        MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
        return runtime.active() == null ? null
                : runtime.active().spec().toGoal()
                .filter(Goal.Fulfill.class::isInstance).map(Goal.Fulfill.class::cast).orElse(null);
    }

    private static int indexWhere(List<String> steps, String prefix) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private static AIPlayerEntity spawnBot(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos cell = context.absolutePos(new BlockPos(1, 2, 1));
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlock(cell.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
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
        return bot;
    }

    private static void despawn(AIPlayerEntity bot) {
        GoalExecutor.INSTANCE.cancelAll(bot);
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
