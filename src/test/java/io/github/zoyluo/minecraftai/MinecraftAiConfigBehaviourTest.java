package io.github.zoyluo.minecraftai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import org.junit.jupiter.api.Test;

/** {@code behaviour}: the companion behaviour switches (pace, hostile-bot targeting, gear, follow, warden). */
final class MinecraftAiConfigBehaviourTest {
    private static MinecraftAiConfig parse(String json, OperatingProfile profile) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        MinecraftAiConfig config = MinecraftAiConfig.parse(root, profile);
        assertNotNull(config, "config must parse");
        return config;
    }

    private static MinecraftAiConfig parse(String json) {
        return parse(json, OperatingProfile.STRICT_SURVIVAL);
    }

    private static void assertShippedDefaults(MinecraftAiConfig.Behaviour behaviour) {
        MinecraftAiConfig.Pace pace = behaviour.pace();
        assertTrue(pace.paceEnabled());
        assertTrue(pace.quietZoneCautionEnabled());
        assertEquals(8.0D, pace.routeSprintDistance());
        assertEquals(4.5D, pace.routeWalkDistance());
        MinecraftAiConfig.Targeting targeting = behaviour.targeting();
        assertTrue(targeting.hostileBotsEnabled());
        assertTrue(targeting.ownerVisionEnabled());
        assertEquals(600, targeting.aggressorMemoryTicks());
        assertEquals(48, targeting.ownerVisionRange());
        assertEquals(0.5D, targeting.ownerViewConeDot());
        assertTrue(behaviour.gear().worstFirstEnabled());
        MinecraftAiConfig.Follow follow = behaviour.follow();
        assertTrue(follow.escortOnlyEnabled());
        assertEquals(6.0D, follow.walkGap());
        assertEquals(10.0D, follow.sprintGap());
        assertTrue(behaviour.warden().sneakAwayEnabled());
        assertEquals(540.0D, behaviour.combatOrDefaults().aimOrDefaults().maxTurnDegPerSec());
    }

    @Test
    void theShippedDefaultsAreTheDocumentedOnes() {
        assertShippedDefaults(MinecraftAiConfig.defaults().behaviour());
        assertShippedDefaults(MinecraftAiConfig.Behaviour.defaults());
    }

    @Test
    void jsonWithoutBehaviourGivesTheDefaultsInBothProfiles() {
        assertShippedDefaults(parse("{}").behaviour());
        assertShippedDefaults(parse("{\"nav\":{\"maxSafeFall\":5}}").behaviour());
        assertShippedDefaults(parse("{}", OperatingProfile.OPERATOR).behaviour());
        assertShippedDefaults(parse("{\"behaviour\":{}}").behaviour());
        assertShippedDefaults(parse("{\"behaviour\":{\"pace\":{},\"targeting\":{},\"gear\":{},\"follow\":{},\"warden\":{}}}").behaviour());
    }

    @Test
    void aPartialSectionKeepsTheGivenValuesAndDefaultsTheRest() {
        MinecraftAiConfig.Behaviour behaviour = parse("{\"behaviour\":{"
                + "\"pace\":{\"enabled\":false,\"routeSprintDistance\":12.0},"
                + "\"targeting\":{\"ownerVision\":false,\"aggressorMemoryTicks\":900},"
                + "\"gear\":{\"worstFirst\":false},"
                + "\"follow\":{\"escortOnly\":false},"
                + "\"warden\":{\"sneakAway\":false}}}").behaviour();
        assertFalse(behaviour.pace().paceEnabled());
        assertEquals(12.0D, behaviour.pace().routeSprintDistance());
        assertEquals(4.5D, behaviour.pace().routeWalkDistance(), "the missing walk distance is the default");
        assertTrue(behaviour.pace().quietZoneCautionEnabled());
        assertFalse(behaviour.targeting().ownerVisionEnabled());
        assertEquals(900, behaviour.targeting().aggressorMemoryTicks());
        assertTrue(behaviour.targeting().hostileBotsEnabled());
        assertEquals(48, behaviour.targeting().ownerVisionRange());
        assertEquals(0.5D, behaviour.targeting().ownerViewConeDot());
        assertFalse(behaviour.gear().worstFirstEnabled());
        assertFalse(behaviour.follow().escortOnlyEnabled());
        assertEquals(6.0D, behaviour.follow().walkGap());
        assertFalse(behaviour.warden().sneakAwayEnabled());
    }

    @Test
    void onlyAGivenSectionIsChanged() {
        MinecraftAiConfig.Behaviour behaviour = parse("{\"behaviour\":{\"gear\":{\"worstFirst\":false}}}").behaviour();
        assertFalse(behaviour.gear().worstFirstEnabled());
        assertEquals(MinecraftAiConfig.Behaviour.defaults().pace(), behaviour.pace());
        assertEquals(MinecraftAiConfig.Behaviour.defaults().targeting(), behaviour.targeting());
        assertEquals(MinecraftAiConfig.Behaviour.defaults().follow(), behaviour.follow());
        assertEquals(MinecraftAiConfig.Behaviour.defaults().warden(), behaviour.warden());
    }

    @Test
    void invalidNumbersFallBackToTheDefaults() {
        MinecraftAiConfig.Behaviour behaviour = parse("{\"behaviour\":{"
                + "\"pace\":{\"routeSprintDistance\":-3.0,\"routeWalkDistance\":0},"
                + "\"targeting\":{\"aggressorMemoryTicks\":-5,\"ownerVisionRange\":0,\"ownerViewConeDot\":-0.3},"
                + "\"follow\":{\"walkGap\":-1.0,\"sprintGap\":0.0}}}").behaviour();
        assertEquals(8.0D, behaviour.pace().routeSprintDistance());
        assertEquals(4.5D, behaviour.pace().routeWalkDistance());
        assertEquals(600, behaviour.targeting().aggressorMemoryTicks());
        assertEquals(48, behaviour.targeting().ownerVisionRange());
        assertEquals(0.5D, behaviour.targeting().ownerViewConeDot());
        assertEquals(6.0D, behaviour.follow().walkGap());
        assertEquals(10.0D, behaviour.follow().sprintGap());
    }

    @Test
    void notANumberAndInfinityFallBackToTheDefaults() {
        MinecraftAiConfig.Pace defaultPace = MinecraftAiConfig.Pace.defaults();
        MinecraftAiConfig.Pace nan = new MinecraftAiConfig.Pace(null, Double.NaN, Double.NaN, null).withDefaults(defaultPace);
        assertEquals(defaultPace, nan);
        MinecraftAiConfig.Pace infinite = new MinecraftAiConfig.Pace(true, Double.POSITIVE_INFINITY, 2.0D, true)
                .withDefaults(defaultPace);
        assertEquals(8.0D, infinite.routeSprintDistance());
        assertEquals(2.0D, infinite.routeWalkDistance());
        MinecraftAiConfig.Targeting targeting = new MinecraftAiConfig.Targeting(null, null, 0, 0, Double.NaN)
                .withDefaults(MinecraftAiConfig.Targeting.defaults());
        assertEquals(MinecraftAiConfig.Targeting.defaults(), targeting);
        MinecraftAiConfig.Follow follow = new MinecraftAiConfig.Follow(null, Double.NaN, Double.NaN)
                .withDefaults(MinecraftAiConfig.Follow.defaults());
        assertEquals(MinecraftAiConfig.Follow.defaults(), follow);
    }

    @Test
    void theViewConeDotMustBeInsideTheDotProductRange() {
        assertEquals(0.5D, parse("{\"behaviour\":{\"targeting\":{\"ownerViewConeDot\":1.5}}}").behaviour().targeting().ownerViewConeDot());
        assertEquals(0.5D, parse("{\"behaviour\":{\"targeting\":{\"ownerViewConeDot\":-1.2}}}").behaviour().targeting().ownerViewConeDot());
        assertEquals(0.7D, parse("{\"behaviour\":{\"targeting\":{\"ownerViewConeDot\":0.7}}}").behaviour().targeting().ownerViewConeDot());
        assertEquals(1.0D, parse("{\"behaviour\":{\"targeting\":{\"ownerViewConeDot\":1.0}}}").behaviour().targeting().ownerViewConeDot());
    }

    @Test
    void invertedOrEqualGapsFallBackToTheDefaultsTogether() {
        MinecraftAiConfig.Pace pace = parse("{\"behaviour\":{\"pace\":{\"routeSprintDistance\":3.0,\"routeWalkDistance\":9.0}}}")
                .behaviour().pace();
        assertEquals(8.0D, pace.routeSprintDistance());
        assertEquals(4.5D, pace.routeWalkDistance());
        MinecraftAiConfig.Pace equalPace = parse("{\"behaviour\":{\"pace\":{\"routeSprintDistance\":5.0,\"routeWalkDistance\":5.0}}}")
                .behaviour().pace();
        assertEquals(8.0D, equalPace.routeSprintDistance());
        assertEquals(4.5D, equalPace.routeWalkDistance());
        MinecraftAiConfig.Follow follow = parse("{\"behaviour\":{\"follow\":{\"walkGap\":12.0,\"sprintGap\":7.0}}}")
                .behaviour().follow();
        assertEquals(6.0D, follow.walkGap());
        assertEquals(10.0D, follow.sprintGap());
        // A consistent pair the user chose is kept.
        MinecraftAiConfig.Follow kept = parse("{\"behaviour\":{\"follow\":{\"walkGap\":3.0,\"sprintGap\":7.0}}}").behaviour().follow();
        assertEquals(3.0D, kept.walkGap());
        assertEquals(7.0D, kept.sprintGap());
    }


    @Test
    void removedSwitchesAreIgnoredAndReportedNotHonoured() {
        String json = "{\"operatorCapabilities\":{\"forcedPickup\":true,\"manualTeleport\":false},"
                + "\"pickup\":{\"forceRadiusH\":9.0,\"forceRadiusV\":9.0,\"sweepRadius\":6.0},"
                + "\"behaviour\":{\"pace\":{\"itemUseSlowdown\":false,\"movementExhaustion\":false,\"enabled\":true}}}";
        MinecraftAiConfig config = parse(json, OperatingProfile.OPERATOR);
        assertEquals(6.0D, config.pickup().sweepRadius(), "the surviving key of a partly removed section is still read");
        assertEquals(Boolean.FALSE, config.operatorCapabilities().manualTeleport());
        assertEquals(
                java.util.List.of("behaviour.pace.itemUseSlowdown", "behaviour.pace.movementExhaustion",
                        "operatorCapabilities.forcedPickup", "pickup.forceRadiusH", "pickup.forceRadiusV"),
                MinecraftAiConfig.removedKeysPresent(JsonParser.parseString(json).getAsJsonObject()));
        assertEquals(java.util.List.of(), MinecraftAiConfig.removedKeysPresent(JsonParser.parseString("{}").getAsJsonObject()));
        assertEquals(java.util.List.of(), MinecraftAiConfig.removedKeysPresent(
                JsonParser.parseString("{\"behaviour\":{\"pace\":{\"enabled\":false}}}").getAsJsonObject()));
    }

    @Test
    void movementExhaustionHasNoConfigurationSwitch() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/entity/AIPlayerEntity.java"));
        int start = source.indexOf("private void chargeMovementExhaustion()");
        int end = source.indexOf("checkMovementStatistics(dx, dy, dz)", start);
        assertTrue(start > 0 && end > start);
        assertFalse(source.substring(start, end).contains("MinecraftAiConfig"),
                "the hunger cost of moving is vanilla and unconditional");
    }
    @Test
    void nullBooleansReadAsTheDefault() {
        assertTrue(new MinecraftAiConfig.Pace(null, 0.0D, 0.0D, null).paceEnabled());
        assertTrue(new MinecraftAiConfig.Pace(null, 0.0D, 0.0D, null).quietZoneCautionEnabled());
        assertTrue(new MinecraftAiConfig.Targeting(null, null, 0, 0, 0.0D).hostileBotsEnabled());
        assertTrue(new MinecraftAiConfig.Targeting(null, null, 0, 0, 0.0D).ownerVisionEnabled());
        assertTrue(new MinecraftAiConfig.Gear(null).worstFirstEnabled());
        assertTrue(new MinecraftAiConfig.Follow(null, 0.0D, 0.0D).escortOnlyEnabled());
        assertTrue(new MinecraftAiConfig.Warden(null).sneakAwayEnabled());
        assertFalse(new MinecraftAiConfig.Gear(false).worstFirstEnabled());
    }

    @Test
    void theAimSectionKeepsAGoodRateAndDefaultsABadOne() {
        assertEquals(720.0D, parse("{\"behaviour\":{\"combat\":{\"aim\":{\"maxTurnDegPerSec\":720.0}}}}")
                .behaviour().combatOrDefaults().aimOrDefaults().maxTurnDegPerSec());
        for (String bad : new String[]{"0", "-90"}) {
            assertEquals(540.0D, parse("{\"behaviour\":{\"combat\":{\"aim\":{\"maxTurnDegPerSec\":" + bad + "}}}}")
                    .behaviour().combatOrDefaults().aimOrDefaults().maxTurnDegPerSec(), "a bad rate is the default: " + bad);
        }
        assertEquals(540.0D, parse("{\"behaviour\":{\"combat\":{}}}").behaviour().combatOrDefaults().aimOrDefaults().maxTurnDegPerSec());
    }

    @Test
    void aNullBehaviourNeverEscapes() {
        MinecraftAiConfig config = MinecraftAiConfig.defaults().withBehaviour(null);
        assertNotNull(config.behaviour());
        assertNotNull(config.behaviourOrDefaults());
        assertShippedDefaults(config.behaviour());
        MinecraftAiConfig.Behaviour hollow = new MinecraftAiConfig.Behaviour(null, null, null, null, null);
        assertShippedDefaults(new MinecraftAiConfig.Behaviour(
                hollow.paceOrDefaults(), hollow.targetingOrDefaults(), hollow.gearOrDefaults(),
                hollow.followOrDefaults(), hollow.wardenOrDefaults()));
    }

    @Test
    void theSixteenArgumentConstructorYieldsTheDefaults() {
        MinecraftAiConfig defaults = MinecraftAiConfig.defaults();
        MinecraftAiConfig oldStyle = new MinecraftAiConfig(defaults.profile(), defaults.operatorCapabilities(), defaults.llm(),
                defaults.perception(), defaults.brain(), defaults.watchdog(), defaults.logging(), defaults.survival(),
                defaults.combat(), defaults.night(), defaults.mining(), defaults.goal(), defaults.nav(), defaults.pickup(),
                defaults.conversation(), defaults.storage());
        assertEquals(defaults, oldStyle);
        assertShippedDefaults(oldStyle.behaviour());
        // The pre-storage 15-argument constructor still exists and reaches the same defaults.
        MinecraftAiConfig older = new MinecraftAiConfig(defaults.profile(), defaults.operatorCapabilities(), defaults.llm(),
                defaults.perception(), defaults.brain(), defaults.watchdog(), defaults.logging(), defaults.survival(),
                defaults.combat(), defaults.night(), defaults.mining(), defaults.goal(), defaults.nav(), defaults.pickup(),
                defaults.conversation());
        assertEquals(defaults, older);
    }

    @Test
    void theCopiesCarryTheBehaviour() {
        MinecraftAiConfig.Behaviour custom = new MinecraftAiConfig.Behaviour(
                new MinecraftAiConfig.Pace(false, 9.0D, 5.0D, true),
                MinecraftAiConfig.Targeting.defaults(),
                new MinecraftAiConfig.Gear(false),
                MinecraftAiConfig.Follow.defaults(),
                MinecraftAiConfig.Warden.defaults());
        MinecraftAiConfig config = MinecraftAiConfig.defaults().withBehaviour(custom);
        assertEquals(custom, config.behaviour());
        assertEquals(custom, config.withNav(config.nav()).behaviour());
        assertEquals(custom, config.withLlm(config.llm()).behaviour());
    }
}
