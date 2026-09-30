package io.github.zoyluo.minecraftai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.perception.CreaturePerception;
import org.junit.jupiter.api.Test;

/** {@code behaviour.perception}: realistic noticing of creatures (docs/PERCEPTION.md): defaults, tolerance and bounds. */
final class PerceptionBehaviourConfigTest {
    private static MinecraftAiConfig.PerceptionBehaviour parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        MinecraftAiConfig config = MinecraftAiConfig.parse(root, OperatingProfile.STRICT_SURVIVAL);
        assertNotNull(config, "config must parse");
        return config.behaviour().perceptionOrDefaults();
    }

    private static void assertDefaults(MinecraftAiConfig.PerceptionBehaviour p) {
        assertTrue(p.enabledOn());
        assertEquals(CreaturePerception.Params.defaults(), p.params(), "the shipped defaults are the shared golden-vector defaults");
        assertEquals(16, p.hearingRadius(), "the Warden's vibration radius");
    }

    @Test
    void theShippedDefaultsAreTheSharedModelsDefaults() {
        assertDefaults(MinecraftAiConfig.defaults().behaviour().perceptionOrDefaults());
        assertDefaults(MinecraftAiConfig.PerceptionBehaviour.defaults());
        assertDefaults(parse("{}"));
        assertDefaults(parse("{\"behaviour\":{}}"));
        assertDefaults(parse("{\"behaviour\":{\"perception\":{}}}"));
        assertDefaults(parse("{\"behaviour\":{\"perception\":{\"hearing\":{}}}}"));
        // the older constructors carry the perception defaults
        assertDefaults(new MinecraftAiConfig.Behaviour(null, null, null, null, null).perceptionOrDefaults());
        assertDefaults(new MinecraftAiConfig.Behaviour(null, null, null, null, null, null).perceptionOrDefaults());
    }

    @Test
    void everyKeyOfTheWrappersModelIsReadHere() {
        MinecraftAiConfig.PerceptionBehaviour p = parse("{\"behaviour\":{\"perception\":{\"enabled\":false,"
                + "\"reactionBaseSeconds\":0.25,\"reactionAt64Seconds\":1.5,\"fullAttentionHalfAngleDeg\":40,"
                + "\"peripheralHalfAngleDeg\":120,\"peripheralMultiplier\":3,\"sneakMultiplier\":2.5,"
                + "\"hearing\":{\"listenerRadius\":8}}}}");
        assertFalse(p.enabledOn());
        assertEquals(new CreaturePerception.Params(false, 0.25, 1.5, 40.0, 120.0, 3.0, 2.5), p.params());
        assertEquals(8, p.hearingRadius());
    }

    @Test
    void anInvalidValueIsTheDefaultAndAnInvertedPairFallsBackToBoth() {
        // negative seconds, angles outside 0..180, multipliers outside 1..20, a radius that is zero, negative or absurd
        MinecraftAiConfig.PerceptionBehaviour bad = parse("{\"behaviour\":{\"perception\":{\"reactionBaseSeconds\":-1,"
                + "\"fullAttentionHalfAngleDeg\":200,\"peripheralMultiplier\":0.5,\"sneakMultiplier\":99,"
                + "\"hearing\":{\"listenerRadius\":-4}}}}");
        assertDefaults(bad);
        assertEquals(16, parse("{\"behaviour\":{\"perception\":{\"hearing\":{\"listenerRadius\":100000}}}}").hearingRadius());
        // at64 below the base: both back to the defaults; full above peripheral: both back to the defaults
        assertDefaults(parse("{\"behaviour\":{\"perception\":{\"reactionBaseSeconds\":3,\"reactionAt64Seconds\":1}}}"));
        assertDefaults(parse("{\"behaviour\":{\"perception\":{\"fullAttentionHalfAngleDeg\":120,\"peripheralHalfAngleDeg\":90}}}"));
        // a zero base is a valid reaction time (instant up close)
        assertEquals(0.0, parse("{\"behaviour\":{\"perception\":{\"reactionBaseSeconds\":0}}}").params().reactionBaseSeconds());
    }
}
