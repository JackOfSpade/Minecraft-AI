package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalPredicateTest {
    private static final Path PREDICATE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/goal/GoalPredicate.java");

    @Test
    void itemAndHarvestPredicatesRequireExactInventoryCount() {
        GoalSnapshot snapshot = snapshot(Map.of("minecraft:wheat", 3), 0, Set.of(), Map.of(), Map.of(), 0, null);
        assertState(new GoalPredicate.ItemCount("minecraft:wheat", 3), snapshot, GoalEvaluation.State.SATISFIED);
        assertState(new GoalPredicate.ItemCount("minecraft:wheat", 4), snapshot, GoalEvaluation.State.UNSATISFIED);
    }

    @Test
    void pickaxeAndOrePredicatesUseTypedFacts() {
        GoalSnapshot snapshot = snapshot(Map.of("minecraft:raw_iron", 2), 3, Set.of(), Map.of(), Map.of(), 0, null);
        assertState(new GoalPredicate.PickaxeTier(3), snapshot, GoalEvaluation.State.SATISFIED);
        assertState(new GoalPredicate.PickaxeTier(4), snapshot, GoalEvaluation.State.UNSATISFIED);
        assertState(new GoalPredicate.AnyItemCount(Set.of("minecraft:raw_iron", "minecraft:iron_ingot"), 2, "ore"),
                snapshot, GoalEvaluation.State.SATISFIED);
    }

    @Test
    void armorRequiresAllFiveCapabilities() {
        GoalPredicate predicate = new GoalPredicate.ArmorSet(GoalPredicates.ARMOR_CAPABILITIES);
        assertState(predicate, snapshot(Map.of(), 0, GoalPredicates.ARMOR_CAPABILITIES, Map.of(), Map.of(), 0, null),
                GoalEvaluation.State.SATISFIED);
        assertState(predicate, snapshot(Map.of(), 0, Set.of("helmet", "chestplate", "leggings", "sword"), Map.of(), Map.of(), 0, null),
                GoalEvaluation.State.UNSATISFIED);
    }

    @Test
    void workstationRequiresTableFurnaceAndChest() {
        GoalPredicate predicate = new GoalPredicate.Workstation();
        assertState(predicate, snapshot(Map.of(), 0, Set.of(), Map.of(
                        "minecraft:crafting_table", 1, "minecraft:furnace", 1, "minecraft:chest", 1), Map.of(), 0, null),
                GoalEvaluation.State.SATISFIED);
        assertState(predicate, snapshot(Map.of(), 0, Set.of(), Map.of(
                        "minecraft:crafting_table", 1, "minecraft:furnace", 1), Map.of(), 0, null),
                GoalEvaluation.State.UNSATISFIED);
    }

    @Test
    void stockpileIgnoresPlayerInventoryAndOnlyCountsContainer() {
        GoalPredicate predicate = new GoalPredicate.Stockpile("minecraft:cobblestone", 6);
        GoalSnapshot inventoryOnly = snapshot(Map.of("minecraft:cobblestone", 64), 0, Set.of(), Map.of(), Map.of(), 0, null);
        assertState(predicate, inventoryOnly, GoalEvaluation.State.UNSATISFIED);
        assertState(predicate, snapshot(Map.of(), 0, Set.of(), Map.of(), Map.of("minecraft:cobblestone", 6), 0, null),
                GoalEvaluation.State.SATISFIED);
    }

    @Test
    void foodUsesNormalizedFoodUnits() {
        GoalPredicate predicate = new GoalPredicate.FoodUnits(4);
        assertState(predicate, snapshot(Map.of("minecraft:raw_beef", 64), 0, Set.of(), Map.of(), Map.of(), 0, null),
                GoalEvaluation.State.UNSATISFIED);
        assertState(predicate, snapshot(Map.of(), 0, Set.of(), Map.of(), Map.of(), 4, null),
                GoalEvaluation.State.SATISFIED);
    }

    @Test
    void buildRequiresBoundFullyMatchingStructureIncludingAirEvidence() {
        GoalPredicate predicate = new GoalPredicate.Structure("small_hut");
        assertState(predicate, GoalSnapshot.empty(), GoalEvaluation.State.UNKNOWN);
        assertState(predicate, snapshot(Map.of(), 0, Set.of(), Map.of(), Map.of(), 0,
                        new StructureReport("0,64,0", 116, 115, 115, 1, 1)),
                GoalEvaluation.State.UNSATISFIED);
        assertState(predicate, snapshot(Map.of(), 0, Set.of(), Map.of(), Map.of(), 0,
                        new StructureReport("0,64,0", 116, 116, 114, 2, 0)),
                GoalEvaluation.State.SATISFIED);
    }

    @Test
    void fulfillmentSeparatesRetainedInventoryFromCommittedNamedHandoffs() throws IOException {
        // Item instances cannot be created before Minecraft's registries are bootstrapped. Pin
        // this runtime predicate's distinct inventory/receipt branches here; the integration
        // GameTest exercises the behavior with real stone-tool items.
        String predicate = Files.readString(PREDICATE);

        assertTrue(predicate.contains("record Fulfillment(List<Goal.Allocation> allocations,"));
        assertTrue(predicate.contains("if (allocation.delivery()) {"));
        assertTrue(predicate.contains("completedDeliveries.contains(allocation)"));
        assertTrue(predicate.contains("snapshot.inventoryCount(allocation.itemId())"));
    }

    private static GoalSnapshot snapshot(Map<String, Integer> inventory,
                                         int pickaxe,
                                         Set<String> armor,
                                         Map<String, Integer> blocks,
                                         Map<String, Integer> containers,
                                         int food,
                                         StructureReport structure) {
        return new GoalSnapshot(inventory, pickaxe, armor, blocks, containers, food, Optional.ofNullable(structure));
    }

    private static void assertState(GoalPredicate predicate, GoalSnapshot snapshot, GoalEvaluation.State state) {
        assertEquals(state, predicate.evaluate(snapshot).state());
    }
}
