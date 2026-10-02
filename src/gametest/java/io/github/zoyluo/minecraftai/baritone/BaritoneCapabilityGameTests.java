package io.github.zoyluo.minecraftai.baritone;

import io.github.zoyluo.minecraftai.gametest.PerceptionFixtures;
import baritone.api.IBaritone;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Baritone capabilities behind the {@code nav.baritone.*} switches, each on a real course with a real bot, with the capability
 * ON (the move happens, safely) and OFF (the engine behaves as it did before the switch existed):
 *
 * <ul>
 *   <li>parkour: visibly observed two- and three-block jumps (including an ascending landing) succeed; a supplied-block
 *       parkour-place crossing succeeds; and an uncrossable gap or disabled parkour is refused before it drives the bot;</li>
 *   <li>water-bucket fall: a drop of 10 blocks is taken with a carried water bucket, without damage, and the water is picked up
 *       again (no water is left, the bucket is full again);</li>
 *   <li>vines: a vine column is climbed up and down;</li>
 *   <li>mob avoidance: a route bends around a hostile mob the bot can see, and ignores one it cannot (sealed behind a wall).</li>
 * </ul>
 *
 * <p>Every course is sealed by a bedrock ring. The capability switches are installed through the config (reflectively, the way
 * the other GameTests swap it) before the bot's Baritone instance is made, and put back when the run ends.</p>
 */
public final class BaritoneCapabilityGameTests {
    /**
     * All tests of a batch run at once and their courses overlap in the horizontal plane, so every course gets its own slab of the
     * world: {@code BASE_Y + SLAB_STEP * index}, the index being the order in which the courses are built.
     */
    private static final int BASE_Y = 60;
    private static final int SLAB_STEP = 22;
    private static final Map<String, Integer> SLABS = new HashMap<>();
    /** A water-bucket descent genuinely needs water movement permission; it remains break/place-free. */
    private static final NavRoute.Options WATER_BUCKET_FALL_ROUTE = new NavRoute.Options(false, false, true);

    // ---------------------------------------------------------------------------------------------------------------
    // Parkour
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_parkour_jumps_two_block_gap", maxTicks = 500)
    public void parkourJumpsTwoBlockGap(GameTestHelper context) {
        crossesObservedGap(context, "CapParkour2GT", 2, false);
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_parkour_jumps_three_block_gap", maxTicks = 500)
    public void parkourJumpsThreeBlockGap(GameTestHelper context) {
        crossesObservedGap(context, "CapParkour3GT", 3, false);
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_parkour_ascend_jumps_gap_onto_higher_block", maxTicks = 500)
    public void parkourAscendJumpsGapOntoHigherBlock(GameTestHelper context) {
        crossesObservedGap(context, "CapParkourUpGT", 2, true);
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_parkour_place_lands_on_block_placed_in_the_air_over_four_block_gap", maxTicks = 600)
    public void parkourPlaceLandsOnBlockPlacedInTheAirOverFourBlockGap(GameTestHelper context) {
        Course c = gapCourse(context, "CapParkourPlaceGT", 4, false, MinecraftAiConfig.BaritoneCaps.defaults());
        c.giveBlocks(Items.COBBLESTONE, 8);
        BlockPos goal = c.feet.offset(4 + 4 + 4, 0, 0);
        // The fixture deliberately supplies blocks, so placement is an honest route permission;
        // the visible gap still has to be admitted by the production observation fence.
        c.goalNear(goal, 1, new NavRoute.Options(false, true, false));
        c.await(500, run -> {
            run.requireNear(goal, 1.6, 0.8, "gap of 4 with a block placed in the air");
            require(context, run.minY >= c.feet.getY() - 0.1D, "the bot fell into the gap: min y offset " + (run.minY - c.feet.getY()));
            List<BaritoneEdits.Edit> placed = BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.PLACE);
            require(context, placed.size() == 1, "one block placed in mid-jump expected (bridging would place 3-4), got " + placed);
            require(context, placed.get(0).pos().getX() >= c.feet.getX() + 4 && placed.get(0).pos().getX() <= c.feet.getX() + 7, "the block was placed outside the gap: " + placed);
            require(context, run.maxY >= c.feet.getY() + 0.5D, "the bot never jumped");
            require(context, BaritoneEdits.of(c.bot.getUUID(), BaritoneEdits.Kind.BREAK).isEmpty(), "something was broken");
            require(context, c.bot.getHealth() >= c.startHealth, "the bot lost health");
        });
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_parkour_refuses_four_block_gap_and_the_bot_stays_on_the_ledge", maxTicks = 500)
    public void parkourRefusesFourBlockGapAndTheBotStaysOnTheLedge(GameTestHelper context) {
        refusesUnbridgedGap(context, "CapParkour4GT", 4, false, MinecraftAiConfig.BaritoneCaps.defaults());
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_parkour_off_leaves_two_block_gap_alone", maxTicks = 500)
    public void parkourOffLeavesTwoBlockGapAlone(GameTestHelper context) {
        refusesUnbridgedGap(context, "CapParkourOffGT", 2, false, MinecraftAiConfig.BaritoneCaps.allOff());
    }

    /** A short jump must be proved by the complete observation-fenced Baritone plan, never fixture knowledge. */
    private static void crossesObservedGap(GameTestHelper context, String name, int gap, boolean ascend) {
        Course c = gapCourse(context, name, gap, ascend, MinecraftAiConfig.BaritoneCaps.defaults());
        int gapStart = 4;
        BlockPos goal = c.feet.offset(gapStart + gap + 4, ascend ? 1 : 0, 0);
        c.goalNear(goal, 1);
        c.await(400, run -> {
            run.requireNear(goal, 1.6, 0.8, "gap of " + gap);
            require(context, run.minY >= c.feet.getY() - 0.1D,
                    "the bot fell into the gap: min y offset " + (run.minY - c.feet.getY()));
            boolean airborneOverGap = run.trail.stream().anyMatch(p -> p.x > c.feet.getX() + gapStart + 0.3D
                    && p.x < c.feet.getX() + gapStart + gap - 0.3D && p.y > c.feet.getY() + 0.1D);
            require(context, airborneOverGap, "the bot never was in the air over the gap: " + c.describe());
            require(context, c.bot.getHealth() >= c.startHealth,
                    "the bot lost health: " + c.startHealth + " -> " + c.bot.getHealth());
            run.requireNoEdits();
        });
    }

    /** A visible far side is not enough to cross an unbridged gap: WALK_ONLY needs a complete fence-constrained plan. */
    private static void refusesUnbridgedGap(GameTestHelper context, String name, int gap, boolean ascend,
                                            MinecraftAiConfig.BaritoneCaps caps) {
        Course c = gapCourse(context, name, gap, ascend, caps);
        int gapStart = 4;
        BlockPos goal = c.feet.offset(gapStart + gap + 4, ascend ? 1 : 0, 0);
        c.expectGoalNearRefusal(goal, 1, NavRoute.Options.WALK_ONLY,
                "navigation_observed_corridor_unavailable");
    }

    /**
     * A floor with a gap of {@code gap} blocks across its whole width, from x = 4 on (the bot starts at x = 0, a run-up of four
     * blocks); with {@code ascend} the far side is one block higher.
     */
    private static Course gapCourse(GameTestHelper context, String name, int gap, boolean ascend, MinecraftAiConfig.BaritoneCaps caps) {
        Course c = Course.begin(context, name, -2, 4 + gap + 10, 4, 9, caps, 0, 0);
        for (int dx = 4; dx < 4 + gap; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                c.set(dx, -1, dz, Blocks.AIR);
            }
        }
        if (ascend) {
            for (int dx = 4 + gap; dx <= 4 + gap + 10; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    c.set(dx, 0, dz, Blocks.STONE);
                }
            }
        }
        c.snapshot();
        return c;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Water-bucket fall
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_water_bucket_fall_from_ten_blocks_lands_without_damage_and_picks_the_water_up", maxTicks = 700)
    public void waterBucketFallFromTenBlocksLandsWithoutDamageAndPicksTheWaterUp(GameTestHelper context) {
        Course c = cliffCourse(context, "CapBucketGT", MinecraftAiConfig.BaritoneCaps.defaults());
        BlockPos goal = c.feet.offset(9, 0, 0);
        c.bot.getInventory().setItem(1, new ItemStack(Items.WATER_BUCKET));
        c.bot.getInventory().setSelectedSlot(0);
        // A player has to lean over a real edge before the full drop is visible.  This is a
        // physical fixture position (still supported by the top block), not a visibility grant;
        // the precondition below proves every cell Baritone may use for the fall is in view.
        c.peekOverCliffEdge();
        c.goalNear(goal, 1, WATER_BUCKET_FALL_ROUTE);
        c.await(600, run -> {
            run.requireNear(goal, 1.6, 0.8, "bucket fall");
            require(context, run.maxFall >= 7.0D, "the bot never fell from a height (max fall distance " + run.maxFall + ")");
            require(context, run.sawWater, "no water was ever placed: the bot did not use the bucket");
            require(context, c.bot.getHealth() >= c.startHealth, "the fall cost health: " + c.startHealth + " -> " + c.bot.getHealth());
            require(context, c.count(Items.WATER_BUCKET) == 1 && c.count(Items.BUCKET) == 0,
                    "the bucket is not full again: water buckets " + c.count(Items.WATER_BUCKET) + ", empty " + c.count(Items.BUCKET));
            require(context, c.waterCells().isEmpty(), "water was left behind: " + c.waterCells());
            BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(c.bot.getUUID());
            require(context, entry == null || entry.placedWater == null, "the placed water is still on the books");
            require(context, BaritoneRefusals.of(c.bot.getUUID(), BaritoneRefusals.Op.USE_ITEM).isEmpty(),
                    "an item use was refused: " + BaritoneRefusals.of(c.bot.getUUID(), BaritoneRefusals.Op.USE_ITEM));
            run.requireNoEdits();
        });
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_water_bucket_fall_off_keeps_the_bot_on_the_cliff_with_bucket_in_its_hotbar", maxTicks = 500)
    public void waterBucketFallOffKeepsTheBotOnTheCliffWithBucketInItsHotbar(GameTestHelper context) {
        Course c = cliffCourse(context, "CapBucketOffGT", MinecraftAiConfig.BaritoneCaps.allOff());
        BlockPos goal = c.feet.offset(9, 0, 0);
        c.bot.getInventory().setItem(1, new ItemStack(Items.WATER_BUCKET));
        // The same honest edge-peek supplies visible terrain. With the capability off, the dry
        // route remains synchronously unavailable rather than taking a partial path over it.
        c.peekOverCliffEdge();
        c.expectGoalNearRefusal(goal, 1, NavRoute.Options.WALK_ONLY,
                "navigation_observed_corridor_unavailable");
    }

    /**
     * A floor and a stone platform ten blocks above it at x = -2..3 whose edge is a cliff. The
     * water tests put the bot at a real supported outer edge before admission, as a player would
     * when looking down a drop; a centered position correctly cannot see through the full lip.
     */
    private static Course cliffCourse(GameTestHelper context, String name, MinecraftAiConfig.BaritoneCaps caps) {
        Course c = Course.begin(context, name, -2, 12, 4, 13, caps, 10, 0);
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                c.set(dx, 9, dz, Blocks.STONE);
            }
        }
        c.snapshot();
        return c;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Vines
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_vines_let_the_bot_climb_vine_column_up_to_platform", maxTicks = 800)
    public void vinesLetTheBotClimbVineColumnUpToPlatform(GameTestHelper context) {
        Course c = vineCourse(context, "CapVineUpGT", vinesOn(), 0);
        BlockPos goal = c.feet.offset(7, 6, 0);
        // The top platform is behind the wall from the floor. The upper vine cell is visible;
        // after climbing it, the platform becomes a real visible second leg.
        c.awaitVia(c.feet.offset(2, 5, 0), 0, goal, NavRoute.Options.WALK_ONLY, 700, run -> {
            run.requireNear(goal, 1.6, 0.8, "vine climb");
            require(context, run.maxY >= c.feet.getY() + 5.9D, "the bot never got to the top (max y offset " + (run.maxY - c.feet.getY()) + ")");
            require(context, c.bot.getHealth() >= c.startHealth, "the bot lost health");
            run.requireNoEdits();
        });
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_vines_let_the_bot_climb_down_vine_column_without_damage", maxTicks = 800)
    public void vinesLetTheBotClimbDownVineColumnWithoutDamage(GameTestHelper context) {
        Course c = vineCourse(context, "CapVineDownGT", vinesOn(), 6);
        BlockPos goal = c.feet.offset(0, 0, 0);
        // From the top platform the lower vine is hidden by the wall. Walk to its visible lip,
        // then use a one-block near goal at the visible cell directly above the vine. The floor
        // leg is issued only after the bot physically reaches that edge and can observe downward.
        c.awaitThrough(List.of(c.feet.offset(3, 6, 0), c.feet.offset(2, 5, 0)), List.of(0, 1),
                goal, NavRoute.Options.WALK_ONLY, 700, run -> {
            run.requireNear(goal, 1.6, 0.8, "vine descent");
            require(context, c.bot.getHealth() >= c.startHealth, "the six-block descent cost health: " + c.startHealth + " -> " + c.bot.getHealth());
            require(context, run.minY <= c.feet.getY() + 0.1D, "the bot never reached the floor");
            run.requireNoEdits();
        });
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_vines_off_still_climbs_vine_column_as_before", maxTicks = 800)
    public void vinesOffStillClimbsVineColumnAsBefore(GameTestHelper context) {
        Course c = vineCourse(context, "CapVineOffGT", MinecraftAiConfig.BaritoneCaps.allOff(), 0);
        BlockPos goal = c.feet.offset(7, 6, 0);
        c.awaitVia(c.feet.offset(2, 5, 0), 0, goal, NavRoute.Options.WALK_ONLY, 700, run -> {
            System.out.println("BARITONE_VINE_OFF " + c.describe() + " maxY offset " + (run.maxY - c.feet.getY()));
            run.requireNear(goal, 1.6, 0.8, "vine climb, vines off");
            run.requireNoEdits();
        });
    }

    private static MinecraftAiConfig.BaritoneCaps vinesOn() {
        MinecraftAiConfig.BaritoneCaps d = MinecraftAiConfig.BaritoneCaps.defaults();
        return new MinecraftAiConfig.BaritoneCaps(d.parkour(), d.parkourPlace(), d.parkourAscend(), d.waterBucketFall(), d.maxBucketFall(), true, d.mobAvoidance());
    }

    /**
     * A bedrock cliff six blocks high from x = 3 on with one column of vines (attached to its west face) at x = 2; the bot starts on
     * the floor ({@code startDy} = 0) or on top of the cliff ({@code startDy} = 6, at x = 7).
     */
    private static Course vineCourse(GameTestHelper context, String name, MinecraftAiConfig.BaritoneCaps caps, int startDy) {
        Course c = Course.begin(context, name, -2, 10, 3, 9, caps, startDy, startDy == 0 ? 0 : 7);
        for (int dx = 3; dx <= 10; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                c.fill(dx, dz, Blocks.BEDROCK, 0, 5);
            }
        }
        BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true);
        for (int dy = 0; dy <= 5; dy++) {
            c.world.setBlock(c.feet.offset(2, dy, 0), vine, Block.UPDATE_CLIENTS);
        }
        c.snapshot();
        return c;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mob avoidance
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_mob_avoidance_bends_the_route_around_hostile_the_bot_can_see", maxTicks = 600 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void mobAvoidanceBendsTheRouteAroundHostileTheBotCanSee(GameTestHelper context) {
        Course c = Course.begin(context, "CapMobSeenGT", -2, 26, 12, 14, MinecraftAiConfig.BaritoneCaps.defaults(), 0, 0);
        // Keep both the hostile and the across-it goal inside the normal 16-block observation
        // radius, so this remains one real observed Baritone plan rather than a relay that
        // changes the avoidance cost horizon.
        Slime slime = slime(c, 7, 0, 0);
        c.snapshot();
        // The bot must have NOTICED the hostile for its route to bend around it: it turns to it and waits the reaction time first.
        PerceptionFixtures.faceToward(c.bot, slime);
        PerceptionFixtures.afterNoticed(context, c.bot, List.of(slime), since -> {
            BlockPos goal = c.feet.offset(15, 0, 0);
            c.goalNear(goal, 1);
            c.await(500, run -> {
                slime.discard();
                run.requireNear(goal, 1.6, 0.8, "route around a hostile");
                double closest = run.closestTo(slime.position());
                System.out.println("BARITONE_AVOID seen closest=" + String.format("%.2f", closest));
                require(context, closest >= 4.5D, "the route passed within " + closest + " blocks of a hostile the bot could see");
                run.requireNoEdits();
            });
        });
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_mob_avoidance_off_walks_straight_past_the_same_hostile", maxTicks = 600)
    public void mobAvoidanceOffWalksStraightPastTheSameHostile(GameTestHelper context) {
        Course c = Course.begin(context, "CapMobOffGT", -2, 26, 12, 14, MinecraftAiConfig.BaritoneCaps.allOff(), 0, 0);
        Slime slime = slime(c, 7, 0, 0);
        c.snapshot();
        BlockPos goal = c.feet.offset(15, 0, 0);
        c.goalNear(goal, 1);
        c.await(500, run -> {
            slime.discard();
            run.requireNear(goal, 1.6, 0.8, "route past a hostile, avoidance off");
            double closest = run.closestTo(slime.position());
            System.out.println("BARITONE_AVOID off closest=" + String.format("%.2f", closest));
            require(context, closest <= 2.6D, "with avoidance off the route still bent away from the hostile (closest " + closest + ")");
            run.requireNoEdits();
        });
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_mob_avoidance_ignores_hostile_sealed_behind_wall", maxTicks = 600)
    public void mobAvoidanceIgnoresHostileSealedBehindWall(GameTestHelper context) {
        Course c = Course.begin(context, "CapMobHiddenGT", -2, 26, 12, 14, MinecraftAiConfig.BaritoneCaps.defaults(), 0, 0);
        // A bedrock box (3 x 3 x 3 inside walls) whose near wall is the row z = 1; the hostile is visibly close but sealed off the lane.
        for (int dx = 5; dx <= 9; dx++) {
            for (int dz = 1; dz <= 5; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    boolean shell = dx == 5 || dx == 9 || dz == 1 || dz == 5 || dy == 3;
                    if (shell) {
                        c.set(dx, dy, dz, Blocks.BEDROCK);
                    }
                }
            }
        }
        Slime slime = slime(c, 7, 0, 3);
        c.snapshot();
        BlockPos goal = c.feet.offset(15, 0, 0);
        c.goalNear(goal, 1);
        c.await(500, run -> {
            slime.discard();
            run.requireNear(goal, 1.6, 0.8, "route past a sealed hostile");
            double bend = Math.max(Math.abs(run.minZ - c.feet.getZ() - 0.5D), Math.abs(run.maxZ - c.feet.getZ() - 0.5D));
            System.out.println("BARITONE_AVOID hidden max lateral offset=" + String.format("%.2f", bend));
            require(context, bend <= 1.6D, "the route bent by " + bend + " blocks around a hostile the bot cannot observe");
            run.requireNoEdits();
        });
    }

    @GameTest(environment = "minecraftai-gametest:baritone_capability_game_tests_mob_avoidance_observation_cost_is_small_with_three_bots", maxTicks = 300)
    public void mobAvoidanceObservationCostIsSmallWithThreeBots(GameTestHelper context) {
        Course c = Course.begin(context, "CapMobCostAGT", -2, 26, 12, 14, MinecraftAiConfig.BaritoneCaps.defaults(), 0, 0);
        c.spawn();
        AIPlayerEntity second = BaritoneServerGameTests.spawn(context, "CapMobCostBGT", c.feet.offset(0, 0, 3));
        AIPlayerEntity third = BaritoneServerGameTests.spawn(context, "CapMobCostCGT", c.feet.offset(0, 0, -3));
        List<Slime> slimes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            slimes.add(slime(c, 8 + i, 0, (i % 2 == 0 ? 6 : -6)));
        }
        List<AIPlayerEntity> bots = List.of(c.bot, second, third);
        for (AIPlayerEntity bot : bots) {
            BaritoneRegistry.INSTANCE.get(bot);
            // Perception: each bot looks east, at the middle of the two slime rows, so every slime is inside its view; the count below
            // is taken at tick 60, after the reaction time of the shared formula for the farthest and widest of them.
            PerceptionFixtures.facePoint(bot, Vec3.atCenterOf(c.feet.offset(10, 0, 0)));
            require(context, PerceptionFixtures.reactionTicks(bot, slimes) < 60,
                    "fixture: the slimes are not all noticed by tick 60 by the formula: " + PerceptionFixtures.reactionTicks(bot, slimes));
        }
        long[] nanos = {0L};
        int[] ticks = {0};
        int[] observed = {0};
        context.onEachTick(() -> {
            ticks[0]++;
            long start = System.nanoTime();
            for (AIPlayerEntity bot : bots) {
                BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
                entry.context.refreshEntities();
                if (ticks[0] == 60) {
                    for (var entity : entry.context.entities()) {
                        if (entity instanceof Slime) {
                            observed[0]++;
                        }
                    }
                }
            }
            if (ticks[0] > 10) {
                nanos[0] += System.nanoTime() - start;
            }
            if (ticks[0] == 200) {
                double perTickMs = nanos[0] / 190.0D / 1.0e6D;
                System.out.println("BARITONE_AVOID cost three bots, six slimes each in view: " + String.format("%.4f", perTickMs) + " ms per server tick (amortised over the refresh interval), hostiles listed=" + observed[0]);
                slimes.forEach(Slime::discard);
                for (String name : List.of("CapMobCostAGT", "CapMobCostBGT", "CapMobCostCGT")) {
                    AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
                }
                require(context, observed[0] >= 3 * 4, "the bots see too few hostiles (" + observed[0] + " of 18)");
                require(context, perTickMs < 1.0D, "observing hostiles costs " + perTickMs + " ms per tick for three bots");
                restoreConfig();
                context.succeed();
            }
        });
    }

    private static Slime slime(Course c, int dx, int dy, int dz) {
        Slime slime = EntityType.SLIME.create(c.world, EntitySpawnReason.COMMAND); // an Enemy the danger watcher ignores (size 1)
        slime.setSize(1, true);
        slime.setNoAi(true);
        slime.setSilent(true);
        slime.setPersistenceRequired();
        slime.snapTo(c.feet.getX() + dx + 0.5D, c.feet.getY() + dy, c.feet.getZ() + dz + 0.5D, 0.0F, 0.0F);
        c.world.addFreshEntity(slime);
        slime.setDeltaMovement(Vec3.ZERO);
        return slime;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------------------------

    private static MinecraftAiConfig originalConfig;

    static void installCaps(MinecraftAiConfig.BaritoneCaps caps) {
        MinecraftAiConfig current = MinecraftAiConfig.get();
        if (originalConfig == null) {
            originalConfig = current;
        }
        MinecraftAiConfig.Nav nav = current.nav();
        MinecraftAiConfig.Nav changed = new MinecraftAiConfig.Nav(nav.jumpReach(), nav.sidleAfter(), nav.sidleLimit(), nav.hardLimit(),
                nav.lookahead(), nav.nodeRetry(), nav.sprintMinDist(), nav.maxSafeFall(), nav.engine(), caps);
        setConfig(current.withNav(changed));
    }

    static void restoreConfig() {
        if (originalConfig != null) {
            setConfig(originalConfig);
            originalConfig = null;
        }
    }

    private static void setConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install the GameTest config", exception);
        }
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
        final int height;
        final int startDy;
        final int startDx;
        AIPlayerEntity bot;
        IBaritone baritone;
        float startHealth;
        private final Map<BlockPos, BlockState> before = new HashMap<>();

        private Course(GameTestHelper context, String name, BlockPos feet, int fromX, int toX, int halfZ, int height, int startDy, int startDx) {
            this.context = context;
            this.world = context.getLevel();
            this.name = name;
            this.feet = feet;
            this.fromX = fromX;
            this.toX = toX;
            this.halfZ = halfZ;
            this.height = height;
            this.startDy = startDy;
            this.startDx = startDx;
        }

        /** The bot starts {@code startDy} above and {@code startDx} east of {@code feet}, which is the floor's surface at x = 0, z = 0. */
        static Course begin(GameTestHelper context, String name, int fromX, int toX, int halfZ, int height, MinecraftAiConfig.BaritoneCaps caps, int startDy, int startDx) {
            installCaps(caps);
            ServerLevel world = context.getLevel();
            int slab;
            synchronized (SLABS) {
                slab = SLABS.computeIfAbsent(name, key -> SLABS.size());
            }
            BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y + SLAB_STEP * slab, 8));
            for (int dx = fromX - 1; dx <= toX + 1; dx++) {
                for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                    boolean ring = dx == fromX - 1 || dx == toX + 1 || dz == -halfZ - 1 || dz == halfZ + 1;
                    for (int dy = -5; dy <= height; dy++) {
                        BlockState state;
                        if (ring && dy >= -1) {
                            state = Blocks.BEDROCK.defaultBlockState();
                        } else if (!ring && dy == -1) {
                            state = Blocks.STONE.defaultBlockState();
                        } else {
                            state = Blocks.AIR.defaultBlockState();
                        }
                        world.setBlock(feet.offset(dx, dy, dz), state, Block.UPDATE_CLIENTS);
                    }
                }
            }
            return new Course(context, name, feet, fromX, toX, halfZ, height, startDy, startDx);
        }

        /** Spawns the bot (after the test has built its obstacles: a bot spawned into the air would be snapped to the ground) and makes its Baritone instance. */
        Course spawn() {
            if (bot == null) {
                bot = BaritoneServerGameTests.spawn(context, name, feet.offset(startDx, startDy, 0));
                baritone = BaritoneRegistry.INSTANCE.get(bot);
                startHealth = bot.getHealth();
            }
            return this;
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

        BaritoneNavigator.Admission admitGoalNear(BlockPos goal, int radius, NavRoute.Options options) {
            return ObservedBaritoneTestRoutes.admitNear(bot, goal, radius, options, "gametest_" + name);
        }

        /** Verifies that this fixture's deliberately unavailable route is refused before it can drive the bot. */
        void expectGoalNearRefusal(BlockPos goal, int radius, NavRoute.Options options, String expectedReason) {
            BaritoneNavigator.Admission admission = admitGoalNear(goal, radius, options);
            try {
                assertRefusal(admission, expectedReason, goal);
            } finally {
                stopAndRestore();
            }
            requireNoBaritoneAfterDespawn();
            context.succeed();
        }

        private void assertRefusal(BaritoneNavigator.Admission admission, String expectedReason, BlockPos goal) {
            require(context, !admission.accepted(), name + ": unavailable goal was admitted: " + goal);
            require(context, expectedReason.equals(admission.failure()), name + ": unavailable goal " + goal
                    + " was refused as " + admission.failure() + ", expected " + expectedReason);
            require(context, !BaritoneRegistry.INSTANCE.isBusy(bot), name + ": a refused goal still drives the bot");
            new Run(this).requireNoEdits();
        }

        private void stopAndRestore() {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
            restoreConfig();
        }

        private void requireNoBaritoneAfterDespawn() {
            require(context, BaritoneRegistry.INSTANCE.find(bot.getUUID()) == null,
                    "the refused route's Baritone instance outlived the bot");
        }

        void fill(int dx, int dz, Block block, int fromDy, int toDy) {
            for (int dy = fromDy; dy <= toDy; dy++) {
                set(dx, dy, dz, block);
            }
        }

        void snapshot() {
            spawn();
            before.clear();
            forEachCell(pos -> before.put(pos, world.getBlockState(pos)));
        }

        /**
         * Positions the fixture bot at the actual outer edge of its current top block. It retains
         * enough collision overlap to stand safely, while its eye can genuinely see the drop
         * column instead of the test treating terrain behind the lip as known.
         */
        void peekOverCliffEdge() {
            bot.teleportTo(world, feet.getX() + 3.93D, feet.getY() + 10.0D, feet.getZ() + 0.5D,
                    Set.of(), bot.getYRot(), bot.getXRot(), true);
            bot.setOnGround(true);
            for (int dy = -1; dy <= 11; dy++) {
                BlockPos cell = feet.offset(4, dy, 0);
                require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, cell),
                        name + ": edge peek cannot see required fall cell " + cell);
            }
        }

        void forEachCell(Consumer<BlockPos> action) {
            for (int dx = fromX - 1; dx <= toX + 1; dx++) {
                for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                    for (int dy = -5; dy <= height; dy++) {
                        action.accept(feet.offset(dx, dy, dz));
                    }
                }
            }
        }

        void giveBlocks(net.minecraft.world.item.Item item, int count) {
            spawn();
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

        List<BlockPos> waterCells() {
            List<BlockPos> water = new ArrayList<>();
            forEachCell(pos -> {
                if (!world.getFluidState(pos).isEmpty()) {
                    water.add(pos.immutable());
                }
            });
            return water;
        }

        /** {@code check} runs when Baritone lets go of the bot (arrived, or gave up); a run still busy after {@code limit} ticks fails. */
        void await(int limit, Consumer<Run> check) {
            Run run = new Run(this);
            PerceptionFixtures.scheduleEachTick(context, () -> {
                if (run.done) {
                    return;
                }
                run.sample();
                boolean busy = BaritoneRegistry.INSTANCE.isBusy(bot);
                if (run.ticks > 2 && !busy) {
                    run.done = true;
                    try {
                        System.out.println("BARITONE_CAP " + name + " finished after " + run.ticks + " ticks at " + describe());
                        check.accept(run);
                    } finally {
                        AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                        restoreConfig();
                    }
                    context.succeed();
                } else if (run.ticks > limit) {
                    run.done = true;
                    String where = describe();
                    AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                    restoreConfig();
                    require(context, false, name + ": still busy after " + limit + " ticks, " + where);
                }
            });
        }

        /** Walks through one real, observed relay before issuing the final observed route. */
        void awaitVia(BlockPos waypoint, BlockPos goal, int limit, Consumer<Run> check) {
            awaitVia(waypoint, 1, goal, NavRoute.Options.WALK_ONLY, limit, check);
        }

        /** Walks through one exact or near visible relay before issuing a final route with its real permissions. */
        void awaitVia(BlockPos waypoint, int waypointRadius, BlockPos goal, NavRoute.Options options,
                      int limit, Consumer<Run> check) {
            awaitThrough(List.of(waypoint), waypointRadius, goal, options, limit, check);
        }

        /**
         * Walks through real visible segment goals before issuing the final observed route. This
         * is deliberately not a fixture visibility grant: every later target is submitted only
         * after the bot physically arrives at the prior segment.
         */
        void awaitThrough(List<BlockPos> waypoints, int waypointRadius, BlockPos goal, NavRoute.Options options,
                          int limit, Consumer<Run> check) {
            awaitThrough(waypoints, java.util.Collections.nCopies(waypoints.size(), waypointRadius),
                    goal, options, limit, check);
        }

        /**
         * As {@link #awaitThrough(List, int, BlockPos, NavRoute.Options, int, Consumer)}, but
         * lets a later relay stop at a genuinely visible neighbour of a thin climbable block.
         * It is still a real Baritone goal and never grants terrain evidence for the following leg.
         */
        void awaitThrough(List<BlockPos> waypoints, List<Integer> waypointRadii, BlockPos goal, NavRoute.Options options,
                          int limit, Consumer<Run> check) {
            require(context, !waypoints.isEmpty(), name + ": a staged route needs a visible first segment");
            require(context, waypoints.size() == waypointRadii.size(), name + ": relay waypoint/radius count differs");
            for (int radius : waypointRadii) {
                require(context, radius >= 0, name + ": relay radius is negative");
            }
            Run run = new Run(this);
            int[] nextWaypoint = {0};
            try {
                goalNear(waypoints.get(0), waypointRadii.get(0));
            } catch (RuntimeException exception) {
                stopAndRestore();
                throw exception;
            }
            PerceptionFixtures.scheduleEachTick(context, () -> {
                if (run.done) {
                    return;
                }
                run.sample();
                boolean busy = BaritoneRegistry.INSTANCE.isBusy(bot);
                if (run.ticks > 2 && !busy) {
                    if (nextWaypoint[0] < waypoints.size()) {
                        try {
                            run.requireNear(waypoints.get(nextWaypoint[0]), 1.6D, 1.2D, "visible relay waypoint");
                            nextWaypoint[0]++;
                            if (nextWaypoint[0] < waypoints.size()) {
                                goalNear(waypoints.get(nextWaypoint[0]), waypointRadii.get(nextWaypoint[0]));
                            } else {
                                goalNear(goal, 1, options);
                            }
                        } catch (RuntimeException exception) {
                            run.done = true;
                            stopAndRestore();
                            throw exception;
                        }
                        return;
                    }
                    run.done = true;
                    try {
                        System.out.println("BARITONE_CAP " + name + " finished through visible segments after " + run.ticks + " ticks at " + describe());
                        check.accept(run);
                    } finally {
                        stopAndRestore();
                    }
                    context.succeed();
                } else if (run.ticks > limit) {
                    run.done = true;
                    String where = describe();
                    stopAndRestore();
                    require(context, false, name + ": visible-segment route still busy after " + limit + " ticks, " + where);
                }
            });
        }

        /** Reaches a visible edge, then checks that the deliberately unavailable final leg is rejected before input. */
        void awaitViaThenRefusal(BlockPos waypoint, int waypointRadius, BlockPos goal, NavRoute.Options options,
                                  String expectedReason, int limit, Consumer<Run> check) {
            awaitThroughThenRefusal(List.of(waypoint), waypointRadius, goal, options, expectedReason, limit, check);
        }

        /** Reaches every real visible relay before checking the deliberately unavailable final leg. */
        void awaitThroughThenRefusal(List<BlockPos> waypoints, int waypointRadius, BlockPos goal, NavRoute.Options options,
                                      String expectedReason, int limit, Consumer<Run> check) {
            require(context, !waypoints.isEmpty(), name + ": a refusal route needs a visible first segment");
            Run run = new Run(this);
            int[] nextWaypoint = {0};
            try {
                goalNear(waypoints.get(0), waypointRadius);
            } catch (RuntimeException exception) {
                stopAndRestore();
                throw exception;
            }
            PerceptionFixtures.scheduleEachTick(context, () -> {
                if (run.done) {
                    return;
                }
                run.sample();
                boolean busy = BaritoneRegistry.INSTANCE.isBusy(bot);
                if (run.ticks > 2 && !busy) {
                    try {
                        run.requireNear(waypoints.get(nextWaypoint[0]), 1.6D, 1.2D, "visible refusal relay");
                        nextWaypoint[0]++;
                        if (nextWaypoint[0] < waypoints.size()) {
                            goalNear(waypoints.get(nextWaypoint[0]), waypointRadius);
                            return;
                        }
                        run.done = true;
                        assertRefusal(admitGoalNear(goal, 1, options), expectedReason, goal);
                        check.accept(run);
                    } finally {
                        if (run.done) {
                            stopAndRestore();
                        }
                    }
                    if (run.done) {
                        requireNoBaritoneAfterDespawn();
                        context.succeed();
                    }
                } else if (run.ticks > limit) {
                    run.done = true;
                    String where = describe();
                    stopAndRestore();
                    require(context, false, name + ": refusal relay still busy after " + limit + " ticks, " + where);
                }
            });
        }

        String describe() {
            return "pos=" + bot.position() + " offset=(" + String.format("%.2f,%.2f,%.2f", bot.getX() - feet.getX(), bot.getY() - feet.getY(), bot.getZ() - feet.getZ())
                    + ") goal=" + baritone.getPathingBehavior().getGoal() + " hp=" + bot.getHealth();
        }

        /** Compact immutable-fence diagnostic for a failed GameTest route; it performs no world read. */
        String observedRouteEvidence(BlockPos goal) {
            ObservedNavigationFence fence = BaritoneRegistry.INSTANCE.observationMemory(bot);
            BlockPos current = bot.blockPosition();
            int direction = Integer.signum(goal.getX() - current.getX());
            BlockPos drop = direction == 0 ? current : current.offset(direction, 0, 0);
            StringBuilder column = new StringBuilder();
            int lower = Math.min(goal.getY() - 1, current.getY() - 2);
            int upper = Math.max(goal.getY() + 2, current.getY() + 2);
            for (int y = upper; y >= lower; y--) {
                if (column.length() > 0) {
                    column.append(',');
                }
                BlockState state = fence.stateAt(drop.getX(), y, drop.getZ());
                column.append(y).append('=').append(state == null ? '?' : state.getBlock());
            }
            return " observed_fence={cells=" + fence.cellCount()
                    + ",current=" + fence.stateAt(current)
                    + ",goal=" + fence.stateAt(goal)
                    + ",goal_below=" + fence.stateAt(goal.below())
                    + ",drop=" + drop + "[" + column + "]}";
        }
    }

    private static final class Run {
        final Course c;
        int ticks;
        double maxY = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        double maxFall;
        boolean sawWater;
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
            minZ = Math.min(minZ, pos.z);
            maxZ = Math.max(maxZ, pos.z);
            maxFall = Math.max(maxFall, bot.fallDistance);
            if (!sawWater) {
                BlockPos around = bot.blockPosition();
                for (int dx = -3; dx <= 3 && !sawWater; dx++) {
                    for (int dz = -3; dz <= 3 && !sawWater; dz++) {
                        for (int dy = -2; dy <= 1; dy++) {
                            if (!c.world.getFluidState(around.offset(dx, dy, dz)).isEmpty()) {
                                sawWater = true;
                                break;
                            }
                        }
                    }
                }
            }
            if (ticks % 10 == 0) {
                System.out.println("BARITONE_CAPTRACE " + c.name + " t=" + ticks + " " + c.describe() + " ground=" + bot.onGround() + " fall=" + String.format("%.1f", bot.fallDistance));
            }
        }

        double closestTo(Vec3 point) {
            double best = Double.MAX_VALUE;
            for (Vec3 p : trail) {
                best = Math.min(best, Math.hypot(p.x - point.x, p.z - point.z));
            }
            return best;
        }

        void requireNear(BlockPos goal, double horizontal, double vertical, String what) {
            Vec3 target = Vec3.atBottomCenterOf(goal);
            double dx = c.bot.getX() - target.x;
            double dz = c.bot.getZ() - target.z;
            double dy = c.bot.getY() - target.y;
            require(c.context, Math.sqrt(dx * dx + dz * dz) <= horizontal && Math.abs(dy) <= vertical,
                    what + ": the bot is not at the goal " + goal + ": " + c.describe() + c.observedRouteEvidence(goal));
        }

        void requireNoEdits() {
            require(c.context, BaritoneEdits.of(c.bot.getUUID()).isEmpty(), "the terrain was edited: " + BaritoneEdits.of(c.bot.getUUID()));
            Set<BlockPos> changed = new HashSet<>();
            c.forEachCell(pos -> {
                BlockState was = c.before.get(pos);
                BlockState now = c.world.getBlockState(pos);
                if (was != null && !was.is(now.getBlock()) && !now.is(Blocks.VINE) && !(was.isAir() && !now.getFluidState().isEmpty())) { // vines spread by random ticks
                    changed.add(pos.immutable());
                }
            });
            require(c.context, changed.isEmpty(), "blocks changed although nothing may be broken or placed: " + changed);
        }
    }
}
