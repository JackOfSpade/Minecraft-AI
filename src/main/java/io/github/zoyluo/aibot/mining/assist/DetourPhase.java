package io.github.zoyluo.aibot.mining.assist;

/**
 * Phase of the opportunistic valuables detour engine (mining-assist design 4.14). Pure data, shared by the
 * engine ({@code task/OreDigDetourEngine}), the per-bot published tuple in {@link MiningAssistState} and the
 * coordinator's tick-granular net (design 2.3 step 3c).
 *
 * <p>The engine is IDLE except between a start and its FINISH. {@code NEXT} and {@code FINISH} are transient:
 * the engine passes through them inside one tick, so a published phase is normally one of the others.</p>
 */
public enum DetourPhase {
    /** No detour. Never published: an idle engine clears the published tuple instead (see {@code MiningAssistState#clearDetour}). */
    IDLE,
    /** Walking (walk-only route) to the stand pose of the current member. */
    APPROACH,
    /** Re-proving, gating and breaking the current member with the channel-tool policy. */
    MINE,
    /** The tick(s) after a break: fluid re-observation, seals, vein discovery. */
    POSTBREAK,
    /** Waiting for the drop of the last break to be picked up, chasing it under a contract-bound route. */
    SETTLE_DROP,
    /** Choosing the next vein member or deciding to return. */
    NEXT,
    /** Walking back to the anchor face (walk-only); on failure the cursor is rebased in place. */
    RETURN,
    /** The engine is releasing the detour (claims, anchor numbers). Transient. */
    FINISH;

    /** Every phase but {@link #IDLE}. */
    public boolean active() {
        return this != IDLE;
    }

    /**
     * Phases in which the coordinator's net may abort the detour on degraded TPS or a rising {@code hurtTime}
     * (design 2.3 step 3c). RETURN is exempt because an abort already means "return", and IDLE and FINISH have
     * nothing to abort. POSTBREAK is included, which the design text does not list (see the P1 contract).
     */
    public boolean netAbortable() {
        return this == APPROACH || this == MINE || this == POSTBREAK || this == SETTLE_DROP || this == NEXT;
    }
}
