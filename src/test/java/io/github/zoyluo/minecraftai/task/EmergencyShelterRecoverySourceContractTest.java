package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the deterministic last-resort/recovery/ownership lifecycle. */
final class EmergencyShelterRecoverySourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

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
    void shelterRetreatsGetsDryRecoversAndRegistersCleanupWithoutAutoDispatch() throws IOException {
        String shelter = read("task/EmergencyShelterTask.java");
        String watcher = read("task/DangerWatcher.java");
        String cleanup = read("task/ShelterCleanupTask.java");
        String follow = read("task/FollowTask.java");

        assertTrue(shelter.contains("RETREAT_TO_SAFE_ANCHOR"));
        assertTrue(shelter.contains("NavSafetyNet.INSTANCE.requestWaterRescue(bot)"));
        assertTrue(shelter.contains("PREBUILD_RETREAT_DISTANCE = 10"));
        assertTrue(shelter.contains("isRecoveredEnoughToExit(bot)"),
                "exit must stay gated on a full recovery (or point 6a's no-more-food exception), "
                        + "not merely on health/food crossing an arbitrary threshold");
        assertTrue(shelter.contains("beginRecoveredExit(bot)"));
        assertTrue(shelter.contains("registerOwnedCleanupDebt(bot)"));
        assertTrue(shelter.contains("expected.equals(bot.getEntityWorld().getBlockState(position))"),
                "cleanup proof must use exact placed block state, not a material/shape guess");
        assertTrue(watcher.contains("shouldStartLastResortShelter(bot, threat)"));
        assertTrue(watcher.contains("isTwoHitLethalHealth"));
        // Per an explicit product decision, bots must never travel back to tear down a shelter or
        // combat structure on their own -- the player cleans those up manually. DangerWatcher still
        // registers the cleanup debt (recorded above), it just never auto-assigns ShelterCleanupTask
        // to act on it; ShelterCleanupTask itself remains available for a manual/panel assignment.
        assertFalse(watcher.contains("new ShelterCleanupTask()"),
                "DangerWatcher must not auto-dispatch bots to travel back and clean up shelters");
        assertTrue(cleanup.contains("DangerWatcher.hasObservableHostilePressure(bot)"));
        assertTrue(cleanup.contains("EmergencyShelterTask.ownsCleanupBlock(bot, debt, target)"));
        assertTrue(cleanup.contains("bot.getHungerManager().getFoodLevel() < 20"));
        assertTrue(follow.contains("EmergencyShelterTask.promoteExitDebtForCleanup(bot, shelterExitDebt);"));
    }

    /**
     * Point 2 of the rescue contract: the "regroup with a far-away player" logic (a live combat
     * regroup, or dispatching a new one) must never preempt an active emergency shelter, even
     * while the bot is still inside it waiting on a food rescue. DangerWatcher already refuses to
     * reassign anything at all while an EmergencyShelterTask owns the bot -- this locks that this
     * unconditional early return still comes strictly before {@code maybeRegroup} is ever reached.
     */
    @Test
    void activeShelterCannotBePreemptedByTheFarAwayRegroupLogic() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        int shelterGuard = watcher.indexOf(
                "active.get() instanceof EmergencyShelterTask");
        int returnTrue = watcher.indexOf("return true;", shelterGuard);
        int regroupCall = watcher.indexOf("maybeRegroup(bot, active)");
        assertTrue(shelterGuard >= 0 && returnTrue > shelterGuard && regroupCall > returnTrue,
                "an active EmergencyShelterTask must make DangerWatcher return before it ever "
                        + "reaches the far-away regroup check, in every phase including the "
                        + "no-food rescue wait");
    }

    /**
     * Points 3/4/5/6 of the rescue contract: the no-food cry-for-help/wait/rescue lifecycle, its
     * required chat feedback and its precise distinction between "ran out of food right as it
     * finished healing" (not a problem) and "ran out of food while still hurt" (genuinely stuck).
     */
    @Test
    void noFoodRescueLifecycleCriesOnceWaitsBelowHalfHealthAndDistinguishesFromFullRecovery()
            throws IOException {
        String shelter = read("task/EmergencyShelterTask.java");

        assertTrue(shelter.contains("private boolean waitingForRescue;"));
        assertTrue(shelter.contains("private boolean criedForHelp;"));
        assertTrue(shelter.contains("private boolean rescueResolvedAnnounced;"));
        // All three must be reset in onStart(), i.e. scoped to this one episode, not a
        // permanent one-time-ever flag (point 3's explicit "reset ... in some FUTURE episode").
        int onStart = shelter.indexOf("protected void onStart(AIPlayerEntity bot) {");
        int onStartEnd = shelter.indexOf("beginBuildAtCurrentPose(bot);", onStart);
        assertTrue(onStart >= 0 && onStartEnd > onStart);
        String onStartBody = shelter.substring(onStart, onStartEnd);
        assertTrue(onStartBody.contains("waitingForRescue = false;"));
        assertTrue(onStartBody.contains("criedForHelp = false;"));
        assertTrue(onStartBody.contains("rescueResolvedAnnounced = false;"));

        assertTrue(shelter.contains("isHealingStalledWithoutFood"),
                "running out of food while still hurt must be its own tracked condition, not "
                        + "inferred from \"is it eating right now\"");
        assertTrue(shelter.contains("shouldAbandonRescueWaitAndFight"));
        assertTrue(shelter.contains("if (!criedForHelp) {"),
                "the cry-for-help message must be gated so it can fire at most once per episode");
        assertTrue(shelter.contains("BrainCoordinator.INSTANCE.sendPanelChat(bot, \"bot\","),
                "must reuse the bot's existing chat mechanism rather than inventing a new one");
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
