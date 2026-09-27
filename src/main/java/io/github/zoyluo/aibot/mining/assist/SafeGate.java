package io.github.zoyluo.aibot.mining.assist;

import java.util.Objects;

/**
 * The pure SAFE gate of the detour (mining-assist design 4.4, invariant I7): a function of
 * {@link SafeGateInputs} and a {@link Stage}. The Minecraft side ({@code task/DetourSafetyGate}) fills the
 * inputs from live state every time it is asked, because tasks tick before the watchers and a cached
 * DangerWatcher verdict is always one tick old. This class decides, in {@link SafeReason} order, which
 * condition fails first; the first failing reason wins.
 *
 * <h2>Stages</h2>
 * <ul>
 *   <li>{@link Stage#START}: items 1 to 10, with the start thresholds. Used only by the selector that decides whether
 *       a detour may start. A running detour uses {@link Stage#TICK_FULL} for its extra checks before a break, a route
 *       leg and a drop chase, so the start/abort hysteresis of {@code TickHeadroom} and the hp margin do not turn a
 *       healthy detour into an abort (see the P1 contract, review log).</li>
 *   <li>{@link Stage#TICK_FAST}: items 1 to 4 with the tick thresholds. Evaluated on every detour tick.</li>
 *   <li>{@link Stage#TICK_FULL}: items 1 to 10 with the tick thresholds. Evaluated on every second detour
 *       tick (the engine picks FAST or FULL by the parity of the ticks since the detour started).</li>
 * </ul>
 *
 * <h2>Thresholds</h2>
 * <ul>
 *   <li>Item 1: {@code !modeAllowsDetour} is MODE, {@code !originReal} is ORIGIN, {@code auditSession} is AUDIT.
 *       (The design lists item 1 for START only. Evaluating it on TICK too costs three boolean reads and closes
 *       a detour when an audit session begins mid-run; see the P1 contract.)</li>
 *   <li>Item 2: {@code tpsDegraded} is TPS. START then requires {@code headroomStartOk}, TICK requires
 *       {@code !headroomAbort}; either failing is HEADROOM. The input builder fills exactly one of the two per
 *       stage and leaves the other at its default.</li>
 *   <li>Item 3: START requires {@code health >= retreatHp + startHpMargin} (14 by default), TICK requires
 *       {@code health > retreatHp} (10); a failure is HP. Then {@code hurtTime > 0} is HURT, {@code onFire}
 *       ON_FIRE, {@code inLava} IN_LAVA, {@code submerged} SUBMERGED, {@code touchingWater} TOUCHING_WATER, and
 *       {@code foodLevel <= hungerCritical} FOOD.</li>
 *   <li>Item 4: {@code waterRescueActive} WATER_RESCUE, {@code pausedDepth > 0} PAUSED, {@code userPaused}
 *       USER_PAUSED, {@code originSafety} ORIGIN_SAFETY.</li>
 *   <li>Item 5: {@code threatCooldown} THREAT_COOLDOWN, {@code shelterEpisode} SHELTER_EPISODE.</li>
 *   <li>Item 6: {@code hostilePressure} HOSTILE_PRESSURE.</li>
 *   <li>Item 7: {@code lavaInThreatBox} LAVA_THREAT_BOX, {@code hazardLavaNear} HAZARD_LAVA.</li>
 *   <li>Item 8: {@code deepDark} DEEP_DARK_BIOME (the builder has already applied {@code safety.deepDarkVeto}).</li>
 *   <li>Item 9: POI_EVIDENCE when {@code poiEvidenceStale} (a fact that was never computed or is old is not "no
 *       evidence": fail closed), or {@code poiStructureScore >= POI_S_LIMIT}, or {@code poiWindowVeto}, or
 *       {@code poiCandidatePending}, or {@code inNoDetourZone}.</li>
 *   <li>Item 10: {@code trapNear} TRAP_SPOT.</li>
 * </ul>
 *
 * <p>A stage ignores the input fields of the items it does not read, so a caller may leave them at their
 * defaults (this is what {@code DetourSafetyGate.inputs} does to keep the per-tick cost down).</p>
 */
public final class SafeGate {
    /** Design 4.4 item 9: structure score S at or above this is POI evidence. */
    public static final double POI_S_LIMIT = 0.35D;

    /** Which slice of the predicate is evaluated. */
    public enum Stage {
        START,
        TICK_FAST,
        TICK_FULL;

        /** Whether this stage reads design item {@code item} (1..10). */
        public boolean reads(int item) {
            return switch (this) {
                case START, TICK_FULL -> item >= 1 && item <= 10;
                case TICK_FAST -> item >= 1 && item <= 4;
            };
        }

        /** START uses the start thresholds (hp margin, headroom start gate); both TICK stages use the tick ones. */
        public boolean isStart() {
            return this == START;
        }
    }

    private SafeGate() {
    }

    /**
     * Evaluates the gate. Returns the first failing {@link SafeReason} in enum order, or {@link SafeReason#OK}.
     * Never throws for a non-null argument pair; a null stage or inputs is a programming error and throws
     * {@link NullPointerException}.
     */
    public static SafeReason evaluate(SafeGateInputs inputs, Stage stage) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(stage, "stage");

        if (stage.reads(1)) {
            if (!inputs.modeAllowsDetour()) {
                return SafeReason.MODE;
            }
            if (!inputs.originReal()) {
                return SafeReason.ORIGIN;
            }
            if (inputs.auditSession()) {
                return SafeReason.AUDIT;
            }
        }
        if (stage.reads(2)) {
            if (inputs.tpsDegraded()) {
                return SafeReason.TPS;
            }
            boolean headroomFails = stage.isStart() ? !inputs.headroomStartOk() : inputs.headroomAbort();
            if (headroomFails) {
                return SafeReason.HEADROOM;
            }
        }
        if (stage.reads(3)) {
            boolean hpFails = stage.isStart()
                    ? inputs.health() < inputs.retreatHp() + inputs.startHpMargin()
                    : inputs.health() <= inputs.retreatHp();
            if (hpFails) {
                return SafeReason.HP;
            }
            if (inputs.hurtTime() > 0) {
                return SafeReason.HURT;
            }
            if (inputs.onFire()) {
                return SafeReason.ON_FIRE;
            }
            if (inputs.inLava()) {
                return SafeReason.IN_LAVA;
            }
            if (inputs.submerged()) {
                return SafeReason.SUBMERGED;
            }
            if (inputs.touchingWater()) {
                return SafeReason.TOUCHING_WATER;
            }
            if (inputs.foodLevel() <= inputs.hungerCritical()) {
                return SafeReason.FOOD;
            }
        }
        if (stage.reads(4)) {
            if (inputs.waterRescueActive()) {
                return SafeReason.WATER_RESCUE;
            }
            if (inputs.pausedDepth() > 0) {
                return SafeReason.PAUSED;
            }
            if (inputs.userPaused()) {
                return SafeReason.USER_PAUSED;
            }
            if (inputs.originSafety()) {
                return SafeReason.ORIGIN_SAFETY;
            }
        }
        if (stage.reads(5)) {
            if (inputs.threatCooldown()) {
                return SafeReason.THREAT_COOLDOWN;
            }
            if (inputs.shelterEpisode()) {
                return SafeReason.SHELTER_EPISODE;
            }
        }
        if (stage.reads(6)) {
            if (inputs.hostilePressure()) {
                return SafeReason.HOSTILE_PRESSURE;
            }
        }
        if (stage.reads(7)) {
            if (inputs.lavaInThreatBox()) {
                return SafeReason.LAVA_THREAT_BOX;
            }
            if (inputs.hazardLavaNear()) {
                return SafeReason.HAZARD_LAVA;
            }
        }
        if (stage.reads(8)) {
            if (inputs.deepDark()) {
                return SafeReason.DEEP_DARK_BIOME;
            }
        }
        if (stage.reads(9)) {
            if (inputs.poiEvidenceStale()
                    || inputs.poiStructureScore() >= POI_S_LIMIT
                    || inputs.poiWindowVeto()
                    || inputs.poiCandidatePending()
                    || inputs.inNoDetourZone()) {
                return SafeReason.POI_EVIDENCE;
            }
        }
        if (stage.reads(10)) {
            if (inputs.trapNear()) {
                return SafeReason.TRAP_SPOT;
            }
        }
        return SafeReason.OK;
    }

    /**
     * Item 9 helper for the input builder: true when {@code window} holds a cell that vetoes a detour by
     * itself, that is any entry whose bucket is {@link PoiBucket#SCULK_STRUCT} or {@link PoiBucket#SPAWNER}, or
     * whose flags contain {@link PoiEvidenceFlags#REINFORCED_DEEPSLATE}. Looks at the structural and the
     * flag-only entries. A null or empty window gives false.
     */
    public static boolean poiWindowVeto(PoiEvidenceWindow window) {
        if (window == null || window.isEmpty()) {
            return false;
        }
        for (PoiEvidenceWindow.Entry entry : window.structuralEntries()) {
            if (vetoes(entry)) {
                return true;
            }
        }
        for (PoiEvidenceWindow.Entry entry : window.flagOnlyEntries()) {
            if (vetoes(entry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean vetoes(PoiEvidenceWindow.Entry entry) {
        return entry.bucket() == PoiBucket.SCULK_STRUCT
                || entry.bucket() == PoiBucket.SPAWNER
                || PoiEvidenceFlags.has(entry.flags(), PoiEvidenceFlags.REINFORCED_DEEPSLATE);
    }

    /**
     * Item 9 helper for the input builder: whether a POI candidate is "pending" for the gate. It is true when
     * {@code lastBand} (the band of the most recent shadow evaluation) is anything but {@link PoiScorer.Band#NONE},
     * except that {@link PoiScorer.Band#CAVERN_ONLY} counts only when {@code cavernBlocksDetour} is true; or when
     * some tracked candidate's hysteresis is currently satisfied ({@code anyCandidateSatisfied}, the caller asks
     * {@code PoiCandidates.Candidate.hysteresis().satisfied(tick)} for each entry). The mere existence of a
     * tracked candidate is NOT enough: a candidate survives 240 ticks and is refreshed by every evaluation near it,
     * even one whose band is NONE, so "non-empty snapshot" would veto detours for as long as the bot stays within
     * 40 blocks of one cavern hit (P1 contract, review log). A null {@code lastBand} counts as
     * {@link PoiScorer.Band#NONE}.
     */
    public static boolean candidatePending(PoiScorer.Band lastBand, boolean anyCandidateSatisfied,
                                           boolean cavernBlocksDetour) {
        PoiScorer.Band band = lastBand == null ? PoiScorer.Band.NONE : lastBand;
        boolean bandPending = switch (band) {
            case NONE -> false;
            case CAVERN_ONLY -> cavernBlocksDetour;
            case POSSIBLE, STRUCTURE_CERTAIN, MANDATORY -> true;
        };
        return bandPending || anyCandidateSatisfied;
    }
}
