package io.github.zoyluo.minecraftai.task;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The follow pace on the legacy engine: a far player is followed at a sprint and a near one at a walk, a sneaking player is mirrored,
 * a sprinting one is kept up with, a walking one is walked with, aggro on the owner keeps the follower at a sprint, and a calm
 * warden makes it creep, also under the TPS throttle. The scenarios are shared with the other engine ({@link FollowPaceScenarios}).
 */
public final class FollowPaceGameTests {
    private static final String ENV = "minecraftai-gametest:follow_pace_game_tests_";

    @GameTest(environment = ENV + "follower_sprints_when_far_and_walks_when_close", maxTicks = 320)
    public void followerSprintsWhenFarAndWalksWhenClose(GameTestHelper context) {
        FollowPaceScenarios.sprintsWhenFarAndWalksWhenClose(context, false, "FpL");
    }

    @GameTest(environment = ENV + "follower_matches_sneaking_player", maxTicks = 240)
    public void followerMatchesSneakingPlayer(GameTestHelper context) {
        FollowPaceScenarios.matchesSneakingPlayer(context, false, "FpL", false);
    }

    @GameTest(environment = ENV + "follower_matches_sprinting_player", maxTicks = 200)
    public void followerMatchesSprintingPlayer(GameTestHelper context) {
        FollowPaceScenarios.matchesSprintingPlayer(context, false, "FpL");
    }

    @GameTest(environment = ENV + "follower_walks_with_walking_player", maxTicks = 240)
    public void followerWalksWithWalkingPlayer(GameTestHelper context) {
        FollowPaceScenarios.walksWithWalkingPlayer(context, false, "FpL");
    }

    @GameTest(environment = ENV + "follower_sprints_when_zombie_aggro_on_owner", maxTicks = 160)
    public void followerSprintsWhenZombieAggroOnOwner(GameTestHelper context) {
        FollowPaceScenarios.sprintsWhenZombieAggroOnOwner(context, false, "FpL");
    }

    @GameTest(environment = ENV + "follower_sneaks_near_calm_warden", maxTicks = 240)
    public void followerSneaksNearCalmWarden(GameTestHelper context) {
        FollowPaceScenarios.sneaksNearCalmWarden(context, false, "FpL");
    }

    @GameTest(environment = ENV + "follower_sneak_persists_under_tps_throttle", maxTicks = 240)
    public void followerSneakPersistsUnderTpsThrottle(GameTestHelper context) {
        FollowPaceScenarios.sneakPersistsUnderTpsThrottle(context, false, "FpL");
    }
}
