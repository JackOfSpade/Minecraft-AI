package io.github.zoyluo.minecraftai.persist;

import io.github.zoyluo.minecraftai.goal.Goal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Stable, declarative Goal representation. No Task, phase, path, entity, or world object is serialized. */
public record MissionSpec(String type, Map<String, String> params, List<String> values,
                          ExecutionMode executionMode) {
    /**
     * Execution policy is deliberately persisted beside the declarative goal, rather than inferred
     * from a chat turn after a restart.  It never changes the goal's recipe, allocation, or
     * postcondition; it only controls whether a safe completed stage needs a fresh strategy
     * decision before the executor dispatches the next one.
     */
    public enum ExecutionMode {
        STANDARD,
        ADAPTIVE
    }

    public MissionSpec {
        type = type == null ? "" : type;
        params = params == null ? Map.of() : Map.copyOf(params);
        values = values == null ? List.of() : List.copyOf(values);
        executionMode = executionMode == null ? ExecutionMode.STANDARD : executionMode;
    }

    /** Compatibility constructor for legacy callers and persisted records without a policy. */
    public MissionSpec(String type, Map<String, String> params, List<String> values) {
        this(type, params, values, ExecutionMode.STANDARD);
    }

    public static MissionSpec fromGoal(Goal goal) {
        return fromGoal(goal, ExecutionMode.STANDARD);
    }

    public static MissionSpec fromGoal(Goal goal, ExecutionMode executionMode) {
        Map<String, String> params = new LinkedHashMap<>();
        List<String> values = List.of();
        String type;
        switch (goal) {
            case Goal.HaveItem g -> {
                type = "have_item";
                params.put("item", BuiltInRegistries.ITEM.getKey(g.item()).toString());
                params.put("count", String.valueOf(g.count()));
            }
            case Goal.HavePickaxeTier g -> {
                type = "have_pickaxe_tier";
                params.put("tier", String.valueOf(g.tier()));
            }
            case Goal.MineOre g -> {
                type = "mine_ore";
                params.put("count", String.valueOf(g.count()));
                // Preserve the captured inventory baseline: without it, a restarted public
                // "mine one more" request could regress into an already-satisfied absolute goal.
                params.put("initial_drop_count", String.valueOf(g.initialDropCount()));
                putTimedCollectionMode(params, g.collectionMode());
                values = g.ores().stream().map(block -> BuiltInRegistries.BLOCK.getKey(block).toString()).sorted().toList();
            }
            case Goal.HarvestCrop g -> {
                type = "harvest_crop";
                params.put("crop", BuiltInRegistries.BLOCK.getKey(g.crop()).toString());
                params.put("seed", BuiltInRegistries.ITEM.getKey(g.seed()).toString());
                params.put("produce", BuiltInRegistries.ITEM.getKey(g.produce()).toString());
                params.put("count", String.valueOf(g.count()));
                params.put("initial_produce_count", String.valueOf(g.initialProduceCount()));
                putTimedCollectionMode(params, g.collectionMode());
            }
            case Goal.Armor ignored -> type = "armor";
            case Goal.Workstation ignored -> type = "workstation";
            case Goal.Stockpile g -> {
                type = "stockpile";
                params.put("item", BuiltInRegistries.ITEM.getKey(g.item()).toString());
                params.put("count", String.valueOf(g.count()));
            }
            case Goal.Food g -> {
                type = "food";
                params.put("count", String.valueOf(g.cookedCount()));
            }
            case Goal.Build g -> {
                type = "build";
                params.put("blueprint", g.blueprint());
            }
            case Goal.Fulfill g -> {
                type = "fulfill";
                params.put("schema", "1");
                List<String> encoded = new ArrayList<>(g.allocations().size() * 3);
                for (Goal.Allocation allocation : g.allocations()) {
                    encoded.add(allocation.itemId());
                    encoded.add(String.valueOf(allocation.count()));
                    encoded.add(allocation.recipient());
                }
                values = List.copyOf(encoded);
            }
        }
        return new MissionSpec(type, params, values, executionMode);
    }

    public Optional<Goal> toGoal() {
        try {
            return Optional.of(switch (type) {
                case "have_item" -> new Goal.HaveItem(item("item"), integer("count"));
                case "have_pickaxe_tier" -> new Goal.HavePickaxeTier(integer("tier"));
                case "mine_ore" -> new Goal.MineOre(values.stream()
                        .map(Identifier::parse)
                        .map(id -> BuiltInRegistries.BLOCK.getOptional(id).orElseThrow())
                        .collect(java.util.stream.Collectors.toSet()), integer("count"),
                        nonNegativeIntegerOrDefault("initial_drop_count", 0), collectionModeOrDefault());
                case "harvest_crop" -> new Goal.HarvestCrop(
                        block("crop"), item("seed"), item("produce"), integer("count"),
                        nonNegativeIntegerOrDefault("initial_produce_count", 0), collectionModeOrDefault());
                case "armor" -> new Goal.Armor();
                case "workstation" -> new Goal.Workstation();
                case "stockpile" -> new Goal.Stockpile(item("item"), integer("count"));
                case "food" -> new Goal.Food(integer("count"));
                case "build" -> new Goal.Build(required("blueprint"));
                case "fulfill" -> fulfill();
                default -> throw new IllegalArgumentException("unknown_mission_type:" + type);
            });
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private net.minecraft.world.item.Item item(String key) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(required(key))).orElseThrow();
    }

    private net.minecraft.world.level.block.Block block(String key) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(required(key))).orElseThrow();
    }

    private int integer(String key) {
        return Integer.parseInt(required(key));
    }

    /** Legacy mission records predate incremental mine/crop baselines and therefore mean zero. */
    private int nonNegativeIntegerOrDefault(String key, int defaultValue) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("negative_mission_param:" + key);
        }
        return parsed;
    }

    /**
     * Older mission records have no collection mode and remain fixed-quota goals.  Persist the
     * non-default timed value only, keeping fixed-count mission records wire-compatible with the
     * pre-timebox schema.
     */
    private static void putTimedCollectionMode(Map<String, String> params, Goal.CollectionMode mode) {
        if (mode != null && mode.isTimedCollection()) {
            params.put("collection_mode", mode.persistedValue());
        }
    }

    private Goal.CollectionMode collectionModeOrDefault() {
        String value = params.get("collection_mode");
        return value == null || value.isBlank()
                ? Goal.CollectionMode.FIXED_QUOTA
                : Goal.CollectionMode.fromPersistedValue(value);
    }

    /** Strict triplet encoding keeps a persisted compound request declarative and replay-safe. */
    private Goal.Fulfill fulfill() {
        if (!params.keySet().equals(Set.of("schema"))
                || !"1".equals(params.get("schema"))
                || values.isEmpty() || values.size() % 3 != 0) {
            throw new IllegalArgumentException("invalid_fulfill_mission_spec");
        }
        List<Goal.Allocation> allocations = new ArrayList<>(values.size() / 3);
        for (int index = 0; index < values.size(); index += 3) {
            String itemId = values.get(index);
            Identifier identifier = Identifier.tryParse(itemId);
            net.minecraft.world.item.Item item = identifier == null
                    ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
            String countText = values.get(index + 1);
            int count = Integer.parseInt(countText);
            if (item == null || !itemId.equals(BuiltInRegistries.ITEM.getKey(item).toString())
                    || count <= 0 || !countText.equals(String.valueOf(count))) {
                throw new IllegalArgumentException("invalid_fulfill_allocation");
            }
            allocations.add(new Goal.Allocation(item, count, values.get(index + 2)));
        }
        Goal.Fulfill decoded = new Goal.Fulfill(allocations);
        // The stored representation must already be canonical.  Otherwise an altered or
        // duplicate manifest could make a restart describe a different authorization scope.
        List<String> canonical = new ArrayList<>(decoded.allocations().size() * 3);
        for (Goal.Allocation allocation : decoded.allocations()) {
            canonical.add(allocation.itemId());
            canonical.add(String.valueOf(allocation.count()));
            canonical.add(allocation.recipient());
        }
        if (!canonical.equals(values)) {
            throw new IllegalArgumentException("noncanonical_fulfill_mission_spec");
        }
        return decoded;
    }

    private String required(String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_mission_param:" + key);
        }
        return value;
    }
}
