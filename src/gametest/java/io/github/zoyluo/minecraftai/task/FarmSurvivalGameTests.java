package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestChunkForcing;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Real-server proof (strict_survival, the deployed profile) that the farm actions work the way a
 * player's do: existing fields are seen (crops have no collider, interior farmland is 15/16 high),
 * every world change goes through real item use (hoe durability, seed light rules, bucket rules),
 * harvest drops are picked up by walking, and bone meal speeds a wait for maturity.
 */
public final class FarmSurvivalGameTests {
    private static final BlockState MATURE_WHEAT =
            Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7);
    private static final BlockState WET_FARMLAND =
            Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_raid_harvests_interior_crops_and_walks_over_the_drops", maxTicks = 900)
    public void raidHarvestsInteriorCropsAndWalksOverTheDrops(GameTestHelper context) {
        raidScenario(context, 14, "FarmRaidGT");
    }

    private void raidScenario(GameTestHelper context, int relZ, String botName) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, relZ));
        forceChunks(context, feet, 12);
        prepareGround(world, feet, 12);
        // 7x7 field of ripe wheat: only the outer ring has exposed farmland sides.
        field(world, feet.offset(2, 0, -3), 7, 7, MATURE_WHEAT);
        AIPlayerEntity bot = spawnBot(context, botName, feet);
        requireStrict(context, bot);

        RaidCropsTask task = new RaidCropsTask(6);
        long startTick = context.getTick() + 5;
        context.failIfEver(() -> {
            if (context.getTick() < startTick) {
                return;
            }
            if (task.state() == TaskState.PENDING) {
                task.start(bot);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                if (context.getTick() > 850) {
                    context.fail(Component.nullToEmpty("raid timed out: " + task.describe()
                            + " wheat=" + InventoryAction.countItem(bot, Items.WHEAT)));
                    return;
                }
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "raid_crops did not complete: " + task.failureReason());
            int wheat = InventoryAction.countItem(bot, Items.WHEAT);
            require(context, wheat >= 6,
                    "raid harvested but the drops were left on the ground: wheat=" + wheat);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_farm_quota_harvests_existing_field_picks_up_drops_and_replants", maxTicks = 1200)
    public void farmQuotaHarvestsExistingFieldPicksUpDropsAndReplants(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, 34));
        forceChunks(context, feet, 12);
        prepareGround(world, feet, 12);
        BlockPos fieldOrigin = feet.offset(2, 0, -2);
        field(world, fieldOrigin, 5, 5, MATURE_WHEAT);
        AIPlayerEntity bot = spawnBot(context, "FarmQuotaGT", feet);
        requireStrict(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 4));

        FarmTask task = new FarmTask(fieldOrigin.offset(2, 0, 2), 3, Items.WHEAT_SEEDS, Blocks.WHEAT,
                false, false, Items.WHEAT, 3);
        // Let the light engine settle before the first replant so the vanilla light rule is not racing it.
        long startTick = context.getTick() + 5;
        context.failIfEver(() -> {
            if (context.getTick() < startTick) {
                return;
            }
            if (task.state() == TaskState.PENDING) {
                task.start(bot);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                if (context.getTick() > 1100) {
                    context.fail(Component.nullToEmpty("farm timed out: " + task.describe()
                            + " wheat=" + InventoryAction.countItem(bot, Items.WHEAT)));
                    return;
                }
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "farm quota did not complete: " + task.failureReason());
            int wheat = InventoryAction.countItem(bot, Items.WHEAT);
            require(context, wheat >= 3, "harvest drops were left on the ground: wheat=" + wheat);
            int planted = 0;
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    if (world.getBlockState(fieldOrigin.offset(x, 0, z)).is(Blocks.WHEAT)) {
                        planted++;
                    }
                }
            }
            require(context, planted == 25,
                    "harvested cells were not replanted with real seeds: crops=" + planted + "/25");
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_seeds_are_not_planted_in_darkness_but_are_once_lit", maxTicks = 200)
    public void seedsAreNotPlantedInDarknessButAreOnceLit(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, 54));
        forceChunks(context, feet, 12);
        // Sealed 9x9 room: stone walls and roof, so sky light cannot reach the floor.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean wall = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                for (int dy = -1; dy <= 3; dy++) {
                    boolean shell = wall || dy == -1 || dy == 3;
                    world.setBlock(feet.offset(dx, dy, dz),
                            shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }
        BlockPos farmland = feet.offset(0, -1, 0);
        world.setBlock(farmland, WET_FARMLAND, Block.UPDATE_ALL);
        AIPlayerEntity bot = spawnBot(context, "FarmDarkGT", feet.offset(0, 0, -2));
        requireStrict(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 3));
        BlockPos crop = farmland.above();

        context.failIfEver(() -> {
            long tick = context.getTick();
            if (tick == 12) {
                require(context, world.getRawBrightness(crop, 0) < 8,
                        "fixture room is not dark: light=" + world.getRawBrightness(crop, 0));
                ActionResult dark = FarmAction.plant(bot, farmland, Items.WHEAT_SEEDS, Blocks.WHEAT);
                require(context, dark.isFailed() && !world.getBlockState(crop).is(Blocks.WHEAT),
                        "seed was planted in the dark (crops there never grow): " + dark);
                require(context, InventoryAction.countItem(bot, Items.WHEAT_SEEDS) == 3,
                        "a refused planting must not consume the seed");
                world.setBlock(feet.offset(0, 0, 1), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
            } else if (tick == 40) {
                require(context, world.getRawBrightness(crop, 0) >= 8,
                        "torch did not light the cell: light=" + world.getRawBrightness(crop, 0));
                ActionResult lit = FarmAction.plant(bot, farmland, Items.WHEAT_SEEDS, Blocks.WHEAT);
                require(context, lit.isSuccess() && world.getBlockState(crop).is(Blocks.WHEAT),
                        "seed was not planted on lit farmland: " + lit);
                require(context, InventoryAction.countItem(bot, Items.WHEAT_SEEDS) == 2,
                        "vanilla planting must consume exactly one seed");
                context.succeed();
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_tilling_costs_hoe_durability", maxTicks = 100)
    public void tillingCostsHoeDurability(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, 74));
        forceChunks(context, feet, 12);
        prepareGround(world, feet, 6);
        BlockPos dirt = feet.offset(1, -1, 0);
        world.setBlock(dirt, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawnBot(context, "FarmTillGT", feet);
        requireStrict(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HOE, 1));

        context.failIfEver(() -> {
            if (context.getTick() != 5) {
                return;
            }
            ActionResult till = FarmAction.till(bot, dirt);
            require(context, till.isSuccess(), "till failed: " + till);
            require(context, world.getBlockState(dirt).is(Blocks.FARMLAND), "till did not make farmland");
            ItemStack hoe = bot.getMainHandItem();
            require(context, hoe.is(Items.IRON_HOE) && hoe.getDamageValue() >= 1,
                    "the hoe lost no durability: " + hoe);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_bone_meal_speeds_the_wait_for_maturity", maxTicks = 900)
    public void boneMealSpeedsTheWaitForMaturity(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, 94));
        forceChunks(context, feet, 12);
        prepareGround(world, feet, 12);
        BlockPos fieldOrigin = feet.offset(2, 0, -1);
        field(world, fieldOrigin, 3, 3, Blocks.WHEAT.defaultBlockState());
        AIPlayerEntity bot = spawnBot(context, "FarmBoneGT", feet);
        requireStrict(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.BONE_MEAL, 32));

        FarmTask task = new FarmTask(fieldOrigin.offset(1, 0, 1), 2, Items.WHEAT_SEEDS, Blocks.WHEAT,
                false, false, Items.WHEAT, 2);
        long startTick = context.getTick() + 5;
        context.failIfEver(() -> {
            if (context.getTick() < startTick) {
                return;
            }
            if (task.state() == TaskState.PENDING) {
                task.start(bot);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                if (context.getTick() > 850) {
                    context.fail(Component.nullToEmpty("bone meal farm timed out: " + task.describe()
                            + " wheat=" + InventoryAction.countItem(bot, Items.WHEAT)
                            + " bone_meal=" + InventoryAction.countItem(bot, Items.BONE_MEAL)));
                    return;
                }
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "bone meal farm did not complete: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.WHEAT) >= 2,
                    "no wheat collected after bone meal");
            require(context, InventoryAction.countItem(bot, Items.BONE_MEAL) < 32,
                    "no bone meal was used");
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_irrigate_pours_real_buckets_into_a_dug_pit", maxTicks = 1200)
    public void irrigatePoursRealBucketsIntoADugPit(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, 114));
        forceChunks(context, feet, 12);
        for (int dx = -4; dx <= 5; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlock(feet.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawnBot(context, "FarmIrrigGT", feet);
        requireStrict(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 2));
        BlockPos center = feet.offset(2, -1, 0);

        IrrigateTask task = new IrrigateTask(center);
        long startTick = context.getTick() + 5;
        context.failIfEver(() -> {
            if (context.getTick() < startTick) {
                return;
            }
            if (task.state() == TaskState.PENDING) {
                task.start(bot);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                if (context.getTick() > 1100) {
                    context.fail(Component.nullToEmpty("irrigate timed out: " + task.describe()));
                    return;
                }
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "irrigate did not complete: " + task.failureReason() + " cells="
                            + world.getBlockState(center) + "|" + world.getBlockState(center.east()) + "|"
                            + world.getBlockState(center.south()) + "|" + world.getBlockState(center.east().south()));
            int sources = 0;
            for (BlockPos cell : new BlockPos[] {center, center.east(), center.south(), center.east().south()}) {
                var fluid = world.getFluidState(cell);
                if (fluid.is(FluidTags.WATER) && fluid.isSource()) {
                    sources++;
                }
            }
            require(context, sources == 4, "expected a 2x2 source pool, got " + sources + " sources");
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 2
                            && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0,
                    "bucket inventory does not match two pours");
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_milking_keeps_the_bucket_stack_and_fills_one_at_a_time", maxTicks = 400)
    public void milkingKeepsTheBucketStackAndFillsOneAtATime(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, 134));
        forceChunks(context, feet, 12);
        prepareGround(world, feet, 6);
        AIPlayerEntity bot = spawnBot(context, "FarmMilkGT", feet);
        requireStrict(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET, 3));
        Cow cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, cow != null, "could not create a cow");
        cow.snapTo(feet.getX() + 2.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        cow.setNoAi(true);
        world.addFreshEntity(cow);
        GameTestCleanup.whenFinished(context, cow::discard);

        MilkCowTask task = new MilkCowTask(2);
        long startTick = context.getTick() + 5;
        context.failIfEver(() -> {
            if (context.getTick() < startTick) {
                return;
            }
            if (task.state() == TaskState.PENDING) {
                task.start(bot);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                if (context.getTick() > 350) {
                    context.fail(Component.nullToEmpty("milking timed out: " + task.describe()));
                    return;
                }
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "milk_cow did not complete: " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.MILK_BUCKET) == 2
                            && InventoryAction.countItem(bot, Items.BUCKET) == 1,
                    "wrong bucket bookkeeping: milk=" + InventoryAction.countItem(bot, Items.MILK_BUCKET)
                            + " empty=" + InventoryAction.countItem(bot, Items.BUCKET));
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_farm_task_tills_and_plants_dirt_with_real_items", maxTicks = 900)
    public void farmTaskTillsAndPlantsDirtWithRealItems(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, 154));
        forceChunks(context, feet, 12);
        prepareGround(world, feet, 8);
        BlockPos patch = feet.offset(2, 0, -1);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                world.setBlock(patch.offset(x, -1, z), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = spawnBot(context, "FarmTillPlantGT", feet);
        requireStrict(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HOE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 9));

        FarmTask task = new FarmTask(patch.offset(1, 0, 1), 1, Items.WHEAT_SEEDS, Blocks.WHEAT, false, false);
        long startTick = context.getTick() + 5;
        context.failIfEver(() -> {
            if (context.getTick() < startTick) {
                return;
            }
            if (task.state() == TaskState.PENDING) {
                task.start(bot);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                if (context.getTick() > 850) {
                    context.fail(Component.nullToEmpty("till/plant timed out: " + task.describe()));
                    return;
                }
                task.tick(bot);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "farm did not complete: " + task.failureReason());
            int crops = 0;
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    if (world.getBlockState(patch.offset(x, 0, z)).is(Blocks.WHEAT)
                            && world.getBlockState(patch.offset(x, -1, z)).is(Blocks.FARMLAND)) {
                        crops++;
                    }
                }
            }
            require(context, crops == 9, "expected 9 wheat on farmland, got " + crops);
            require(context, InventoryAction.countItem(bot, Items.WHEAT_SEEDS) == 0,
                    "seeds were not consumed by planting: " + InventoryAction.countItem(bot, Items.WHEAT_SEEDS));
            ItemStack hoe = InventoryAction.findItem(bot, Items.IRON_HOE).isPresent()
                    ? bot.getInventory().getNonEquipmentItems().get(
                            InventoryAction.findItem(bot, Items.IRON_HOE).getAsInt())
                    : ItemStack.EMPTY;
            require(context, hoe.getDamageValue() >= 9, "tilling 9 cells cost the hoe " + hoe.getDamageValue());
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:farm_survival_game_tests_farm_task_in_a_dark_room_fails_typed_and_plants_nothing", maxTicks = 200)
    public void farmTaskInADarkRoomFailsTypedAndPlantsNothing(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(14, 4, 174));
        forceChunks(context, feet, 12);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean wall = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                for (int dy = -1; dy <= 3; dy++) {
                    boolean shell = wall || dy == 3;
                    world.setBlock(feet.offset(dx, dy, dz),
                            shell ? Blocks.STONE.defaultBlockState()
                                    : dy == -1 ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawnBot(context, "FarmDarkTaskGT", feet);
        requireStrict(context, bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HOE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 9));

        FarmTask task = new FarmTask(feet, 2, Items.WHEAT_SEEDS, Blocks.WHEAT, false, false, Items.WHEAT, 1);
        context.failIfEver(() -> {
            long tick = context.getTick();
            if (tick < 12) {
                return;
            }
            if (task.state() == TaskState.PENDING) {
                require(context, world.getRawBrightness(feet, 0) < 8, "fixture room is not dark");
                task.start(bot);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
                if (tick > 150) {
                    context.fail(Component.nullToEmpty("dark farm neither failed nor finished: " + task.describe()));
                }
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && task.failureReason().startsWith("farm_area_too_dark"),
                    "expected farm_area_too_dark, got " + task.state() + " " + task.failureReason());
            require(context, InventoryAction.countItem(bot, Items.WHEAT_SEEDS) == 9,
                    "seeds were spent in the dark");
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    require(context, !world.getBlockState(feet.offset(dx, 0, dz)).is(Blocks.WHEAT),
                            "a seed was planted in the dark");
                }
            }
            context.succeed();
        });
    }

    /** Item entities only tick in entity-ticking chunks: force every chunk the fixture (and its drops) can reach. */
    private static void forceChunks(GameTestHelper context, BlockPos feet, int radius) {
        GameTestChunkForcing.forceForTest(context,
                (feet.getX() - radius) >> 4, (feet.getX() + radius) >> 4,
                (feet.getZ() - radius) >> 4, (feet.getZ() + radius) >> 4);
    }

    private static void prepareGround(ServerLevel world, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.setBlock(feet.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** A sizeX x sizeZ block of wet farmland with a crop state on top; {@code origin} is the first crop cell. */
    private static void field(ServerLevel world, BlockPos origin, int sizeX, int sizeZ, BlockState crop) {
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                BlockPos cell = origin.offset(x, 0, z);
                world.setBlock(cell.below(), WET_FARMLAND, Block.UPDATE_ALL);
                world.setBlock(cell, crop, Block.UPDATE_ALL);
            }
        }
    }

    private static AIPlayerEntity spawnBot(GameTestHelper context, String name, BlockPos feet) {
        var world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        -90.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), -90.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        GameTestCleanup.whenFinished(context,
                () -> AIPlayerManager.INSTANCE.despawn(world.getServer(), name));
        return bot;
    }

    private static void requireStrict(GameTestHelper context, AIPlayerEntity bot) {
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.FORCED_PICKUP,
                        "farm_survival_gametest").allowed(),
                "strict_survival unexpectedly allowed forced pickup");
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
