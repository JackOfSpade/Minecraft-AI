package io.github.zoyluo.minecraftai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JSON section that overrides only one field must not silently reset its other, omitted
 * boolean fields to Java's primitive default ({@code false}); they must keep falling back to the
 * shipped default, exactly like the {@code Boolean}-typed flags elsewhere in this file
 * (Brain/Goal/Conversation). See {@code MinecraftAiConfigConversationTest} for the same contract
 * on {@code Conversation.enabled}.
 */
final class MinecraftAiConfigPartialSectionBooleanTest {

    private static MinecraftAiConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        MinecraftAiConfig config = MinecraftAiConfig.parse(root, OperatingProfile.STRICT_SURVIVAL);
        assertTrue(config != null, "config must parse");
        return config;
    }

    @Test
    void aPartialLoggingSectionKeepsTheShippedBooleanDefaults() {
        MinecraftAiConfig.Logging logging = parse("{\"logging\":{\"directory\":\"custom_logs\"}}").logging();
        MinecraftAiConfig.Logging defaults = MinecraftAiConfig.defaults().logging();

        assertTrue(logging.enabled(), "omitting 'enabled' must not silently disable logging");
        assertTrue(logging.perBotFile(), "omitting 'perBotFile' must fall back to the shipped default");
        assertTrue(logging.mirrorToSlf4j(), "omitting 'mirrorToSlf4j' must fall back to the shipped default");
        assertTrue(defaults.enabled() && defaults.perBotFile() && defaults.mirrorToSlf4j(),
                "sanity check: the shipped defaults for these flags are true");
    }

    @Test
    void explicitFalseInTheLoggingSectionIsHonoured() {
        MinecraftAiConfig.Logging logging = parse(
                "{\"logging\":{\"enabled\":false,\"perBotFile\":false,\"mirrorToSlf4j\":false}}").logging();

        assertFalse(logging.enabled());
        assertFalse(logging.perBotFile());
        assertFalse(logging.mirrorToSlf4j());
    }

    @Test
    void aPartialNightSectionKeepsTheShippedAutoLightDefault() {
        MinecraftAiConfig.Night night = parse("{\"night\":{\"torchLightThreshold\":12}}").night();

        assertTrue(night.autoLight(), "omitting 'autoLight' must not silently disable it");
        assertTrue(MinecraftAiConfig.defaults().night().autoLight(), "sanity check: shipped default is true");
    }

    @Test
    void explicitFalseAutoLightIsHonoured() {
        assertFalse(parse("{\"night\":{\"autoLight\":false}}").night().autoLight());
    }

    @Test
    void theLegacyAutoSleepKeyIsStillReadAndTheNewNameWinsWhenBothArePresent() {
        assertFalse(parse("{\"night\":{\"autoSleep\":false}}").night().autoLight(),
                "an existing config file that says autoSleep=false keeps switching the reflexes off");
        assertTrue(parse("{\"night\":{\"autoSleep\":true}}").night().autoLight());
        assertTrue(parse("{\"night\":{\"autoSleep\":false,\"autoLight\":true}}").night().autoLight());
        assertFalse(parse("{\"night\":{\"autoLight\":false,\"autoSleep\":true}}").night().autoLight());
    }

    @Test
    void aPartialMiningSectionKeepsTheShippedPlaceTorchesDefault() {
        MinecraftAiConfig.Mining mining = parse("{\"mining\":{\"returnWhenFreeSlots\":4}}").mining();

        assertTrue(mining.placeTorches(), "omitting 'placeTorches' must not silently disable it");
        assertTrue(MinecraftAiConfig.defaults().mining().placeTorches(), "sanity check: shipped default is true");
    }

    @Test
    void anOldMiningSectionWithTheRetiredToolDurabilityFloorStillLoads() {
        // A worn pickaxe never ends a tunnel any more (use it until it breaks); the key is ignored, the rest of the section is kept.
        MinecraftAiConfig.Mining mining = parse(
                "{\"mining\":{\"returnWhenFreeSlots\":4,\"toolDurabilityFloor\":0.25,\"placeTorches\":false}}").mining();

        assertTrue(mining.returnWhenFreeSlots() == 4);
        assertFalse(mining.placeTorches());
    }

    @Test
    void explicitFalsePlaceTorchesIsHonoured() {
        MinecraftAiConfig.Mining mining = parse("{\"mining\":{\"placeTorches\":false}}").mining();

        assertFalse(mining.placeTorches());
    }
}
