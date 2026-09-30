package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-shape pins for the review follow-up of the companion perception (docs/PERCEPTION.md): (a) a failing scan never leaves the bot
 * blind, (b) the projectile test with perception off is exactly the old test, (c) the {@code attack_entity} tool only considers noticed
 * creatures and never replaces a running task, (d) the scan is throttled and measured, and the exit of the emergency shelter looks
 * through its observation port for the reaction time before it opens the door. The behaviour itself is proved live by
 * {@code CompanionPerceptionGameTests}, {@code EmergencyShelterAtomicRecoveryGameTests} and the converted fixtures.
 */
final class PerceptionFollowupSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertTrue(start >= 0, "missing: " + from);
        int end = text.indexOf(to, start + from.length());
        assertTrue(end > start, "missing end: " + to);
        return text.substring(start, end);
    }

    @Test
    void aFailingScanFallsBackToTheLegacyOmnidirectionalTestAndIsLoggedOnce() throws IOException {
        String senses = read("perception/CreatureSenses.java");
        String tick = between(senses, "public void tickBot(MinecraftServer server, AIPlayerEntity bot)", "/** True when the scan of");
        assertTrue(tick.contains("catch (RuntimeException exception)") && tick.contains("failedAt.put(bot.getUUID(), level.getGameTime())")
                        && tick.contains("if (failed.add(bot.getUUID()))") && tick.contains("BotLog.error(bot, \"perception_failed\""),
                "a scan failure marks the bot as degraded for that tick and is logged once per bot");
        String noticed = between(senses, "public boolean noticed(AIPlayerEntity bot, LivingEntity creature)", "/** How {@code bot} came to notice");
        assertTrue(noticed.contains("scanFailedRecently(bot)") && noticed.contains("legacyNoticed(bot, creature)"),
                "while the scan is failing every creature question is the old omnidirectional test, never blindness");
        String projectile = between(senses, "public boolean noticedProjectile(AIPlayerEntity bot, Entity projectile)",
                "/** The place {@code bot} should turn");
        assertTrue(projectile.contains("scanFailedRecently(bot)") && projectile.contains("bot.hasLineOfSight(projectile)"),
                "the projectile test degrades the same way");
    }

    @Test
    void anUnexplainedSoundTurnsTheHeadOfAnIdleBotOnlyAndNeverAnItemItDroppedItself() throws IOException {
        String senses = read("perception/CreatureSenses.java");
        String look = between(senses, "private static void lookAtHint(BotState s, long now)", "HumanAim.lookToward(s.bot, hint.pos());");
        assertTrue(look.contains("TaskManager.INSTANCE.getActive(s.bot).isPresent()")
                        && look.contains("s.bot.getActionPack().hasActiveActions()"),
                "a bot in the middle of an action (step, dig, route, item use) keeps its head where the action needs it");
        String ears = read("perception/BotEars.java");
        String receive = between(ears, "public boolean canReceiveVibration(", "public void onReceiveVibration(");
        assertTrue(receive.contains("context.sourceEntity() != bot")
                        && receive.contains("instanceof net.minecraft.world.entity.item.ItemEntity item && item.getOwner() == bot"),
                "a bot does not hear its own steps and blows, nor an item it dropped itself");
    }

    @Test
    void theProjectileTestWithPerceptionOffIsExactlyTheOldTestIncludingTheCapabilityBypass() throws IOException {
        String senses = read("perception/CreatureSenses.java");
        String projectile = between(senses, "public boolean noticedProjectile(AIPlayerEntity bot, Entity projectile)",
                "/** The place {@code bot} should turn");
        int off = projectile.indexOf("if (!enabled())");
        assertTrue(off >= 0 && projectile.indexOf("ObservableWorldQuery.canObserveEntity(bot, projectile)") > off,
                "enabled=false answers with the old canObserveEntity (which honours HIDDEN_BLOCK_SCAN) before anything else");
        assertTrue(projectile.indexOf("PrivilegedCapability.HIDDEN_BLOCK_SCAN") > off,
                "with perception on, a bot with the strict capability bypass still sees what the bypass lets it see");
        assertTrue(projectile.indexOf("PrivilegedCapability.HIDDEN_BLOCK_SCAN") < projectile.indexOf("scanFailedRecently(bot)"),
                "the capability bypass is asked before the scan state");
    }

    @Test
    void theAttackToolRefusesWithBusyInsteadOfReplacingARunningTask() throws IOException {
        String tool = read("brain/ToolRegistry.java");
        String body = between(tool, "register(\"attack_entity\"", "/** Why {@code attack_entity} cannot take the bot's hands right now");
        int notYet = body.indexOf("InteractAction.NOT_UNDER_CROSSHAIR.equals(first.reason())");
        int busy = body.indexOf("attackBusyReason(bot)");
        int assign = body.indexOf("assignLlm(bot, attack)");
        assertTrue(notYet >= 0 && busy > notYet && assign > busy && body.contains("return fail(\"busy: \" + busy"),
                "after the first strike only asks for a turn, a busy bot is refused before the attack task is assigned");
        String reason = between(tool, "private static String attackBusyReason(AIPlayerEntity bot)", "/** Terminal/mission-control commands");
        assertTrue(reason.contains("isActiveSafety(bot)") && reason.contains("this request is not kept")
                        && reason.contains("isUserPaused(bot)") && reason.contains("!(task instanceof AttackEntityTask)"),
                "a safety task is never interrupted (and the request is not kept), a paused mission stays paused, only an idle bot or an attack of its own is free");
        assertFalse(body.contains("SafetyTaskActiveException"), "the busy answer is the tool's own truthful reply, not an exception");
        assertTrue(body.contains("ObservableWorldQuery.canNoticeCreature(bot, entity)"),
                "candidates are creatures the bot has noticed, so the reply never reveals an unseen mob");
    }

    @Test
    void theScanIsThrottledAndMeasured() throws IOException {
        String senses = read("perception/CreatureSenses.java");
        assertTrue(senses.contains("static boolean isPassive(LivingEntity e)")
                        && senses.contains("!(mob instanceof Enemy) && !(mob instanceof NeutralMob) && mob.getTarget() == null"),
                "a mob that is neither an Enemy nor a NeutralMob and hunts nothing is never scanned");
        assertTrue(senses.contains("!(throttle && isPassive(e))") && senses.contains("(throttle && isPassive(creature))"),
                "passive creatures are filtered out of the scan and answered by the plain test on demand");
        assertTrue(senses.contains("public static final int SCAN_CADENCE_TICKS = 2;")
                        && senses.contains("!s.exposure.inProgress(id, now) && (now + c.getId()) % SCAN_CADENCE_TICKS != 0L"),
                "a creature with no run of exposure and no sound at it is read every second tick, alternating by entity");
        assertTrue(senses.contains("seenClear(bot, c, clear)"), "one clear-view answer per creature per scan");
        assertTrue(senses.contains("if (throttle && now - track.lastClear <= 1) {")
                        && senses.contains("now - track.lastClear <= (throttle ? SCAN_CADENCE_TICKS : 1)"),
                "a noticed creature's line is verified every second tick and the tolerance for a lost line grows by the same tick");
        assertTrue(senses.contains("BotProfiler.INSTANCE.record(bot, \"perception_scan\""),
                "the cost of the scan is recorded per bot in the profiler");
    }

    @Test
    void theShelterExitLooksThroughTheObservationPortForTheReactionTimeBeforeOpeningTheDoor() throws IOException {
        String shelter = read("task/EmergencyShelterTask.java");
        int reseal = shelter.indexOf("if (!forcePressureExit && resealObservedPressure(bot)) {");
        int dwell = shelter.indexOf("if (!forcePressureExit && observationPortDwell(bot)) {");
        int obstruction = shelter.indexOf("BlockPos obstruction = firstExitObstruction(bot, egressFeet);");
        assertTrue(reseal >= 0 && dwell > reseal && obstruction > dwell,
                "after the port is open and before the foot wall is mined, the bot looks through the port");
        String body = between(shelter, "private boolean observationPortDwell(AIPlayerEntity bot)", "private int observableExitPressure");
        assertTrue(body.contains("CreatureSenses.noticeDwellTicks(CombatCore.hostilePressureScanRange())")
                        && body.contains("ownsCurrentPlacement(bot, egressFeet)"),
                "the dwell is the shared reaction-time formula for the farthest pressure the exit cares about");
        String senses = read("perception/CreatureSenses.java");
        String dwellBody = between(senses, "public static int noticeDwellTicks(double distance)", "/** The scan reads a creature that is not yet");
        assertTrue(dwellBody.contains("if (!enabled())") && dwellBody.contains("CreaturePerception.requiredSeconds(")
                        && dwellBody.contains("CreaturePerception.noticeTick(seconds) + SCAN_CADENCE_TICKS"),
                "zero with perception off, otherwise the formula plus the scan cadence");
    }

    @Test
    void aCoverPeekLastsAtLeastAsLongAsItTakesToNoticeTheShootersAgain() throws IOException {
        String combat = read("task/CombatTask.java");
        String peek = between(combat, "if (peekStage == PeekStage.EXPOSED) {", "peekStage = PeekStage.BACK;");
        int hold = peek.indexOf("CreatureSenses.noticeDwellTicks(bot.distanceTo(target))");
        int count = peek.indexOf("peekThreatsAtLastPeek = CombatCore.rangedThreatsAround(bot, PEEKABOO_SCAN_RANGE).size();");
        assertTrue(hold >= 0 && peek.contains("Math.max(PEEKABOO_EXPOSE_TICKS,") && peek.contains("++peekExposedTicks <= exposeTicks")
                        && count > hold,
                "shooters behind the cover are forgotten after a tick and noticed again only after the reaction time, so the peek that counts them "
                        + "lasts at least that long (zero extra with perception off)");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
