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
        assertEquals(10, a.loseGraceTicks, "0.5 s");
        assertEquals(200, a.searchTicks, "10 s");
        assertEquals(1.5, a.returnArriveDistance);
        assertEquals(40, a.stuckTicks);
        assertEquals(1200, a.returnMaxTicks, "60 s");
        assertEquals(3, a.scanIntervalTicks);
        assertEquals(16, a.hearing.listenerRadius, "the Warden's vanilla listener radius");
        String json = ConfigIO.toJson(again.config());
        for (String gone : new String[]{"acquireRange", "leashRange", "loseSightTicks", "returnToOrigin", "returnStuckTicks",
                "peripheralFactor", "sneakFactor", "reactionTicks", "distanceReactionTicksPer32", "frontHalfAngleDeg",
                "hearWalk", "hearSprint", "hearCombat", "combatNoiseTicks"}) {
            assertFalse(json.contains("\"" + gone + "\""), gone + " no longer exists:\n" + json);
        }
        for (String key : new String[]{"reactionBaseSeconds", "reactionAt64Seconds", "fullAttentionHalfAngleDeg",
                "peripheralHalfAngleDeg", "loseGraceTicks", "searchTicks", "stuckTicks", "peripheralMultiplier",
                "sneakMultiplier", "listenerRadius"}) {
            assertTrue(json.contains("\"" + key + "\""), key + " is written:\n" + json);
        }
    }

    private static InhabitantsConfig.Aggro load(Path dir, String aggroJson) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": " + aggroJson + " }", StandardCharsets.UTF_8);
        return ConfigIO.load(f).config().aggro;
    }

    @Test
    void thereIsNoEngageDistanceKeyOnlyAConstant() {
        for (var field : InhabitantsConfig.Aggro.class.getFields()) {
            String n = field.getName().toLowerCase();
            assertFalse(n.contains("range") || n.contains("leash") || n.contains("radius") || n.contains("engage"),
                    "the 16 block engage limit is a constant, not a setting: " + field.getName());
        }
    }

    @Test
    void tickCountsAreClamped(@TempDir Path dir) throws IOException {
        InhabitantsConfig.Aggro low = load(dir, "{ \"loseGraceTicks\": 0, \"searchTicks\": 1, "
                + "\"stuckTicks\": 1, \"returnMaxTicks\": 1, \"scanIntervalTicks\": 0 }");
        assertEquals(1, low.loseGraceTicks);
        assertEquals(20, low.searchTicks);
        assertEquals(10, low.stuckTicks);
        assertEquals(20, low.returnMaxTicks);
        assertEquals(1, low.scanIntervalTicks);
        InhabitantsConfig.Aggro high = load(dir, "{ \"loseGraceTicks\": 9999999, \"searchTicks\": 9999999, "
                + "\"stuckTicks\": 99999, \"returnMaxTicks\": 9999999, \"scanIntervalTicks\": 500 }");
        assertEquals(72000, high.loseGraceTicks);
        assertEquals(72000, high.searchTicks);
        assertEquals(1200, high.stuckTicks);
        assertEquals(72000, high.returnMaxTicks);
        assertEquals(40, high.scanIntervalTicks);
    }

    @Test
    void theHumanAimDefaultsAreAFastFlickAndAreWritten(@TempDir Path dir) {
        Path f = dir.resolve("cfg.json");
        ConfigIO.load(f);
        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        InhabitantsConfig.AggroAim m = again.config().aggro.aim;
        assertTrue(m.enabled);
        assertEquals(540.0, m.maxTurnDegPerSec, "a fast human flick: 27 degrees per tick");
        assertEquals(1.5, m.fireToleranceDeg);
        assertEquals(0.25, m.fireTargetRadius);
        assertEquals(0.3, m.jitterBaseDeg);
        assertEquals(2.5, m.jitterSettleDeg);
        assertEquals(0.25, m.jitterSettleSeconds);
        String json = ConfigIO.toJson(again.config());
        for (String key : new String[]{"maxTurnDegPerSec", "fireToleranceDeg", "fireTargetRadius", "jitterBaseDeg",
                "jitterSettleDeg", "jitterSettleSeconds"}) {
            assertTrue(json.contains("\"" + key + "\""), key + " is written: " + json);
        }
    }

    @Test
    void theHumanAimValuesAreValidated(@TempDir Path dir) throws IOException {
        InhabitantsConfig.Aggro low = load(dir, "{ \"aim\": { \"maxTurnDegPerSec\": 1, \"fireToleranceDeg\": 0, \"fireTargetRadius\": 0, "
                + "\"jitterBaseDeg\": -1, \"jitterSettleDeg\": -1, \"jitterSettleSeconds\": 0 } }");
        assertEquals(30.0, low.aim.maxTurnDegPerSec);
        assertEquals(0.1, low.aim.fireToleranceDeg);
        assertEquals(0.05, low.aim.fireTargetRadius);
        assertEquals(0.0, low.aim.jitterBaseDeg);
        assertEquals(0.0, low.aim.jitterSettleDeg);
        assertEquals(0.01, low.aim.jitterSettleSeconds);
        InhabitantsConfig.Aggro high = load(dir, "{ \"aim\": { \"maxTurnDegPerSec\": 99999, \"fireToleranceDeg\": 99, "
                + "\"fireTargetRadius\": 99, \"jitterBaseDeg\": 99, \"jitterSettleDeg\": 99, \"jitterSettleSeconds\": 99 } }");
        assertEquals(3600.0, high.aim.maxTurnDegPerSec);
        assertEquals(10.0, high.aim.fireToleranceDeg);
        assertEquals(1.0, high.aim.fireTargetRadius);
        assertEquals(5.0, high.aim.jitterBaseDeg);
        assertEquals(15.0, high.aim.jitterSettleDeg);
        assertEquals(5.0, high.aim.jitterSettleSeconds);
        InhabitantsConfig.Aggro off = load(dir, "{ \"aim\": { \"enabled\": false } }");
        assertFalse(off.aim.enabled);
        assertEquals(540.0, off.aim.maxTurnDegPerSec, "the rest keeps its defaults");
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
                + "\"reactionTicks\": 5, \"distanceReactionTicksPer32\": 5, "
                + "\"perception\": { \"peripheralFactor\": 0.5, \"sneakFactor\": 0.5, \"hearWalk\": 5, "
                + "\"hearSprint\": 8, \"hearCombat\": 12, \"combatNoiseTicks\": 10, \"frontHalfAngleDeg\": 60 } } }",
                StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertNull(r.fatalError());
        assertEquals(java.util.List.of(), r.warnings(), "old keys are not a warning");
        assertEquals(1, r.notes().size(), r.notes().toString());
        String note = r.notes().get(0);
        for (String key : new String[]{"aggro.acquireRange", "aggro.leashRange", "aggro.loseSightTicks",
                "aggro.returnToOrigin", "aggro.returnStuckTicks", "aggro.reactionTicks", "aggro.distanceReactionTicksPer32",
                "aggro.perception.peripheralFactor", "aggro.perception.sneakFactor", "aggro.perception.hearWalk",
                "aggro.perception.hearSprint", "aggro.perception.hearCombat", "aggro.perception.combatNoiseTicks",
                "aggro.perception.frontHalfAngleDeg"}) {
            assertTrue(note.contains(key), key + " named in: " + note);
        }
        // what still exists keeps its value; the old keys changed nothing
        assertEquals(900, r.config().aggro.returnMaxTicks);
        assertEquals(0.5, r.config().aggro.perception.reactionBaseSeconds, "the reaction time is its own default now");
        assertEquals(30.0, r.config().aggro.perception.fullAttentionHalfAngleDeg);
        assertEquals(10, r.config().aggro.loseGraceTicks, "the new grace is its own default, not the old 200 ticks");
        assertEquals(200, r.config().aggro.searchTicks);
        String json = ConfigIO.toJson(r.config());
        assertFalse(json.contains("acquireRange") || json.contains("hearWalk"), "and they are not written back");
    }

    @Test
    void aConfigWithoutOldKeysHasNoNote(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": { \"searchTicks\": 300, \"perception\": { \"reactionBaseSeconds\": 0.4 } } }",
                StandardCharsets.UTF_8);
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
        assertEquals(0.5, p.reactionBaseSeconds, "0.5 s up close");
        assertEquals(2.0, p.reactionAt64Seconds, "2.0 s at 64 blocks");
        assertEquals(30.0, p.fullAttentionHalfAngleDeg);
        assertEquals(100.0, p.peripheralHalfAngleDeg);
        assertEquals(2.0, p.peripheralMultiplier);
        assertEquals(2.0, p.sneakMultiplier);
        assertTrue(ConfigIO.toJson(again.config()).contains("\"perception\""));
    }

    @Test
    void thePerceptionBlockCanBeSwitchedOffAndTuned(@TempDir Path dir) throws IOException {
        InhabitantsConfig.AggroPerception p = load(dir,
                "{ \"perception\": { \"enabled\": false, \"sneakMultiplier\": 3, \"reactionAt64Seconds\": 4 } }").perception;
        assertFalse(p.enabled);
        assertEquals(3.0, p.sneakMultiplier);
        assertEquals(4.0, p.reactionAt64Seconds);
        assertEquals(30.0, p.fullAttentionHalfAngleDeg, "keys left out keep their defaults");
    }

    @Test
    void thePerceptionBoundsAreKeptCoherent(@TempDir Path dir) throws IOException {
        InhabitantsConfig.AggroPerception p = load(dir, "{ \"perception\": { \"fullAttentionHalfAngleDeg\": 250, "
                + "\"peripheralHalfAngleDeg\": 30, \"peripheralMultiplier\": 0.2, \"sneakMultiplier\": 99, "
                + "\"reactionBaseSeconds\": -1, \"reactionAt64Seconds\": -5 } }").perception;
        assertEquals(180.0, p.fullAttentionHalfAngleDeg);
        assertEquals(180.0, p.peripheralHalfAngleDeg, "the field is never narrower than the full attention cone");
        assertEquals(1.0, p.peripheralMultiplier, "the periphery is never faster than the front");
        assertEquals(20.0, p.sneakMultiplier);
        assertEquals(0.0, p.reactionBaseSeconds);
        assertEquals(0.0, p.reactionAt64Seconds, "the 64 block reaction is never faster than the close one");
        InhabitantsConfig.AggroPerception q = load(dir,
                "{ \"perception\": { \"fullAttentionHalfAngleDeg\": 80, \"peripheralHalfAngleDeg\": 50 } }").perception;
        assertEquals(80.0, q.fullAttentionHalfAngleDeg);
        assertEquals(80.0, q.peripheralHalfAngleDeg);
    }

    @Test
    void aMissingPerceptionBlockIsFilledIn(@TempDir Path dir) throws IOException {
        InhabitantsConfig.Aggro a = load(dir, "{ \"perception\": null }");
        assertTrue(a.perception != null && a.perception.enabled);
    }

    // ---------------------------------------------------------------- hearing block

    @Test
    void theHearingRadiusIsBoundedAndAMissingBlockIsFilledIn(@TempDir Path dir) throws IOException {
        assertEquals(1, load(dir, "{ \"hearing\": { \"listenerRadius\": 0 } }").hearing.listenerRadius);
        assertEquals(64, load(dir, "{ \"hearing\": { \"listenerRadius\": 500 } }").hearing.listenerRadius);
        assertEquals(8, load(dir, "{ \"hearing\": { \"listenerRadius\": 8 } }").hearing.listenerRadius, "the sculk sensor's own");
        assertEquals(16, load(dir, "{ \"hearing\": null }").hearing.listenerRadius);
    }
}
