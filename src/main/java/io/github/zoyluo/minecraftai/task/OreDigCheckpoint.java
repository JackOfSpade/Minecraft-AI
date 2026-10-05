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
                        Map<BlockPos, BlockPos> rememberedHighWorkPoses,
                        Set<BlockPos> openedVeinBreaks,
                        Set<BlockPos> queuedVeinHints) {
    static final int CHECKPOINT_SCHEMA = 6;
    /** Schema 5 first persisted factual opened-seam break anchors. */
    private static final int PREVIOUS_CHECKPOINT_SCHEMA = 5;
    /** Schema 4 first persisted delivered counts and high work poses. */
    private static final int DELIVERED_CHECKPOINT_SCHEMA = 4;
    private static final int MISSION_CHECKPOINT_SCHEMA = 3;
    private static final int RESOURCE_EPOCH_CHECKPOINT_SCHEMA = 2;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 1;
    private static final int MAX_CHECKPOINT_TARGET_COUNT = 4096;
    private static final int MAX_CURSOR_LEGS = 4096;
    /**
     * The open-seam ledger is a recovery hint, never mining authority.  Keep it finite even for
     * an unusually large modded vein: each retained cell is re-observed before it can queue work
     * after a restart.
     */
    private static final int MAX_OPENED_VEIN_BREAKS = MAX_CHECKPOINT_TARGET_COUNT;
    /**
     * A factual anchor may be farther from the current face than the ordinary scan radius.  That
     * is normal for a long, branched count-mode seam, but a finite envelope prevents a corrupted
     * checkpoint from turning recovery into an unbounded remote observation sweep.
     */
    private static final int MAX_OPENED_VEIN_ANCHOR_OFFSET = MAX_CHECKPOINT_TARGET_COUNT;
    /** Three signed int coordinates plus commas: {@code -2147483648,-2147483648,-2147483648}. */
    private static final int MAX_CHECKPOINT_POS_TEXT_LENGTH = 35;

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

    /**
     * Factual ore cells already broken in the currently open connected seam.  This is deliberately
     * a frontier reconstruction hint, not a queued target list: after a restart OreDig re-observes
     * every neighbour before mining anything further.
     */
    private static String encodeOpenedVeinBreaks(Set<BlockPos> breaks) {
        return breaks.stream()
                .sorted(java.util.Comparator.comparing(OreDigCheckpoint::encodeCheckpointPos))
                .map(OreDigCheckpoint::encodeCheckpointPos)
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Optional<Set<BlockPos>> decodeOpenedVeinBreaks(String value) {
        if (value == null) {
            return Optional.of(Set.of());
        }
        if (value.isBlank()) {
            return Optional.empty();
        }
        Set<BlockPos> decoded = new java.util.LinkedHashSet<>();
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf(';', start);
            if (end < 0) {
                end = value.length();
            }
            // Reject an overlong entry before substring/split can allocate unbounded temporary
            // arrays for a malformed persisted checkpoint.
            if (end == start || end - start > MAX_CHECKPOINT_POS_TEXT_LENGTH
                    || decoded.size() >= MAX_OPENED_VEIN_BREAKS) {
                return Optional.empty();
            }
            String entry = value.substring(start, end);
            BlockPos pos = decodeCheckpointPos(entry).orElse(null);
            if (pos == null || !decoded.add(pos)) {
                return Optional.empty();
            }
            if (end == value.length()) {
                break;
            }
            if (end == value.length() - 1) {
                return Optional.empty();
            }
            start = end + 1;
        }
        return Optional.of(Set.copyOf(decoded));
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
    private static final Set<String> DELIVERED_REQUIRED_KEYS =
            withDeliveredKey(MISSION_REQUIRED_KEYS);
    private static final Set<String> DELIVERED_ALLOWED_KEYS =
            withRememberedHighWorkPosesKey(
                    withControlledStripRearKey(
                            withBoundaryRerouteKey(withDeliveredKey(MISSION_ALLOWED_KEYS))));
    private static final Set<String> PREVIOUS_REQUIRED_KEYS =
            withActiveBreakConfirmedGoneKey(DELIVERED_REQUIRED_KEYS);
    private static final Set<String> PREVIOUS_ALLOWED_KEYS =
            withOpenedVeinBreaksKey(withActiveBreakConfirmedGoneKey(DELIVERED_ALLOWED_KEYS));
    private static final Set<String> REQUIRED_KEYS = PREVIOUS_REQUIRED_KEYS;
    private static final Set<String> ALLOWED_KEYS =
            withQueuedVeinHintsKey(PREVIOUS_ALLOWED_KEYS);

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
        if (openedVeinBreaks == null || openedVeinBreaks.isEmpty()) {
            openedVeinBreaks = Set.of();
        } else {
            openedVeinBreaks = normalizeOpenedVeinBreaks(openedVeinBreaks,
                    cursor == null ? null : cursor.face());
        }
        if (queuedVeinHints == null || queuedVeinHints.isEmpty()) {
            queuedVeinHints = Set.of();
        } else {
            queuedVeinHints = normalizeOpenedVeinBreaks(queuedVeinHints,
                    cursor == null ? null : cursor.face());
        }
        if (!java.util.Collections.disjoint(openedVeinBreaks, queuedVeinHints)) {
            throw new IllegalArgumentException("opened_and_queued_vein_overlap");
        }
    }

    /**
     * Canonicalizes a live seam ledger into a bounded, spatially distributed set of factual
     * anchors.  Normal gameplay commonly keeps every fact exactly; coalescing runs only when an
     * exceptional seam exceeds the wire cap.  It prevents a valid running task from encoding a
     * checkpoint which this same codec would immediately reject on restore.
     */
    private static Set<BlockPos> normalizeOpenedVeinBreaks(Set<BlockPos> breaks,
                                                            BlockPos face) {
        Set<BlockPos> inEnvelope = new java.util.LinkedHashSet<>();
        for (BlockPos broken : breaks) {
            BlockPos immutable = broken == null ? null : broken.immutable();
            if (immutable == null || !inEnvelope.add(immutable)) {
                throw new IllegalArgumentException("invalid_opened_vein_break");
            }
        }
        if (face != null) {
            inEnvelope.removeIf(broken -> !isOpenedVeinBreakAnchorInEnvelope(face, broken));
        }
        if (inEnvelope.size() <= MAX_OPENED_VEIN_BREAKS) {
            return Set.copyOf(inEnvelope);
        }
        return spatiallyBoundOpenedVeinBreaks(inEnvelope);
    }

    /**
     * Coalesces an oversized seam with an adaptive spatial tree.  Dense old portions get split
     * repeatedly while a remote branch keeps its own leaf, so a restart retains anchors across
     * the whole seam rather than retaining only the first coordinate-sorted portion.
     */
    private static Set<BlockPos> spatiallyBoundOpenedVeinBreaks(Set<BlockPos> breaks) {
        java.util.List<VeinAnchorRegion> leaves = new java.util.ArrayList<>();
        java.util.PriorityQueue<VeinAnchorRegion> splittable = new java.util.PriorityQueue<>(
                java.util.Comparator
                        .<VeinAnchorRegion>comparingInt(region -> region.anchors().size())
                        .reversed()
                        .thenComparing(java.util.Comparator
                                .comparingLong(OreDigCheckpoint::longestAnchorRegionSpan)
                                .reversed())
                        .thenComparingLong(VeinAnchorRegion::minX)
                        .thenComparingLong(VeinAnchorRegion::minY)
                        .thenComparingLong(VeinAnchorRegion::minZ)
                        .thenComparingLong(VeinAnchorRegion::maxX)
                        .thenComparingLong(VeinAnchorRegion::maxY)
                        .thenComparingLong(VeinAnchorRegion::maxZ));
        VeinAnchorRegion root = VeinAnchorRegion.of(breaks);
        leaves.add(root);
        splittable.add(root);
        while (leaves.size() < MAX_OPENED_VEIN_BREAKS && !splittable.isEmpty()) {
            VeinAnchorRegion region = splittable.poll();
            java.util.List<VeinAnchorRegion> children = splitAnchorRegion(region);
            if (children.size() <= 1
                    || leaves.size() - 1 + children.size() > MAX_OPENED_VEIN_BREAKS) {
                continue;
            }
            leaves.remove(region);
            leaves.addAll(children);
            for (VeinAnchorRegion child : children) {
                if (child.anchors().size() > 1) {
                    splittable.add(child);
                }
            }
        }
        java.util.LinkedHashSet<BlockPos> ordered = leaves.stream()
                .map(VeinAnchorRegion::centerAnchor)
                .sorted(java.util.Comparator.comparing(OreDigCheckpoint::encodeCheckpointPos))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return Set.copyOf(ordered);
    }

    private static long longestAnchorRegionSpan(VeinAnchorRegion region) {
        return Math.max(region.maxX() - region.minX(), Math.max(
                region.maxY() - region.minY(), region.maxZ() - region.minZ()));
    }

    private static java.util.List<VeinAnchorRegion> splitAnchorRegion(VeinAnchorRegion region) {
        if (region.anchors().size() <= 1) {
            return java.util.List.of(region);
        }
        long midX = region.minX() + (region.maxX() - region.minX()) / 2L;
        long midY = region.minY() + (region.maxY() - region.minY()) / 2L;
        long midZ = region.minZ() + (region.maxZ() - region.minZ()) / 2L;
        Map<Integer, java.util.List<BlockPos>> partitions = new java.util.HashMap<>();
        for (BlockPos anchor : region.anchors()) {
            int partition = (anchor.getX() > midX ? 4 : 0)
                    | (anchor.getY() > midY ? 2 : 0)
                    | (anchor.getZ() > midZ ? 1 : 0);
            partitions.computeIfAbsent(partition, ignored -> new java.util.ArrayList<>()).add(anchor);
        }
        return partitions.values().stream()
                .map(VeinAnchorRegion::of)
                .sorted(java.util.Comparator
                        .comparingLong(VeinAnchorRegion::minX)
                        .thenComparingLong(VeinAnchorRegion::minY)
                        .thenComparingLong(VeinAnchorRegion::minZ))
                .toList();
    }

    private static boolean isOpenedVeinBreakAnchorInEnvelope(BlockPos face, BlockPos anchor) {
        return face != null && anchor != null
                && Math.abs((long) anchor.getX() - face.getX()) <= MAX_OPENED_VEIN_ANCHOR_OFFSET
                && Math.abs((long) anchor.getY() - face.getY()) <= MAX_OPENED_VEIN_ANCHOR_OFFSET
                && Math.abs((long) anchor.getZ() - face.getZ()) <= MAX_OPENED_VEIN_ANCHOR_OFFSET;
    }

    private record VeinAnchorRegion(java.util.List<BlockPos> anchors,
                                    long minX,
                                    long maxX,
                                    long minY,
                                    long maxY,
                                    long minZ,
                                    long maxZ) {
        private static VeinAnchorRegion of(java.util.Collection<BlockPos> anchors) {
            java.util.List<BlockPos> immutable = java.util.List.copyOf(anchors);
            long minX = immutable.stream().mapToLong(BlockPos::getX).min().orElseThrow();
            long maxX = immutable.stream().mapToLong(BlockPos::getX).max().orElseThrow();
            long minY = immutable.stream().mapToLong(BlockPos::getY).min().orElseThrow();
            long maxY = immutable.stream().mapToLong(BlockPos::getY).max().orElseThrow();
            long minZ = immutable.stream().mapToLong(BlockPos::getZ).min().orElseThrow();
            long maxZ = immutable.stream().mapToLong(BlockPos::getZ).max().orElseThrow();
            return new VeinAnchorRegion(immutable, minX, maxX, minY, maxY, minZ, maxZ);
        }

        private BlockPos centerAnchor() {
            return anchors.stream().min(java.util.Comparator
                    .comparingLong(this::distanceFromCenter)
                    .thenComparing(OreDigCheckpoint::encodeCheckpointPos)).orElseThrow();
        }

        private long distanceFromCenter(BlockPos pos) {
            return Math.abs(2L * pos.getX() - (minX + maxX))
                    + Math.abs(2L * pos.getY() - (minY + maxY))
                    + Math.abs(2L * pos.getZ() - (minZ + maxZ));
        }
    }

    /** Compatibility constructor for schema-5 callers before queued seam hints were durable. */
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
                     boolean activeBreakConfirmedGone,
                     Map<BlockPos, BlockPos> rememberedHighWorkPoses,
                     Set<BlockPos> openedVeinBreaks) {
        this(taskSchema, targetCount, batchOpen, delivered, rareMissionTarget,
                inventoryServiceUsed, torchLimit, torchPlacements, resourceEpoch, cursor,
                oreFingerprint, budgetUsed, lastProgressBudget, controlledStripRear,
                boundaryRerouteOrigin, pendingPickupPos, pendingPickupLastSeenPos,
                pendingPickupInventory, pendingPickupStartedBudget, pendingPickupGainBudget,
                activeBreakPos, activeBreakInventory, activeBreakConfirmedGone,
                rememberedHighWorkPoses, openedVeinBreaks, Set.of());
    }

    /** Compatibility constructor for schema-5 callers before open-seam facts were durable. */
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
                     boolean activeBreakConfirmedGone,
                     Map<BlockPos, BlockPos> rememberedHighWorkPoses) {
        this(taskSchema, targetCount, batchOpen, delivered, rareMissionTarget,
                inventoryServiceUsed, torchLimit, torchPlacements, resourceEpoch, cursor,
                oreFingerprint, budgetUsed, lastProgressBudget, controlledStripRear,
                boundaryRerouteOrigin, pendingPickupPos, pendingPickupLastSeenPos,
                pendingPickupInventory, pendingPickupStartedBudget, pendingPickupGainBudget,
                activeBreakPos, activeBreakInventory, activeBreakConfirmedGone,
                rememberedHighWorkPoses, Set.of(), Set.of());
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
                activeBreakPos, activeBreakInventory, false, rememberedHighWorkPoses, Set.of(), Set.of());
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
                activeBreakPos, activeBreakInventory, false, Map.of(), Set.of(), Set.of());
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
                rememberedHighWorkPoses, openedVeinBreaks, queuedVeinHints);
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
                rememberedHighWorkPoses, openedVeinBreaks, queuedVeinHints);
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
                rememberedHighWorkPoses, openedVeinBreaks, queuedVeinHints);
    }

    Map<String, String> encode() {
        Map<String, String> values = new java.util.LinkedHashMap<>(cursor.encode());
        values.put("task_schema", String.valueOf(taskSchema));
        values.put("target_count", String.valueOf(targetCount));
        values.put("batch_open", String.valueOf(batchOpen));
        if (taskSchema >= DELIVERED_CHECKPOINT_SCHEMA) {
            values.put("delivered", String.valueOf(delivered));
        }
        if (taskSchema >= MISSION_CHECKPOINT_SCHEMA) {
            values.put("rare_mission_target", String.valueOf(rareMissionTarget));
            values.put("inventory_service_used", String.valueOf(inventoryServiceUsed));
        }
        if (taskSchema >= RESOURCE_EPOCH_CHECKPOINT_SCHEMA) {
            values.put("torch_limit", String.valueOf(torchLimit));
            values.put("torch_placements", String.valueOf(torchPlacements));
            values.put("resource_epoch", String.valueOf(resourceEpoch));
        }
        values.put("budget_used", String.valueOf(budgetUsed));
        values.put("last_progress_budget", String.valueOf(lastProgressBudget));
        values.put("ore_fingerprint", oreFingerprint);
        values.put("pending_pickup_inventory", String.valueOf(pendingPickupInventory));
        values.put("pending_pickup_started_budget", String.valueOf(pendingPickupStartedBudget));
        values.put("pickup_gain_budget", String.valueOf(pendingPickupGainBudget));
        values.put("active_break_inventory", String.valueOf(activeBreakInventory));
        if (taskSchema >= PREVIOUS_CHECKPOINT_SCHEMA) {
            values.put("active_break_confirmed_gone", String.valueOf(activeBreakConfirmedGone));
        }
        if (taskSchema >= DELIVERED_CHECKPOINT_SCHEMA && controlledStripRear != null) {
            values.put("controlled_strip_rear",
                    encodeCheckpointPos(controlledStripRear));
        }
        if (taskSchema >= DELIVERED_CHECKPOINT_SCHEMA && boundaryRerouteOrigin != null) {
            values.put("boundary_reroute_origin",
                    encodeCheckpointPos(boundaryRerouteOrigin));
        }
        if (pendingPickupPos != null) {
            values.put("pending_pickup_pos", encodeCheckpointPos(pendingPickupPos));
        }
        if (taskSchema >= MISSION_CHECKPOINT_SCHEMA
                && pendingPickupLastSeenPos != null
                && !pendingPickupLastSeenPos.equals(pendingPickupPos)) {
            values.put("pending_pickup_last_seen_pos",
                    encodeCheckpointPos(pendingPickupLastSeenPos));
        }
        if (activeBreakPos != null) {
            values.put("active_break_pos", encodeCheckpointPos(activeBreakPos));
        }
        if (taskSchema >= DELIVERED_CHECKPOINT_SCHEMA && !rememberedHighWorkPoses.isEmpty()) {
            values.put("remembered_high_work_poses",
                    encodeRememberedHighWorkPoses(rememberedHighWorkPoses));
        }
        // Schema 4 predates the factual open-seam ledger.  Compatibility fixtures may still
        // construct an older record shape, but its wire form must not smuggle a newer optional
        // key into a schema whose strict decoder correctly rejects it.
        if (taskSchema >= PREVIOUS_CHECKPOINT_SCHEMA && !openedVeinBreaks.isEmpty()) {
            values.put("opened_vein_breaks", encodeOpenedVeinBreaks(openedVeinBreaks));
        }
        if (taskSchema == CHECKPOINT_SCHEMA && !queuedVeinHints.isEmpty()) {
            values.put("queued_vein_hints", encodeOpenedVeinBreaks(queuedVeinHints));
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
                case DELIVERED_CHECKPOINT_SCHEMA -> DELIVERED_REQUIRED_KEYS;
                case PREVIOUS_CHECKPOINT_SCHEMA -> PREVIOUS_REQUIRED_KEYS;
                case CHECKPOINT_SCHEMA -> REQUIRED_KEYS;
                default -> Set.of();
            };
            Set<String> allowedKeys = switch (taskSchema) {
                case LEGACY_CHECKPOINT_SCHEMA -> LEGACY_ALLOWED_KEYS;
                case RESOURCE_EPOCH_CHECKPOINT_SCHEMA -> RESOURCE_ALLOWED_KEYS;
                case MISSION_CHECKPOINT_SCHEMA -> MISSION_ALLOWED_KEYS;
                case DELIVERED_CHECKPOINT_SCHEMA -> DELIVERED_ALLOWED_KEYS;
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
            int delivered = taskSchema >= DELIVERED_CHECKPOINT_SCHEMA
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
            BlockPos controlledStripRear = taskSchema >= DELIVERED_CHECKPOINT_SCHEMA
                    ? optionalPos(values, "controlled_strip_rear") : null;
            BlockPos boundaryRerouteOrigin = taskSchema >= DELIVERED_CHECKPOINT_SCHEMA
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
            boolean activeBreakConfirmedGone = taskSchema >= PREVIOUS_CHECKPOINT_SCHEMA
                    && strictBoolean(values, "active_break_confirmed_gone");
            Map<BlockPos, BlockPos> rememberedHighWorkPoses =
                    taskSchema >= DELIVERED_CHECKPOINT_SCHEMA
                            ? decodeRememberedHighWorkPoses(
                            values.get("remembered_high_work_poses")).orElseThrow()
                            : Map.of();
            Set<BlockPos> openedVeinBreaks = decodeOpenedVeinBreaks(
                    values.get("opened_vein_breaks")).orElseThrow();
            Set<BlockPos> queuedVeinHints = taskSchema == CHECKPOINT_SCHEMA
                    ? decodeOpenedVeinBreaks(values.get("queued_vein_hints")).orElseThrow()
                    : Set.of();

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
            boolean openedVeinBreakShape = openedVeinBreaks.size() <= MAX_OPENED_VEIN_BREAKS
                    && (batchOpen || openedVeinBreaks.isEmpty())
                    && openedVeinBreaks.stream().allMatch(pos ->
                    isOpenedVeinBreakAnchorInEnvelope(face, pos));
            boolean queuedVeinHintShape = queuedVeinHints.size() <= MAX_OPENED_VEIN_BREAKS
                    && (batchOpen || queuedVeinHints.isEmpty())
                    && java.util.Collections.disjoint(openedVeinBreaks, queuedVeinHints)
                    && queuedVeinHints.stream().allMatch(pos ->
                    isOpenedVeinBreakAnchorInEnvelope(face, pos));
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
                    && taskSchema != DELIVERED_CHECKPOINT_SCHEMA
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
                    || !rememberedHighWorkPoseShape || !openedVeinBreakShape || !queuedVeinHintShape
                    || !boundaryRerouteShape || !controlledStripRearShape
                    || pending != null && activeBreak != null
                    || !committedShape
                    || !deliveredShape
                    // Schemas 1-3 never recorded how much an open batch already delivered to
                    // inventory. Assuming zero would duplicate coal/iron as well as rare output.
                    || taskSchema < DELIVERED_CHECKPOINT_SCHEMA && batchOpen
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
                    rememberedHighWorkPoses, openedVeinBreaks, queuedVeinHints));
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

    private static Set<String> withOpenedVeinBreaksKey(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("opened_vein_breaks");
        return Set.copyOf(keys);
    }

    private static Set<String> withQueuedVeinHintsKey(Set<String> base) {
        Set<String> keys = new java.util.HashSet<>(base);
        keys.add("queued_vein_hints");
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
