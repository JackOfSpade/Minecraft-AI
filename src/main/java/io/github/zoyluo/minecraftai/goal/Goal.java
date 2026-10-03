package io.github.zoyluo.minecraftai.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public sealed interface Goal permits Goal.HaveItem, Goal.HavePickaxeTier, Goal.MineOre, Goal.HarvestCrop,
        Goal.Armor, Goal.Workstation, Goal.Stockpile, Goal.Food, Goal.Build, Goal.Fulfill {
    record HaveItem(Item item, int count) implements Goal {
        public HaveItem {
            count = Math.max(1, count);
        }
    }

    record HavePickaxeTier(int tier) implements Goal {
        public HavePickaxeTier {
            tier = Math.max(0, tier);
        }
    }

    record MineOre(Set<Block> ores, int count) implements Goal {
        public MineOre {
            ores = ores == null ? Set.of() : Set.copyOf(ores);
            count = Math.max(1, count);
        }
    }

    /** P3: harvest N crops (wheat/carrot/potato). Backward-chained: have a hoe (+seeds) -> till/plant/wait to mature/harvest. */
    record HarvestCrop(Block crop, Item seed, Item produce, int count) implements Goal {
        public HarvestCrop {
            count = Math.max(1, count);
        }
    }

    /** Phase1: gear up -- full armor set + sword (currently iron tier, reuses GoalPlanner.ensureArmor's backward chaining). */
    record Armor() implements Goal {
    }

    /** Phase2: infrastructure -- prepare and place the crafting table/furnace/chest trio (a production + storage base). */
    record Workstation() implements Goal {
    }

    /** Phase3: stockpiling -- acquire count of item, and (best-effort) store it in a nearby chest. */
    record Stockpile(Item item, int count) implements Goal {
        public Stockpile {
            count = Math.max(1, count);
        }
    }

    /** Layer 4 Provisioning: hunt for meat and cook it into cookedCount servings of cooked food (follows GoalPlanner's hunt -> cook loop).
     *  Serves colloquial entry points like "go hunting/go get some food/get some meat" (the provision_food tool). */
    record Food(int cookedCount) implements Goal {
        public Food {
            cookedCount = Math.max(1, cookedCount);
        }
    }

    /** Build goal: construct according to a blueprint (the "build a house" one-line full chain: auto-gather materials -> build), blueprint names like small_hut/hut_5x5. */
    record Build(String blueprint) implements Goal {
    }

    /**
     * One requested allocation of an item.  An empty recipient means the bot must retain the
     * item; a non-empty recipient means the item is to be handed to that named player after all
     * production prerequisites have completed.
     */
    record Allocation(Item item, int count, String recipient) {
        public Allocation {
            Objects.requireNonNull(item, "item");
            if (count <= 0) {
                throw new IllegalArgumentException("allocation_count_must_be_positive");
            }
            recipient = recipient == null ? "" : recipient.trim();
            if (!recipient.isBlank() && (recipient.length() > 16
                    || !recipient.matches("[A-Za-z0-9_]+"))) {
                throw new IllegalArgumentException("invalid_allocation_recipient");
            }
        }

        public boolean delivery() {
            return !recipient.isBlank();
        }

        /** Stable registry-backed identity used for canonical ordering and persisted receipts. */
        public String itemId() {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
    }

    /**
     * A general compound fulfillment objective.  It is deliberately declarative: the language
     * model chooses any finite set of requested item allocations, while the regular recipe
     * planner derives their shared prerequisites.  Duplicate item/recipient allocations are
     * merged so repeated model entries cannot create competing handoff obligations.
     */
    record Fulfill(List<Allocation> allocations) implements Goal {
        public Fulfill {
            if (allocations == null || allocations.isEmpty()) {
                throw new IllegalArgumentException("fulfillment_requires_allocations");
            }
            Map<AllocationKey, Integer> merged = new LinkedHashMap<>();
            // The planner's virtual inventory is int-counted. Validate the aggregate physical
            // requirement up front rather than admitting a manifest that can overflow only
            // halfway through dependency planning.
            Map<Item, Integer> aggregateByItem = new LinkedHashMap<>();
            for (Allocation allocation : allocations) {
                Allocation value = Objects.requireNonNull(allocation, "allocation");
                AllocationKey key = new AllocationKey(value.item(), value.recipient());
                merged.merge(key, value.count(), Math::addExact);
                aggregateByItem.merge(value.item(), value.count(), Math::addExact);
            }
            List<Allocation> canonical = new ArrayList<>(merged.size());
            merged.entrySet().stream()
                    .sorted(Comparator.comparing((Map.Entry<AllocationKey, Integer> entry) ->
                                    BuiltInRegistries.ITEM.getKey(entry.getKey().item()).toString())
                            .thenComparing(entry -> entry.getKey().recipient()))
                    .forEach(entry -> canonical.add(new Allocation(
                            entry.getKey().item(), entry.getValue(), entry.getKey().recipient())));
            allocations = List.copyOf(canonical);
        }

        /** The allocations the bot itself must still carry after any handoffs. */
        public List<Allocation> retained() {
            return allocations.stream().filter(allocation -> !allocation.delivery()).toList();
        }

        /** The allocations that must be handed to another player. */
        public List<Allocation> deliveries() {
            return allocations.stream().filter(Allocation::delivery).toList();
        }

        /**
         * The physical inventory required before the remaining handoffs can begin.  Receipted
         * handoffs are intentionally excluded, so a later replan never crafts and drops them
         * again.
         */
        public Map<Item, Integer> inventoryRequired(Set<Allocation> completedDeliveries) {
            Set<Allocation> completed = completedDeliveries == null ? Set.of() : Set.copyOf(completedDeliveries);
            Map<Item, Integer> required = new LinkedHashMap<>();
            for (Allocation allocation : allocations) {
                if (!allocation.delivery() || !completed.contains(allocation)) {
                    required.merge(allocation.item(), allocation.count(), Math::addExact);
                }
            }
            return Map.copyOf(required);
        }

        private record AllocationKey(Item item, String recipient) {
        }
    }
}
