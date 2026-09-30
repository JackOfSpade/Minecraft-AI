package dev.spawnbotswrapper.inhabitants.adapter;

import org.junit.jupiter.api.Test;
import org.stepan1411.pvp_bot.bot.BotSettings;
import org.stepan1411.testdouble.Recorder;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The managed PvP BOT settings end to end against the fake settings singleton: what is written, when, how often the
 * file is saved, the one INFO line, and what a missing upstream name costs.
 */
class PvpBotAdapterManagedSettingsTest {
    private static final ManagedSettings SHIPPED = new ManagedSettings(10.0, 6.0, 8.0, 10.0, false, false);

    private static Object field(String name) throws ReflectiveOperationException {
        Field f = BotSettings.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(BotSettings.get());
    }

    private static long saves() {
        return Recorder.CALLS.stream().filter(c -> c.equals("BotSettings.save")).count();
    }

    private static long settingsLines(AdapterFixture f) {
        return f.sink.info.stream().filter(l -> l.startsWith("PvP BOT settings:")).count();
    }

    @Test
    void nothingIsWrittenOrSavedWhenNothingIsManaged() {
        AdapterFixture f = AdapterFixture.probed();
        f.adapter.manageSettings(null);
        f.adapter.manageSettings(ManagedSettings.NONE);
        assertEquals(0, saves());
        assertFalse(f.sink.anyContains("PvP BOT settings:"));
    }

    @Test
    void theShortRangesGoStraightIntoTheFieldsWhichASetterWouldHaveClamped() throws Exception {
        AdapterFixture f = AdapterFixture.probed();
        f.adapter.manageSettings(SHIPPED);
        assertEquals(10.0, field("maxTargetDistance"));
        assertEquals(6.0, field("rangedMinRange"));
        assertEquals(8.0, field("rangedOptimalRange"), "upstream's setter would have raised this to 10");
        assertEquals(10.0, field("rangedMaxRange"), "upstream's setter would have raised this to 15");
        assertEquals(Boolean.FALSE, field("autoEquipWeapon"));
        assertEquals(1, saves(), "the settings file is written once, after all the fields");
        assertTrue(Recorder.FORBIDDEN.isEmpty(), "no setter is ever called: " + Recorder.FORBIDDEN);
    }

    @Test
    void oneInfoLineNamesExactlyWhatChanged() {
        AdapterFixture f = AdapterFixture.probed();
        f.adapter.manageSettings(SHIPPED);
        List<String> lines = f.sink.info.stream().filter(l -> l.startsWith("PvP BOT settings:")).toList();
        assertEquals(List.of("PvP BOT settings: maxTargetDistance 64 -> 10, rangedMinRange 20 -> 6, rangedOptimalRange 40 -> 8, "
                + "rangedMaxRange 60 -> 10, autoEquipWeapon true -> false"), lines);
    }

    @Test
    void aSecondCallForTheSameSettingsObjectDoesNothing() {
        AdapterFixture f = AdapterFixture.probed();
        f.adapter.manageSettings(SHIPPED);
        f.adapter.manageSettings(SHIPPED);
        f.adapter.manageSettings(new ManagedSettings(10.0, 6.0, 8.0, 10.0, false, false));
        assertEquals(1, saves());
        assertEquals(1, settingsLines(f));
    }

    @Test
    void whenPvpBotLoadsItsSettingsAgainTheValuesAreAppliedAgain() throws Exception {
        AdapterFixture f = AdapterFixture.probed();
        f.adapter.manageSettings(SHIPPED);
        BotSettings.resetInstance(); // what the upstream reload does: a NEW settings object with upstream's values
        f.adapter.manageSettings(SHIPPED);
        assertEquals(2, saves());
        assertEquals(Boolean.FALSE, field("autoEquipWeapon"));
        assertEquals(2, settingsLines(f));
    }

    @Test
    void aChangedWishIsAppliedToTheSameObject() throws Exception {
        AdapterFixture f = AdapterFixture.probed();
        f.adapter.manageSettings(SHIPPED);
        f.adapter.manageSettings(new ManagedSettings(16.0, null, null, null, null, null));
        assertEquals(16.0, field("maxTargetDistance"));
        assertEquals(6.0, field("rangedMinRange"), "what the new wish no longer names keeps the last value");
        assertEquals(2, saves());
        assertTrue(f.sink.info.contains("PvP BOT settings: maxTargetDistance 10 -> 16"), f.sink.info.toString());
    }

    @Test
    void valuesThatAlreadyMatchAreNotWrittenAndNothingIsSaved() {
        AdapterFixture f = AdapterFixture.probed();
        BotSettings.put("maxTargetDistance", 10.0);
        BotSettings.put("autoEquipWeapon", false);
        f.adapter.manageSettings(new ManagedSettings(10.0, null, null, null, false, null));
        assertEquals(0, saves());
        assertFalse(f.sink.anyContains("PvP BOT settings:"));
    }

    @Test
    void wishesGivenBeforeTheProbeAreAppliedByTheProbeSoItsReportSeesThem() throws Exception {
        AdapterFixture f = AdapterFixture.healthy();
        f.adapter.manageSettings(SHIPPED);
        assertEquals(0, saves(), "nothing can be written before the contract is probed");
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(1, saves());
        assertEquals(10.0, field("maxTargetDistance"));
        assertTrue(f.sink.info.get(0).startsWith("PvP BOT settings:"), "the change is logged before the probe report");
    }

    @Test
    void invalidRangesAreRefusedWithOneWarningAndTheRestStillApplies() throws Exception {
        AdapterFixture f = AdapterFixture.probed();
        f.adapter.manageSettings(new ManagedSettings(10.0, 9.0, 8.0, 10.0, false, null));
        f.adapter.manageSettings(new ManagedSettings(10.0, 9.0, 8.0, 10.0, false, null));
        assertEquals(20.0, field("rangedMinRange"), "the ranged keys are left alone");
        assertEquals(10.0, field("maxTargetDistance"));
        assertEquals(1, f.sink.warn.stream().filter(w -> w.contains("ranged distances are not applied")).count(),
                f.sink.warn.toString());
    }

    @Test
    void aSettingsClassWithoutTheManagedFieldsIsReportedAsProblemsNotAnException() {
        UpstreamSettingsWriter.Handles none = UpstreamSettingsWriter.resolve(Object.class);
        assertTrue(none.fields().isEmpty());
        assertEquals(7, none.problems().size(), none.problems().toString());
        assertFalse(none.canWrite("maxTargetDistance"));
    }

    @Test
    void unavailableUpstreamNeverWrites() {
        AdapterFixture f = AdapterFixture.with(TestLocators.missing("org.stepan1411.pvp_bot.bot.BotSettings"));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        f.adapter.manageSettings(SHIPPED);
        assertEquals(0, saves());
    }
}
