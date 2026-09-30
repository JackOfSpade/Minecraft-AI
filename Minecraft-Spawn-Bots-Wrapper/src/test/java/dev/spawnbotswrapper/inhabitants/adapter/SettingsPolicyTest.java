package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.SettingsPolicy.Current;
import dev.spawnbotswrapper.inhabitants.adapter.SettingsPolicy.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which managed PvP BOT settings have to change, and when the ranged distances are refused. */
class SettingsPolicyTest {

    /** PvP BOT's own defaults. */
    private static final Current UPSTREAM = new Current(64.0, 20.0, 40.0, 60.0, true);
    private static final ManagedSettings SHIPPED = new ManagedSettings(10.0, 6.0, 8.0, 10.0, false);

    private static List<String> names(Plan plan) {
        return plan.changes().stream().map(SettingsPolicy.Change::name).toList();
    }

    @Test
    void theShippedValuesChangeEverythingOnUpstreamDefaultsAndSayHowInOneLine() {
        Plan plan = SettingsPolicy.plan(SHIPPED, UPSTREAM);
        assertEquals(List.of("maxTargetDistance", "rangedMinRange", "rangedOptimalRange", "rangedMaxRange", "autoEquipWeapon"),
                names(plan));
        assertEquals("maxTargetDistance 64 -> 10, rangedMinRange 20 -> 6, rangedOptimalRange 40 -> 8, "
                + "rangedMaxRange 60 -> 10, autoEquipWeapon true -> false", plan.summary());
        assertEquals(List.of(), plan.warnings());
    }

    @Test
    void valuesAlreadyThereChangeNothingAndProduceNoSummary() {
        Plan plan = SettingsPolicy.plan(SHIPPED, new Current(10.0, 6.0, 8.0, 10.0, false));
        assertEquals(List.of(), plan.changes());
        assertNull(plan.summary());
        assertEquals(List.of(), plan.warnings());
    }

    @Test
    void aNullValueLeavesThatSettingAlone() {
        Plan plan = SettingsPolicy.plan(new ManagedSettings(16.0, null, null, null, null), UPSTREAM);
        assertEquals("maxTargetDistance 64 -> 16", plan.summary());
        Plan onlyEquip = SettingsPolicy.plan(new ManagedSettings(null, null, null, null, false), UPSTREAM);
        assertEquals("autoEquipWeapon true -> false", onlyEquip.summary());
        assertEquals(List.of(), SettingsPolicy.plan(ManagedSettings.NONE, UPSTREAM).changes());
        assertEquals(List.of(), SettingsPolicy.plan(null, UPSTREAM).changes());
    }

    @Test
    void aFractionalValueKeepsItsFraction() {
        Plan plan = SettingsPolicy.plan(new ManagedSettings(12.5, null, null, null, null), UPSTREAM);
        assertEquals("maxTargetDistance 64 -> 12.5", plan.summary());
    }

    @Test
    void theTargetDistanceMustBeInsideItsBounds() {
        for (double bad : new double[]{3.9, 64.1, 0.0, -5.0, Double.NaN}) {
            Plan plan = SettingsPolicy.plan(new ManagedSettings(bad, null, null, null, null), UPSTREAM);
            assertEquals(List.of(), plan.changes(), "target distance " + bad);
            assertEquals(1, plan.warnings().size(), plan.warnings().toString());
            assertTrue(plan.warnings().get(0).contains("outside 4..64"), plan.warnings().get(0));
        }
        assertEquals(List.of("maxTargetDistance"),
                names(SettingsPolicy.plan(new ManagedSettings(4.0, null, null, null, null), UPSTREAM)));
        assertEquals(List.of("maxTargetDistance"),
                names(SettingsPolicy.plan(new ManagedSettings(64.0, null, null, null, null), new Current(10.0, 6.0, 8.0, 10.0, false))));
    }

    @Test
    void disorderedRangesAreAllSkippedWithOneWarningButTheOtherSettingsStillApply() {
        // min 9 is not below optimal 8
        ManagedSettings wanted = new ManagedSettings(10.0, 9.0, 8.0, 10.0, false);
        Plan plan = SettingsPolicy.plan(wanted, UPSTREAM);
        assertEquals(List.of("maxTargetDistance", "autoEquipWeapon"), names(plan));
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().get(0).contains("rangedMinRange (9) must be below rangedOptimalRange (8)"),
                plan.warnings().get(0));
    }

    @Test
    void theRangedMaximumMayNotExceedTheTargetDistance() {
        Plan plan = SettingsPolicy.plan(new ManagedSettings(10.0, 6.0, 8.0, 12.0, null), UPSTREAM);
        assertEquals(List.of("maxTargetDistance"), names(plan));
        assertTrue(plan.warnings().get(0).contains("rangedMaxRange (12) must not exceed maxTargetDistance (10)"),
                plan.warnings().get(0));
    }

    @Test
    void aPartlyManagedSetIsJudgedAgainstWhatUpstreamHasForTheRest() {
        // upstream max is 60: managing only the minimum and optimal below a 10 block radius leaves max 60 > 10
        Plan refused = SettingsPolicy.plan(new ManagedSettings(10.0, 6.0, 8.0, null, null), UPSTREAM);
        assertEquals(List.of("maxTargetDistance"), names(refused));
        assertTrue(refused.warnings().get(0).contains("rangedMaxRange (60)"), refused.warnings().get(0));
        // the same minimum against a radius that fits everything upstream has is fine
        Plan accepted = SettingsPolicy.plan(new ManagedSettings(64.0, 15.0, null, null, null), UPSTREAM);
        assertEquals(List.of("rangedMinRange"), names(accepted), "the radius is unchanged at its 64, only the minimum moves");
        assertEquals(List.of(), accepted.warnings());
    }

    @Test
    void notManagingTheRangesNeverWarnsAboutThem() {
        // upstream ranges 20/40/60 are beyond a 10 block radius, but they are not this addon's to judge; hygiene reports it
        Plan plan = SettingsPolicy.plan(new ManagedSettings(10.0, null, null, null, null), UPSTREAM);
        assertEquals(List.of("maxTargetDistance"), names(plan));
        assertEquals(List.of(), plan.warnings());
    }

    @Test
    void unreadableCurrentValuesStillProduceTheChangeWithAQuestionMarkForTheOldValue() {
        Plan plan = SettingsPolicy.plan(SHIPPED, new Current(null, null, null, null, null));
        assertEquals("maxTargetDistance ? -> 10, rangedMinRange ? -> 6, rangedOptimalRange ? -> 8, "
                + "rangedMaxRange ? -> 10, autoEquipWeapon ? -> false", plan.summary());
    }

    @Test
    void nonPositiveRangesAreRefused() {
        Plan plan = SettingsPolicy.plan(new ManagedSettings(null, 0.0, 8.0, 10.0, null), UPSTREAM);
        assertEquals(List.of(), plan.changes());
        assertTrue(plan.warnings().get(0).contains("positive"), plan.warnings().get(0));
    }
}
