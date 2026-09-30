package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.finish;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.isSealed;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.preparePlatform;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.shelterShell;

/** Deterministic regressions for the bounded underground safety contracts. */
public final class UndergroundSafetyGameTests {
    @GameTest(maxTicks = 20)
    public void emergencyShelterRejectsAnUnstableOriginBeforeWorldMutation(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterOriginGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        context.getLevel().setBlock(feet.below(),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        bot.setOnGround(false);

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_unstable_origin"));

        require(context, task.state() == TaskState.FAILED
                        && "shelter_origin_not_stable".equals(task.failureReason()),
                "unstable shelter origin was accepted: "
                        + task.state() + ":" + task.failureReason());
        require(context, shell.stream().allMatch(pos -> context.getLevel().getBlockState(pos).isAir()),
                "unstable shelter preflight mutated the world");
        require(context, InventoryAction.countItem(bot, Items.DIRT) == 16,
                "unstable shelter preflight consumed material");
        finish(context, bot, "ShelterOriginGT");
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_emergency_shelter_cannot_complete_before_roof_and_all_sides_are_physically_sealed", maxTicks = 400)
    public void emergencyShelterCannotCompleteBeforeRoofAndAllSidesArePhysicallySealed(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        List<BlockPos> shell = shelterShell(feet);
        BlockPos roof = feet.above(2);
        require(context, shell.stream().allMatch(pos -> context.getLevel().getBlockState(pos).isAir()),
                "shelter fixture was not initially open");
        require(context, Direction.stream().noneMatch(direction ->
                        !context.getLevel().getBlockState(roof.relative(direction)).isAir()),
                "roof fixture unexpectedly had placement support");

        AIPlayerEntity bot = spawn(context, "ShelterSealGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_seal"));

        int[] sealedAt = {-1};
        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe()));
            }
            if (sealedAt[0] < 0 && shell.stream().allMatch(pos -> isSealed(context, pos))) {
                sealedAt[0] = task.elapsedTicks();
                require(context, task.state() == TaskState.RUNNING,
                        "shelter published terminal state at the first sealed tick");
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sealedAt[0] >= 0, "shelter exited without first proving a sealed envelope");
            require(context, task.elapsedTicks() - sealedAt[0] >= 100,
                    "shelter did not hold its sealed envelope for 100 ticks");
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterSealGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_emergency_shelter_builds_a_foundation_from_a_single_supported_landing", maxTicks = 500)
    public void emergencyShelterBuildsAFoundationFromASingleSupportedLanding(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    context.getLevel().setBlock(feet.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        context.getLevel().setBlock(feet.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        List<BlockPos> foundations = new ArrayList<>(4);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            foundations.add(feet.below().relative(direction).immutable());
        }
        require(context, foundations.stream().allMatch(pos -> context.getLevel().getBlockState(pos).isAir()),
                "single-pillar fixture unexpectedly had a side foundation");

        AIPlayerEntity bot = spawn(context, "ShelterPillarGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 24));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_single_pillar"));

        int[] sealedAt = {-1};
        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("single-pillar shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe()));
            }
            if (task.state() != TaskState.COMPLETED) {
                if (sealedAt[0] < 0 && shelterShell(feet).stream()
                        .allMatch(pos -> isSealed(context, pos))) {
                    sealedAt[0] = task.elapsedTicks();
                }
                return;
            }
            for (BlockPos cell : foundations) {
                require(context, isSealed(context, cell),
                        "single-pillar shelter omitted foundation " + cell.toShortString());
            }
            require(context, sealedAt[0] >= 0, "single-pillar shelter never proved a full seal");
            require(context, task.elapsedTicks() - sealedAt[0] >= 100,
                    "single-pillar shelter skipped its hold interval");
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterPillarGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_emergency_shelter_rejects_insufficient_foundation_budget_before_world_mutation", maxTicks = 20)
    public void emergencyShelterRejectsInsufficientFoundationBudgetBeforeWorldMutation(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        clearVolume(context, feet, 3);
        context.getLevel().setBlock(feet.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        List<BlockPos> required = new ArrayList<>(14);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            required.add(feet.below().relative(direction));
        }
        required.add(feet.above(2).north());
        required.addAll(shelterShell(feet));
        require(context, required.stream().distinct().count() == 14L,
                "shelter preflight fixture did not contain fourteen distinct placements");

        AIPlayerEntity bot = spawn(context, "ShelterBudgetGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 13));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.OBSIDIAN, 1));
        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);

        require(context, task.state() == TaskState.FAILED,
                "thirteen-block shelter budget was not rejected before execution");
        require(context, task.failureReason().equals("missing shelter_blocks required=14 available=13"),
                "unexpected shelter preflight reason: " + task.failureReason());
        require(context, required.stream().allMatch(pos -> context.getLevel().getBlockState(pos).isAir()),
                "shelter preflight mutated the world before rejecting its budget");
        int remaining = bot.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.DIRT))
                .mapToInt(ItemStack::getCount)
                .sum();
        require(context, remaining == 13,
                "shelter preflight consumed blocks: remaining=" + remaining);
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1
                        && InventoryAction.countItem(bot, Items.FURNACE) == 1
                        && InventoryAction.countItem(bot, Items.OBSIDIAN) == 1,
                "shelter palette consumed a workstation or mission output");
        finish(context, bot, "ShelterBudgetGT");
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_partial_shelter_failure_opens_owned_doorway_before_publishing_failure", maxTicks = 300)
    public void partialShelterFailureOpensOwnedDoorwayBeforePublishingFailure(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        BlockPos doorwayFeet = feet.north();
        BlockPos doorwayHead = doorwayFeet.above();

        AIPlayerEntity bot = spawn(context, "ShelterFailureExitGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_failure_exit"));

        boolean[] inventoryDrained = {false};
        context.failIfEver(() -> {
            if (!inventoryDrained[0]
                    && isSealed(context, doorwayFeet)
                    && isSealed(context, doorwayHead)
                    && shelterShell(feet).stream().anyMatch(pos -> !isSealed(context, pos))) {
                int dirt = InventoryAction.countItem(bot, Items.DIRT);
                require(context, dirt > 0 && InventoryAction.removeItems(bot, Items.DIRT, dirt),
                        "failed to trigger deterministic partial-build exhaustion");
                inventoryDrained[0] = true;
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, inventoryDrained[0], "fixture never reached a partial owned doorway");
            require(context, task.state() == TaskState.FAILED,
                    "partial shelter ended as " + task.state());
            require(context, task.failureReason().equals("missing shelter_block"),
                    "unexpected partial shelter failure: " + task.failureReason());
            require(context, context.getLevel().getBlockState(doorwayFeet).isAir()
                            && context.getLevel().getBlockState(doorwayHead).isAir(),
                    "failure published before its owned doorway was physically mined");
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterFailureExitGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_displaced_partial_shelter_releases_its_stale_anchor_without_spinning", maxTicks = 120)
    public void displacedPartialShelterReleasesItsStaleAnchorWithoutSpinning(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterDisplacedGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_displaced"));

        boolean[] displaced = {false};
        int[] displacedAt = {-1};
        context.failIfEver(() -> {
            if (!displaced[0]
                    && task.state() == TaskState.RUNNING
                    && shelterShell(feet).stream().anyMatch(pos -> isSealed(context, pos))) {
                BlockPos safeOutside = feet.east(3);
                bot.teleportTo(context.getLevel(), safeOutside.getX() + 0.5D, safeOutside.getY(),
                        safeOutside.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
                bot.setOnGround(true);
                displaced[0] = true;
                displacedAt[0] = task.elapsedTicks();
                return;
            }
            if (!displaced[0] || task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_anchor_displaced".equals(task.failureReason()),
                    "displaced shelter ended unexpectedly: "
                            + task.state() + ":" + task.failureReason());
            require(context, task.elapsedTicks() - displacedAt[0] <= 2,
                    "displaced shelter spun instead of releasing its anchor: elapsed="
                            + (task.elapsedTicks() - displacedAt[0]));
            require(context, Standability.isStandable(context.getLevel(), bot.blockPosition()),
                    "displaced shelter released an unsafe pose: " + bot.blockPosition().toShortString());
            finish(context, bot, "ShelterDisplacedGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_shelter_material_loss_before_first_placement_fails_without_inventing_exit_debt", maxTicks = 40)
    public void shelterMaterialLossBeforeFirstPlacementFailsWithoutInventingExitDebt(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        clearVolume(context, feet, 3);
        context.getLevel().setBlock(feet.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "ShelterPrebuildLossGT", feet);
        BlockPos taskStart = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 14));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_prebuild_loss"));
        require(context, InventoryAction.removeItems(bot, Items.DIRT, 14),
                "failed to remove shelter material after the successful preflight");

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && task.failureReason().equals("missing shelter_block"),
                    "prebuild material loss ended unexpectedly: "
                            + task.state() + ":" + task.failureReason());
            BlockPos actual = bot.blockPosition();
            require(context, actual.getX() == taskStart.getX()
                            && actual.getZ() == taskStart.getZ(),
                    "open prebuild failure invented an unsupported exit step");
            require(context, Math.abs(actual.getY() - taskStart.getY()) <= 1,
                    "prebuild fixture suffered unexpected vertical displacement");
            require(context, shelterShell(feet).stream()
                            .allMatch(pos -> context.getLevel().getBlockState(pos).isAir()),
                    "prebuild material loss changed the shelter envelope");
            finish(context, bot, "ShelterPrebuildLossGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_shelter_exit_never_mines_preexisting_world_blocks", maxTicks = 400)
    public void shelterExitNeverMinesPreexistingWorldBlocks(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        BlockPos preexistingFeet = feet.north();
        BlockPos preexistingHead = preexistingFeet.above();
        context.getLevel().setBlock(preexistingFeet,
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(preexistingHead,
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "ShelterOwnershipGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_ownership"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("ownership shelter ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, context.getLevel().getBlockState(preexistingFeet).is(Blocks.STONE)
                            && context.getLevel().getBlockState(preexistingHead).is(Blocks.STONE),
                    "shelter exit mined a preexisting world block");
            require(context, bot.blockPosition().equals(feet.east()),
                    "shelter did not choose the first ownable doorway away from preexisting stone: "
                            + bot.blockPosition().toShortString());
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterOwnershipGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_shelter_skips_foundation_hidden_below_a_preexisting_tunnel_wall", maxTicks = 400)
    public void shelterSkipsFoundationHiddenBelowAPreexistingTunnelWall(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        BlockPos northFeet = feet.north();
        BlockPos northHead = northFeet.above();
        BlockPos impossibleFoundation = northFeet.below();
        context.getLevel().setBlock(northFeet,
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(northHead,
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(impossibleFoundation,
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "ShelterTunnelWallGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_tunnel_wall"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "natural-wall shelter ended as " + task.state() + ":" + task.failureReason());
            require(context, context.getLevel().getBlockState(northFeet).is(Blocks.STONE)
                            && context.getLevel().getBlockState(northHead).is(Blocks.STONE),
                    "shelter changed its preexisting tunnel wall");
            require(context, context.getLevel().getBlockState(impossibleFoundation).isAir(),
                    "shelter built a useless foundation below a sealed natural wall");
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterTunnelWallGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_shelter_opens_its_owned_door_before_failing_when_exit_support_disappears", maxTicks = 400)
    public void shelterOpensItsOwnedDoorBeforeFailingWhenExitSupportDisappears(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        clearVolume(context, feet, 3);
        context.getLevel().setBlock(feet.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST)) {
            context.getLevel().setBlock(feet.relative(direction),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(feet.above().relative(direction),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos doorwayFeet = feet.east();
        BlockPos doorwayHead = doorwayFeet.above();
        BlockPos doorwaySupport = doorwayFeet.below();

        AIPlayerEntity bot = spawn(context, "ShelterLostExitSupportGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_lost_exit_support"));

        boolean[] removedSupport = {false};
        context.failIfEver(() -> {
            if (!removedSupport[0]
                    && isSealed(context, doorwayFeet)
                    && isSealed(context, doorwayHead)) {
                require(context, context.getLevel().getBlockState(doorwaySupport).is(Blocks.DIRT),
                        "fixture never observed the owned exit foundation");
                context.getLevel().setBlock(doorwaySupport,
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                removedSupport[0] = true;
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, removedSupport[0], "fixture never removed the exit support");
            require(context, task.state() == TaskState.FAILED,
                    "unsupported-exit shelter ended as " + task.state());
            require(context, task.failureReason().equals("shelter_exit_not_standable"),
                    "unexpected unsupported-exit reason: " + task.failureReason());
            require(context, bot.blockPosition().equals(feet),
                    "unsupported exit invented a ravine step: " + bot.blockPosition().toShortString());
            require(context, context.getLevel().getBlockState(doorwayFeet).isAir()
                            && context.getLevel().getBlockState(doorwayHead).isAir(),
                    "shelter failed before reopening its owned unsupported doorway");
            for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST)) {
                require(context, context.getLevel().getBlockState(feet.relative(direction)).is(Blocks.STONE)
                                && context.getLevel().getBlockState(feet.above().relative(direction)).is(Blocks.STONE),
                        "shelter mined a preexisting tunnel wall at " + direction);
            }
            finish(context, bot, "ShelterLostExitSupportGT");
        });
    }

    @GameTest(maxTicks = 20)
    public void standableStepRejectsUnsupportedAndWaterCells(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(4, 5, 12));
        clearVolume(context, start, 3);
        context.getLevel().setBlock(start.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos unsupported = start.north();
        BlockPos water = start.east();
        context.getLevel().setBlock(water.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(water,
                Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "StandableStepGT", start);
        require(context, !FakePlayerMotion.stepToStandable(bot, unsupported, "gametest_unsupported"),
                "standable step entered an unsupported air cell");
        // The walked step that replaces the teleporting primitive applies the same landing rules before it presses a key.
        require(context, io.github.zoyluo.minecraftai.action.WalkedStep.refusal(bot, unsupported,
                        io.github.zoyluo.minecraftai.action.WalkedStep.Kind.FLAT) != null,
                "a walked step accepted an unsupported air cell as its landing");
        require(context, bot.blockPosition().equals(start),
                "unsupported step moved the bot: " + bot.blockPosition().toShortString());
        require(context, !FakePlayerMotion.stepToStandable(bot, water, "gametest_water"),
                "standable step entered a water cell");
        require(context, io.github.zoyluo.minecraftai.action.WalkedStep.refusal(bot, water,
                        io.github.zoyluo.minecraftai.action.WalkedStep.Kind.FLAT) != null,
                "a walked step accepted a water cell as its landing");
        require(context, bot.blockPosition().equals(start),
                "water step moved the bot: " + bot.blockPosition().toShortString());
        finish(context, bot, "StandableStepGT");
    }

    @GameTest(maxTicks = 20)
    public void adjacentFakePlayerStepRejectsAnEntityOccupiedLanding(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(12, 5, 12));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "OccupiedStepGT", start);
        BlockPos occupied = start.east();
        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            finish(context, bot, "OccupiedStepGT");
            context.fail(Component.nullToEmpty("failed to create occupied landing fixture"));
            return;
        }
        zombie.setPersistenceRequired();
        zombie.snapTo(occupied.getX() + 0.5D, occupied.getY(),
                occupied.getZ() + 0.5D, 0.0F, 0.0F);
        require(context, context.getLevel().addFreshEntity(zombie),
                "failed to spawn occupied landing fixture");

        require(context, !FakePlayerMotion.stepToStandable(bot, occupied, "gametest_occupied"),
                "fake-player step entered an entity-occupied landing");
        require(context, io.github.zoyluo.minecraftai.action.WalkedStep.refusal(bot, occupied,
                        io.github.zoyluo.minecraftai.action.WalkedStep.Kind.FLAT) != null,
                "a walked step accepted an entity-occupied landing");
        require(context, bot.blockPosition().equals(start),
                "occupied landing moved the bot: " + bot.blockPosition().toShortString());

        zombie.discard();
        finish(context, bot, "OccupiedStepGT");
    }

    @GameTest(maxTicks = 20)
    public void descendLateralDetourRejectsUnsupportedAirShaft(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(4, 8, 20));
        clearVolume(context, start, 4);
        context.getLevel().setBlock(start.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos safeEast = start.east();
        context.getLevel().setBlock(safeEast.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DescendDetourGT", start);
        DescendToYTask task = new DescendToYTask(start.getY() - 5);
        task.start(bot);
        // NORTH's own unsupported shaft is observably empty all the way down, so it is rejected
        // outright on the first tick exactly as before. EAST's deeper landing is hidden behind its
        // own solid floor tile, so rotateStair now needs one tick just to pick EAST (a real player
        // cannot yet know what's under a floor they have not stood on); a second tick recognizes
        // that floor is already an observed, dry, standable landing and takes the flat step onto
        // it without ever mining through it.
        for (int i = 0; i < 5 && task.state() == TaskState.RUNNING
                && !bot.blockPosition().equals(safeEast); i++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.RUNNING,
                "descend detour ended unexpectedly: " + task.state() + ":" + task.failureReason());
        require(context, bot.blockPosition().equals(safeEast),
                "descend detour did not skip the unsupported north shaft: "
                        + start.toShortString() + " -> " + bot.blockPosition().toShortString());
        require(context, bot.blockPosition().getY() >= start.getY() - 1,
                "descend detour fell below its verified landing");
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendDetourGT");
    }

    @GameTest(maxTicks = 20)
    public void descendLateralDetourKeepsHeadingInsteadOfReversingInTwoCellLoop(
            GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(12, 8, 20));
        clearVolume(context, start, 4);
        BlockPos north = start.north();
        BlockPos westExit = north.west();
        for (BlockPos landing : List.of(start, north, westExit)) {
            context.getLevel().setBlock(landing.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        AIPlayerEntity bot = spawn(context, "DescendHeadingGT", start);
        DescendToYTask task = new DescendToYTask(start.getY() - 5);
        task.start(bot);
        task.tick(bot);
        require(context, bot.blockPosition().equals(north),
                "first lateral move did not enter the north corridor: "
                        + start.toShortString() + " -> " + bot.blockPosition().toShortString());

        // From `north`, continuing NORTH is observably a real unsupported drop (rejected outright).
        // WEST's own deeper landing is hidden behind its solid floor tile, so -- exactly like the
        // flat-landing rim case -- one tick is spent just rotating onto WEST (rotateStair now tries
        // the sideways options before ever reversing back toward `start`) and a second recognizes
        // that floor is already open, dry and standable and steps onto it without mining.
        for (int i = 0; i < 5 && task.state() == TaskState.RUNNING
                && !bot.blockPosition().equals(westExit); i++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.RUNNING,
                "heading-aware detour ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.blockPosition().equals(westExit),
                "detour reversed south instead of trying the west exit: "
                        + north.toShortString() + " -> " + bot.blockPosition().toShortString());
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendHeadingGT");
    }

    @GameTest(maxTicks = 20)
    public void descendLateralDetourNeverReplaysATraversedDirectedEdge(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(18, 8, 20));
        clearVolume(context, start, 4);
        BlockPos north = start.north();
        for (BlockPos landing : List.of(start, north)) {
            context.getLevel().setBlock(landing.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        AIPlayerEntity bot = spawn(context, "DescendEdgeLoopGT", start);
        DescendToYTask task = new DescendToYTask(start.getY() - 5);
        task.start(bot);
        task.tick(bot);
        require(context, bot.blockPosition().equals(north),
                "fixture did not traverse A->B: " + bot.blockPosition().toShortString());

        // From `north`, continuing NORTH is an observable, confirmed unsupported drop (rejected
        // outright). EAST/WEST are equally open dead air. SOUTH's own deeper landing is hidden
        // behind `start`'s floor tile, so -- exactly like the other rim/heading fixtures -- one
        // tick is spent only rotating onto SOUTH before a second physically retraces the one
        // necessary B->A backtrack via the flat-landing step.
        for (int i = 0; i < 5 && task.state() == TaskState.RUNNING
                && !bot.blockPosition().equals(start); i++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.RUNNING && bot.blockPosition().equals(start),
                "fixture did not permit the one necessary B->A backtrack: "
                        + task.state() + ":" + task.failureReason()
                        + " at=" + bot.blockPosition().toShortString());
        Map<String, String> afterBacktrack = task.checkpoint();
        require(context, "2".equals(afterBacktrack.get("lateral_detours"))
                        && afterBacktrack.getOrDefault("traversed_detour_edges", "")
                        .split(";", -1).length == 2,
                "directed detour edges were not durably recorded: " + afterBacktrack);

        task.cancel(bot, "gametest_restart");
        DescendToYTask restored = new DescendToYTask(start.getY() - 5, afterBacktrack);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.FAILED
                        && restored.failureReason().startsWith("descend_no_safe_landing"),
                "restored two-cell detour did not fail closed after exhausting fresh edges: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.blockPosition().equals(start),
                "restored Descend replayed A->B after A->B->A: "
                        + bot.blockPosition().toShortString());
        require(context, "2".equals(restored.checkpoint().get("lateral_detours")),
                "restored two-cell loop consumed the full lateral budget: "
                        + restored.checkpoint());
        finish(context, bot, "DescendEdgeLoopGT");
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_descend_rolls_back_collapsed_same_level_detour_and_retries_from_origin", maxTicks = 180)
    public void descendRollsBackCollapsedSameLevelDetourAndRetriesFromOrigin(
            GameTestHelper context) {
        BlockPos origin = context.absolutePos(new BlockPos(24, 10, 20));
        clearVolume(context, origin, 4);
        BlockPos detour = origin.north();
        for (BlockPos landing : List.of(origin, detour)) {
            context.getLevel().setBlock(landing.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        AIPlayerEntity bot = spawn(context, "DescendDetourRollbackGT", origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SHOVEL));
        DescendToYTask task = new DescendToYTask(origin.getY() - 5);
        task.start(bot);
        task.tick(bot);
        require(context, bot.blockPosition().equals(detour),
                "fixture did not issue its same-level detour: " + bot.blockPosition());
        require(context, "1".equals(task.checkpoint().get("lateral_detours")),
                "issued detour was not recorded: " + task.checkpoint());

        float healthBefore = bot.getHealth();
        context.getLevel().setBlock(detour,
                Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING && bot.blockPosition().equals(origin),
                "collapsed detour did not physically retreat to its factual origin: "
                        + task.state() + ":" + task.failureReason()
                        + " at=" + bot.blockPosition().toShortString());
        Map<String, String> rolledBack = task.checkpoint();
        require(context, "0".equals(rolledBack.get("lateral_detours"))
                        && rolledBack.getOrDefault("traversed_detour_edges", "").isEmpty(),
                "collapsed detour retained uncommitted graph debt: " + rolledBack);
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "detour rollback lost health before retreat");

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "detour retry failed after rollback: " + task.failureReason());
            if (!bot.blockPosition().equals(detour)) {
                return;
            }
            Map<String, String> retried = task.checkpoint();
            require(context, "1".equals(retried.get("lateral_detours"))
                            && !retried.getOrDefault("traversed_detour_edges", "").isEmpty(),
                    "cleared detour was not retried transactionally: " + retried);
            require(context, context.getLevel().getBlockState(detour).isAir(),
                    "retry entered the detour before physically clearing gravel");
            require(context, bot.getHealth() == healthBefore,
                    "bot lost health while clearing and retrying the detour");
            task.cancel(bot, "gametest_complete");
            finish(context, bot, "DescendDetourRollbackGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_descend_restored_upper_detour_retreats_down_to_persisted_origin", maxTicks = 80)
    public void descendRestoredUpperDetourRetreatsDownToPersistedOrigin(
            GameTestHelper context) {
        BlockPos origin = context.absolutePos(new BlockPos(30, 9, 20));
        clearVolume(context, origin, 4);
        context.getLevel().setBlock(origin.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos upperDetour = origin.north().above();
        // The solid north cell supports only the upper retreat; its own lower support remains air,
        // so the same-level phase cannot consume the candidate first.
        context.getLevel().setBlock(origin.north(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DescendUpperRollbackGT", origin);
        DescendToYTask first = new DescendToYTask(origin.getY() - 5);
        first.start(bot);
        first.tick(bot);
        require(context, bot.blockPosition().equals(upperDetour),
                "fixture did not issue the upper detour: " + bot.blockPosition());
        Map<String, String> checkpoint = first.checkpoint();
        require(context, "1".equals(checkpoint.get("lateral_detours"))
                        && !checkpoint.getOrDefault("traversed_detour_edges", "").isEmpty(),
                "upper detour checkpoint did not retain its edge: " + checkpoint);
        first.cancel(bot, "gametest_restart");

        context.getLevel().setBlock(upperDetour,
                Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        float healthBefore = bot.getHealth();
        DescendToYTask restored = new DescendToYTask(origin.getY() - 5, checkpoint);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.RUNNING
                        && bot.blockPosition().equals(origin),
                "restored upper detour did not take the exact diagonal-down retreat: "
                        + restored.state() + ":" + restored.failureReason()
                        + " at=" + bot.blockPosition().toShortString());
        Map<String, String> rolledBack = restored.checkpoint();
        require(context, "0".equals(rolledBack.get("lateral_detours"))
                        && rolledBack.getOrDefault("traversed_detour_edges", "").isEmpty(),
                "restored upper collapse retained graph debt: " + rolledBack);
        require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                "restored physical retreat incorrectly delegated to NavSafetyNet");
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "restored upper retreat lost health");

        restored.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendUpperRollbackGT");
    }

    @GameTest(maxTicks = 60)
    public void descendLateralBudgetResetsOnlyAfterAConfirmedLowerLanding(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(4, 12, 20));
        List<BlockPos> upperCorridor = new ArrayList<>();
        for (int dz = 0; dz <= 23; dz++) {
            upperCorridor.add(start.north(dz));
        }
        BlockPos upperLanding = upperCorridor.get(upperCorridor.size() - 1).east().below();
        BlockPos lowerOne = upperLanding.east();
        BlockPos lowerTwo = lowerOne.east();
        BlockPos targetLanding = lowerTwo.south().below();
        List<BlockPos> standingCells = new ArrayList<>(upperCorridor);
        standingCells.add(upperLanding);
        standingCells.add(lowerOne);
        standingCells.add(lowerTwo);
        standingCells.add(targetLanding);
        for (BlockPos standing : standingCells) {
            context.getLevel().setBlock(standing, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(standing.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(standing.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        // The two real lower landings must be air with separate solid support. Flat corridor cells
        // intentionally use their y-1 stone as current-level footing, which makes diagonal descent
        // unsupported and forces physical lateral movement.
        context.getLevel().setBlock(upperLanding, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(targetLanding, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DescBudgetGT", start);
        DescendToYTask task = new DescendToYTask(targetLanding.getY());
        task.start(bot);
        int maxSameLayerDebt = 0;
        for (int tick = 0; tick < 55 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
            maxSameLayerDebt = Math.max(maxSameLayerDebt,
                    Integer.parseInt(task.checkpoint().get("lateral_detours")));
        }

        require(context, task.state() == TaskState.COMPLETED,
                "cross-layer detour budget did not complete: "
                        + task.state() + ":" + task.failureReason()
                        + " at=" + bot.blockPosition().toShortString());
        require(context, bot.blockPosition().equals(targetLanding),
                "cross-layer detour ended at the wrong landing: expected="
                        + targetLanding.toShortString() + " actual=" + bot.blockPosition().toShortString());
        require(context, maxSameLayerDebt == 23,
                "long cave-roof fixture did not cross its 23-edge supported rim: max_debt="
                        + maxSameLayerDebt);
        require(context, "0".equals(task.checkpoint().get("lateral_detours"))
                        && "".equals(task.checkpoint().get("traversed_detour_edges")),
                "confirmed lower landing retained stale detour history: " + task.checkpoint());
        finish(context, bot, "DescBudgetGT");
    }

    @GameTest(maxTicks = 20)
    public void descendNeverCompletesBelowTarget(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(20, 12, 20));
        clearVolume(context, start, 10);
        context.getLevel().setBlock(start.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        int targetY = start.getY() - 3;
        BlockPos overshot = new BlockPos(start.getX(), targetY - 5, start.getZ());
        context.getLevel().setBlock(overshot.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DescendOvershootGT", start);
        DescendToYTask task = new DescendToYTask(targetY);
        task.start(bot);
        bot.teleportTo(context.getLevel(), overshot.getX() + 0.5D, overshot.getY(),
                overshot.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED,
                "descend completed or continued below its target: " + task.state());
        require(context, task.failureReason().startsWith("descend_overshoot_unrecoverable"),
                "unexpected overshoot reason: " + task.failureReason());
        require(context, bot.getActionPack().isPathExecutorIdle(),
                "overshot descend left a path action active");
        finish(context, bot, "DescendOvershootGT");
    }

    @GameTest(maxTicks = 80)
    public void standableCornerOverlapIsShovedClearOnceAndRetiresStaleRoute(
            GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(5, 12, 5));
        preparePlatform(context, start, 3);
        AIPlayerEntity bot = spawn(context, "CornerOverlapSuffocationGT", start);
        var staleRoute = bot.getActionPack().startSurfacePathTo(start.east(2));
        require(context, !staleRoute.isFailed() && !bot.getActionPack().isPathExecutorIdle(),
                "fixture did not open the route that must be retired after body recovery");

        // Dedicated-server console commands use the lower corner of the world-spawn BlockPos.
        // Reproduce seed 3000: the logical current column remains air/air/solid-support, while the
        // 0.6-wide player body at the exact integer corner intrudes into two raised north cells.
        BlockPos north = start.north();
        BlockPos northWest = north.west();
        context.getLevel().setBlock(
                north, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(
                northWest, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        bot.teleportTo(context.getLevel(), start.getX(), start.getY(), start.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setOnGround(true);
        Standability.clearCache();

        require(context, bot.blockPosition().equals(start)
                        && Standability.isStandable(context.getLevel(), start),
                "fixture current column must remain logically standable");
        require(context, !FakePlayerMotion.isBlockCollisionFree(bot),
                "fixture exact-corner body did not overlap the raised north terrain");

        io.github.zoyluo.minecraftai.entity.TeleportAudit.reset(bot);
        boolean handled = NavSafetyNet.INSTANCE.tickBot(
                context.getLevel().getServer(), bot);

        // R5: the overlap is not repaired by a teleport onto the cell centre. The safety net retires the stale route and shoves the
        // body clear of the block the way a client does (a small horizontal velocity), inside the same cell.
        require(context, handled,
                "real corner overlap was not handled by the safety net");
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()
                        && bot.getActionPack().isMiningIdle(),
                "corner recovery left the stale route active");
        int[] ticks = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            require(context, io.github.zoyluo.minecraftai.entity.TeleportAudit.corrections(bot) == 0,
                    "the corner overlap was corrected by a teleport ("
                            + io.github.zoyluo.minecraftai.entity.TeleportAudit.lastCaller(bot) + ")");
            if (!FakePlayerMotion.isBlockCollisionFree(bot)) {
                require(context, ticks[0] < 50, "the body never left the raised terrain: " + bot.position());
                return;
            }
            require(context, bot.blockPosition().equals(start),
                    "same-cell recovery left the cell: " + bot.blockPosition().toShortString());
            require(context, context.getLevel().getBlockState(north).is(Blocks.GRASS_BLOCK)
                            && context.getLevel().getBlockState(northWest).is(Blocks.GRASS_BLOCK),
                    "corner recovery removed the neighbouring terrain");
            require(context, bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()
                            && bot.getActionPack().isMiningIdle(),
                    "corner recovery left the stale route active");
            Vec3 after = bot.position();
            require(context, !NavSafetyNet.INSTANCE.tickBot(
                            context.getLevel().getServer(), bot),
                    "cleared corner overlap triggered a second false suffocation recovery");
            require(context, bot.position().distanceToSqr(after) < 1.0E-12D,
                    "idempotent safety tick moved the already-cleared body");

            NavSafetyNet.INSTANCE.clear(bot);
            finish(context, bot, "CornerOverlapSuffocationGT");
        });
    }

    @GameTest(maxTicks = 20)
    public void partialHeightDirtPathTopDoesNotTriggerSuffocation(GameTestHelper context) {
        BlockPos support = context.absolutePos(new BlockPos(5, 12, 12));
        clearVolume(context, support.above(), 3);
        context.getLevel().setBlock(
                support, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(
                support.east(), Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawn(context, "DirtPathSuffocationGT", support.above());
        double landingY = support.getY() + 15.0D / 16.0D;
        bot.teleportTo(context.getLevel(), support.getX() + 0.5D, landingY,
                support.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setOnGround(true);

        require(context, bot.blockPosition().equals(support),
                "fixture must reproduce partial-height support flooring: "
                        + bot.blockPosition().toShortString());
        require(context, FakePlayerMotion.isBlockCollisionFree(bot),
                "fixture body actually intersects dirt_path instead of merely touching its top");
        Vec3 before = bot.position();

        for (int tick = 0; tick < 3; tick++) {
            require(context, !NavSafetyNet.INSTANCE.tickBot(
                            context.getLevel().getServer(), bot),
                    "normal dirt_path landing was misclassified as suffocation on tick " + tick);
            require(context, bot.position().distanceToSqr(before) < 1.0E-12D,
                    "false suffocation recovery moved across adjacent dirt paths: "
                            + before + " -> " + bot.position());
        }

        NavSafetyNet.INSTANCE.clear(bot);
        finish(context, bot, "DirtPathSuffocationGT");
    }

    @GameTest(maxTicks = 80)
    public void strictSuffocationDenialFallsBackToAdjacentPhysicalExit(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(12, 12, 12));
        clearVolume(context, start, 4);
        preparePlatform(context, start, 3);
        AIPlayerEntity bot = spawn(context, "StrictSuffocationGT", start);
        bot.setHealth(bot.getMaxHealth());
        var staleRoute = bot.getActionPack().startSurfacePathTo(start.east(2));
        require(context, !staleRoute.isFailed() && !bot.getActionPack().isPathExecutorIdle(),
                "fixture did not open the route that must be retired after suffocation recovery");
        BlockPos blockedHead = start.above();
        // A block that stays where it is: the exit now takes real ticks, and a gravel block over an open cell would fall away (or onto
        // the bot) while it does. The falling-gravel burial itself is covered by NaturalMovementGameTests.
        context.getLevel().setBlock(
                blockedHead, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        require(context, !CapabilityRuntime.decide(
                        bot, PrivilegedCapability.EMERGENCY_TELEPORT,
                        "strict_suffocation_gametest").allowed(),
                "strict_survival unexpectedly allowed emergency teleport");
        require(context, Standability.isStandable(context.getLevel(), start.above(2)),
                "fixture must expose the upward standable candidate that triggers denied teleport");
        require(context, !FakePlayerMotion.isBlockCollisionFree(bot),
                "fixture gravel did not actually overlap the player's body");
        float healthBefore = bot.getHealth();
        io.github.zoyluo.minecraftai.entity.TeleportAudit.reset(bot);

        boolean handled = NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot);

        require(context, handled,
                "strict suffocation denial did not fall through to physical recovery");
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()
                        && bot.getActionPack().isMiningIdle(),
                "suffocation recovery left the stale route able to replay its blocked edge");
        // R5: the exit is a real shove out of the gravel (no teleport), so the bot is inside the block for the few ticks it takes: a
        // physical exit cannot cost nothing. It may cost the suffocation damage of those ticks, never more than two hearts.
        int[] ticks = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            require(context, io.github.zoyluo.minecraftai.entity.TeleportAudit.corrections(bot) == 0,
                    "the suffocation exit was a teleport (" + io.github.zoyluo.minecraftai.entity.TeleportAudit.lastCaller(bot) + ")");
            if (!FakePlayerMotion.isBlockCollisionFree(bot)) {
                require(context, ticks[0] < 70, "the bot never left the gravel: " + bot.position());
                return;
            }
            BlockPos after = bot.blockPosition();
            int horizontal = Math.abs(after.getX() - start.getX())
                    + Math.abs(after.getZ() - start.getZ());
            require(context, after.getY() == start.getY() && horizontal == 1,
                    "suffocation recovery was not one adjacent physical step: "
                            + start.toShortString() + " -> " + after.toShortString());
            require(context, Standability.isStandable(context.getLevel(), after),
                    "physical suffocation exit was not standable: " + after.toShortString());
            require(context, context.getLevel().getBlockState(blockedHead).is(Blocks.STONE),
                    "physical suffocation exit silently removed the obstruction");
            require(context, bot.isAlive() && bot.getHealth() >= healthBefore - 4.0F,
                    "bot took more than two hearts before the adjacent suffocation exit: " + bot.getHealth());
            require(context, bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()
                            && bot.getActionPack().isMiningIdle(),
                    "suffocation recovery left the stale route able to replay its blocked edge");

            NavSafetyNet.INSTANCE.clear(bot);
            finish(context, bot, "StrictSuffocationGT");
        });
    }

    @GameTest(maxTicks = 40)
    public void descendPhysicallyRetreatsWhenGravelReoccupiesItsHeadInStrictSurvival(
            GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(20, 12, 12));
        clearVolume(context, start, 4);
        BlockPos buriedLanding = start.north().below();
        BlockPos alternateLanding = start.east().below();
        for (BlockPos landing : List.of(start, buriedLanding, alternateLanding)) {
            context.getLevel().setBlock(landing.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(landing,
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(landing.above(),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // Keep the following north stair solid so the second task tick confirms the first landing
        // without immediately descending a second level. Its own top face must stay solid too --
        // an already-open top would let Descend's honest climb-over shortcut clear this exact
        // obstacle in a single free step (a real player climbs a chest-high block with open
        // headroom instead of mining through it), which is correct behavior but would move the bot
        // on this very tick instead of leaving it holding the confirmed landing this fixture needs.
        context.getLevel().setBlock(buriedLanding.north(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(buriedLanding.north().above(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(buriedLanding.north().below(2),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DescendGravelGT", start);
        // The descend tool gate typed-fails a pickless stair dig; this fixture tests gravel
        // retreat physics, so provision the ordinary descent pick like a real mission would.
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        bot.setHealth(bot.getMaxHealth());
        bot.setOnGround(true);
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.EMERGENCY_TELEPORT,
                        "descend_gravel_gametest").allowed(),
                "strict_survival unexpectedly allowed emergency teleport");

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        task.start(bot);
        task.tick(bot);
        require(context, bot.blockPosition().equals(buriedLanding),
                "fixture did not enter its first factual stair: "
                        + start.toShortString() + " -> " + bot.blockPosition().toShortString());
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING
                        && bot.blockPosition().equals(buriedLanding),
                "fixture did not hold the confirmed landing before collapse: "
                        + task.state() + ":" + task.failureReason());

        BlockPos reoccupiedHead = buriedLanding.above();
        context.getLevel().setBlock(reoccupiedHead,
                Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        require(context, !context.getLevel().getBlockState(reoccupiedHead)
                        .getCollisionShape(context.getLevel(), reoccupiedHead).isEmpty(),
                "gravel fixture did not reoccupy the bot's head cell");
        float healthBefore = bot.getHealth();
        BlockPos before = bot.blockPosition().immutable();

        task.tick(bot);

        BlockPos after = bot.blockPosition();
        int dx = Math.abs(after.getX() - before.getX());
        int dy = Math.abs(after.getY() - before.getY());
        int dz = Math.abs(after.getZ() - before.getZ());
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        require(context, after.equals(start),
                "descend did not retreat to its previous factual landing: "
                        + before.toShortString() + " -> " + after.toShortString());
        require(context, dx <= 1 && dy <= 1 && dz <= 1 && changedAxes == 2,
                "blocked-body recovery used a non-adjacent movement: " + before + " -> " + after);
        require(context, context.getLevel().getBlockState(reoccupiedHead).is(Blocks.GRAVEL),
                "retreat fixture was silently cleared instead of exited physically");
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "bot took suffocation damage before physical retreat");
        require(context, task.state() == TaskState.RUNNING,
                "descend ended during recoverable gravel collapse: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getActionPack().isMiningIdle(),
                "blocked-body retreat left the abandoned stair miner active");

        // The collapsed NORTH edge is durable for this origin. One tick rotates, the next enters
        // the prepared EAST stair; blindly retrying NORTH would bury the bot again.
        task.tick(bot);
        task.tick(bot);
        require(context, bot.blockPosition().equals(alternateLanding),
                "descend retried the collapsed edge instead of rotating to the safe stair: "
                        + bot.blockPosition().toShortString());
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "bot lost health after rotating away from the collapsed stair");

        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendGravelGT");
    }

    @GameTest(maxTicks = 40)
    public void descendMinesItsOccupiedHeadWhenNoPhysicalRetreatLandingExists(
            GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(20, 12, 4));
        clearVolume(context, start, 4);
        context.getLevel().setBlock(start.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos occupiedHead = start.above();
        context.getLevel().setBlock(occupiedHead,
                Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DescendClearHeadGT", start);
        bot.setHealth(bot.getMaxHealth());
        bot.setOnGround(true);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_SHOVEL));
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.EMERGENCY_TELEPORT,
                        "descend_clear_head_gametest").allowed(),
                "strict_survival unexpectedly allowed emergency teleport");

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_descend_clear_head"));
        context.failIfEver(() -> {
            require(context, bot.isAlive() && bot.getHealth() == bot.getMaxHealth(),
                    "bot took damage while clearing its occupied head cell");
            require(context, bot.blockPosition().equals(start),
                    "head-clear fallback moved without a factual retreat landing: "
                            + start.toShortString() + " -> " + bot.blockPosition().toShortString());
            if (!context.getLevel().getBlockState(occupiedHead).isAir()) {
                return;
            }
            require(context, bot.getActionPack().isPathExecutorIdle(),
                    "head-clear fallback started a navigation path");
            finish(context, bot, "DescendClearHeadGT");
        });
    }

    @GameTest(maxTicks = 20)
    public void defensiveCombatDoesNotDescendTowardAHostileBelowItsAnchorFloor(GameTestHelper context) {
        BlockPos anchor = context.absolutePos(new BlockPos(4, 8, 4));
        preparePlatform(context, anchor, 2);
        BlockPos hostileFeet = anchor.offset(2, -3, 0);
        context.getLevel().setBlock(hostileFeet.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(hostileFeet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(hostileFeet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DefCombatGT", anchor);
        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            finish(context, bot, "DefCombatGT");
            context.fail(Component.nullToEmpty("failed to create defensive hostile fixture"));
            return;
        }
        zombie.setPersistenceRequired();
        zombie.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);

        CombatTask task = CombatTask.defensive(zombie, 6.0F, anchor);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.COMPLETED,
                "defensive combat did not cleanly disengage from the lower hostile: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.blockPosition().getY() == anchor.getY(),
                "defensive combat descended below its anchor: "
                        + anchor.toShortString() + " -> " + bot.blockPosition().toShortString());
        require(context, bot.getActionPack().isPathExecutorIdle(),
                "defensive combat started a path toward the lower hostile");

        zombie.discard();
        finish(context, bot, "DefCombatGT");
    }

    @GameTest(maxTicks = 140)
    public void unreachableDropRecoveryTypedFailsWithinItsNoProgressBudget(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "RecoverRouteGT", start);
        int worldTop = context.getLevel().getMinY() + context.getLevel().getHeight();
        BlockPos unreachable = new BlockPos(start.getX(), worldTop + 10, start.getZ());
        RecoverDropsTask task = new RecoverDropsTask(
                unreachable, context.getLevel().getServer().getTickCount());
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_recover_unreachable"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                require(context, task.elapsedTicks() <= 120,
                        "unreachable recovery remained running beyond 120 ticks");
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "unreachable recovery ended as " + task.state());
            require(context, task.failureReason().startsWith("recover_route_unreachable:"),
                    "unexpected unreachable recovery reason: " + task.failureReason());
            require(context, task.elapsedTicks() <= 120,
                    "typed route failure exceeded 120 ticks: " + task.elapsedTicks());
            require(context, bot.blockPosition().equals(start),
                    "unreachable recovery moved away from its start: "
                            + start.toShortString() + " -> " + bot.blockPosition().toShortString());
            finish(context, bot, "RecoverRouteGT");
        });
    }

    private static void assertPhysicalShelterExit(GameTestHelper context,
                                                  AIPlayerEntity bot,
                                                  BlockPos shelterFeet) {
        BlockPos actual = bot.blockPosition();
        int horizontal = Math.abs(actual.getX() - shelterFeet.getX())
                + Math.abs(actual.getZ() - shelterFeet.getZ());
        require(context, actual.getY() == shelterFeet.getY() && horizontal == 1,
                "shelter terminal pose was not one physical adjacent exit step: "
                        + shelterFeet.toShortString() + " -> " + actual.toShortString());
        require(context, Standability.isStandable(context.getLevel(), actual),
                "shelter terminal exit was not standable: " + actual.toShortString());
        require(context, context.getLevel().getBlockState(actual).isAir()
                        && context.getLevel().getBlockState(actual.above()).isAir(),
                "shelter terminal exit did not leave a two-block opening");
    }

    private static void clearVolume(GameTestHelper context, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    context.getLevel().setBlock(center.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }
}
