package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.assertPhysicalExit;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.finish;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.isSealed;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.preparePlatform;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.runLocked;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.shelterShell;

/** Physical regressions for the shelter's ordered build and sealed healing transaction. */
public final class EmergencyShelterAtomicRecoveryGameTests {
    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_moving_edge_anchor_settles_before_envelope_placement", maxTicks = 16000)
    public void movingEdgeAnchorSettlesBeforeEnvelopePlacement(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterAnchorSettleGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        bot.teleportTo(context.getLevel(), feet.getX() + 0.78D, feet.getY(),
                feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        bot.setOnGround(true);
        bot.setDeltaMovement(new Vec3(0.18D, 0.0D, 0.0D));
        require(context, bot.blockPosition().equals(feet)
                        && new net.minecraft.world.phys.AABB(feet.east())
                        .intersects(bot.getBoundingBox()),
                "moving-edge fixture did not overlap the east wall cell");

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_anchor_settlement"));
        require(context, Math.abs(bot.getX() - (feet.getX() + 0.5D)) < 1.0E-6D
                        && Math.abs(bot.getZ() - (feet.getZ() + 0.5D)) < 1.0E-6D
                        && bot.getDeltaMovement().lengthSqr() <= 1.0E-8D,
                "shelter admission did not settle the moving edge pose");

        boolean[] eastWallSealed = {false};
        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("moving-edge shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            if (isSealed(context, feet.east()) && isSealed(context, feet.east().above())) {
                eastWallSealed[0] = true;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, eastWallSealed[0],
                    "settled shelter never sealed the formerly overlapping east wall");
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterAnchorSettleGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_build_time_edge_correction_places_wall_in_same_tick", maxTicks = 30)
    public void buildTimeEdgeCorrectionPlacesWallInSameTick(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        AIPlayerEntity bot = spawn(context, "ShelterBuildSettleGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));

        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);
        require(context, task.state() == TaskState.RUNNING,
                "build-time settlement fixture failed shelter admission");
        bot.teleportTo(context.getLevel(), feet.getX() + 0.5D, feet.getY(),
                feet.getZ() + 0.22D, Set.of(), 0.0F, 0.0F, false);
        bot.setOnGround(true);
        bot.setDeltaMovement(Vec3.ZERO);
        BlockPos northWall = feet.north();
        require(context, new AABB(northWall).intersects(bot.getBoundingBox()),
                "build-time settlement fixture did not overlap its first wall");

        task.tick(bot);

        require(context, Math.abs(bot.getX() - (feet.getX() + 0.5D)) < 1.0E-6D
                        && Math.abs(bot.getZ() - (feet.getZ() + 0.5D)) < 1.0E-6D,
                "build-time edge correction did not return to the exact anchor center");
        require(context, isSealed(context, northWall),
                "successful edge correction ended the tick before wall placement");
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "ShelterBuildSettleGT");
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_persistent_hostile_gets_one_strike_then_forces_physical_exit", maxTicks = 16000)
    public void persistentHostileGetsOneStrikeThenForcesPhysicalExit(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterWallBlockerGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD, 1));
        Husk blocker = spawnHusk(context, feet.east());
        float blockerHealth = blocker.getHealth();

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_wall_blocker"));
        boolean[] struck = {false};
        float[] previousBlockerHealth = {blockerHealth};
        int[] damageEvents = {0};

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (blocker.getHealth() < previousBlockerHealth[0]) {
                damageEvents[0]++;
                struck[0] = true;
                previousBlockerHealth[0] = blocker.getHealth();
            }
            require(context, damageEvents[0] <= 1,
                    "shelter repeatedly attacked a persistent wall blocker");
            require(context, blocker.isAlive(),
                    "shelter killed the blocker instead of releasing through another side");
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_wall_blocked_by_persistent_hostile"
                            .equals(task.failureReason()),
                    "persistent blocker produced the wrong terminal: "
                            + task.state() + ":" + task.failureReason());
            require(context, struck[0] && damageEvents[0] == 1,
                    "persistent blocker was not given exactly one physical clearance strike");
            require(context, !isSealed(context, feet.east()),
                    "shelter sealed the still-occupied east wall");
            assertPhysicalExit(context, bot, feet);
            blocker.discard();
            finish(context, bot, "ShelterWallBlockerGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_occupied_centered_aabb_rejects_same_cell_correction", maxTicks = 30)
    public void occupiedCenteredAabbRejectsSameCellCorrection(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        AIPlayerEntity bot = spawn(context, "ShelterCenteredEntityGuardGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        bot.teleportTo(context.getLevel(), feet.getX() + 0.78D, feet.getY(),
                feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        double edgeX = bot.getX();
        Boat occupant = EntityType.OAK_BOAT.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (occupant == null) {
            finish(context, bot, "ShelterCenteredEntityGuardGT");
            context.fail(Component.nullToEmpty("failed to create centered non-living occupant"));
            return;
        }
        occupant.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                0.0F, 0.0F);
        require(context, context.getLevel().addFreshEntity(occupant),
                "failed to spawn centered non-living occupant");

        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);
        require(context, task.state() == TaskState.FAILED
                        && "shelter_origin_not_centerable".equals(task.failureReason()),
                "occupied centered AABB did not reject shelter admission: "
                        + task.state() + ":" + task.failureReason());
        require(context, Math.abs(bot.getX() - edgeX) < 1.0E-6D,
                "center correction teleported through a collidable non-living entity");
        occupant.discard();
        finish(context, bot, "ShelterCenteredEntityGuardGT");
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_ai_enabled_close_pressure_uses_one_strike_and_low_health_bot_survives", maxTicks = 16000)
    public void aiEnabledClosePressureUsesOneStrikeAndLowHealthBotSurvives(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterAiPressureGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD, 1));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_ai_pressure"));
        Husk[] hostile = {null};
        float[] previousHostileHealth = {Float.NaN};
        int[] damageEvents = {0};
        int[] closePressureTicks = {0};
        boolean[] lowHealthInjected = {false};

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (hostile[0] == null
                    && isSealed(context, feet.north())
                    && isSealed(context, feet.north().above())) {
                bot.setHealth(8.0F);
                lowHealthInjected[0] = true;
                hostile[0] = spawnAiHusk(context, feet.east(), bot);
                previousHostileHealth[0] = hostile[0].getHealth();
                require(context, !hostile[0].isNoAi(),
                        "close-pressure hostile unexpectedly had AI disabled");
                return;
            }
            if (hostile[0] != null) {
                hostile[0].setTarget(bot);
                if (hostile[0].getHealth() < previousHostileHealth[0]) {
                    damageEvents[0]++;
                    previousHostileHealth[0] = hostile[0].getHealth();
                }
                if (new AABB(feet.east()).intersects(hostile[0].getBoundingBox())
                        || bot.distanceToSqr(hostile[0]) <= 2.25D) {
                    closePressureTicks[0]++;
                }
                require(context, damageEvents[0] <= 1,
                        "AI pressure caused repeated shelter attacks");
                require(context, hostile[0].isAlive(),
                        "AI pressure test relied on killing the hostile");
            }
            require(context, bot.isAlive() && bot.getHealth() > 0.0F,
                    "low-health bot died under close shelter pressure");
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_wall_blocked_by_persistent_hostile"
                            .equals(task.failureReason()),
                    "AI pressure produced the wrong terminal: "
                            + task.state() + ":" + task.failureReason());
            require(context, lowHealthInjected[0]
                            && damageEvents[0] == 1
                            && closePressureTicks[0] >= 2,
                    "fixture did not prove sustained close pressure with one strike");
            require(context, bot.isAlive() && bot.getHealth() > 0.0F,
                    "low-health bot did not survive its physical release");
            assertPhysicalExit(context, bot, feet);
            hostile[0].discard();
            finish(context, bot, "ShelterAiPressureGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_melee_forbidden_occupied_egress_uses_alternate_owned_exit", maxTicks = 16000)
    public void meleeForbiddenOccupiedEgressUsesAlternateOwnedExit(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterForbiddenBlockerGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD, 1));
        Creeper creeper = EntityType.CREEPER.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (creeper == null) {
            finish(context, bot, "ShelterForbiddenBlockerGT");
            context.fail(Component.nullToEmpty("failed to create forbidden wall blocker"));
            return;
        }
        creeper.setPersistenceRequired();
        creeper.setNoAi(true);
        BlockPos blockedEgress = feet.north();
        creeper.snapTo(
                blockedEgress.getX() + 0.5D, blockedEgress.getY(),
                blockedEgress.getZ() + 0.5D, 0.0F, 0.0F);
        require(context, context.getLevel().addFreshEntity(creeper),
                "failed to spawn forbidden wall blocker");
        float creeperHealth = creeper.getHealth();

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_forbidden_wall_blocker"));
        boolean[] blockerReleasedAfterRejection = {false};
        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            require(context, creeper.getHealth() == creeperHealth,
                    "shelter performed forbidden melee against a Creeper");
            // OPEN_EXIT proves the adjacent Creeper was already classified as a forbidden
            // blocker and the alternate doorway transaction is committed. Release the fixture
            // now so the bot-wide DangerWatcher cannot start its own synchronous Creeper defense
            // after the shelter publishes the expected terminal in the same server tick.
            if (!blockerReleasedAfterRejection[0]
                    && task.state() == TaskState.RUNNING
                    && task.describe().contains("phase=OPEN_EXIT")) {
                blockerReleasedAfterRejection[0] = true;
                creeper.discard();
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_wall_blocked_by_melee_forbidden_hostile"
                            .equals(task.failureReason()),
                    "forbidden blocker produced the wrong terminal: "
                            + task.state() + ":" + task.failureReason());
            require(context, blockerReleasedAfterRejection[0],
                    "fixture never observed the committed alternate-exit transaction");
            require(context, bot.blockPosition().equals(feet.east()),
                    "shelter entered the rejected Creeper egress instead of east: "
                            + bot.blockPosition().toShortString());
            creeper.discard();
            finish(context, bot, "ShelterForbiddenBlockerGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_adjacent_second_shelter_reuses_residual_roof_without_blocked_support_jump", maxTicks = 16000)
    public void adjacentSecondShelterReusesResidualRoofWithoutBlockedSupportJump(
            GameTestHelper context) {
        BlockPos firstFeet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, firstFeet, 5);
        AIPlayerEntity bot = spawn(context, "ShelterAdjacentGT", firstFeet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 40));

        EmergencyShelterTask first = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, first,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_adjacent_first"));
        EmergencyShelterTask[] second = {null};
        BlockPos[] secondFeet = {null};
        BlockPos[] inheritedCenterRoof = {null};
        BlockPos[] unnecessarySecondRim = {null};

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (second[0] == null) {
                if (first.state() == TaskState.FAILED || first.state() == TaskState.CANCELLED) {
                    context.fail(Component.nullToEmpty("first shelter ended as " + first.state()
                            + ":" + first.failureReason() + " " + first.describe()));
                    return;
                }
                BlockPos firstRoof = firstFeet.above(2);
                // Once OPEN_EXIT starts, the north head cell is intentionally mined while the
                // center roof remains. Only the BUILD/HOLD window can prove construction order.
                boolean envelopeOwned = first.describe().contains("phase=BUILD")
                        || first.describe().contains("phase=HOLD");
                if (envelopeOwned && isSealed(context, firstRoof)) {
                    require(context, isSealed(context, firstFeet.above().north()),
                            "center roof appeared before roofSupportBase");
                    require(context, isSealed(context, firstRoof.north()),
                            "center roof appeared before roofSupport");
                }
                if (first.state() != TaskState.COMPLETED) {
                    return;
                }

                secondFeet[0] = bot.blockPosition().immutable();
                require(context, secondFeet[0].equals(firstFeet.north()),
                        "first shelter did not leave through its deterministic north doorway: "
                                + secondFeet[0].toShortString());
                inheritedCenterRoof[0] = secondFeet[0].above(2).immutable();
                unnecessarySecondRim[0] = inheritedCenterRoof[0].north().immutable();
                require(context, context.getLevel().getBlockState(inheritedCenterRoof[0])
                                .is(Blocks.DIRT),
                        "first shelter did not leave its rim as the adjacent center roof");
                require(context, context.getLevel().getBlockState(unnecessarySecondRim[0]).isAir(),
                        "adjacent fixture unexpectedly began with a second rim");

                second[0] = new EmergencyShelterTask();
                TaskManager.INSTANCE.assign(bot, second[0],
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_shelter_adjacent_second"));
                return;
            }

            if (second[0].state() == TaskState.FAILED
                    || second[0].state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("adjacent second shelter ended as "
                        + second[0].state() + ":" + second[0].failureReason()
                        + " " + second[0].describe()));
                return;
            }
            if (second[0].state() != TaskState.COMPLETED) {
                return;
            }
            require(context, context.getLevel().getBlockState(inheritedCenterRoof[0])
                            .is(Blocks.DIRT),
                    "second shelter replaced or removed its inherited center roof");
            require(context, context.getLevel().getBlockState(unnecessarySecondRim[0]).isAir(),
                    "second shelter built an unnecessary rim despite an existing center roof");
            assertPhysicalExit(context, bot, secondFeet[0]);
            finish(context, bot, "ShelterAdjacentGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_low_health_shelter_consumes_backpack_food_and_heals_before_opening", maxTicks = 16000)
    public void lowHealthShelterConsumesBackpackFoodAndHealsBeforeOpening(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterSealedEatGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_sealed_eat"));
        boolean[] lowHealthInjected = {false};
        boolean[] consumedWhileSealed = {false};
        boolean[] healed = {false};
        boolean[] safeThresholdObserved = {false};

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("sealed-eat shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            boolean sealed = shell.stream().allMatch(pos -> isSealed(context, pos));
            if (!lowHealthInjected[0] && sealed) {
                bot.setHealth(5.0F);
                bot.getFoodData().setFoodLevel(12);
                bot.getFoodData().setSaturation(0.0F);
                lowHealthInjected[0] = true;
                return;
            }
            if (lowHealthInjected[0]) {
                int beef = InventoryAction.countItem(bot, Items.COOKED_BEEF);
                // Observe the atomic consumption edge once. After safe healing, OPEN_EXIT must
                // make the envelope non-sealed while the already-consumed count stays below two.
                if (beef < 2 && !consumedWhileSealed[0]) {
                    require(context, sealed,
                            "shelter opened before its physical food consumption completed");
                    consumedWhileSealed[0] = true;
                }
                if (bot.getHealth() > 5.0F) {
                    healed[0] = true;
                }
                if (bot.getHealth() >= 18.0F) {
                    safeThresholdObserved[0] = true;
                } else {
                    require(context, task.state() == TaskState.RUNNING && sealed,
                            "shelter opened or terminated below its safe exit threshold: hp="
                                    + bot.getHealth() + " state=" + task.state());
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, lowHealthInjected[0],
                    "sealed-eat fixture never reached the HOLD envelope");
            require(context, consumedWhileSealed[0],
                    "backpack food was not physically consumed while the shelter was sealed");
            require(context, healed[0] && safeThresholdObserved[0]
                            && bot.getHealth() >= 18.0F,
                    "shelter exited before physical healing reached 18 HP: hp="
                            + bot.getHealth());
            require(context, bot.getFoodData().getFoodLevel() > 12,
                    "food use did not increase the hunger level");
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterSealedEatGT");
        });
    }

    // EmergencyShelterTask#shouldStartHoldEating (pinned by EmergencyShelterEatingPolicyTest) tops every recovery
    // shelter's hunger bar to 20, so a bot at food 19 spends its reserve item while still sealed, and the shelter only
    // opens once health is full. (This test used to expect "food 19 heals naturally without eating" and an 18 HP exit,
    // an older policy.)
    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_food_nineteen_tops_hunger_to_twenty_from_reserve_while_sealed", maxTicks = 16000)
    public void foodNineteenTopsHungerToTwentyFromReserveWhileSealed(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterFoodNineteenGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_food_nineteen_top_up"));
        boolean[] boundaryInjected = {false};
        boolean[] reserveSpentWhileSealed = {false};
        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("food-19 shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            boolean sealed = shell.stream().allMatch(pos -> isSealed(context, pos));
            if (!boundaryInjected[0] && sealed) {
                bot.setHealth(17.0F);
                bot.getFoodData().setFoodLevel(19);
                bot.getFoodData().setSaturation(5.0F);
                boundaryInjected[0] = true;
                return;
            }
            if (boundaryInjected[0]) {
                if (InventoryAction.countItem(bot, Items.COOKED_BEEF) == 0
                        && !reserveSpentWhileSealed[0]) {
                    require(context, sealed,
                            "shelter opened before its reserve food was physically consumed");
                    reserveSpentWhileSealed[0] = true;
                }
                if (bot.getHealth() < bot.getMaxHealth()) {
                    // Out of food items but with hunger at 18+ the bot still regenerates: it holds sealed
                    // while natural regeneration is actually running (the fixture's saturation funds the few
                    // heals needed); it must not cry for help or give up at half health merely because no
                    // food item is left.
                    require(context, task.state() == TaskState.RUNNING && sealed,
                            "food-19 shelter opened before healing completed: hp=" + bot.getHealth()
                                    + " " + task.describe());
                    require(context, !task.describe().contains("cried_for_help=true"),
                            "a regenerating bot with a full hunger bar must not cry for help: "
                                    + task.describe());
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, boundaryInjected[0] && reserveSpentWhileSealed[0]
                            && bot.getHealth() >= bot.getMaxHealth()
                            && bot.getFoodData().getFoodLevel() >= 20,
                    "food-19 fixture did not top hunger up from the reserve and heal fully: hp="
                            + bot.getHealth() + " food=" + bot.getFoodData().getFoodLevel());
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterFoodNineteenGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_surface_shelter_stays_sealed_until_daylight", maxTicks = 16000)
    public void surfaceShelterStaysSealedUntilDaylight(GameTestHelper context) {
        BlockPos feet = highSurfaceFeet(context);
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterNightHoldGT", feet);
        context.getLevel().setDayTime(18000L);
        require(context, EmergencyShelterTask.isSurfaceShelterAnchor(bot, feet),
                "high terrain anchor was not classified as a surface shelter");
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_night_hold"));
        int[] sealedNightTicks = {0};
        boolean[] daylightReleased = {false};
        boolean[] firstDaylightTickHeld = {false};
        boolean[] nightInterruptedGrace = {false};
        boolean[] interruptedGraceReset = {false};

        runLocked(context, () -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("night shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            if (!daylightReleased[0] && task.describe().contains("phase=HOLD")) {
                context.getLevel().setDayTime(18000L);
                sealedNightTicks[0]++;
                require(context, shelterShell(feet).stream().allMatch(
                                pos -> isSealed(context, pos)),
                        "surface shelter opened during the night hold");
                if (sealedNightTicks[0] >= 140) {
                    daylightReleased[0] = true;
                    context.getLevel().setDayTime(1000L);
                }
                return;
            }
            if (daylightReleased[0]) {
                context.getLevel().setDayTime(1000L);
                int daylightTicks = describedInt(task, "daylight_ticks");
                if (nightInterruptedGrace[0] && daylightTicks == 0) {
                    interruptedGraceReset[0] = true;
                }
                if (task.state() == TaskState.RUNNING
                        && daylightTicks > 0
                        && daylightTicks < 100) {
                    require(context, task.describe().contains("phase=HOLD")
                                    && shelterShell(feet).stream().allMatch(
                                    pos -> isSealed(context, pos)),
                            "surface shelter opened before 100 consecutive daylight ticks: "
                                    + task.describe());
                    if (daylightTicks == 1) {
                        firstDaylightTickHeld[0] = true;
                    }
                    if (daylightTicks == 50 && !nightInterruptedGrace[0]) {
                        context.getLevel().setDayTime(18000L);
                        nightInterruptedGrace[0] = true;
                    }
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, daylightReleased[0] && sealedNightTicks[0] >= 140,
                    "surface shelter exited before proving a sustained night HOLD");
            require(context, firstDaylightTickHeld[0]
                            && nightInterruptedGrace[0]
                            && interruptedGraceReset[0]
                            && describedInt(task, "daylight_ticks") >= 100,
                    "surface shelter did not enforce/reset the consecutive daylight grace: "
                            + task.describe());
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterNightHoldGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_leaf_canopy_still_uses_surface_night_hold", maxTicks = 16000)
    public void leafCanopyStillUsesSurfaceNightHold(GameTestHelper context) {
        verifyOccludedSurfaceNightHold(
                context, Blocks.OAK_LEAVES, 4,
                "ShelterCanopyNightGT", "leaf canopy");
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_shallow_overhang_still_uses_surface_night_hold", maxTicks = 16000)
    public void shallowOverhangStillUsesSurfaceNightHold(GameTestHelper context) {
        verifyOccludedSurfaceNightHold(
                context, Blocks.STONE, 5,
                "ShelterOverhangNightGT", "shallow overhang");
    }

    // The shelter's HOLD ends in beginRecoveredExit, which deliberately FORCES the door: "a fully healed
    // bot deliberately reopens its own door even if the original hostile is still visible ... resealing
    // forever would turn recovery into a dirt prison" (EmergencyShelterTask#beginSafeExit). These three
    // scenarios used to expect the head-port observation reseal / four-door rotation / typed pressure
    // timeout for a recovered bot, but that machinery only runs for a non-forced exit. They now pin the
    // documented policy: a recovered bot leaves through its own doorway with a hostile visible at it,
    // whether or not it holds a spare block, and never publishes a pressure reseal or a timeout.

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_recovered_bot_reopens_its_door_despite_a_hostile_at_the_head_port", maxTicks = 16000)
    public void recoveredBotReopensItsDoorDespiteAHostileAtTheHeadPort(GameTestHelper context) {
        verifyRecoveredBotLeavesDespiteHostiles(context, "ShelterObservationResealGT",
                "gametest_shelter_recovered_exit_hostile_at_port", List.of(Direction.NORTH), false);
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_recovered_bot_reopens_its_door_despite_hostiles_on_all_four_sides", maxTicks = 16000)
    public void recoveredBotReopensItsDoorDespiteHostilesOnAllFourSides(GameTestHelper context) {
        verifyRecoveredBotLeavesDespiteHostiles(context, "ShelterAllPressureGT",
                "gametest_shelter_recovered_exit_all_pressure",
                List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST), false);
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_recovered_bot_needs_no_reseal_block_to_leave_despite_a_hostile", maxTicks = 16000)
    public void recoveredBotNeedsNoResealBlockToLeaveDespiteAHostile(GameTestHelper context) {
        verifyRecoveredBotLeavesDespiteHostiles(context, "ShelterResealFailureGT",
                "gametest_shelter_recovered_exit_no_reseal_block", List.of(Direction.NORTH), true);
    }

    private static void verifyRecoveredBotLeavesDespiteHostiles(GameTestHelper context,
                                                                String botName,
                                                                String reason,
                                                                List<Direction> hostileSides,
                                                                boolean noSpareBlock) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, botName, feet);
        // Ten blocks exactly fund the supported envelope; the no-spare variant then fills every free slot so
        // a mined wall drop cannot be collected either: the exit must not depend on having a block to reseal.
        InventoryAction.giveItem(bot, new ItemStack(noSpareBlock ? Items.NETHERRACK : Items.DIRT,
                noSpareBlock ? 10 : 32));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, reason));
        List<Husk> hostiles = new ArrayList<>();

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("recovered-exit shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            if (hostiles.isEmpty() && task.state() == TaskState.RUNNING
                    && task.describe().contains("phase=HOLD")) {
                if (noSpareBlock) {
                    require(context, InventoryAction.countItem(bot, Items.NETHERRACK) == 0,
                            "exact-material fixture retained a reseal block");
                    for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
                        if (bot.getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                            bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.STICK, 64));
                        }
                    }
                    bot.getInventory().setChanged();
                }
                for (Direction side : hostileSides) {
                    hostiles.add(spawnHusk(context, feet.relative(side, 2)));
                }
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, !hostiles.isEmpty(), "fixture never reached the HOLD envelope");
            require(context, hostiles.stream().allMatch(Husk::isAlive),
                    "the hostiles must still be there: this proves the bot left despite them");
            require(context, describedInt(task, "observation_reseals") == 0
                            && describedInt(task, "pressured_egress") == 0
                            && task.describe().contains("force_pressure_exit=true"),
                    "a recovered bot must reopen its door (forced exit), not reseal against visible "
                            + "hostiles: " + task.describe());
            require(context, describedInt(task, "exit_age") < 500,
                    "a recovered bot must not spend the pressure deadline in a sealed shelter: "
                            + task.describe());
            assertPhysicalExit(context, bot, feet);
            hostiles.forEach(Husk::discard);
            finish(context, bot, botName);
        });
    }

    // ---- non-forced (failure) exits ------------------------------------------------------------------------------
    // The head-port observation reseal, the pressured-egress rotation and the exit deadline still run for an exit
    // that is NOT a recovered/forced one. The cleanest way to reach one is a breach during HOLD: removing the roof
    // block makes isEnvelopeSealed false, so beginExit("shelter_breached_during_hold") starts an ordinary exit while
    // the bot is at full health (a recovered bot would force its door instead, see above). The terminal failure
    // keeps the FIRST reason noted (the breach): the typed pressure timeout only flips the exit to forced mode
    // (force_pressure_exit=true once exit_age reaches the 500-tick deadline), it never replaces an earlier reason.

    private static final String BREACHED_DURING_HOLD = "shelter_breached_during_hold";
    private static final int PRESSURE_EXIT_DEADLINE = 500;

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_breach_exit_reseals_a_hostile_at_the_head_port_and_rotates_egress", maxTicks = 16000)
    public void breachExitResealsAHostileAtTheHeadPortAndRotatesEgress(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterBreachResealGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 24));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_breach_reseal"));
        Husk[] hostile = {null};
        boolean[] breached = {false};
        boolean[] resealObserved = {false};

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (!breached[0]) {
                requireStillBuildingOrHolding(context, task);
                if (task.describe().contains("phase=HOLD")) {
                    hostile[0] = spawnHusk(context, feet.north(2));
                    breachRoof(context, feet);
                    breached[0] = true;
                }
                return;
            }
            if (hostile[0] != null) {
                require(context, isSealed(context, feet.north()),
                        "the foot doorway opened next to a visible hostile: " + task.describe());
                if (describedInt(task, "observation_reseals") >= 1) {
                    require(context, isSealed(context, feet.north().above()),
                            "the observed head port was not resealed: " + task.describe());
                    resealObserved[0] = true;
                    hostile[0].discard();
                    hostile[0] = null;
                    return;
                }
            }
            if (task.state() == TaskState.RUNNING) {
                if (!task.describe().contains("phase=STEP_OUT")) {
                    require(context, bot.blockPosition().equals(feet),
                            "the bot moved before a physical STEP_OUT: " + task.describe());
                }
                return;
            }
            require(context, resealObserved[0],
                    "the exit ended without ever resealing the observed pressure: " + task.describe());
            require(context, task.state() == TaskState.FAILED
                            && BREACHED_DURING_HOLD.equals(task.failureReason()),
                    "a breach exit must end with its own typed reason: "
                            + task.state() + ":" + task.failureReason());
            require(context, !bot.blockPosition().equals(feet.north()),
                    "the exit reused the pressured north doorway instead of rotating egress");
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterBreachResealGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_breach_exit_with_hostiles_on_all_four_sides_rotates_then_forces_after_the_pressure_deadline", maxTicks = 16000)
    public void breachExitWithHostilesOnAllFourSidesRotatesThenForcesAfterThePressureDeadline(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterBreachAllPressureGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 32));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_breach_all_pressure"));
        List<Husk> hostiles = new ArrayList<>();
        boolean[] breached = {false};
        int[] maxPressured = {0};
        int[] forcedAtExitAge = {-1};

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (!breached[0]) {
                requireStillBuildingOrHolding(context, task);
                if (task.describe().contains("phase=HOLD")) {
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        hostiles.add(spawnHusk(context, feet.relative(direction, 2)));
                    }
                    breachRoof(context, feet);
                    breached[0] = true;
                }
                return;
            }
            maxPressured[0] = Math.max(maxPressured[0], describedInt(task, "pressured_egress"));
            boolean forced = task.describe().contains("force_pressure_exit=true");
            if (forced && forcedAtExitAge[0] < 0) {
                forcedAtExitAge[0] = describedInt(task, "exit_age");
            }
            if (!forced) {
                // Until the global deadline no foot doorway may open next to a visible hostile, and the bot
                // stays on its anchor.
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    require(context, isSealed(context, feet.relative(direction)),
                            "a foot doorway opened before the pressure deadline: " + direction + " "
                                    + task.describe());
                }
                require(context, bot.blockPosition().equals(feet),
                        "the bot left its anchor before the pressure deadline: " + task.describe());
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && BREACHED_DURING_HOLD.equals(task.failureReason()),
                    "a breach exit must end with its own typed reason: "
                            + task.state() + ":" + task.failureReason());
            require(context, maxPressured[0] == 4 && describedInt(task, "observation_reseals") >= 4,
                    "the exit did not rotate across all four pressured doors before forcing: max_pressured="
                            + maxPressured[0] + " " + task.describe());
            require(context, forcedAtExitAge[0] >= PRESSURE_EXIT_DEADLINE,
                    "the pressure exit was forced before its deadline: exit_age=" + forcedAtExitAge[0]);
            assertPhysicalExit(context, bot, feet);
            hostiles.forEach(Husk::discard);
            finish(context, bot, "ShelterBreachAllPressureGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_breach_exit_without_a_spare_block_stays_sealed_until_the_pressure_deadline", maxTicks = 16000)
    public void breachExitWithoutASpareBlockStaysSealedUntilThePressureDeadline(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterBreachNoBlockGT", feet);
        // Ten blocks exactly fund the supported envelope. Once it is sealed, a full non-block inventory keeps
        // a mined wall drop from being collected, so the observation reseal is factually unavailable.
        InventoryAction.giveItem(bot, new ItemStack(Items.NETHERRACK, 10));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_breach_no_spare_block"));
        Husk[] hostile = {null};
        boolean[] breached = {false};
        int[] sealedRetryTicks = {0};
        int[] forcedAtExitAge = {-1};

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (!breached[0]) {
                requireStillBuildingOrHolding(context, task);
                if (task.describe().contains("phase=HOLD")) {
                    require(context, InventoryAction.countItem(bot, Items.NETHERRACK) == 0,
                            "exact-material fixture retained a reseal block");
                    for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
                        if (bot.getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                            bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.STICK, 64));
                        }
                    }
                    bot.getInventory().setChanged();
                    hostile[0] = spawnHusk(context, feet.north(2));
                    breachRoof(context, feet);
                    breached[0] = true;
                }
                return;
            }
            boolean forced = task.describe().contains("force_pressure_exit=true");
            if (forced && forcedAtExitAge[0] < 0) {
                forcedAtExitAge[0] = describedInt(task, "exit_age");
            }
            boolean headOpenFootSealed = context.getLevel().getBlockState(feet.north().above()).isAir()
                    && isSealed(context, feet.north());
            if (!forced) {
                require(context, bot.blockPosition().equals(feet) && !hasPassableEnvelopeSide(context, feet),
                        "a failed reseal published movement or a passable door before the deadline: "
                                + task.describe());
                require(context, describedInt(task, "observation_reseals") == 0,
                        "a reseal was published without a block to place: " + task.describe());
                if (task.state() == TaskState.RUNNING && headOpenFootSealed) {
                    sealedRetryTicks[0]++;
                }
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && BREACHED_DURING_HOLD.equals(task.failureReason()),
                    "a breach exit must end with its own typed reason: "
                            + task.state() + ":" + task.failureReason());
            require(context, sealedRetryTicks[0] >= 20,
                    "the fixture did not prove bounded head-open/foot-sealed reseal retries: "
                            + sealedRetryTicks[0]);
            require(context, forcedAtExitAge[0] >= PRESSURE_EXIT_DEADLINE,
                    "the pressure exit was forced before its deadline: exit_age=" + forcedAtExitAge[0]);
            assertPhysicalExit(context, bot, feet);
            if (hostile[0] != null) {
                hostile[0].discard();
            }
            finish(context, bot, "ShelterBreachNoBlockGT");
        });
    }

    /** Before the breach the shelter must still be building or holding; anything else is a broken fixture. */
    private static void requireStillBuildingOrHolding(GameTestHelper context, EmergencyShelterTask task) {
        if (task.state() != TaskState.RUNNING) {
            context.fail(Component.nullToEmpty("shelter ended before the breach fixture ran: "
                    + task.state() + ":" + task.failureReason() + " " + task.describe()));
        }
    }

    /** Removes the roof block of a sealed shelter: an ordinary (non-forced) exit starts on the next task tick. */
    private static void breachRoof(GameTestHelper context, BlockPos feet) {
        require(context, shelterShell(feet).stream().allMatch(pos -> isSealed(context, pos)),
                "the fixture breached a shelter that was not sealed yet");
        context.getLevel().setBlock(feet.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_water_rescue_and_body_fluid_reject_fixed_shelter_admission", maxTicks = 30)
    public void waterRescueAndBodyFluidRejectFixedShelterAdmission(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        AIPlayerEntity bot = spawn(context, "ShelterWaterAdmissionGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        int dirtBefore = InventoryAction.countItem(bot, Items.DIRT);

        NavSafetyNet.INSTANCE.requestWaterRescue(bot);
        require(context, !EmergencyShelterTask.canStartAtCurrentPose(bot),
                "active water rescue admitted a fixed shelter anchor");
        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);
        require(context, task.state() == TaskState.FAILED
                        && "shelter_origin_not_stable".equals(task.failureReason()),
                "water-rescue shelter start was not rejected: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.DIRT) == dirtBefore,
                "rejected water-rescue shelter consumed material");
        require(context, shelterShell(feet).stream().noneMatch(pos -> isSealed(context, pos)),
                "rejected water-rescue shelter mutated its enclosure");

        NavSafetyNet.INSTANCE.clear(bot);
        context.getLevel().setBlock(feet.above(),
                Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        require(context, !EmergencyShelterTask.canStartAtCurrentPose(bot),
                "head-column fluid admitted a fixed shelter anchor");
        context.getLevel().setBlock(feet.above(),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        finish(context, bot, "ShelterWaterAdmissionGT");
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_atomic_recovery_game_tests_sealed_shelter_reopens_owned_door_before_environmental_failure", maxTicks = 16000)
    public void sealedShelterReopensOwnedDoorBeforeEnvironmentalFailure(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterWaterExitGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_water_exit"));
        boolean[] injected = {false};

        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (!injected[0]
                    && task.state() == TaskState.RUNNING
                    && task.describe().contains("phase=HOLD")) {
                require(context, shelterShell(feet).stream().allMatch(pos -> isSealed(context, pos)),
                        "water was injected before the shelter proved a sealed envelope");
                context.getLevel().setBlock(feet,
                        Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                injected[0] = true;
                return;
            }
            if (!injected[0] && (task.state() == TaskState.FAILED
                    || task.state() == TaskState.CANCELLED
                    || task.state() == TaskState.COMPLETED)) {
                context.fail(Component.nullToEmpty("shelter ended before water injection: "
                        + task.state() + ":" + task.failureReason()));
                return;
            }
            if (!injected[0] || task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_environmental_escape_required"
                            .equals(task.failureReason()),
                    "wet shelter did not publish its typed terminal: "
                            + task.state() + ":" + task.failureReason());
            require(context, hasPassableEnvelopeSide(context, feet),
                    "wet shelter failed while its owned enclosure remained sealed");
            NavSafetyNet.INSTANCE.clear(bot);
            finish(context, bot, "ShelterWaterExitGT");
        });
    }

    private static void verifyOccludedSurfaceNightHold(GameTestHelper context,
                                                       Block overheadBlock,
                                                       int overheadHeight,
                                                       String botName,
                                                       String fixtureName) {
        BlockPos feet = highSurfaceFeet(context);
        preparePlatform(context, feet, 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getLevel().setBlock(
                        feet.offset(dx, overheadHeight, dz),
                        overheadBlock.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = spawn(context, botName, feet);
        context.getLevel().setDayTime(18000L);
        require(context,
                context.getLevel().getBlockState(feet.above(overheadHeight))
                        .is(overheadBlock),
                fixtureName + " did not install its overhead fixture");
        require(context, EmergencyShelterTask.isSurfaceShelterAnchor(bot, feet),
                fixtureName + " was not admitted by the nearby surface heightmap");
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_occluded_surface_night"));
        int[] sealedNightTicks = {0};
        runLocked(context, () -> {
            context.getLevel().setDayTime(18000L);
            if (task.state() != TaskState.RUNNING) {
                context.fail(Component.nullToEmpty(fixtureName + " shelter ended during night HOLD: "
                        + task.state() + ":" + task.failureReason()));
                return;
            }
            if (!task.describe().contains("phase=HOLD")) {
                return;
            }
            sealedNightTicks[0]++;
            require(context, shelterShell(feet).stream().allMatch(pos -> isSealed(context, pos)),
                    fixtureName + " surface shelter opened during night HOLD");
            if (sealedNightTicks[0] >= 140) {
                finish(context, bot, botName);
            }
        });
    }

    private static Husk spawnHusk(GameTestHelper context, BlockPos feet) {
        Husk husk = EntityType.HUSK.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (husk == null) {
            throw new IllegalStateException("failed to create shelter pressure husk");
        }
        husk.setPersistenceRequired();
        husk.setNoAi(true);
        husk.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                0.0F, 0.0F);
        context.getLevel().addFreshEntity(husk);
        return husk;
    }

    private static Husk spawnAiHusk(GameTestHelper context,
                                          BlockPos feet,
                                          AIPlayerEntity target) {
        Husk husk = EntityType.HUSK.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (husk == null) {
            throw new IllegalStateException("failed to create AI shelter pressure husk");
        }
        husk.setPersistenceRequired();
        husk.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                0.0F, 0.0F);
        husk.setTarget(target);
        if (!context.getLevel().addFreshEntity(husk)) {
            throw new IllegalStateException("failed to spawn AI shelter pressure husk");
        }
        return husk;
    }

    private static int describedInt(EmergencyShelterTask task, String key) {
        String marker = key + "=";
        String description = task.describe();
        int start = description.indexOf(marker);
        if (start < 0) {
            return -1;
        }
        start += marker.length();
        int end = description.indexOf(' ', start);
        String value = end < 0 ? description.substring(start) : description.substring(start, end);
        return Integer.parseInt(value);
    }

    private static BlockPos highSurfaceFeet(GameTestHelper context) {
        BlockPos template = context.absolutePos(new BlockPos(4, 4, 4));
        return new BlockPos(template.getX(), 64, template.getZ());
    }

    private static boolean hasPassableEnvelopeSide(GameTestHelper context, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            if (context.getLevel().getBlockState(side)
                    .getCollisionShape(context.getLevel(), side).isEmpty()
                    && context.getLevel().getBlockState(side.above())
                    .getCollisionShape(context.getLevel(), side.above()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        context.getLevel().setDayTime(1000L);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }
}
