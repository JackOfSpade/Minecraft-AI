package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import org.junit.jupiter.api.Test;
import org.stepan1411.pvp_bot.bot.BotSettings;
import org.stepan1411.testdouble.Recorder;
import org.stepan1411.testdouble.Settings;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** readCapabilities() and discoverUpstreamSettingNames(): read-only views of PvP BOT's global settings. */
class PvpBotAdapterCapabilitiesTest {

    /** One capability: the fake's field name and the record component it feeds. */
    private record Cap(String field, Function<GlobalCapabilities, Boolean> component) {
    }

    private static final List<Cap> CAPS = List.of(
            new Cap("autoEquipArmor", GlobalCapabilities::autoEquipArmor),
            new Cap("autoEquipWeapon", GlobalCapabilities::autoEquipWeapon),
            new Cap("combatEnabled", GlobalCapabilities::combatEnabled),
            new Cap("autoTargetEnabled", GlobalCapabilities::autoTargetEnabled),
            new Cap("rangedEnabled", GlobalCapabilities::rangedEnabled),
            new Cap("maceEnabled", GlobalCapabilities::maceEnabled),
            new Cap("spearEnabled", GlobalCapabilities::spearEnabled),
            new Cap("crystalPvpEnabled", GlobalCapabilities::crystalPvpEnabled),
            new Cap("anchorPvpEnabled", GlobalCapabilities::anchorPvpEnabled),
            new Cap("cobwebEnabled", GlobalCapabilities::cobwebEnabled),
            new Cap("autoTotemEnabled", GlobalCapabilities::autoTotemEnabled),
            new Cap("autoShieldEnabled", GlobalCapabilities::autoShieldEnabled),
            new Cap("autoEatEnabled", GlobalCapabilities::autoEatEnabled),
            new Cap("autoPotionEnabled", GlobalCapabilities::autoPotionEnabled),
            new Cap("autoMendEnabled", GlobalCapabilities::autoMendEnabled),
            new Cap("shieldBreakEnabled", GlobalCapabilities::shieldBreakEnabled),
            new Cap("retreatEnabled", GlobalCapabilities::retreatEnabled),
            new Cap("botsRelogs", GlobalCapabilities::botsRelogs),
            new Cap("botLeaveOnDeath", GlobalCapabilities::botLeaveOnDeath),
            new Cap("clearOnRemove", GlobalCapabilities::clearOnRemove),
            new Cap("totemPriority", GlobalCapabilities::totemPriority),
            new Cap("preferSword", GlobalCapabilities::preferSword),
            new Cap("rangedRetreatOnClose", GlobalCapabilities::rangedRetreatOnClose));

    // ---------------------------------------------------------------- capabilities

    @Test
    void beforeAnyProbeTheUpstreamDefaultsAreReturned() {
        AdapterFixture f = AdapterFixture.healthy();
        assertEquals(GlobalCapabilities.upstreamDefaults(), f.adapter.readCapabilities());
        assertTrue(Recorder.CALLS.isEmpty(), "nothing upstream may be touched before the probe");
    }

    @Test
    void afterTheProbeTheValuesComeFromTheSettingsSingleton() {
        AdapterFixture f = AdapterFixture.probed();
        assertEquals(GlobalCapabilities.upstreamDefaults(), f.adapter.readCapabilities(),
                "the fake is at upstream's factory defaults");
        BotSettings.put("maceEnabled", false);
        BotSettings.put("spearEnabled", true);
        BotSettings.put("autoTargetEnabled", true);
        GlobalCapabilities c = f.adapter.readCapabilities();
        assertFalse(c.maceEnabled());
        assertTrue(c.spearEnabled());
        assertTrue(c.autoTargetEnabled());
        assertTrue(c.rangedEnabled(), "untouched switches keep their value");
    }

    @Test
    void everyReadIsFreshBecauseSettingsChangeAtRunTime() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(f.adapter.readCapabilities().botsRelogs());
        BotSettings.put("botsRelogs", false);
        assertFalse(f.adapter.readCapabilities().botsRelogs());
        BotSettings.put("botsRelogs", true);
        assertTrue(f.adapter.readCapabilities().botsRelogs());
        BotSettings.resetInstance();
        BotSettings.put("botLeaveOnDeath", false);
        assertFalse(f.adapter.readCapabilities().botLeaveOnDeath(), "even a brand-new singleton is seen");
    }

    @Test
    void eachFlagIsReadFromItsOwnGetter() {
        AdapterFixture f = AdapterFixture.probed();
        GlobalCapabilities defaults = GlobalCapabilities.upstreamDefaults();
        assertEquals(23, CAPS.size());
        for (Cap cap : CAPS) {
            BotSettings.resetInstance();
            boolean flipped = !cap.component().apply(defaults);
            BotSettings.put(cap.field(), flipped);
            GlobalCapabilities read = f.adapter.readCapabilities();
            assertEquals(flipped, cap.component().apply(read), cap.field() + " must follow its getter");
            for (Cap other : CAPS) {
                if (other != cap) {
                    assertEquals(other.component().apply(defaults), other.component().apply(read),
                            other.field() + " must not change when only " + cap.field() + " did");
                }
            }
        }
    }

    @Test
    void gettersThatSurvivedAreReadAndTheRestFallBackToDefaultsIndividually() {
        // FewGetters: maceEnabled/botsRelogs/cobwebEnabled are all FALSE there, everything else has no getter.
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.FewGetters.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        GlobalCapabilities c = f.adapter.readCapabilities();
        GlobalCapabilities d = GlobalCapabilities.upstreamDefaults();
        assertFalse(c.maceEnabled());
        assertFalse(c.botsRelogs());
        assertFalse(c.cobwebEnabled());
        assertEquals(d.rangedEnabled(), c.rangedEnabled(), "no getter: default");
        assertEquals(d.autoTargetEnabled(), c.autoTargetEnabled());
        assertEquals(d.retreatEnabled(), c.retreatEnabled());
        assertNotEquals(d, c);
    }

    @Test
    void gettersWithTheWrongShapeAreIgnored() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.WrongTypes.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(GlobalCapabilities.upstreamDefaults(), assertDoesNotThrow(f.adapter::readCapabilities));
    }

    @Test
    void aGetterThatThrowsFallsBackForThatFlagOnlyAndLogsOnce() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.ThrowingGetter.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        f.sink.warn.clear();
        GlobalCapabilities first = assertDoesNotThrow(f.adapter::readCapabilities);
        assertTrue(first.maceEnabled(), "the throwing getter falls back to the default (mace defaults to on)");
        assertTrue(first.spearEnabled(), "another getter of the same class still works (spear defaults to off)");
        for (int i = 0; i < 50; i++) {
            f.adapter.readCapabilities();
        }
        assertEquals(1, f.sink.warn.stream().filter(w -> w.contains("isMaceEnabled")).count(),
                "a persistent failure is reported once, not once per tick");
    }

    @Test
    void aStaticAccessorThatThrowsGivesTheDefaultsAndLogsOnce() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.ThrowingGet.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        for (int i = 0; i < 20; i++) {
            assertEquals(GlobalCapabilities.upstreamDefaults(), f.adapter.readCapabilities());
        }
        long reports = f.sink.warn.stream().filter(w -> w.contains("settings could not be loaded")).count();
        assertEquals(1, reports, "one warning for the probe and all twenty reads together: " + f.sink.warn);
    }

    @Test
    void aSingletonThatIsNotLoadedYetGivesTheDefaults() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.NullGet.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(GlobalCapabilities.upstreamDefaults(), f.adapter.readCapabilities());
    }

    @Test
    void missingSettingsApiGivesTheDefaults() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.NoGet.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(GlobalCapabilities.upstreamDefaults(), f.adapter.readCapabilities());
    }

    @Test
    void neverWritesAnythingNoSetterNoLoadNoSave() {
        AdapterFixture f = AdapterFixture.probed();
        for (int i = 0; i < 5; i++) {
            f.adapter.readCapabilities();
        }
        f.adapter.discoverUpstreamSettingNames();
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertTrue(Recorder.FORBIDDEN.isEmpty(), "forbidden upstream calls happened: " + Recorder.FORBIDDEN);
    }

    // ---------------------------------------------------------------- setting discovery

    @Test
    void discoveryListsTheInstanceFieldsAndOnlyThose() {
        AdapterFixture f = AdapterFixture.healthy();
        Set<String> names = f.adapter.discoverUpstreamSettingNames();
        assertEquals(39, names.size(), names.toString());
        assertTrue(names.containsAll(Set.of("autoEquipArmor", "botsRelogs", "botLeaveOnDeath", "checkInterval",
                "maxMassSpawn", "useSpecialNames", "clearOnRemove", "cobwebEnabled", "totemPriority", "preferSword",
                "rangedRetreatOnClose")));
        for (String staticField : List.of("GSON", "INSTANCE", "configPath", "DEFAULTS")) {
            assertFalse(names.contains(staticField), staticField + " is static, not a setting");
        }
    }

    @Test
    void discoveryIsMetadataOnlyAndWorksBeforeTheProbe() {
        AdapterFixture f = AdapterFixture.healthy();
        assertFalse(f.adapter.discoverUpstreamSettingNames().isEmpty());
        assertTrue(Recorder.CALLS.isEmpty(), "no upstream method is called, no instance is read");
    }

    @Test
    void discoveryIsEmptyWhenTheClassIsAbsent() {
        AdapterFixture f = AdapterFixture.with(TestLocators.missing(UpstreamNames.CLASS_BOT_SETTINGS));
        assertEquals(Set.of(), f.adapter.discoverUpstreamSettingNames());
    }

    @Test
    void discoveryNeverThrowsWhateverTheLoaderDoes() {
        AdapterFixture f = AdapterFixture.with(TestLocators.builder()
                .failWith(UpstreamNames.CLASS_BOT_SETTINGS, new NoClassDefFoundError("x")).build());
        assertEquals(Set.of(), assertDoesNotThrow(f.adapter::discoverUpstreamSettingNames));
    }

    @Test
    void discoveryDoesNotListStaticFieldsOfAVariantEither() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.NoGet.class));
        assertEquals(Set.of("maceEnabled"), f.adapter.discoverUpstreamSettingNames());
    }

    @Test
    void theDiscoveredSetIsUnmodifiableAndStable() {
        AdapterFixture f = AdapterFixture.healthy();
        Set<String> first = f.adapter.discoverUpstreamSettingNames();
        assertEquals(first, f.adapter.discoverUpstreamSettingNames());
        try {
            first.add("hack");
            throw new AssertionError("the set must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // fine
        }
    }
}
