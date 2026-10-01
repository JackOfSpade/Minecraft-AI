package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/** Durable {@link OreDigTask} restart state: codec, schema migration and validation. */
record OreDigCheckpoint(int taskSchema,
                        int targetCount,
                        boolean batchOpen,
                        int delivered,
                        int rareMissionTarget,
                        boolean inventoryServiceUsed,
                        int torchLimit,
                        int torchPlacements,
                        int resourceEpoch,
                        MiningCursor cursor,
                        String oreFingerprint,
                        int budgetUsed,
                        int lastProgressBudget,
                        BlockPos controlledStripRear,
                        BlockPos boundaryRerouteOrigin,
                        BlockPos pendingPickupPos,
                        BlockPos pendingPickupLastSeenPos,
                        int pendingPickupInventory,
                        int pendingPickupStartedBudget,
                        int pendingPickupGainBudget,
                        BlockPos activeBreakPos,
                        int activeBreakInventory,
                        boolean activeBreakConfirmedGone,
                        Map<BlockPos, BlockPos> rememberedHighWorkPoses) {
    static final int CHECKPOINT_SCHEMA = 5;
    private static final int PREVIOUS_CHECKPOINT_SCHEMA = 4;
    private static final int MISSION_CHECKPOINT_SCHEMA = 3;
    private static final int RESOURCE_EPOCH_CHECKPOINT_SCHEMA = 2;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 1;
    private static final int MAX_CHECKPOINT_TARGET_COUNT = 4096;
    private static final int MAX_CURSOR_LEGS = 4096;

    static String encodeCheckpointPos(BlockPos pos) {
        return BlockPosText.encodePos(pos);
    }

    private static Optional<BlockPos> decodeCheckpointPos(String value) {
        return BlockPosText.decodePos(value);
    }

    private static String encodeRememberedHighWorkPoses(Map<BlockPos, BlockPos> poses) {
        return poses.entrySet().stream()
                .sorted(java.util.Comparator.comparing(
                        entry -> encodeCheckpointPos(entry.getKey())))
                .map(entry -> encodeCheckpointPos(entry.getKey())
                        + "@" + encodeCheckpointPos(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Optional<Map<BlockPos, BlockPos>> decodeRememberedHighWorkPoses(String value) {
        if (value == null) {
            return Optional.of(Map.of());
        }
        if (value.isBlank()) {
            return Optional.empty();
        }
        String[] encodedEntries = value.split(";", -1);
        if (encodedEntries.length > OreDigTask.VEIN_CAP) {
            return Optional.empty();
        }
        Map<BlockPos, BlockPos> decoded = new java.util.LinkedHashMap<>();
        for (String encodedEntry : encodedEntries) {
            String[] pair = encodedEntry.split("@", -1);
            if (pair.length != 2) {
                return Optional.empty();
            }
            BlockPos ore = decodeCheckpointPos(pair[0]).orElse(null);
            BlockPos pose = decodeCheckpointPos(pair[1]).orElse(null);
            if (ore == null || pose == null || decoded.put(ore, pose) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(Map.copyOf(decoded));
    }

    private static final Set<String> LEGACY_REQUIRED_KEYS = Set.of(
            "task_schema", "target_count", "batch_open", "budget_used",
            "last_progress_budget", "schema", "origin", "face", "direction", "leg",
            "steps_left", "leg_length", "batches", "ore_fingerprint",
            "pending_pickup_inventory", "pending_pickup_started_budget",
            "pickup_gain_budget", "active_break_inventory");
    private static final Set<String> LEGACY_ALLOWED_KEYS = Set.of(
            "task_schema", "target_count", "batch_open", "budget_used",
            "last_progress_budget", "schema", "origin", "face", "direction", "leg",
            "steps_left", "leg_length", "batches", "ore_fingerprint",
            "pending_pickup_pos", "pending_pickup_inventory",
            "pending_pickup_started_budget", "pickup_gain_budget",
            "active_break_pos", "active_break_inventory");
    private static final Set<String> RESOURCE_REQUIRED_KEYS =
            withResourceKeys(LEGACY_REQUIRED_KEYS);
    private static final Set<String> RESOURCE_ALLOWED_KEYS =
            withResourceKeys(LEGACY_ALLOWED_KEYS);
    private static final Set<String> MISSION_REQUIRED_KEYS =
            withMissionKeys(RESOURCE_REQUIRED_KEYS);
    private static final Set<String> MISSION_ALLOWED_KEYS =
            withPickupLastSeenKey(withMissionKeys(RESOURCE_ALLOWED_KEYS));
    private static final Set<String> PREVIOUS_REQUIRED_KEYS =
            withDeliveredKey(MISSION_REQUIRED_KEYS);
    private static final Set<String> PREVIOUS_ALLOWED_KEYS =
            withRememberedHighWorkPosesKey(
                    withControlledStripRearKey(
                            withBoundaryRerouteKey(withDeliveredKey(MISSION_ALLOWED_KEYS))));
    private static final Set<String> REQUIRED_KEYS =
            withActiveBreakConfirmedGoneKey(PREVIOUS_REQUIRED_KEYS);
    private static final Set<String> ALLOWED_KEYS =
            withActiveBreakConfirmedGoneKey(PREVIOUS_ALLOWED_KEYS);

    OreDigCheckpoint {
        controlledStripRear = controlledStripRear == null
                ? null : controlledStripRear.immutable();
        boundaryRerouteOrigin = boundaryRerouteOrigin == null
                ? null : boundaryRerouteOrigin.immutable();
        pendingPickupPos = pendingPickupPos == null ? null : pendingPickupPos.immutable();
        pendingPickupLastSeenPos = pendingPickupLastSeenPos == null
                ? null : pendingPickupLastSeenPos.immutable();
        activeBreakPos = activeBreakPos == null ? null : activeBreakPos.immutable();
        if (rememberedHighWorkPoses == null || rememberedHighWorkPoses.isEmpty()) {
            rememberedHighWorkPoses = Map.of();
        } else {
            Map<BlockPos, BlockPos> immutable = new java.util.LinkedHashMap<>();
            for (Map.Entry<BlockPos, BlockPos> entry : rememberedHighWorkPoses.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("null_remembered_high_work_pose");
                }
                immutable.put(entry.getKey().immutable(), entry.getValue().immutable());
            }
            rememberedHighWorkPoses = Map.copyOf(immutable);
        }
    }

    /** Compatibility constructor for schema-4 callers that already supplied remembered poses. */
    OreDigCheckpoint(int taskSchema,
                     int targetCount,
                     boolean batchOpen,
                     int delivered,
                     int rareMissionTarget,
                     boolean inventoryServiceUsed,
                     int torchLimit,
                     int torchPlacements,
                     int resourceEpoch,
                     MiningCursor cursor,
                     String oreFingerprint,
                     int budgetUsed,
                     int lastProgressBudget,
                     BlockPos controlledStripRear,
                     BlockPos boundaryRerouteOrigin,
                     BlockPos pendingPickupPos,
                     BlockPos pendingPickupLastSeenPos,
                     int pendingPickupInventory,
                     int pendingPickupStartedBudget,
                     int pendingPickupGainBudget,
                     BlockPos activeBreakPos,
                     int activeBreakInventory,
                     Map<BlockPos, BlockPos> rememberedHighWorkPoses) {
        this(taskSchema, targetCount, batchOpen, delivered, rareMissionTarget,
                inventoryServiceUsed, torchLimit, torchPlacements, resourceEpoch, cursor,
                oreFingerprint, budgetUsed, lastProgressBudget, controlledStripRear,
                boundaryRerouteOrigin, pendingPickupPos, pendingPickupLastSeenPos,
                pendingPickupInventory, pendingPickupStartedBudget, pendingPickupGainBudget,
                activeBreakPos, activeBreakInventory, false, rememberedHighWorkPoses);
    }

    /** Compatibility constructor for deterministic fixtures created before the optional key. */
    OreDigCheckpoint(int taskSchema,
                     int targetCount,
                     boolean batchOpen,
                     int delivered,
                     int rareMissionTarget,
                     boolean inventoryServiceUsed,
                     int torchLimit,
                     int torchPlacements,
                     int resourceEpoch,
                     MiningCursor cursor,
                     String oreFingerprint,
                     int budgetUsed,
                     int lastProgressBudget,
                     BlockPos controlledStripRear,
                     BlockPos boundaryRerouteOrigin,
                     BlockPos pendingPickupPos,
                     BlockPos pendingPickupLastSeenPos,
                     int pendingPickupInventory,
                     int pendingPickupStartedBudget,
                     int pendingPickupGainBudget,
                     BlockPos activeBreakPos,
                     int activeBreakInventory) {
        this(taskSchema, targetCount, batchOpen, delivered, rareMissionTarget,
                inventoryServiceUsed, torchLimit, torchPlacements, resourceEpoch, cursor,
                oreFingerprint, budgetUsed, lastProgressBudget, controlledStripRear,
                boundaryRerouteOrigin, pendingPickupPos, pendingPickupLastSeenPos,
                pendingPickupInventory, pendingPickupStartedBudget, pendingPickupGainBudget,
                activeBreakPos, activeBreakInventory, false, Map.of());
    }

    /** Derives a copy with only {@link #resourceEpoch} replaced; every other field is kept. */
    OreDigCheckpoint withResourceEpoch(int resourceEpoch) {
        return new OreDigCheckpoint(taskSchema, targetCount, batchOpen, delivered,
                rareMissionTarget, inventoryServiceUsed, torchLimit, torchPlacements,
                resourceEpoch, cursor, oreFingerprint, budgetUsed, lastProgressBudget,
                controlledStripRear, boundaryRerouteOrigin, pendingPickupPos,
                pendingPickupLastSeenPos, pendingPickupInventory, pendingPickupStartedBudget,
                pendingPickupGainBudget, activeBreakPos, activeBreakInventory,
                activeBreakConfirmedGone,
                rememberedHighWorkPoses);
    }

    /** Derives a copy with only {@link #torchPlacements} replaced; every other field is kept. */
    OreDigCheckpoint withTorchPlacements(int torchPlacements) {
        return new OreDigCheckpoint(taskSchema, targetCount, batchOpen, delivered,
                rareMissionTarget, inventoryServiceUsed, torchLimit, torchPlacements,
                resourceEpoch, cursor, oreFingerprint, budgetUsed, lastProgressBudget,
                controlledStripRear, boundaryRerouteOrigin, pendingPickupPos,
                pendingPickupLastSeenPos, pendingPickupInventory, pendingPickupStartedBudget,
                pendingPickupGainBudget, activeBreakPos, activeBreakInventory,
                activeBreakConfirmedGone,
                rememberedHighWorkPoses);
    }

    /**
     * Derives a copy with only {@link #inventoryServiceUsed} replaced; every other field is
     * kept.
     */
    OreDigCheckpoint withInventoryServiceUsed(boolean inventoryServiceUsed) {
        return new OreDigCheckpoint(taskSchema, targetCount, batchOpen, delivered,
                rareMissionTarget, inventoryServiceUsed, torchLimit, torchPlacements,
                resourceEpoch, cursor, oreFingerprint, budgetUsed, lastProgressBudget,
                controlledStripRear, boundaryRerouteOrigin, pendingPickupPos,
                pendingPickupLastSeenPos, pendingPickupInventory, pendingPickupStartedBudget,
                pendingPickupGainBudget, activeBreakPos, activeBreakInventory,
                activeBreakConfirmedGone,
                rememberedHighWorkPoses);
    }

    Map<String, String> encode() {
        Map<String, String> values = new java.util.LinkedHashMap<>(cursor.encode());
        values.put("task_schema", String.valueOf(taskSchema));
        values.put("target_count", String.valueOf(targetCount));
        values.put("batch_open", String.valueOf(batchOpen));
        values.put("delivered", String.valueOf(delivered));
        values.put("rare_mission_target", String.valueOf(rareMissionTarget));
        values.put("inventory_service_used", String.valueOf(inventoryServiceUsed));
        values.put("torch_limit", String.valueOf(torchLimit));
        values.put("torch_placements", String.valueOf(torchPlacements));
        values.put("resource_epoch", String.valueOf(resourceEpoch));
        values.put("budget_used", String.valueOf(budgetUsed));
        values.put("last_progress_budget", String.valueOf(lastProgressBudget));
        values.put("ore_fingerprint", oreFingerprint);
        values.put("pending_pickup_inventory", String.valueOf(pendingPickupInventory));
        values.put("pending_pickup_started_budget", String.valueOf(pendingPickupStartedBudget));
        values.put("pickup_gain_budget", String.valueOf(pendingPickupGainBudget));
        values.put("active_break_inventory", String.valueOf(activeBreakInventory));
        if (taskSchema == CHECKPOINT_SCHEMA) {
            values.put("active_break_confirmed_gone", String.valueOf(activeBreakConfirmedGone));
        }
        if (controlledStripRear != null) {
            values.put("controlled_strip_rear",
                    encodeCheckpointPos(controlledStripRear));
        }
        if (boundaryRerouteOrigin != null) {
            values.put("boundary_reroute_origin",
                    encodeCheckpointPos(boundaryRerouteOrigin));
        }
        if (pendingPickupPos != null) {
            values.put("pending_pickup_pos", encodeCheckpointPos(pendingPickupPos));
        }
        if (pendingPickupLastSeenPos != null
                && !pendingPickupLastSeenPos.equals(pendingPickupPos)) {
            values.put("pending_pickup_last_seen_pos",
                    encodeCheckpointPos(pendingPickupLastSeenPos));
        }
        if (activeBreakPos != null) {
            values.put("active_break_pos", encodeCheckpointPos(activeBreakPos));
        }
        if (!rememberedHighWorkPoses.isEmpty()) {
            values.put("remembered_high_work_poses",
                    encodeRememberedHighWorkPoses(rememberedHighWorkPoses));
        }
        return Map.copyOf(values);
    }

    static Optional<OreDigCheckpoint> decode(Map<String, String> values, Set<Block> ores) {
        return decode(values, ores, null);
    }

    static Optional<OreDigCheckpoint> decode(Map<String, String> values,
                                             Set<Block> ores,
                                             Integer expectedRareMissionTarget) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        try {
            int taskSchema = requiredInt(values, "task_schema");
            Set<String> requiredKeys = switch (taskSchema) {
                case LEGACY_CHECKPOINT_SCHEMA -> LEGACY_REQUIRED_KEYS;
                case RESOURCE_EPOCH_CHECKPOINT_SCHEMA -> RESOURCE_REQUIRED_KEYS;
                case MISSION_CHECKPOINT_SCHEMA -> MISSION_REQUIRED_KEYS;
                case PREVIOUS_CHECKPOINT_SCHEMA -> PREVIOUS_REQUIRED_KEYS;
                case CHECKPOINT_SCHEMA -> REQUIRED_KEYS;
                default -> Set.of();
            };
            Set<String> allowedKeys = switch (taskSchema) {
                case LEGACY_CHECKPOINT_SCHEMA -> LEGACY_ALLOWED_KEYS;
                case RESOURCE_EPOCH_CHECKPOINT_SCHEMA -> RESOURCE_ALLOWED_KEYS;
                case MISSION_CHECKPOINT_SCHEMA -> MISSION_ALLOWED_KEYS;
                case PREVIOUS_CHECKPOINT_SCHEMA -> PREVIOUS_ALLOWED_KEYS;
                case CHECKPOINT_SCHEMA -> ALLOWED_KEYS;
                default -> Set.of();
            };
            if (!values.keySet().containsAll(requiredKeys)
                    || !allowedKeys.containsAll(values.keySet())) {
                return Optional.empty();
            }
            int targetCount = requiredInt(values, "target_count");
            boolean batchOpen = strictBoolean(values, "batch_open");
            int delivered = taskSchema >= PREVIOUS_CHECKPOINT_SCHEMA
                    ? requiredInt(values, "delivered") : 0;
            int rareMissionTarget;
            boolean inventoryServiceUsed;
            if (taskSchema >= MISSION_CHECKPOINT_SCHEMA) {
                rareMissionTarget = requiredInt(values, "rare_mission_target");
                inventoryServiceUsed = strictBoolean(values, "inventory_service_used");
            } else {
                if (expectedRareMissionTarget == null) {
                    return Optional.empty();
                }
                rareMissionTarget = expectedRareMissionTarget;
                inventoryServiceUsed = false;
            }
            int torchLimit = MiningBudget.RARE_BATCH_TORCH_LIMIT;
            int torchPlacements = taskSchema == LEGACY_CHECKPOINT_SCHEMA
                    ? (batchOpen && rareMissionTarget >= MiningBudget.EXPEDITION_THRESHOLD
                    ? MiningBudget.RARE_BATCH_TORCH_LIMIT : 0)
                    : requiredInt(values, "torch_placements");
            int resourceEpoch = taskSchema == LEGACY_CHECKPOINT_SCHEMA
                    ? 0 : requiredInt(values, "resource_epoch");
            if (taskSchema != LEGACY_CHECKPOINT_SCHEMA) {
                torchLimit = requiredInt(values, "torch_limit");
            }
            int budget = requiredInt(values, "budget_used");
            int lastProgress = requiredInt(values, "last_progress_budget");
            String fingerprint = required(values, "ore_fingerprint");
            int cursorSchema = requiredInt(values, "schema");
            BlockPos origin = decodeCheckpointPos(required(values, "origin")).orElse(null);
            BlockPos face = decodeCheckpointPos(required(values, "face")).orElse(null);
            int direction = requiredInt(values, "direction");
            int leg = requiredInt(values, "leg");
            int stepsLeft = requiredInt(values, "steps_left");
            int legLength = requiredInt(values, "leg_length");
            int batches = requiredInt(values, "batches");
            BlockPos controlledStripRear = taskSchema >= PREVIOUS_CHECKPOINT_SCHEMA
                    ? optionalPos(values, "controlled_strip_rear") : null;
            BlockPos boundaryRerouteOrigin = taskSchema >= PREVIOUS_CHECKPOINT_SCHEMA
                    ? optionalPos(values, "boundary_reroute_origin") : null;
            BlockPos pending = optionalPos(values, "pending_pickup_pos");
            BlockPos pendingLastSeen = taskSchema >= MISSION_CHECKPOINT_SCHEMA
                    ? optionalPos(values, "pending_pickup_last_seen_pos") : null;
            if (pending != null && pendingLastSeen == null) {
                // Optional schema-3 extension: older checkpoints resume from the durable
                // break cell until a visible moving ItemEntity publishes a newer position.
                pendingLastSeen = pending;
            }
            int pendingInventory = requiredInt(values, "pending_pickup_inventory");
            int pendingStarted = requiredInt(values, "pending_pickup_started_budget");
            int pendingGain = requiredInt(values, "pickup_gain_budget");
            BlockPos activeBreak = optionalPos(values, "active_break_pos");
            int activeBreakInventory = requiredInt(values, "active_break_inventory");
            boolean activeBreakConfirmedGone = taskSchema == CHECKPOINT_SCHEMA
                    && strictBoolean(values, "active_break_confirmed_gone");
            Map<BlockPos, BlockPos> rememberedHighWorkPoses =
                    taskSchema >= PREVIOUS_CHECKPOINT_SCHEMA
                            ? decodeRememberedHighWorkPoses(
                            values.get("remembered_high_work_poses")).orElseThrow()
                            : Map.of();

            int maxBudget = OreDigTask.maxElapsedForTarget(
                    ores, targetCount, rareMissionTarget, resourceEpoch);
            boolean cursorShape = cursorSchema == MiningCursor.CURRENT_SCHEMA
                    && origin != null && face != null
                    && direction >= -1 && direction < OreDigTask.STRIP_DIRS.length
                    && leg >= 0 && leg <= MAX_CURSOR_LEGS
                    && legLength >= OreDigTask.STRIP_SEGMENT && legLength <= OreDigTask.STRIP_SEGMENT * 8
                    && legLength % OreDigTask.STRIP_SEGMENT == 0
                    && stepsLeft >= 0 && stepsLeft <= legLength
                    && batches >= 0 && batches <= MAX_CHECKPOINT_TARGET_COUNT;
            boolean pendingPair = (pending == null)
                    ? pendingInventory == -1 && pendingStarted == -1 && pendingGain == -1
                    : pendingInventory >= 0 && pendingInventory <= 4096
                    && pendingStarted >= 0 && pendingStarted <= budget
                    && (pendingGain == -1
                    || pendingGain >= pendingStarted && pendingGain <= budget);
            boolean pendingLastSeenPair = (pending == null) == (pendingLastSeen == null)
                    && (pendingLastSeen == null
                    || Math.abs((long) pendingLastSeen.getX() - pending.getX())
                    <= OreDigTask.TARGET_DROP_LAST_SEEN_RANGE
                    && Math.abs((long) pendingLastSeen.getY() - pending.getY())
                    <= OreDigTask.TARGET_DROP_LAST_SEEN_RANGE
                    && Math.abs((long) pendingLastSeen.getZ() - pending.getZ())
                    <= OreDigTask.TARGET_DROP_LAST_SEEN_RANGE);
            boolean activePair = (activeBreak == null)
                    ? activeBreakInventory == -1 && !activeBreakConfirmedGone
                    : activeBreakInventory >= 0 && activeBreakInventory <= 4096;
            boolean rememberedHighWorkPoseShape = rememberedHighWorkPoses.size() <= OreDigTask.VEIN_CAP
                    && (batchOpen || rememberedHighWorkPoses.isEmpty())
                    && rememberedHighWorkPoses.entrySet().stream().allMatch(entry ->
                    OreDigTask.isExactHighWorkPose(entry.getKey(), entry.getValue())
                            && OreDigTask.isRememberedHighWorkPoseNearFace(face, entry.getKey()));
            boolean boundaryRerouteShape = boundaryRerouteOrigin == null
                    || batchOpen && direction >= 0 && stepsLeft > 0
                    && boundaryRerouteOrigin.equals(face);
            boolean controlledStripRearShape = controlledStripRear == null
                    || batchOpen && direction >= 0 && stepsLeft > 0
                    && (boundaryRerouteOrigin == null
                    && controlledStripRear.equals(
                    face.relative(OreDigTask.STRIP_DIRS[direction].getOpposite()))
                    || boundaryRerouteOrigin != null
                    && boundaryRerouteOrigin.equals(face)
                    && stepsLeft == legLength
                    && controlledStripRear.equals(
                    face.relative(OreDigTask.STRIP_DIRS[direction].getClockWise())))
                    && pending == null && activeBreak == null;
            boolean committedShape = batchOpen
                    || budget == 0 && lastProgress == 0
                    && pending == null && activeBreak == null;
            boolean deliveredShape = batchOpen
                    ? delivered >= 0 && delivered <= targetCount
                    : delivered == 0;
            boolean rareMissionShape = rareMissionTarget == 0
                    || rareMissionTarget >= MiningBudget.EXPEDITION_THRESHOLD
                    && rareMissionTarget <= MAX_CHECKPOINT_TARGET_COUNT
                    && OreDigTask.isRareOreFamily(ores);
            boolean rareExpedition = OreDigTask.isRareExpeditionBatch(ores, rareMissionTarget);
            // Margin epochs raise the per-batch bound only by the mission-derived pool; the
            // non-rare branch below still pins ordinary batches to epoch zero.
            int epochCapacity = rareExpedition
                    ? OreDigTask.rareMissionResourceEpochCapacity(rareMissionTarget)
                    : MiningBudget.RARE_RESOURCE_EPOCHS_PER_BATCH;
            if (taskSchema != CHECKPOINT_SCHEMA
                    && taskSchema != PREVIOUS_CHECKPOINT_SCHEMA
                    && taskSchema != MISSION_CHECKPOINT_SCHEMA
                    && taskSchema != RESOURCE_EPOCH_CHECKPOINT_SCHEMA
                    && taskSchema != LEGACY_CHECKPOINT_SCHEMA
                    || targetCount < 1 || targetCount > MAX_CHECKPOINT_TARGET_COUNT
                    || !rareMissionShape
                    || expectedRareMissionTarget != null
                    && rareMissionTarget != expectedRareMissionTarget
                    || torchLimit != MiningBudget.RARE_BATCH_TORCH_LIMIT
                    || torchPlacements < 0 || torchPlacements > torchLimit
                    || resourceEpoch < 0 || resourceEpoch >= epochCapacity
                    || !OreDigTask.oreFingerprint(ores).equals(fingerprint)
                    || budget < 0 || budget > maxBudget
                    || lastProgress < 0 || lastProgress > budget
                    || !cursorShape || !pendingPair || !pendingLastSeenPair || !activePair
                    || !rememberedHighWorkPoseShape
                    || !boundaryRerouteShape || !controlledStripRearShape
                    || pending != null && activeBreak != null
                    || !committedShape
                    || !deliveredShape
                    // Schemas 1-3 never recorded how much an open batch already delivered to
                    // inventory. Assuming zero would duplicate coal/iron as well as rare output.
                    || taskSchema < PREVIOUS_CHECKPOINT_SCHEMA && batchOpen
                    || !rareExpedition && (torchPlacements != 0 || resourceEpoch != 0)
                    || !batchOpen && (torchPlacements != 0 || resourceEpoch != 0
                    || inventoryServiceUsed)) {
                return Optional.empty();
            }
            MiningCursor cursor = new MiningCursor(cursorSchema, origin, face, direction, leg,
                    stepsLeft, legLength, batches);
            return Optional.of(new OreDigCheckpoint(CHECKPOINT_SCHEMA, targetCount, batchOpen,
                    delivered, rareMissionTarget, inventoryServiceUsed,
                    torchLimit, torchPlacements, resourceEpoch, cursor,
                    fingerprint, budget, lastProgress, controlledStripRear,
                    boundaryRerouteOrigin,
                    pending, pendingLastSeen,
                    pendingInventory,
                    pendingStarted, pendingGain, activeBreak, activeBreakInventory,
                    activeBreakConfirmedGone,
                    rememberedHighWorkPoses));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Set<String> withResourceKeys(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("torch_limit");
        keys.add("torch_placements");
        keys.add("resource_epoch");
        return Set.copyOf(keys);
    }

    private static Set<String> withMissionKeys(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("rare_mission_target");
        keys.add("inventory_service_used");
        return Set.copyOf(keys);
    }

    private static Set<String> withPickupLastSeenKey(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("pending_pickup_last_seen_pos");
        return Set.copyOf(keys);
    }

    private static Set<String> withDeliveredKey(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("delivered");
        return Set.copyOf(keys);
    }

    private static Set<String> withBoundaryRerouteKey(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("boundary_reroute_origin");
        return Set.copyOf(keys);
    }

    private static Set<String> withControlledStripRearKey(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("controlled_strip_rear");
        return Set.copyOf(keys);
    }

    private static Set<String> withRememberedHighWorkPosesKey(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("remembered_high_work_poses");
        return Set.copyOf(keys);
    }

    private static Set<String> withActiveBreakConfirmedGoneKey(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("active_break_confirmed_gone");
        return Set.copyOf(keys);
    }

    private static BlockPos optionalPos(Map<String, String> values, String key) {
        if (!values.containsKey(key)) {
            return null;
        }
        return decodeCheckpointPos(values.get(key)).orElseThrow();
    }

    private static int requiredInt(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static boolean strictBoolean(Map<String, String> values, String key) {
        return switch (required(values, key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("invalid_boolean:" + key);
        };
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_checkpoint_key:" + key);
        }
        return value;
    }
}
