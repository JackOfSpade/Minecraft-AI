package io.github.zoyluo.minecraftai.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.perception.CreaturePerception.Params;
import io.github.zoyluo.minecraftai.perception.CreaturePerception.Reading;
import io.github.zoyluo.minecraftai.perception.CreaturePerception.Sense;
import io.github.zoyluo.minecraftai.perception.CreaturePerception.Subject;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The pure perception function against the golden vectors shared with the PvP BOT wrapper ({@code docs/perception/vectors.json} in the
 * repository root): every case must give the same sense, the same required seconds and the same notice tick as the wrapper's
 * {@code PerceptionTest}. Plus the pieces on their own: the continuous reaction formula, the linear angle factor, sneaking, hearing as
 * an input, lazy occlusion, and the exposure runs (one missed tick tolerated, every re-sighting restarts).
 */
class CreaturePerceptionTest {

    private static JsonObject vectors() throws IOException {
        Path file = Path.of("docs", "perception", "vectors.json");
        if (!Files.exists(file)) {
            file = Path.of("..", "docs", "perception", "vectors.json");
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
            Reading got = CreaturePerception.read(p, c.get("angleDeg").getAsDouble(), c.get("distance").getAsDouble(),
                    subject(c.getAsJsonObject("subject")), bool(c, "heardNear", false), () -> bool(c, "occlusionClear", true));
            assertEquals(Sense.valueOf(c.get("expectSense").getAsString()), got.sense(), name);
            JsonElement seconds = c.get("expectSeconds");
            if (seconds.isJsonPrimitive() && seconds.getAsJsonPrimitive().isString()) {
                assertEquals("never", seconds.getAsString(), name);
                assertEquals(CreaturePerception.NEVER, got.requiredSeconds(), name);
            } else {
                assertEquals(seconds.getAsDouble(), got.requiredSeconds(), 1e-9, name);
            }
            assertEquals(c.get("expectNoticeTick").getAsLong(), CreaturePerception.noticeTick(got.requiredSeconds()), name);
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
            double required = req.isJsonPrimitive() && req.getAsJsonPrimitive().isString()
                    ? CreaturePerception.NEVER : req.getAsDouble();
            String sighted = r.get("sighted").getAsString();
            ExposureTracker<String> tracker = new ExposureTracker<>();
            int noticed = -1;
            for (int i = 0; i < sighted.length() && noticed < 0; i++) {
                if (sighted.charAt(i) == '1') {
                    long ticks = tracker.sighted("k", i);
                    if (CreaturePerception.noticed(CreaturePerception.exposureSeconds(ticks), required)) {
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

    private static final Subject PLAIN = Subject.of(false);

    @Test
    void theReactionTimeIsOneContinuousFormula() {
        Params p = Params.defaults();
        assertEquals(0.5, CreaturePerception.requiredSeconds(p, 0, 0, PLAIN, true), 1e-12, "0.5 s up close");
        assertEquals(2.0, CreaturePerception.requiredSeconds(p, 0, 64, PLAIN, true), 1e-12, "2.0 s at 64 blocks");
        assertEquals(0.734375, CreaturePerception.requiredSeconds(p, 0, 10, PLAIN, true), 1e-12, "the ~0.73 s at 10 blocks");
        double a = CreaturePerception.requiredSeconds(p, 0, 10.3, PLAIN, true);
        double b = CreaturePerception.requiredSeconds(p, 0, 10.4, PLAIN, true);
        assertEquals(0.74140625, a, 1e-12);
        assertTrue(b > a, "monotone in the distance");
        assertEquals(1.5 * 0.1 / 64.0, b - a, 1e-12, "linear in the distance");
        assertEquals(0.5, CreaturePerception.requiredSeconds(p, 30, 0, PLAIN, true), 1e-12);
        assertEquals(0.75, CreaturePerception.requiredSeconds(p, 65, 0, PLAIN, true), 1e-12);
        assertEquals(1.0, CreaturePerception.requiredSeconds(p, 100, 0, PLAIN, true), 1e-12);
        assertEquals(CreaturePerception.NEVER, CreaturePerception.requiredSeconds(p, 100.01, 0, PLAIN, true));
        assertEquals(0.75, CreaturePerception.requiredSeconds(p, -65, 0, PLAIN, true), 1e-12, "either side");
        assertEquals(1.0, CreaturePerception.requiredSeconds(p, 0, 0, Subject.of(true), true), 1e-12);
        assertEquals(0.5, CreaturePerception.requiredSeconds(p, 0, 0, Subject.of(true), false), 1e-12, "pain ignores sneaking");
        assertEquals(1.0, CreaturePerception.requiredSeconds(p, 0, 0, new Subject(false, 0.5), true), 1e-12);
        assertEquals(CreaturePerception.NEVER,
                CreaturePerception.requiredSeconds(p, 0, 0, new Subject(false, 0.0), true), "invisible: never");
    }

    @Test
    void theNoticeHappensOnTheFirstTickThatReachesTheSeconds() {
        assertEquals(10, CreaturePerception.noticeTick(0.5));
        assertEquals(15, CreaturePerception.noticeTick(0.734375));
        assertEquals(0, CreaturePerception.noticeTick(0.0));
        assertEquals(-1, CreaturePerception.noticeTick(CreaturePerception.NEVER));
        assertFalse(CreaturePerception.noticed(CreaturePerception.exposureSeconds(14), 0.734375));
        assertTrue(CreaturePerception.noticed(CreaturePerception.exposureSeconds(15), 0.734375));
        assertFalse(CreaturePerception.noticed(1000.0, CreaturePerception.NEVER));
        assertTrue(CreaturePerception.noticed(0.0, 0.0), "perception off: at once");
    }

    @Test
    void occlusionIsAskedLastAndAtMostOnce() {
        AtomicInteger asked = new AtomicInteger();
        Params p = Params.defaults();
        assertEquals(Sense.NONE, CreaturePerception.read(p, 170, 2, Subject.of(true), false,
                () -> asked.incrementAndGet() > 0).sense());
        assertEquals(Sense.NONE, CreaturePerception.read(p, 0, 2, new Subject(false, 0.0), false,
                () -> asked.incrementAndGet() > 0).sense());
        assertEquals(0, asked.get(), "behind and silent, or invisible: no ray is cast");
        assertEquals(Sense.SIGHT, CreaturePerception.read(p, 0, 2, PLAIN, false, () -> asked.incrementAndGet() > 0).sense());
        assertEquals(1, asked.get());
        assertEquals(Sense.HEARING, CreaturePerception.read(p, 170, 2, PLAIN, true, () -> asked.incrementAndGet() > 0).sense());
        assertEquals(2, asked.get());
    }

    @Test
    void perceptionOffIsTodaysOmnidirectionalLineOfSight() {
        Params off = Params.defaults().disabled();
        Reading r = CreaturePerception.read(off, 179, 100, new Subject(true, 0.0), false, () -> true);
        assertEquals(Sense.SIGHT, r.sense());
        assertEquals(0.0, r.requiredSeconds(), "no reaction time");
        assertEquals(Sense.NONE, CreaturePerception.read(off, 0, 5, PLAIN, false, () -> false).sense());
    }

    @Test
    void hearingRemovesTheConeRequirementButNeverTheLine() {
        Params p = Params.defaults();
        Reading behind = CreaturePerception.read(p, 150, 6, PLAIN, true, () -> true);
        assertEquals(Sense.HEARING, behind.sense());
        assertEquals(CreaturePerception.requiredSeconds(p, 0, 6, PLAIN, true), behind.requiredSeconds(), 1e-12,
                "angle factor 1 when heard");
        assertEquals(Sense.NONE, CreaturePerception.read(p, 150, 6, PLAIN, true, () -> false).sense(),
                "heard through a wall: an investigate hint, never a notice");
        assertEquals(Sense.NONE, CreaturePerception.read(p, 150, 1, PLAIN, false, () -> true).sense(),
                "silent and behind: never noticed, however close");
    }

    @Test
    void everyResightingRestartsTheExposure() {
        ExposureTracker<String> tracker = new ExposureTracker<>();
        for (int tick = 0; tick < 12; tick++) {
            assertEquals(tick, tracker.sighted("zombie", tick));
        }
        // two missed ticks in a row: the run is over
        tracker.missed("zombie", 12);
        tracker.missed("zombie", 13);
        assertEquals(0, tracker.sighted("zombie", 14), "the re-sighting starts from zero");
        // one missed tick is tolerated
        assertEquals(2, tracker.sighted("zombie", 16));
        assertTrue(tracker.inProgress("zombie", 17));
        tracker.forget("zombie");
        assertEquals(0, tracker.sighted("zombie", 18));
        tracker.retain(java.util.Set.of());
        assertEquals(0, tracker.size());
    }
}
