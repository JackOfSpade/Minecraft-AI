package io.github.zoyluo.minecraftai.mining.assist;

/**
 * Everything {@link SafeGate#evaluate} may look at, as plain values (mining-assist design 4.4). Built by
 * {@code task/DetourSafetyGate.inputs(bot, stage, pose, ore)} from live state, filled per stage: a stage that
 * does not read an item leaves its fields at the {@link Builder} defaults, which are the "all clear" values.
 * Immutable; use {@link #builder()} (all clear) and set only what differs.
 *
 * <p>Field groups follow the design's item numbers; see {@link SafeReason} for the order in which they fail and
 * {@link SafeGate} for the thresholds.</p>
 *
 * @param modeAllowsDetour    item 1: the config resolves to a mode that allows R1 ({@code MiningAssistConfig.detourActive()})
 * @param originReal          item 1: the active task's origin is one of the five real kinds
 * @param auditSession        item 1: {@code MiningEvidenceAudit.hasSession(uuid)}
 * @param tpsDegraded         item 2: the TPS verdict ({@code MiningAssistRuntime.tpsDegraded(bot)}, includes the test override)
 * @param headroomStartOk     item 2, START: {@code TickHeadroom.canStart(tpsDegraded)}
 * @param headroomAbort       item 2, TICK: {@code TickHeadroom.shouldAbort(tpsDegraded)} (side effect: disarms; only call while a detour is live)
 * @param health              item 3: the bot's current health
 * @param retreatHp           item 3: {@code MinecraftAiConfig.combat().retreatHp()}
 * @param startHpMargin       item 3: {@code detour.startHpMargin}
 * @param hurtTime            item 3: {@code bot.hurtTime}
 * @param onFire              item 3
 * @param inLava              item 3
 * @param submerged           item 3: {@code bot.isSubmergedInWater()}
 * @param touchingWater       item 3: {@code bot.isTouchingWater()}
 * @param foodLevel           item 3: the hunger manager's food level
 * @param hungerCritical      item 3: {@code MinecraftAiConfig.survival().hungerCriticalThreshold()}
 * @param waterRescueActive   item 4: {@code NavSafetyNet.isWaterRescueActive(bot)}
 * @param pausedDepth         item 4: {@code TaskManager.pausedDepth(bot)}
 * @param userPaused          item 4: {@code TaskManager.isUserPaused(bot)}
 * @param originSafety        item 4: the active origin is SAFETY
 * @param threatCooldown      item 5: {@code DangerWatcher.threatCooldownActive(bot, tick)}
 * @param shelterEpisode      item 5: {@code DangerWatcher.shelterEpisodeActive(bot)}
 * @param hostilePressure     item 6: {@code DangerWatcher.hasObservableHostilePressure(bot)}
 * @param lavaInThreatBox     item 7: {@code DangerWatcher.observedLavaInThreatBox(bot)} is present
 * @param hazardLavaNear      item 7: a remembered lava cell within {@code detour.lavaClearRadius} of the bot, the stand pose or the valuable
 * @param deepDark            item 8: the own-cell biome is the deep dark AND {@code safety.deepDarkVeto}
 * @param poiStructureScore   item 9: the last structure score S of the shadow POI evaluation (0 when none)
 * @param poiEvidenceStale    item 9: the POI facts are not fresh (score never computed or older than 60 ticks while {@code poi.enabled}, no state, or the window cannot be trusted); fails closed as POI_EVIDENCE
 * @param poiWindowVeto       item 9: {@link SafeGate#poiWindowVeto} of the POI window
 * @param poiCandidatePending item 9: a POI candidate is pending, see {@link SafeGate#candidatePending}
 * @param inNoDetourZone      item 9: inside a mandatory no-detour zone ({@link io.github.zoyluo.minecraftai.mining.assist.MandatoryLatch#inNoDetourZone})
 * @param trapNear            item 10: a remembered TRAP cell within 3 blocks of the bot, the stand pose or the valuable
 */
public record SafeGateInputs(
        boolean modeAllowsDetour,
        boolean originReal,
        boolean auditSession,
        boolean tpsDegraded,
        boolean headroomStartOk,
        boolean headroomAbort,
        double health,
        int retreatHp,
        int startHpMargin,
        int hurtTime,
        boolean onFire,
        boolean inLava,
        boolean submerged,
        boolean touchingWater,
        int foodLevel,
        int hungerCritical,
        boolean waterRescueActive,
        int pausedDepth,
        boolean userPaused,
        boolean originSafety,
        boolean threatCooldown,
        boolean shelterEpisode,
        boolean hostilePressure,
        boolean lavaInThreatBox,
        boolean hazardLavaNear,
        boolean deepDark,
        double poiStructureScore,
        boolean poiEvidenceStale,
        boolean poiWindowVeto,
        boolean poiCandidatePending,
        boolean inNoDetourZone,
        boolean trapNear) {

    /** A builder whose defaults are the all-clear values: every gate passes for every stage. */
    public static Builder builder() {
        return new Builder();
    }

    /** All clear: {@code builder().build()}. */
    public static SafeGateInputs allClear() {
        return new Builder().build();
    }

    /** A builder pre-loaded with this record's values. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.modeAllowsDetour = modeAllowsDetour;
        b.originReal = originReal;
        b.auditSession = auditSession;
        b.tpsDegraded = tpsDegraded;
        b.headroomStartOk = headroomStartOk;
        b.headroomAbort = headroomAbort;
        b.health = health;
        b.retreatHp = retreatHp;
        b.startHpMargin = startHpMargin;
        b.hurtTime = hurtTime;
        b.onFire = onFire;
        b.inLava = inLava;
        b.submerged = submerged;
        b.touchingWater = touchingWater;
        b.foodLevel = foodLevel;
        b.hungerCritical = hungerCritical;
        b.waterRescueActive = waterRescueActive;
        b.pausedDepth = pausedDepth;
        b.userPaused = userPaused;
        b.originSafety = originSafety;
        b.threatCooldown = threatCooldown;
        b.shelterEpisode = shelterEpisode;
        b.hostilePressure = hostilePressure;
        b.lavaInThreatBox = lavaInThreatBox;
        b.hazardLavaNear = hazardLavaNear;
        b.deepDark = deepDark;
        b.poiStructureScore = poiStructureScore;
        b.poiEvidenceStale = poiEvidenceStale;
        b.poiWindowVeto = poiWindowVeto;
        b.poiCandidatePending = poiCandidatePending;
        b.inNoDetourZone = inNoDetourZone;
        b.trapNear = trapNear;
        return b;
    }

    /**
     * Mutable builder; every setter returns the builder. Defaults: health 20, retreatHp 10, margin 4, food 20, poiEvidenceStale false,
     * hunger critical 6, headroomStartOk true, every other flag clear, poiStructureScore 0.
     */
    public static final class Builder {
        boolean modeAllowsDetour = true;
        boolean originReal = true;
        boolean auditSession;
        boolean tpsDegraded;
        boolean headroomStartOk = true;
        boolean headroomAbort;
        double health = 20.0D;
        int retreatHp = 10;
        int startHpMargin = 4;
        int hurtTime;
        boolean onFire;
        boolean inLava;
        boolean submerged;
        boolean touchingWater;
        int foodLevel = 20;
        int hungerCritical = 6;
        boolean waterRescueActive;
        int pausedDepth;
        boolean userPaused;
        boolean originSafety;
        boolean threatCooldown;
        boolean shelterEpisode;
        boolean hostilePressure;
        boolean lavaInThreatBox;
        boolean hazardLavaNear;
        boolean deepDark;
        double poiStructureScore;
        boolean poiEvidenceStale;
        boolean poiWindowVeto;
        boolean poiCandidatePending;
        boolean inNoDetourZone;
        boolean trapNear;

        private Builder() {
        }

        public Builder modeAllowsDetour(boolean v) {
            this.modeAllowsDetour = v;
            return this;
        }

        public Builder originReal(boolean v) {
            this.originReal = v;
            return this;
        }

        public Builder auditSession(boolean v) {
            this.auditSession = v;
            return this;
        }

        public Builder tpsDegraded(boolean v) {
            this.tpsDegraded = v;
            return this;
        }

        public Builder headroomStartOk(boolean v) {
            this.headroomStartOk = v;
            return this;
        }

        public Builder headroomAbort(boolean v) {
            this.headroomAbort = v;
            return this;
        }

        public Builder health(double v) {
            this.health = v;
            return this;
        }

        public Builder retreatHp(int v) {
            this.retreatHp = v;
            return this;
        }

        public Builder startHpMargin(int v) {
            this.startHpMargin = v;
            return this;
        }

        public Builder hurtTime(int v) {
            this.hurtTime = v;
            return this;
        }

        public Builder onFire(boolean v) {
            this.onFire = v;
            return this;
        }

        public Builder inLava(boolean v) {
            this.inLava = v;
            return this;
        }

        public Builder submerged(boolean v) {
            this.submerged = v;
            return this;
        }

        public Builder touchingWater(boolean v) {
            this.touchingWater = v;
            return this;
        }

        public Builder foodLevel(int v) {
            this.foodLevel = v;
            return this;
        }

        public Builder hungerCritical(int v) {
            this.hungerCritical = v;
            return this;
        }

        public Builder waterRescueActive(boolean v) {
            this.waterRescueActive = v;
            return this;
        }

        public Builder pausedDepth(int v) {
            this.pausedDepth = v;
            return this;
        }

        public Builder userPaused(boolean v) {
            this.userPaused = v;
            return this;
        }

        public Builder originSafety(boolean v) {
            this.originSafety = v;
            return this;
        }

        public Builder threatCooldown(boolean v) {
            this.threatCooldown = v;
            return this;
        }

        public Builder shelterEpisode(boolean v) {
            this.shelterEpisode = v;
            return this;
        }

        public Builder hostilePressure(boolean v) {
            this.hostilePressure = v;
            return this;
        }

        public Builder lavaInThreatBox(boolean v) {
            this.lavaInThreatBox = v;
            return this;
        }

        public Builder hazardLavaNear(boolean v) {
            this.hazardLavaNear = v;
            return this;
        }

        public Builder deepDark(boolean v) {
            this.deepDark = v;
            return this;
        }

        public Builder poiStructureScore(double v) {
            this.poiStructureScore = v;
            return this;
        }

        public Builder poiEvidenceStale(boolean v) {
            this.poiEvidenceStale = v;
            return this;
        }

        public Builder poiWindowVeto(boolean v) {
            this.poiWindowVeto = v;
            return this;
        }

        public Builder poiCandidatePending(boolean v) {
            this.poiCandidatePending = v;
            return this;
        }

        public Builder inNoDetourZone(boolean v) {
            this.inNoDetourZone = v;
            return this;
        }

        public Builder trapNear(boolean v) {
            this.trapNear = v;
            return this;
        }

        public SafeGateInputs build() {
            return new SafeGateInputs(
                    modeAllowsDetour, originReal, auditSession,
                    tpsDegraded, headroomStartOk, headroomAbort,
                    health, retreatHp, startHpMargin, hurtTime, onFire, inLava, submerged, touchingWater,
                    foodLevel, hungerCritical,
                    waterRescueActive, pausedDepth, userPaused, originSafety,
                    threatCooldown, shelterEpisode,
                    hostilePressure,
                    lavaInThreatBox, hazardLavaNear,
                    deepDark,
                    poiStructureScore, poiEvidenceStale, poiWindowVeto, poiCandidatePending, inNoDetourZone,
                    trapNear);
        }
    }
}
