package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.perception.SharedWorldSight;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The observed exploration hops of a resource search, run for real through the observation fence and the
 * navigator. Their compass used to turn 45 degrees after every hop, so a bot that found nothing walked a
 * loop of about twelve blocks around where it started and re-covered its own ground.
 */
public final class ExplorationGameTests {
    private static final int HOPS = 4;
    /** A hop that has not arrived after this long is stuck; a real one takes about fifty ticks. */
    private static final int HOP_TIMEOUT_TICKS = 200;
    /** Cells the survey of a place without trees may leave in the bot's sight memory (the old order left about a thousand; the new one about fifty). */
    private static final int SURVEY_REMEMBERED_CELLS = 100;

    @GameTest(environment = "minecraftai-gametest:exploration_game_tests_search_walks_out_through_open_ground_instead_of_circling", maxTicks = 1000)
    public void searchWalksOutThroughOpenGroundInsteadOfCircling(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 44, 8);
        AIPlayerEntity bot = fixture.bot("ExploreOutGT", -40, 0, true);
        ObservedSearchHops hops = new ObservedSearchHops(16);
        List<BlockPos> stops = new ArrayList<>();
        BlockPos[] goal = {null};
        int[] hopStartedTick = {0};

        context.failIfEver(() -> {
            int tick = (int) context.getTick();
            if (goal[0] == null) {
                if (tick < 10 || !bot.onGround()) {
                    return; // a bot just placed in the arena has not settled on its floor yet
                }
                if (stops.isEmpty()) {
                    stops.add(bot.blockPosition().immutable());
                }
                ObservedSearchHops.Attempt attempt = hops.begin(bot, null);
                fixture.require(attempt.started(), "hop " + stops.size() + " was not admitted: "
                        + attempt.reason() + " at " + bot.blockPosition());
                goal[0] = attempt.observedGoal();
                hopStartedTick[0] = tick;
                return;
            }
            boolean arrived = bot.blockPosition().equals(goal[0]);
            fixture.require(arrived || tick - hopStartedTick[0] <= HOP_TIMEOUT_TICKS,
                    "hop " + stops.size() + " toward " + goal[0] + " did not arrive: " + bot.blockPosition());
            if (!arrived) {
                return;
            }
            bot.getActionPack().stopAll();
            BlockPos previous = stops.get(stops.size() - 1);
            BlockPos here = bot.blockPosition().immutable();
            fixture.require(here.getX() >= previous.getX(),
                    "hop " + stops.size() + " turned back over ground already searched: " + previous + " -> " + here);
            for (BlockPos earlier : stops) {
                fixture.require(Math.hypot(here.getX() - earlier.getX(), here.getZ() - earlier.getZ()) >= 8.0D,
                        "hop " + stops.size() + " ended at " + here + ", within eight blocks of " + earlier);
            }
            stops.add(here);
            goal[0] = null;
            if (stops.size() <= HOPS) {
                return;
            }
            int walked = here.getX() - stops.get(0).getX();
            fixture.require(walked >= HOPS * 9,
                    HOPS + " hops carried the bot only " + walked + " blocks from where it began: " + stops);
            fixture.finish();
        });
    }

    /**
     * Looking around a place with no tree in it used to cost about a hundred milliseconds of server thread on every
     * tick for some seventy ticks: the survey and then a 42,000-cell pillar volume cast the observation rays for every
     * cell they visited, whatever block it held. Now only a cell that holds a log is ray-tested, so the survey is
     * over in a few ticks. How many ticks that is depends on the machine, so this counts the work instead: a ray that
     * finds a solid cell in view leaves it in the bot's sight memory, and the floor and walls of this place are all in
     * view, so a survey that cast rays at them would leave them all there.
     */
    @GameTest(environment = "minecraftai-gametest:exploration_game_tests_survey_of_a_treeless_place_rays_no_cell_that_cannot_be_a_log", maxTicks = 200)
    public void surveyOfATreelessPlaceRaysNoCellThatCannotBeALog(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 44, 8);
        AIPlayerEntity bot = fixture.bot("ExploreSurveyGT", -40, 0, true);
        fixture.give(bot, new ItemStack(Items.WOODEN_AXE));
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_gather_survey_of_treeless_place"));

        int[] remembered = {0};

        context.failIfEver(() -> {
            if (!task.describe().contains("phase=EXPLORE")) {
                // Counted while the survey runs: the tick that hands over also admits the first hop, whose route
                // fence rays a great many cells of its own.
                remembered[0] = SharedWorldSight.knownBlocks(bot, state -> true, Integer.MAX_VALUE).size();
                return;
            }
            fixture.require(remembered[0] <= SURVEY_REMEMBERED_CELLS,
                    "the survey of a place without trees ray-tested " + remembered[0] + " cells it could see (at most "
                            + SURVEY_REMEMBERED_CELLS + " expected): " + task.describe());
            fixture.finish();
        });
    }

    /**
     * After a long fruitless search the gather's stuck watchdog is armed. It used to start the next hop the moment
     * a leg ended, before the bot had looked around the place it reached, so a log in plain view was passed again
     * and again until every hop of the episode was spent (a real session: sixteen hops in thirty seconds, with the
     * log in sight from the seventh). The bot must look at each place it reaches.
     */
    @GameTest(environment = "minecraftai-gametest:exploration_game_tests_log_in_view_after_a_long_fruitless_search_is_chopped_at_once", maxTicks = 900)
    public void logInViewAfterALongFruitlessSearchIsChoppedAtOnce(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 44, 8);
        AIPlayerEntity bot = fixture.bot("ExploreWatchdogGT", -40, 0, true);
        fixture.give(bot, new ItemStack(Items.WOODEN_AXE));
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_gather_watchdog_after_explore"));
        BlockPos[] log = {null};
        int[] placedTick = {0};

        context.failIfEver(() -> {
            int tick = (int) context.getTick();
            fixture.require(task.state() != TaskState.FAILED && task.state() != TaskState.CANCELLED,
                    "the gather ended as " + task.state() + ":" + task.failureReason());
            if (log[0] == null) {
                // Past the stuck threshold (160 ticks) and mid-leg: the log appears a few blocks ahead.
                if (tick >= 260 && task.describe().contains("phase=EXPLORE")) {
                    log[0] = bot.blockPosition().east(8);
                    fixture.level.setBlock(log[0], Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
                    placedTick[0] = tick;
                }
                return;
            }
            if (task.state() == TaskState.COMPLETED || fixture.level.getBlockState(log[0]).isAir()) {
                fixture.finish();
                return;
            }
            fixture.require(tick - placedTick[0] <= 150,
                    "the log in view was still standing " + (tick - placedTick[0]) + " ticks after it appeared: "
                            + task.describe());
        });
    }
}
