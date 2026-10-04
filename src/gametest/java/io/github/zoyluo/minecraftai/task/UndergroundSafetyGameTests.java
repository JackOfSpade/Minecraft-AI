package io.github.zoyluo.minecraftai.task;

import com.mojang.logging.LogUtils;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
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
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.finish;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.isSealed;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.preparePlatform;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.shelterShell;

/** Deterministic regressions for the bounded underground safety contracts. */
public final class UndergroundSafetyGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

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

    @GameTest(environment = "minecraftai-gametest:underground_safety_game_tests_partial_shelter_failure_publishes_after_physical_exit", maxTicks = 300)
    public void partialShelterFailurePublishesAfterPhysicalExit(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);

        AIPlayerEntity bot = spawn(context, "ShelterFailureExitGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_failure_exit"));

        boolean[] inventoryDrained = {false};
        context.failIfEver(() -> {
            if (!inventoryDrained[0]
                    && isSealed(context, feet.north())
                    && isSealed(context, feet.north().above())
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

    /**
     * A neutral, collidable boat appears only after each forced doorway is physically open. The
     * real shelter task sees each preflight refusal, gives that live doorway its bounded retries,
     * then rotates through all four owned doors. Once none remain it must publish a failure and
     * exact cleanup debt rather than spinning under {@link EmergencyShelterTask#isWaiting()}.
     */
    @GameTest(maxTicks = 700)
    public void forcedEgressPreflightRefusalsExhaustDoorsAndRegisterCleanup(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        context.getLevel().setDayTime(1000L);
        AIPlayerEntity bot = spawn(context, "ShelterForcedPreflightGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        TeleportAudit.reset(bot);

        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);
        List<Boat> blockers = new ArrayList<>();
        List<BlockPos> blockedDoors = new ArrayList<>();
        int[] forcedAt = {-1};
        int[] fourthDoorBlockedAt = {-1};

        context.onEachTick(() -> {
            context.getLevel().setDayTime(1000L);
            if (task.state() == TaskState.RUNNING) {
                if (task.describe().contains("force_pressure_exit=true") && forcedAt[0] < 0) {
                    forcedAt[0] = task.elapsedTicks();
                }
                // This callback deliberately runs before the task's next tick. At STEP_OUT the
                // foot/head cells are already physically open, so this is a genuine preflight
                // landing refusal rather than a block-mining or post-start fixture.
                if (forcedAt[0] >= 0
                        && task.describe().contains("phase=STEP_OUT")
                        && bot.getActionPack().stepIdle()) {
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        BlockPos door = feet.relative(direction);
                        if (!blockedDoors.contains(door)
                                && context.getLevel().getBlockState(door).isAir()
                                && context.getLevel().getBlockState(door.above()).isAir()) {
                            blockers.add(spawnBoatOccupant(context, door));
                            blockedDoors.add(door.immutable());
                        }
                    }
                    if (blockedDoors.size() == 4 && fourthDoorBlockedAt[0] < 0) {
                        // The full forced transaction includes real vanilla mining for the first
                        // three owned doors, so that elapsed time is intentionally not treated as
                        // a spin. Once the fourth live doorway is open and occupied, only its
                        // three remaining bounded preflight attempts (the production limit) and
                        // terminal handoff are left. The 80-tick tail below allows ordinary tick
                        // scheduling without treating the earlier real mining as a spin.
                        fourthDoorBlockedAt[0] = task.elapsedTicks();
                    }
                }
                task.tick(bot);
                require(context, task.elapsedTicks() < 620,
                        "forced preflight exhaustion did not reach a bounded terminal: " + task.describe());
                return;
            }

            require(context, forcedAt[0] >= 0, "fixture never entered forced egress");
            require(context, blockedDoors.size() == 4,
                    "forced preflight did not rotate across four factual doorways: " + blockedDoors);
            require(context, fourthDoorBlockedAt[0] >= 0,
                    "fixture never occupied the fourth forced doorway");
            require(context, task.state() == TaskState.FAILED
                            && task.failureReason().startsWith("shelter_exit_preflight_refused:"),
                    "forced preflight exhaustion ended unexpectedly: "
                            + task.state() + ":" + task.failureReason());
            require(context, task.elapsedTicks() - fourthDoorBlockedAt[0] <= 80,
                    "forced preflight exhaustion did not terminate within 80 ticks of the fourth "
                            + "open occupied doorway: " + task.describe());
            require(context, bot.blockPosition().equals(feet),
                    "preflight refusal moved through an occupied doorway: " + bot.blockPosition().toShortString());
            require(context, EmergencyShelterTask.hasPendingCleanup(bot),
                    "failed forced preflight did not register exact owned cleanup");
            require(context, EmergencyShelterTask.pendingExitDebt(bot).isEmpty(),
                    "open forced doorways incorrectly became a sealed-shell exit debt");
            require(context, bot.getActionPack().stepIdle() && bot.getActionPack().isMiningIdle(),
                    "failed forced preflight left shelter input in flight");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "forced preflight used a teleport: " + TeleportAudit.lastCaller(bot));
            blockers.forEach(Boat::discard);
            finish(context, bot, "ShelterForcedPreflightGT");
        });
    }

    /**
     * The obstacle is introduced only after a real {@code shelter_owned_egress} step is in
     * flight. Each blocked step therefore exercises the post-start completion path, not the
     * preflight check above. The task must reject all four three-attempt doorways and settle its
     * remaining owned shell as cleanup without inventing an exit landing.
     */
    @GameTest(maxTicks = 700)
    public void forcedEgressPostStartFailuresExhaustDoorsAndRegisterCleanup(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        context.getLevel().setDayTime(1000L);
        AIPlayerEntity bot = spawn(context, "ShelterForcedPostStartGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        TeleportAudit.reset(bot);

        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);
        Boat[] activeBlocker = {null};
        BlockPos[] activeDoor = {null};
        List<BlockPos> steppedDoors = new ArrayList<>();
        int[] forcedAt = {-1};
        int[] postStartFailures = {0};

        context.onEachTick(() -> {
            context.getLevel().setDayTime(1000L);
            if (task.state() == TaskState.RUNNING) {
                if (task.describe().contains("force_pressure_exit=true") && forcedAt[0] < 0) {
                    forcedAt[0] = task.elapsedTicks();
                }
                if (forcedAt[0] >= 0 && activeBlocker[0] == null) {
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        BlockPos door = feet.relative(direction);
                        if (bot.getActionPack().stepInFlightFor(
                                "shelter_owned_egress", door, WalkedStep.Kind.FLAT)) {
                            activeDoor[0] = door.immutable();
                            activeBlocker[0] = spawnBoatOccupant(context, door);
                            if (!steppedDoors.contains(door)) {
                                steppedDoors.add(door.immutable());
                            }
                            break;
                        }
                    }
                } else if (activeBlocker[0] != null && bot.getActionPack().stepIdle()) {
                    WalkedStep.Result result = bot.getActionPack().stepResult();
                    require(context, result != null && result.failed(),
                            "post-start boat did not fail its in-flight egress step at "
                                    + activeDoor[0].toShortString());
                    activeBlocker[0].discard();
                    activeBlocker[0] = null;
                    activeDoor[0] = null;
                    postStartFailures[0]++;
                }
                task.tick(bot);
                require(context, task.elapsedTicks() < 620,
                        "forced post-start exhaustion did not reach a bounded terminal: " + task.describe());
                return;
            }

            require(context, activeBlocker[0] == null, "terminal arrived while a post-start blocker remained");
            require(context, forcedAt[0] >= 0, "fixture never entered forced egress");
            require(context, steppedDoors.size() == 4 && postStartFailures[0] == 12,
                    "post-start failures did not use three real attempts at all four doors: doors="
                            + steppedDoors + " failures=" + postStartFailures[0]);
            require(context, task.state() == TaskState.FAILED
                            && "shelter_exit_pose_unverified".equals(task.failureReason()),
                    "forced post-start exhaustion ended unexpectedly: "
                            + task.state() + ":" + task.failureReason());
            require(context, task.elapsedTicks() - forcedAt[0] <= 240,
                    "forced post-start exhaustion spun after all doors failed: " + task.describe());
            require(context, bot.blockPosition().equals(feet),
                    "post-start failure invented an occupied-door exit: " + bot.blockPosition().toShortString());
            require(context, EmergencyShelterTask.hasPendingCleanup(bot),
                    "failed forced post-start exit did not register exact owned cleanup");
            require(context, EmergencyShelterTask.pendingExitDebt(bot).isEmpty(),
                    "open forced doorways incorrectly became a sealed-shell exit debt");
            require(context, bot.getActionPack().stepIdle() && bot.getActionPack().isMiningIdle(),
                    "failed forced post-start exit left shelter input in flight");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "forced post-start exit used a teleport: " + TeleportAudit.lastCaller(bot));
            finish(context, bot, "ShelterForcedPostStartGT");
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
        BlockPos waterSupport = start.south();
        context.getLevel().setBlock(water.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(water,
                Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(waterSupport.below(),
                Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "StandableStepGT", start);
        // A walked step applies the landing rules before it presses a key.
        require(context, io.github.zoyluo.minecraftai.action.WalkedStep.refusal(bot, unsupported,
                        io.github.zoyluo.minecraftai.action.WalkedStep.Kind.FLAT) != null,
                "a walked step accepted an unsupported air cell as its landing");
        require(context, bot.blockPosition().equals(start),
                "unsupported step moved the bot: " + bot.blockPosition().toShortString());
        require(context, io.github.zoyluo.minecraftai.action.WalkedStep.refusal(bot, water,
                        io.github.zoyluo.minecraftai.action.WalkedStep.Kind.FLAT) != null,
                "a walked step accepted a water cell as its landing");
        require(context, bot.blockPosition().equals(start),
                "water step moved the bot: " + bot.blockPosition().toShortString());
        require(context, io.github.zoyluo.minecraftai.action.WalkedStep.refusal(bot, waterSupport,
                        io.github.zoyluo.minecraftai.action.WalkedStep.Kind.FLAT) != null,
                "a walked step accepted air above water as dry footing");
        require(context, bot.blockPosition().equals(start),
                "water-supported step moved the bot: " + bot.blockPosition().toShortString());
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

        require(context, io.github.zoyluo.minecraftai.action.WalkedStep.refusal(bot, occupied,
                        io.github.zoyluo.minecraftai.action.WalkedStep.Kind.FLAT) != null,
                "a walked step accepted an entity-occupied landing");
        require(context, bot.blockPosition().equals(start),
                "occupied landing moved the bot: " + bot.blockPosition().toShortString());

        zombie.discard();
        finish(context, bot, "OccupiedStepGT");
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
        // Blocks that stay where they are: the exit now takes real ticks, and a gravel block over an open cell would fall away (or onto
        // the bot) while it does. Both of the bot's cells are filled, as a collapsed two-high gravel column fills them: with only the head
        // cell blocked a player (and so a bot) just crawls (its pose shrinks to 0.6 blocks) and is not inside the block at all. The
        // falling-gravel burial itself is covered by NaturalMovementGameTests.
        context.getLevel().setBlock(
                start, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
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
        // R5: the exit is a real shove out of the gravel (no teleport), so the bot is inside the block for the few ticks it takes.
        // The serialized run exited in 10 ticks with zero loss; permit at most one heart for vanilla damage-frame timing.
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
            require(context, bot.isAlive() && bot.getHealth() >= healthBefore - 2.0F,
                    "bot took more than one heart before the adjacent suffocation exit: " + bot.getHealth());
            LOGGER.info("STRICT_SUFFOCATION_PHYSICAL_EXIT ticks={} health_loss={} health_before={} health_after={}",
                    ticks[0], healthBefore - bot.getHealth(), healthBefore, bot.getHealth());
            require(context, bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()
                            && bot.getActionPack().isMiningIdle(),
                    "suffocation recovery left the stale route able to replay its blocked edge");

            NavSafetyNet.INSTANCE.clear(bot);
            finish(context, bot, "StrictSuffocationGT");
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

    /** A neutral collidable occupant for doorway checks; unlike a hostile it cannot alter combat ownership. */
    private static Boat spawnBoatOccupant(GameTestHelper context, BlockPos feet) {
        Boat boat = EntityType.OAK_BOAT.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (boat == null) {
            throw new IllegalStateException("failed to create occupied doorway boat");
        }
        boat.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        if (!context.getLevel().addFreshEntity(boat)) {
            throw new IllegalStateException("failed to spawn occupied doorway boat");
        }
        return boat;
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
