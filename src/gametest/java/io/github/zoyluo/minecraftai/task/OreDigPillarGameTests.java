package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.lang.reflect.Field;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Live strict-survival coverage for an ore that hangs out of reach on open terrain, where the stair has no floors to
 * dig it up to: the bot builds up to it with placed blocks, the way a player pillars up a cave wall.
 *
 * <p>Fixture (frame relative to the coal): a wall with the coal in its face, a cave in front of it, the bot on the cave
 * floor {@code height} blocks under the coal and four blocks out from the wall. There is no ledge to walk up, and the pillar
 * is tried before a stair is dug into the wall: where the test gives the bot no blocks to build with, the wall is bedrock, so
 * that nothing it could dig leads to the coal either.</p>
 */
public final class OreDigPillarGameTests {
    private static final int OUT = 4;
    /** The cobblestone OreDig keeps for emergencies, whatever its mission: a pillar spends only what is carried beyond it. */
    private static final int RESERVE = MiningBudget.EMERGENCY_STONE_LIKE;

    @GameTest(maxTicks = 3600)
    public void coalOnACaveWallFourBlocksUpIsReachedByAPillarOfCobblestone(GameTestHelper context) {
        Fixture fixture = build(context, "PillarCoalGT", 4, RESERVE + 6);
        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(fixture.bot(), task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_pillar_coal"));
        pillarsUpAndMines(context, fixture, task, RESERVE + 6, 3);
    }

    /**
     * The same coal as a member of a vein that was already queued (a vein member that hangs out of reach is climbed to like the primary
     * target, not released): the task is handed the coal as its queue head before its first tick.
     */
    @GameTest(maxTicks = 3600)
    public void aQueuedVeinMemberOutOfReachIsBuiltUpToLikeAPrimaryTarget(GameTestHelper context) {
        Fixture fixture = build(context, "PillarQueuedGT", 4, RESERVE + 6);
        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(fixture.bot(), task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_pillar_queued"));
        queuedVeinMembers(task).addLast(fixture.ore());
        pillarsUpAndMines(context, fixture, task, RESERVE + 6, 3);
    }

    /**
     * A second coal in the same wall, not a member of the first one's vein: the tower of the first pillar is taken down before the bot
     * goes for it, and the second is built up to by a pillar of its own, from the cobblestone the first tower gave back.
     */
    @GameTest(maxTicks = 7200)
    public void aSecondCoalIsBuiltUpToByAPillarOfItsOwnAfterTheFirstTowerCameDown(GameTestHelper context) {
        Fixture fixture = build(context, "PillarSecondGT", 4, RESERVE + 8);
        AIPlayerEntity bot = fixture.bot();
        ServerLevel world = bot.level();
        BlockPos first = fixture.ore();
        BlockPos second = first.offset(0, 2, SECOND_COAL_OFFSET);
        world.setBlock(second, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        Map<BlockPos, BlockState> before = snapshot(world, first);
        int deaths = deathCount(bot);
        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 2);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_pillar_second_coal"));

        int[] tallest = {0, 0};
        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "the task ended as " + task.state() + ":" + task.failureReason()
                        + " at " + bot.blockPosition().toShortString());
            }
            tallest[0] = Math.max(tallest[0], towerBlocks(world, first));
            tallest[1] = Math.max(tallest[1], towerBlocks(world, second));
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(first).isAir() && world.getBlockState(second).isAir(),
                    "a coal was not mined");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 2,
                    "the coal drops were not collected: " + InventoryAction.countItem(bot, Items.COAL));
            require(context, tallest[0] >= 1 && tallest[1] >= 1,
                    "a coal was reached without a pillar of cobblestone beside it: " + tallest[0] + "," + tallest[1]);
            requireBackDown(context, world, before, first, second);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == RESERVE + 8,
                    "the towers' blocks did not all come back: " + InventoryAction.countItem(bot, Items.COBBLESTONE));
            finish(context, fixture);
        });
    }

    @SuppressWarnings("unchecked")
    private static Deque<BlockPos> queuedVeinMembers(OreDigTask task) {
        try {
            Field field = OreDigTask.class.getDeclaredField("veinQueue");
            field.setAccessible(true);
            return (Deque<BlockPos>) field.get(task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("could not reach the vein queue of the task", exception);
        }
    }

    /**
     * The coal is mined from the top of a pillar of cobblestone built in the one column beside it and collected, and the tower
     * is taken down again: nothing but the coal is changed once the task is done, and every block came back.
     *
     * @param blocks  the cobblestone the bot was given
     * @param minTower the height the tower must have reached at its tallest
     */
    private static void pillarsUpAndMines(GameTestHelper context, Fixture fixture, OreDigTask task, int blocks, int minTower) {
        AIPlayerEntity bot = fixture.bot();
        ServerLevel world = bot.level();
        BlockPos ore = fixture.ore();
        Map<BlockPos, BlockState> before = snapshot(world, ore);
        int deaths = deathCount(bot);
        float health = bot.getHealth();

        int[] tallest = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            require(context, bot.getHealth() >= health, "the bot was hurt");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "the task ended as " + task.state() + ":" + task.failureReason()
                        + " at " + bot.blockPosition().toShortString());
            }
            tallest[0] = Math.max(tallest[0], towerBlocks(world, ore));
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(ore).isAir(), "the coal was not mined");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 1,
                    "the coal drop was not collected: " + InventoryAction.countItem(bot, Items.COAL));
            require(context, tallest[0] >= minTower,
                    "the pillar beside the coal had " + tallest[0] + " blocks at its tallest, not " + minTower);
            // The task is not done until the bot is back on the ground: the tower is gone and its blocks are in the inventory.
            requireBackDown(context, world, before, ore);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocks,
                    "the blocks of the tower did not all come back: " + InventoryAction.countItem(bot, Items.COBBLESTONE)
                            + " of " + blocks);
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 3600)
    public void withNoBlocksBeyondTheProtectedStoneReserveTheCoalIsLeftIntactAndNothingIsPlaced(GameTestHelper context) {
        Fixture fixture = build(context, "PillarNoBlocksGT", 4, RESERVE, Blocks.BEDROCK);
        refusedWithoutBuilding(context, fixture, RESERVE);
    }

    /**
     * A tower of eight blocks is more than a route steps down from (the safe fall), and a route never breaks the block under the bot's
     * own feet: the bot takes the tower down the way a player does, one block at a time, and every block comes back.
     */
    @GameTest(maxTicks = 4800)
    public void aCoalNineBlocksUpIsReachedByATowerTallerThanTheSafeFallThatIsTakenDownAgain(GameTestHelper context) {
        Fixture fixture = build(context, "PillarTallGT", 9, RESERVE + 12);
        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(fixture.bot(), task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_pillar_tall"));
        int minTower = 8;
        require(context, minTower > MinecraftAiConfig.get().nav().maxSafeFall(),
                "fixture: the tower must be taller than the bot can step down from");
        pillarsUpAndMines(context, fixture, task, RESERVE + 12, minTower);
    }

    /** The coal stays, nothing is placed and the bot comes to no harm, once the task has given the coal up. */
    private static void refusedWithoutBuilding(GameTestHelper context, Fixture fixture, int blocks) {
        AIPlayerEntity bot = fixture.bot();
        ServerLevel world = bot.level();
        BlockPos ore = fixture.ore();
        Map<BlockPos, BlockState> before = snapshot(world, ore);
        int deaths = deathCount(bot);
        float health = bot.getHealth();
        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_pillar_refused"));

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            require(context, bot.getHealth() >= health, "the bot was hurt");
            require(context, world.getBlockState(ore).is(Blocks.COAL_ORE), "the coal was mined");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocks,
                    "blocks were placed: " + InventoryAction.countItem(bot, Items.COBBLESTONE) + " of " + blocks + " left");
            if (!EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), ore, world.getServer().getTickCount())) {
                return;
            }
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                require(context, world.getBlockState(entry.getKey()).equals(entry.getValue()),
                        "the refusal still changed " + entry.getKey().toShortString());
            }
            finish(context, fixture);
        });
    }

    /**
     * A pillar route places from the first support stack the bot carries, until it is gone, and cannot be given the mission's protected
     * stone reserve: so a pillar is started only when that stack alone holds its blocks beyond the reserve.
     */
    @GameTest(maxTicks = 20)
    public void aPillarIsStartedOnlyFromAStackThatHoldsItBeyondTheProtectedStoneReserve(GameTestHelper context) {
        Fixture fixture = build(context, "PillarReserveGT", 4, 10);
        AIPlayerEntity bot = fixture.bot();
        require(context, MaterialPalette.spendableFirstPillarSupports(bot, 0) == 10, "no reserve spends all ten cobblestone");
        require(context, MaterialPalette.spendableFirstPillarSupports(bot, 4) == 6, "4 protected: 6 cobblestone");
        require(context, MaterialPalette.spendableFirstPillarSupports(bot, 10) == 0, "all of it protected");
        require(context, MaterialPalette.spendableFirstPillarSupports(bot, 99) == 0, "a reserve beyond the stock protects it all");
        require(context, bot.getActionPack().startPillarPathTo(fixture.ore().above(), 7, 4).reason()
                        .equals("pillar_support_below_reserve"),
                "a pillar of 7 blocks was started from 6 spendable ones");

        // Dirt comes first in the order the route places in, and nothing of it is protected.
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 4));
        require(context, MaterialPalette.spendableFirstPillarSupports(bot, 0) == 4
                        && MaterialPalette.spendableFirstPillarSupports(bot, 99) == 4,
                "the dirt is the stack that is placed from, and it is all spendable");
        require(context, bot.getActionPack().startPillarPathTo(fixture.ore().above(), 5, 99).reason()
                        .equals("pillar_support_below_reserve"),
                "a pillar of 5 blocks was started from a stack of 4");
        finish(context, fixture);
    }

    // ---- fixture ---------------------------------------------------------------------------------------------------

    private record Fixture(String name, AIPlayerEntity bot, BlockPos ore) {
    }

    private static final int MIN_X = -4;
    private static final int MAX_X = 10;
    private static final int MIN_Z = -8;
    private static final int MAX_Z = 8;
    private static final int TOP = 10;

    /** How far along the wall the second coal of a test sits (two higher): not a neighbour of the first. */
    private static final int SECOND_COAL_OFFSET = 4;
    /** The tallest tower, in cells under the coal, that a test looks for. */
    private static final int TOWER_REACH = 12;

    /** The coal at {@code ore} in a stone wall with a cave in front of it, its floor {@code height} under the coal. */
    private static void carveCave(ServerLevel world, BlockPos ore, int height, Block wall) {
        for (int dx = MIN_X; dx <= MAX_X; dx++) {
            for (int dy = -height - 6; dy <= TOP; dy++) {
                for (int dz = MIN_Z; dz <= MAX_Z; dz++) {
                    boolean cave = dx >= 1 && dx <= 9 && Math.abs(dz) <= 6 && dy >= -height;
                    world.setBlock(ore.offset(dx, dy, dz),
                            cave ? Blocks.AIR.defaultBlockState() : wall.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** The coal at the origin of a stone wall, a cave in front of it, and the bot on its floor {@code height} under the coal. */
    private static Fixture build(GameTestHelper context, String name, int height, int cobblestone) {
        return build(context, name, height, cobblestone, Blocks.STONE);
    }

    private static Fixture build(GameTestHelper context, String name, int height, int cobblestone, Block wall) {
        ServerLevel world = context.getLevel();
        BlockPos ore = context.absolutePos(new BlockPos(8, 20, 8));
        carveCave(world, ore, height, wall);
        BlockPos stand = ore.offset(OUT + 1, -height, 0).immutable();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(stand), 90.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, Set.of(), 90.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        if (cobblestone > 0) {
            InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, cobblestone));
        }
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        // A bot that has just joined sees nothing until its chunk view is set up a tick or two later.
        context.runAfterDelay(8, () -> require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "fixture: the coal must be in the bot's view across the cave"));
        return new Fixture(name, bot, ore);
    }

    private static Map<BlockPos, BlockState> snapshot(ServerLevel world, BlockPos ore) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int dx = MIN_X; dx <= MAX_X; dx++) {
            for (int dy = -20; dy <= TOP; dy++) {
                for (int dz = MIN_Z; dz <= MAX_Z; dz++) {
                    BlockPos pos = ore.offset(dx, dy, dz).immutable();
                    states.put(pos, world.getBlockState(pos));
                }
            }
        }
        return states;
    }

    /** The cobblestone standing in the column beside {@code ore} where a pillar is built to reach it. */
    private static int towerBlocks(ServerLevel world, BlockPos ore) {
        int blocks = 0;
        for (int dy = -TOWER_REACH; dy <= 1; dy++) {
            if (world.getBlockState(ore.offset(1, dy, 0)).is(Blocks.COBBLESTONE)) {
                blocks++;
            }
        }
        return blocks;
    }

    /** Nothing is left of the pillar: every cell of the fixture is as it was, but for the coal that was mined. */
    private static void requireBackDown(GameTestHelper context, ServerLevel world, Map<BlockPos, BlockState> before,
                                        BlockPos... mined) {
        Set<BlockPos> ores = Set.of(mined);
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            BlockState now = world.getBlockState(entry.getKey());
            boolean expected = ores.contains(entry.getKey()) ? now.isAir() : now.equals(entry.getValue());
            require(context, expected, "the tower was not taken down: " + entry.getKey().toShortString() + " is "
                    + now.getBlock() + " instead of " + (ores.contains(entry.getKey()) ? "air" : entry.getValue().getBlock().toString()));
        }
    }

    private static int deathCount(AIPlayerEntity bot) {
        return bot.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
    }

    private static void finish(GameTestHelper context, Fixture fixture) {
        TaskManager.INSTANCE.cancelIntentTasks(fixture.bot(), "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
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
}
