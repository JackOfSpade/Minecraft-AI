package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code aggro} block: defaults, round trip, the bounds, and old keys being ignored with one INFO note. */
class AggroConfigTest {

    @Test
    void defaultsAreTheAgreedRules(@TempDir Path dir) {
        Path f = dir.resolve("cfg.json");
        ConfigIO.load(f);
        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        assertTrue(again.notes().isEmpty(), again.notes().toString());
        InhabitantsConfig.Aggro a = again.config().aggro;
        assertTrue(a.enabled);
        assertTrue(a.requireLineOfSight);
        assertEquals(5, a.reactionTicks, "0.25 s");
        assertEquals(5.0, a.distanceReactionTicksPer32);
        assertEquals(10, a.loseGraceTicks, "0.5 s");
        assertEquals(200, a.searchTicks, "10 s");
        assertEquals(1.5, a.returnArriveDistance);
        assertEquals(40, a.stuckTicks);
        assertEquals(1200, a.returnMaxTicks, "60 s");
        assertEquals(3, a.scanIntervalTicks);
        String json = ConfigIO.toJson(again.config());
        for (String gone : new String[]{"acquireRange", "leashRange", "loseSightTicks", "returnToOrigin", "returnStuckTicks",
                "peripheralFactor", "sneakFactor"}) {
            assertFalse(json.contains("\"" + gone + "\""), gone + " no longer exists:\n" + json);
        }
        for (String key : new String[]{"reactionTicks", "distanceReactionTicksPer32", "loseGraceTicks", "searchTicks",
                "stuckTicks", "peripheralMultiplier", "sneakMultiplier"}) {
            assertTrue(json.contains("\"" + key + "\""), key + " is written:\n" + json);
        }
    }

    private static InhabitantsConfig.Aggro load(Path dir, String aggroJson) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": " + aggroJson + " }", StandardCharsets.UTF_8);
        return ConfigIO.load(f).config().aggro;
    }

    @Test
    void thereIsNoBlockDistanceAnywhereInTheBlock() {
        for (var field : InhabitantsConfig.Aggro.class.getFields()) {
            String n = field.getName().toLowerCase();
            assertFalse(n.contains("range") || n.contains("leash") || n.contains("radius"),
                    "no hard-coded block distance restriction: " + field.getName());
        }
    }

    @Test
    void tickCountsAreClamped(@TempDir Path dir) throws IOException {
        InhabitantsConfig.Aggro low = load(dir, "{ \"reactionTicks\": -3, \"loseGraceTicks\": 0, \"searchTicks\": 1, "
                + "\"stuckTicks\": 1, \"returnMaxTicks\": 1, \"scanIntervalTicks\": 0, \"distanceReactionTicksPer32\": -1 }");
        assertEquals(0, low.reactionTicks);
        assertEquals(1, low.loseGraceTicks);
        assertEquals(20, low.searchTicks);
        assertEquals(10, low.stuckTicks);
        assertEquals(20, low.returnMaxTicks);
        assertEquals(1, low.scanIntervalTicks);
        assertEquals(0.0, low.distanceReactionTicksPer32, "0 disables the distance term");
        InhabitantsConfig.Aggro high = load(dir, "{ \"reactionTicks\": 5000, \"loseGraceTicks\": 9999999, \"searchTicks\": 9999999, "
                + "\"stuckTicks\": 99999, \"returnMaxTicks\": 9999999, \"scanIntervalTicks\": 500 }");
        assertEquals(100, high.reactionTicks);
        assertEquals(72000, high.loseGraceTicks);
        assertEquals(72000, high.searchTicks);
        assertEquals(1200, high.stuckTicks);
        assertEquals(72000, high.returnMaxTicks);
        assertEquals(40, high.scanIntervalTicks);
    }

    @Test
    void theArriveDistanceIsKeptSane(@TempDir Path dir) throws IOException {
        assertEquals(0.5, load(dir, "{ \"returnArriveDistance\": 0 }").returnArriveDistance);
        assertEquals(16.0, load(dir, "{ \"returnArriveDistance\": 99 }").returnArriveDistance);
    }

    @Test
    void anOutOfRangeValueIsReportedAsAWarning(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": { \"searchTicks\": 3 } }", StandardCharsets.UTF_8);
        assertTrue(ConfigIO.load(f).warnings().stream().anyMatch(w -> w.contains("aggro.searchTicks")));
    }

    // ---------------------------------------------------------------- migration of the old rules

    @Test
    void theOldRangeLeashAndSightKeysAreIgnoredWithOneInfoNoteAndNeverFailTheLoad(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": { \"enabled\": true, \"acquireRange\": 10.0, \"leashRange\": 32.0, "
                + "\"loseSightTicks\": 200, \"returnToOrigin\": true, \"returnStuckTicks\": 200, \"returnMaxTicks\": 900, "
                + "\"perception\": { \"peripheralFactor\": 0.5, \"sneakFactor\": 0.5, \"hearWalk\": 5 } } }",
                StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertNull(r.fatalError());
        assertEquals(java.util.List.of(), r.warnings(), "old keys are not a warning");
        assertEquals(1, r.notes().size(), r.notes().toString());
        String note = r.notes().get(0);
        for (String key : new String[]{"aggro.acquireRange", "aggro.leashRange", "aggro.loseSightTicks",
                "aggro.returnToOrigin", "aggro.returnStuckTicks", "aggro.perception.peripheralFactor",
                "aggro.perception.sneakFactor"}) {
            assertTrue(note.contains(key), key + " named in: " + note);
        }
        // what still exists keeps its value; the old keys changed nothing
        assertEquals(900, r.config().aggro.returnMaxTicks);
        assertEquals(5.0, r.config().aggro.perception.hearWalk);
        assertEquals(10, r.config().aggro.loseGraceTicks, "the new grace is its own default, not the old 200 ticks");
        assertEquals(200, r.config().aggro.searchTicks);
        assertFalse(ConfigIO.toJson(r.config()).contains("acquireRange"), "and they are not written back");
    }

    @Test
    void aConfigWithoutOldKeysHasNoNote(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": { \"reactionTicks\": 6 } }", StandardCharsets.UTF_8);
        assertTrue(ConfigIO.load(f).notes().isEmpty());
    }

    @Test
    void anAggroBlockThatIsNotAnObjectDoesNotBreakTheNoteScan(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": null, \"rangedPacing\": 3 }", StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertNull(r.fatalError());
        assertTrue(r.notes().isEmpty());
    }

    // ---------------------------------------------------------------- perception block

    @Test
    void thePerceptionBlockDefaultsToTheSharedModel(@TempDir Path dir) {
        Path f = dir.resolve("cfg.json");
        ConfigIO.load(f);
        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        InhabitantsConfig.AggroPerception p = again.config().aggro.perception;
        assertTrue(p.enabled);
        assertEquals(60.0, p.frontHalfAngleDeg);
        assertEquals(100.0, p.peripheralHalfAngleDeg);
        assertEquals(2.0, p.peripheralMultiplier);
        assertEquals(2.0, p.sneakMultiplier);
        assertEquals(4.0, p.hearWalk);
        assertEquals(8.0, p.hearSprint);
        assertEquals(12.0, p.hearCombat);
        assertEquals(10, p.combatNoiseTicks);
        assertTrue(ConfigIO.toJson(again.config()).contains("\"perception\""));
    }

    @Test
    void thePerceptionBlockCanBeSwitchedOffAndTuned(@TempDir Path dir) throws IOException {
        InhabitantsConfig.AggroPerception p = load(dir,
                "{ \"perception\": { \"enabled\": false, \"sneakMultiplier\": 3, \"hearWalk\": 6 } }").perception;
        assertFalse(p.enabled);
        assertEquals(3.0, p.sneakMultiplier);
        assertEquals(6.0, p.hearWalk);
        assertEquals(60.0, p.frontHalfAngleDeg, "keys left out keep their defaults");
    }

    @Test
    void thePerceptionBoundsAreKeptCoherent(@TempDir Path dir) throws IOException {
        InhabitantsConfig.AggroPerception p = load(dir, "{ \"perception\": { \"frontHalfAngleDeg\": 250, "
                + "\"peripheralHalfAngleDeg\": 30, \"peripheralMultiplier\": 0.2, \"sneakMultiplier\": 99, "
                + "\"hearWalk\": -2, \"hearSprint\": 999, \"hearCombat\": -1, \"combatNoiseTicks\": -5 } }").perception;
        assertEquals(180.0, p.frontHalfAngleDeg);
        assertEquals(180.0, p.peripheralHalfAngleDeg, "the field is never narrower than the front cone");
        assertEquals(1.0, p.peripheralMultiplier, "the periphery is never faster than the front");
        assertEquals(20.0, p.sneakMultiplier);
        assertEquals(0.0, p.hearWalk);
        assertEquals(128.0, p.hearSprint);
        assertEquals(0.0, p.hearCombat);
        assertEquals(0, p.combatNoiseTicks);
        InhabitantsConfig.AggroPerception q = load(dir,
                "{ \"perception\": { \"frontHalfAngleDeg\": 80, \"peripheralHalfAngleDeg\": 50 } }").perception;
        assertEquals(80.0, q.frontHalfAngleDeg);
        assertEquals(80.0, q.peripheralHalfAngleDeg);
    }

    @Test
    void aMissingPerceptionBlockIsFilledIn(@TempDir Path dir) throws IOException {
        InhabitantsConfig.Aggro a = load(dir, "{ \"perception\": null }");
        assertTrue(a.perception != null && a.perception.enabled);
    }
}
