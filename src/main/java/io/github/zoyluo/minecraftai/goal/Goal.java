package io.github.zoyluo.minecraftai.goal;

import java.util.ArrayList;
import java.util.Collections;
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
    /**
     * The completion contract for resource-collection goals.
     *
     * <p>The timed collection mode deliberately has no inventory-count terminal
     * condition.  The executor owns the elapsed-time checkpoint, while this declarative mode
     * keeps the ten-minute collection intent stable across saves and restores.</p>
     */
    enum CollectionMode {
        FIXED_QUOTA("fixed_quota", 0),
        /** A bounded player-facing collection window, currently ten minutes. */
        TIMED_COLLECTION("timed_collection", 20 * 60 * 10);

        private final String persistedValue;
        private final int timeLimitTicks;

        CollectionMode(String persistedValue, int timeLimitTicks) {
            this.persistedValue = persistedValue;
            this.timeLimitTicks = timeLimitTicks;
        }

        /** Stable MissionSpec value; do not serialize enum names as a wire contract. */
        public String persistedValue() {
            return persistedValue;
        }

        /** Zero means that the goal is quota-bound rather than time-bound. */
        public int timeLimitTicks() {
            return timeLimitTicks;
        }

        public boolean isTimedCollection() {
            return timeLimitTicks > 0;
        }

        /** Strict parser for persisted values. Legacy records omit the value entirely. */
        public static CollectionMode fromPersistedValue(String value) {
            // Missions written during the original five-minute implementation must continue
            // with the current ten-minute contract after an upgrade.
            if ("five_minute_collection".equals(value)) {
                return TIMED_COLLECTION;
            }
            for (CollectionMode mode : values()) {
                if (mode.persistedValue.equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("unknown_collection_mode:" + value);
        }
    }

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

    /**
     * Mine an immutable quota of ore drops, or collect them for a bounded duration.
     * {@code initialDropCount} is the number of matching drops held when an incremental public
     * request was accepted; it makes the terminal inventory target explicit without turning that
     * historical inventory into the mission's mining quota.
     *
     * <p>The two-argument constructor deliberately retains the legacy absolute-inventory
     * interpretation: a zero baseline means that {@code count} is both the quota and the required
     * total.  Planner-internal ore prerequisites and existing persisted missions therefore retain
     * their original behavior.</p>
     *
     * <p>For timed collection, {@code count} is retained as a
     * positive compatibility value but is not a completion target. Callers must use
     * {@link #isTimedCollection()} and {@link #timeLimitTicks()} instead of the inventory target
     * when deciding whether the mission is complete.</p>
     */
    record MineOre(Set<Block> ores, int count, int initialDropCount, CollectionMode collectionMode) implements Goal {
        public MineOre(Set<Block> ores, int count) {
            this(ores, count, 0, CollectionMode.FIXED_QUOTA);
        }

        public MineOre(Set<Block> ores, int count, int initialDropCount) {
            this(ores, count, initialDropCount, CollectionMode.FIXED_QUOTA);
        }

        public MineOre {
            ores = ores == null ? Set.of() : Set.copyOf(ores);
            count = Math.max(1, count);
            initialDropCount = Math.max(0, initialDropCount);
            collectionMode = collectionMode == null ? CollectionMode.FIXED_QUOTA : collectionMode;
            if (initialDropCount > Integer.MAX_VALUE - count) {
                throw new IllegalArgumentException("mine_ore_target_overflow");
            }
        }

        /** Start an open-ended ten-minute collection mission from the current ore baseline. */
        public static MineOre timedCollection(Set<Block> ores, int initialDropCount) {
            return new MineOre(ores, 1, initialDropCount, CollectionMode.TIMED_COLLECTION);
        }

        /** Start an open-ended ten-minute collection mission with no prior inventory baseline. */
        public static MineOre timedCollection(Set<Block> ores) {
            return timedCollection(ores, 0);
        }

        public boolean isTimedCollection() {
            return collectionMode.isTimedCollection();
        }

        public int timeLimitTicks() {
            return collectionMode.timeLimitTicks();
        }

        /** The physical inventory target used for the mine-goal postcondition. */
        public int targetDropCount() {
            return initialDropCount + count;
        }

        /**
         * The part of the requested quota evidenced by inventory growth since submission.  This
         * is intentionally capped at the request so an unrelated surplus cannot move a rare-ore
         * service boundary beyond this mission's declared contract.
         */
        public int deliveredFromInitial(int currentDropCount) {
            if (currentDropCount <= initialDropCount) {
                return 0;
            }
            return Math.min(count, currentDropCount - initialDropCount);
        }
    }

    /**
     * Harvest an immutable quota of crop produce, or collect it for a bounded duration.
     * {@code initialProduceCount} captures the matching produce held when an incremental public
     * harvest request was accepted, so the terminal inventory target is explicit without
     * inflating the farming quota.
     *
     * <p>The legacy four-argument constructor keeps its original absolute-inventory behavior by
     * supplying a zero baseline. In timed mode, {@code count} is a positive compatibility value,
     * not a terminal inventory requirement.</p>
     */
    record HarvestCrop(Block crop, Item seed, Item produce, int count, int initialProduceCount,
                       CollectionMode collectionMode) implements Goal {
        public HarvestCrop(Block crop, Item seed, Item produce, int count) {
            this(crop, seed, produce, count, 0, CollectionMode.FIXED_QUOTA);
        }

        public HarvestCrop(Block crop, Item seed, Item produce, int count, int initialProduceCount) {
            this(crop, seed, produce, count, initialProduceCount, CollectionMode.FIXED_QUOTA);
        }

        public HarvestCrop {
            count = Math.max(1, count);
            initialProduceCount = Math.max(0, initialProduceCount);
            collectionMode = collectionMode == null ? CollectionMode.FIXED_QUOTA : collectionMode;
            if (initialProduceCount > Integer.MAX_VALUE - count) {
                throw new IllegalArgumentException("harvest_crop_target_overflow");
            }
        }

        /** Start an open-ended ten-minute crop-collection mission from the current produce baseline. */
        public static HarvestCrop timedCollection(Block crop, Item seed, Item produce, int initialProduceCount) {
            return new HarvestCrop(crop, seed, produce, 1, initialProduceCount,
                    CollectionMode.TIMED_COLLECTION);
        }

        /** Start an open-ended ten-minute crop-collection mission with no prior produce baseline. */
        public static HarvestCrop timedCollection(Block crop, Item seed, Item produce) {
            return timedCollection(crop, seed, produce, 0);
        }

        public boolean isTimedCollection() {
            return collectionMode.isTimedCollection();
        }

        public int timeLimitTicks() {
            return collectionMode.timeLimitTicks();
        }

        /** The physical inventory target used for the harvest-goal postcondition. */
        public int targetProduceCount() {
            return initialProduceCount + count;
        }

        /** The request-owned produce growth, capped to this mission's declared quota. */
        public int deliveredFromInitial(int currentProduceCount) {
            if (currentProduceCount <= initialProduceCount) {
                return 0;
            }
            return Math.min(count, currentProduceCount - initialProduceCount);
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
    record Fulfill(List<Allocation> allocations,
                   Map<Item, Integer> initialItemCounts) implements Goal {
        /**
         * Legacy/internal fulfillment remains an absolute inventory goal.  Public acquisition
         * requests use the two-argument form, whose non-empty baseline is an immutable proof
         * boundary captured when the request was accepted.
         */
        public Fulfill(List<Allocation> allocations) {
            this(allocations, Map.of());
        }

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

            // An empty map deliberately preserves the pre-existing absolute-inventory semantics.
            // A fresh public request, on the other hand, must snapshot every requested item --
            // including items whose baseline is zero -- so it cannot become indistinguishable
            // from a legacy request after serialization or a replan.
            if (initialItemCounts == null || initialItemCounts.isEmpty()) {
                initialItemCounts = Map.of();
            } else {
                Map<Item, Integer> suppliedBaselines = initialItemCounts;
                if (!suppliedBaselines.keySet().equals(aggregateByItem.keySet())) {
                    throw new IllegalArgumentException("fulfillment_baseline_items_must_match_allocations");
                }
                Map<Item, Integer> canonicalBaselines = new LinkedHashMap<>();
                suppliedBaselines.entrySet().stream()
                        .sorted(Comparator.comparing(entry ->
                                BuiltInRegistries.ITEM.getKey(entry.getKey()).toString()))
                        .forEach(entry -> {
                            Item item = Objects.requireNonNull(entry.getKey(), "baseline_item");
                            Integer count = Objects.requireNonNull(entry.getValue(), "baseline_count");
                            if (count < 0) {
                                throw new IllegalArgumentException("negative_fulfillment_baseline");
                            }
                            // Validate the largest possible pre-handoff requirement here rather
                            // than overflowing only when a later replan reaches it.
                            Math.addExact(count, aggregateByItem.get(item));
                            canonicalBaselines.put(item, count);
                        });
                initialItemCounts = Collections.unmodifiableMap(canonicalBaselines);
            }
        }

        /** True when this goal must prove that every requested allocation was newly produced. */
        public boolean isFreshInventoryRequest() {
            return !initialItemCounts.isEmpty();
        }

        /**
         * The same manifest with its freshness boundary moved to what the bot holds right now.  A
         * fresh request that waited behind another mission was snapshotted when it was accepted,
         * but it must be measured from the inventory it STARTS with: whatever the mission ahead of
         * it produced is not "new" for this request, and whatever that mission consumed is not
         * owed back.  A persisted ACTIVE mission is never re-baselined, only a promoted one.
         */
        public Fulfill rebaselined(java.util.function.ToIntFunction<Item> heldCount) {
            if (!isFreshInventoryRequest()) {
                return this;
            }
            Map<Item, Integer> held = new LinkedHashMap<>();
            for (Item item : initialItemCounts.keySet()) {
                int count = Math.max(0, heldCount.applyAsInt(item));
                if (count > Integer.MAX_VALUE - requestedItemCount(item)) {
                    // The planner's int counts cannot hold baseline + request: keep the boundary
                    // the request was accepted with rather than throw on the tick that promotes it.
                    return this;
                }
                held.put(item, count);
            }
            return new Fulfill(allocations, held);
        }

        /** Count held at public-request submission time; zero for legacy absolute goals. */
        public int initialItemCount(Item item) {
            return initialItemCounts.getOrDefault(item, 0);
        }

        /** Total requested quantity of one item across retained and delivery allocations. */
        public int requestedItemCount(Item item) {
            int requested = 0;
            for (Allocation allocation : allocations) {
                if (allocation.item() == item) {
                    requested = Math.addExact(requested, allocation.count());
                }
            }
            return requested;
        }

        /** Fresh quantity currently evidenced above the immutable submission baseline. */
        public int deliveredFromInitial(Item item, int currentItemCount) {
            if (!isFreshInventoryRequest() || currentItemCount <= initialItemCount(item)) {
                return 0;
            }
            return Math.min(requestedItemCount(item), currentItemCount - initialItemCount(item));
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
         *
         * <p>The baseline is deliberately never lowered while a mission runs (no "lowest count
         * seen" ratchet), because a lost stack can come back: the corpse run returns what a death
         * dropped and a chest withdrawal returns what a deposit stored, and a ratchet would then
         * count those old units as fresh and hand them over, which is the bug the baseline exists
         * to prevent.  The price is over-production when baseline units are gone for good (a death
         * whose corpse is never recovered, torches placed or food eaten from the baseline stack).</p>
         */
        public Map<Item, Integer> inventoryRequired(Set<Allocation> completedDeliveries) {
            Set<Allocation> completed = completedDeliveries == null ? Set.of() : Set.copyOf(completedDeliveries);
            Map<Item, Integer> required = new LinkedHashMap<>();
            if (isFreshInventoryRequest()) {
                // Preserve the pre-request stack while satisfying every still-owned allocation.
                // This makes a later GiveItemTask drop only newly produced quantity: after a
                // completed delivery, the baseline remains required but that delivery does not.
                initialItemCounts.forEach((item, count) -> required.put(item, count));
            }
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
