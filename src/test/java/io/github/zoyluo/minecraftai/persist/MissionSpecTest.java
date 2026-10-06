package io.github.zoyluo.minecraftai.persist;

import io.github.zoyluo.minecraftai.goal.Goal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionSpecTest {
    private static final Path MISSION_SPEC = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/persist/MissionSpec.java");

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
    void executionPolicyIsPersistedButLegacyConstructionDefaultsToStandard() {
        MissionSpec legacy = new MissionSpec("food", java.util.Map.of("count", "1"), List.of());
        MissionSpec adaptive = new MissionSpec("food", java.util.Map.of("count", "1"), List.of(),
                MissionSpec.ExecutionMode.ADAPTIVE);

        assertEquals(MissionSpec.ExecutionMode.STANDARD, legacy.executionMode());
        assertEquals(MissionSpec.ExecutionMode.ADAPTIVE, adaptive.executionMode());
        assertTrue(adaptive.toGoal().isPresent());
    }

    @Test
    void mineOreBaselineRoundTripsAndLegacyMineOreDefaultsItToZero() {
        // An empty ore family deliberately avoids needing a bootstrapped Minecraft registry in
        // this ordinary JVM test; GameTests cover registry-backed ore identities.
        Goal.MineOre additional = new Goal.MineOre(Set.of(), 1, 63);
        MissionSpec encoded = MissionSpec.fromGoal(additional);

        assertEquals("1", encoded.params().get("count"));
        assertEquals("63", encoded.params().get("initial_drop_count"));
        assertEquals(additional, encoded.toGoal().orElseThrow());

        MissionSpec legacy = new MissionSpec("mine_ore", java.util.Map.of("count", "1"), List.of());
        assertEquals(new Goal.MineOre(Set.of(), 1), legacy.toGoal().orElseThrow());
    }

    @Test
    void timedMineCollectionRoundTripsWithoutChangingLegacyFixedMissionWireFormat() {
        Goal.MineOre timed = Goal.MineOre.timedCollection(Set.of(), 63);
        MissionSpec timedSpec = MissionSpec.fromGoal(timed);

        assertEquals("timed_collection", timedSpec.params().get("collection_mode"));
        assertEquals(timed, timedSpec.toGoal().orElseThrow());

        MissionSpec fixedSpec = MissionSpec.fromGoal(new Goal.MineOre(Set.of(), 1, 63));
        assertTrue(!fixedSpec.params().containsKey("collection_mode"),
                "the default fixed mode remains absent for pre-timebox wire compatibility");
        assertEquals(Goal.CollectionMode.FIXED_QUOTA,
                ((Goal.MineOre) fixedSpec.toGoal().orElseThrow()).collectionMode());

        MissionSpec invalidMode = new MissionSpec("mine_ore",
                java.util.Map.of("count", "1", "collection_mode", "indefinite"), List.of());
        assertTrue(invalidMode.toGoal().isEmpty(), "unknown collection modes must not silently change a mission");
    }

    @Test
    void harvestCropBaselineIsPersistedAndLegacyCropRecordsDefaultItToZero() throws IOException {
        // Crop identities require the Minecraft registry, so keep the ordinary JVM test focused
        // on the declarative wire contract; registry-backed round trips run in GameTests.
        String source = Files.readString(MISSION_SPEC);

        assertTrue(source.contains("params.put(\"initial_produce_count\", String.valueOf(g.initialProduceCount()));"));
        assertTrue(source.contains("nonNegativeIntegerOrDefault(\"initial_produce_count\", 0)"));
        assertTrue(source.contains("putTimedCollectionMode(params, g.collectionMode());"));
        assertTrue(source.contains("collectionModeOrDefault()"));
    }

    @Test
    void compoundFulfillmentPersistsCanonicalItemRecipientTriplets() throws IOException {
        // The real registry-backed round trip runs in the Fabric GameTest. This source contract
        // keeps the ordinary JUnit suite bootstrap-free while guarding its wire format.
        String source = Files.readString(MISSION_SPEC);

        assertTrue(source.contains("case Goal.Fulfill g -> {"));
        assertTrue(source.contains("params.put(\"schema\", \"1\");"));
        assertTrue(source.contains("encoded.add(allocation.itemId());"));
        assertTrue(source.contains("encoded.add(allocation.recipient());"));
        assertTrue(source.contains("case \"fulfill\" -> fulfill();"));
        assertTrue(source.contains("noncanonical_fulfill_mission_spec"));
        assertTrue(source.contains("ExecutionMode executionMode"));
        assertTrue(source.contains("executionMode == null ? ExecutionMode.STANDARD"));
    }
}
