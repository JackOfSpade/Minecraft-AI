package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the structure of R4/R6 that the behaviour depends on and a GameTest would only notice by luck: the follow pace is leased before
 * the movement decisions and the escort runs after them, a swing is only made on a ready tick, the escort task stays TPS-critical, the
 * dark-trap scan asks for the capability first, and a despawned bot leaves no aggro cache behind.
 */
final class FollowEscortSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void theFollowPaceIsLeasedBeforeTheMovementAndTheEscortRunsAfterIt() throws IOException {
        String follow = read("task/FollowTask.java");
        int tick = follow.indexOf("protected void onTick(");
        int end = follow.indexOf("private void observeTarget(");
        assertTrue(tick >= 0 && end > tick);
        String onTick = follow.substring(tick, end);
        int pace = onTick.indexOf("publishPace(bot, target);");
        int land = onTick.indexOf("followLand(bot, target);");
        int yaw = onTick.indexOf("float yawBeforeEscort = bot.getYRot();");
        int escort = onTick.indexOf("escort.tick(bot, target);");
        int preserve = onTick.indexOf("reprojectLegacyInputsForYawChange(yawBeforeEscort);");
        assertTrue(pace >= 0 && land > pace && yaw > land && escort > yaw && preserve > escort,
                "publishPace, then followLand, then escort aim preserves its already-issued legacy movement");
        assertTrue(onTick.substring(escort, preserve).contains("!bot.getActionPack().hasBaritoneRoute()"),
                "the FollowTask fast path must not touch an ActionPack-owned Baritone route");
        assertTrue(follow.contains("requestPace(gait, PaceOwner.FOLLOW)"), "the follow pace is a FOLLOW tick lease");
        assertFalse(follow.contains("requestRoutePace"), "a route lease would outlive the follow's own dwell");
    }

    @Test
    void aSwingIsOnlyMadeOnAReadyTickAndNeverInterruptsTheFollow() throws IOException {
        String escort = read("task/FollowEscort.java");
        int ready = escort.indexOf("bot.getAttackStrengthScale(0.5F) < READY_STRENGTH");
        int strike = escort.indexOf("CombatCore.strikeIfReady(bot, target)");
        assertTrue(ready >= 0 && strike > ready, "the cooldown is checked before strikeIfReady (whose look-at steers the bot)");
        assertTrue(escort.contains("CombatCore.canStrikeNow(bot, entity)") && escort.contains("bot.isUsingItem()"));
        assertTrue(escort.contains("CombatCore.hostileTo(bot, entity)")
                        && escort.contains("!CombatCore.isMeleeForbiddenThreat(entity)")
                        && escort.contains("ObservableWorldQuery.canNoticeCreature(bot, entity)"),
                "the candidates are the bot's own defence targets it has noticed, never a creeper or a warden");
        assertFalse(escort.contains("stopMovement") || escort.contains("stopNavigation") || escort.contains("startPathTo")
                        || escort.contains("startApproachTo") || escort.contains("startWalkTo"),
                "the escort never stops or redirects the follow");
        assertTrue(escort.contains("calmWardenObservedWithin(bot, CALM_WARDEN_RANGE)"), "silent next to a calm warden");

        String pack = read("action/ActionPack.java");
        int projection = pack.indexOf("public void reprojectLegacyInputsForYawChange(float yawBefore)");
        int next = pack.indexOf("private boolean baritoneOwnsBot()", projection);
        assertTrue(projection >= 0 && next > projection);
        String projectionBody = pack.substring(projection, next);
        int paceOwnerEnd = pack.indexOf("public void setSneaking", next);
        assertTrue(paceOwnerEnd > next);
        String baritoneOwner = pack.substring(next, paceOwnerEnd);
        assertTrue(projectionBody.contains("player.zza = applied.forward()")
                        && projectionBody.contains("player.xxa = applied.strafing()"),
                "the next physics tick must receive the reprojected inputs, not only ActionPack's later update");
        assertTrue(projectionBody.contains("forward == 0.0F && strafing == 0.0F && player.zza == 0.0F && player.xxa == 0.0F"),
                "a stale already-applied input must still be reprojected even after its controller cleared raw fields");
        assertTrue(projectionBody.contains("if (baritoneOwnsBot())")
                        && baritoneOwner.contains("route != null")
                        && baritoneOwner.contains("NavEngineSelector.query(\"baritone_busy\", () -> BaritoneRegistry.INSTANCE.isBusy(player), false)"),
                "the writer boundary must leave inputs untouched for both ActionPack routes and direct busy Baritone callers");
        assertFalse(projectionBody.contains("claim(") || projectionBody.contains("stopNavigation")
                        || projectionBody.contains("startPathTo") || projectionBody.contains("BaritoneRegistry")
                        || projectionBody.contains("NavEngineSelector"),
                "the input projection is a passive legacy correction, not controller hand-over");
    }

    @Test
    void anEngagedEscortKeepsTheFollowTaskCritical() throws IOException {
        String manager = read("task/TaskManager.java");
        assertTrue(manager.contains("task instanceof FollowTask follow && follow.escortEngaged()"));
    }

    @Test
    void theDarkTrapScanAsksForTheTeleportCapabilityFirst() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        int start = watcher.indexOf("boolean escapeToSurface(AIPlayerEntity bot)");
        assertTrue(start >= 0);
        String body = watcher.substring(start, watcher.indexOf("// combat stuck-trap detection", start));
        int decide = body.indexOf("CapabilityRuntime.decide(");
        int scan = body.indexOf("surfaceScans++");
        int column = body.indexOf("Standability.isStandable(");
        assertTrue(decide >= 0 && scan > decide && column > scan,
                "the capability decision comes before any column scan");
        assertTrue(body.substring(0, scan).contains("return false;"), "a denied teleport ends the escape before the scan");
    }

    @Test
    void aDespawnedBotLeavesNoAggroCacheBehind() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        int clear = watcher.indexOf("public void clear(AIPlayerEntity bot)");
        assertTrue(clear >= 0 && watcher.substring(clear, watcher.indexOf("public void clearAll()")).contains("AggroSense.clear(bot)"));
        String sense = read("task/AggroSense.java");
        assertTrue(sense.contains("CACHE.remove(bot.getUUID());") && sense.contains("CLOSING.remove(bot.getUUID());"));
        String lifecycle = read("runtime/RuntimeLifecycleCoordinator.java");
        assertTrue(lifecycle.contains("DangerWatcher.INSTANCE.clear(bot)"), "the despawn path clears the danger watcher");
    }

    @Test
    void theFollowedPlayerIsAProtectedVictimOfTheAggroSense() throws IOException {
        String sense = read("task/AggroSense.java");
        assertTrue(sense.contains("FollowTask::currentTarget"), "the followed player counts like the owner");
    }

    @Test
    void theBotEatsAtTheSprintLimitWithoutWaitingForItsWalkToEnd() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        assertTrue(watcher.contains("SPRINT_LIMIT_FOOD = PaceRules.SPRINT_FOOD_FLOOR + 1"));
        assertTrue(watcher.contains("hasActiveActions() && !urgent && !sprintLimitHunger"),
                "a follower on a long walk eats at food 7 instead of waiting for the walk");
        int pressure = watcher.indexOf("if (hasNakedEatHostilePressure(bot, hostilePressure)) {");
        int start = watcher.indexOf("TaskManager.INSTANCE.assign(bot, regenStallOnly");
        assertTrue(pressure >= 0 && start > pressure, "the fight gate still comes before the bite");
    }

    @Test
    void theFollowEscapeCheckRunsOnlyForAThreatThatCouldActuallyBeAssigned() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        int top = watcher.indexOf("Threat top = threat.get();");
        int branch = watcher.lastIndexOf("if (threat.isPresent()) {", top);
        int end = watcher.indexOf("        // Mitigation hardening", top);
        assertTrue(branch >= 0 && end > top);
        String dispatch = watcher.substring(branch, end);
        int severity = dispatch.indexOf("top.severity().ordinal()");
        int owner = dispatch.indexOf("shouldAssignThreatTask(bot, active, top)");
        int cooldown = dispatch.indexOf("canAssignThreatTask(server, bot, top)");
        int followKeeps = dispatch.indexOf("followKeepsThreat(server, bot, active, top)");
        assertTrue(severity >= 0 && owner > severity && cooldown > owner && followKeeps > cooldown,
                "follow escort logging and shelter probing run only after a medium-or-higher threat can dispatch");
    }

    @Test
    void aCalmWardenOnlyDefersRoutineEatingNotUrgentSurvivalTransactions() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        int wardenGate = watcher.indexOf("QuietZone.calmWardenObservedWithin(bot, CALM_WARDEN_EAT_RANGE)");
        int urgentGate = watcher.lastIndexOf("if (!urgent", wardenGate);
        assertTrue(wardenGate >= 0 && urgentGate >= 0 && urgentGate < wardenGate,
                "critical hunger, healing and shelter-cleanup recovery must bypass the calm-warden eating deferral");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
