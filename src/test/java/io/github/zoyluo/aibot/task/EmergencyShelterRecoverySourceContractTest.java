package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the deterministic last-resort/recovery/ownership lifecycle. */
final class EmergencyShelterRecoverySourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void twoHitGateRequiresBothLowHealthAndEnoughDamage() {
        assertTrue(DangerWatcher.isTwoHitLethalHealth(6.0F, 20.0F, 3.0D));
        assertTrue(DangerWatcher.isTwoHitLethalHealth(8.0F, 20.0F, 4.0D));
        assertFalse(DangerWatcher.isTwoHitLethalHealth(11.0F, 20.0F, 8.0D),
                "high health is not an automatic shelter admission");
        assertFalse(DangerWatcher.isTwoHitLethalHealth(6.0F, 20.0F, 2.9D),
                "a hostile needing more than two hits must use normal combat/evade");
    }

    @Test
    void shelterRetreatsGetsDryRecoversAndThenRegistersExactCleanup() throws IOException {
        String shelter = read("task/EmergencyShelterTask.java");
        String watcher = read("task/DangerWatcher.java");
        String cleanup = read("task/ShelterCleanupTask.java");
        String follow = read("task/FollowTask.java");

        assertTrue(shelter.contains("RETREAT_TO_SAFE_ANCHOR"));
        assertTrue(shelter.contains("NavSafetyNet.INSTANCE.requestWaterRescue(bot)"));
        assertTrue(shelter.contains("PREBUILD_RETREAT_DISTANCE = 10"));
        assertTrue(shelter.contains("isFullyRecovered(bot)"));
        assertTrue(shelter.contains("beginRecoveredExit(bot)"));
        assertTrue(shelter.contains("registerOwnedCleanupDebt(bot)"));
        assertTrue(shelter.contains("expected.equals(bot.getServerWorld().getBlockState(position))"),
                "cleanup proof must use exact placed block state, not a material/shape guess");
        assertTrue(watcher.contains("shouldStartLastResortShelter(bot, threat)"));
        assertTrue(watcher.contains("isTwoHitLethalHealth"));
        assertTrue(watcher.contains("new ShelterCleanupTask()"));
        assertTrue(cleanup.contains("DangerWatcher.hasObservableHostilePressure(bot)"));
        assertTrue(cleanup.contains("EmergencyShelterTask.ownsCleanupBlock(bot, debt, target)"));
        assertTrue(cleanup.contains("bot.getHungerManager().getFoodLevel() < 20"));
        assertTrue(follow.contains("EmergencyShelterTask.promoteExitDebtForCleanup(bot, shelterExitDebt);"));
    }

    @Test
    void cancellationDoesNotLeaveAnUnroofedButSideSealedBotWithoutAnExitOrSafeCleanup() throws IOException {
        String shelter = read("task/EmergencyShelterTask.java");
        int preserve = shelter.indexOf("private boolean preserveOwnedExitDebt(AIPlayerEntity bot)");
        int abort = shelter.indexOf("protected void onAbort(AIPlayerEntity bot)");

        assertTrue(preserve >= 0 && abort > preserve);
        String preservation = shelter.substring(preserve, abort);
        String cancellation = shelter.substring(abort);
        assertTrue(preservation.contains("|| hasPassableEnvelopeSide(bot)"),
                "a roofless but side-sealed shell still needs a verified owned doorway handoff");
        assertTrue(cancellation.contains("boolean exitDebtHandedOff = preserveOwnedExitDebt(bot);"));
        assertTrue(cancellation.contains("!exitDebtHandedOff"));
        assertTrue(cancellation.contains("registerOwnedCleanupDebt(bot);"),
                "partial shells are registered only after cancellation proves a passable escape");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
