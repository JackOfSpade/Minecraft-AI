package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.navigation.NavEngine;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The navigation obstacle courses (docs/NAVIGATION_COURSES.md): every course runs once with {@code nav.engine=legacy} and once with
 * {@code nav.engine=baritone} (per-bot override) on a fresh arena with identical geometry, through the real FollowTask / MoveTask.
 * Each run is its own test environment (its own batch: no other bot is active, so the timings compare). The tests assert the
 * safety invariants both engines must meet (no damage, never in water or lava, no block broken while a walkable way exists) and
 * that the course was reached; a test whose name ends in {@code Measurement} records a known failure of one engine instead
 * (the same run and the same result line, no outcome assertion). The numbers are in {@code NAVCOURSE} lines of the server log
 * and in {@code <game dir>/nav_courses/results.tsv}.
 */
public final class NavigationCourseGameTests {
    private static final String ENV = "minecraftai-gametest:navigation_course_game_tests_";

    @GameTest(environment = ENV + "wall_detour_legacy", maxTicks = 800)
    public void wallDetourLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.WALL_DETOUR, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "wall_detour_baritone", maxTicks = 800)
    public void wallDetourBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.WALL_DETOUR, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "wall_detour_pickaxe_legacy", maxTicks = 800)
    public void wallDetourPickaxeLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.WALL_DETOUR_PICKAXE, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "wall_detour_pickaxe_baritone", maxTicks = 800)
    public void wallDetourPickaxeBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.WALL_DETOUR_PICKAXE, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "sealed_wall_legacy", maxTicks = 1700)
    public void sealedWallLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.SEALED_WALL, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "sealed_wall_baritone", maxTicks = 1700)
    public void sealedWallBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.SEALED_WALL, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "steps_legacy", maxTicks = 700)
    public void stepsLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.STEPS, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "steps_baritone", maxTicks = 700)
    public void stepsBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.STEPS, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "pit_crevasse_legacy", maxTicks = 800)
    public void pitCrevasseLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.PIT_CREVASSE, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "pit_crevasse_baritone", maxTicks = 800)
    public void pitCrevasseBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.PIT_CREVASSE, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "staircase_legacy", maxTicks = 1000)
    public void staircaseLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.STAIRCASE, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "staircase_baritone", maxTicks = 1000)
    public void staircaseBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.STAIRCASE, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "lake_dry_path_legacy", maxTicks = 1000)
    public void lakeDryPathLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAKE_DRY_PATH, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "lake_dry_path_baritone", maxTicks = 1000)
    public void lakeDryPathBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAKE_DRY_PATH, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "lake_no_dry_path_legacy_measurement", maxTicks = 1000)
    public void lakeNoDryPathLegacyMeasurement(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAKE_NO_DRY_PATH, NavEngine.LEGACY, false);
    }

    @GameTest(environment = ENV + "lake_no_dry_path_baritone", maxTicks = 1000)
    public void lakeNoDryPathBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAKE_NO_DRY_PATH, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "lava_moat_bridge_legacy", maxTicks = 900)
    public void lavaMoatBridgeLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAVA_MOAT_BRIDGE, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "lava_moat_bridge_baritone", maxTicks = 900)
    public void lavaMoatBridgeBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LAVA_MOAT_BRIDGE, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "cactus_field_legacy_measurement", maxTicks = 800)
    public void cactusFieldLegacyMeasurement(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CACTUS_FIELD, NavEngine.LEGACY, false);
    }

    @GameTest(environment = ENV + "cactus_field_baritone", maxTicks = 800)
    public void cactusFieldBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CACTUS_FIELD, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "cliff_safe_drop_legacy", maxTicks = 700)
    public void cliffSafeDropLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CLIFF_SAFE_DROP, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "cliff_safe_drop_baritone", maxTicks = 700)
    public void cliffSafeDropBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CLIFF_SAFE_DROP, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "cliff_unsafe_drop_legacy", maxTicks = 700)
    public void cliffUnsafeDropLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CLIFF_UNSAFE_DROP, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "cliff_unsafe_drop_baritone", maxTicks = 700)
    public void cliffUnsafeDropBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.CLIFF_UNSAFE_DROP, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "house_door_legacy_measurement", maxTicks = 1100)
    public void houseDoorLegacyMeasurement(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.HOUSE_DOOR, NavEngine.LEGACY, false);
    }

    @GameTest(environment = ENV + "house_door_baritone", maxTicks = 1100)
    public void houseDoorBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.HOUSE_DOOR, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "fence_gate_legacy_measurement", maxTicks = 800)
    public void fenceGateLegacyMeasurement(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.FENCE_GATE, NavEngine.LEGACY, false);
    }

    @GameTest(environment = ENV + "fence_gate_baritone", maxTicks = 800)
    public void fenceGateBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.FENCE_GATE, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "ladder_shaft_legacy_measurement", maxTicks = 700)
    public void ladderShaftLegacyMeasurement(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LADDER_SHAFT, NavEngine.LEGACY, false);
    }

    @GameTest(environment = ENV + "ladder_shaft_baritone", maxTicks = 700)
    public void ladderShaftBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LADDER_SHAFT, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "forest_legacy", maxTicks = 1100)
    public void forestLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.FOREST, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "forest_baritone", maxTicks = 1100)
    public void forestBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.FOREST, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "moving_target_legacy", maxTicks = 1000)
    public void movingTargetLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.MOVING_TARGET, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "moving_target_baritone", maxTicks = 1000)
    public void movingTargetBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.MOVING_TARGET, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "two_bots_legacy", maxTicks = 1000)
    public void twoBotsLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.TWO_BOTS, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "two_bots_baritone", maxTicks = 1000)
    public void twoBotsBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.TWO_BOTS, NavEngine.BARITONE, true);
    }

    @GameTest(environment = ENV + "long_path_legacy", maxTicks = 2200)
    public void longPathLegacy(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LONG_PATH, NavEngine.LEGACY, true);
    }

    @GameTest(environment = ENV + "long_path_baritone", maxTicks = 2200)
    public void longPathBaritone(GameTestHelper context) {
        NavigationCourseRun.run(context, NavigationCourses.LONG_PATH, NavEngine.BARITONE, true);
    }

}
