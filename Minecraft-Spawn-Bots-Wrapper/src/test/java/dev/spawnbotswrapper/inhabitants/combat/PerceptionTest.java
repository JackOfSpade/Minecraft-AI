package dev.spawnbotswrapper.inhabitants.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.spawnbotswrapper.inhabitants.combat.Perception.MobKind;
import dev.spawnbotswrapper.inhabitants.combat.Perception.Notice;
import dev.spawnbotswrapper.inhabitants.combat.Perception.Params;
import dev.spawnbotswrapper.inhabitants.combat.Perception.Subject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure perception function against the golden vectors shared with the Minecraft-AI mod
 * ({@code docs/perception/vectors.json} in the repository root), plus the pieces on their own.
 */
class PerceptionTest {

    private static JsonObject vectors() throws IOException {
        // The tests run in the wrapper project directory; the file lives in the repository root, one level up.
        Path file = Path.of("..", "docs", "perception", "vectors.json");
        if (!Files.exists(file)) {
            file = Path.of("docs", "perception", "vectors.json");
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static double num(JsonObject o, String key, double dflt) {
        return o.has(key) ? o.get(key).getAsDouble() : dflt;
    }

    private static boolean bool(JsonObject o, String key, boolean dflt) {
        return o.has(key) ? o.get(key).getAsBoolean() : dflt;
    }

    private static Params params(JsonObject p) {
        return new Params(bool(p, "enabled", true), num(p, "frontHalfAngleDeg", 60), num(p, "peripheralHalfAngleDeg", 100),
                num(p, "peripheralFactor", 0.5), num(p, "sneakFactor", 0.5), num(p, "hearWalk", 4),
                num(p, "hearSprint", 8), num(p, "hearCombat", 12), num(p, "hearNoisyMob", 8),
                num(p, "hearPrimedCreeper", 16), num(p, "hearWarden", 24), num(p, "hearAnimal", 4),
                (int) num(p, "combatNoiseTicks", 10), (int) num(p, "awarenessTicks", 200));
    }

    private static Params withOverride(JsonObject base, JsonObject override) {
        JsonObject merged = base.deepCopy();
        for (var e : override.entrySet()) {
            merged.add(e.getKey(), e.getValue());
        }
        return params(merged);
    }

    private static Subject subject(JsonObject s) {
        return new Subject(bool(s, "sneaking", false), bool(s, "moving", false), bool(s, "sprinting", false),
                s.has("combatNoiseAge") ? s.get("combatNoiseAge").getAsInt() : Perception.NO_NOISE,
                MobKind.valueOf(s.has("mob") ? s.get("mob").getAsString() : "NONE"), num(s, "visibility", 1.0));
    }

    @Test
    void everyGoldenVectorMatches() throws IOException {
        JsonObject root = vectors();
        JsonObject base = root.getAsJsonObject("params");
        JsonArray cases = root.getAsJsonArray("cases");
        assertTrue(cases.size() >= 40, "the vector file lost cases: " + cases.size());
        for (JsonElement el : cases) {
            JsonObject c = el.getAsJsonObject();
            String name = c.get("name").getAsString();
            Params p = c.has("paramsOverride") ? withOverride(base, c.getAsJsonObject("paramsOverride")) : params(base);
            Notice got = Perception.notice(p, num(c, "baseRange", 10.0), c.get("angleDeg").getAsDouble(),
                    c.get("distance").getAsDouble(), subject(c.getAsJsonObject("subject")),
                    () -> bool(c, "occlusionClear", true));
            assertEquals(Notice.valueOf(c.get("expect").getAsString()), got, name);
        }
    }

    @Test
    void theShippedDefaultsAreTheVectorFileDefaults() throws IOException {
        assertEquals(Params.defaults(), params(vectors().getAsJsonObject("params")));
    }

    @Test
    void occlusionIsAskedLastAndAtMostOnce() {
        AtomicInteger asked = new AtomicInteger();
        Params p = Params.defaults();
        // Out of range, behind and silent, sneaking: the cheap filters say no, no ray is cast.
        assertEquals(Notice.NONE, Perception.notice(p, 10, 0, 12, Subject.player(false, false, false),
                () -> asked.incrementAndGet() > 0));
        assertEquals(Notice.NONE, Perception.notice(p, 10, 170, 2, Subject.player(true, true, false),
                () -> asked.incrementAndGet() > 0));
        assertEquals(0, asked.get());
        // Both sight and hearing hold: one ray decides.
        assertEquals(Notice.SIGHT, Perception.notice(p, 10, 0, 2, Subject.player(false, true, false),
                () -> asked.incrementAndGet() > 0));
        assertEquals(1, asked.get());
    }

    @Test
    void sightWinsOverHearingWhenBothApply() {
        assertEquals(Notice.SIGHT, Perception.notice(Params.defaults(), 10, 0, 3, Subject.player(false, true, false),
                () -> true));
    }

    @Test
    void theAngleIsTheThreeDimensionalAngleBetweenLookAndDirection() {
        assertEquals(0.0, Perception.angleDeg(0, 0, 1, 0, 0, 5), 1e-9);
        assertEquals(180.0, Perception.angleDeg(0, 0, 1, 0, 0, -5), 1e-9);
        assertEquals(90.0, Perception.angleDeg(0, 0, 1, 3, 0, 0), 1e-9);
        // Looking straight ahead, a subject directly above is 90 degrees off.
        assertEquals(90.0, Perception.angleDeg(0, 0, 1, 0, 4, 0), 1e-9);
        assertEquals(45.0, Perception.angleDeg(0, 0, 1, 0, 2, 2), 1e-9);
        assertEquals(0.0, Perception.angleDeg(0, 0, 0, 1, 1, 1), "no direction, no angle");
    }

    @Test
    void noiseIsTheMaxOfWhatApplies() {
        Params p = Params.defaults();
        assertEquals(0.0, Perception.noiseRadius(p, Subject.player(false, false, false)));
        assertEquals(0.0, Perception.noiseRadius(p, Subject.player(true, true, false)));
        assertEquals(4.0, Perception.noiseRadius(p, Subject.player(false, true, false)));
        assertEquals(8.0, Perception.noiseRadius(p, Subject.player(false, true, true)));
        assertEquals(12.0, Perception.noiseRadius(p, new Subject(true, true, false, 3, MobKind.NONE, 1.0)),
                "combat noise is heard even by a sneaking player");
        assertEquals(12.0, Perception.noiseRadius(p, new Subject(false, true, true, 0, MobKind.NONE, 1.0)));
        assertEquals(0.0, Perception.noiseRadius(p, new Subject(false, false, false, 10, MobKind.NONE, 1.0)));
    }

    @Test
    void theSneakFactorAndTheVisibilityMultiply() {
        Params p = Params.defaults();
        assertEquals(2.5, Perception.sightRange(p, 10, 0, new Subject(true, false, false, Perception.NO_NOISE,
                MobKind.NONE, 0.5)), 1e-9);
        assertEquals(2.5, Perception.sightRange(p, 10, 80, Subject.player(true, false, false)), 1e-9);
        assertEquals(0.0, Perception.sightRange(p, 10, 120, Subject.player(false, false, false)), 1e-9);
    }
}
