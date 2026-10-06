package io.github.zoyluo.minecraftai.goal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;

public sealed interface GoalPredicate permits GoalPredicate.ItemCount,
        GoalPredicate.PickaxeTier,
        GoalPredicate.AnyItemCount,
        GoalPredicate.TimedCollection,
        GoalPredicate.ArmorSet,
        GoalPredicate.Workstation,
        GoalPredicate.Stockpile,
        GoalPredicate.FoodUnits,
        GoalPredicate.Structure,
        GoalPredicate.Fulfillment {
    GoalEvaluation evaluate(GoalSnapshot snapshot);

    record ItemCount(String itemId, int count) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = snapshot.inventoryCount(itemId);
            return GoalEvaluation.count(actual, count, Map.of("item", itemId, "actual", String.valueOf(actual)),
                    "missing_item:" + itemId);
        }
    }

    record PickaxeTier(int tier) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = snapshot.bestPickaxeTier();
            return GoalEvaluation.count(actual, tier, Map.of("best_pickaxe_tier", String.valueOf(actual)),
                    "pickaxe_tier_below:" + tier);
        }
    }

    record AnyItemCount(Set<String> itemIds, int count, String evidenceKey) implements GoalPredicate {
        public AnyItemCount {
            itemIds = Set.copyOf(itemIds);
        }

        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = itemIds.stream().mapToInt(snapshot::inventoryCount).sum();
            return GoalEvaluation.count(actual, count,
                    Map.of(evidenceKey, String.valueOf(actual), "items", String.join(",", itemIds)),
                    "insufficient_" + evidenceKey);
        }
    }

    /**
     * A collection window is intentionally not satisfied by inventory alone.  It records only
     * mission-owned growth above the captured baseline, while the executor completes it when its
     * timed physical task reaches the window boundary.  This prevents a preexisting stack from
     * turning a player request to gather into an immediate no-op.
     */
    record TimedCollection(Set<String> itemIds, int initialCount, String evidenceKey) implements GoalPredicate {
        public TimedCollection {
            itemIds = Set.copyOf(itemIds);
            initialCount = Math.max(0, initialCount);
        }

        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = itemIds.stream().mapToInt(snapshot::inventoryCount).sum();
            int collected = Math.max(0, actual - initialCount);
            return new GoalEvaluation(GoalEvaluation.State.UNSATISFIED, collected, 1,
                    Map.of(evidenceKey, String.valueOf(actual),
                            "baseline", String.valueOf(initialCount),
                            "collected", String.valueOf(collected),
                            "items", String.join(",", itemIds)),
                    List.of("collection_window_active"));
        }
    }

    record ArmorSet(Set<String> requiredCapabilities) implements GoalPredicate {
        public ArmorSet {
            requiredCapabilities = Set.copyOf(requiredCapabilities);
        }

        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int matched = 0;
            java.util.ArrayList<String> unmet = new java.util.ArrayList<>();
            for (String capability : requiredCapabilities) {
                if (snapshot.equipmentCapabilities().contains(capability)) {
                    matched++;
                } else {
                    unmet.add(capability);
                }
            }
            return new GoalEvaluation(unmet.isEmpty() ? GoalEvaluation.State.SATISFIED : GoalEvaluation.State.UNSATISFIED,
                    matched, requiredCapabilities.size(), Map.of("capabilities", String.join(",", snapshot.equipmentCapabilities())), unmet);
        }
    }

    record Workstation() implements GoalPredicate {
        private static final List<String> REQUIRED = List.of(
                "minecraft:crafting_table", "minecraft:furnace", "minecraft:chest");

        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int matched = 0;
            java.util.ArrayList<String> unmet = new java.util.ArrayList<>();
            Map<String, String> evidence = new LinkedHashMap<>();
            for (String block : REQUIRED) {
                int count = snapshot.nearbyBlockCount(block);
                evidence.put(block, String.valueOf(count));
                if (count > 0) {
                    matched++;
                } else {
                    unmet.add(block);
                }
            }
            return new GoalEvaluation(unmet.isEmpty() ? GoalEvaluation.State.SATISFIED : GoalEvaluation.State.UNSATISFIED,
                    matched, REQUIRED.size(), evidence, unmet);
        }
    }

    record Stockpile(String itemId, int count) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = snapshot.containerCount(itemId);
            return GoalEvaluation.count(actual, count,
                    Map.of("container_item", itemId, "container_count", String.valueOf(actual)),
                    "container_missing_item:" + itemId);
        }
    }

    record FoodUnits(int count) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            return GoalEvaluation.count(snapshot.foodUnits(), count,
                    Map.of("food_units", String.valueOf(snapshot.foodUnits())), "insufficient_cooked_food");
        }
    }

    record Structure(String blueprint) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            if (snapshot.structure().isEmpty()) {
                return GoalEvaluation.unknown("structure_binding_missing:" + blueprint);
            }
            StructureReport report = snapshot.structure().get();
            boolean satisfied = report.expected() > 0 && report.mismatched() == 0 && report.matched() == report.expected();
            return new GoalEvaluation(satisfied ? GoalEvaluation.State.SATISFIED : GoalEvaluation.State.UNSATISFIED,
                    report.matched(), report.expected(),
                    Map.of("blueprint", blueprint,
                            "anchor", report.anchor(),
                            "expected", String.valueOf(report.expected()),
                            "matched", String.valueOf(report.matched()),
                            "placed", String.valueOf(report.placed()),
                            "skipped", String.valueOf(report.skipped()),
                            "mismatched", String.valueOf(report.mismatched())),
                    satisfied ? List.of() : List.of("structure_mismatch:" + report.mismatched()));
        }
    }

    /**
     * Verifies both the inventory allocations retained by the bot and the exact delivery
     * receipts committed by the mission.  A receipt means the existing GiveItemTask proved the
     * requested count left the bot's inventory while it was beside the named player.
     */
    record Fulfillment(List<Goal.Allocation> allocations,
                       Set<Goal.Allocation> completedDeliveries,
                       Map<Item, Integer> initialItemCounts) implements GoalPredicate {
        /** Legacy/internal fulfillment remains an absolute inventory predicate. */
        public Fulfillment(List<Goal.Allocation> allocations,
                           Set<Goal.Allocation> completedDeliveries) {
            this(allocations, completedDeliveries, Map.of());
        }

        /** Preserve the immutable fresh-request baseline carried by the declarative goal. */
        public Fulfillment(Goal.Fulfill fulfill,
                           Set<Goal.Allocation> completedDeliveries) {
            this(fulfill == null ? List.of() : fulfill.allocations(), completedDeliveries,
                    fulfill == null ? Map.of() : fulfill.initialItemCounts());
        }

        public Fulfillment {
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
            completedDeliveries = completedDeliveries == null
                    ? Set.of() : Set.copyOf(completedDeliveries);
            initialItemCounts = initialItemCounts == null ? Map.of() : Map.copyOf(initialItemCounts);
        }

        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int matched = 0;
            int required = 0;
            List<String> unmet = new java.util.ArrayList<>();
            Map<String, String> evidence = new LinkedHashMap<>();
            for (Goal.Allocation allocation : allocations) {
                int count = allocation.count();
                required = Math.addExact(required, count);
                if (allocation.delivery()) {
                    boolean delivered = completedDeliveries.contains(allocation);
                    evidence.put("delivery." + allocation.recipient() + "." + allocation.itemId(),
                            delivered ? String.valueOf(count) : "0");
                    if (delivered) {
                        matched = Math.addExact(matched, count);
                    } else {
                        unmet.add("undelivered_item:" + allocation.itemId()
                                + ":recipient=" + allocation.recipient());
                    }
                    continue;
                }
                int actual = snapshot.inventoryCount(allocation.itemId());
                int baseline = initialItemCounts.getOrDefault(allocation.item(), 0);
                int requiredActual = Math.addExact(baseline, count);
                int fresh = Math.max(0, actual - baseline);
                evidence.put("inventory." + allocation.itemId(), String.valueOf(actual));
                if (!initialItemCounts.isEmpty()) {
                    evidence.put("inventory." + allocation.itemId() + ".baseline", String.valueOf(baseline));
                    evidence.put("inventory." + allocation.itemId() + ".fresh", String.valueOf(fresh));
                }
                matched = Math.addExact(matched, Math.min(fresh, count));
                if (actual < requiredActual) {
                    unmet.add("missing_item:" + allocation.itemId());
                }
            }
            return new GoalEvaluation(unmet.isEmpty()
                    ? GoalEvaluation.State.SATISFIED : GoalEvaluation.State.UNSATISFIED,
                    matched, required, evidence, unmet);
        }
    }
}
