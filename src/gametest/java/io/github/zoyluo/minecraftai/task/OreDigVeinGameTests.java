package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.brain.ToolDefinition;
import io.github.zoyluo.minecraftai.brain.ToolRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
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

    @GameTest(environment = "minecraftai-gametest:ore_dig_vein_game_tests_mine_whole_vein_behind_stone_stops_without_tunnelling", maxTicks = 1500)
    public void mineWholeVeinBehindStoneStopsWithoutTunnelling(GameTestHelper context) {
        Fixture fixture = spawn(context, "VeinBehindGT", true, true, 20);
        AIPlayerEntity bot = fixture.bot();
        var world = bot.level();
        BlockPos start = fixture.start();
        // Three ores on the wall face, then three ores that are only reachable through the cells the
        // first ones leave behind (each has exactly one exposed face, into another ore).
        Set<BlockPos> vein = cells(start, new int[][]{
                {0, 0, -3}, {1, 0, -3}, {1, 1, -3}, {1, 0, -4}, {1, 1, -4}, {1, 0, -5}});
        for (BlockPos pos : vein) {
            world.setBlock(pos, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos hiddenDecoy = start.offset(3, 1, -6);
        BlockPos coalDecoy = start.offset(0, 1, -3);
        world.setBlock(hiddenDecoy, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(coalDecoy, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        Map<BlockPos, BlockState> before = snapshot(world, start);
        int deaths = deathCount(bot);

        JsonObject args = JsonParser.parseString(
                "{\"ore\":\"minecraft:iron_ore\",\"mode\":\"vein\",\"count\":1}").getAsJsonObject();
        OreDigTask task = invokeVeinTool(context, bot, args);

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "vein task ended as " + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, task.veinMined() == vein.size(),
                    "vein mode mined " + task.veinMined() + " ores, expected " + vein.size());
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == vein.size(),
                    "raw iron collected " + InventoryAction.countItem(bot, Items.RAW_IRON)
                            + ", expected " + vein.size());
            requireOnlyChanged(context, world, before, vein, "vein mode changed a block outside the vein");
            require(context, world.getBlockState(hiddenDecoy).is(Blocks.IRON_ORE),
                    "an unconnected hidden iron ore was mined");
            require(context, world.getBlockState(coalDecoy).is(Blocks.COAL_ORE),
                    "a different ore type beside the vein was mined");
            require(context, bot.getBlockY() >= start.getY() - 1, "bot descended while mining a vein");
            finish(context, fixture);
        });
    }

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

        JsonObject args = JsonParser.parseString(
                "{\"ore\":\"minecraft:iron_ore\",\"mode\":\"vein\",\"x\":" + start.getX()
                        + ",\"y\":" + (start.getY() + 1) + ",\"z\":" + (start.getZ() - 3) + "}").getAsJsonObject();
        OreDigTask task = invokeVeinTool(context, bot, args);

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            require(context, !bot.isInLava() && !bot.isOnFire(), "miner touched lava");
            require(context, world.getFluidState(lava.member()).isEmpty(),
                    "lava flowed into the lava-adjacent member's cell");
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
            require(context, world.getFluidState(lava.lava()).isEmpty()
                            && !world.getBlockState(lava.lava()).is(Blocks.LAVA),
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
