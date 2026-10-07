package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.gametest.GameTestSightDistance;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import java.lang.reflect.Field;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * What a bot does with a target 20 to 30 blocks away, as it sees it in play. The harness gives every bot 16 blocks of block sight and
 * a bot in play has 32 ({@link GameTestSightDistance}), so no other test of the suite exercises a target sighted between the two: each
 * test here opts in with {@code useProductionView}. They pin that sight reaches that far and no farther, that it passes what the
 * eyes pass and stops at what they cannot (a wall hides a target at 24 blocks as it does at 4), and that the tasks built on sight
 * (the hunt, the gatherer's look-around for a tree or a canopy, the explorer's look-around) use the whole of it.
 */
public final class LongSightGameTests {
    private static final String ENV = "minecraftai-gametest:long_sight_game_tests_";
    /** The slab of the world every sealed room of this class is built in; tests of the suite run alone, so it is free. */
    private static final int SLAB = 150;
    private static final int ROOM_HEIGHT = 8;
    /** The sight of a bot in play, in blocks. */
    private static final int SIGHT = GameTestSightDistance.BOT_BLOCK_SIGHT;
    /** More than the whole raster of either look-around needs: 2 phases x 320 x 48 samples at 62 a step is under 1000. */
    private static final int SWEEP_STEPS = 1200;

    // ---------------------------------------------------------------------------------------------
    // Block sight: how far, through what, and what still hides a target
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV + "a_block_in_open_air_is_seen_out_to_the_block_sight_and_not_beyond", maxTicks = 40)
    public void aBlockInOpenAirIsSeenOutToTheBlockSightAndNotBeyond(GameTestHelper context) {
        Arena arena = new Arena(context, "LongSightOpenGT", 44);
        // One lane each, so no block stands in the way of another.
        int[][] seen = {{20, -4}, {26, 0}, {31, 4}};
        int[][] unseen = {{36, -2}, {40, 2}};
        for (int[] cell : seen) {
            arena.room.set(cell[0], 1, cell[1], Blocks.STONE);
        }
        for (int[] cell : unseen) {
            arena.room.set(cell[0], 1, cell[1], Blocks.STONE);
        }
        context.runAfterDelay(3L, () -> {
            arena.require(ObservableWorldQuery.visibleRangeBlocks(arena.bot) == SIGHT,
                    "the bot's block sight is " + ObservableWorldQuery.visibleRangeBlocks(arena.bot) + ", not " + SIGHT);
            for (int[] cell : seen) {
                BlockPos block = arena.room.at(cell[0], 1, cell[1]);
                arena.require(ObservableWorldQuery.canObserveBlock(arena.bot, block),
                        "a stone block " + cell[0] + " blocks away in open air is not in sight");
            }
            for (int[] cell : unseen) {
                BlockPos block = arena.room.at(cell[0], 1, cell[1]);
                arena.require(!ObservableWorldQuery.canObserveBlock(arena.bot, block),
                        "a stone block " + cell[0] + " blocks away is in sight past the block sight of " + SIGHT);
            }
            arena.finish();
        });
    }

    @GameTest(environment = ENV + "a_log_behind_leaves_or_glass_is_seen_beyond_twenty_blocks_and_one_behind_stone_is_not", maxTicks = 40)
    public void aLogBehindLeavesOrGlassIsSeenBeyondTwentyBlocksAndOneBehindStoneIsNot(GameTestHelper context) {
        Arena arena = new Arena(context, "LongSightThroughGT", 30);
        // Three lanes; in each the target log stands at 24 behind a 3x3 barrier two blocks deep (glass: one deep).
        int target = 24;
        barrier(arena, -4, 2, leaves());
        barrier(arena, 0, 1, Blocks.GLASS.defaultBlockState());
        barrier(arena, 4, 2, Blocks.STONE.defaultBlockState());
        for (int lane : new int[] {-4, 0, 4}) {
            arena.room.set(target, 1, lane, Blocks.OAK_LOG);
        }
        context.runAfterDelay(3L, () -> {
            arena.require(ObservableWorldQuery.canObserveBlock(arena.bot, arena.room.at(target, 1, -4)),
                    "a log 24 blocks away behind two leaves is not in sight: the eyes pass leaves");
            arena.require(ObservableWorldQuery.canObserveBlock(arena.bot, arena.room.at(target, 1, 0)),
                    "a log 24 blocks away behind glass is not in sight: the eyes pass glass");
            arena.require(!ObservableWorldQuery.canObserveBlock(arena.bot, arena.room.at(target, 1, 4)),
                    "a log 24 blocks away behind stone is in sight: the eyes do not pass stone");
            arena.finish();
        });
    }

    @GameTest(environment = ENV + "a_target_behind_a_wall_beyond_twenty_blocks_is_not_seen_until_the_wall_is_gone", maxTicks = 40)
    public void aTargetBehindAWallBeyondTwentyBlocksIsNotSeenUntilTheWallIsGone(GameTestHelper context) {
        Arena arena = new Arena(context, "LongSightWallGT", 34);
        int[][] targets = {{24, 0}, {30, 3}};
        for (int[] cell : targets) {
            arena.room.set(cell[0], 1, cell[1], Blocks.IRON_ORE);
        }
        arena.room.wall(10);
        context.runAfterDelay(3L, () -> {
            for (int[] cell : targets) {
                BlockPos ore = arena.room.at(cell[0], 1, cell[1]);
                arena.require(!ObservableWorldQuery.canObserveBlock(arena.bot, ore)
                                && !ObservableWorldQuery.canObserveBlockCellFace(arena.bot, ore)
                                && !ObservableWorldQuery.canObserveBlockWithInsetFaces(arena.bot, ore),
                        "iron ore " + cell[0] + " blocks away behind a wall is in sight");
            }
            // The control: the same targets with the wall taken away are in sight, so it was the wall that hid them.
            for (int dy = 0; dy < ROOM_HEIGHT; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    arena.room.set(10, dy, dz, Blocks.AIR);
                }
            }
            for (int[] cell : targets) {
                BlockPos ore = arena.room.at(cell[0], 1, cell[1]);
                arena.require(ObservableWorldQuery.canObserveBlock(arena.bot, ore),
                        "iron ore " + cell[0] + " blocks away is not in sight once the wall is gone");
            }
            arena.finish();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The tasks that run on sight
    // ---------------------------------------------------------------------------------------------

    @GameTest(environment = ENV + "hunt_walks_to_prey_twenty_four_blocks_away_that_is_in_block_sight", maxTicks = 1600)
    public void huntWalksToPreyTwentyFourBlocksAwayThatIsInBlockSight(GameTestHelper context) {
        hunt(context, "LongSightHuntNearGT", 24);
    }

    @GameTest(environment = ENV + "hunt_walks_to_prey_forty_blocks_away_that_is_beyond_block_sight", maxTicks = 1600)
    public void huntWalksToPreyFortyBlocksAwayThatIsBeyondBlockSight(GameTestHelper context) {
        hunt(context, "LongSightHuntFarGT", 40);
    }

    /**
     * The open-ground fixture of {@code HuntCrossRegionGameTests} with the production block sight: the cow is nominated either way
     * (prey sight is 64), but at 24 blocks it is inside block sight and the hunt proves its ground like any other target, while at
     * 40 it is outside and the hunt walks observed legs toward it until its ground comes into sight. The test ends when the cow is
     * hurt; a hunt that fails first fails the test.
     */
    private static void hunt(GameTestHelper context, String name, int distance) {
        GameTestSightDistance.useProductionView(context);
        var world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(4, 0, 4));
        int baseY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        BlockPos start = origin.atY(baseY);
        for (int dx = -2; dx <= distance + 4; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = start.getX() + dx;
                int z = start.getZ() + dz;
                int localTop = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = baseY + 1; y <= localTop + 3; y++) {
                    world.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                BlockPos feet = new BlockPos(x, baseY, z);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "failed to create cow");
        cow.setNoAi(true);
        cow.snapTo(start.getX() + distance + 0.5D, start.getY(), start.getZ() + 0.5D, 270.0F, 0.0F);
        require(context, world.addFreshEntity(cow), "failed to spawn cow");

        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(world.dimension().identifier().toString(), start.getX(), start.getY(), start.getZ());
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_long_sight_hunt"));

        float initialHealth = cow.getHealth();
        context.runAfterDelay(3L, () -> {
            require(context, ObservableWorldQuery.visibleRangeBlocks(bot) == SIGHT,
                    "the bot's block sight is " + ObservableWorldQuery.visibleRangeBlocks(bot) + ", not " + SIGHT);
            require(context, ObservableWorldQuery.canObserveEntityWithin(bot, cow, 64),
                    "the cow " + distance + " blocks away on open ground is not in prey sight");
        });
        context.failIfEver(() -> {
            if (cow.getHealth() < initialHealth || !cow.isAlive()) {
                AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                context.succeed();
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("the hunt of a cow " + distance + " blocks away ended as " + task.state() + ":"
                        + task.failureReason() + " before the cow was hurt"));
            }
        });
    }

    @GameTest(environment = ENV + "the_tree_look_around_sights_a_trunk_and_an_overhead_canopy_twenty_four_blocks_away", maxTicks = 40)
    public void theTreeLookAroundSightsATrunkAndAnOverheadCanopyTwentyFourBlocksAway(GameTestHelper context) {
        // The gatherer's own look-around for a tree, run to the end of its raster: the sighting it would pursue.
        Arena arena = new Arena(context, "LongSightTreeGT", 34);
        for (int y = 0; y < 5; y++) {
            arena.room.set(24, y, 0, Blocks.OAK_LOG);
        }
        context.runAfterDelay(3L, () -> {
            TreeHorizonScan.Sighting trunk = sweep(arena.bot, new TreeHorizonScan(Set.of(Blocks.OAK_LOG)));
            arena.require(trunk != null && trunk.kind() == TreeHorizonScan.Kind.LOG
                            && trunk.pos().getX() == arena.room.at(24, 0, 0).getX() && trunk.pos().getZ() == arena.room.at(24, 0, 0).getZ(),
                    "the look-around did not sight the trunk 24 blocks away: " + trunk);
            for (int y = 0; y < 5; y++) {
                arena.room.set(24, y, 0, Blocks.AIR);
            }
            // A canopy is a mass, not one block: seven up and 24 away, five by five logs.
            for (int dx = 22; dx <= 26; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    arena.room.set(dx, 7, dz, Blocks.OAK_LOG);
                }
            }
            TreeHorizonScan.Sighting canopy = sweep(arena.bot, new TreeHorizonScan(Set.of(Blocks.OAK_LOG)));
            arena.require(canopy != null && canopy.kind() == TreeHorizonScan.Kind.LOG
                            && canopy.pos().getY() == arena.room.at(0, 7, 0).getY()
                            && canopy.pos().getX() >= arena.room.at(22, 0, 0).getX() && canopy.pos().getX() <= arena.room.at(26, 0, 0).getX(),
                    "the look-around did not sight the canopy seven up and 24 blocks away: " + canopy);
            arena.finish();
        });
    }

    @GameTest(environment = ENV + "the_tree_look_around_does_not_sight_a_trunk_twenty_four_blocks_away_behind_a_wall", maxTicks = 40)
    public void theTreeLookAroundDoesNotSightATrunkTwentyFourBlocksAwayBehindAWall(GameTestHelper context) {
        Arena arena = new Arena(context, "LongSightTreeWallGT", 34);
        for (int y = 0; y < 5; y++) {
            arena.room.set(24, y, 0, Blocks.OAK_LOG);
        }
        arena.room.wall(10);
        context.runAfterDelay(3L, () -> {
            TreeHorizonScan scan = new TreeHorizonScan(Set.of(Blocks.OAK_LOG));
            TreeHorizonScan.Sighting seen = sweep(arena.bot, scan);
            arena.require(scan.complete(), "the look-around did not run to the end of its raster");
            arena.require(seen == null, "the look-around sighted a trunk behind a wall: " + seen);
            arena.finish();
        });
    }

    @GameTest(environment = ENV + "the_target_look_around_sights_ore_twenty_four_blocks_away_through_leaves_and_not_behind_stone", maxTicks = 40)
    public void theTargetLookAroundSightsOreTwentyFourBlocksAwayThroughLeavesAndNotBehindStone(GameTestHelper context) {
        // The look-around every ore, mine and gather request shares: the block itself has to be in sight.
        Arena arena = new Arena(context, "LongSightOreGT", 34);
        BlockPos ore = arena.room.at(24, 1, 0);
        arena.room.set(24, 1, 0, Blocks.IRON_ORE);
        barrier(arena, 0, 2, leaves());
        context.runAfterDelay(3L, () -> {
            VisibleTargetHorizonScan.Sighting through = sweep(arena.bot, new VisibleTargetHorizonScan(Set.of(Blocks.IRON_ORE)));
            arena.require(through != null && ore.equals(through.pos()),
                    "the look-around did not sight iron ore 24 blocks away behind two leaves: " + through);
            barrier(arena, 0, 2, Blocks.STONE.defaultBlockState());
            VisibleTargetHorizonScan scan = new VisibleTargetHorizonScan(Set.of(Blocks.IRON_ORE));
            VisibleTargetHorizonScan.Sighting behind = sweep(arena.bot, scan);
            arena.require(scan.complete(), "the look-around did not run to the end of its raster");
            arena.require(behind == null, "the look-around sighted iron ore behind stone: " + behind);
            arena.finish();
        });
    }

    @GameTest(environment = ENV + "the_explorers_look_around_credits_what_the_bot_sees_out_to_its_block_sight_and_no_farther", maxTicks = 40)
    public void theExplorersLookAroundCreditsWhatTheBotSeesOutToItsBlockSightAndNoFarther(GameTestHelper context) {
        // A bot configured to observe 40 blocks still sees 32: it may credit as searched only the ground it saw, which is 32 where
        // nothing blocked its view and the distance to the wall where something did.
        MinecraftAiConfig original = MinecraftAiConfig.get();
        GameTestCleanup.whenFinished(context, () -> installConfig(original));
        installConfig(withPerceptionRadius(original, 40));
        Arena arena = new Arena(context, "LongSightLookGT", 44);
        int east = ExplorationMemory.sector(1.0D, 0.0D);
        context.runAfterDelay(3L, () -> {
            double open = ObservedSearchHops.lookAround(arena.bot, 40)[east];
            arena.require(open == SIGHT, "the look-around credited " + open + " blocks of open ground to the east, the bot sees " + SIGHT);
            arena.room.wall(22);
            double walled = ObservedSearchHops.lookAround(arena.bot, 40)[east];
            arena.require(walled > 21.0D && walled < 22.5D,
                    "the look-around credited " + walled + " blocks toward a wall 22 blocks to the east");
            arena.finish();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /** Steps a look-around until it sights something or its raster is done. */
    private static TreeHorizonScan.Sighting sweep(AIPlayerEntity bot, TreeHorizonScan scan) {
        for (int step = 0; step < SWEEP_STEPS && !scan.complete(); step++) {
            TreeHorizonScan.Sighting seen = scan.step(bot);
            if (seen != null) {
                return seen;
            }
        }
        return null;
    }

    private static VisibleTargetHorizonScan.Sighting sweep(AIPlayerEntity bot, VisibleTargetHorizonScan scan) {
        for (int step = 0; step < SWEEP_STEPS && !scan.complete(); step++) {
            VisibleTargetHorizonScan.Sighting seen = scan.step(bot);
            if (seen != null) {
                return seen;
            }
        }
        return null;
    }

    /** A barrier three by three across a lane, {@code depth} blocks deep, starting at dx 22, whose cells are {@code state}. */
    private static void barrier(Arena arena, int lane, int depth, BlockState state) {
        for (int dx = 22; dx < 22 + depth; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = lane - 1; dz <= lane + 1; dz++) {
                    arena.room.world.setBlock(arena.room.at(dx, dy, dz), state, Block.UPDATE_ALL);
                }
            }
        }
    }

    /** A leaf that cannot decay: nothing here is a tree it could belong to. */
    private static BlockState leaves() {
        return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    /** A long sealed room under the production view, with a bot at its west end. */
    private static final class Arena {
        final GameTestHelper context;
        final Room room;
        final AIPlayerEntity bot;
        final String name;

        Arena(GameTestHelper context, String name, int maxDx) {
            GameTestSightDistance.useProductionView(context);
            this.context = context;
            this.name = name;
            this.room = new Room(context, SLAB, -4, maxDx, -6, 6, ROOM_HEIGHT);
            // Light everywhere: a hostile in view would make the bot's danger watcher react to it.
            for (int dx = -3; dx < maxDx; dx += 3) {
                for (int dz = -5; dz < 6; dz += 3) {
                    room.set(dx, ROOM_HEIGHT - 1, dz, Blocks.LIGHT);
                }
            }
            this.bot = AIPlayerManager.INSTANCE.spawn(context.getLevel().getServer(), name, room.world,
                            Vec3.atBottomCenterOf(room.feet), 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            bot.teleportTo(room.world, room.feet.getX() + 0.5D, room.feet.getY(), room.feet.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
        }

        void require(boolean condition, String message) {
            if (!condition) {
                cleanup();
                context.fail(Component.nullToEmpty(message));
                throw new IllegalStateException(message);
            }
        }

        void finish() {
            cleanup();
            context.succeed();
        }

        private void cleanup() {
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
            room.clear();
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    /** Test-only immutable-config replacement, as in the sibling GameTests. */
    private static MinecraftAiConfig withPerceptionRadius(MinecraftAiConfig config, int radius) {
        MinecraftAiConfig.Perception perception = config.perception();
        return new MinecraftAiConfig(config.profile(), config.operatorCapabilities(), config.llm(),
                new MinecraftAiConfig.Perception(radius, perception.maxBlocks(), perception.maxEntities(),
                        perception.maxItems(), perception.includeRawLists()),
                config.brain(), config.watchdog(), config.logging(), config.survival(), config.combat(), config.night(),
                config.mining(), config.goal(), config.nav(), config.pickup(), config.conversation(), config.storage(),
                config.behaviour());
    }

    private static void installConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install GameTest config", exception);
        }
    }
}
