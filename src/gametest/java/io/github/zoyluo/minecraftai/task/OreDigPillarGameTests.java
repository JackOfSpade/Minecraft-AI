package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.HarvestCore;
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
 * <p>Fixture (frame relative to the coal): a wall of stone with the coal in its face, a cave in front of it, the bot on
 * the cave floor {@code height} blocks under the coal and four blocks out from the wall. There is no ledge to walk up and
 * nothing to dig through: the only way to the coal is to build up beside it.</p>
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
        pillarsUpAndMines(context, fixture, task);
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
        pillarsUpAndMines(context, fixture, task);
    }

    /**
     * The tower of the first pillar must not cap the pillars of the rest of the task: a second coal in a cave whose floor is higher than the
     * first tower can be (a hillside above it) is built up to from that floor, counted from that ground.
     */
    @GameTest(maxTicks = 7200)
    public void aSecondCoalOnHigherGroundIsBuiltUpToFromThatGroundNotFromTheFirstTowersGround(GameTestHelper context) {
        Fixture fixture = build(context, "PillarHigherGT", 4, RESERVE + 12);
        AIPlayerEntity bot = fixture.bot();
        ServerLevel world = bot.level();
        BlockPos highOre = fixture.ore().offset(0, HIGHER_GROUND, HIGH_CAVE_OFFSET);
        carveCave(world, highOre, 4);
        BlockPos highStand = highOre.offset(OUT + 1, -4, 0);
        int deaths = deathCount(bot);
        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 2);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_pillar_higher_ground"));

        boolean[] onHigherGround = {false};
        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "the task ended as " + task.state() + ":" + task.failureReason()
                        + " at " + bot.blockPosition().toShortString());
            }
            if (!onHigherGround[0]) {
                // The first coal is mined from the top of its tower; the bot then climbs the hillside to the second cave.
                if (world.getBlockState(fixture.ore()).isAir() && InventoryAction.countItem(bot, Items.COAL) >= 1) {
                    onHigherGround[0] = true;
                    bot.teleportTo(world, highStand.getX() + 0.5D, highStand.getY(), highStand.getZ() + 0.5D,
                            Set.of(), 90.0F, 0.0F, true);
                }
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(highOre).isAir(), "the second coal was not mined");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 2,
                    "the second coal drop was not collected: " + InventoryAction.countItem(bot, Items.COAL));
            // Not by a stair dug into the wall, which the bot also falls back to after refusing the pillar for a while.
            int tower = 0;
            for (int dy = -4; dy <= 0; dy++) {
                if (world.getBlockState(highOre.offset(1, dy, 0)).is(Blocks.COBBLESTONE)) {
                    tower++;
                }
            }
            require(context, tower >= 1, "the second coal was reached without a pillar of cobblestone beside it");
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

    /** The coal is mined from the top of a pillar of cobblestone built in the one column beside it, and collected. */
    private static void pillarsUpAndMines(GameTestHelper context, Fixture fixture, OreDigTask task) {
        AIPlayerEntity bot = fixture.bot();
        ServerLevel world = bot.level();
        BlockPos ore = fixture.ore();
        Map<BlockPos, BlockState> before = snapshot(world, ore);
        int deaths = deathCount(bot);
        float health = bot.getHealth();

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            require(context, bot.getHealth() >= health, "the bot was hurt");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "the task ended as " + task.state() + ":" + task.failureReason()
                        + " at " + bot.blockPosition().toShortString());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(ore).isAir(), "the coal was not mined");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 1,
                    "the coal drop was not collected: " + InventoryAction.countItem(bot, Items.COAL));
            // Only the pillar was built, in the one column beside the coal, and no taller than the bot can step down from.
            int placed = 0;
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                BlockState now = world.getBlockState(entry.getKey());
                if (now.equals(entry.getValue())) {
                    continue;
                }
                if (entry.getKey().equals(ore)) {
                    continue;
                }
                require(context, now.is(Blocks.COBBLESTONE) && entry.getValue().isAir()
                                && entry.getKey().getX() == ore.getX() + 1 && entry.getKey().getZ() == ore.getZ(),
                        "something other than the pillar changed at " + entry.getKey().toShortString() + " from "
                                + entry.getValue().getBlock() + " to " + now.getBlock());
                placed++;
            }
            require(context, placed >= 1 && placed <= HarvestCore.maxPillarSupports(),
                    "the pillar has " + placed + " blocks; the bot can leave one of at most "
                            + HarvestCore.maxPillarSupports());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == RESERVE + 6 - placed,
                    "the blocks that went into the pillar are not the ones missing from the inventory");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 3600)
    public void withNoBlocksBeyondTheProtectedStoneReserveTheCoalIsLeftIntactAndNothingIsPlaced(GameTestHelper context) {
        Fixture fixture = build(context, "PillarNoBlocksGT", 4, RESERVE);
        refusedWithoutBuilding(context, fixture, RESERVE);
    }

    @GameTest(maxTicks = 3600)
    public void coalTooHighForATowerTheBotCanLeaveIsLeftIntactAndNothingIsPlaced(GameTestHelper context) {
        Fixture fixture = build(context, "PillarTooHighGT", 9, RESERVE + 12);
        refusedWithoutBuilding(context, fixture, RESERVE + 12);
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

    /** Where the second cave of a test sits against the first: further along the wall, with a floor higher than any tower of the first pillar. */
    private static final int HIGHER_GROUND = 6;
    private static final int HIGH_CAVE_OFFSET = 24;

    /** The coal at {@code ore} in a stone wall with a cave in front of it, its floor {@code height} under the coal. */
    private static void carveCave(ServerLevel world, BlockPos ore, int height) {
        for (int dx = MIN_X; dx <= MAX_X; dx++) {
            for (int dy = -height - 6; dy <= TOP; dy++) {
                for (int dz = MIN_Z; dz <= MAX_Z; dz++) {
                    boolean cave = dx >= 1 && dx <= 9 && Math.abs(dz) <= 6 && dy >= -height;
                    world.setBlock(ore.offset(dx, dy, dz),
                            cave ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** The coal at the origin of a stone wall, a cave in front of it, and the bot on its floor {@code height} under the coal. */
    private static Fixture build(GameTestHelper context, String name, int height, int cobblestone) {
        ServerLevel world = context.getLevel();
        BlockPos ore = context.absolutePos(new BlockPos(8, 20, 8));
        carveCave(world, ore, height);
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
