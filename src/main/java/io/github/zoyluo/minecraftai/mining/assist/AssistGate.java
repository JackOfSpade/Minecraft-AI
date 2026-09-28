package io.github.zoyluo.minecraftai.mining.assist;

import java.util.List;

/**
 * Pure form of the {@code enabledFor} rule (design 2.1). The caller resolves each input from live
 * state (task origin, {@code MiningEvidenceAudit}, {@code TpsGuard}, the cached
 * {@link MiningAssistConfig}); this class only combines them, so the truth table is unit-testable.
 *
 * <p>Assist may act only when all of the following hold:</p>
 * <ul>
 *   <li>the mode is at least {@link AssistMode#SENSE} (anything but {@link AssistMode#OFF});</li>
 *   <li>the harness default-off is not active, unless the bot was force-enabled;</li>
 *   <li>the task origin is one of the five real kinds ({@link #isRealOrigin(String)});</li>
 *   <li>no evidence-audit session is open for the bot;</li>
 *   <li>TPS is not degraded.</li>
 * </ul>
 * Forcing a bot bypasses only the harness default, never the mode, origin, audit or TPS checks.
 */
public final class AssistGate {
    /** Origin kind names ({@code TaskOrigin.Kind.name()}) that count as real, player-visible work. */
    public static final String MISSION = "MISSION";
    public static final String PLAYER_COMMAND = "PLAYER_COMMAND";
    public static final String PLAYER_PANEL = "PLAYER_PANEL";
    public static final String LLM_TOOL = "LLM_TOOL";
    public static final String JOB = "JOB";

    private static final List<String> REAL_ORIGINS =
            List.of(MISSION, PLAYER_COMMAND, PLAYER_PANEL, LLM_TOOL, JOB);

    /** Reason strings returned by {@link #denyReason}. */
    public static final String DENY_MODE_OFF = "mode_off";
    public static final String DENY_HARNESS_OFF = "harness_off";
    public static final String DENY_ORIGIN = "origin";
    public static final String DENY_AUDIT = "audit_session";
    public static final String DENY_TPS = "tps_degraded";

    private AssistGate() {
    }

    /** The five origin kind names that may be assisted, in a fixed order. */
    public static List<String> realOriginNames() {
        return REAL_ORIGINS;
    }

    /**
     * True only for the exact {@code TaskOrigin.Kind} names MISSION, PLAYER_COMMAND, PLAYER_PANEL,
     * LLM_TOOL and JOB. SAFETY, SYSTEM_BACKGROUND, VERIFY, null and anything else are not real.
     */
    public static boolean isRealOrigin(String kindName) {
        return kindName != null && REAL_ORIGINS.contains(kindName);
    }

    /** The {@code enabledFor} rule; see the class comment. A null mode counts as OFF. */
    public static boolean enabled(
            AssistMode mode,
            boolean harnessOff,
            boolean forced,
            boolean originIsRealMissionKind,
            boolean auditSessionActive,
            boolean tpsDegraded) {
        return denyReason(mode, harnessOff, forced, originIsRealMissionKind, auditSessionActive, tpsDegraded) == null;
    }

    /**
     * The first failing condition as one of the {@code DENY_*} strings, or {@code null} when the
     * gate is open. Checked in the order mode, harness, origin, audit, TPS.
     */
    public static String denyReason(
            AssistMode mode,
            boolean harnessOff,
            boolean forced,
            boolean originIsRealMissionKind,
            boolean auditSessionActive,
            boolean tpsDegraded) {
        if (mode == null || !mode.allowsSense()) {
            return DENY_MODE_OFF;
        }
        if (harnessOff && !forced) {
            return DENY_HARNESS_OFF;
        }
        if (!originIsRealMissionKind) {
            return DENY_ORIGIN;
        }
        if (auditSessionActive) {
            return DENY_AUDIT;
        }
        if (tpsDegraded) {
            return DENY_TPS;
        }
        return null;
    }
}
