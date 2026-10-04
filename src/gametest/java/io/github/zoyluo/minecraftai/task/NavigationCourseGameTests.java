package io.github.zoyluo.minecraftai.task;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Strict-survival Baritone obstacle-course regressions (docs/NAVIGATION_COURSES.md). Each course has a fresh arena and drives the
 * real {@code FollowTask} or {@code MoveTask}; it asserts safety, outcome, and that Baritone owned the route. The server log and
 * {@code <game dir>/nav_courses/results.tsv} receive one {@code NAVCOURSE} result per run.
 */
public final class NavigationCourseGameTests {
    private static final String ENV = "minecraftai-gametest:navigation_course_game_tests_";

    @GameTest(environment = ENV + "wall_detour_baritone", maxTicks = 800)
    public void wallDetourBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.WALL_DETOUR);
    }

    @GameTest(environment = ENV + "wall_detour_pickaxe_baritone", maxTicks = 800)
    public void wallDetourPickaxeBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.WALL_DETOUR_PICKAXE);
    }

    @GameTest(environment = ENV + "sealed_wall_baritone", maxTicks = 1700)
    public void sealedWallBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.SEALED_WALL);
    }

    @GameTest(environment = ENV + "steps_baritone", maxTicks = 700)
    public void stepsBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.STEPS);
    }

    @GameTest(environment = ENV + "pit_crevasse_baritone", maxTicks = 800)
    public void pitCrevasseBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.PIT_CREVASSE);
    }

    @GameTest(environment = ENV + "lake_dry_path_baritone", maxTicks = 1000)
    public void lakeDryPathBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAKE_DRY_PATH);
    }

    @GameTest(environment = ENV + "lake_no_dry_path_baritone", maxTicks = 1000)
    public void lakeNoDryPathBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAKE_NO_DRY_PATH);
    }

    @GameTest(environment = ENV + "lava_moat_bridge_baritone", maxTicks = 900)
    public void lavaMoatBridgeBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAVA_MOAT_BRIDGE);
    }

    @GameTest(environment = ENV + "cactus_field_baritone", maxTicks = 800)
    public void cactusFieldBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CACTUS_FIELD);
    }

    @GameTest(environment = ENV + "cliff_safe_drop_baritone", maxTicks = 700)
    public void cliffSafeDropBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CLIFF_SAFE_DROP);
    }

    @GameTest(environment = ENV + "cliff_unsafe_drop_baritone", maxTicks = 700)
    public void cliffUnsafeDropBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CLIFF_UNSAFE_DROP);
    }

    @GameTest(environment = ENV + "house_door_baritone", maxTicks = 1100)
    public void houseDoorBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.HOUSE_DOOR);
    }

    @GameTest(environment = ENV + "forest_baritone", maxTicks = 1100)
    public void forestBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.FOREST);
    }

    @GameTest(environment = ENV + "moving_target_baritone", maxTicks = 1000)
    public void movingTargetBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.MOVING_TARGET);
    }

    @GameTest(environment = ENV + "two_bots_baritone", maxTicks = 1000)
    public void twoBotsBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.TWO_BOTS);
    }

}
