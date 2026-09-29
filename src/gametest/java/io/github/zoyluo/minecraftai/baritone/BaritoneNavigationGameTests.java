package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.input.Input;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.MiningController;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
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
 * End-to-end navigation: real bots on real (built) terrain are given a {@code GoalNear}/{@code GoalBlock} through Baritone's
 * {@code CustomGoalProcess} and must get there by Baritone's own execution, driven through the mod's input and look bridge
 * ({@link BaritoneDriver}): walking and sprinting, a wall that has to be walked around, a step up and a drop, a closed wooden
 * door, a ladder, a gap that has to be bridged and a column that has to be pillared (only when placing is allowed), a wall
 * that has to be broken through with the right tool, and the hand-over with the legacy action executor.
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
        BlockPos goal = c.feet.offset(20, 0, 0);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
        c.await(300, run -> {
            run.requireNear(goal, 1.6, 0.6, "flat walk");
            require(context, run.ticks <= 200, "20 blocks took " + run.ticks + " ticks");
            require(context, run.sprintTicks >= 20, "the bot sprinted for only " + run.sprintTicks + " ticks");
            double speed = 20.0D / Math.max(1, run.movingTicks) * 20.0D;
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
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
        c.await(400, run -> {
            run.requireNear(goal, 1.6, 0.6, "wall detour");
            require(context, run.maxZ >= c.feet.getZ() + 3.5, "the bot never used the gap (max z offset " + (run.maxZ - c.feet.getZ()) + ")");
            require(context, run.trail.stream().noneMatch(p -> Math.abs(p.x - (c.feet.getX() + 5.5)) < 0.8 && p.z < c.feet.getZ() + 3.0),
                    "the bot went through the wall");
            run.requireNoEdits();
        });
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
        float health = c.bot.getHealth();
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
        c.await(400, run -> {
            run.requireNear(goal, 1.6, 0.8, "step and drop");
            require(context, run.maxY >= c.feet.getY() + 0.95, "the bot never stood on the step (max y offset " + (run.maxY - c.feet.getY()) + ")");
            require(context, run.minY <= c.feet.getY() - 1.9, "the bot never reached the lower floor");
            require(context, c.bot.getHealth() >= health, "the three-block drop cost health: " + health + " -> " + c.bot.getHealth());
            run.requireNoEdits();
        });
    }

    @GameTest(maxTicks = 400)
    public void drivenBotTakesVanillaFallDamageAndKeepsGoing(GameTestHelper context) {
        Course c = Course.begin(context, "NavFallGT", 13, -2, 44, 4);
        c.snapshot();
        BlockPos goal = c.feet.offset(40, 0, 0);
        float health = c.bot.getHealth();
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
        // A server player only checks a fall when a client's move packet arrives, so a bot that moves on its own gets no fall damage
        // at all; the driver's per-tick check is what applies it. While Baritone is walking the bot, six blocks of accumulated fall
        // distance (what a six-block drop leaves on landing; Baritone cannot be kept busy through a real fall, it gives up on a path
        // that starts in mid-air) must be paid at the next tick on the ground: 6 - 3 safe = 3 hit points, exactly as vanilla.
        int[] tick = {0};
        float[] damageSeen = {0.0F};
        context.onEachTick(() -> {
            if (++tick[0] == 70) {
                // A fake-connection bot counts as "client not loaded" (and takes no damage) for its first 60 ticks; wait that out.
                require(context, c.bot.connection.hasClientLoaded(), "the bot is still protected as a not-yet-loaded client");
                require(context, BaritoneRegistry.INSTANCE.isBusy(c.bot) && c.bot.onGround(), "the bot is not being driven on the ground at tick 70");
                c.bot.fallDistance = 6.0D;
            } else if (tick[0] == 72) {
                // The landing check ran in the bot's tick right after the injection; natural regeneration may already have given one point back.
                damageSeen[0] = health - c.bot.getHealth();
                require(context, damageSeen[0] >= 2.0F, "six blocks of fall distance must cost 3 hit points, the bot lost " + damageSeen[0]);
            }
        });
        c.await(300, run -> {
            run.requireNear(goal, 1.6, 0.6, "walk with a fall");
            System.out.println("BARITONE_FALL damage_two_ticks_after_landing=" + damageSeen[0]);
            require(context, c.bot.fallDistance == 0.0D, "the fall distance was not reset by the landing check: " + c.bot.fallDistance);
            run.requireNoEdits();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Doors and ladders (no placing, no breaking: Baritone may only walk, open and climb)
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
        BaritoneRegistry.INSTANCE.setPolicy(c.bot, BaritonePolicy.WALK_ONLY);
        BlockPos goal = c.feet.offset(8, 0, 0);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
        c.await(400, run -> {
            run.requireNear(goal, 1.6, 0.6, "door");
            BlockState after = c.world.getBlockState(door);
            require(context, after.getBlock() instanceof DoorBlock, "the door is gone: " + after);
            require(context, after.getValue(DoorBlock.OPEN), "the door was not opened by the bot: " + after);
            require(context, run.trail.stream().anyMatch(p -> Math.abs(p.x - (door.getX() + 0.5)) < 0.5 && Math.abs(p.z - (door.getZ() + 0.5)) < 0.5),
                    "the bot never stood in the doorway");
            run.requireNoEdits(); // not placing, not breaking: the door was opened, that is all
        });
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
        BaritoneRegistry.INSTANCE.setPolicy(c.bot, BaritonePolicy.WALK_ONLY);
        BlockPos goal = c.feet.offset(7, 6, 0);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
        c.await(600, run -> {
            run.requireNear(goal, 1.6, 0.6, "ladder");
            require(context, run.maxY >= c.feet.getY() + 5.9, "the bot never got to the top (max y offset " + (run.maxY - c.feet.getY()) + ")");
            run.requireNoEdits();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Placing: bridge a gap, pillar up; only when the policy allows it
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:baritone_navigation_game_tests_bridges_gap_when_placing_is_allowed", maxTicks = 1000)
    public void bridgesGapWhenPlacingIsAllowed(GameTestHelper context) {
        Course c = gapCourse(context, "NavBridgeGT", 5);
        c.giveBlocks(Items.COBBLESTONE, 16);
        BlockPos goal = c.feet.offset(10, 0, 0);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
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
        BaritoneRegistry.INSTANCE.setPolicy(c.bot, BaritonePolicy.NO_PLACING);
        BlockPos goal = c.feet.offset(10, 0, 0);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
        c.await(400, run -> {
            require(context, c.bot.getX() < c.feet.getX() + 4.0D, "the bot crossed to x offset " + (c.bot.getX() - c.feet.getX()) + " without being allowed to place");
            require(context, run.minY >= c.feet.getY() - 0.1, "the bot fell into the gap");
            require(context, c.count(Items.COBBLESTONE) == 16, "blocks were placed although placing is forbidden");
            run.requireNoEdits();
        });
    }

    @GameTest(maxTicks = 800)
    public void pillarsUpFourBlocksWhenPlacingIsAllowed(GameTestHelper context) {
        Course c = Course.begin(context, "NavPillarGT", 7, -3, 3, 3);
        c.snapshot();
        c.giveBlocks(Items.COBBLESTONE, 16);
        BlockPos goal = c.feet.offset(0, 4, 0);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
        c.await(700, run -> {
            require(context, c.bot.getY() >= c.feet.getY() + 3.95, "the bot did not get up: y offset " + (c.bot.getY() - c.feet.getY()));
            List<BaritoneEdits.Edit> placed = BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.PLACE);
            Set<BlockPos> cells = new HashSet<>();
            placed.forEach(edit -> cells.add(edit.pos()));
            for (int dy = 0; dy <= 3; dy++) {
                require(context, cells.contains(c.feet.offset(0, dy, 0)), "no block was placed at y offset " + dy + ": " + placed);
            }
            run.requireEditsExplainTheWorldDiff();
            run.requireLogged("place", placed.size());
        });
    }

    @GameTest(maxTicks = 400)
    public void doesNotPillarWhenPlacingIsForbidden(GameTestHelper context) {
        Course c = Course.begin(context, "NavNoPillarGT", 8, -3, 3, 3);
        c.snapshot();
        c.giveBlocks(Items.COBBLESTONE, 16);
        BaritoneRegistry.INSTANCE.setPolicy(c.bot, BaritonePolicy.NO_PLACING);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(c.feet.offset(0, 4, 0)));
        c.await(300, run -> {
            require(context, c.bot.getY() < c.feet.getY() + 1.0D, "the bot got up to y offset " + (c.bot.getY() - c.feet.getY()) + " without placing");
            require(context, c.count(Items.COBBLESTONE) == 16, "blocks were placed although placing is forbidden");
            run.requireNoEdits();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Breaking: through MiningController, with the right tool
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 1000)
    public void breaksThroughWallWithThePickaxeAndEveryBreakGoesThroughMiningController(GameTestHelper context) {
        Course c = Course.begin(context, "NavBreakGT", 9, -2, 14, 3);
        Set<BlockPos> wall = new HashSet<>();
        for (int dx = 4; dx <= 6; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                c.fill(dx, dz, Blocks.STONE, 0, 3);
                for (int dy = 0; dy <= 3; dy++) {
                    wall.add(c.feet.offset(dx, dy, dz));
                }
            }
        }
        c.snapshot();
        // The wrong tool sits in the selected slot; the right one is elsewhere in the hotbar.
        c.bot.getInventory().setItem(0, new ItemStack(Items.WOODEN_SHOVEL));
        c.bot.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        c.bot.getInventory().setSelectedSlot(0);
        BaritoneRegistry.INSTANCE.setPolicy(c.bot, BaritonePolicy.NO_PLACING);
        BlockPos goal = c.feet.offset(10, 0, 0);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 1));
        c.await(900, run -> {
            run.requireNear(goal, 1.6, 0.6, "break through");
            List<BaritoneEdits.Edit> breaks = BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.BREAK);
            System.out.println("BARITONE_BREAKS " + breaks);
            require(context, breaks.size() >= 6 && breaks.size() <= 10, "a 3-thick, 2-high tunnel is 6 cells; " + breaks.size() + " were broken: " + breaks);
            for (BaritoneEdits.Edit edit : breaks) {
                require(context, wall.contains(edit.pos()), "a block outside the wall was broken: " + edit);
                require(context, edit.block().equals("minecraft:stone"), "broke " + edit.block());
                require(context, edit.tool().equals("minecraft:iron_pickaxe"), "broke " + edit.block() + " with " + edit.tool() + " instead of the pickaxe");
                require(context, edit.ticks() >= 1, "a break that took no time: " + edit);
            }
            require(context, BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.PLACE).isEmpty(), "something was placed although placing is forbidden");
            run.requireEditsExplainTheWorldDiff();
            run.requireLogged("mine_start", breaks.size());
            run.requireLogged("mine_complete", breaks.size());
        });
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

            MiningController legacy = new MiningController(target, Direction.WEST);
            legacy.tick(pack);
            require(context, Math.abs(Mth.wrapDegrees(c.bot.getYRot() + 90.0F)) < 5.0F, "the legacy controller did not aim at the block: yaw " + c.bot.getYRot());
            require(context, c.bot.getInventory().getSelectedSlot() == 1, "the legacy controller did not pick the pickaxe: slot " + c.bot.getInventory().getSelectedSlot());
            legacy.abort(c.bot);
        } finally {
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
        }
        context.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The legacy executor and Baritone take turns
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 500)
    public void legacyNavigationStillWorksWhileBaritoneInstanceIsIdle(GameTestHelper context) {
        Course c = Course.begin(context, "NavLegacyGT", 11, -2, 14, 5);
        c.snapshot();
        ActionPack pack = c.bot.getActionPack();
        BlockPos first = c.feet.offset(8, 0, 3);
        BlockPos second = c.feet.offset(1, 0, -2);
        ActionResult started = pack.startPathTo(first);
        require(context, started == ActionResult.IN_PROGRESS, "the legacy path did not start: " + started);
        int[] phase = {0};
        int[] ticks = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            require(context, !BaritoneRegistry.INSTANCE.isBusy(c.bot), "an idle Baritone instance claims the bot");
            if (phase[0] == 0 && pack.isPathExecutorIdle()) {
                require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(first)) < 1.5, "the legacy path ended at " + c.bot.position());
                phase[0] = 1;
                ActionResult walk = pack.startWalkTo(Vec3.atBottomCenterOf(second));
                require(context, walk == ActionResult.IN_PROGRESS, "the legacy walk did not start: " + walk);
            } else if (phase[0] == 1 && pack.isWalkToIdle()) {
                require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(second)) < 1.5, "the legacy walk ended at " + c.bot.position());
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                context.succeed();
                phase[0] = 2;
            } else if (ticks[0] > 400 && phase[0] < 2) {
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                require(context, false, "legacy navigation stalled in phase " + phase[0] + " at " + c.bot.position());
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void controlChangesHandsBetweenBaritoneAndLegacyExecutorWithSingleWriter(GameTestHelper context) {
        Course c = Course.begin(context, "NavHandGT", 12, -2, 26, 4);
        c.snapshot();
        ActionPack pack = c.bot.getActionPack();
        BlockPos far = c.feet.offset(22, 0, 0);
        BlockPos back = c.feet.offset(2, 0, 0);
        BlockPos again = c.feet.offset(12, 0, 0);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(far, 1));
        int[] phase = {0};
        int[] ticks = {0};
        int[] driven = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            boolean baritoneDrives = BaritoneRegistry.INSTANCE.isBusy(c.bot);
            boolean legacyActs = !pack.isPathExecutorIdle() || !pack.isWalkToIdle() || !pack.isMiningIdle();
            require(context, !(baritoneDrives && legacyActs), "two writers at tick " + ticks[0] + " phase " + phase[0]);
            try {
                switch (phase[0]) {
                    case 0 -> { // Baritone runs east
                        if (baritoneDrives) {
                            driven[0]++;
                        }
                        if (driven[0] == 25) {
                            require(context, c.bot.getX() > c.feet.getX() + 3.0D, "Baritone did not get the bot moving: x offset " + (c.bot.getX() - c.feet.getX()));
                            // The legacy executor is given an order: it takes the bot, Baritone stops.
                            pack.startWalkTo(Vec3.atBottomCenterOf(back));
                            require(context, !BaritoneRegistry.INSTANCE.isBusy(c.bot), "Baritone still busy right after the legacy order");
                            require(context, !c.baritone.getCustomGoalProcess().isActive() && !c.baritone.getPathingBehavior().isPathing(),
                                    "Baritone kept its goal process or path after being preempted");
                            phase[0] = 1;
                        }
                    }
                    case 1 -> { // the legacy walk runs back
                        require(context, !baritoneDrives, "Baritone took the bot back on its own");
                        if (pack.isWalkToIdle()) {
                            require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(back)) < 1.5, "the legacy walk ended at " + c.bot.position());
                            c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(again, 1));
                            phase[0] = 2;
                        }
                    }
                    case 2 -> { // Baritone again
                        if (!baritoneDrives) {
                            require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(again)) < 1.6, "Baritone's second run ended at " + c.bot.position());
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
     * A linkage failure that escapes a Baritone call in the middle of a driven tick (the seam raises a {@link NoClassDefFoundError}
     * exactly where Baritone would): the bot's tick completes (the server does not die of it), Baritone is retired for the session
     * and every instance torn down, and the bot's next order is carried out by the legacy executor, which gets the bot in the
     * same tick the driver gave up.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_navigation_game_tests_a_linkage_failure_inside_adriven_tick_retires_baritone_and_the_bot_continues_legacy", maxTicks = 500)
    public void aLinkageFailureInsideADrivenTickRetiresBaritoneAndTheBotContinuesLegacy(GameTestHelper context) {
        Course c = Course.begin(context, "NavFaultGT", 0, -2, 30, 4);
        c.snapshot();
        ActionPack pack = c.bot.getActionPack();
        BlockPos far = c.feet.offset(26, 0, 0);
        BlockPos legacyGoal = c.feet.offset(10, 0, 2);
        c.baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(far, 1));
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
                            BaritoneDriver.testFault = where -> {
                                if (where.equals("after_physics")) {
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
                            ActionResult started = pack.startPathTo(legacyGoal);
                            require(context, started.isInProgress() && !pack.hasBaritoneRoute() && !pack.isPathExecutorIdle(),
                                    "the legacy executor did not take the order: " + started.status() + " " + started.reason());
                            phase[0] = 2;
                        }
                    }
                    case 2 -> {
                        require(context, BaritoneRegistry.INSTANCE.size() == 0, "something created a Baritone instance after the failure");
                        if (pack.isPathExecutorIdle()) {
                            require(context, c.bot.position().distanceTo(Vec3.atBottomCenterOf(legacyGoal)) < 1.6,
                                    "the legacy route ended at " + c.bot.position());
                            phase[0] = 3;
                            NavEngineSelector.clearFailureForTests();
                            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), c.name);
                            context.succeed();
                        }
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
