package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.MiningSafety;
import io.github.zoyluo.minecraftai.baritone.BaritoneRefusals;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for associating a self-support refusal with the route that emitted it. */
final class FollowSelfSupportRecoveryTest {
    @Test
    void historicSelfSupportRefusalCannotBeBorrowedByANewerPolicyRefusedRoute() {
        BaritoneRefusals.Refusal historic = selfSupport(new BlockPos(4, 64, 4), 200);

        assertTrue(FollowSelfSupportRecovery.newestEligibleRefusal(
                List.of(historic), historic, Integer.MIN_VALUE, FollowSelfSupportRecoveryTest::isSelfSupport).isEmpty(),
                "a new follow route must not mine from a prior route's still-live refusal ledger entry");
    }

    @Test
    void newestSelfSupportRefusalFromTheCurrentRouteRemainsEligible() {
        BaritoneRefusals.Refusal historic = selfSupport(new BlockPos(4, 64, 4), 200);
        BaritoneRefusals.Refusal current = selfSupport(new BlockPos(5, 64, 4), 201);

        assertEquals(current, FollowSelfSupportRecovery.newestEligibleRefusal(
                List.of(historic, current), historic, Integer.MIN_VALUE, FollowSelfSupportRecoveryTest::isSelfSupport)
                .orElseThrow());
    }

    @Test
    void sameTickHistoricRefusalCannotBeBorrowedAfterRouteReplacement() {
        BaritoneRefusals.Refusal historic = selfSupport(new BlockPos(4, 64, 4), 201);
        BaritoneRefusals.Refusal current = selfSupport(new BlockPos(5, 64, 4), 201);

        assertEquals(current, FollowSelfSupportRecovery.newestEligibleRefusal(
                List.of(historic, current), historic, Integer.MIN_VALUE, FollowSelfSupportRecoveryTest::isSelfSupport)
                .orElseThrow());
    }

    private static BaritoneRefusals.Refusal selfSupport(BlockPos position, int serverTick) {
        return new BaritoneRefusals.Refusal(BaritoneRefusals.Op.BREAK, position,
                MiningSafety.SELF_SUPPORT, "minecraft:stone", serverTick);
    }

    private static boolean isSelfSupport(BaritoneRefusals.Refusal refusal) {
        return MiningSafety.SELF_SUPPORT.equals(refusal.reason());
    }
}
