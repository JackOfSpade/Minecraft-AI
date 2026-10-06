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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

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
        // what the log's drop could not slide off, and what keeps it out of the bot's sight once it lies there: above
        // the eye line, behind the leaf). The bot lets it fall by breaking that leaf from the pillar before it comes down.
        Case c = new Case(context, "GatherCanopyPlatformGT", 2, 9);
        BlockPos log = c.at(3, 8, 0);
        c.set(3, 8, 0, Blocks.OAK_LOG);
        for (int dx = 3; dx <= 5; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                c.leaf(dx, 7, dz);
            }
        }
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 12));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        c.run(task, () -> {
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
            c.require(c.bot.blockPosition().getY() == c.feet.getY(),
                    "the task ended with the bot still up on a tower: " + c.tail(lines));
            c.require(c.count(lines, "gather_tower_descent_failed") == 0, "a tower could not be taken down: " + c.tail(lines));
            Map<String, Integer> repeats = new HashMap<>();
            for (String line : lines) {
                if (line.contains("event=gather_") && (line.contains("refused") || line.contains("excluded")
                        || line.contains("sighted") || line.contains("unstick") || line.contains("timeout"))) {
                    repeats.merge(line.substring(line.indexOf("event=")).replaceAll(" rays='\\d+'", ""), 1, Integer::sum);
                }
            }
            repeats.forEach((line, count) -> c.require(count <= 3,
                    "the same line was logged " + count + " times: " + line));
            return true;
        });
    }
}
