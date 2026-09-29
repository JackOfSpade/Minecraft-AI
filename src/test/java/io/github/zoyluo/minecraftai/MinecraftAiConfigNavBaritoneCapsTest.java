package io.github.zoyluo.minecraftai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import org.junit.jupiter.api.Test;

/** {@code nav.baritone.*}: the switches of the Baritone movement capabilities (parkour, water-bucket fall, vines, mob avoidance). */
final class MinecraftAiConfigNavBaritoneCapsTest {
    private static MinecraftAiConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        MinecraftAiConfig config = MinecraftAiConfig.parse(root, OperatingProfile.STRICT_SURVIVAL);
        assertNotNull(config, "config must parse");
        return config;
    }

    @Test
    void theShippedDefaultsHaveTheTestedCapabilitiesOn() {
        MinecraftAiConfig.BaritoneCaps caps = MinecraftAiConfig.defaults().nav().baritoneCaps();
        assertTrue(caps.parkourEnabled() && caps.parkourPlaceEnabled() && caps.parkourAscendEnabled());
        assertTrue(caps.waterBucketFallEnabled() && caps.vinesEnabled() && caps.mobAvoidanceEnabled());
        assertEquals(12, caps.maxBucketFall());
    }

    @Test
    void missingSectionsAndValuesFallBackToTheDefaults() {
        MinecraftAiConfig.BaritoneCaps defaults = MinecraftAiConfig.BaritoneCaps.defaults();
        assertEquals(defaults, parse("{}").nav().baritoneCaps(), "no nav section");
        assertEquals(defaults, parse("{\"nav\":{\"engine\":\"baritone\"}}").nav().baritoneCaps(), "a nav section without baritone");
        assertEquals(defaults, parse("{\"nav\":{\"baritone\":{}}}").nav().baritoneCaps(), "an empty baritone section");
    }

    @Test
    void eachSwitchIsIndependentAndKeepsTheOthers() {
        MinecraftAiConfig.BaritoneCaps caps = parse("{\"nav\":{\"baritone\":{\"parkour\":false,\"waterBucketFall\":false,\"maxBucketFall\":9}}}").nav().baritoneCaps();
        assertFalse(caps.parkourEnabled());
        assertFalse(caps.waterBucketFallEnabled());
        assertEquals(9, caps.maxBucketFall());
        assertTrue(caps.parkourPlaceEnabled() && caps.parkourAscendEnabled(), "the sub-switches keep their own values (parkour gates them at apply time)");
        assertTrue(caps.vinesEnabled() && caps.mobAvoidanceEnabled());
        MinecraftAiConfig.BaritoneCaps off = parse("{\"nav\":{\"baritone\":{\"vines\":false,\"mobAvoidance\":false}}}").nav().baritoneCaps();
        assertFalse(off.vinesEnabled() || off.mobAvoidanceEnabled());
        assertTrue(off.parkourEnabled() && off.waterBucketFallEnabled());
    }

    @Test
    void aNonPositiveBucketLimitIsTheDefaultAndAllOffIsAllOff() {
        assertEquals(12, parse("{\"nav\":{\"baritone\":{\"maxBucketFall\":0}}}").nav().baritoneCaps().maxBucketFall());
        MinecraftAiConfig.BaritoneCaps off = MinecraftAiConfig.BaritoneCaps.allOff();
        assertFalse(off.parkourEnabled() || off.parkourPlaceEnabled() || off.parkourAscendEnabled() || off.waterBucketFallEnabled()
                || off.vinesEnabled() || off.mobAvoidanceEnabled());
    }

    @Test
    void theNineArgumentNavConstructorStillMeansTheDefaultCapabilities() {
        MinecraftAiConfig.Nav nav = MinecraftAiConfig.defaults().nav();
        MinecraftAiConfig.Nav legacyShape = new MinecraftAiConfig.Nav(nav.jumpReach(), nav.sidleAfter(), nav.sidleLimit(), nav.hardLimit(),
                nav.lookahead(), nav.nodeRetry(), nav.sprintMinDist(), nav.maxSafeFall(), nav.engine());
        assertEquals(MinecraftAiConfig.BaritoneCaps.defaults(), legacyShape.baritoneCaps());
    }
}
