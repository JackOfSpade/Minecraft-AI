package io.github.zoyluo.minecraftai.persist;

import io.github.zoyluo.minecraftai.goal.Goal;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/** Registry-backed persistence coverage for the immutable fresh-fulfillment baseline. */
public final class MissionSpecFreshFulfillmentGameTests {
    @GameTest(maxTicks = 20)
    public void freshFulfillmentBaselineRoundTripsWithoutChangingLegacySchema(
            GameTestHelper context) {
        Goal.Fulfill fresh = new Goal.Fulfill(List.of(
                new Goal.Allocation(Items.OAK_LOG, 32, "Alex"),
                new Goal.Allocation(Items.STICK, 4, "")), Map.of(
                Items.OAK_LOG, 19,
                Items.STICK, 0));
        MissionSpec freshSpec = MissionSpec.fromGoal(fresh, MissionSpec.ExecutionMode.ADAPTIVE);

        require(context, "2".equals(freshSpec.params().get("schema")),
                "fresh fulfillment did not write schema 2");
        require(context, "2".equals(freshSpec.params().get("allocation_count")),
                "fresh manifest allocation boundary was not persisted");
        require(context, freshSpec.executionMode() == MissionSpec.ExecutionMode.ADAPTIVE,
                "execution policy was not preserved");
        Goal restoredGoal = freshSpec.toGoal().orElse(null);
        require(context, fresh.equals(restoredGoal),
                "fresh fulfillment baseline did not survive round trip: " + restoredGoal);

        Goal.Fulfill legacy = new Goal.Fulfill(List.of(
                new Goal.Allocation(Items.OAK_LOG, 1, "Alex")));
        MissionSpec legacySpec = MissionSpec.fromGoal(legacy);
        require(context, "1".equals(legacySpec.params().get("schema"))
                        && legacySpec.params().size() == 1,
                "legacy fulfillment schema changed: " + legacySpec.params());
        require(context, legacy.equals(legacySpec.toGoal().orElse(null)),
                "legacy fulfillment no longer round trips");

        MissionSpec mismatchedBaseline = new MissionSpec("fulfill", Map.of(
                "schema", "2",
                "allocation_count", "1"), List.of(
                "minecraft:oak_log", "1", "Alex",
                "minecraft:stick", "0"));
        require(context, mismatchedBaseline.toGoal().isEmpty(),
                "schema 2 accepted a baseline for an undeclared item");
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
