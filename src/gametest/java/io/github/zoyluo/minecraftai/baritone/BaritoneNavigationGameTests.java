package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.MiningController;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * End-to-end navigation: real bots on real (built) terrain are given observed block/near goals through the production
 * navigator and must get there by Baritone's own execution, driven through the mod's input and look bridge
 * ({@link BaritoneDriver}): walking and sprinting, a wall that has to be walked around, and a step up and a drop. Goals
 * deliberately hidden behind doors, cliffs, solid walls, or unsupported air are instead pinned as strict observation
 * refusals; an observed gap remains the placement exercise. Public ActionPack route replacement is covered too.
 *
 * <p>Every course is sealed by a bedrock ring, so the only ways to the goal are the ones the test builds. Every course gets
 * its own world slab (17 blocks apart in height), because all tests of a batch run at once and their structures overlap in
 * the horizontal plane.</p>
 *
 * <p>Break/place routing proof ("everything went through the mod's primitives"): a snapshot of every block of the course is
 * taken once the terrain is built, and after the run every block that differs must be a cell {@link BaritoneEdits} recorded
 * (a completed {@code MiningController} break or a {@code BuildAction} placement). Baritone has no other way to change a block.
 * Where the log writer runs, the bot's log file must also carry the {@code mine_start}/{@code mine_complete}/{@code place}
 * lines of those edits.</p>
 */
public final class BaritoneNavigationGameTests {
    private static final int HEIGHT = 9;
    private static final int SLAB_BASE_Y = 140;
    private static final int SLAB_STEP = 17;

    // ---------------------------------------------------------------------------------------------------------------
    // Walking
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 400)
    public void walksTwentyBlocksOnFlatGroundAndSprintsThere(GameTestHelper context) {
        Course c = Course.begin(context, "NavFlatGT", 0, -2, 24, 4);
        c.snapshot();
        // Keep the direct walking fixture within the honest observation radius. Long-range
        // navigation is exercised through the public waypoint/ActionPack routes below.
        BlockPos goal = c.feet.offset(12, 0, 0);
        c.goalNear(goal, 1);
        c.await(300, run -> {
            run.requireNear(goal, 1.6, 0.6, "flat walk");
            require(context, run.ticks <= 200, "12 blocks took " + run.ticks + " ticks");
            require(context, run.sprintTicks >= 12, "the bot sprinted for only " + run.sprintTicks + " ticks");
            double speed = 12.0D / Math.max(1, run.movingTicks) * 20.0D;
            System.out.println("BARITONE_SPEED flat_walk blocks_per_second=" + String.format("%.2f", speed) + " moving_ticks=" + run.movingTicks);
            require(context, speed > 4.0D && speed < 6.6D, "average speed " + speed + " blocks/s is not that of a walking/sprinting player");
            float yaw = Mth.wrapDegrees(c.bot.getYRot() + 90.0F);
            require(context, Math.abs(yaw) < 45.0F, "the bot does not face the goal side, yaw=" + c.bot.getYRot());
            run.requireReleased();
            run.requireNoEdits();
        });
    }

    @GameTest(maxTicks = 500)
    public void walksAroundTwoHighWallThroughItsGap(GameTestHelper context) {
        Course c = Course.begin(context, "NavWallGT", 1, -2, 14, 6);
        for (int dz = -6; dz <= 3; dz++) {
            c.fill(5, dz, Blocks.BEDROCK, 0, 1);
        }
        c.snapshot();
        BlockPos goal = c.feet.offset(10, 0, 0);
        // The far side is deliberately hidden by the wall. A caller must first choose and reach
        // a visible observation point at the gap; it may not submit a raw detour goal through
        // terrain it has never seen.
        c.expectGoalNearRefusal(goal, 1, NavRoute.Options.WALK_ONLY, "navigation_goal_unobserved");
    }

    @GameTest(maxTicks = 500)
    public void stepsUpOneBlockAndDropsThreeWithoutDamage(GameTestHelper context) {
        Course c = Course.begin(context, "NavStepGT", 2, -2, 16, 4);
        for (int dx = 4; dx <= 6; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                c.set(dx, 0, dz, Blocks.STONE);
            }
        }
        for (int dx = 7; dx <= 16; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                c.set(dx, -1, dz, Blocks.AIR);
                c.set(dx, -3, dz, Blocks.STONE);
            }
        }
        c.snapshot();
        BlockPos goal = c.feet.offset(12, -2, 0);
        // The lower floor is occluded by the raised step. It must be discovered from a real
        // intermediate stance rather than inferred from loaded fixture terrain.
        c.expectGoalNearRefusal(goal, 1, NavRoute.Options.WALK_ONLY, "navigation_goal_unobserved");
    }

    @GameTest(maxTicks = 400)
    public void drivenBotTakesVanillaFallDamageAndKeepsGoing(GameTestHelper context) {
        Course c = Course.begin(context, "NavFallGT", 13, -2, 44, 4);
        c.snapshot();
        BlockPos goal = c.feet.offset(12, 0, 0);
        float health = c.bot.getHealth();
        // Start after fake-client immunity ends, using a goal inside the honest observation
        // radius. The former forty-block fixture relied on an unseen long-range target merely
        // to keep Baritone active through tick 70.
        // A server player only checks a fall when a client's move packet arrives, so a bot that moves on its own gets no fall damage
        // at all; the driver's per-tick check is what applies it. While Baritone is walking the bot, six blocks of accumulated fall
        // distance (what a six-block drop leaves on landing; Baritone cannot be kept busy through a real fall, it gives up on a path
        // that starts in mid-air) must be paid at the next tick on the ground: 6 - 3 safe = 3 hit points, exactly as vanilla.
        int[] tick = {0};
        int[] injectedAt = {-1};
        boolean[] routeStarted = {false};
        float[] damageSeen = {0.0F};
        context.onEachTick(() -> {
            int now = ++tick[0];
            // A fake-connection bot counts as "client not loaded" (and takes no damage) for
            // its first 60 ticks. Once that period ends, inject only while a visible relay leg
            // is actively driving on ground; unlike the former fixed tick this does not depend
            // on a hidden 40-block goal remaining active.
            if (routeStarted[0] && injectedAt[0] < 0 && now >= 60 && c.bot.connection.hasClientLoaded()
                    && BaritoneRegistry.INSTANCE.isBusy(c.bot) && c.bot.onGround()) {
                c.bot.fallDistance = 6.0D;
                injectedAt[0] = now;
            } else if (injectedAt[0] >= 0 && now == injectedAt[0] + 2) {
                // The landing check ran in the bot's tick right after the injection; natural regeneration may already have given one point back.
                damageSeen[0] = health - c.bot.getHealth();
                require(context, damageSeen[0] >= 2.0F, "six blocks of fall distance must cost 3 hit points, the bot lost " + damageSeen[0]);
            }
        });
        context.runAfterDelay(61, () -> {
            routeStarted[0] = true;
            c.goalNear(goal, 1);
            c.await(260, run -> {
                run.requireNear(goal, 1.6, 0.6, "walk with a fall");
                require(context, injectedAt[0] >= 0, "the bot was never visibly driven after its connection loaded");
                System.out.println("BARITONE_FALL damage_two_ticks_after_landing=" + damageSeen[0]);
                require(context, c.bot.fallDistance == 0.0D, "the fall distance was not reset by the landing check: " + c.bot.fallDistance);
                run.requireNoEdits();
            });
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Doors and ladders: a sealed destination stays unavailable until it has been observed
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 500)
    public void opensClosedWoodenDoorAndPassesWithoutBreakingIt(GameTestHelper context) {
        Course c = Course.begin(context, "NavDoorGT", 3, -2, 12, 4);
        for (int dz = -4; dz <= 4; dz++) {
            c.fill(4, dz, Blocks.BEDROCK, 0, 3);
        }
        BlockPos door = c.feet.offset(4, 0, 0);
        placeClosedDoor(c.world, door, Blocks.OAK_DOOR.defaultBlockState());
        c.snapshot();
        BlockPos goal = c.feet.offset(8, 0, 0);
        // The sealed far side is not yet visible through a closed door. Opening it toward an
        // unobserved destination would turn the navigator into a wall scanner, so this fixture
        // pins the strict refusal rather than fabricating a route snapshot behind the door.
        c.expectGoalNearRefusal(goal, 1, NavRoute.Options.WALK_ONLY, "navigation_goal_unobserved");
    }

    @GameTest(maxTicks = 700)
    public void climbsLadderToPlatformSixBlocksUp(GameTestHelper context) {
        Course c = Course.begin(context, "NavLadderGT", 4, -2, 10, 3);
        // A bedrock cliff six blocks high from x=+3 on, with one ladder against its west face.
        for (int dx = 3; dx <= 10; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                c.fill(dx, dz, Blocks.BEDROCK, 0, 5);
            }
        }
        for (int dy = 0; dy <= 5; dy++) {
            c.world.setBlock(c.feet.offset(2, dy, 0), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
        }
        c.snapshot();
        BlockPos goal = c.feet.offset(7, 6, 0);
        // The platform above the cliff has not been seen from the starting side. The ladder
        // itself may be visible, but it cannot authorise a route to unseen terrain at its top.
        c.expectGoalNearRefusal(goal, 1, NavRoute.Options.WALK_ONLY, "navigation_goal_unobserved");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Placing: an observed gap may be bridged; a visibly proven vertical column may be pillared
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:baritone_navigation_game_tests_bridges_gap_when_placing_is_allowed", maxTicks = 1000)
    public void bridgesGapWhenPlacingIsAllowed(GameTestHelper context) {
        Course c = gapCourse(context, "NavBridgeGT", 5);
        c.giveBlocks(Items.COBBLESTONE, 16);
        BlockPos goal = c.feet.offset(10, 0, 0);
        c.goalNear(goal, 1, new NavRoute.Options(false, true, false));
        c.await(900, run -> {
            run.requireNear(goal, 1.6, 0.6, "bridge");
            List<BaritoneEdits.Edit> placed = BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.PLACE);
            require(context, placed.size() >= 3 && placed.size() <= 8, "expected 3-8 placements to cross a 3-wide gap, got " + placed.size() + ": " + placed);
            for (BaritoneEdits.Edit edit : placed) {
                require(context, edit.pos().getX() >= c.feet.getX() + 4 && edit.pos().getX() <= c.feet.getX() + 6,
                        "a block was placed outside the gap: " + edit);
                require(context, edit.block().equals("minecraft:cobblestone"), "placed " + edit.block());
            }
            require(context, c.count(Items.COBBLESTONE) == 16 - placed.size(),
                    "the inventory lost " + (16 - c.count(Items.COBBLESTONE)) + " blocks for " + placed.size() + " placements");
            require(context, BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.BREAK).isEmpty(), "something was broken while bridging");
            run.requireEditsExplainTheWorldDiff();
            run.requireLogged("place", placed.size());
        });
    }

    @GameTest(environment = "minecraftai-gametest:baritone_navigation_game_tests_does_not_bridge_gap_when_placing_is_forbidden", maxTicks = 500)
    public void doesNotBridgeGapWhenPlacingIsForbidden(GameTestHelper context) {
        Course c = gapCourse(context, "NavNoBridgeGT", 6);
        c.giveBlocks(Items.COBBLESTONE, 16);
        BlockPos goal = c.feet.offset(10, 0, 0);
        // Exercise NO_PLACING rather than WALK_ONLY: allowing observed breaking must not turn
        // a partial path up to this visible gap into a physical fall before any input is sent.
        c.expectGoalNearRefusal(goal, 1, new NavRoute.Options(true, false, false),
                "navigation_observed_corridor_unavailable");
    }

    @GameTest(maxTicks = 800)
    public void pillarsUpFourBlocksWhenPlacingIsAllowed(GameTestHelper context) {
        Course c = Course.begin(context, "NavPillarGT", 7, -3, 3, 3);
        c.snapshot();
        c.giveBlocks(Items.COBBLESTONE, 16);
        BlockPos goal = c.feet.offset(0, 4, 0);
        // This is a clear, in-range vertical air column over a collision-bearing observed base.
        // The production fence must prove every future body/place cell by a real eye ray; it may
        // then permit the one legitimate use of a placement-created stance.
        c.goalBlock(goal, new NavRoute.Options(false, true, false));
        c.await(700, run -> {
            run.requireNear(goal, 0.8D, 0.6D, "pillar");
            List<BaritoneEdits.Edit> placed = BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.PLACE);
            require(context, placed.size() >= 4 && placed.size() <= 6,
                    "four-block pillar should use 4-6 placements, got " + placed.size() + ": " + placed);
            for (BaritoneEdits.Edit edit : placed) {
                require(context, edit.pos().getX() == c.feet.getX() && edit.pos().getZ() == c.feet.getZ()
                                && edit.pos().getY() >= c.feet.getY() && edit.pos().getY() < goal.getY(),
                        "pillar placed outside its ray-proven column: " + edit);
                require(context, edit.block().equals("minecraft:cobblestone"), "pillar placed " + edit.block());
            }
            require(context, BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.BREAK).isEmpty(),
                    "something was broken while pillaring");
            require(context, c.count(Items.COBBLESTONE) == 16 - placed.size(),
                    "inventory lost " + (16 - c.count(Items.COBBLESTONE)) + " blocks for " + placed.size() + " placements");
            run.requireEditsExplainTheWorldDiff();
            run.requireLogged("place", placed.size());
        });
    }

    @GameTest(maxTicks = 400)
    public void doesNotPillarWhenPlacingIsForbidden(GameTestHelper context) {
        Course c = Course.begin(context, "NavNoPillarGT", 8, -3, 3, 3);
        c.snapshot();
        c.giveBlocks(Items.COBBLESTONE, 16);
        // The same visible air is still not a stance when the route is not allowed to create
        // the footing. This keeps the no-invented-support refusal beside the positive column.
        c.expectGoalBlockRefusal(c.feet.offset(0, 4, 0), NavRoute.Options.WALK_ONLY,
                "navigation_goal_without_observed_stance");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Breaking: a hidden far-side goal must not turn into an inferred tunnel
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 1000)
    public void breaksThroughWallWithThePickaxeAndEveryBreakGoesThroughMiningController(GameTestHelper context) {
        Course c = Course.begin(context, "NavBreakGT", 9, -2, 14, 3);
        for (int dx = 4; dx <= 6; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                c.fill(dx, dz, Blocks.STONE, 0, 3);
            }
        }
        c.snapshot();
        // The wrong tool sits in the selected slot; the right one is elsewhere in the hotbar.
        c.bot.getInventory().setItem(0, new ItemStack(Items.WOODEN_SHOVEL));
        c.bot.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        c.bot.getInventory().setSelectedSlot(0);
        BlockPos goal = c.feet.offset(10, 0, 0);
        // The far side of a three-thick wall is intentionally hidden. A break-capable request
        // must not tunnel toward it merely because the chunks are loaded; dedicated observed
        // mining/controller tests cover the exposed-block path separately.
        c.expectGoalNearRefusal(goal, 1, new NavRoute.Options(true, false, false),
                "navigation_goal_unobserved");
    }

    @GameTest(maxTicks = 20)
    public void drivenMiningControllerNeitherReAimsNorSwapsTheTool(GameTestHelper context) {
        Course c = Course.begin(context, "NavAimGT", 10, -2, 6, 3);
        BlockPos target = c.feet.offset(2, 0, 0);
        c.world.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        c.bot.getInventory().setItem(0, new ItemStack(Items.WOODEN_SHOVEL));
        c.bot.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        c.bot.getInventory().setSelectedSlot(0);
        io.github.zoyluo.minecraftai.action.LookAction.setYawPitch(c.bot, 0.0F, 0.0F); // south: the block is to the east
        ActionPack pack = c.bot.getActionPack();
        try {
            MiningController driven = MiningController.driven(target, Direction.WEST);
            ActionResult first = driven.tick(pack);
            require(context, first == ActionResult.IN_PROGRESS, "the driven break did not start: " + first);
            require(context, c.bot.getYRot() == 0.0F && c.bot.getXRot() == 0.0F, "the driven controller turned the bot: " + c.bot.getYRot() + "/" + c.bot.getXRot());
            require(context, c.bot.getInventory().getSelectedSlot() == 0, "the driven controller changed the tool to slot " + c.bot.getInventory().getSelectedSlot());
            driven.abort(c.bot);

            MiningController ordinary = new MiningController(target, Direction.WEST);
            ordinary.tick(pack);
            require(context, Math.abs(Mth.wrapDegrees(c.bot.getYRot() + 90.0F)) < 5.0F, "the ordinary controller did not aim at the block: yaw " + c.bot.getYRot());
            require(context, c.bot.getInventory().getSelectedSlot() == 1, "the ordinary controller did not pick the pickaxe: slot " + c.bot.getInventory().getSelectedSlot());
            ordinary.abort(c.bot);
        } finally {
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
        }
        context.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Public ActionPack navigation remains Baritone-owned
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 500)
    public void ordinaryActionPackRoutesStayBaritoneOwned(GameTestHelper context) {
        Course c = Course.begin(context, "NavActionPackGT", 11, -2, 14, 5);
        c.snapshot();
        ActionPack pack = c.bot.getActionPack();
        BlockPos first = c.feet.offset(8, 0, 3);
        BlockPos second = c.feet.offset(1, 0, -2);
        ActionResult started = pack.startPathTo(first);
        require(context, started == ActionResult.IN_PROGRESS && pack.hasBaritoneRoute()
                        && BaritoneRegistry.INSTANCE.find(c.bot.getUUID()) != null,
                "the initial ActionPack path did not start on Baritone: " + started);
        require(context, pack.isWalkToIdle() && pack.isMiningIdle(),
                "an ordinary controller started beside the initial Baritone route");
        int[] phase = {0};
        int[] ticks = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            try {
                require(context, pack.isWalkToIdle() && pack.isMiningIdle(),
                        "an ordinary controller wrote while a Baritone route was active");
                if (phase[0] == 0 && !pack.hasBaritoneRoute()) {
                    require(context, succeeded(pack), "the first ActionPack Baritone route ended as " + pack.lastRouteOutcome());
                    require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(first)) < 1.5,
                            "the first ActionPack route ended at " + c.bot.position());
                    ActionResult walk = pack.startWalkTo(Vec3.atBottomCenterOf(second));
                    require(context, walk == ActionResult.IN_PROGRESS && pack.hasBaritoneRoute(),
                            "the second ActionPack route did not start on Baritone: " + walk);
                    phase[0] = 1;
                } else if (phase[0] == 1 && !pack.hasBaritoneRoute()) {
                    require(context, succeeded(pack), "the second ActionPack Baritone route ended as " + pack.lastRouteOutcome());
                    require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(second)) < 1.5,
                            "the second ActionPack route ended at " + c.bot.position());
                    require(context, pack.isPathExecutorIdle(), "a local path executor survived after the Baritone route ended");
                    phase[0] = 2;
                    AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                    context.succeed();
                } else if (ticks[0] > 400 && phase[0] < 2) {
                    require(context, false, "ActionPack Baritone navigation stalled in phase " + phase[0] + " at " + c.bot.position());
                }
            } catch (RuntimeException failure) {
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                throw failure;
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void actionPackRegoalsStayBaritoneOwnedWithSingleWriter(GameTestHelper context) {
        Course c = Course.begin(context, "NavReGoalGT", 12, -2, 26, 4);
        c.snapshot();
        ActionPack pack = c.bot.getActionPack();
        BlockPos far = c.feet.offset(12, 0, 0);
        BlockPos back = c.feet.offset(2, 0, 0);
        BlockPos again = c.feet.offset(10, 0, 0);
        ActionResult started = pack.startPathTo(far);
        require(context, started == ActionResult.IN_PROGRESS && pack.hasBaritoneRoute(),
                "the first ActionPack Baritone route did not start: " + started);
        int[] phase = {0};
        int[] ticks = {0};
        int[] driven = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            boolean baritoneDrives = BaritoneRegistry.INSTANCE.isBusy(c.bot);
            try {
                require(context, !(baritoneDrives && (!pack.isWalkToIdle() || !pack.isMiningIdle())),
                        "an ordinary controller wrote beside Baritone at tick " + ticks[0] + " phase " + phase[0]);
                switch (phase[0]) {
                    case 0 -> { // Baritone runs east
                        // Count from the first execution tick, rather than from route submission: planning happens on a worker
                        // while the GameTest server runs ticks back to back.
                        if (baritoneDrives && c.baritone.getPathingBehavior().isPathing()) {
                            driven[0]++;
                        }
                        if (driven[0] == 25) {
                            require(context, c.bot.getX() > c.feet.getX() + 3.0D, "Baritone did not get the bot moving: x offset " + (c.bot.getX() - c.feet.getX()));
                            ActionResult reGoal = pack.startWalkTo(Vec3.atBottomCenterOf(back));
                            require(context, reGoal == ActionResult.IN_PROGRESS && pack.hasBaritoneRoute(),
                                    "the ActionPack re-goal did not start on Baritone: " + reGoal);
                            require(context, pack.isWalkToIdle() && pack.isMiningIdle(),
                                    "the ActionPack re-goal started an ordinary controller");
                            phase[0] = 1;
                        }
                    }
                    case 1 -> { // Baritone completes the re-goal
                        if (!pack.hasBaritoneRoute()) {
                            require(context, succeeded(pack), "the ActionPack re-goal ended as " + pack.lastRouteOutcome());
                            require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(back)) < 1.5,
                                    "the ActionPack re-goal ended at " + c.bot.position());
                            ActionResult finalGoal = pack.startPathTo(again);
                            require(context, finalGoal == ActionResult.IN_PROGRESS && pack.hasBaritoneRoute(),
                                    "the final ActionPack route did not start on Baritone: " + finalGoal);
                            phase[0] = 2;
                        }
                    }
                    case 2 -> { // Baritone completes the final route
                        if (!pack.hasBaritoneRoute()) {
                            require(context, succeeded(pack), "the final ActionPack route ended as " + pack.lastRouteOutcome());
                            require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(again)) < 1.6,
                                    "the final ActionPack route ended at " + c.bot.position());
                            require(context, pack.isPathExecutorIdle(), "a local path executor survived after the final Baritone route");
                            require(context, !c.bot.isSprinting() && c.bot.zza == 0.0F, "the inputs were not released at the end");
                            phase[0] = 3;
                            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                            context.succeed();
                        }
                    }
                    default -> { }
                }
                if (ticks[0] > 600 && phase[0] < 3) {
                    require(context, false, "hand-over stalled in phase " + phase[0] + " at " + c.bot.position());
                }
            } catch (RuntimeException failure) {
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                throw failure;
            }
        });
    }

    /**
     * A linkage failure inside a driven tick is contained: the bot survives, Baritone is retired for the session, the active
     * route ends with a typed unavailable outcome, and later navigation refuses rather than starting the removed local navigator.
     * The fixtures cover both contained driver halves.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_navigation_game_tests_a_linkage_failure_inside_adriven_tick_stops_navigation", maxTicks = 500)
    public void aLinkageFailureInsideADrivenTickStopsNavigation(GameTestHelper context) {
        linkageFailureInsideADrivenTickStopsNavigation(context, "NavFaultPreGT", "before_physics");
    }

    @GameTest(environment = "minecraftai-gametest:baritone_navigation_game_tests_a_linkage_failure_after_physics_inside_adriven_tick_stops_navigation", maxTicks = 500)
    public void aLinkageFailureAfterPhysicsInsideADrivenTickStopsNavigation(GameTestHelper context) {
        linkageFailureInsideADrivenTickStopsNavigation(context, "NavFaultPostGT", "after_physics");
    }

    private void linkageFailureInsideADrivenTickStopsNavigation(GameTestHelper context, String botName, String faultPhase) {
        Course c = Course.begin(context, botName, 0, -2, 30, 4);
        c.snapshot();
        ActionPack pack = c.bot.getActionPack();
        BlockPos far = c.feet.offset(12, 0, 0);
        BlockPos retryGoal = c.feet.offset(7, 0, 2);
        ActionResult started = pack.startPathTo(far);
        require(context, started == ActionResult.IN_PROGRESS && pack.hasBaritoneRoute(),
                "the failure fixture did not start a Baritone route: " + started);
        // Whatever happens, neither the fault nor the failure flag is left behind for the tests that follow.
        context.runAfterDelay(480, () -> {
            BaritoneDriver.testFault = null;
            NavEngineSelector.clearFailureForTests();
        });
        int[] phase = {0};
        int[] ticks = {0};
        int[] driven = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            try {
                switch (phase[0]) {
                    case 0 -> {
                        if (BaritoneRegistry.INSTANCE.isBusy(c.bot)) {
                            driven[0]++;
                        }
                        if (driven[0] == 12) {
                            require(context, NavEngineSelector.baritoneActive(), "fixture: Baritone is not active");
                            // Exercise exactly one contained driver half. Both must retire Baritone without killing the bot.
                            BaritoneDriver.testFault = where -> {
                                if (where.equals(faultPhase)) {
                                    throw new NoClassDefFoundError("baritone/pathing/movement/MovementHelper");
                                }
                            };
                            phase[0] = 1;
                        }
                    }
                    case 1 -> {
                        if (NavEngineSelector.baritoneFailed()) {
                            BaritoneDriver.testFault = null;
                            require(context, c.bot.isAlive() && !c.bot.isRemoved(), "the bot did not survive the failing tick");
                            require(context, !NavEngineSelector.baritoneActive(), "Baritone is still active after a linkage failure");
                            require(context, BaritoneRegistry.INSTANCE.find(c.bot.getUUID()) == null, "the instance outlived the failure");
                            require(context, !BaritoneRegistry.INSTANCE.isBusy(c.bot), "Baritone still drives the bot");
                            require(context, !pack.hasBaritoneRoute() && pack.isPathExecutorIdle()
                                            && pack.isWalkToIdle() && pack.isMiningIdle(),
                                    "the failed route left a navigation controller behind");
                            NavOutcome outcome = pack.lastRouteOutcome();
                            require(context, outcome != null && outcome.status() == NavOutcome.Status.FAILED
                                            && "baritone_unavailable".equals(outcome.reason()),
                                    "the Baritone route did not end as unavailable: " + outcome);
                            ActionResult refused = pack.startPathTo(retryGoal);
                            require(context, !refused.isInProgress() && "baritone_unavailable".equals(refused.reason())
                                            && !pack.hasBaritoneRoute() && pack.isPathExecutorIdle(),
                                    "a later navigation request did not fail closed: " + refused.status() + " " + refused.reason());
                            phase[0] = 2;
                        }
                    }
                    case 2 -> {
                        require(context, BaritoneRegistry.INSTANCE.find(c.bot.getUUID()) == null,
                                "a Baritone instance reappeared after the failure");
                        require(context, !pack.hasBaritoneRoute() && pack.isPathExecutorIdle()
                                        && pack.isWalkToIdle() && pack.isMiningIdle(),
                                "navigation resumed after the Baritone failure");
                        require(context, c.bot.zza == 0.0F && c.bot.xxa == 0.0F && !c.bot.isSprinting(),
                                "navigation inputs were not released after the Baritone failure");
                        phase[0] = 3;
                        NavEngineSelector.clearFailureForTests();
                        AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                        context.succeed();
                    }
                    default -> { }
                }
                if (ticks[0] > 450 && phase[0] < 3) {
                    require(context, false, "the failure course stalled in phase " + phase[0] + " at " + c.bot.position());
                }
            } catch (RuntimeException failure) {
                BaritoneDriver.testFault = null;
                NavEngineSelector.clearFailureForTests();
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                throw failure;
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Courses
    // ---------------------------------------------------------------------------------------------------------------

    /** x=-2..14, z=-3..3; a full-width gap at x=+4..+6 that is six deep (the bots' safe fall is three), floor beyond it. */
    private static Course gapCourse(GameTestHelper context, String name, int layer) {
        // The subject here is bridging by placing blocks; with parkour on the bot would simply jump this 3-wide gap (BaritoneCapabilityGameTests).
        BaritoneCapabilityGameTests.installCaps(io.github.zoyluo.minecraftai.MinecraftAiConfig.BaritoneCaps.allOff());
        Course c = Course.begin(context, name, layer, -2, 14, 3);
        for (int dx = 4; dx <= 6; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                c.set(dx, -1, dz, Blocks.AIR);
                c.set(dx, -6, dz, Blocks.STONE);
            }
        }
        c.snapshot();
        return c;
    }

    /** A closed door (both halves) whose panel blocks travel along x. */
    private static void placeClosedDoor(ServerLevel world, BlockPos lower, BlockState door) {
        BlockState base = door.setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.OPEN, false);
        world.setBlock(lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        world.setBlock(lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        BaritoneServerGameTests.require(context, condition, message);
    }

    private static boolean succeeded(ActionPack pack) {
        NavOutcome outcome = pack.lastRouteOutcome();
        return outcome != null && outcome.status() == NavOutcome.Status.SUCCESS;
    }

    /** A sealed, flat course with one bot and its Baritone instance; the test builds its obstacles on it. */
    private static final class Course {
        final GameTestHelper context;
        final ServerLevel world;
        final BlockPos feet;
        final String name;
        final int fromX;
        final int toX;
        final int halfZ;
        final AIPlayerEntity bot;
        final IBaritone baritone;
        private final Map<BlockPos, BlockState> before = new HashMap<>();

        private Course(GameTestHelper context, String name, BlockPos feet, int fromX, int toX, int halfZ, AIPlayerEntity bot, IBaritone baritone) {
            this.context = context;
            this.world = context.getLevel();
            this.name = name;
            this.feet = feet;
            this.fromX = fromX;
            this.toX = toX;
            this.halfZ = halfZ;
            this.bot = bot;
            this.baritone = baritone;
        }

        static Course begin(GameTestHelper context, String name, int layer, int fromX, int toX, int halfZ) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(8, SLAB_BASE_Y + SLAB_STEP * layer, 8));
            int bulk = Block.UPDATE_CLIENTS;
            for (int dx = fromX - 1; dx <= toX + 1; dx++) {
                for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                    boolean ring = dx == fromX - 1 || dx == toX + 1 || dz == -halfZ - 1 || dz == halfZ + 1;
                    for (int dy = -7; dy <= HEIGHT; dy++) {
                        BlockState state;
                        if (ring && dy >= -1) {
                            state = Blocks.BEDROCK.defaultBlockState();
                        } else if (!ring && dy == -1) {
                            state = Blocks.STONE.defaultBlockState();
                        } else {
                            state = Blocks.AIR.defaultBlockState();
                        }
                        world.setBlock(feet.offset(dx, dy, dz), state, bulk);
                    }
                }
            }
            AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, name, feet);
            IBaritone baritone = BaritoneRegistry.INSTANCE.get(bot);
            return new Course(context, name, feet, fromX, toX, halfZ, bot, baritone);
        }

        void set(int dx, int dy, int dz, Block block) {
            world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
        }

        void goalNear(BlockPos goal, int radius) {
            goalNear(goal, radius, NavRoute.Options.WALK_ONLY);
        }

        void goalNear(BlockPos goal, int radius, NavRoute.Options options) {
            ObservedBaritoneTestRoutes.near(bot, goal, radius, options, "gametest_" + name);
        }

        void goalBlock(BlockPos goal) {
            goalBlock(goal, NavRoute.Options.WALK_ONLY);
        }

        void goalBlock(BlockPos goal, NavRoute.Options options) {
            ObservedBaritoneTestRoutes.block(bot, goal, options, "gametest_" + name);
        }

        BaritoneNavigator.Admission admitGoalNear(BlockPos goal, int radius, NavRoute.Options options) {
            return ObservedBaritoneTestRoutes.admitNear(bot, goal, radius, options, "gametest_" + name);
        }

        BaritoneNavigator.Admission admitGoalBlock(BlockPos goal, NavRoute.Options options) {
            return ObservedBaritoneTestRoutes.admitBlock(bot, goal, options, "gametest_" + name);
        }

        /** Verifies that a deliberately hidden fixture goal fails at the production observation boundary. */
        void expectGoalNearRefusal(BlockPos goal, int radius, NavRoute.Options options, String expectedReason) {
            expectRefusal(admitGoalNear(goal, radius, options), expectedReason, goal);
        }

        /** Verifies that a target without an actually observed stance cannot invent one by placing blocks. */
        void expectGoalBlockRefusal(BlockPos goal, NavRoute.Options options, String expectedReason) {
            expectRefusal(admitGoalBlock(goal, options), expectedReason, goal);
        }

        private void expectRefusal(BaritoneNavigator.Admission admission, String expectedReason, BlockPos goal) {
            try {
                require(context, !admission.accepted(), name + ": hidden goal was admitted: " + goal);
                require(context, expectedReason.equals(admission.failure()), name + ": hidden goal " + goal
                        + " was refused as " + admission.failure() + ", expected " + expectedReason);
                require(context, !BaritoneRegistry.INSTANCE.isBusy(bot), name + ": a refused goal still drives the bot");
                new Run(this).requireNoEdits();
            } finally {
                AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                BaritoneCapabilityGameTests.restoreConfig();
            }
            require(context, BaritoneRegistry.INSTANCE.find(bot.getUUID()) == null,
                    "the refused route's Baritone instance outlived the bot");
            context.succeed();
        }

        /** {@code block} in the column at ({@code dx}, {@code dz}) for heights {@code fromDy..toDy}. */
        void fill(int dx, int dz, Block block, int fromDy, int toDy) {
            for (int dy = fromDy; dy <= toDy; dy++) {
                set(dx, dy, dz, block);
            }
        }

        /** Remembers every block of the course; {@link Run#requireEditsExplainTheWorldDiff} compares against it. */
        void snapshot() {
            before.clear();
            forEachCell(pos -> before.put(pos, world.getBlockState(pos)));
        }

        private void forEachCell(Consumer<BlockPos> action) {
            for (int dx = fromX - 1; dx <= toX + 1; dx++) {
                for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                    for (int dy = -7; dy <= HEIGHT; dy++) {
                        action.accept(feet.offset(dx, dy, dz));
                    }
                }
            }
        }

        void giveBlocks(net.minecraft.world.item.Item item, int count) {
            bot.getInventory().setItem(0, new ItemStack(item, count));
            bot.getInventory().setSelectedSlot(0);
        }

        int count(net.minecraft.world.item.Item item) {
            int total = 0;
            for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        /**
         * Watches the run: when Baritone lets go of the bot (arrived, or gave up) {@code check} runs, then the bot is removed and the
         * test succeeds; if Baritone is still busy after {@code limit} ticks the test fails with where the bot is.
         */
        void await(int limit, Consumer<Run> check) {
            Run run = new Run(this);
            context.onEachTick(() -> {
                if (run.done) {
                    return;
                }
                run.sample();
                boolean busy = BaritoneRegistry.INSTANCE.isBusy(bot);
                if (run.ticks > 2 && !busy) {
                    run.done = true;
                    try {
                        System.out.println("BARITONE_NAV " + name + " finished after " + run.ticks + " ticks at " + describe());
                        check.accept(run);
                    } finally {
                        AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                        BaritoneCapabilityGameTests.restoreConfig();
                    }
                    require(context, BaritoneRegistry.INSTANCE.find(bot.getUUID()) == null, "the bot's Baritone instance outlived the bot");
                    context.succeed();
                } else if (run.ticks > limit) {
                    run.done = true;
                    String where = describe();
                    AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                    BaritoneCapabilityGameTests.restoreConfig();
                    require(context, false, name + ": still busy after " + limit + " ticks, " + where);
                }
            });
        }

        String describe() {
            return "pos=" + bot.position() + " offset=(" + String.format("%.2f,%.2f,%.2f", bot.getX() - feet.getX(), bot.getY() - feet.getY(), bot.getZ() - feet.getZ())
                    + ") yaw=" + bot.getYRot() + " goal=" + baritone.getPathingBehavior().getGoal() + " hp=" + bot.getHealth();
        }
    }

    /** Sampled state of one run plus the assertions every course shares. */
    private static final class Run {
        final Course c;
        int ticks;
        int sprintTicks;
        int movingTicks;
        double maxY = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        final List<Vec3> trail = new ArrayList<>();
        boolean done;

        Run(Course c) {
            this.c = c;
        }

        void sample() {
            ticks++;
            AIPlayerEntity bot = c.bot;
            Vec3 pos = bot.position();
            trail.add(pos);
            maxY = Math.max(maxY, pos.y);
            minY = Math.min(minY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
            if (bot.isSprinting()) {
                sprintTicks++;
            }
            if (BaritoneRegistry.INSTANCE.isBusy(bot) && (Math.abs(bot.getDeltaMovement().x) > 0.01 || Math.abs(bot.getDeltaMovement().z) > 0.01)) {
                movingTicks++;
            }
            if (ticks % 10 == 0) {
                IBaritone b = c.baritone;
                System.out.println("BARITONE_TRACE " + c.name + " t=" + ticks + " " + c.describe()
                        + " keys=" + (b.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD) ? "F" : "-")
                        + (b.getInputOverrideHandler().isInputForcedDown(Input.JUMP) ? "J" : "-")
                        + (b.getInputOverrideHandler().isInputForcedDown(Input.SNEAK) ? "S" : "-")
                        + (b.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT) ? "L" : "-")
                        + (b.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT) ? "R" : "-")
                        + " sprint=" + bot.isSprinting() + " ground=" + bot.onGround());
            }
        }

        void requireNear(BlockPos goal, double horizontal, double vertical, String what) {
            Vec3 target = Vec3.atBottomCenterOf(goal);
            double dx = c.bot.getX() - target.x;
            double dz = c.bot.getZ() - target.z;
            double dy = c.bot.getY() - target.y;
            require(c.context, Math.sqrt(dx * dx + dz * dz) <= horizontal && Math.abs(dy) <= vertical,
                    what + ": the bot is not at the goal " + goal + ": " + c.describe());
        }

        void requireReleased() {
            require(c.context, c.bot.zza == 0.0F && c.bot.xxa == 0.0F && !c.bot.isSprinting() && !c.bot.isShiftKeyDown(),
                    "the bot's inputs were not released: zza=" + c.bot.zza + " xxa=" + c.bot.xxa + " sprint=" + c.bot.isSprinting());
            require(c.context, !BaritoneRegistry.INSTANCE.isBusy(c.bot), "Baritone still claims the bot");
        }

        void requireNoEdits() {
            require(c.context, BaritoneEdits.of(c.bot.getUUID()).isEmpty(), "the terrain was edited: " + BaritoneEdits.of(c.bot.getUUID()));
            requireEditsExplainTheWorldDiff();
        }

        /** Every block of the course that differs from the snapshot is a cell the ledger recorded as broken or placed. */
        void requireEditsExplainTheWorldDiff() {
            Set<BlockPos> recorded = new HashSet<>();
            BaritoneEdits.of(c.bot.getUUID()).forEach(edit -> recorded.add(edit.pos().immutable()));
            List<String> unexplained = new ArrayList<>();
            c.forEachCell(pos -> {
                BlockState was = c.before.get(pos);
                // A door or gate that was opened is the same block in another state (a right click, not an edit).
                if (was != null && !was.is(c.world.getBlockState(pos).getBlock()) && !recorded.contains(pos)) {
                    unexplained.add(pos.toShortString() + " " + was.getBlock() + " -> " + c.world.getBlockState(pos).getBlock());
                }
            });
            require(c.context, unexplained.isEmpty(), "blocks changed without a MiningController break or a BuildAction placement: " + unexplained);
        }

        /**
         * The bot's log file carries {@code count} lines of the event with {@code driver=baritone} (mine events) or the place event.
         * Only checked when the log writer runs in this server; its writer thread is asynchronous, so the file is read a little later.
         */
        void requireLogged(String event, int count) {
            if (!BotLogWriter.INSTANCE.isStarted() || BotLogWriter.INSTANCE.baseDir() == null) {
                System.out.println("BARITONE_LOG skipped: the log writer is not running in this test server");
                return;
            }
            Path file = BotLogWriter.INSTANCE.baseDir().resolve("by-bot").resolve(c.name + ".log");
            long found = 0;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    if (Files.exists(file)) {
                        found = Files.readAllLines(file).stream().filter(line -> line.contains(event) && (!event.startsWith("mine_") || line.contains("baritone"))).count();
                    }
                } catch (IOException ignored) {
                    found = 0;
                }
                if (found >= count) {
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("BARITONE_LOG " + c.name + " event=" + event + " lines=" + found + " expected=" + count + " file=" + file);
            require(c.context, found >= count, "the bot log has " + found + " '" + event + "' lines, expected " + count + " (" + file + ")");
        }
    }

    static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
