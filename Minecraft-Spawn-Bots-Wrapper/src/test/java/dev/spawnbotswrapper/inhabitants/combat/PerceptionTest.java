package dev.spawnbotswrapper.inhabitants.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.spawnbotswrapper.inhabitants.combat.Perception.MobKind;
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
 * ({@code docs/perception/vectors.json} in the repository root), plus the pieces on their own: the reaction-time table
 * (front 5 ticks, peripheral 10, sneaking 10, invisible never, 64 blocks 15), hearing from behind, occluded sound.
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
                num(p, "peripheralMultiplier", 2), num(p, "sneakMultiplier", 2), num(p, "reactionTicks", 5),
                num(p, "distanceTicksPer32", 5), num(p, "hearWalk", 4), num(p, "hearSprint", 8), num(p, "hearCombat", 12),
                num(p, "hearNoisyMob", 8), num(p, "hearPrimedCreeper", 16), num(p, "hearWarden", 24),
                num(p, "hearAnimal", 4), (int) num(p, "combatNoiseTicks", 10));
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
        assertTrue(cases.size() >= 45, "the vector file lost cases: " + cases.size());
        for (JsonElement el : cases) {
            JsonObject c = el.getAsJsonObject();
            String name = c.get("name").getAsString();
            Params p = c.has("paramsOverride") ? withOverride(base, c.getAsJsonObject("paramsOverride")) : params(base);
            Reading got = Perception.read(p, num(c, "modMax", num(root, "modMax", 128)), c.get("angleDeg").getAsDouble(),
                    c.get("distance").getAsDouble(), subject(c.getAsJsonObject("subject")),
                    () -> bool(c, "occlusionClear", true));
            assertEquals(Sense.valueOf(c.get("expectSense").getAsString()), got.sense(), name);
            JsonElement ticks = c.get("expectTicks");
            if (ticks.isJsonPrimitive() && ticks.getAsJsonPrimitive().isString()) {
                assertEquals("never", ticks.getAsString(), name);
                assertEquals(Perception.NEVER, got.requiredTicks(), name);
            } else {
                assertEquals(ticks.getAsDouble(), got.requiredTicks(), 1e-9, name);
            }
        }
    }

    @Test
    void theShippedDefaultsAreTheVectorFileDefaults() throws IOException {
        assertEquals(Params.defaults(), params(vectors().getAsJsonObject("params")));
    }

    private static final Params NO_DISTANCE_TERM = withoutDistance(Params.defaults());

    private static Params withoutDistance(Params d) {
        return new Params(d.enabled(), d.frontHalfAngleDeg(), d.peripheralHalfAngleDeg(), d.peripheralMultiplier(),
                d.sneakMultiplier(), d.reactionTicks(), 0.0, d.hearWalk(), d.hearSprint(), d.hearCombat(),
                d.hearNoisyMob(), d.hearPrimedCreeper(), d.hearWarden(), d.hearAnimal(), d.combatNoiseTicks());
    }

    @Test
    void theReactionTimeTable() {
        Params p = NO_DISTANCE_TERM;
        Subject plain = Subject.player(false, false, false);
        assertEquals(5.0, Perception.sightTicks(p, 0, 10, plain), 1e-9, "front: 0.25 s");
        assertEquals(10.0, Perception.sightTicks(p, 80, 10, plain), 1e-9, "peripheral: twice as long");
        assertEquals(10.0, Perception.sightTicks(p, 0, 10, Subject.player(true, false, false)), 1e-9, "sneaking front");
        assertEquals(Perception.NEVER, Perception.sightTicks(p, 0, 10,
                new Subject(false, false, false, Perception.NO_NOISE, MobKind.NONE, 0.0)), "invisible: never");
        assertEquals(Perception.NEVER, Perception.sightTicks(p, 120, 3, plain), "behind: never");
        // far: 5 extra ticks per 32 blocks (a soft scaling): 64 blocks is 15
        assertEquals(15.0, Perception.sightTicks(Params.defaults(), 0, 64, plain), 1e-9);
        assertEquals(11.25, Perception.sightTicks(Params.defaults(), 0, 40, plain), 1e-9);
    }

    @Test
    void theDistanceTermIsSoftAndZeroDisablesIt() {
        Subject plain = Subject.player(false, false, false);
        assertEquals(5.0, Perception.sightTicks(NO_DISTANCE_TERM, 0, 128, plain), 1e-9);
        assertEquals(25.0, Perception.sightTicks(Params.defaults(), 0, 128, plain), 1e-9);
    }

    @Test
    void nothingIsSightedBeyondTheModMaximumAndNothingIsHeardThere() {
        Params p = Params.defaults();
        assertEquals(Sense.SIGHT, Perception.read(p, 128, 0, 128, Subject.player(false, false, false), () -> true).sense());
        assertEquals(Sense.NONE, Perception.read(p, 128, 0, 128.1, Subject.player(false, false, false), () -> true).sense());
        assertEquals(Sense.NONE, Perception.read(p.disabled(), 128, 0, 129, Subject.player(false, false, false),
                () -> true).sense(), "vanilla hasLineOfSight has the same cap");
    }

    @Test
    void occlusionIsAskedLastAndAtMostOnce() {
        AtomicInteger asked = new AtomicInteger();
        Params p = Params.defaults();
        // Beyond the mod maximum, behind and silent, invisible: the cheap filters say no, no ray is cast.
        assertEquals(Sense.NONE, Perception.read(p, 128, 0, 130, Subject.player(false, false, false),
                () -> asked.incrementAndGet() > 0).sense());
        assertEquals(Sense.NONE, Perception.read(p, 128, 170, 2, Subject.player(true, true, false),
                () -> asked.incrementAndGet() > 0).sense());
        assertEquals(Sense.NONE, Perception.read(p, 128, 0, 2,
                new Subject(false, false, false, Perception.NO_NOISE, MobKind.NONE, 0.0),
                () -> asked.incrementAndGet() > 0).sense());
        assertEquals(0, asked.get());
        // Both sight and hearing hold: one ray decides.
        assertEquals(Sense.SIGHT, Perception.read(p, 128, 0, 2, Subject.player(false, true, false),
                () -> asked.incrementAndGet() > 0).sense());
        assertEquals(1, asked.get());
    }

    @Test
    void perceptionOffIsExactlyVanillaHasLineOfSight() {
        Params off = Params.defaults().disabled();
        AtomicInteger asked = new AtomicInteger();
        // behind, sneaking, invisible, far: all seen at once as long as the line is clear and within the maximum
        Reading r = Perception.read(off, 128, 179, 100,
                new Subject(true, false, false, Perception.NO_NOISE, MobKind.NONE, 0.0), () -> {
                    asked.incrementAndGet();
                    return true;
                });
        assertEquals(Sense.SIGHT, r.sense());
        assertEquals(0.0, r.requiredTicks(), "no reaction time");
        assertEquals(1, asked.get());
        assertEquals(Sense.NONE, Perception.read(off, 128, 0, 5, Subject.player(false, false, false), () -> false).sense());
    }

    @Test
    void anOccludedSoundIsOnlyAHintAndNeverANotice() {
        Reading r = Perception.read(Params.defaults(), 128, 150, 3, Subject.player(false, true, false), () -> false);
        assertEquals(Sense.INVESTIGATE, r.sense());
        assertFalse(r.exposed());
        assertFalse(Perception.noticed(1000, r.requiredTicks()), "no amount of time turns a hint into a notice");
    }

    @Test
    void aNoticeNeedsTheFullExposure() {
        assertFalse(Perception.noticed(4, 5.0));
        assertTrue(Perception.noticed(5, 5.0));
        assertFalse(Perception.noticed(11, 11.25));
        assertTrue(Perception.noticed(12, 11.25));
        assertFalse(Perception.noticed(100, Perception.NEVER));
        assertTrue(Perception.noticed(0, 0.0), "perception off: at once");
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
    void hearingOnlyAddsAwarenessItNeverRestrictsSight() {
        // A walking player 30 blocks away in front is out of earshot but still seen: the noise radius is no sight limit.
        Reading far = Perception.read(Params.defaults(), 128, 0, 30, Subject.player(false, true, false), () -> true);
        assertEquals(Sense.SIGHT, far.sense());
        // Silent and behind: not noticed, at any distance.
        assertEquals(Sense.NONE, Perception.read(Params.defaults(), 128, 170, 1, Subject.player(false, false, false),
                () -> true).sense());
    }
}
