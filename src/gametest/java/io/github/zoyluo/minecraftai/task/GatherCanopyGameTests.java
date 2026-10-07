package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.task.GatherOverheadLogGameTests.Case;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What is left of gather's answer to a canopy it can see but not walk to, once a log straight overhead is
 * climbed (see {@link GatherOverheadLogGameTests}): a log whose own column cannot start a pillar, ground
 * that rises under a log, an exact break request, and the real session's whole canopy in one run.
 */
public final class GatherCanopyGameTests {
    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_ring_pillar_log_is_picked_up_after_the_tower_comes_down", maxTicks = 1800)
    public void ringPillarLogIsPickedUpAfterTheTowerComesDown(GameTestHelper context) {
        // The floor under the log's own column is a pit, so no pillar can start there: the bot builds in a column
        // beside it, five blocks tall, and the felled log falls into the pit a block away from where it stands.
        // It has to come down off that tower first, and then it has to find the log it can no longer see from above.
        Case c = new Case(context, "GatherCanopyRingGT", 0, 9);
        BlockPos log = c.at(3, 10, 0);
        c.set(3, 10, 0, Blocks.OAK_LOG);
        c.set(3, -1, 0, Blocks.AIR);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 8));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            int start = c.indexOf(lines, "gather_pillar_start", "target='" + log.toShortString() + "'");
            c.require(start >= 0, "the log was never climbed: " + c.tail(lines));
            String goal = Case.field(lines.get(start), "goal");
            c.require(goal != null && !(goal.startsWith(log.getX() + ", ") && goal.endsWith(", " + log.getZ())),
                    "the pillar went up the log's own column, over the pit: " + goal);
            c.require(InventoryAction.countItem(c.bot, Items.OAK_LOG) >= 1,
                    "the quota closed without the log in the inventory: " + c.tail(lines));
            c.require(c.count(lines, "gather_tower_descended") == 1 && c.count(lines, "gather_tower_descent_failed") == 0,
                    "the tower was not taken down: " + c.tail(lines));
            c.require(c.count(lines, "gather_pickup_miss") == 0,
                    "the felled log was missed after the climb: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_hill_under_a_log_is_found_by_the_broad_scan_and_climbed_from", maxTicks = 1800)
    public void hillUnderALogIsFoundByTheBroadScanAndClimbedFrom(GameTestHelper context) {
        // Two broad steps of stone rise toward the far wall and the log hangs nine up above the top one. Nothing at
        // the bot's own level is a column worth building in (the cells are stone), so the broad scan has no pillar
        // to offer; it must still say which log has one from the ground above, and the bot walks up there and builds
        // two blocks (not the four it would need from the floor) instead of giving the log up.
        Case c = new Case(context, "GatherCanopyHillGT", 1, 9);
        BlockPos log = c.at(0, 9, 6);
        c.set(0, 9, 6, Blocks.OAK_LOG);
        hill(c, 4);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 8));
        Set<Block> logs = Set.of(Blocks.OAK_LOG);

        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        boolean[] started = {false};
        context.runAfterDelay(2L, () -> {
            // A fresh bot sees nothing until its chunk tracking view is set up on its first ticks.
            HarvestCore.PillarApproachScan scan = HarvestCore.beginNearestPillarApproachScan(c.bot, logs, 16, 6, 32, null);
            while (!scan.step(Long.MAX_VALUE)) {
                // one unbounded step: the scan is small in this fixture
            }
            c.require(scan.result() == null, "a column at the bot's own level was found inside the hill: " + scan.result());
            c.require(log.equals(scan.baseWalkTarget()),
                    "the scan did not name the log whose column stands on the hill: " + scan.baseWalkTarget());
            List<HarvestCore.PillarApproach> elsewhere = HarvestCore.pillarApproachesOnOtherFloors(c.bot, log, logs);
            c.require(!elsewhere.isEmpty() && HarvestCore.pillarFloor(elsewhere.get(0)).getY() == c.feet.getY() + 2
                            && elsewhere.get(0).supports() == 2,
                    "expected a two-block pillar from the top of the hill: " + elsewhere);
            task.start(c.bot);
            started[0] = true;
        });
        c.run(task, () -> {
            if (!started[0] || task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            int walk = c.indexOf(lines, "gather_pillar_base_walk", "target='" + log.toShortString() + "'");
            int pillar = c.indexOf(lines, "gather_pillar_start", "target='" + log.toShortString() + "'", "supports='2'");
            c.require(walk >= 0 && pillar > walk,
                    "expected a walk up the hill, then the two-block pillar (" + walk + "," + pillar + "): " + c.tail(lines));
            c.require(c.count(lines, "gather_target_excluded") == 0 && c.count(lines, "gather_pillar_base_failed") == 0,
                    "the log was written off: " + c.tail(lines));
            c.require(InventoryAction.countItem(c.bot, Items.OAK_LOG) >= 1, "no log in the inventory: " + c.tail(lines));
            return true;
        });
    }

    /**
     * Where a felled block's drop comes to rest is a matter of its random pop: puts the first item of {@code kind} near
     * {@code around} at rest on top of the block at {@code support}. True when there was one to put.
     */
    private static boolean restDropOn(Case c, BlockPos around, Item kind, BlockPos support) {
        boolean moved = false;
        for (ItemEntity drop : c.world().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(4.0D),
                item -> item.getItem().is(kind))) {
            drop.setPos(support.getX() + 0.5D, support.getY() + 1.0D, support.getZ() + 0.5D);
            drop.setDeltaMovement(Vec3.ZERO);
            moved = true;
        }
        return moved;
    }

    /**
     * Two broad steps of stone toward the far wall, four blocks deep and one higher each (a gentle slope, so the
     * risers of both can be seen from the floor), {@code half} blocks to each side of the middle.
     */
    private static void hill(Case c, int half) {
        for (int row = 0; row < 2; row++) {
            for (int dz = 2 + 4 * row; dz <= 5 + 4 * row; dz++) {
                for (int dx = -half; dx <= half; dx++) {
                    for (int y = 0; y <= row; y++) {
                        c.set(dx, y, dz, Blocks.STONE);
                    }
                }
            }
        }
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_log_over_a_leaf_platform_is_climbed_and_its_drop_let_fall", maxTicks = 1800)
    public void logOverALeafPlatformIsClimbedAndItsDropLetFall(GameTestHelper context) {
        // A canopy log at the edge of a platform of leaves, three blocks wide, with the leaf under it (the platform is
        // what the log's drop could not slide off). The eyes see through the platform's edge, so a cell beside the log is
        // a stance a route could take; leaves above those cells leave no room to stand in them, so the log can only be
        // climbed to by a pillar. The bot lets the drop fall by breaking the leaf under the log from the pillar before it
        // comes down. (The fixture puts the drop on that leaf: where a drop comes to rest is a matter of its random pop.)
        Case c = new Case(context, "GatherCanopyPlatformGT", 2, 9);
        BlockPos log = c.at(3, 8, 0);
        BlockPos under = c.at(3, 7, 0);
        c.set(3, 8, 0, Blocks.OAK_LOG);
        leafPlatform(c);
        roofTheStances(c);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 12));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        boolean[] placed = {false};
        c.run(task, () -> {
            placed[0] |= !placed[0] && restDropOn(c, log, Items.OAK_LOG, under);
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "gather_pillar_start", "target='" + log.toShortString() + "'") == 1,
                    "the log was not climbed: " + c.tail(lines));
            c.require(c.count(lines, "gather_drop_released") >= 1,
                    "the leaf under the felled log was never broken: " + c.tail(lines));
            c.require(InventoryAction.countItem(c.bot, Items.OAK_LOG) >= 1 && c.count(lines, "gather_pickup_miss") == 0,
                    "the felled log was left on the platform: " + c.tail(lines));
            c.require(c.count(lines, "gather_tower_descended") >= 1 && c.bot.blockPosition().getY() == c.feet.getY(),
                    "the bot did not come back down: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_log_on_a_leaf_platform_seen_through_its_edge_is_mined_from_the_routes_pillar_and_its_drop_let_fall", maxTicks = 1800)
    public void logOnALeafPlatformSeenThroughItsEdgeIsMinedFromTheRoutesPillarAndItsDropLetFall(GameTestHelper context) {
        // The platform again, with room to stand beside the log on top of it. The eyes see that stance through the
        // platform's edge, so the ordinary route (which may place the bot's own blocks) is admitted to it: the bot
        // climbs by the route's pillar, a tower nobody takes down, and breaks the log as soon as it is in reach, below the
        // platform. The drop lies on the platform, which no ordinary movement reaches from there; the bot sees it
        // through the leaf and lets it fall by breaking the leaf, as a player does, instead of leaving the log behind.
        Case c = new Case(context, "GatherCanopyDirectGT", 6, 9);
        BlockPos log = c.at(3, 8, 0);
        BlockPos under = c.at(3, 7, 0);
        c.set(3, 8, 0, Blocks.OAK_LOG);
        leafPlatform(c);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 12));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        boolean[] placed = {false};
        c.run(task, () -> {
            placed[0] |= !placed[0] && restDropOn(c, log, Items.OAK_LOG, under);
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(placed[0], "the felled log's drop never appeared: " + c.tail(lines));
            c.require(c.count(lines, "gather_drop_released", "leaf='" + under.toShortString() + "'") >= 1,
                    "the leaf the felled log lay on was never broken: " + c.tail(lines));
            c.require(InventoryAction.countItem(c.bot, Items.OAK_LOG) >= 1 && c.count(lines, "gather_pickup_miss") == 0,
                    "the felled log was left on the platform: " + c.tail(lines));
            return true;
        });
    }

    /** A platform of leaves three blocks wide under and beside the log's cell, one level under the log. */
    private static void leafPlatform(Case c) {
        for (int dx = 3; dx <= 5; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                c.leaf(dx, 7, dz);
            }
        }
    }

    /** Leaves over the cells of the platform beside the log: the eyes see them, but nobody can stand in them. */
    private static void roofTheStances(Case c) {
        c.leaf(3, 9, -1);
        c.leaf(3, 9, 1);
        c.leaf(4, 9, 0);
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_drop_on_a_leaf_beside_the_felled_log_is_seen_through_the_platform_and_its_leaf_broken", maxTicks = 2400)
    public void dropOnALeafBesideTheFelledLogIsSeenThroughThePlatformAndItsLeafBroken(GameTestHelper context) {
        // The platform case, with the felled log's drop on the leaf next to the one under the cell it was broken from
        // (where a drop comes to rest is a matter of its random pop; the fixture puts it there). That leaf is not the
        // one the bot breaks blind, but the eyes see the item through the platform's edge: the bot breaks the leaf it
        // lies on (building up to the level of the log first when the leaf is out of reach from the pillar's head),
        // comes down and picks the log up from the floor.
        Case c = new Case(context, "GatherCanopyBesideGT", 5, 9);
        BlockPos log = c.at(3, 8, 0);
        BlockPos beside = c.at(4, 7, 0);
        c.set(3, 8, 0, Blocks.OAK_LOG);
        leafPlatform(c);
        roofTheStances(c);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 12));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        boolean[] placed = {false};
        c.budget = 2200;
        c.run(task, () -> {
            placed[0] |= !placed[0] && restDropOn(c, log, Items.OAK_LOG, beside);
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(placed[0], "the felled log's drop never appeared: " + c.tail(lines));
            c.require(c.count(lines, "gather_drop_released", "leaf='" + beside.toShortString() + "'") == 1,
                    "the leaf the log lay on was not broken: " + c.tail(lines));
            c.require(InventoryAction.countItem(c.bot, Items.OAK_LOG) >= 1 && c.count(lines, "gather_pickup_miss") == 0,
                    "the log was left on the platform: " + c.tail(lines));
            c.require(c.count(lines, "gather_tower_descended") >= 1 && c.bot.blockPosition().getY() == c.feet.getY(),
                    "the bot did not come back down: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_drop_seen_on_a_leaf_out_of_reach_of_the_pillars_head_is_climbed_to_and_its_leaf_broken", maxTicks = 700)
    public void dropSeenOnALeafOutOfReachOfThePillarsHeadIsClimbedToAndItsLeafBroken(GameTestHelper context) {
        // The tower watch alone, on a pillar that is still low: the cell a log was broken in is seven blocks up, and its drop
        // lies on a leaf of a platform four blocks to the side and a level under that cell. The eyes see the item through the
        // platform at once, but the leaf is more than an arm from the bot's eye (the watch used to end there, and the tower
        // came down with the log left on the canopy). One climb to the level of the break, the same an unseen item gets,
        // brings the leaf within reach; the bot breaks it from there and the item falls.
        Case c = new Case(context, "TowerDropFarLeafGT", 4, 9);
        BlockPos broken = c.at(0, 7, 0);
        BlockPos leaf = c.at(4, 6, 0);
        for (int dx = 3; dx <= 5; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                c.leaf(dx, 6, dz);
            }
        }
        c.give(new ItemStack(Items.DIRT, 12));
        ItemEntity log = new ItemEntity(c.world(), leaf.getX() + 0.5D, leaf.getY() + 1.0D, leaf.getZ() + 0.5D, new ItemStack(Items.OAK_LOG));
        log.setDeltaMovement(Vec3.ZERO);
        c.world().addFreshEntity(log);
        TowerDropWatch watch = new TowerDropWatch(broken, c.world().getGameTime(), Set.of(Items.OAK_LOG), 400, "gather");

        boolean[] checked = {false};
        c.budget = 600;
        c.watch(() -> {
            if (!checked[0]) {
                checked[0] = true;
                c.require(HarvestCore.nearestDropAnyOf(c.bot, Set.of(Items.OAK_LOG), 8.0D).isPresent(),
                        "fixture: the bot must see the item through the platform");
                c.require(!HarvestCore.canReach(c.bot, leaf),
                        "fixture: the leaf under the item must be out of reach from the bot's eye");
            }
            if (watch.hold(c.bot)) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "gather_drop_climb") == 1 && c.count(lines, "gather_drop_climb_refused") == 0,
                    "the bot never built up to the leaf it could see but not reach: " + c.tail(lines));
            c.require(c.count(lines, "gather_drop_released", "leaf='" + leaf.toShortString() + "'") == 1,
                    "the leaf the item lay on was not broken from the pillar: " + c.tail(lines));
            c.require(c.world().getBlockState(leaf).isAir(), "the leaf is still standing");
            c.require(c.bot.blockPosition().getY() == broken.getY() - 1,
                    "the bot did not stand at the level of the break: " + c.bot.blockPosition());
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_drop_resting_in_a_hole_far_from_the_tower_is_fetched_from_the_holes_edge", maxTicks = 1800)
    public void dropRestingInAHoleFarFromTheTowerIsFetchedFromTheHolesEdge(GameTestHelper context) {
        // The real session's pit, with the drop put where it makes the case: the log is overhead and climbed from its own
        // column, and its drop (put to rest in a one-block hole two cells to the side, where the random pop of a real
        // drop puts it about one run in ten) lies at the bottom of a hole whose floor is seen only from within a block
        // or so of its edge. The bot sees the item come to rest from the pillar's head; from the floor, at the foot of the
        // tower, it sees nothing. It must remember where the item lies, walk to the edge, and pick it up out of the hole.
        Case c = new Case(context, "GatherCanopyHoleGT", 7, 9);
        BlockPos log = c.at(3, 10, 0);
        BlockPos hole = c.at(5, -1, 0);
        BlockPos holeFloor = c.at(5, -2, 0);
        c.set(3, 10, 0, Blocks.OAK_LOG);
        c.set(5, -1, 0, Blocks.AIR);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 12));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        boolean[] placed = {false};
        c.run(task, () -> {
            placed[0] |= !placed[0] && restDropOn(c, log, Items.OAK_LOG, holeFloor);
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(placed[0], "the felled log's drop never appeared");
            c.require(InventoryAction.countItem(c.bot, Items.OAK_LOG) >= 1 && c.count(lines, "gather_pickup_miss") == 0,
                    "the log was left in the hole: " + c.tail(lines));
            c.require(c.count(lines, "gather_pickup_origin_approach", "origin='" + hole.toShortString() + "'") >= 1,
                    "the bot never walked to the edge of the hole the item rests in: " + c.tail(lines, "gather_pickup"));
            c.require(c.count(lines, "gather_tower_descended") >= 1 && c.bot.blockPosition().getY() <= c.feet.getY(),
                    "the bot did not come down from its tower: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_exact_break_log_on_a_ledge_is_climbed_before_it_is_excluded", maxTicks = 1800)
    public void exactBreakLogOnALedgeIsClimbedBeforeItIsExcluded(GameTestHelper context) {
        // The ledge geometry of the plain gather case: the survey picks a log whose stance Baritone then refuses.
        // An exact break never tunnels, but the pillar is no tunnel, and writing the block off first left it standing.
        Case c = new Case(context, "GatherCanopyExactGT", 3, 9);
        BlockPos log = c.at(0, 7, 7);
        c.set(0, 7, 7, Blocks.OAK_LOG);
        c.set(1, 6, 7, Blocks.STONE);
        for (int[] cell : new int[][] {{-3, 0, 0}, {-3, 0, 1}, {-3, 0, -1}, {-4, 0, 0}}) {
            c.set(cell[0], cell[1], cell[2], Blocks.DIRT);
        }
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.WOODEN_SHOVEL));
        GatherQuotaTask task = GatherQuotaTask.breakBlocks(Blocks.OAK_LOG, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            int pillar = c.indexOf(lines, "gather_pillar_start", "target='" + log.toShortString() + "'");
            int unreachable = c.indexOf(lines, "break_blocks_target_unreachable");
            c.require(pillar >= 0, "the refused log was never climbed: " + c.tail(lines));
            c.require(unreachable < 0 || unreachable > pillar,
                    "the block was written off before its pillar had a chance: " + c.tail(lines));
            c.require(c.world().getBlockState(log).isAir(), "the log is still standing");
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_blocks_of_the_towers_own_kind_are_not_counted_as_gathered", maxTicks = 3000)
    public void blocksOfTheTowersOwnKindAreNotCountedAsGathered(GameTestHelper context) {
        // The tower is built of dirt and the quota is for dirt: its blocks come back as dirt when it is taken down,
        // and each one is a pickup. Two blocks are asked for; the first must not close the quota with the tower's help.
        Case c = new Case(context, "GatherCanopySameKindGT", 4, 9);
        BlockPos first = c.at(0, 7, 0);
        BlockPos second = c.at(6, 7, 0);
        c.set(0, 7, 0, Blocks.DIRT);
        c.set(6, 7, 0, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.DIRT, 8));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.DIRT, 2);
        task.start(c.bot);

        c.budget = 2800;
        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.world().getBlockState(first).isAir() && c.world().getBlockState(second).isAir(),
                    "the quota of two closed with a block still standing: " + c.tail(lines));
            // One of the tower's blocks may have popped out of reach of the spot the bot finished on.
            c.require(InventoryAction.countItem(c.bot, Items.DIRT) >= 9,
                    "expected the supports back and the two blocks gathered: " + InventoryAction.countItem(c.bot, Items.DIRT));
            c.require(c.count(lines, "gather_summary", "consistent='true'") == 1,
                    "the tower's own blocks were counted as gathered: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_pillar_plan_does_not_depend_on_the_bot_being_mid_fall", maxTicks = 100)
    public void pillarPlanDoesNotDependOnTheBotBeingMidFall(GameTestHelper context) {
        // The bot plans its next pillar the moment a descent drops it onto the floor, still a fraction of a block
        // above it. The eye it will have standing on a pillar is the standing eye, not the one it has right then:
        // measured from the fall the goal came out a block too low, the log stayed out of reach and the pillar
        // "ended short".
        Case c = new Case(context, "GatherCanopyMidFallGT", 0, 6);
        BlockPos log = c.at(0, 8, 0);
        c.set(0, 8, 0, Blocks.OAK_LOG);
        Set<Block> logs = Set.of(Blocks.OAK_LOG);

        context.runAfterDelay(2L, () -> {
            HarvestCore.PillarApproach standing = HarvestCore.pillarApproachFor(c.bot, log, logs);
            c.bot.teleportTo(c.world(), c.feet.getX() + 0.5D, c.feet.getY() + 0.6D, c.feet.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
            HarvestCore.PillarApproach falling = HarvestCore.pillarApproachFor(c.bot, log, logs);
            c.require(standing != null && standing.supports() == 3, "expected a three-block pillar from the floor: " + standing);
            c.require(falling != null && falling.supports() == standing.supports(),
                    "the plan changed while the bot was falling: " + falling + " against " + standing);
            c.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_canopy_game_tests_the_real_sessions_canopy_is_gathered_without_refusal_spam", maxTicks = 4200)
    public void theRealSessionsCanopyIsGatheredWithoutRefusalSpam(GameTestHelper context) {
        // Everything the 2026-10-06 session met in one place: a log seven blocks straight up, a leaf overhead with a
        // trunk beside it, a canopy log ten up on the far side with leaves around it and a pit under it, and one
        // thirteen up on the other side. Five logs in all. The bot has to get every one (or say why not), and no
        // refused sighting or refused route may repeat.
        // Two things in this fixture are not under the test's control, and the run has to end the same whichever way
        // they go: the two last logs are about as far from the trunks at the start, so which of them is climbed first
        // depends on the cell the bot stands in when it has picked up the third log's drop (where that drop came to rest
        // is a matter of its random pop); and the drop of the log over the pit lands in the one-block hole or beside it,
        // and a hole's bottom shows only from its edge.
        Case c = new Case(context, "GatherCanopySessionGT", 0, 12);
        c.set(0, 7, 0, Blocks.OAK_LOG);
        c.leaf(-6, 6, -4);
        c.set(-4, 6, -4, Blocks.OAK_LOG);
        c.set(-4, 7, -4, Blocks.OAK_LOG);
        c.set(7, 10, 5, Blocks.OAK_LOG);
        c.set(7, -1, 5, Blocks.AIR);
        c.leaf(8, 10, 5);
        c.leaf(6, 11, 5);
        c.set(-6, 13, 6, Blocks.OAK_LOG);
        c.leaf(-5, 13, 6);
        c.leaf(-7, 13, 6);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 12));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 5);
        task.start(c.bot);

        c.budget = 4000;
        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(InventoryAction.countItem(c.bot, Items.OAK_LOG) >= 5, "fewer than five logs: " + c.tail(lines));
            // At the floor, or one down in the hole the last drop may have been fetched from: not up on a tower.
            c.require(c.bot.blockPosition().getY() <= c.feet.getY(),
                    "the task ended with the bot still up on a tower: " + c.tail(lines));
            c.require(c.count(lines, "gather_tower_descent_failed") == 0, "a tower could not be taken down: " + c.tail(lines));
            // Refusal spam is a bot that says the same thing again and again without getting anywhere. Every survey of a
            // healthy run sights the nearest leaf anew (the sweep forgets what it declined once the bot stands in another
            // cell, which it does while it walks to a drop), so the count starts over with each log gathered.
            Map<String, Integer> repeats = new HashMap<>();
            for (String line : lines) {
                if (line.contains("event=gather_unit ")) {
                    repeats.clear();
                } else if (line.contains("event=gather_") && (line.contains("refused") || line.contains("excluded")
                        || line.contains("sighted") || line.contains("unstick") || line.contains("timeout"))) {
                    String said = line.substring(line.indexOf("event=")).replaceAll(" rays='\\d+'", "");
                    int count = repeats.merge(said, 1, Integer::sum);
                    c.require(count <= 3, "the same line was logged " + count + " times without a log gathered in between: "
                            + said);
                }
            }
            return true;
        });
    }
}
