package io.github.zoyluo.minecraftai.persist;

import io.github.zoyluo.minecraftai.goal.Goal;
import org.junit.jupiter.api.Test;

import java.util.List;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionSpecTest {
    @Test
    void registryIndependentGoalKindsRoundTripWithoutTaskState() {
        List<Goal> goals = List.of(
                new Goal.HavePickaxeTier(3),
                new Goal.Armor(),
                new Goal.Workstation(),
                new Goal.Food(5),
                new Goal.Build("small_hut"));

        for (Goal goal : goals) {
            MissionSpec spec = MissionSpec.fromGoal(goal);
            assertEquals(goal, spec.toGoal().orElseThrow());
            assertTrue(spec.params().keySet().stream().noneMatch(key -> key.contains("task") || key.contains("phase")));
        }
    }

    @Test
    void invalidNumericOrFutureTypeIsIsolated() {
        assertTrue(new MissionSpec("food", java.util.Map.of("count", "not-a-number"), List.of()).toGoal().isEmpty());
        assertTrue(new MissionSpec("future_goal", java.util.Map.of(), List.of()).toGoal().isEmpty());
    }

    @Test
    void compoundFulfillmentRoundTripsCanonicalRetainedAndDeliveryAllocations() {
        Goal.Fulfill goal = new Goal.Fulfill(List.of(
                new Goal.Allocation(Items.STONE_AXE, 1, "Alex"),
                new Goal.Allocation(Items.STICK, 4, ""),
                new Goal.Allocation(Items.STONE_AXE, 1, "Alex")));

        MissionSpec spec = MissionSpec.fromGoal(goal);

        assertEquals("fulfill", spec.type());
        assertEquals(goal, spec.toGoal().orElseThrow());
        assertTrue(new MissionSpec("fulfill", java.util.Map.of("schema", "1"),
                List.of("minecraft:stick", "01", "")).toGoal().isEmpty());
        assertTrue(new MissionSpec("fulfill", java.util.Map.of("schema", "1"),
                List.of("minecraft:stick", "1", "", "minecraft:stick", "1", ""))
                .toGoal().isEmpty());
    }
}
