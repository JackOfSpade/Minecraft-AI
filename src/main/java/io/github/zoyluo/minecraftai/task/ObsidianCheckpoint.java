package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.task.CreateObsidianTask.Phase;
import io.github.zoyluo.minecraftai.task.CreateObsidianTask.PourPlan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable, self-verifying restart snapshot for {@link CreateObsidianTask}: the checkpoint's own
 * decoder rejects any shape a live obsidian mission could never actually be in (see
 * {@code transactionShape}/{@code basicBudget}/{@code waterPair} etc. in {@link #decode}), so a
 * corrupted or hand-edited checkpoint fails closed instead of resuming into invented progress.
 *
 * <p>Kept behind {@link CreateObsidianTask}'s own static entry points ({@code inspectCheckpoint},
 * {@code acknowledgeServiceBoundary}, {@code nextServiceBoundary}) so every other caller keeps
 * calling {@code CreateObsidianTask.*} exactly as before; only the type itself moved out, the same
 * way {@link ObsidianSearchCursor} already lives outside {@code CreateObsidianTask.java}.</p>
 */
record ObsidianCheckpoint(int targetCount,
                          Phase phase,
                          ObsidianSearchCursor searchCursor,
                          BlockPos scanResumeFace,
                          int inventoryBaseline,
                          int collected,
                          int servicedCollected,
                          int pendingServiceBoundary,
                          int budgetUsed,
                          int phaseStartedBudget,
                          int lastProgressBudget,
                          int pickupGrace,
                          BlockPos waterTarget,
                          BlockPos waterSource,
                          BlockPos lavaClue,
                          BlockPos lavaTarget,
                          BlockPos obsidian,
                          BlockPos pickupPos,
                          BlockPos pickupLastSeenPos,
                          int pickupInventoryBaseline,
                          int pickupGainBudget,
                          BlockPos returnRim,
                          BlockPos obsidianStandHint,
                          BlockPos standPos,
                          PourPlan pourPlan,
                          int waterBucketBaseline,
                          boolean protectionPrepared,
                          BlockPos activeBreakPos,
                          int activeBreakInventoryBaseline,
                          UUID auditSessionToken) {
    // Mirrors CreateObsidianTask's own CHECKPOINT_SCHEMA/PREVIOUS_CHECKPOINT_SCHEMA/
    // LEGACY_CHECKPOINT_SCHEMA/SERVICE_INTERVAL/PICKUP_GRACE_TICKS. Duplicated rather than shared
    // because CreateObsidianTask still needs its own copies outside this checkpoint (e.g. the live
    // pickup-grace and service-boundary bookkeeping); a later package consolidates this
    // duplication (obsidianwater-refactor-004 orchestrator note).
    private static final int CHECKPOINT_SCHEMA = 3;
    private static final int PREVIOUS_CHECKPOINT_SCHEMA = 2;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 1;
    private static final int SERVICE_INTERVAL = 8;
    private static final int PICKUP_GRACE_TICKS = 30;

    ObsidianCheckpoint {
        scanResumeFace = immutable(scanResumeFace);
        waterTarget = immutable(waterTarget);
        waterSource = immutable(waterSource);
        lavaClue = immutable(lavaClue);
        lavaTarget = immutable(lavaTarget);
        obsidian = immutable(obsidian);
        pickupPos = immutable(pickupPos);
        pickupLastSeenPos = immutable(pickupLastSeenPos);
        returnRim = immutable(returnRim);
        obsidianStandHint = immutable(obsidianStandHint);
        standPos = immutable(standPos);
        activeBreakPos = immutable(activeBreakPos);
    }

    Phase resumePhase() {
        if (phase == Phase.PROTECT_PICKUP && pickupPos != null && waterSource != null) {
            // The remaining protection window is part of the durable drop transaction. A
            // restart must keep its original phaseStartedBudget instead of reclaiming early.
            return Phase.PROTECT_PICKUP;
        }
        return waterSource != null || waterBucketBaseline >= 0
                ? Phase.RECOVER_WATER : phase;
    }

    ObsidianCheckpoint acknowledgeService() {
        if (pendingServiceBoundary == 0) {
            return this;
        }
        return new ObsidianCheckpoint(
                targetCount, Phase.SCAN, searchCursor, scanResumeFace,
                inventoryBaseline, collected,
                pendingServiceBoundary, 0, budgetUsed, budgetUsed,
                Math.max(lastProgressBudget, budgetUsed), pickupGrace,
                waterTarget, waterSource, lavaClue, lavaTarget, obsidian, pickupPos,
                pickupLastSeenPos, pickupInventoryBaseline, pickupGainBudget, returnRim,
                obsidianStandHint, standPos, pourPlan, waterBucketBaseline,
                protectionPrepared, activeBreakPos, activeBreakInventoryBaseline,
                auditSessionToken);
    }

    Map<String, String> encode() {
        Map<String, String> values = new LinkedHashMap<>(searchCursor.encode());
        values.put("task_schema", String.valueOf(CHECKPOINT_SCHEMA));
        values.put("target_count", String.valueOf(targetCount));
        values.put("phase", phase.name());
        values.put("scan_resume_face", encodeCheckpointPos(scanResumeFace));
        values.put("inventory_baseline", String.valueOf(inventoryBaseline));
        values.put("collected", String.valueOf(collected));
        values.put("serviced_collected", String.valueOf(servicedCollected));
        values.put("pending_service_boundary", String.valueOf(pendingServiceBoundary));
        values.put("budget_used", String.valueOf(budgetUsed));
        values.put("phase_started", String.valueOf(phaseStartedBudget));
        values.put("last_progress", String.valueOf(lastProgressBudget));
        values.put("pickup_grace", String.valueOf(pickupGrace));
        values.put("water_bucket_baseline", String.valueOf(waterBucketBaseline));
        values.put("pending_pickup_inventory", String.valueOf(pickupInventoryBaseline));
        values.put("pickup_gain_budget", String.valueOf(pickupGainBudget));
        values.put("active_break_inventory", String.valueOf(activeBreakInventoryBaseline));
        values.put("protection_prepared", String.valueOf(protectionPrepared));
        if (auditSessionToken != null) {
            values.put("audit_session", auditSessionToken.toString());
        }
        putPos(values, "water_target", waterTarget);
        putPos(values, "water_source", waterSource);
        putPos(values, "lava_clue", lavaClue);
        putPos(values, "lava_target", lavaTarget);
        putPos(values, "obsidian", obsidian);
        putPos(values, "pending_pickup_pos", pickupPos);
        if (pickupLastSeenPos != null && !pickupLastSeenPos.equals(pickupPos)) {
            putPos(values, "pending_pickup_last_seen_pos", pickupLastSeenPos);
        }
        putPos(values, "return_rim", returnRim);
        putPos(values, "obsidian_stand_hint", obsidianStandHint);
        putPos(values, "stand", standPos);
        putPos(values, "active_break_pos", activeBreakPos);
        if (pourPlan != null) {
            values.put("pour_support", encodeCheckpointPos(pourPlan.support()));
            values.put("pour_face", pourPlan.face().name());
        }
        return Map.copyOf(values);
    }

    static Optional<ObsidianCheckpoint> decode(Map<String, String> values,
                                               int expectedTarget,
                                               int maxBudget) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        try {
            int schema = requiredInt(values, "task_schema");
            if (schema != CHECKPOINT_SCHEMA && schema != PREVIOUS_CHECKPOINT_SCHEMA
                    && schema != LEGACY_CHECKPOINT_SCHEMA
                    || requiredInt(values, "target_count") != expectedTarget) {
                return Optional.empty();
            }
            Phase phase = Phase.valueOf(required(values, "phase"));
            ObsidianSearchCursor cursor = ObsidianSearchCursor.decode(values).orElseThrow();
            BlockPos scanResumeFace = schema == CHECKPOINT_SCHEMA
                    ? decodeCheckpointPos(required(values, "scan_resume_face")).orElseThrow()
                    : cursor.face();
            int inventoryBaseline = requiredInt(values, "inventory_baseline");
            int collected = requiredInt(values, "collected");
            int servicedCollected = schema == LEGACY_CHECKPOINT_SCHEMA
                    ? 0 : requiredInt(values, "serviced_collected");
            int pendingServiceBoundary = schema == LEGACY_CHECKPOINT_SCHEMA
                    ? 0 : requiredInt(values, "pending_service_boundary");
            int budget = requiredInt(values, "budget_used");
            int phaseStarted = requiredInt(values, "phase_started");
            int lastProgress = requiredInt(values, "last_progress");
            int pickupGrace = requiredInt(values, "pickup_grace");
            int waterBaseline = requiredInt(values, "water_bucket_baseline");
            int pickupInventory = requiredInt(values, "pending_pickup_inventory");
            int pickupGain = requiredInt(values, "pickup_gain_budget");
            int activeBreakInventory = requiredInt(values, "active_break_inventory");
            boolean protectionPrepared = strictBoolean(values, "protection_prepared");
            UUID auditSessionToken = optionalUuid(values, "audit_session");

            BlockPos waterTarget = optionalPos(values, "water_target");
            BlockPos waterSource = optionalPos(values, "water_source");
            BlockPos lavaClue = optionalPos(values, "lava_clue");
            BlockPos lavaTarget = optionalPos(values, "lava_target");
            BlockPos obsidian = optionalPos(values, "obsidian");
            BlockPos pickupPos = optionalPos(values, "pending_pickup_pos");
            BlockPos pickupLastSeenPos = optionalPos(values,
                    "pending_pickup_last_seen_pos");
            if (pickupPos != null && pickupLastSeenPos == null) {
                // This field was introduced as an optional schema-3 extension. Existing
                // checkpoints resume from the factual break cell they already persisted.
                pickupLastSeenPos = pickupPos;
            }
            BlockPos returnRim = optionalPos(values, "return_rim");
            BlockPos obsidianStand = optionalPos(values, "obsidian_stand_hint");
            BlockPos stand = optionalPos(values, "stand");
            BlockPos activeBreak = optionalPos(values, "active_break_pos");
            PourPlan pourPlan = decodePourPlan(values);

            boolean basicBudget = inventoryBaseline >= 0 && inventoryBaseline <= 4096
                    && collected >= 0 && collected <= 4096
                    && servicedCollected >= 0 && servicedCollected <= collected
                    && servicedCollected % SERVICE_INTERVAL == 0
                    && servicedCollected < expectedTarget
                    && pendingServiceBoundary >= 0
                    && (pendingServiceBoundary == 0
                    || pendingServiceBoundary % SERVICE_INTERVAL == 0
                    && pendingServiceBoundary == servicedCollected + SERVICE_INTERVAL
                    && pendingServiceBoundary <= collected
                    && pendingServiceBoundary < expectedTarget)
                    && budget >= 0 && budget <= maxBudget
                    && phaseStarted >= 0 && phaseStarted <= budget
                    && lastProgress >= 0 && lastProgress <= budget
                    && pickupGrace >= 0 && pickupGrace <= PICKUP_GRACE_TICKS + 1;
            boolean waterPair = waterSource == null
                    ? waterBaseline == -1
                    || (waterBaseline > 0 && phase == Phase.RECOVER_WATER)
                    : waterBaseline > 0;
            boolean pickupPair = (pickupPos == null) == (pickupInventory == -1)
                    && pickupInventory >= -1 && pickupInventory <= 4096
                    && (pickupGain == -1
                    || pickupPos != null && pickupGain >= 0 && pickupGain <= budget);
            boolean pickupLastSeenPair = (pickupPos == null) == (pickupLastSeenPos == null)
                    && (pickupLastSeenPos == null
                    || Math.abs((long) pickupLastSeenPos.getX() - pickupPos.getX()) <= 8L
                    && Math.abs((long) pickupLastSeenPos.getY() - pickupPos.getY()) <= 4L
                    && Math.abs((long) pickupLastSeenPos.getZ() - pickupPos.getZ()) <= 8L);
            boolean activeBreakPair = (activeBreak == null) == (activeBreakInventory == -1)
                    && activeBreakInventory >= -1 && activeBreakInventory <= 4096
                    && (activeBreak == null || activeBreak.equals(obsidian));
            boolean pourPair = (values.containsKey("pour_support"))
                    == values.containsKey("pour_face");
            boolean pendingPhase = phase == Phase.PROTECT_PICKUP
                    || phase == Phase.PICKUP
                    || phase == Phase.RECOVER_WATER
                    || phase == Phase.WAIT_DRAIN;
            boolean activeBreakPhase = phase == Phase.MINE
                    || phase == Phase.APPROACH_OBSIDIAN
                    || phase == Phase.RECOVER_WATER
                    || phase == Phase.WAIT_DRAIN;
            boolean transactionShape = !(pickupPos != null && activeBreak != null)
                    && (pickupPos == null || pendingPhase)
                    && (activeBreak == null || activeBreakPhase)
                    && (!protectionPrepared || obsidian != null)
                    && (phase != Phase.MINE || activeBreak != null)
                    && (pickupPos == null || returnRim != null)
                    && (phase != Phase.PROTECT_PICKUP && phase != Phase.PICKUP
                    || pickupPos != null)
                    && (phase != Phase.APPROACH_WATER || waterTarget != null && stand != null)
                    && (phase != Phase.APPROACH_LAVA_VIEW
                    || lavaClue != null && stand != null && pourPlan != null)
                    && (phase != Phase.APPROACH_LAVA && phase != Phase.POUR
                    || lavaTarget != null && stand != null && pourPlan != null)
                    && (phase != Phase.WAIT_FORM
                    || lavaTarget != null && waterSource != null)
                    && (phase != Phase.WAIT_WATER_SPREAD || waterSource != null)
                    && (phase != Phase.RECOVER_WATER || waterBaseline >= 0)
                    && (phase != Phase.APPROACH_OBSIDIAN && phase != Phase.PROTECT_OBSIDIAN
                    && phase != Phase.MINE || obsidian != null && stand != null)
                    && (phase != Phase.PROTECT_OBSIDIAN || pourPlan != null)
                    && (phase != Phase.RETURN_TO_RIM || returnRim != null)
                    && (phase != Phase.SERVICE_BOUNDARY
                    || pendingServiceBoundary > 0
                    && waterTarget == null && waterSource == null
                    && lavaClue == null && lavaTarget == null && obsidian == null
                    && pickupPos == null && pickupLastSeenPos == null
                    && returnRim == null && obsidianStand == null
                    && stand == null && pourPlan == null && activeBreak == null
                    && waterBaseline == -1 && pickupInventory == -1
                    && activeBreakInventory == -1)
                    && (phase != Phase.DONE
                    || collected >= expectedTarget && pendingServiceBoundary == 0);
            if (!basicBudget || !waterPair || !pickupPair || !pickupLastSeenPair
                    || !activeBreakPair
                    || !pourPair || !transactionShape) {
                return Optional.empty();
            }
            return Optional.of(new ObsidianCheckpoint(expectedTarget, phase, cursor,
                    scanResumeFace,
                    inventoryBaseline, collected, servicedCollected, pendingServiceBoundary,
                    budget, phaseStarted, lastProgress,
                    pickupGrace, waterTarget, waterSource, lavaClue, lavaTarget, obsidian,
                    pickupPos, pickupLastSeenPos, pickupInventory, pickupGain, returnRim,
                    obsidianStand, stand, pourPlan, waterBaseline, protectionPrepared,
                    activeBreak, activeBreakInventory, auditSessionToken));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static PourPlan decodePourPlan(Map<String, String> values) {
        boolean supportPresent = values.containsKey("pour_support");
        boolean facePresent = values.containsKey("pour_face");
        if (!supportPresent && !facePresent) {
            return null;
        }
        if (!supportPresent || !facePresent) {
            throw new IllegalArgumentException("partial pour plan");
        }
        BlockPos support = decodeCheckpointPos(required(values, "pour_support")).orElseThrow();
        return new PourPlan(support, Direction.valueOf(required(values, "pour_face")));
    }

    private static BlockPos optionalPos(Map<String, String> values, String key) {
        if (!values.containsKey(key)) {
            return null;
        }
        return decodeCheckpointPos(values.get(key)).orElseThrow();
    }

    private static UUID optionalUuid(Map<String, String> values, String key) {
        if (!values.containsKey(key)) {
            return null;
        }
        return UUID.fromString(required(values, key));
    }

    private static void putPos(Map<String, String> values, String key, BlockPos pos) {
        if (pos != null) {
            values.put(key, encodeCheckpointPos(pos));
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private static int requiredInt(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static boolean strictBoolean(Map<String, String> values, String key) {
        String value = required(values, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("invalid boolean " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static BlockPos immutable(BlockPos pos) {
        return pos == null ? null : pos.toImmutable();
    }

    // Duplicated from CreateObsidianTask (encodeCheckpointPos/decodeCheckpointPos): see the
    // schema-constant duplication note above; a later package consolidates this too.
    private static String encodeCheckpointPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Optional<BlockPos> decodeCheckpointPos(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = value.split(",");
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
