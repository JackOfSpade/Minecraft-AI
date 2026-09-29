package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.LightType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static io.github.zoyluo.minecraftai.task.SensingArena.botLog;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;

/**
 * Real-server proofs that the danger watcher's AUTOMATIC lighting reflexes (night top-up and the dark-spot
 * reflex) never light the surface, while lighting under a roof and every explicit request keep working:
 * the {@link SurfaceCheck} block classifier, then idle bots at night on open grass and under a tree canopy
 * (no torch, a throttled {@code auto_light_skipped}), in a dark enclosed room (lit), under a cave-mouth
 * overhang (torches only under the roof) and an explicit light_area on the surface (still lit).
 */
public final class AutomaticLightingSurfaceGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-auto-light-gametest");
    private static final long MIDNIGHT = 18000L;
    /** Longer than the 200-tick surface recheck, so the reflex has looked at the bot at least twice. */
    private static final int OBSERVE_TICKS = 260;
    private static final int TORCHES_GIVEN = 8;

    @GameTest(environment = "minecraftai-gametest:automatic_lighting_surface_game_tests_surface_check_classifies_canopy_mushroom_and_roof_blocks", maxTicks = 100)
    public void surfaceCheckClassifiesCanopyMushroomAndRoofBlocks(TestContext context) {
        for (Block block : new Block[]{
                Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG, Blocks.OAK_WOOD, Blocks.STRIPPED_BIRCH_WOOD, Blocks.SPRUCE_LOG,
                Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG, Blocks.PALE_OAK_LOG, Blocks.CRIMSON_STEM, Blocks.WARPED_HYPHAE,
                Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES,
                Blocks.MANGROVE_LEAVES, Blocks.CHERRY_LEAVES, Blocks.PALE_OAK_LEAVES,
                Blocks.VINE, Blocks.COCOA, Blocks.BEE_NEST, Blocks.BEEHIVE, Blocks.MANGROVE_ROOTS,
                Blocks.MUDDY_MANGROVE_ROOTS, Blocks.MANGROVE_PROPAGULE, Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET,
                Blocks.PALE_HANGING_MOSS, Blocks.SNOW,
                Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM,
                Blocks.RED_MUSHROOM, Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS, Blocks.NETHER_WART_BLOCK,
                Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT,
                Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DANDELION,
                Blocks.OAK_SAPLING, Blocks.SUGAR_CANE, Blocks.BAMBOO}) {
            require(context, SurfaceCheck.classify(block.getDefaultState()) == SurfaceColumn.Cell.CANOPY,
                    block + " must not count as a roof");
        }
        for (BlockState open : new BlockState[]{Blocks.AIR.getDefaultState(), Blocks.CAVE_AIR.getDefaultState(),
                Blocks.WATER.getDefaultState(), Blocks.LAVA.getDefaultState()}) {
            require(context, SurfaceCheck.classify(open) == SurfaceColumn.Cell.OPEN, open + " must be open");
        }
        for (Block block : new Block[]{
                Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.OAK_SLAB,
                Blocks.GLASS, Blocks.GLASS_PANE, Blocks.BEDROCK, Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.WHITE_WOOL,
                Blocks.OAK_TRAPDOOR, Blocks.CHEST, Blocks.TORCH, Blocks.SCAFFOLDING, Blocks.COBWEB, Blocks.ICE,
                Blocks.POWDER_SNOW, Blocks.CACTUS, Blocks.HAY_BLOCK}) {
            require(context, SurfaceCheck.classify(block.getDefaultState()) == SurfaceColumn.Cell.ROOF,
                    block + " must count as a roof");
        }

        // The same decision on real columns.
        ServerWorld world = context.getWorld();
        BlockPos base = context.getAbsolutePos(new BlockPos(20, 5, 5));
        int topCell = world.getBottomY() + world.getHeight() - 1;
        BlockState leaves = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
        BlockState stone = Blocks.STONE.getDefaultState();
        Column[] columns = {
                new Column("open sky", true, base, new BlockPos[0], new BlockState[0]),
                new Column("leaves+log canopy", true, base.east(2),
                        new BlockPos[]{base.east(2).up(3), base.east(2).up(4), base.east(2).up(5)},
                        new BlockState[]{Blocks.OAK_LOG.getDefaultState(), leaves, leaves}),
                new Column("mushroom cap", true, base.east(4),
                        new BlockPos[]{base.east(4).up(3)}, new BlockState[]{Blocks.RED_MUSHROOM_BLOCK.getDefaultState()}),
                new Column("water above", true, base.east(6),
                        new BlockPos[]{base.east(6).up(2)}, new BlockState[]{Blocks.WATER.getDefaultState()}),
                new Column("tall grass fills the head cell", true, base.east(8),
                        new BlockPos[]{base.east(8), base.east(8).up()},
                        new BlockState[]{Blocks.TALL_GRASS.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.LOWER),
                                Blocks.TALL_GRASS.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.UPPER)}),
                new Column("stone roof", false, base.east(10),
                        new BlockPos[]{base.east(10).up(2)}, new BlockState[]{stone}),
                new Column("glass roof", false, base.east(12),
                        new BlockPos[]{base.east(12).up(2)}, new BlockState[]{Blocks.GLASS.getDefaultState()}),
                new Column("stone above a canopy", false, base.east(14),
                        new BlockPos[]{base.east(14).up(2), base.east(14).up(6)}, new BlockState[]{leaves, stone}),
                new Column("roof in the very last cell of the world", false, base.east(16),
                        new BlockPos[]{new BlockPos(base.getX() + 16, topCell, base.getZ())}, new BlockState[]{stone}),
        };
        List<String> failures = new ArrayList<>();
        for (Column column : columns) {
            for (int i = 0; i < column.cells().length; i++) {
                world.setBlockState(column.cells()[i], column.states()[i], Block.NOTIFY_ALL);
            }
            if (SurfaceCheck.isOnSurface(world, column.feet()) != column.expectedSurface()) {
                failures.add(column.name() + ": expected surface=" + column.expectedSurface());
            }
        }
        for (Column column : columns) {
            for (BlockPos cell : column.cells()) {
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        require(context, failures.isEmpty(), "column decisions wrong: " + failures);
        context.complete();
    }

    @GameTest(environment = "minecraftai-gametest:automatic_lighting_surface_game_tests_night_idle_bot_on_open_grass_places_no_torch_and_logs_the_skip", maxTicks = 3000)
    public void nightIdleBotOnOpenGrassPlacesNoTorchAndLogsTheSkip(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos feet = context.getAbsolutePos(new BlockPos(20, 5, 30));
        buildGrassPlot(world, feet, 9);
        AIPlayerEntity bot = spawn(context, "AutoLightGrassGT", feet);
        giveTorches(bot, TORCHES_GIVEN);
        observeNoTorchAtNight(context, bot, feet, 9, "open grass");
    }

    @GameTest(environment = "minecraftai-gametest:automatic_lighting_surface_game_tests_night_idle_bot_under_tree_canopy_places_no_torch", maxTicks = 3000)
    public void nightIdleBotUnderTreeCanopyPlacesNoTorch(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos feet = context.getAbsolutePos(new BlockPos(20, 5, 60));
        buildGrassPlot(world, feet, 9);
        // A big canopy: two persistent leaf layers over 9x9 plus a log column beside the bot. Leaves count as
        // opaque for isSkyVisible, so the combined-light gate of the dark-spot reflex fires under it at night.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 3; dy <= 4; dy++) {
                    world.setBlockState(feet.add(dx, dy, dz),
                            Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true), Block.NOTIFY_ALL);
                }
            }
        }
        for (int dy = 0; dy <= 4; dy++) {
            world.setBlockState(feet.add(2, dy, 0), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
        AIPlayerEntity bot = spawn(context, "AutoLightTreeGT", feet);
        giveTorches(bot, TORCHES_GIVEN);
        observeNoTorchAtNight(context, bot, feet, 9, "tree canopy");
    }

    @GameTest(environment = "minecraftai-gametest:automatic_lighting_surface_game_tests_night_idle_bot_in_dark_enclosed_room_lights_it", maxTicks = 3000)
    public void nightIdleBotInDarkEnclosedRoomLightsIt(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos feet = context.getAbsolutePos(new BlockPos(20, 5, 90));
        int radius = 5;
        buildEnclosure(world, feet, radius);
        AIPlayerEntity bot = spawn(context, "AutoLightRoomGT", feet);
        giveTorches(bot, 16);
        int threshold = MinecraftAiConfig.get().night().torchLightThreshold();
        boolean[] sawLightArea = {false};
        int[] settle = {0};
        TimeLockedRun.run(context, 1500, () -> {
            world.setTimeOfDay(MIDNIGHT);
            require(context, !SurfaceCheck.isOnSurface(world, feet), "fixture room is not under a roof");
            TaskManager.INSTANCE.getActive(bot).ifPresent(task -> sawLightArea[0] |= "light_area".equals(task.name()));
            if (!sawLightArea[0] || TaskManager.INSTANCE.getActive(bot).isPresent()) {
                return false;
            }
            if (++settle[0] < 20) {
                return false; // let the light engine settle before reading block light
            }
            int torches = torchCells(world, feet, radius + 1).size();
            require(context, torches >= 1, "the room was not lit: no torch placed");
            for (BlockPos cell : floorCells(feet, radius)) {
                int light = world.getLightLevel(LightType.BLOCK, cell);
                require(context, light >= threshold,
                        "floor cell " + cell + " is still dark (" + light + ") with " + torches + " torches placed");
            }
            return true;
        }, () -> cleanUp(bot, "AutoLightRoomGT"));
    }

    @GameTest(environment = "minecraftai-gametest:automatic_lighting_surface_game_tests_cave_mouth_overhang_automatic_lighting_only_places_torches_under_the_roof", maxTicks = 3000)
    public void caveMouthOverhangAutomaticLightingOnlyPlacesTorchesUnderTheRoof(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos feet = context.getAbsolutePos(new BlockPos(20, 5, 120));
        int radius = 7;
        // A 15x15 stone floor; the WEST half (dx <= -1) has a stone overhang three cells up, the EAST half is
        // open sky (a cave mouth). No walls. The bot stands under the overhang.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    world.setBlockState(cell.up(dy), dy == 3 && dx <= -1
                            ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        BlockPos standing = feet.add(-3, 0, 0);
        AIPlayerEntity bot = spawn(context, "AutoLightMouthGT", standing);
        giveTorches(bot, 16);
        boolean[] sawLightArea = {false};
        int[] settle = {0};
        TimeLockedRun.run(context, 1500, () -> {
            world.setTimeOfDay(MIDNIGHT);
            require(context, !SurfaceCheck.isOnSurface(world, standing), "the bot is not under the overhang");
            require(context, SurfaceCheck.isOnSurface(world, feet.add(3, 0, 0)), "the mouth half is not open sky");
            TaskManager.INSTANCE.getActive(bot).ifPresent(task -> sawLightArea[0] |= "light_area".equals(task.name()));
            if (!sawLightArea[0] || TaskManager.INSTANCE.getActive(bot).isPresent()) {
                return false;
            }
            if (++settle[0] < 20) {
                return false;
            }
            List<BlockPos> torches = torchCells(world, feet, radius + 1);
            require(context, !torches.isEmpty(), "the area under the overhang was not lit");
            for (BlockPos torch : torches) {
                require(context, !SurfaceCheck.isOnSurface(world, torch),
                        "automatic lighting placed a torch on a surface cell " + torch.toShortString()
                                + " (all torches: " + torches + ")");
            }
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null) {
                LOG.warn("[surface-cell skip log check skipped: no per-bot log] {}", bot.getGameProfile().name());
            } else {
                require(context, lines.stream().anyMatch(line -> line.contains("event=light_area_surface_cells_skipped")),
                        "the surface cells were never rejected from the lighting pool (no light_area_surface_cells_skipped)");
            }
            return true;
        }, () -> cleanUp(bot, "AutoLightMouthGT"));
    }

    @GameTest(environment = "minecraftai-gametest:automatic_lighting_surface_game_tests_explicit_light_area_on_the_surface_still_places_torches", maxTicks = 600)
    public void explicitLightAreaOnTheSurfaceStillPlacesTorches(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos feet = context.getAbsolutePos(new BlockPos(20, 5, 150));
        buildGrassPlot(world, feet, 9);
        AIPlayerEntity bot = spawn(context, "ExplicitLightGT", feet);
        giveTorches(bot, 6);
        require(context, SurfaceCheck.isOnSurface(world, feet), "fixture is not open to the sky");
        LightAreaTask task = new LightAreaTask(6, 3);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_explicit_light_area"));
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException(Text.of(
                        "explicit light_area ended as " + task.state() + ": " + task.failureReason()));
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            List<BlockPos> torches = torchCells(world, feet, 7);
            require(context, !torches.isEmpty(), "an explicit light_area on the surface placed no torch");
            for (BlockPos torch : torches) {
                require(context, SurfaceCheck.isOnSurface(world, torch), "torch " + torch + " is not on the surface");
            }
            ShelterGameTestFixtures.finish(context, bot, "ExplicitLightGT");
        });
    }

    // ---- scenario driver: the reflexes must leave a surface bot alone ----

    private static void observeNoTorchAtNight(TestContext context, AIPlayerEntity bot, BlockPos feet, int radius,
                                              String where) {
        ServerWorld world = context.getWorld();
        int threshold = MinecraftAiConfig.get().night().torchLightThreshold();
        int[] ticks = {0};
        TimeLockedRun.run(context, 1500, () -> {
            world.setTimeOfDay(MIDNIGHT);
            ticks[0]++;
            require(context, SurfaceCheck.isOnSurface(world, feet), "fixture (" + where + ") is not open to the sky");
            TaskManager.INSTANCE.getActive(bot).ifPresent(task -> require(context, !"light_area".equals(task.name()),
                    "automatic lighting started on the surface (" + where + ")"));
            if (ticks[0] == 10 && where.equals("tree canopy")) {
                require(context, !world.isSkyVisible(feet), "fixture canopy did not block direct sky visibility");
                int combined = world.getLightLevel(feet, world.getAmbientDarkness());
                require(context, combined < threshold,
                        "fixture is not dark enough to exercise the dark-spot reflex: combined=" + combined);
            }
            if (ticks[0] < OBSERVE_TICKS) {
                return false;
            }
            require(context, torchCells(world, feet, radius).isEmpty(), "a torch was placed on the surface (" + where + ")");
            require(context, InventoryAction.countItem(bot, Items.TORCH) == TORCHES_GIVEN,
                    "the bot spent torches on the surface (" + where + ")");
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null) {
                LOG.warn("[auto_light_skipped log check skipped: no per-bot log] {}", bot.getGameProfile().name());
            } else {
                long skips = lines.stream()
                        .filter(line -> line.contains("event=auto_light_skipped") && line.contains("surface")).count();
                if (skips < 1 && ticks[0] < OBSERVE_TICKS + 200) {
                    return false; // the per-bot log is written asynchronously; give it a moment
                }
                require(context, skips == 1,
                        "expected exactly one throttled auto_light_skipped in " + OBSERVE_TICKS + "+ ticks, saw " + skips);
            }
            return true;
        }, () -> cleanUp(bot, bot.getGameProfile().name()));
    }

    // ---- fixtures ----

    private static void cleanUp(AIPlayerEntity bot, String name) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        DangerWatcher.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.getEntityWorld().getServer(), name);
    }

    private record Column(String name, boolean expectedSurface, BlockPos feet, BlockPos[] cells, BlockState[] states) {
    }

    private static void buildGrassPlot(ServerWorld world, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    world.setBlockState(cell.up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    /** A stone box with a 3-high air interior: floor at feet.y-1, roof at feet.y+3, walls at |d| = radius + 1. */
    private static void buildEnclosure(ServerWorld world, BlockPos feet, int radius) {
        int wall = radius + 1;
        for (int dx = -wall; dx <= wall; dx++) {
            for (int dz = -wall; dz <= wall; dz++) {
                boolean onWall = Math.abs(dx) == wall || Math.abs(dz) == wall;
                world.setBlockState(feet.add(dx, -1, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlockState(feet.add(dx, dy, dz), onWall || dy == 3
                            ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    private static List<BlockPos> floorCells(BlockPos feet, int radius) {
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                cells.add(feet.add(dx, 0, dz));
            }
        }
        return cells;
    }

    private static List<BlockPos> torchCells(ServerWorld world, BlockPos feet, int radius) {
        List<BlockPos> torches = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    BlockPos cell = feet.add(dx, dy, dz);
                    if (world.getBlockState(cell).isOf(Blocks.TORCH)) {
                        torches.add(cell);
                    }
                }
            }
        }
        return torches;
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        return bot;
    }

    private static void giveTorches(AIPlayerEntity bot, int count) {
        for (int slot = 0; slot < bot.getInventory().getMainStacks().size(); slot++) {
            bot.getInventory().getMainStacks().set(slot, ItemStack.EMPTY);
        }
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getMainStacks().set(0, new ItemStack(Items.TORCH, count));
        bot.getInventory().markDirty();
    }
}
