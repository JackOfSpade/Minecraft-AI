package io.github.zoyluo.minecraftai.goal;

import static io.github.zoyluo.minecraftai.goal.GoalExecutor.DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT;
import static io.github.zoyluo.minecraftai.goal.GoalExecutor.MAX_POSTCONDITION_REPLANS;
import static io.github.zoyluo.minecraftai.goal.GoalExecutor.MAX_SETTLED_SERVICE_TOMBSTONES;
import static io.github.zoyluo.minecraftai.goal.GoalExecutor.MAX_SKIPPED_TARGET_RECEIPTS;
import static io.github.zoyluo.minecraftai.goal.GoalExecutor.MAX_SKIPPED_TARGET_TEXT_BYTES;

import io.github.zoyluo.minecraftai.goal.GoalExecutor.GoalBatchCheckpoint;
import io.github.zoyluo.minecraftai.goal.GoalExecutor.PostconditionRepairCheckpoint;
import io.github.zoyluo.minecraftai.goal.GoalExecutor.ReplanSnapshot;
import io.github.zoyluo.minecraftai.goal.GoalExecutor.SkippedTargetReceipt;
import io.github.zoyluo.minecraftai.persist.MissionRecord;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.task.HuntSearchCursor;
import io.github.zoyluo.minecraftai.task.MiningServiceTask;
import io.github.zoyluo.minecraftai.task.OreDigTask;
import io.github.zoyluo.minecraftai.task.ServicePolicy;
import io.github.zoyluo.minecraftai.task.ServiceProfile;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * S8: the mostly-pure checkpoint encode/decode cluster extracted from {@link GoalExecutor} --
 * every method here takes and returns {@code Map<String, String>}/records and touches no
 * {@code ActivePlan} mutable field directly. GoalExecutor still owns {@code checkpoint(ActivePlan)}
 * (it just reads getters and delegates per-field encoding here) and every method that needs the
 * bot/world/mission runtime (restoreSeed, restoreCheckpointValidationFailure, the live/persisted
 * service-authority lookups, and the rare-resource mission-budget helpers).
 */
final class GoalCheckpointCodec {
    private static final String BATCH_CHECKPOINT_PREFIX = "batch_checkpoint.";
    private static final Set<String> BATCH_CHECKPOINT_KEYS = Set.of(
            "schema", "awaiting_player", "completed_at_checkpoint", "step_limit");
    private static final int BATCH_CHECKPOINT_SCHEMA = 1;
    private static final int MAX_POSTCONDITION_FINGERPRINT_BYTES = 32_768;
    private static final String POSTCONDITION_REPLANS_KEY =
            "postcondition_replans";
    private static final String POSTCONDITION_LAST_MATCHED_KEY =
            "postcondition_last_matched";
    private static final String POSTCONDITION_FINGERPRINT_KEY =
            "postcondition_fingerprint";
    private static final String POSTCONDITION_PREFIX = "postcondition_";
    private static final Set<String> POSTCONDITION_REPAIR_KEYS = Set.of(
            POSTCONDITION_REPLANS_KEY,
            POSTCONDITION_LAST_MATCHED_KEY,
            POSTCONDITION_FINGERPRINT_KEY);
    private static final String SKIPPED_TARGET_PREFIX = "skipped_target.";
    private static final Set<String> SKIPPED_TARGET_ENTRY_KEYS = Set.of(
            "kind", "item", "count", "block", "ores", "input", "output",
            "pos", "tag_present", "tag", "best_effort", "reason");
    private static final Set<String> LEGACY_REPLAN_SNAPSHOT_KEYS = Set.of(
            "snap_steps", "snap_target", "snap_x", "snap_y", "snap_z");
    private static final Set<String> MODERN_REPLAN_SNAPSHOT_KEYS = Set.of(
            "snap_steps", "snap_target", "snap_x", "snap_y", "snap_z",
            "snap_dimension", "snap_hunt_raw_meat", "snap_hunt_visited_sectors");
    private static final String HUNT_CURSOR_PREFIX = "hunt.";
    private static final Set<String> SETTLED_SERVICE_ENTRY_KEYS = Set.of(
            "schema", "descriptor", "dimension", "work_face", "pocket_axis",
            "pocket_a", "pocket_b", "failure");

    private GoalCheckpointCodec() {
    }

    static Map<String, String> activeTaskCheckpoint(
            MissionRuntimeRecord runtime) {
        MissionRecord active = runtime == null ? null : runtime.active();
        if (active == null || active.checkpoint() == null) {
            return Map.of();
        }
        Map<String, String> task = new java.util.LinkedHashMap<>();
        active.checkpoint().forEach((key, value) -> {
            if (key.startsWith("task.") && key.length() > "task.".length()) {
                task.put(key.substring("task.".length()), value);
            }
        });
        return Map.copyOf(task);
    }

    static Map<String, String> encodeBatchCheckpoint(
            GoalBatchCheckpoint checkpoint) {
        if (checkpoint == null || !checkpoint.persisted()) {
            return Map.of();
        }
        if (checkpoint.completedAtCheckpoint() < checkpoint.stepLimit()
                || checkpoint.stepLimit() != DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT) {
            throw new IllegalArgumentException("invalid_goal_batch_checkpoint");
        }
        return Map.of(
                BATCH_CHECKPOINT_PREFIX + "schema", String.valueOf(BATCH_CHECKPOINT_SCHEMA),
                BATCH_CHECKPOINT_PREFIX + "awaiting_player", "true",
                BATCH_CHECKPOINT_PREFIX + "completed_at_checkpoint",
                String.valueOf(checkpoint.completedAtCheckpoint()),
                BATCH_CHECKPOINT_PREFIX + "step_limit", String.valueOf(checkpoint.stepLimit()));
    }

    /**
     * A batch checkpoint is deliberately small: at a safe boundary the durable goal, inventory,
     * world state, and ordinary task cursor are the source of truth.  Storing a stale symbolic
     * task list would make a restart craft or gather things the player has already supplied.
     */
    static Optional<GoalBatchCheckpoint> decodeBatchCheckpoint(
            Map<String, String> checkpoint) {
        Map<String, String> source = checkpoint == null ? Map.of() : checkpoint;
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        boolean present = false;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("batch_checkpoint".equals(key) || BATCH_CHECKPOINT_PREFIX.equals(key)) {
                return Optional.empty();
            }
            if (key != null && key.startsWith(BATCH_CHECKPOINT_PREFIX)) {
                present = true;
                String nested = key.substring(BATCH_CHECKPOINT_PREFIX.length());
                if (nested.isBlank() || entry.getValue() == null
                        || values.put(nested, entry.getValue()) != null) {
                    return Optional.empty();
                }
            }
        }
        if (!present) {
            return Optional.of(GoalBatchCheckpoint.legacy());
        }
        if (!values.keySet().equals(BATCH_CHECKPOINT_KEYS)
                || !String.valueOf(BATCH_CHECKPOINT_SCHEMA).equals(values.get("schema"))) {
            return Optional.empty();
        }
        Optional<Boolean> awaiting = decodeCanonicalBoolean(values.get("awaiting_player"));
        OptionalInt completed = canonicalNonNegativeInt(values.get("completed_at_checkpoint"));
        OptionalInt limit = canonicalNonNegativeInt(values.get("step_limit"));
        if (awaiting.isEmpty() || !awaiting.orElseThrow()
                || completed.isEmpty() || limit.isEmpty()
                || limit.getAsInt() != DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT
                || completed.getAsInt() < limit.getAsInt()) {
            return Optional.empty();
        }
        GoalBatchCheckpoint decoded = new GoalBatchCheckpoint(
                true, completed.getAsInt(), limit.getAsInt());
        return encodeBatchCheckpoint(decoded).entrySet().stream().allMatch(entry ->
                entry.getValue().equals(source.get(entry.getKey())))
                ? Optional.of(decoded) : Optional.empty();
    }

    static Map<String, String> encodeHuntSearchCursorNamespace(HuntSearchCursor cursor) {
        java.util.LinkedHashMap<String, String> encoded = new java.util.LinkedHashMap<>();
        cursor.encode().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> encoded.put(
                        HUNT_CURSOR_PREFIX + entry.getKey(), entry.getValue()));
        return Map.copyOf(encoded);
    }

    /**
     * A missing namespace is legacy only when no hunt watermark exists. Any modern watermark
     * without its cursor, or any present but partial, unknown, or malformed namespace, fails closed
     * so a restart cannot erase factual search history.
     */
    static Optional<HuntSearchCursor> decodeHuntSearchCursorNamespace(
            Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return Optional.of(HuntSearchCursor.initial());
        }
        java.util.LinkedHashMap<String, String> encoded = new java.util.LinkedHashMap<>();
        boolean present = false;
        for (Map.Entry<String, String> entry : checkpoint.entrySet()) {
            String key = entry.getKey();
            if ("hunt".equals(key) || "hunt.".equals(key)) {
                return Optional.empty();
            }
            if (key != null && key.startsWith(HUNT_CURSOR_PREFIX)) {
                present = true;
                String nested = key.substring(HUNT_CURSOR_PREFIX.length());
                if (nested.isBlank() || entry.getValue() == null
                        || encoded.put(nested, entry.getValue()) != null) {
                    return Optional.empty();
                }
            }
        }
        return present
                ? HuntSearchCursor.decode(encoded)
                : hasAnyHuntReplanWatermark(checkpoint)
                ? Optional.empty()
                : Optional.of(HuntSearchCursor.initial());
    }

    static Map<String, String> encodeSkippedTargetReceipts(
            List<SkippedTargetReceipt> receipts) {
        List<SkippedTargetReceipt> values =
                receipts == null ? List.of() : List.copyOf(receipts);
        if (values.size() > MAX_SKIPPED_TARGET_RECEIPTS) {
            throw new IllegalArgumentException("too many skipped target receipts");
        }
        java.util.LinkedHashMap<String, String> encoded = new java.util.LinkedHashMap<>();
        encoded.put(SKIPPED_TARGET_PREFIX + "schema", "1");
        encoded.put(SKIPPED_TARGET_PREFIX + "count", String.valueOf(values.size()));
        for (int index = 0; index < values.size(); index++) {
            String prefix = SKIPPED_TARGET_PREFIX + String.format("%02d.", index);
            encodeSkippedTargetReceipt(values.get(index)).forEach(
                    (key, value) -> encoded.put(prefix + key, value));
        }
        return Map.copyOf(encoded);
    }

    /**
     * Missing is the sole legacy representation. Once present, the bounded collection and every
     * target field are exact-key and canonical so a damaged receipt cannot suppress another step.
     */
    static Optional<List<SkippedTargetReceipt>> decodeSkippedTargetReceipts(
            Map<String, String> checkpoint) {
        Map<String, String> source = checkpoint == null ? Map.of() : checkpoint;
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        boolean present = false;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("skipped_target".equals(key) || SKIPPED_TARGET_PREFIX.equals(key)) {
                return Optional.empty();
            }
            if (key != null && key.startsWith(SKIPPED_TARGET_PREFIX)) {
                present = true;
                String nested = key.substring(SKIPPED_TARGET_PREFIX.length());
                if (nested.isBlank() || entry.getValue() == null
                        || values.put(nested, entry.getValue()) != null) {
                    return Optional.empty();
                }
            }
        }
        if (!present) {
            return Optional.of(List.of());
        }
        try {
            if (!"1".equals(values.get("schema"))) {
                return Optional.empty();
            }
            OptionalInt decodedCount = canonicalNonNegativeInt(values.get("count"));
            if (decodedCount.isEmpty()
                    || decodedCount.getAsInt() > MAX_SKIPPED_TARGET_RECEIPTS) {
                return Optional.empty();
            }
            int count = decodedCount.getAsInt();
            Set<String> expected = new HashSet<>();
            expected.add("schema");
            expected.add("count");
            for (int index = 0; index < count; index++) {
                String prefix = String.format("%02d.", index);
                for (String key : SKIPPED_TARGET_ENTRY_KEYS) {
                    expected.add(prefix + key);
                }
            }
            if (!values.keySet().equals(expected)) {
                return Optional.empty();
            }
            List<SkippedTargetReceipt> decoded = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String prefix = String.format("%02d.", index);
                java.util.LinkedHashMap<String, String> entry =
                        new java.util.LinkedHashMap<>();
                for (String key : SKIPPED_TARGET_ENTRY_KEYS) {
                    entry.put(key, values.get(prefix + key));
                }
                decoded.add(decodeSkippedTargetReceipt(entry).orElseThrow());
            }
            return Optional.of(List.copyOf(decoded));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Map<String, String> encodeSkippedTargetReceipt(
            SkippedTargetReceipt receipt) {
        GoalStep step = java.util.Objects.requireNonNull(receipt, "receipt").step();
        String tag = step.tag();
        return Map.ofEntries(
                Map.entry("kind", step.kind().name()),
                Map.entry("item", encodeRegistryItem(step.item())),
                Map.entry("count", String.valueOf(step.count())),
                Map.entry("block", encodeRegistryBlock(step.block())),
                Map.entry("ores", encodeRegistryBlocks(step.ores())),
                Map.entry("input", encodeRegistryItem(step.input())),
                Map.entry("output", encodeRegistryItem(step.output())),
                Map.entry("pos", step.pos() == null ? "" : encodePos(step.pos())),
                Map.entry("tag_present", String.valueOf(tag != null)),
                Map.entry("tag", tag == null ? "" : encodeCanonicalText(tag)),
                Map.entry("best_effort", String.valueOf(step.bestEffort())),
                Map.entry("reason", encodeCanonicalText(receipt.reason())));
    }

    private static Optional<SkippedTargetReceipt> decodeSkippedTargetReceipt(
            Map<String, String> values) {
        if (values == null || !values.keySet().equals(SKIPPED_TARGET_ENTRY_KEYS)) {
            return Optional.empty();
        }
        try {
            GoalStep.Kind kind = GoalStep.Kind.valueOf(values.get("kind"));
            OptionalInt count = canonicalNonNegativeInt(values.get("count"));
            if (count.isEmpty()) {
                return Optional.empty();
            }
            Item item = decodeRegistryItem(values.get("item"));
            Block block = decodeRegistryBlock(values.get("block"));
            Set<Block> ores = decodeRegistryBlocks(values.get("ores"));
            Item input = decodeRegistryItem(values.get("input"));
            Item output = decodeRegistryItem(values.get("output"));
            BlockPos pos = values.get("pos").isEmpty()
                    ? null : decodePos(values.get("pos")).orElseThrow();
            if (pos != null && !encodePos(pos).equals(values.get("pos"))) {
                return Optional.empty();
            }
            boolean tagPresent = decodeCanonicalBoolean(
                    values.get("tag_present")).orElseThrow();
            String decodedTag = decodeCanonicalText(values.get("tag")).orElseThrow();
            if (!tagPresent && !decodedTag.isEmpty()) {
                return Optional.empty();
            }
            String tag = tagPresent ? decodedTag : null;
            boolean bestEffort = decodeCanonicalBoolean(
                    values.get("best_effort")).orElseThrow();
            String reason = decodeCanonicalText(values.get("reason")).orElseThrow();
            GoalStep step = new GoalStep(
                    kind, item, count.getAsInt(), block, ores,
                    input, output, pos, tag, bestEffort);
            if (step.count() != count.getAsInt()) {
                return Optional.empty();
            }
            SkippedTargetReceipt receipt = new SkippedTargetReceipt(step, reason);
            return encodeSkippedTargetReceipt(receipt).equals(values)
                    ? Optional.of(receipt) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String encodeRegistryItem(Item item) {
        return item == null ? "" : Registries.ITEM.getId(item).toString();
    }

    private static Item decodeRegistryItem(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("missing item id");
        }
        if (encoded.isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(encoded);
        Item item = id == null ? null : Registries.ITEM.getOptionalValue(id).orElse(null);
        if (item == null || !id.toString().equals(encoded)
                || !Registries.ITEM.getId(item).toString().equals(encoded)) {
            throw new IllegalArgumentException("invalid item id");
        }
        return item;
    }

    private static String encodeRegistryBlock(Block block) {
        return block == null ? "" : Registries.BLOCK.getId(block).toString();
    }

    private static Block decodeRegistryBlock(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("missing block id");
        }
        if (encoded.isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(encoded);
        Block block = id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
        if (block == null || !id.toString().equals(encoded)
                || !Registries.BLOCK.getId(block).toString().equals(encoded)) {
            throw new IllegalArgumentException("invalid block id");
        }
        return block;
    }

    private static String encodeRegistryBlocks(Set<Block> blocks) {
        return (blocks == null ? Set.<Block>of() : blocks).stream()
                .map(GoalCheckpointCodec::encodeRegistryBlock)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static Set<Block> decodeRegistryBlocks(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("missing block set");
        }
        if (encoded.isEmpty()) {
            return Set.of();
        }
        java.util.LinkedHashSet<Block> blocks = new java.util.LinkedHashSet<>();
        for (String value : encoded.split(",", -1)) {
            Block block = decodeRegistryBlock(value);
            if (block == null || !blocks.add(block)) {
                throw new IllegalArgumentException("invalid block set");
            }
        }
        Set<Block> decoded = Set.copyOf(blocks);
        if (!encodeRegistryBlocks(decoded).equals(encoded)) {
            throw new IllegalArgumentException("non-canonical block set");
        }
        return decoded;
    }

    private static String encodeCanonicalText(String value) {
        String text = value == null ? "" : value;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SKIPPED_TARGET_TEXT_BYTES) {
            throw new IllegalArgumentException("skipped target text too large");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Optional<String> decodeCanonicalText(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_SKIPPED_TARGET_TEXT_BYTES) {
                return Optional.empty();
            }
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            return encodeCanonicalText(decoded).equals(encoded)
                    ? Optional.of(decoded) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> decodeCanonicalBoolean(String value) {
        if ("true".equals(value)) {
            return Optional.of(true);
        }
        if ("false".equals(value)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    /**
     * Missing is a legacy zero. Once persisted, mission budget counters must remain non-negative
     * integers; malformed values must not refresh a retry budget on restart.
     */
    static OptionalInt decodePersistedMissionCounter(
            Map<String, String> checkpoint, String key) {
        if (checkpoint == null || !checkpoint.containsKey(key)) {
            return OptionalInt.of(0);
        }
        return canonicalNonNegativeInt(checkpoint.get(key));
    }

    static Map<String, String> encodePostconditionRepairCheckpoint(
            int replans, int lastMatched, String fingerprint) {
        if (!validPostconditionRepairState(replans, lastMatched, fingerprint)) {
            throw new IllegalArgumentException("invalid postcondition repair state");
        }
        String encodedFingerprint = fingerprint.isEmpty() ? ""
                : Base64.getUrlEncoder().withoutPadding().encodeToString(
                fingerprint.getBytes(StandardCharsets.UTF_8));
        return Map.of(
                POSTCONDITION_REPLANS_KEY, String.valueOf(replans),
                POSTCONDITION_LAST_MATCHED_KEY, String.valueOf(lastMatched),
                POSTCONDITION_FINGERPRINT_KEY, encodedFingerprint);
    }

    /**
     * Missing is the legacy representation. Once any namespaced field exists, the complete
     * canonical triple is required so a restart cannot reset the repair count or forget the last
     * rejected plan fingerprint.
     */
    static Optional<PostconditionRepairCheckpoint> decodePostconditionRepairCheckpoint(
            Map<String, String> checkpoint) {
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Set<String> presentKeys = values.keySet().stream()
                .filter(key -> key != null && key.startsWith(POSTCONDITION_PREFIX))
                .collect(java.util.stream.Collectors.toSet());
        if (presentKeys.isEmpty()) {
            return Optional.of(PostconditionRepairCheckpoint.legacy());
        }
        if (!presentKeys.equals(POSTCONDITION_REPAIR_KEYS)) {
            return Optional.empty();
        }

        OptionalInt replans = canonicalNonNegativeInt(
                values.get(POSTCONDITION_REPLANS_KEY));
        OptionalInt lastMatched = canonicalNonNegativeInt(
                values.get(POSTCONDITION_LAST_MATCHED_KEY));
        if (replans.isEmpty() || lastMatched.isEmpty()) {
            return Optional.empty();
        }
        String fingerprint = decodeCanonicalPostconditionFingerprint(
                values.get(POSTCONDITION_FINGERPRINT_KEY)).orElse(null);
        if (fingerprint == null || !validPostconditionRepairState(
                replans.getAsInt(), lastMatched.getAsInt(), fingerprint)) {
            return Optional.empty();
        }
        return Optional.of(new PostconditionRepairCheckpoint(
                true, replans.getAsInt(), lastMatched.getAsInt(), fingerprint));
    }

    static OptionalInt canonicalNonNegativeInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && String.valueOf(parsed).equals(value)
                    ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    private static Optional<String> decodeCanonicalPostconditionFingerprint(
            String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        if (encoded.isEmpty()) {
            return Optional.of("");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length > MAX_POSTCONDITION_FINGERPRINT_BYTES) {
                return Optional.empty();
            }
            String fingerprint = new String(decoded, StandardCharsets.UTF_8);
            String canonical = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(fingerprint.getBytes(StandardCharsets.UTF_8));
            return canonical.equals(encoded)
                    ? Optional.of(fingerprint) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean validPostconditionRepairState(
            int replans, int lastMatched, String fingerprint) {
        if (replans < 0 || replans > MAX_POSTCONDITION_REPLANS
                || lastMatched < 0 || fingerprint == null
                || fingerprint.getBytes(StandardCharsets.UTF_8).length
                > MAX_POSTCONDITION_FINGERPRINT_BYTES) {
            return false;
        }
        return replans == 0 ? fingerprint.isEmpty() : !fingerprint.isBlank();
    }

    static Optional<ReplanSnapshot> decodeReplanSnapshot(Map<String, String> checkpoint) {
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Set<String> snapshotKeys = values.keySet().stream()
                .filter(key -> key != null && key.startsWith("snap_"))
                .collect(java.util.stream.Collectors.toSet());
        boolean legacy = snapshotKeys.equals(LEGACY_REPLAN_SNAPSHOT_KEYS);
        boolean modern = snapshotKeys.equals(MODERN_REPLAN_SNAPSHOT_KEYS);
        if (!legacy && !modern) {
            return Optional.empty();
        }
        OptionalInt steps = canonicalNonNegativeInt(values.get("snap_steps"));
        OptionalInt target = canonicalNonNegativeInt(values.get("snap_target"));
        Optional<Integer> x = canonicalSignedInt(values.get("snap_x"));
        Optional<Integer> y = canonicalSignedInt(values.get("snap_y"));
        Optional<Integer> z = canonicalSignedInt(values.get("snap_z"));
        if (steps.isEmpty() || target.isEmpty()
                || x.isEmpty() || y.isEmpty() || z.isEmpty()) {
            return Optional.empty();
        }
        if (legacy) {
            return Optional.of(new ReplanSnapshot(
                    steps.getAsInt(), target.getAsInt(),
                    x.orElseThrow(), y.orElseThrow(), z.orElseThrow(),
                    "", -1, -1));
        }
        OptionalInt huntRaw = canonicalNonNegativeInt(
                values.get("snap_hunt_raw_meat"));
        OptionalInt huntSectors = canonicalNonNegativeInt(
                values.get("snap_hunt_visited_sectors"));
        String dimension = values.get("snap_dimension");
        Identifier dimensionId = dimension == null
                ? null : Identifier.tryParse(dimension);
        if (huntRaw.isEmpty() || huntSectors.isEmpty()
                || dimensionId == null || !dimensionId.toString().equals(dimension)) {
            return Optional.empty();
        }
        return Optional.of(new ReplanSnapshot(
                steps.getAsInt(), target.getAsInt(),
                x.orElseThrow(), y.orElseThrow(), z.orElseThrow(),
                dimension, huntRaw.getAsInt(), huntSectors.getAsInt()));
    }

    private static boolean hasAnyHuntReplanWatermark(
            Map<String, String> checkpoint) {
        return checkpoint != null
                && (checkpoint.containsKey("snap_hunt_raw_meat")
                || checkpoint.containsKey("snap_hunt_visited_sectors"));
    }

    private static Optional<Integer> canonicalSignedInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return String.valueOf(parsed).equals(value)
                    ? Optional.of(parsed) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    static Optional<GoalStep.Kind> decodeStepKind(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GoalStep.Kind.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    static int nonNegativeInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    static String encodePos(BlockPos pos) {
        return BlockPosText.encodePos(pos);
    }

    static Optional<BlockPos> decodePos(String value) {
        return BlockPosText.decodePos(value);
    }

    record SettledServiceDescriptor(
            String oreFingerprint,
            ServicePolicy policy,
            String missionId,
            int target,
            int boundary) {
        private static final String CODEC_VERSION = "1";

        SettledServiceDescriptor {
            if (!validOreFingerprint(oreFingerprint) || policy == null
                    || missionId == null || missionId.isBlank()
                    || missionId.indexOf('|') >= 0
                    || missionId.chars().anyMatch(Character::isISOControl)
                    || !MiningServiceTask.validServiceDescriptor(
                    policy, target, boundary)) {
                throw new IllegalArgumentException("invalid_settled_service_descriptor");
            }
        }

        static SettledServiceDescriptor fromMetadata(
                MiningServiceTask.RestoreMetadata metadata) {
            return new SettledServiceDescriptor(
                    OreDigTask.oreFingerprint(metadata.ores()), metadata.policy(),
                    metadata.serviceMissionId(), metadata.serviceTargetCount(),
                    metadata.serviceBoundary());
        }

        private String encode() {
            return String.join("|",
                    CODEC_VERSION,
                    policy.profile().name(),
                    missionId,
                    String.valueOf(target),
                    String.valueOf(boundary),
                    String.valueOf(policy.targetToolUsableDurability()),
                    String.valueOf(policy.channelToolUsableDurability()),
                    String.valueOf(policy.foodMinUnits()),
                    String.valueOf(policy.torchMinCount()),
                    String.valueOf(policy.freeSlotsMin()),
                    String.valueOf(policy.emergencyBlocksReserved()),
                    String.valueOf(policy.futureStickReserve()),
                    String.valueOf(policy.craftingTableRequired()),
                    oreFingerprint);
        }

        private static Optional<SettledServiceDescriptor> decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return Optional.empty();
            }
            try {
                String[] parts = encoded.split("\\|", -1);
                if (parts.length != 14 || !CODEC_VERSION.equals(parts[0])
                        || !"true".equals(parts[12]) && !"false".equals(parts[12])) {
                    return Optional.empty();
                }
                ServicePolicy policy =
                        new ServicePolicy(
                                ServiceProfile.valueOf(parts[1]),
                                Integer.parseInt(parts[5]),
                                Integer.parseInt(parts[6]),
                                Integer.parseInt(parts[7]),
                                Integer.parseInt(parts[8]),
                                Integer.parseInt(parts[9]),
                                Integer.parseInt(parts[10]),
                                Integer.parseInt(parts[11]),
                                Boolean.parseBoolean(parts[12]));
                SettledServiceDescriptor descriptor = new SettledServiceDescriptor(
                        parts[13], policy, parts[2], Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]));
                return descriptor.encode().equals(encoded)
                        ? Optional.of(descriptor) : Optional.empty();
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        private static boolean validOreFingerprint(String fingerprint) {
            if (fingerprint == null || fingerprint.isBlank()) {
                return false;
            }
            Set<Block> blocks = new HashSet<>();
            for (String encoded : fingerprint.split(",", -1)) {
                Identifier id = Identifier.tryParse(encoded);
                Block block = id == null
                        ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
                if (block == null || block == Blocks.AIR || !blocks.add(block)) {
                    return false;
                }
            }
            return OreDigTask.oreFingerprint(blocks).equals(fingerprint);
        }
    }

    record SettledServiceAuthority(
            SettledServiceDescriptor descriptor,
            String dimension,
            MiningServiceTask.DisposalGeometry geometry) {
        SettledServiceAuthority {
            Identifier dimensionId = dimension == null
                    ? null : Identifier.tryParse(dimension);
            if (descriptor == null || dimensionId == null
                    || !dimensionId.toString().equals(dimension) || geometry == null) {
                throw new IllegalArgumentException("invalid_settled_service_authority");
            }
        }

        String key() {
            return descriptor.encode() + "@" + dimension + "@"
                    + encodePos(geometry.workFace()) + "@"
                    + geometry.pocketAxis().name();
        }
    }

    record SettledServiceTombstone(
            SettledServiceAuthority authority,
            String failureReason) {
        SettledServiceTombstone {
            if (authority == null
                    || !MiningServiceTask.validTerminalFailureReason(failureReason)) {
                throw new IllegalArgumentException("invalid_settled_service_tombstone");
            }
        }

        String key() {
            return authority.key();
        }

        private MiningServiceTask.DisposalGeometry geometry() {
            return authority.geometry();
        }

        boolean sameGeometry(SettledServiceAuthority other) {
            return other != null
                    && authority.dimension().equals(other.dimension())
                    && authority.geometry().equals(other.geometry());
        }

        boolean sameGeometry(SettledServiceTombstone other) {
            return other != null && sameGeometry(other.authority());
        }

        MiningServiceTask.DisposalReplayGuard replayGuard() {
            return new MiningServiceTask.DisposalReplayGuard(
                    authority.dimension(), authority.geometry(), failureReason);
        }

        Map<String, String> encode() {
            MiningServiceTask.DisposalGeometry geometry = authority.geometry();
            return Map.of(
                    "schema", "1",
                    "descriptor", authority.descriptor().encode(),
                    "dimension", authority.dimension(),
                    "work_face", encodePos(geometry.workFace()),
                    "pocket_axis", geometry.pocketAxis().name(),
                    "pocket_a", encodePos(geometry.firstEntry()),
                    "pocket_b", encodePos(geometry.secondEntry()),
                    "failure", failureReason);
        }

        private static Optional<SettledServiceTombstone> decode(
                Map<String, String> values) {
            if (values == null || !values.keySet().equals(
                    SETTLED_SERVICE_ENTRY_KEYS)
                    || !"1".equals(values.get("schema"))) {
                return Optional.empty();
            }
            try {
                Optional<SettledServiceDescriptor> descriptor =
                        SettledServiceDescriptor.decode(values.get("descriptor"));
                Optional<BlockPos> workFace = decodePos(values.get("work_face"));
                Optional<BlockPos> pocketA = decodePos(values.get("pocket_a"));
                Optional<BlockPos> pocketB = decodePos(values.get("pocket_b"));
                Direction.Axis axis = Direction.Axis.valueOf(values.get("pocket_axis"));
                if (descriptor.isEmpty() || workFace.isEmpty()
                        || pocketA.isEmpty() || pocketB.isEmpty()
                        || !encodePos(workFace.orElseThrow()).equals(
                        values.get("work_face"))
                        || !encodePos(pocketA.orElseThrow()).equals(
                        values.get("pocket_a"))
                        || !encodePos(pocketB.orElseThrow()).equals(
                        values.get("pocket_b"))) {
                    return Optional.empty();
                }
                MiningServiceTask.DisposalGeometry geometry =
                        new MiningServiceTask.DisposalGeometry(
                                workFace.orElseThrow(), axis,
                                pocketA.orElseThrow(), pocketB.orElseThrow());
                SettledServiceAuthority authority = new SettledServiceAuthority(
                        descriptor.orElseThrow(), values.get("dimension"), geometry);
                return Optional.of(new SettledServiceTombstone(
                        authority, values.get("failure")));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }
    }

    static Optional<List<SettledServiceTombstone>>
    decodeSettledServiceTombstones(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Optional.of(List.of());
        }
        try {
            String rawCount = values.getOrDefault("count", "");
            int count = Integer.parseInt(rawCount);
            if (count < 1 || count > MAX_SETTLED_SERVICE_TOMBSTONES
                    || !String.valueOf(count).equals(rawCount)) {
                return Optional.empty();
            }
            Set<String> expected = new HashSet<>();
            expected.add("count");
            for (int index = 0; index < count; index++) {
                for (String key : SETTLED_SERVICE_ENTRY_KEYS) {
                    expected.add(index + "." + key);
                }
            }
            if (!values.keySet().equals(expected)) {
                return Optional.empty();
            }
            List<SettledServiceTombstone> decoded = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            Set<String> geometries = new HashSet<>();
            String previousIdentity = null;
            for (int index = 0; index < count; index++) {
                Map<String, String> entry = new java.util.LinkedHashMap<>();
                String prefix = index + ".";
                for (String key : SETTLED_SERVICE_ENTRY_KEYS) {
                    entry.put(key, values.get(prefix + key));
                }
                SettledServiceTombstone tombstone =
                        SettledServiceTombstone.decode(entry).orElseThrow();
                String geometryKey = tombstone.authority().dimension() + "@"
                        + encodePos(tombstone.geometry().workFace()) + "@"
                        + tombstone.geometry().pocketAxis().name();
                if (!identities.add(tombstone.key()) || !geometries.add(geometryKey)
                        || previousIdentity != null
                        && previousIdentity.compareTo(tombstone.key()) >= 0) {
                    return Optional.empty();
                }
                previousIdentity = tombstone.key();
                decoded.add(tombstone);
            }
            return Optional.of(List.copyOf(decoded));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
