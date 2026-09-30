package dev.spawnbotswrapper.inhabitants.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.spawnbotswrapper.inhabitants.combat.Perception.Params;
import dev.spawnbotswrapper.inhabitants.combat.Perception.Reading;
import dev.spawnbotswrapper.inhabitants.combat.Perception.Sense;
import dev.spawnbotswrapper.inhabitants.combat.Perception.Subject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure perception function against the golden vectors shared with the Minecraft-AI mod
 * ({@code docs/perception/vectors.json} in the repository root), plus the pieces on their own: the continuous reaction
 * formula (0.5 s close, 2.0 s at 64 blocks, a different number at every distance), the linear angle factor, sneaking,
 * hearing as an input, and the exposure runs.
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
        return new Params(bool(p, "enabled", true), num(p, "reactionBaseSeconds", 0.5), num(p, "reactionAt64Seconds", 2.0),
                num(p, "fullAttentionHalfAngleDeg", 30), num(p, "peripheralHalfAngleDeg", 100),
                num(p, "peripheralMultiplier", 2), num(p, "sneakMultiplier", 2));
    }

    private static Params withOverride(JsonObject base, JsonObject override) {
        JsonObject merged = base.deepCopy();
        for (var e : override.entrySet()) {
            merged.add(e.getKey(), e.getValue());
        }
        return params(merged);
    }

    private static Subject subject(JsonObject s) {
        return new Subject(bool(s, "sneaking", false), num(s, "visibility", 1.0));
    }

    @Test
    void everyGoldenVectorMatches() throws IOException {
        JsonObject root = vectors();
        JsonObject base = root.getAsJsonObject("params");
        JsonArray cases = root.getAsJsonArray("cases");
        assertTrue(cases.size() >= 30, "the vector file lost cases: " + cases.size());
        for (JsonElement el : cases) {
            JsonObject c = el.getAsJsonObject();
            String name = c.get("name").getAsString();
            Params p = c.has("paramsOverride") ? withOverride(base, c.getAsJsonObject("paramsOverride")) : params(base);
            Reading got = Perception.read(p, c.get("angleDeg").getAsDouble(), c.get("distance").getAsDouble(),
                    subject(c.getAsJsonObject("subject")), bool(c, "heardNear", false), () -> bool(c, "occlusionClear", true));
            assertEquals(Sense.valueOf(c.get("expectSense").getAsString()), got.sense(), name);
            JsonElement seconds = c.get("expectSeconds");
            if (seconds.isJsonPrimitive() && seconds.getAsJsonPrimitive().isString()) {
                assertEquals("never", seconds.getAsString(), name);
                assertEquals(Perception.NEVER, got.requiredSeconds(), name);
            } else {
                assertEquals(seconds.getAsDouble(), got.requiredSeconds(), 1e-9, name);
            }
            assertEquals(c.get("expectNoticeTick").getAsLong(), Perception.noticeTick(got.requiredSeconds()), name);
        }
    }

    @Test
    void everyGoldenExposureRunMatches() throws IOException {
        JsonArray runs = vectors().getAsJsonArray("runs");
        assertTrue(runs.size() >= 5, "the vector file lost its runs");
        for (JsonElement el : runs) {
            JsonObject r = el.getAsJsonObject();
            String name = r.get("name").getAsString();
            JsonElement req = r.get("requiredSeconds");
            double required = req.isJsonPrimitive() && req.getAsJsonPrimitive().isString() ? Perception.NEVER : req.getAsDouble();
            String sighted = r.get("sighted").getAsString();
            ExposureTracker tracker = new ExposureTracker();
            int noticed = -1;
            for (int i = 0; i < sighted.length() && noticed < 0; i++) {
                if (sighted.charAt(i) == '1') {
                    long ticks = tracker.sighted("k", i);
                    if (Perception.noticed(Perception.exposureSeconds(ticks), required)) {
                        noticed = i;
                    }
                } else {
                    tracker.missed("k", i);
                }
            }
            assertEquals(r.get("expectNoticeIndex").getAsInt(), noticed, name);
        }
    }

    @Test
    void theShippedDefaultsAreTheVectorFileDefaults() throws IOException {
        assertEquals(Params.defaults(), params(vectors().getAsJsonObject("params")));
    }

    private static final Subject PLAIN = Subject.player(false);

    @Test
    void theReactionTimeIsOneContinuousFormula() {
        Params p = Params.defaults();
        assertEquals(0.5, Perception.requiredSeconds(p, 0, 0, PLAIN, true), 1e-12, "0.5 s up close");
        assertEquals(2.0, Perception.requiredSeconds(p, 0, 64, PLAIN, true), 1e-12, "2.0 s at 64 blocks");
        // every distance has its own number: no steps, no rounding
        double a = Perception.requiredSeconds(p, 0, 10.3, PLAIN, true);
        double b = Perception.requiredSeconds(p, 0, 10.4, PLAIN, true);
        assertEquals(0.74140625, a, 1e-12);
        assertTrue(b > a, "monotone in the distance");
        assertEquals(1.5 * 0.1 / 64.0, b - a, 1e-12, "linear in the distance");
        // the angle factor: 1 up to 30 degrees, linear to 2 at 100 degrees, never beyond
        assertEquals(0.5, Perception.requiredSeconds(p, 30, 0, PLAIN, true), 1e-12);
        assertEquals(0.75, Perception.requiredSeconds(p, 65, 0, PLAIN, true), 1e-12);
        assertEquals(1.0, Perception.requiredSeconds(p, 100, 0, PLAIN, true), 1e-12);
        assertEquals(Perception.NEVER, Perception.requiredSeconds(p, 100.01, 0, PLAIN, true));
        assertEquals(0.75, Perception.requiredSeconds(p, -65, 0, PLAIN, true), 1e-12, "either side");
        // sneaking doubles it (unless the reaction is to pain), visibility divides it
        assertEquals(1.0, Perception.requiredSeconds(p, 0, 0, Subject.player(true), true), 1e-12);
        assertEquals(0.5, Perception.requiredSeconds(p, 0, 0, Subject.player(true), false), 1e-12, "pain ignores sneaking");
        assertEquals(1.0, Perception.requiredSeconds(p, 0, 0, new Subject(false, 0.5), true), 1e-12);
        assertEquals(Perception.NEVER, Perception.requiredSeconds(p, 0, 0, new Subject(false, 0.0), true), "invisible: never");
    }

    @Test
    void theNoticeHappensOnTheFirstTickThatReachesTheSeconds() {
        assertEquals(10, Perception.noticeTick(0.5));
        assertEquals(15, Perception.noticeTick(0.734375));
        assertEquals(0, Perception.noticeTick(0.0));
        assertEquals(-1, Perception.noticeTick(Perception.NEVER));
        assertFalse(Perception.noticed(Perception.exposureSeconds(9), 0.5));
        assertTrue(Perception.noticed(Perception.exposureSeconds(10), 0.5));
        assertFalse(Perception.noticed(Perception.exposureSeconds(14), 0.734375));
        assertTrue(Perception.noticed(Perception.exposureSeconds(15), 0.734375));
        assertFalse(Perception.noticed(1000.0, Perception.NEVER));
        assertTrue(Perception.noticed(0.0, 0.0), "perception off: at once");
    }

    @Test
    void occlusionIsAskedLastAndAtMostOnce() {
        AtomicInteger asked = new AtomicInteger();
        Params p = Params.defaults();
        // Behind and silent, or invisible: the cheap filters say no, no ray is cast.
        assertEquals(Sense.NONE, Perception.read(p, 170, 2, Subject.player(true), false,
                () -> asked.incrementAndGet() > 0).sense());
        assertEquals(Sense.NONE, Perception.read(p, 0, 2, new Subject(false, 0.0), false,
                () -> asked.incrementAndGet() > 0).sense());
        assertEquals(0, asked.get());
        // In view: one ray decides.
        assertEquals(Sense.SIGHT, Perception.read(p, 0, 2, PLAIN, false, () -> asked.incrementAndGet() > 0).sense());
        assertEquals(1, asked.get());
        // Heard from behind: one ray decides.
        assertEquals(Sense.HEARING, Perception.read(p, 170, 2, PLAIN, true, () -> asked.incrementAndGet() > 0).sense());
        assertEquals(2, asked.get());
    }

    @Test
    void perceptionOffIsExactlyVanillaHasLineOfSight() {
        Params off = Params.defaults().disabled();
        AtomicInteger asked = new AtomicInteger();
        // behind, sneaking, invisible, far: all seen at once as long as the line is clear
        Reading r = Perception.read(off, 179, 100, new Subject(true, 0.0), false, () -> {
            asked.incrementAndGet();
            return true;
        });
        assertEquals(Sense.SIGHT, r.sense());
        assertEquals(0.0, r.requiredSeconds(), "no reaction time");
        assertEquals(1, asked.get());
        assertEquals(Sense.NONE, Perception.read(off, 0, 5, PLAIN, false, () -> false).sense());
    }

    @Test
    void hearingIsAnInputThatOnlyRemovesTheViewConeRequirement() {
        Params p = Params.defaults();
        // heard behind with a clear line: sighted at angle factor 1
        Reading behind = Perception.read(p, 150, 6, PLAIN, true, () -> true);
        assertEquals(Sense.HEARING, behind.sense());
        assertEquals(Perception.requiredSeconds(p, 0, 6, PLAIN, true), behind.requiredSeconds(), 1e-12);
        // heard but the line is blocked: not a notice
        assertEquals(Sense.NONE, Perception.read(p, 150, 6, PLAIN, true, () -> false).sense());
        // nothing heard and behind: nothing, at any distance
        assertEquals(Sense.NONE, Perception.read(p, 170, 1, PLAIN, false, () -> true).sense());
        // hearing never restricts sight: in front and not heard is plainly seen at any distance
        assertEquals(Sense.SIGHT, Perception.read(p, 0, 90, PLAIN, false, () -> true).sense());
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
}
