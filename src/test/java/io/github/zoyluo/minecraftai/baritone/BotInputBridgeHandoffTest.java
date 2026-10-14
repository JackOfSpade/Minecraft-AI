package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.navigation.NavRoute;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Focused pure contracts for the strictly one-tick FollowTask input receipt. */
final class BotInputBridgeHandoffTest {
    @Test
    void directionalPursuitUsesItsResolvedLocalHopRatherThanItsRemoteHeading() {
        NavRoute pursuit = new NavRoute(NavRoute.Shape.DIRECTIONAL_PURSUIT,
                new BlockPos(40, 64, 0), 12, NavRoute.Options.WALK_ONLY, "directional_pursuit", 0);
        pursuit.setResolvedGoal(new BlockPos(4, 65, 0));

        assertFalse(BotInputBridge.isLevelDryFollowHandoffRoute(pursuit, 64, false),
                "a remote target at the current Y cannot authorize an upward local pursuit hop");

        pursuit.setResolvedGoal(new BlockPos(4, 64, 0));
        assertTrue(BotInputBridge.isLevelDryFollowHandoffRoute(pursuit, 64, false),
                "only the actual local GoalBlock may authorize a horizontal continuation");
    }

    @Test
    void ownerFollowHandoffRequiresDryFeetAtBothSharedGateways() {
        NavRoute ownerFollow = NavRoute.ownerFollow(UUID.randomUUID(), new BlockPos(8, 64, 0),
                4, "owner_follow", 0);

        assertTrue(BotInputBridge.isLevelDryFollowHandoffRoute(ownerFollow, 64, false));
        assertFalse(BotInputBridge.isLevelDryFollowHandoffRoute(ownerFollow, 64, true),
                "a swim-capable owner route must not preserve land-only horizontal input");
    }

    @Test
    void onlyConfirmedArrivalCanIssueTheReceipt() {
        assertTrue(BotInputBridge.isNaturalFollowHandoffCompletion(NavRoute.Progress.ARRIVED));
        assertFalse(BotInputBridge.isNaturalFollowHandoffCompletion(NavRoute.Progress.ENDED_SHORT));
        assertFalse(BotInputBridge.isNaturalFollowHandoffCompletion(NavRoute.Progress.OBSERVATION_LOST));
        assertFalse(BotInputBridge.isNaturalFollowHandoffCompletion(NavRoute.Progress.MOVEMENT_BOUNDARY_REFUSED));
    }
}
