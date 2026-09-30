package io.github.zoyluo.minecraftai.mode;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftAiConfigModeDefaultsTest {
    @Test
    void generatedConfigShapeUsesTopLevelStrictProfileAndCapabilityFlags() {
        MinecraftAiConfig defaults = MinecraftAiConfig.defaults();
        JsonObject json = JsonParser.parseString(new Gson().toJson(defaults)).getAsJsonObject();

        assertEquals(OperatingProfile.STRICT_SURVIVAL, defaults.profile());
        assertEquals("strict_survival", json.get("profile").getAsString());
        assertTrue(json.has("operatorCapabilities"));
        JsonObject flags = json.getAsJsonObject("operatorCapabilities");
        assertTrue(flags.get("hiddenBlockScan").getAsBoolean());
        assertTrue(flags.get("emergencyTeleport").getAsBoolean());
        assertTrue(flags.get("manualTeleport").getAsBoolean());
    }

    @Test
    void partialOperatorFlagsPreserveExplicitFalseAndDefaultMissingValues() {
        OperatorCapabilities merged = new OperatorCapabilities(false, null, false)
                .withDefaults(OperatorCapabilities.defaults());

        assertEquals(Boolean.FALSE, merged.hiddenBlockScan());
        assertEquals(Boolean.TRUE, merged.emergencyTeleport());
        assertEquals(Boolean.FALSE, merged.manualTeleport());
    }
}
