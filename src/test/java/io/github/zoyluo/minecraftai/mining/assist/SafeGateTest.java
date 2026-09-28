package io.github.zoyluo.minecraftai.mining.assist;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static io.github.zoyluo.minecraftai.mining.assist.SafeGate.Stage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the design 4.4 SAFE gate truth table and ordering (P1 contract section D, G.3): every
 * {@link SafeReason}, the first-failing-reason order between and within items, which stage reads which
 * item, the hp/S boundaries, and the item 9 helpers ({@link SafeGate#poiWindowVeto},
 * {@link SafeGate#candidatePending}).
 */
class SafeGateTest {

    // ---- all clear ----

    @Test
    void allClearPassesEveryStage() {
        SafeGateInputs in = SafeGateInputs.allClear();
        for (Stage s : Stage.values()) {
            assertEquals(SafeReason.OK, SafeGate.evaluate(in, s), s.name());
        }
    }

    // ---- item 1: mode, origin, audit (every stage reads item 1) ----

    @Test
    void modeFailsWhenDetourNotAllowed() {
        SafeGateInputs in = SafeGateInputs.builder().modeAllowsDetour(false).build();
        assertEveryStage(SafeReason.MODE, in);
    }

    @Test
    void originFailsWhenNotReal() {
        SafeGateInputs in = SafeGateInputs.builder().originReal(false).build();
        assertEveryStage(SafeReason.ORIGIN, in);
    }

    @Test
    void auditFailsWhenSessionActive() {
        SafeGateInputs in = SafeGateInputs.builder().auditSession(true).build();
        assertEveryStage(SafeReason.AUDIT, in);
    }

    @Test
    void modeBeatsOriginAndAudit() {
        SafeGateInputs in = SafeGateInputs.builder().modeAllowsDetour(false).originReal(false)
                .auditSession(true).build();
        assertEveryStage(SafeReason.MODE, in);
    }

    @Test
    void originBeatsAudit() {
        SafeGateInputs in = SafeGateInputs.builder().originReal(false).auditSession(true).build();
        assertEveryStage(SafeReason.ORIGIN, in);
    }

    // ---- item 2: TPS, headroom (every stage reads item 2) ----

    @Test
    void tpsFailsWhenDegraded() {
        SafeGateInputs in = SafeGateInputs.builder().tpsDegraded(true).build();
        assertEveryStage(SafeReason.TPS, in);
    }

    @Test
    void headroomStartOkOnlyMattersForStart() {
        SafeGateInputs in = SafeGateInputs.builder().headroomStartOk(false).build();
        assertEquals(SafeReason.HEADROOM, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void headroomAbortOnlyMattersForTick() {
        SafeGateInputs in = SafeGateInputs.builder().headroomAbort(true).build();
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.HEADROOM, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.HEADROOM, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void tpsBeatsHeadroom() {
        SafeGateInputs in = SafeGateInputs.builder().tpsDegraded(true).headroomAbort(true).build();
        assertEquals(SafeReason.TPS, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void modeBeatsTps() {
        SafeGateInputs in = SafeGateInputs.builder().modeAllowsDetour(false).tpsDegraded(true).build();
        assertEveryStage(SafeReason.MODE, in);
    }

    // ---- item 3: hp, hurt, fire, lava, water, food (every stage reads item 3) ----

    @Test
    void hpBoundaryAtStart() {
        SafeGateInputs below = SafeGateInputs.builder().health(13.999D).build();
        assertEquals(SafeReason.HP, SafeGate.evaluate(below, Stage.START));
        SafeGateInputs at = SafeGateInputs.builder().health(14.0D).build();
        assertEquals(SafeReason.OK, SafeGate.evaluate(at, Stage.START));
    }

    @Test
    void hpBoundaryAtTick() {
        SafeGateInputs at = SafeGateInputs.builder().health(10.0D).build();
        assertEquals(SafeReason.HP, SafeGate.evaluate(at, Stage.TICK_FAST));
        assertEquals(SafeReason.HP, SafeGate.evaluate(at, Stage.TICK_FULL));
        SafeGateInputs above = SafeGateInputs.builder().health(10.001D).build();
        assertEquals(SafeReason.OK, SafeGate.evaluate(above, Stage.TICK_FAST));
        assertEquals(SafeReason.OK, SafeGate.evaluate(above, Stage.TICK_FULL));
    }

    @Test
    void tickHpUsesTheLowerThresholdThanStart() {
        // Health 12 fails the START margin (needs >= 14) but passes the TICK threshold (needs > 10).
        SafeGateInputs in = SafeGateInputs.builder().health(12.0D).build();
        assertEquals(SafeReason.HP, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void hurtFailsWhenPositive() {
        SafeGateInputs in = SafeGateInputs.builder().hurtTime(1).build();
        assertEveryStage(SafeReason.HURT, in);
    }

    @Test
    void onFireFails() {
        assertEveryStage(SafeReason.ON_FIRE, SafeGateInputs.builder().onFire(true).build());
    }

    @Test
    void inLavaFails() {
        assertEveryStage(SafeReason.IN_LAVA, SafeGateInputs.builder().inLava(true).build());
    }

    @Test
    void submergedFails() {
        assertEveryStage(SafeReason.SUBMERGED, SafeGateInputs.builder().submerged(true).build());
    }

    @Test
    void touchingWaterFails() {
        assertEveryStage(SafeReason.TOUCHING_WATER, SafeGateInputs.builder().touchingWater(true).build());
    }

    @Test
    void foodFailsAtOrBelowCritical() {
        SafeGateInputs at = SafeGateInputs.builder().foodLevel(6).build();
        assertEveryStage(SafeReason.FOOD, at);
        SafeGateInputs above = SafeGateInputs.builder().foodLevel(7).build();
        assertEveryStage(SafeReason.OK, above);
    }

    @Test
    void hpBeatsHurtBeatsFireBeatsLavaBeatsSubmergedBeatsTouchingWaterBeatsFood() {
        assertEquals(SafeReason.HP, SafeGate.evaluate(
                SafeGateInputs.builder().health(0.0D).hurtTime(1).build(), Stage.TICK_FULL));
        assertEquals(SafeReason.HURT, SafeGate.evaluate(
                SafeGateInputs.builder().hurtTime(1).onFire(true).build(), Stage.TICK_FULL));
        assertEquals(SafeReason.ON_FIRE, SafeGate.evaluate(
                SafeGateInputs.builder().onFire(true).inLava(true).build(), Stage.TICK_FULL));
        assertEquals(SafeReason.IN_LAVA, SafeGate.evaluate(
                SafeGateInputs.builder().inLava(true).submerged(true).build(), Stage.TICK_FULL));
        assertEquals(SafeReason.SUBMERGED, SafeGate.evaluate(
                SafeGateInputs.builder().submerged(true).touchingWater(true).build(), Stage.TICK_FULL));
        assertEquals(SafeReason.TOUCHING_WATER, SafeGate.evaluate(
                SafeGateInputs.builder().touchingWater(true).foodLevel(0).build(), Stage.TICK_FULL));
    }

    @Test
    void item3BeatsItem4() {
        SafeGateInputs in = SafeGateInputs.builder().onFire(true).waterRescueActive(true).build();
        assertEveryStage(SafeReason.ON_FIRE, in);
    }

    // ---- item 4: water rescue, paused, user paused, origin safety (every stage reads item 4) ----

    @Test
    void waterRescueFails() {
        assertEveryStage(SafeReason.WATER_RESCUE, SafeGateInputs.builder().waterRescueActive(true).build());
    }

    @Test
    void pausedFailsWhenDepthPositive() {
        assertEveryStage(SafeReason.PAUSED, SafeGateInputs.builder().pausedDepth(1).build());
    }

    @Test
    void userPausedFails() {
        assertEveryStage(SafeReason.USER_PAUSED, SafeGateInputs.builder().userPaused(true).build());
    }

    @Test
    void originSafetyFails() {
        assertEveryStage(SafeReason.ORIGIN_SAFETY, SafeGateInputs.builder().originSafety(true).build());
    }

    @Test
    void waterRescueBeatsPausedBeatsUserPausedBeatsOriginSafety() {
        assertEquals(SafeReason.WATER_RESCUE, SafeGate.evaluate(
                SafeGateInputs.builder().waterRescueActive(true).pausedDepth(1).build(), Stage.TICK_FULL));
        assertEquals(SafeReason.PAUSED, SafeGate.evaluate(
                SafeGateInputs.builder().pausedDepth(1).userPaused(true).build(), Stage.TICK_FULL));
        assertEquals(SafeReason.USER_PAUSED, SafeGate.evaluate(
                SafeGateInputs.builder().userPaused(true).originSafety(true).build(), Stage.TICK_FULL));
    }

    @Test
    void item4BeatsItem5() {
        SafeGateInputs in = SafeGateInputs.builder().waterRescueActive(true).threatCooldown(true).build();
        assertEveryStage(SafeReason.WATER_RESCUE, in);
    }

    // ---- item 5: threat cooldown, shelter episode (TICK_FAST does not read it) ----

    @Test
    void threatCooldownIgnoredByTickFastOnly() {
        SafeGateInputs in = SafeGateInputs.builder().threatCooldown(true).build();
        assertEquals(SafeReason.THREAT_COOLDOWN, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.THREAT_COOLDOWN, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void shelterEpisodeIgnoredByTickFastOnly() {
        SafeGateInputs in = SafeGateInputs.builder().shelterEpisode(true).build();
        assertEquals(SafeReason.SHELTER_EPISODE, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.SHELTER_EPISODE, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void threatCooldownBeatsShelterEpisode() {
        SafeGateInputs in = SafeGateInputs.builder().threatCooldown(true).shelterEpisode(true).build();
        assertEquals(SafeReason.THREAT_COOLDOWN, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void item5BeatsItem6() {
        SafeGateInputs in = SafeGateInputs.builder().threatCooldown(true).hostilePressure(true).build();
        assertEquals(SafeReason.THREAT_COOLDOWN, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.THREAT_COOLDOWN, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    // ---- item 6: hostile pressure (TICK_FAST does not read it) ----

    @Test
    void hostilePressureIgnoredByTickFastOnly() {
        SafeGateInputs in = SafeGateInputs.builder().hostilePressure(true).build();
        assertEquals(SafeReason.HOSTILE_PRESSURE, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.HOSTILE_PRESSURE, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void item6BeatsItem7() {
        SafeGateInputs in = SafeGateInputs.builder().hostilePressure(true).lavaInThreatBox(true).build();
        assertEquals(SafeReason.HOSTILE_PRESSURE, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    // ---- item 7: lava threat box, hazard lava (TICK_FAST does not read it) ----

    @Test
    void lavaThreatBoxIgnoredByTickFastOnly() {
        SafeGateInputs in = SafeGateInputs.builder().lavaInThreatBox(true).build();
        assertEquals(SafeReason.LAVA_THREAT_BOX, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.LAVA_THREAT_BOX, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void hazardLavaIgnoredByTickFastOnly() {
        SafeGateInputs in = SafeGateInputs.builder().hazardLavaNear(true).build();
        assertEquals(SafeReason.HAZARD_LAVA, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.HAZARD_LAVA, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void lavaThreatBoxBeatsHazardLava() {
        SafeGateInputs in = SafeGateInputs.builder().lavaInThreatBox(true).hazardLavaNear(true).build();
        assertEquals(SafeReason.LAVA_THREAT_BOX, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void item7BeatsItem8() {
        SafeGateInputs in = SafeGateInputs.builder().lavaInThreatBox(true).deepDark(true).build();
        assertEquals(SafeReason.LAVA_THREAT_BOX, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    // ---- item 8: deep dark biome (TICK_FAST does not read it) ----

    @Test
    void deepDarkIgnoredByTickFastOnly() {
        SafeGateInputs in = SafeGateInputs.builder().deepDark(true).build();
        assertEquals(SafeReason.DEEP_DARK_BIOME, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.DEEP_DARK_BIOME, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void item8BeatsItem9() {
        SafeGateInputs in = SafeGateInputs.builder().deepDark(true).poiStructureScore(1.0D).build();
        assertEquals(SafeReason.DEEP_DARK_BIOME, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    // ---- item 9: POI evidence (TICK_FAST does not read it) ----

    @Test
    void poiEvidenceIgnoredByTickFastOnly() {
        SafeGateInputs in = SafeGateInputs.builder().poiStructureScore(1.0D).build();
        assertEquals(SafeReason.POI_EVIDENCE, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.POI_EVIDENCE, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    @Test
    void structureScoreBoundary() {
        SafeGateInputs below = SafeGateInputs.builder().poiStructureScore(0.3499D).build();
        assertEquals(SafeReason.OK, SafeGate.evaluate(below, Stage.START));
        SafeGateInputs at = SafeGateInputs.builder().poiStructureScore(0.35D).build();
        assertEquals(SafeReason.POI_EVIDENCE, SafeGate.evaluate(at, Stage.START));
    }

    @Test
    void poiEvidenceStaleFailsRegardlessOfScoreValue() {
        SafeGateInputs in = SafeGateInputs.builder().poiEvidenceStale(true).poiStructureScore(0.0D).build();
        assertEquals(SafeReason.POI_EVIDENCE, SafeGate.evaluate(in, Stage.START));
    }

    @Test
    void poiWindowVetoFieldFailsTheGate() {
        SafeGateInputs in = SafeGateInputs.builder().poiWindowVeto(true).build();
        assertEquals(SafeReason.POI_EVIDENCE, SafeGate.evaluate(in, Stage.START));
    }

    @Test
    void poiCandidatePendingFieldFailsTheGate() {
        SafeGateInputs in = SafeGateInputs.builder().poiCandidatePending(true).build();
        assertEquals(SafeReason.POI_EVIDENCE, SafeGate.evaluate(in, Stage.START));
    }

    @Test
    void inNoDetourZoneFailsTheGate() {
        SafeGateInputs in = SafeGateInputs.builder().inNoDetourZone(true).build();
        assertEquals(SafeReason.POI_EVIDENCE, SafeGate.evaluate(in, Stage.START));
    }

    @Test
    void item9BeatsItem10() {
        SafeGateInputs in = SafeGateInputs.builder().poiStructureScore(1.0D).trapNear(true).build();
        assertEquals(SafeReason.POI_EVIDENCE, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    // ---- item 10: trap spot (TICK_FAST does not read it) ----

    @Test
    void trapSpotIgnoredByTickFastOnly() {
        SafeGateInputs in = SafeGateInputs.builder().trapNear(true).build();
        assertEquals(SafeReason.TRAP_SPOT, SafeGate.evaluate(in, Stage.START));
        assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.TICK_FAST));
        assertEquals(SafeReason.TRAP_SPOT, SafeGate.evaluate(in, Stage.TICK_FULL));
    }

    // ---- abortReason() / item() tables ----

    @Test
    void abortReasonMapsPerTheFailureMatrix() {
        assertEquals("", SafeReason.OK.abortReason());
        assertEquals("safety_mode", SafeReason.MODE.abortReason());
        assertEquals("safety_origin", SafeReason.ORIGIN.abortReason());
        assertEquals("safety_audit", SafeReason.AUDIT.abortReason());
        assertEquals("degraded_tps", SafeReason.TPS.abortReason());
        assertEquals("degraded_tps", SafeReason.HEADROOM.abortReason());
        assertEquals("safety_hp", SafeReason.HP.abortReason());
        assertEquals("safety_hurt", SafeReason.HURT.abortReason());
        assertEquals("safety_on_fire", SafeReason.ON_FIRE.abortReason());
        assertEquals("safety_in_lava", SafeReason.IN_LAVA.abortReason());
        assertEquals("safety_submerged", SafeReason.SUBMERGED.abortReason());
        assertEquals("safety_touching_water", SafeReason.TOUCHING_WATER.abortReason());
        assertEquals("safety_food", SafeReason.FOOD.abortReason());
        assertEquals("safety_water_rescue", SafeReason.WATER_RESCUE.abortReason());
        assertEquals("paused", SafeReason.PAUSED.abortReason());
        assertEquals("paused", SafeReason.USER_PAUSED.abortReason());
        assertEquals("safety_origin_safety", SafeReason.ORIGIN_SAFETY.abortReason());
        assertEquals("safety_threat_cooldown", SafeReason.THREAT_COOLDOWN.abortReason());
        assertEquals("safety_shelter_episode", SafeReason.SHELTER_EPISODE.abortReason());
        assertEquals("safety_hostile_pressure", SafeReason.HOSTILE_PRESSURE.abortReason());
        assertEquals("safety_lava_threat_box", SafeReason.LAVA_THREAT_BOX.abortReason());
        assertEquals("safety_hazard_lava", SafeReason.HAZARD_LAVA.abortReason());
        assertEquals("deep_dark_biome", SafeReason.DEEP_DARK_BIOME.abortReason());
        assertEquals("poi_evidence", SafeReason.POI_EVIDENCE.abortReason());
        assertEquals("trap_spot", SafeReason.TRAP_SPOT.abortReason());
    }

    @Test
    void itemNumbersMatchTheDesign() {
        assertEquals(0, SafeReason.OK.item());
        assertEquals(1, SafeReason.MODE.item());
        assertEquals(1, SafeReason.ORIGIN.item());
        assertEquals(1, SafeReason.AUDIT.item());
        assertEquals(2, SafeReason.TPS.item());
        assertEquals(2, SafeReason.HEADROOM.item());
        assertEquals(3, SafeReason.HP.item());
        assertEquals(3, SafeReason.HURT.item());
        assertEquals(3, SafeReason.ON_FIRE.item());
        assertEquals(3, SafeReason.IN_LAVA.item());
        assertEquals(3, SafeReason.SUBMERGED.item());
        assertEquals(3, SafeReason.TOUCHING_WATER.item());
        assertEquals(3, SafeReason.FOOD.item());
        assertEquals(4, SafeReason.WATER_RESCUE.item());
        assertEquals(4, SafeReason.PAUSED.item());
        assertEquals(4, SafeReason.USER_PAUSED.item());
        assertEquals(4, SafeReason.ORIGIN_SAFETY.item());
        assertEquals(5, SafeReason.THREAT_COOLDOWN.item());
        assertEquals(5, SafeReason.SHELTER_EPISODE.item());
        assertEquals(6, SafeReason.HOSTILE_PRESSURE.item());
        assertEquals(7, SafeReason.LAVA_THREAT_BOX.item());
        assertEquals(7, SafeReason.HAZARD_LAVA.item());
        assertEquals(8, SafeReason.DEEP_DARK_BIOME.item());
        assertEquals(9, SafeReason.POI_EVIDENCE.item());
        assertEquals(10, SafeReason.TRAP_SPOT.item());
    }

    @Test
    void stageReadsMatchesTheDesign() {
        for (int item = 1; item <= 10; item++) {
            assertTrue(Stage.START.reads(item), "START item " + item);
            assertTrue(Stage.TICK_FULL.reads(item), "TICK_FULL item " + item);
        }
        for (int item = 1; item <= 4; item++) {
            assertTrue(Stage.TICK_FAST.reads(item), "TICK_FAST item " + item);
        }
        for (int item = 5; item <= 10; item++) {
            assertFalse(Stage.TICK_FAST.reads(item), "TICK_FAST item " + item);
        }
        assertTrue(Stage.START.isStart());
        assertFalse(Stage.TICK_FAST.isStart());
        assertFalse(Stage.TICK_FULL.isStart());
    }

    // ---- poiWindowVeto ----

    @Test
    void poiWindowVetoIsFalseForNullOrEmpty() {
        assertFalse(SafeGate.poiWindowVeto(null));
        assertFalse(SafeGate.poiWindowVeto(new PoiEvidenceWindow()));
    }

    @Test
    void poiWindowVetoIsTrueForSculkStruct() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(new BlockPos(1, 2, 3), PoiBucket.SCULK_STRUCT, 0, 10, false);
        assertTrue(SafeGate.poiWindowVeto(window));
    }

    @Test
    void poiWindowVetoIsTrueForSpawner() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(new BlockPos(1, 2, 3), PoiBucket.SPAWNER, 0, 10, false);
        assertTrue(SafeGate.poiWindowVeto(window));
    }

    @Test
    void poiWindowVetoIsTrueForAReinforcedDeepslateFlagOnlyEntry() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        // A natural cell carrying only the flag: lands in the flag-only sub-window, not structural.
        window.observe(new BlockPos(1, 2, 3), PoiBucket.NATURAL, PoiEvidenceFlags.REINFORCED_DEEPSLATE, 10, false);
        assertEquals(0, window.structuralSize());
        assertEquals(1, window.flagOnlySize());
        assertTrue(SafeGate.poiWindowVeto(window));
    }

    @Test
    void poiWindowVetoIsFalseForAHarmlessCobbleCell() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        window.observe(new BlockPos(1, 2, 3), PoiBucket.COBBLE, 0, 10, false);
        assertFalse(SafeGate.poiWindowVeto(window));
    }

    // ---- candidatePending ----

    @Test
    void candidatePendingBandNoneAndNoSatisfiedCandidateIsFalse() {
        assertFalse(SafeGate.candidatePending(PoiScorer.Band.NONE, false, true));
        assertFalse(SafeGate.candidatePending(PoiScorer.Band.NONE, false, false));
        assertFalse(SafeGate.candidatePending(null, false, true));
    }

    @Test
    void candidatePendingIsTrueForPossibleStructureCertainOrMandatoryRegardless() {
        assertTrue(SafeGate.candidatePending(PoiScorer.Band.POSSIBLE, false, false));
        assertTrue(SafeGate.candidatePending(PoiScorer.Band.STRUCTURE_CERTAIN, false, false));
        assertTrue(SafeGate.candidatePending(PoiScorer.Band.MANDATORY, false, false));
    }

    @Test
    void candidatePendingCavernOnlyDependsOnTheConstant() {
        assertTrue(SafeGate.candidatePending(PoiScorer.Band.CAVERN_ONLY, false, true));
        assertFalse(SafeGate.candidatePending(PoiScorer.Band.CAVERN_ONLY, false, false));
    }

    @Test
    void candidatePendingSatisfiedCandidateIsTrueEvenWithBandNone() {
        assertTrue(SafeGate.candidatePending(PoiScorer.Band.NONE, true, true));
        assertTrue(SafeGate.candidatePending(null, true, false));
    }

    /** Design 4.4's three detour-start/tick stages only (START, TICK_FAST, TICK_FULL): every one of them
     * reads item 1 and, of the items exercised by every {@code assertEveryStage} call site in this file
     * (1, 2, 4, 5), each item is read by at least START/TICK_FULL and (for 1-4) TICK_FAST alike, so a single
     * failing reason is expected identically across all three. {@link Stage#HOLD} (design 6.5's {@code
     * safeToHold}) deliberately reads only items 3 and 6 and is asserted on its own, in the "HOLD stage"
     * section below -- looping it in here would wrongly expect items 1/2/4/5/7/8/9/10 failures to reach it too. */
    private static void assertEveryStage(SafeReason expected, SafeGateInputs in) {
        for (Stage s : new Stage[] {Stage.START, Stage.TICK_FAST, Stage.TICK_FULL}) {
            assertEquals(expected, SafeGate.evaluate(in, s), s.name());
        }
    }

    // ---- HOLD stage (design 6.5 safeToHold): items 3 and 6 only, nothing else ------------------------

    @Test
    void holdReadsItem3HealthAndHurt() {
        SafeGateInputs hurt = SafeGateInputs.builder().hurtTime(1).build();
        assertEquals(SafeReason.HURT, SafeGate.evaluate(hurt, Stage.HOLD));
        SafeGateInputs lowHp = SafeGateInputs.builder().health(5.0F).retreatHp(10).build();
        assertEquals(SafeReason.HP, SafeGate.evaluate(lowHp, Stage.HOLD));
    }

    @Test
    void holdReadsItem3FireLavaSubmergedWaterAndFood() {
        assertEquals(SafeReason.ON_FIRE, SafeGate.evaluate(SafeGateInputs.builder().onFire(true).build(), Stage.HOLD));
        assertEquals(SafeReason.IN_LAVA, SafeGate.evaluate(SafeGateInputs.builder().inLava(true).build(), Stage.HOLD));
        assertEquals(SafeReason.SUBMERGED, SafeGate.evaluate(SafeGateInputs.builder().submerged(true).build(), Stage.HOLD));
        assertEquals(SafeReason.TOUCHING_WATER,
                SafeGate.evaluate(SafeGateInputs.builder().touchingWater(true).build(), Stage.HOLD));
        SafeGateInputs starving = SafeGateInputs.builder().foodLevel(0).hungerCritical(6).build();
        assertEquals(SafeReason.FOOD, SafeGate.evaluate(starving, Stage.HOLD));
    }

    @Test
    void holdReadsItem6HostilePressure() {
        SafeGateInputs pressured = SafeGateInputs.builder().hostilePressure(true).build();
        assertEquals(SafeReason.HOSTILE_PRESSURE, SafeGate.evaluate(pressured, Stage.HOLD));
    }

    @Test
    void holdIgnoresEveryOtherItem() {
        SafeGateInputs[] onlyOtherItemsFail = {
                SafeGateInputs.builder().modeAllowsDetour(false).build(),
                SafeGateInputs.builder().tpsDegraded(true).headroomAbort(true).build(),
                SafeGateInputs.builder().userPaused(true).build(),
                SafeGateInputs.builder().threatCooldown(true).build(),
                SafeGateInputs.builder().lavaInThreatBox(true).build(),
                SafeGateInputs.builder().deepDark(true).build(),
                SafeGateInputs.builder().poiStructureScore(1.0D).build(),
                SafeGateInputs.builder().trapNear(true).build(),
        };
        for (SafeGateInputs in : onlyOtherItemsFail) {
            assertEquals(SafeReason.OK, SafeGate.evaluate(in, Stage.HOLD),
                    "HOLD must not read items 1/2/4/5/7/8/9/10");
        }
    }

    @Test
    void holdUsesTheTickHpThresholdNotTheStartMargin() {
        // START fails once health < retreatHp + startHpMargin; HOLD (never "isStart") only fails at
        // health <= retreatHp, exactly like TICK_FAST/TICK_FULL.
        SafeGateInputs betweenTickAndStartThreshold = SafeGateInputs.builder()
                .health(11.0F).retreatHp(10).startHpMargin(4).build();
        assertEquals(SafeReason.OK, SafeGate.evaluate(betweenTickAndStartThreshold, Stage.HOLD));
        assertEquals(SafeReason.HP, SafeGate.evaluate(betweenTickAndStartThreshold, Stage.START));
    }

    @Test
    void holdPassesOnAllClearInputs() {
        assertEquals(SafeReason.OK, SafeGate.evaluate(SafeGateInputs.allClear(), Stage.HOLD));
    }
}
