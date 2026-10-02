package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonObject;
import com.mojang.brigadier.tree.CommandNode;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.AbstractTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Runtime registry coverage for the public mine_ore argument contract. */
public final class ToolRegistryMiningGameTests {
    @GameTest(maxTicks = 20)
    public void aliasesResolveOnlyTheirRequestedOreFamily(GameTestHelper context) {
        if (!OreScan.oreFamily(Blocks.DIAMOND_ORE)
                .equals(ToolRegistry.oreTargetsFrom("minecraft:diamond"))) {
            context.fail(Component.nullToEmpty("diamond alias resolved to the wrong ore family"));
            return;
        }
        if (!OreScan.oreFamily(Blocks.IRON_ORE)
                .equals(ToolRegistry.oreTargetsFrom("minecraft:raw_iron"))) {
            context.fail(Component.nullToEmpty("raw_iron alias resolved to the wrong ore family"));
            return;
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void obsidianAndNonOreTargetsFailClosed(GameTestHelper context) {
        requireRejected(context, "minecraft:obsidian", true);
        requireRejected(context, "minecraft:stone", false);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void retiredDirectToolsAreAbsentWithoutDisturbingActiveWork(GameTestHelper context) {
        ActiveFixture fixture = activeFixture(context, "StripDirectGT");
        try {
            ToolRegistry registry = new ToolRegistry();
            require(context, registry.get("strip_mine").isEmpty(), "strip_mine remained publicly registered");
            require(context, registry.get("mine_vein").isEmpty(), "mine_vein remained publicly registered");
            requireUndisturbed(context, fixture, "retired direct tools");
        } finally {
            cleanupFixture(context, fixture);
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void retiredAssignTaskTypesRejectWithoutDisturbingActiveWork(GameTestHelper context) {
        ActiveFixture fixture = activeFixture(context, "StripAssignGT");
        try {
            ToolRegistry registry = new ToolRegistry();
            requireUnknownTaskType(context, fixture, registry, "strip_mine");
            requireUnknownTaskType(context, fixture, registry, "mine_vein");
        } finally {
            cleanupFixture(context, fixture);
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void unknownMineOreModeFailsClosedWithoutDisturbingActiveWork(GameTestHelper context) {
        ActiveFixture fixture = activeFixture(context, "MineOreModeGT");
        try {
            ToolDefinition definition = new ToolRegistry().get("mine_ore").orElse(null);
            require(context, definition != null, "mine_ore was not registered");
            for (String mode : List.of("veins", "all", "until_vein_exhausted", "count,vein")) {
                JsonObject args = new JsonObject();
                args.addProperty("ore", "minecraft:iron_ore");
                args.addProperty("count", 1);
                args.addProperty("mode", mode);
                ToolDefinition.ToolResult result = definition.handler().invoke(fixture.bot(), args);
                require(context, result != null && !result.ok(),
                        "mine_ore mode=" + mode + " was accepted instead of rejected");
                require(context, result.message().contains("invalid_mode")
                                && result.message().contains("count") && result.message().contains("vein"),
                        "mine_ore mode=" + mode + " must fail listing the valid modes, got: " + result.message());
                requireUndisturbed(context, fixture, "mine_ore mode=" + mode);
            }
            JsonObject schema = definition.parametersSchema().getAsJsonObject("properties")
                    .getAsJsonObject("mode");
            require(context, schema.getAsJsonArray("enum").size() == ToolRegistry.MINE_ORE_MODES.size(),
                    "the mode schema enum must list exactly the valid modes: " + schema);
        } finally {
            cleanupFixture(context, fixture);
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void retiredPlayerCommandsAreAbsentWithoutDisturbingActiveWork(GameTestHelper context) {
        ActiveFixture fixture = activeFixture(context, "StripCmdGT");
        try {
            CommandNode<CommandSourceStack> root = fixture.bot().level().getServer().getCommands()
                    .getDispatcher().getRoot();
            CommandNode<CommandSourceStack> minecraftAi = root.getChild("minecraftai");
            CommandNode<CommandSourceStack> task = minecraftAi == null ? null : minecraftAi.getChild("task");
            CommandNode<CommandSourceStack> assign = task == null ? null : task.getChild("assign");
            CommandNode<CommandSourceStack> botName = assign == null ? null : assign.getChild("name");
            require(context, botName != null, "minecraftai task assign command tree was not registered");
            require(context, botName == null || botName.getChild("strip_mine") == null,
                    "strip_mine remained in the player command tree");
            require(context, botName == null || botName.getChild("mine_vein") == null,
                    "mine_vein remained in the player command tree");
            requireUndisturbed(context, fixture, "retired player commands");
        } finally {
            cleanupFixture(context, fixture);
        }
        context.succeed();
    }

    /**
     * lookup_recipe is the fix for the model denying content newer than its memory (copper tools,
     * the Lunge enchantment): it must answer from the live registries/recipe index, stay read-only
     * (the active work is left untouched) and stay short.
     */
    @GameTest(maxTicks = 20)
    public void lookupRecipeAnswersFromTheLiveGameAndIsReadOnly(GameTestHelper context) {
        ActiveFixture fixture = activeFixture(context, "LookupRecipeGT");
        try {
            ToolDefinition definition = new ToolRegistry().get("lookup_recipe").orElse(null);
            require(context, definition != null, "lookup_recipe was not registered");

            String copper = lookup(context, fixture, definition, "minecraft:copper_pickaxe");
            require(context, copper.contains("exists in Minecraft 1.21.11")
                            && copper.contains("minecraft:copper_ingot") && copper.contains("minecraft:stick"),
                    "copper pickaxe must be reported with its copper_ingot + stick recipe, got: " + copper);
            require(context, copper.contains("crafting table"),
                    "the copper pickaxe needs a crafting table (3x3 recipe), got: " + copper);
            String plainName = lookup(context, fixture, definition, "Copper Pickaxe");
            require(context, plainName.equals(copper),
                    "a plain-language name must resolve like the id: " + plainName);

            String lunge = lookup(context, fixture, definition, "lunge");
            require(context, lunge.contains("enchantment that exists in Minecraft 1.21.11"),
                    "Lunge must be reported as an existing enchantment, got: " + lunge);

            String unknown = lookup(context, fixture, definition, "copper_pick");
            require(context, unknown.contains("neither an item nor an enchantment")
                            && unknown.contains("Similar ids:") && unknown.contains("minecraft:copper_pickaxe"),
                    "an unknown name must list similar real ids, got: " + unknown);
            for (String answer : List.of(copper, lunge, unknown)) {
                require(context, answer.length() < 400, "lookup output must stay short, was " + answer.length());
            }

            JsonObject missing = new JsonObject();
            ToolDefinition.ToolResult bad = null;
            try {
                bad = definition.handler().invoke(fixture.bot(), missing);
            } catch (IllegalArgumentException expected) {
                // The dispatcher turns this into bad_arg; a direct handler call surfaces it.
            }
            require(context, bad == null || !bad.ok(), "a missing name must be rejected");
            requireUndisturbed(context, fixture, "lookup_recipe");
        } finally {
            cleanupFixture(context, fixture);
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void breakBlocksLeavesRequestIsRecognisedForBothEntryPoints(GameTestHelper context) {
        for (String accepted : List.of("leaves", "minecraft:leaves", " Leaves ")) {
            JsonObject args = new JsonObject();
            args.addProperty("block", accepted);
            require(context, ToolRegistry.isAnyLeavesRequest(args, "block"),
                    "'" + accepted + "' must mean any leaf block");
        }
        for (String other : List.of("minecraft:oak_leaves", "minecraft:oak_log", "")) {
            JsonObject args = new JsonObject();
            args.addProperty("block", other);
            require(context, !ToolRegistry.isAnyLeavesRequest(args, "block"),
                    "'" + other + "' is an exact block id, not the generic leaves request");
        }
        require(context, !ToolRegistry.isAnyLeavesRequest(new JsonObject(), "block"),
                "a missing block argument is not a leaves request");
        context.succeed();
    }

    private static String lookup(GameTestHelper context, ActiveFixture fixture, ToolDefinition definition,
                                 String name) {
        JsonObject args = new JsonObject();
        args.addProperty("name", name);
        ToolDefinition.ToolResult result = definition.handler().invoke(fixture.bot(), args);
        require(context, result != null && result.ok(), "lookup_recipe " + name + " failed: " + result);
        return result.message();
    }

    private static void requireRejected(GameTestHelper context, String id, boolean requireCorrection) {
        try {
            ToolRegistry.oreTargetsFrom(id);
            context.fail(Component.nullToEmpty(id + " silently became a common-ore target"));
        } catch (IllegalArgumentException expected) {
            String message = expected.getMessage();
            if (message == null || !message.contains("unsupported_mine_ore_target: " + id)) {
                context.fail(Component.nullToEmpty("unexpected rejection for " + id + ": " + message));
            }
            if (requireCorrection && !message.contains("use achieve_goal with item=" + id)) {
                context.fail(Component.nullToEmpty("missing achieve_goal correction for " + id));
            }
        }
    }

    private static void requireUnknownTaskType(GameTestHelper context,
                                               ActiveFixture fixture,
                                               ToolRegistry registry,
                                               String taskType) {
        ToolDefinition definition = registry.get("assign_task").orElse(null);
        require(context, definition != null, "assign_task was not registered");
        JsonObject args = new JsonObject();
        args.addProperty("task_type", taskType);
        args.add("params", new JsonObject());
        try {
            definition.handler().invoke(fixture.bot(), args);
            context.fail(Component.nullToEmpty(taskType + " was accepted by assign_task"));
        } catch (IllegalArgumentException expected) {
            require(context, ("unknown_task_type: " + taskType).equals(expected.getMessage()),
                    taskType + " returned the wrong rejection: " + expected.getMessage());
        }
        requireUndisturbed(context, fixture, "assign_task " + taskType);
    }

    private static ActiveFixture activeFixture(GameTestHelper context, String botName) {
        var world = context.getLevel();
        BlockPos spawn = context.absolutePos(new BlockPos(1, 126, 1));
        prepareCell(world, spawn);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3.atBottomCenterOf(spawn),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));

        BotRuntimeOptions.INSTANCE.setVerboseReportsEnabled(bot, true);
        SentinelTask sentinel = new SentinelTask();
        TaskOrigin origin = TaskOrigin.of(TaskOrigin.Kind.VERIFY, "retired_mining_gate_sentinel");
        TaskManager.INSTANCE.assign(bot, sentinel, origin);
        ActionResult action = bot.getActionPack().startWalkTo(bot.position().add(2.0D, 0.0D, 0.0D));
        require(context, action.isInProgress(), "sentinel walk action did not start");

        long reportSequence = BotReporter.INSTANCE.taskReportSequence(bot);
        require(context, reportSequence > 0L, "sentinel assignment did not activate report sequencing");
        ActionSnapshot actionSnapshot = ActionSnapshot.capture(bot);
        require(context, actionSnapshot.hasActiveActions()
                        && !actionSnapshot.pathExecutorIdle()
                        && actionSnapshot.activePathGoal() != null,
                "sentinel Baritone route was not observable");
        return new ActiveFixture(
                botName,
                bot,
                sentinel,
                TaskManager.INSTANCE.status(bot),
                origin,
                reportSequence,
                actionSnapshot);
    }

    private static void requireUndisturbed(GameTestHelper context,
                                           ActiveFixture fixture,
                                           String route) {
        require(context, TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null)
                        == fixture.sentinel(),
                route + " replaced the active sentinel task");
        require(context, fixture.status().equals(TaskManager.INSTANCE.status(fixture.bot())),
                route + " changed the published task status");
        require(context, TaskManager.INSTANCE.activeOrigin(fixture.bot())
                        .filter(fixture.origin()::equals).isPresent(),
                route + " changed active task authority");
        require(context, BotReporter.INSTANCE.taskReportSequence(fixture.bot())
                        == fixture.reportSequence(),
                route + " published an assignment/task event");
        require(context, fixture.actionSnapshot().equals(ActionSnapshot.capture(fixture.bot())),
                route + " stopped or replaced the active walk action");
    }

    private static void cleanupFixture(GameTestHelper context, ActiveFixture fixture) {
        IntentController.INSTANCE.cancelAll(
                fixture.bot(), IntentController.ControlOrigin.SYSTEM,
                "retired_mining_gate_gametest_cleanup");
        AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), fixture.botName());
    }

    private static void prepareCell(net.minecraft.server.level.ServerLevel world, BlockPos center) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(),
                        Block.UPDATE_CLIENTS);
                world.setBlock(center.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS);
                world.setBlock(center.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record ActiveFixture(String botName,
                                 AIPlayerEntity bot,
                                 SentinelTask sentinel,
                                 TaskStatus status,
                                 TaskOrigin origin,
                                 long reportSequence,
                                 ActionSnapshot actionSnapshot) {
    }

    private record ActionSnapshot(boolean hasActiveActions,
                                  boolean walkToIdle,
                                  boolean pathExecutorIdle,
                                  boolean miningIdle,
                                  BlockPos activePathGoal) {
        private static ActionSnapshot capture(AIPlayerEntity bot) {
            return new ActionSnapshot(
                    bot.getActionPack().hasActiveActions(),
                    bot.getActionPack().isWalkToIdle(),
                    bot.getActionPack().isPathExecutorIdle(),
                    bot.getActionPack().isMiningIdle(),
                    bot.getActionPack().activePathGoal());
        }
    }

    private static final class SentinelTask extends AbstractTask {
        @Override
        public String name() {
            return "strict_gate_sentinel";
        }

        @Override
        public String describe() {
            return "strict gate sentinel";
        }

        @Override
        public double progress() {
            return 0.25D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }

}
