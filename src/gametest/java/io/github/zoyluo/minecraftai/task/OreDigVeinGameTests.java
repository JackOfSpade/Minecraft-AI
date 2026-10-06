package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.brain.ToolDefinition;
import io.github.zoyluo.minecraftai.brain.ToolRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Live strict-survival coverage for OreDig's vein mode ("mine the whole vein"): the connected
 * same-type vein of one observed seed ore is mined and the task then completes, without tunnelling
 * for more ore, touching another vein, or mining into lava.
 *
 * <p>Fixture frame (relative to the bot's start cell, the bot looks north): a stone floor two blocks
 * thick, an open cavern, and a solid stone wall at dz -3..-8, dx -4..4, dy 0..3.
 */
public final class OreDigVeinGameTests {
    private static final int MIN_X = -4;
    private static final int MAX_X = 4;
    private static final int MIN_Z = -8;
    private static final int MAX_Z = 3;

    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_nearest_vein_only_leaves_second_vein_intact", maxTicks = 1500)
    public void nearestVeinOnlyLeavesSecondVeinIntact(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinNearestGT", true, true, 20);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        Set<BlockPos> near = cells(start, new int[][]{{0, 0, -3}, {1, 0, -3}, {1, 1, -3}, {1, 0, -4}});
        Set<BlockPos> far = cells(start, new int[][]{{-3, 0, -3}, {-3, 1, -3}, {-2, 1, -3}, {-3, 0, -4}});
        placeOre(world, near);
        placeOre(world, far);
        Map<BlockPos, BlockState> before = snapshot(world, start);
        int deaths = deathCount(bot);

        JsonObject args = JsonParser.parseString(
                "{\"ore\":\"minecraft:iron_ore\",\"mode\":\"vein\"}").getAsJsonObject();
        OreDigTask task = invokeVeinTool(context, bot, args);

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "vein task ended as " + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, task.veinMined() == near.size(),
                    "vein mode mined " + task.veinMined() + " ores, expected " + near.size());
            requireOnlyChanged(context, world, before, near, "the second vein (or stone) was disturbed");
            for (BlockPos pos : far) {
                require(context, world.getBlockState(pos).is(Blocks.IRON_ORE),
                        "the unchosen vein lost " + pos.toShortString());
            }
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == near.size(),
                    "raw iron collected " + InventoryAction.countItem(bot, Items.RAW_IRON));
            finish(context, fixture);
        });
    }

    /**
     * A normal count request is allowed to exceed its quota only to finish the connected seam it
     * already opened.  This is the live regression for Moss mining one coal, rejecting adjacent
     * members as route-required, then starting depth exploration at 1/8.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_count_mode_drains_opened_vein_before_returning_to_quota_search", maxTicks = 1500)
    public void countModeDrainsOpenedVeinBeforeReturningToQuotaSearch(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinCountGT", true, true, 20);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        Set<BlockPos> near = cells(start, new int[][]{{0, 0, -3}, {1, 0, -3}, {1, 1, -3}, {1, 0, -4}});
        Set<BlockPos> far = cells(start, new int[][]{{-3, 0, -3}, {-3, 1, -3}, {-2, 1, -3}, {-3, 0, -4}});
        placeOre(world, near);
        placeOre(world, far);
        Map<BlockPos, BlockState> before = snapshot(world, start);
        int deaths = deathCount(bot);

        // Assign the physical count-mode task directly: tool-level count normally enters the
        // adaptive goal wrapper, while this regression is specifically its task's seam contract.
        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.IRON_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_count_vein"));

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "count task ended as " + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            for (BlockPos pos : near) {
                require(context, world.getBlockState(pos).isAir(),
                        "count mode left connected ore " + pos.toShortString());
            }
            for (BlockPos pos : far) {
                require(context, world.getBlockState(pos).is(Blocks.IRON_ORE),
                        "count mode touched disconnected ore " + pos.toShortString());
            }
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == near.size(),
                    "raw iron collected " + InventoryAction.countItem(bot, Items.RAW_IRON)
                            + ", expected all " + near.size() + " connected drops");
            requireOnlyChanged(context, world, before, near,
                    "count mode disturbed terrain outside the opened vein");
            finish(context, fixture);
        });
    }

    /**
     * The public count tool promises new drops, rather than an absolute inventory total.  In
     * particular, the coal it already holds must not make {@code mine_ore count=1} report
     * {@code already_satisfied} while a visible coal ore remains unmined.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_public_count_with_held_coal_mines_seen_ore", maxTicks = 1500)
    public void publicCountWithHeldCoalMinesSeenOre(GameTestHelper context) {
        Fixture fixture = spawn(context, "PublicCoalCountGT", true, true, 20);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        BlockPos ore = start.offset(0, 0, -3).immutable();
        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        Map<BlockPos, BlockState> before = snapshot(world, start);
        int deaths = deathCount(bot);
        require(context, MinecraftAiConfig.get().goal().autoToolFillEnabled(),
                "public mine_ore count must exercise its adaptive goal boundary");
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        int heldCoal = InventoryAction.countItem(bot, Items.COAL);
        require(context, heldCoal == 1, "fixture did not start with exactly one held coal");
        int requestedDrops = 1;
        Goal.MineOre expectedGoal = new Goal.MineOre(
                OreScan.oreFamily(Blocks.COAL_ORE), requestedDrops, heldCoal);
        require(context, expectedGoal.count() == requestedDrops
                        && expectedGoal.initialDropCount() == heldCoal
                        && expectedGoal.targetDropCount() == heldCoal + requestedDrops,
                "public count goal did not preserve its quota, baseline, and target semantics");
        long resultBaseline = GoalExecutor.INSTANCE.lastResult(bot).map(GoalResult::sequence).orElse(0L);

        ToolDefinition definition = new ToolRegistry().get("mine_ore").orElse(null);
        require(context, definition != null, "mine_ore was not registered");
        JsonObject args = JsonParser.parseString(
                "{\"ore\":\"minecraft:coal_ore\",\"count\":1}").getAsJsonObject();
        ToolDefinition.ToolResult result = definition.handler().invoke(bot, args);
        require(context, result != null && result.ok(),
                "public mine_ore count request was rejected: " + (result == null ? "null" : result.message()));
        require(context, GoalExecutor.INSTANCE.isActiveGoal(bot, expectedGoal),
                "public mine_ore did not retain its one-drop quota and held-coal baseline");
        require(context, GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).isEmpty(),
                "public mine_ore completed before mining the visible coal");

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            GoalResult completed = GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).orElse(null);
            if (completed == null) {
                if (context.getTick() > 1400) {
                    fail(context, "public mine_ore did not complete after mining the visible coal");
                }
                return;
            }
            require(context, completed.goal().equals(expectedGoal)
                            && completed.status() == GoalResult.Status.COMPLETED,
                    "public mine_ore ended as " + completed.status() + ":" + completed.reason());
            require(context, world.getBlockState(ore).isAir(),
                    "public mine_ore reported completion without mining the visible coal");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= heldCoal + 1,
                    "public mine_ore did not collect a new coal: "
                            + InventoryAction.countItem(bot, Items.COAL));
            requireOnlyChanged(context, world, before, Set.of(ore),
                    "public mine_ore disturbed terrain outside the visible coal");
            finish(context, fixture);
        });
    }

    /**
     * The connected-seam obligation survives a task/process restore even when the requested
     * count was already delivered by the first block.  Only factual broken cells are checkpointed;
     * the restored task must re-observe its neighbours before it mines them.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_count_mode_restored_after_first_pickup_drains_opened_vein", maxTicks = 1500)
    public void countModeRestoredAfterFirstPickupDrainsOpenedVein(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinRestartGT", true, true, 20);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        Set<BlockPos> near = cells(start, new int[][]{{0, 0, -3}, {1, 0, -3}, {1, 1, -3}, {1, 0, -4}});
        Set<BlockPos> far = cells(start, new int[][]{{-3, 0, -3}, {-3, 1, -3}, {-2, 1, -3}, {-3, 0, -4}});
        placeOre(world, near);
        placeOre(world, far);
        Map<BlockPos, BlockState> before = snapshot(world, start);
        int deaths = deathCount(bot);
        OreDigTask[] active = {new OreDigTask(OreScan.oreFamily(Blocks.IRON_ORE), 1)};
        boolean[] restarted = {false};
        TaskManager.INSTANCE.assign(bot, active[0],
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_count_vein_restart"));

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            OreDigTask task = active[0];
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "count task ended as " + task.state() + ":" + task.failureReason());
            }
            int rawIron = InventoryAction.countItem(bot, Items.RAW_IRON);
            if (!restarted[0] && rawIron >= 1 && rawIron < near.size()) {
                Map<String, String> checkpoint = task.checkpoint();
                require(context, checkpoint.containsKey("opened_vein_breaks"),
                        "first delivered ore did not checkpoint its opened seam: " + checkpoint);
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_count_vein_restart_boundary");
                OreDigTask restored = new OreDigTask(OreScan.oreFamily(Blocks.IRON_ORE), 1, checkpoint);
                TaskManager.INSTANCE.assign(bot, restored,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_count_vein_restored"));
                require(context, checkpoint.get("opened_vein_breaks").equals(
                                restored.checkpoint().get("opened_vein_breaks")),
                        "restored seam frontier changed: before=" + checkpoint
                                + " after=" + restored.checkpoint());
                active[0] = restored;
                restarted[0] = true;
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted[0], "count task completed before the forced restart");
            for (BlockPos pos : near) {
                require(context, world.getBlockState(pos).isAir(),
                        "restored count mode left connected ore " + pos.toShortString());
            }
            for (BlockPos pos : far) {
                require(context, world.getBlockState(pos).is(Blocks.IRON_ORE),
                        "restored count mode touched disconnected ore " + pos.toShortString());
            }
            require(context, rawIron == near.size(),
                    "restored count mode collected " + rawIron + ", expected " + near.size());
            requireOnlyChanged(context, world, before, near,
                    "restored count mode disturbed terrain outside the opened vein");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_coordinate_hint_selects_the_other_vein", maxTicks = 1500)
    public void coordinateHintSelectsTheOtherVein(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinHintGT", true, true, 20);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        Set<BlockPos> near = cells(start, new int[][]{{0, 0, -3}, {1, 0, -3}, {1, 1, -3}, {1, 0, -4}});
        Set<BlockPos> far = cells(start, new int[][]{{-3, 0, -3}, {-3, 1, -3}, {-2, 1, -3}, {-3, 0, -4}});
        placeOre(world, near);
        placeOre(world, far);
        Map<BlockPos, BlockState> before = snapshot(world, start);
        int deaths = deathCount(bot);
        BlockPos hint = start.offset(-3, 1, -3);

        JsonObject args = JsonParser.parseString("{\"ore\":\"minecraft:iron_ore\",\"mode\":\"vein\",\"x\":"
                + hint.getX() + ",\"y\":" + hint.getY() + ",\"z\":" + hint.getZ() + "}").getAsJsonObject();
        OreDigTask task = invokeVeinTool(context, bot, args);

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "vein task ended as " + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, task.veinMined() == far.size(),
                    "vein mode mined " + task.veinMined() + " ores, expected " + far.size());
            requireOnlyChanged(context, world, before, far, "the hinted vein run disturbed other blocks");
            for (BlockPos pos : near) {
                require(context, world.getBlockState(pos).is(Blocks.IRON_ORE),
                        "the nearer, unchosen vein lost " + pos.toShortString());
            }
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_member_beside_lava_is_sealed_before_it_is_mined", maxTicks = 1500)
    public void memberBesideLavaIsSealedBeforeItIsMined(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinLavaSealGT", true, true, 20);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        LavaVein lava = lavaVein(world, start);
        Map<BlockPos, BlockState> before = snapshot(world, start);
        int deaths = deathCount(bot);
        boolean[] sourceSealedWhileMemberIntact = {false};

        JsonObject args = JsonParser.parseString(
                "{\"ore\":\"minecraft:iron_ore\",\"mode\":\"vein\",\"x\":" + start.getX()
                        + ",\"y\":" + (start.getY() + 1) + ",\"z\":" + (start.getZ() - 3) + "}").getAsJsonObject();
        OreDigTask task = invokeVeinTool(context, bot, args);

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            require(context, !bot.isInLava() && !bot.isOnFire(), "miner touched lava");
            require(context, world.getFluidState(lava.member()).isEmpty(),
                    "lava flowed into the lava-adjacent member's cell");
            boolean memberMined = world.getBlockState(lava.member()).isAir();
            boolean sourceSolidlySealed = isSolidSeal(world, lava.lava());
            if (memberMined) {
                require(context, sourceSealedWhileMemberIntact[0] && sourceSolidlySealed,
                        "the lava-adjacent member was mined before a prior solid source seal");
            } else if (sourceSolidlySealed) {
                // Record only a seal visible while the member still exists: a same-tick
                // mine-and-seal transition must not satisfy the temporal ordering proof.
                sourceSealedWhileMemberIntact[0] = true;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "vein task ended as " + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, task.veinMined() == 4,
                    "vein mode mined " + task.veinMined() + " ores, expected 4 (lava member sealed first)");
            require(context, world.getBlockState(lava.member()).isAir(),
                    "the lava-adjacent member was not mined after sealing");
            require(context, sourceSealedWhileMemberIntact[0] && isSolidSeal(world, lava.lava()),
                    "the adjacent lava source was not sealed");
            Set<BlockPos> allowed = new HashSet<>(lava.ores());
            allowed.add(lava.lava());
            requireOnlyChanged(context, world, before, allowed,
                    "sealing changed blocks other than the lava source and the vein");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_member_beside_lava_is_skipped_when_nothing_can_seal", maxTicks = 1500)
    public void memberBesideLavaIsSkippedWhenNothingCanSeal(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinLavaSkipGT", true, true, 0);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        LavaVein lava = lavaVein(world, start);
        Map<BlockPos, BlockState> before = snapshot(world, start);
        int deaths = deathCount(bot);

        JsonObject args = JsonParser.parseString(
                "{\"ore\":\"minecraft:iron_ore\",\"mode\":\"vein\",\"x\":" + start.getX()
                        + ",\"y\":" + (start.getY() + 1) + ",\"z\":" + (start.getZ() - 3) + "}").getAsJsonObject();
        OreDigTask task = invokeVeinTool(context, bot, args);

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            require(context, !bot.isInLava() && !bot.isOnFire(), "miner touched lava");
            require(context, world.getBlockState(lava.member()).is(Blocks.IRON_ORE),
                    "the lava-adjacent member was mined although nothing could seal the lava");
            require(context, world.getBlockState(lava.lava()).is(Blocks.LAVA),
                    "the lava source changed");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "vein task ended as " + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, task.veinMined() == 2,
                    "vein mode mined " + task.veinMined() + " ores, expected the 2 eye-level members");
            Set<BlockPos> allowed = new HashSet<>(lava.ores());
            allowed.remove(lava.member());
            allowed.remove(lava.link());
            requireOnlyChanged(context, world, before, allowed,
                    "skipping the lava member disturbed other blocks");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_no_visible_ore_fails_instead_of_strip_mining", maxTicks = 600)
    public void noVisibleOreFailsInsteadOfStripMining(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinNoneGT", true, true, 0);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        // A completely enclosed ore is not observable and must not be scanned for.
        world.setBlock(start.offset(2, 1, -6), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        Map<BlockPos, BlockState> before = snapshot(world, start);

        JsonObject args = JsonParser.parseString(
                "{\"ore\":\"minecraft:iron_ore\",\"mode\":\"vein\"}").getAsJsonObject();
        OreDigTask task = invokeVeinTool(context, bot, args);

        context.failIfEver(() -> {
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                fail(context, "vein task ended as " + task.state() + " instead of failing typed");
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith("vein_not_found"),
                    "unexpected failure reason " + task.failureReason());
            requireOnlyChanged(context, world, before, Set.of(),
                    "vein mode dug although no ore was observable");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_tool_requires_suitable_pickaxe_up_front", maxTicks = 40)
    public void toolRequiresSuitablePickaxeUpFront(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinToolGT", true, false, 0);
        AIPlayerEntity bot = fixture.bot();
        ToolDefinition definition = new ToolRegistry().get("mine_ore").orElse(null);
        require(context, definition != null, "mine_ore was not registered");
        JsonObject args = JsonParser.parseString(
                "{\"ore\":\"minecraft:iron_ore\",\"mode\":\"vein\"}").getAsJsonObject();
        ToolDefinition.ToolResult result = definition.handler().invoke(bot, args);
        require(context, result != null && !result.ok()
                        && result.message().startsWith("need_better_tool"),
                "vein mode without a pickaxe was not rejected up front: "
                        + (result == null ? "null" : result.message()));
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                "a rejected vein request still assigned a task");
        finish(context, fixture);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private record LavaVein(Set<BlockPos> ores, BlockPos link, BlockPos member, BlockPos lava) {
    }

    /**
     * Three ores on the wall face plus a fourth ore set into the floor one cell in front of the wall
     * whose neighbouring floor cell, on the bot's side, is an open lava source. The source sits in a stone-walled floor
     * pocket, so it cannot spread until the ore's cell is opened, and it is visible from above.
     * The wall-base ore {@code link} joins the floor ore to the rest of the vein; it sits at feet level,
     * so mining it needs a drop-support block (the second test deliberately has none).
     */
    private static LavaVein lavaVein(net.minecraft.server.level.ServerLevel world, BlockPos start) {
        Set<BlockPos> ores = cells(start, new int[][]{{0, 1, -3}, {1, 1, -3}, {1, 0, -3}, {2, -1, -2}});
        placeOre(world, ores);
        BlockPos link = start.offset(1, 0, -3);
        BlockPos member = start.offset(2, -1, -2);
        BlockPos lava = start.offset(2, -1, -1);
        world.setBlock(lava, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        return new LavaVein(ores, link.immutable(), member.immutable(), lava.immutable());
    }

    private static Fixture spawn(GameTestHelper context, String name, boolean wall, boolean pickaxe, int cobble) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(6, 3, 9));
        for (int dx = MIN_X; dx <= MAX_X; dx++) {
            for (int dz = MIN_Z; dz <= MAX_Z; dz++) {
                world.setBlock(start.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    boolean solid = wall && dz <= -3;
                    world.setBlock(start.offset(dx, dy, dz),
                            (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        180.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 180.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        if (cobble > 0) {
            InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, cobble));
        }
        if (pickaxe) {
            InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        }
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        return new Fixture(name, bot, start.immutable());
    }

    private static OreDigTask invokeVeinTool(GameTestHelper context, AIPlayerEntity bot, JsonObject args) {
        ToolDefinition definition = new ToolRegistry().get("mine_ore").orElse(null);
        require(context, definition != null, "mine_ore was not registered");
        ToolDefinition.ToolResult result = definition.handler().invoke(bot, args);
        require(context, result != null && result.ok(),
                "mine_ore mode=vein was rejected: " + (result == null ? "null" : result.message()));
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof OreDigTask task && task.isVeinMode(),
                "mine_ore mode=vein did not assign a vein-mode OreDigTask: " + active);
        return (OreDigTask) active;
    }

    private static Set<BlockPos> cells(BlockPos start, int[][] offsets) {
        Set<BlockPos> result = new HashSet<>();
        for (int[] o : offsets) {
            result.add(start.offset(o[0], o[1], o[2]).immutable());
        }
        return result;
    }

    private static void placeOre(net.minecraft.server.level.ServerLevel world, Set<BlockPos> ores) {
        for (BlockPos pos : ores) {
            world.setBlock(pos, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static Map<BlockPos, BlockState> snapshot(net.minecraft.server.level.ServerLevel world, BlockPos start) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int dx = MIN_X; dx <= MAX_X; dx++) {
            for (int dz = MIN_Z; dz <= MAX_Z; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos pos = start.offset(dx, dy, dz).immutable();
                    states.put(pos, world.getBlockState(pos));
                }
            }
        }
        return states;
    }

    /** Every fixture cell outside {@code allowed} must be exactly as it was before the task ran. */
    private static void requireOnlyChanged(GameTestHelper context,
                                           net.minecraft.server.level.ServerLevel world,
                                           Map<BlockPos, BlockState> before,
                                           Set<BlockPos> allowed,
                                           String message) {
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            if (allowed.contains(entry.getKey())) {
                continue;
            }
            BlockState now = world.getBlockState(entry.getKey());
            if (!now.equals(entry.getValue())) {
                fail(context, message + ": " + entry.getKey().toShortString() + " "
                        + entry.getValue().getBlock() + " -> " + now.getBlock());
            }
        }
    }

    private static boolean isSolidSeal(net.minecraft.server.level.ServerLevel world, BlockPos source) {
        BlockState state = world.getBlockState(source);
        return world.getFluidState(source).isEmpty()
                && !state.is(Blocks.LAVA)
                && !state.isAir()
                && !state.getCollisionShape(world, source).isEmpty();
    }

    private static int deathCount(AIPlayerEntity bot) {
        return bot.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
    }

    private static void finish(GameTestHelper context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(
                fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void fail(GameTestHelper context, String message) {
        context.fail(Component.nullToEmpty(message));
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            fail(context, message);
        }
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos start) {
    }
}
