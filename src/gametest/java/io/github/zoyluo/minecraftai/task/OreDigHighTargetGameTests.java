package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * Live strict-survival coverage for the stair OreDig digs up to an ore that hangs out of reach.
 *
 * <p>Fixture (frame relative to the coal): solid stone all round. The bot stands in a sealed two-cell
 * pit seven blocks under the coal and three blocks east of it, and sees the coal's east face along
 * its own line of sight, through a slit of air cut exactly along that line (the way a player sees an
 * ore through a crack in the rock). There is no ledge, cave or open column to walk or pillar up: the
 * only way to the coal is to dig up to it.</p>
 */
public final class OreDigHighTargetGameTests {
    private static final int HEIGHT = 7;
    private static final int PIT_EAST = 3;
    private static final int MIN_X = -6;
    private static final int MAX_X = 8;
    private static final int MIN_Y = -10;
    private static final int MAX_Y = 4;
    private static final int MIN_Z = -8;
    private static final int MAX_Z = 8;

    @GameTest(environment = "minecraftai-gametest:ore_dig_high_target_game_tests_stair_is_dug_up_to_coal_seven_blocks_overhead", maxTicks = 3600)
    public void stairIsDugUpToCoalSevenBlocksOverhead(GameTestHelper context) {
        Fixture fixture = build(context, "HighCoalGT", true);
        AIPlayerEntity bot = fixture.bot();
        ServerLevel world = bot.level();
        BlockPos ore = fixture.ore();
        int deaths = deathCount(bot);
        Map<BlockPos, BlockState> before = snapshot(world, ore);
        List<BlockPos> climbed = new ArrayList<>();
        WalkBack[] wayBack = {null};

        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_high_coal"));

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            if (wayBack[0] != null) {
                wayBack[0].tick();
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                fail(context, "the task ended as " + task.state() + ":" + task.failureReason()
                        + " at " + bot.blockPosition().toShortString());
            }
            if (world.getBlockState(ore).is(Blocks.COAL_ORE)) {
                BlockPos here = bot.blockPosition();
                if (bot.onGround() && (climbed.isEmpty() || !climbed.get(climbed.size() - 1).equals(here))) {
                    climbed.add(here.immutable());
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(ore).isAir(), "the coal was not mined");
            require(context, InventoryAction.countItem(bot, Items.COAL) >= 1,
                    "the coal drop was not collected: " + InventoryAction.countItem(bot, Items.COAL));
            require(context, world.getBlockState(ore.below()).is(Blocks.STONE),
                    "the cell under the coal, which holds its drop, was dug");

            // The climb ends beside the coal, at head height, and was made of ordinary treads.
            BlockPos top = climbed.get(climbed.size() - 1);
            require(context, ore.getY() - top.getY() <= 2
                            && Math.abs(top.getX() - ore.getX()) + Math.abs(top.getZ() - ore.getZ()) <= 1,
                    "the bot never stood within reach of the coal: top=" + top);
            for (int i = 0; i < climbed.size(); i++) {
                BlockPos stand = climbed.get(i);
                Standability.clearCache();
                require(context, Standability.isStandable(world, stand),
                        "the tread at " + stand.toShortString() + " cannot be stood on");
                if (i > 0) {
                    BlockPos previous = climbed.get(i - 1);
                    int dy = stand.getY() - previous.getY();
                    int manhattan = Math.abs(stand.getX() - previous.getX())
                            + Math.abs(stand.getZ() - previous.getZ());
                    require(context, manhattan == 1 && (dy == 0 || dy == 1),
                            "the climb was not a staircase of one-block treads: " + previous.toShortString()
                                    + " -> " + stand.toShortString());
                }
            }
            require(context, climbed.size() >= HEIGHT,
                    "the stair has fewer treads than the coal is high: " + climbed.size());
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                BlockState now = world.getBlockState(entry.getKey());
                require(context, now.equals(entry.getValue()) || now.isAir(),
                        "something was placed in the rock at " + entry.getKey().toShortString());
            }
            // And there is a way back: the bot walks down the very treads it climbed.
            require(context, bot.blockPosition().equals(top),
                    "the bot ended away from the top of its stair: " + bot.blockPosition().toShortString());
            wayBack[0] = new WalkBack(context, fixture, climbed);
            wayBack[0].tick();
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_high_target_game_tests_sand_on_the_route_refuses_the_stair_and_leaves_the_coal_intact", maxTicks = 900)
    public void sandOnTheRouteRefusesTheStairAndLeavesTheCoalIntact(GameTestHelper context) {
        Fixture fixture = build(context, "HighCoalSandGT", true);
        // The cell above the first level tunnel cell: sand there would fall into the tunnel and bury the bot.
        BlockPos sand = fixture.ore().offset(PIT_EAST - 1, -HEIGHT + 1, 0);
        fixture.bot().level().setBlock(sand, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
        refusedWithoutDigging(context, fixture, Set.of(), "sand");
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_high_target_game_tests_lava_under_the_first_tread_refuses_the_stair_before_stepping", maxTicks = 900)
    public void lavaUnderTheFirstTreadRefusesTheStairBeforeStepping(GameTestHelper context) {
        Fixture fixture = build(context, "HighCoalLavaGT", true);
        // A source in a stone-walled pocket under the first tread: static, and hidden until that tread is opened.
        BlockPos lava = fixture.ore().offset(PIT_EAST - 1, -HEIGHT - 1, 0);
        fixture.bot().level().setBlock(lava, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        Set<BlockPos> opened = Set.of(
                fixture.ore().offset(PIT_EAST - 1, -HEIGHT, 0), fixture.ore().offset(PIT_EAST - 1, -HEIGHT + 1, 0));
        refusedWithoutDigging(context, fixture, opened, "lava");
    }

    /**
     * Lava in the rock over the cell above the bot's head, on the stair's second rise. The rock hides it, so nothing forbids opening
     * that cell; opening it is what shows the lava, and the stair must stop there instead of digging on past it.
     */
    @GameTest(environment = "minecraftai-gametest:ore_dig_high_target_game_tests_lava_above_an_opened_cell_stops_the_stair_before_the_next_cell", maxTicks = 1800)
    public void lavaAboveAnOpenedCellStopsTheStairBeforeTheNextCell(GameTestHelper context) {
        Fixture fixture = build(context, "HighCoalLavaRoofGT", true);
        AIPlayerEntity bot = fixture.bot();
        ServerLevel world = bot.level();
        BlockPos ore = fixture.ore();
        // The second rise stands at ore+(1,-6,1) and opens ore+(1,-4,1), then its landing ore+(0,-5,1) and that landing's head cell.
        BlockPos standing = ore.offset(1, -HEIGHT + 1, 1);
        BlockPos roof = ore.offset(1, -4, 1);
        BlockPos landing = ore.offset(0, -5, 1);
        world.setBlock(roof.above(), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        int deaths = deathCount(bot);
        float health = bot.getHealth();

        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_high_coal_lava_roof"));

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            require(context, bot.getHealth() >= health, "the bot was hurt");
            require(context, world.getBlockState(ore).is(Blocks.COAL_ORE), "the coal was mined past the lava");
            if (!EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), ore, world.getServer().getTickCount())) {
                return;
            }
            require(context, world.getBlockState(roof).isAir(),
                    "the stair gave the coal up before it opened the cell under the lava: the fixture did not play out");
            require(context, world.getBlockState(landing).is(Blocks.STONE)
                            && world.getBlockState(landing.above()).is(Blocks.STONE),
                    "the stair went on digging after it had seen the lava overhead");
            require(context, bot.blockPosition().equals(standing),
                    "the bot stepped on toward the lava: " + bot.blockPosition().toShortString());
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:ore_dig_high_target_game_tests_open_cell_under_the_coal_refuses_the_stair_as_drop_catch_unproven", maxTicks = 900)
    public void openCellUnderTheCoalRefusesTheStairAsDropCatchUnproven(GameTestHelper context) {
        Fixture fixture = build(context, "HighCoalOpenGT", true);
        ServerLevel world = fixture.bot().level();
        // The drop of a coal with nothing under it would fall down the open cell and out of reach.
        BlockPos support = fixture.ore().below();
        cutSight(world, fixture.bot().getEyePosition(), Vec3.atCenterOf(support), support);
        world.setBlock(support, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.runAfterDelay(8, () -> require(context, ObservableWorldQuery.canObserveCell(fixture.bot(), support),
                "fixture: the open cell under the coal must be in view"));
        refusedWithoutDigging(context, fixture, Set.of(), "open_support");
    }

    /**
     * Runs the task until it gives the coal up, and checks that it did so without opening any rock
     * beyond {@code allowedChanges}, without leaving its pit, and without harm.
     */
    private static void refusedWithoutDigging(GameTestHelper context, Fixture fixture,
                                              Set<BlockPos> allowedChanges, String hazard) {
        AIPlayerEntity bot = fixture.bot();
        ServerLevel world = bot.level();
        BlockPos ore = fixture.ore();
        int deaths = deathCount(bot);
        Map<BlockPos, BlockState> before = snapshot(world, ore);
        float health = bot.getHealth();

        OreDigTask task = new OreDigTask(OreScan.oreFamily(Blocks.COAL_ORE), 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_high_coal_" + hazard));

        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deaths, "miner died");
            require(context, bot.getHealth() >= health, "the bot was hurt");
            require(context, world.getBlockState(ore).is(Blocks.COAL_ORE), "the coal was mined past a " + hazard);
            require(context, bot.blockPosition().equals(fixture.pit()),
                    "the bot left its pit toward a " + hazard + ": " + bot.blockPosition().toShortString());
            if (!EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), ore, world.getServer().getTickCount())) {
                return;
            }
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                BlockState now = world.getBlockState(entry.getKey());
                if (now.equals(entry.getValue())) {
                    continue;
                }
                require(context, allowedChanges.contains(entry.getKey()) && now.isAir(),
                        "the " + hazard + " refusal still changed " + entry.getKey().toShortString() + " from "
                                + entry.getValue().getBlock() + " to " + now.getBlock());
            }
            finish(context, fixture);
        });
    }

    // ---- fixture ---------------------------------------------------------------------------------------------------

    private record Fixture(String name, AIPlayerEntity bot, BlockPos ore, BlockPos pit) {
    }

    private static Fixture build(GameTestHelper context, String name, boolean pickaxe) {
        ServerLevel world = context.getLevel();
        BlockPos ore = context.absolutePos(new BlockPos(8, 12, 8));
        for (int dx = MIN_X; dx <= MAX_X; dx++) {
            for (int dy = MIN_Y; dy <= MAX_Y; dy++) {
                for (int dz = MIN_Z; dz <= MAX_Z; dz++) {
                    world.setBlock(ore.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos pit = ore.offset(PIT_EAST, -HEIGHT, 0).immutable();
        world.setBlock(pit, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(pit.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(pit), 90.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, pit.getX() + 0.5D, pit.getY(), pit.getZ() + 0.5D, Set.of(), 90.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        if (pickaxe) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());

        // The slit: every cell the bot's eye ray to the coal's east face crosses.
        cutSight(world, bot.getEyePosition(), new Vec3(ore.getX() + 1.001D, ore.getY() + 0.5D, ore.getZ() + 0.5D), ore);
        // A bot that has just joined sees nothing until its chunk view is set up a tick or two later.
        context.runAfterDelay(8, () -> require(context, ObservableWorldQuery.canObserveBlock(bot, ore),
                "fixture: the coal must be in the bot's view through the slit"));
        return new Fixture(name, bot, ore, pit);
    }

    /** Clears every cell on the straight line from {@code eye} to {@code target}, except {@code keep}. */
    private static void cutSight(ServerLevel world, Vec3 eye, Vec3 target, BlockPos keep) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        int steps = 2000;
        for (int i = 0; i <= steps; i++) {
            BlockPos cell = BlockPos.containing(eye.lerp(target, i / (double) steps));
            if (!cell.equals(keep)) {
                cells.add(cell.immutable());
            }
        }
        for (BlockPos cell : cells) {
            world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** Walks the bot down the treads it climbed, one walked step at a time, and ends the test at the first. */
    private static final class WalkBack {
        private final GameTestHelper context;
        private final Fixture fixture;
        private final List<BlockPos> treads;
        /** The index of the tread the bot stands on. */
        private int index;
        private ActionPack.StepLease lease;
        private int ticks;

        private WalkBack(GameTestHelper context, Fixture fixture, List<BlockPos> treads) {
            this.context = context;
            this.fixture = fixture;
            this.treads = treads;
            this.index = treads.size() - 1;
        }

        void tick() {
            AIPlayerEntity bot = fixture.bot();
            ActionPack pack = bot.getActionPack();
            if (lease != null) {
                if (pack.stepInFlightFor(lease)) {
                    require(context, ++ticks <= 80,
                            "the walk down to " + treads.get(index - 1).toShortString() + " stalled");
                    return;
                }
                WalkedStep.Result result = pack.stepResultFor(lease);
                BlockPos reached = treads.get(index - 1);
                require(context, result != null && result.succeeded() && bot.blockPosition().equals(reached),
                        "the walk down to " + reached.toShortString() + " failed at "
                                + bot.blockPosition().toShortString() + ": " + result);
                index--;
                lease = null;
                ticks = 0;
            }
            if (index == 0) {
                finish(context, fixture);
                return;
            }
            BlockPos target = treads.get(index - 1);
            WalkedStep.Kind kind = WalkedStepRules.walkKindFor(target.getY() - bot.blockPosition().getY());
            require(context, kind != null, "no walked step goes down to " + target.toShortString());
            String refused = WalkedStep.refusal(bot, target, kind);
            require(context, refused == null,
                    "the walk down to " + target.toShortString() + " was refused: " + refused);
            lease = pack.runStep(WalkedStep.begin(bot, target, kind, "gametest_way_back"));
            require(context, lease != null, "the walk down to " + target.toShortString() + " was not admitted");
        }
    }

    private static Map<BlockPos, BlockState> snapshot(ServerLevel world, BlockPos ore) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int dx = MIN_X; dx <= MAX_X; dx++) {
            for (int dy = MIN_Y; dy <= MAX_Y; dy++) {
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
