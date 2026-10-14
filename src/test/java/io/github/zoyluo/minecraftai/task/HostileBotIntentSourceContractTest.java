package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Pins the promotion boundary between visible pre-attack pressure and a targetable hostile act. */
class HostileBotIntentSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/HostileBotIntent.java");

    @Test
    void aimMustBeSustainedButAChargeRemainsOnlyASuspect() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("static final int DRAW_TICKS = 20;"),
                "a ranged aim must remain exclusive for one second before it becomes a mark");

        int draw = source.indexOf("// DRAW:");
        int swing = source.indexOf("// SWING:", draw);
        String drawBody = source.substring(draw, swing);
        assertTrue(drawBody.contains("++pair.drawTicks >= DRAW_TICKS")
                        && drawBody.contains("HostileBotLedger.markPlayer(aggressor, victim, now, \"aim\")"),
                "only the sustained exclusive aim branch may promote a player to a target");

        int charge = source.indexOf("// ARMED CHARGE:");
        int end = source.indexOf("/** Records the wrapper", charge);
        String chargeBody = source.substring(charge, end);
        assertTrue(chargeBody.contains("HostileBotLedger.suspectPlayer(aggressor, victim, now, \"charge\")"),
                "an armed closing movement must provide pressure without making a target");
        assertFalse(chargeBody.contains("HostileBotLedger.markPlayer(aggressor, victim, now, \"charge\")"),
                "only a concrete hostile act may promote the charge suspect");
    }

    @Test
    void anInterruptedObservationErasesPartialAimAndChargeEvidence() throws ReflectiveOperationException {
        Class<?> pairType = Class.forName(HostileBotIntent.class.getName() + "$Pair");
        Constructor<?> constructor = pairType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object pair = constructor.newInstance();
        Field drawTicks = pairType.getDeclaredField("drawTicks");
        Field chargeTicks = pairType.getDeclaredField("chargeTicks");
        Field distanceTicks = pairType.getDeclaredField("distanceTick");
        Method reset = pairType.getDeclaredMethod("resetInterruptedSignals");
        drawTicks.setAccessible(true);
        chargeTicks.setAccessible(true);
        distanceTicks.setAccessible(true);
        reset.setAccessible(true);

        drawTicks.setInt(pair, HostileBotIntent.DRAW_TICKS - 1);
        chargeTicks.setInt(pair, 1);
        long[] history = (long[]) distanceTicks.get(pair);
        Arrays.fill(history, 73L);

        reset.invoke(pair);

        assertEquals(0, drawTicks.getInt(pair), "a range/visibility gap restarts the 20-sample aim proof");
        assertEquals(0, chargeTicks.getInt(pair), "a gap cannot carry a charge signal into the next sample");
        assertTrue(Arrays.stream(history).allMatch(tick -> tick == Long.MIN_VALUE),
                "a later charge must not compare against a pre-gap distance");
    }

    @Test
    void sourceResetsTrackedEvidenceForRangeAndPresenceGaps() throws IOException {
        String source = Files.readString(SOURCE);
        int sampleLevel = source.indexOf("private static void sampleLevel(ServerLevel level)");
        int continuity = source.indexOf("private static void resetTrackSignals", sampleLevel);
        String sampling = source.substring(sampleLevel, continuity);

        assertTrue(sampling.contains("if (victims == null || !aggressor.isAlive() || aggressor.isSpectator())")
                        && sampling.contains("resetTrackSignals(track);"),
                "an unavailable victim/aggressor must reset all retained signals");
        assertTrue(sampling.contains("Pair skipped = track.pairs.get(victim.getUUID())")
                        && sampling.contains("skipped.resetInterruptedSignals();"),
                "an out-of-range victim must not resume a partial draw when it returns");
        assertTrue(sampling.contains("resetAbsentAggressorSignals(level, Set.of())")
                        && sampling.contains("resetAbsentAggressorSignals(level, sampledAggressors)"),
                "a temporarily absent or no-longer-markable aggressor must also lose its partial proof");
        int levelChange = sampling.indexOf("if (track.level != null && track.level != level)");
        int rebind = sampling.indexOf("track.level = level;", levelChange);
        assertTrue(levelChange >= 0 && rebind > levelChange
                        && sampling.substring(levelChange, rebind).contains("resetTrackSignals(track);"),
                "a portal/world transfer must reset a partial intent proof before the track changes levels");
    }
}
