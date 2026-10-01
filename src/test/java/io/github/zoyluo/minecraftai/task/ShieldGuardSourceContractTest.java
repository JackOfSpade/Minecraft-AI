package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the wiring of the one reactive shield owner ({@link ShieldGuard}) to its intended shape: where it runs, what yields to it,
 * and the vanilla use path it goes through. The behaviour itself is proven by ShieldBlockingGameTests and the pure rules by
 * ShieldRulesTest and ShieldBlockabilityTest.
 */
class ShieldGuardSourceContractTest {
    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/" + relative));
    }

    @Test
    void theSafetyNetsRescueStandsTheGuardDown() throws IOException {
        String coordinator = read("task/BotTickCoordinator.java");
        int net = coordinator.indexOf("NavSafetyNet.INSTANCE.tickBot(server, bot)");
        int stand = coordinator.indexOf("ShieldGuard.INSTANCE.standDown(bot, \"safety_net\");", net);
        assertTrue(net > 0 && stand > net && stand < coordinator.indexOf("continue;", net),
                "a reactive shield comes down when the safety net takes the bot over");
    }

    @Test
    void theGuardRunsOncePerBotTickBetweenTheDangerScanAndTheTasks() throws IOException {
        String coordinator = read("task/BotTickCoordinator.java");
        int scan = coordinator.indexOf("DangerWatcher.INSTANCE.scanBot(server, bot)");
        int guard = coordinator.indexOf("ShieldGuard.INSTANCE.tickBot(server, bot);");
        int goals = coordinator.indexOf("GoalExecutor.INSTANCE.tickBot(server, bot)");
        assertTrue(scan > 0 && guard > scan && goals > guard,
                "the guard decides after the danger scan and before the mission executor; the tasks (ticked earlier) read it next tick");
        assertTrue(coordinator.indexOf("ShieldGuard.INSTANCE.tickBot(", guard + 1) < 0, "exactly one call site");
    }

    @Test
    void theUseGoesThroughTheVanillaPathMainHandFirstThenOffhand() throws IOException {
        String guard = read("task/ShieldGuard.java");
        int use = guard.indexOf("private static Raise tryVanillaUse(");
        String body = guard.substring(use, guard.indexOf("/** Lowers the shield if the bot is using it", use));
        assertTrue(body.contains("for (InteractionHand hand : InteractionHand.values())")
                        && body.contains("InteractAction.useItemInAir(bot, hand)"),
                "MAIN_HAND then OFF_HAND, exactly as the client tries them");
        int raise = guard.indexOf("public static Raise raise(");
        String raiseBody = guard.substring(raise, guard.indexOf("private static Raise tryVanillaUse(", raise));
        assertTrue(raiseBody.indexOf("isOnCooldown(shieldStack(bot))") > 0
                        && raiseBody.indexOf("isOnCooldown(shieldStack(bot))") < raiseBody.indexOf("tryVanillaUse(bot, owner)"),
                "a shield on its item cooldown is never tried (no re-raise spam)");
        assertTrue(raiseBody.contains("mainHandConsumesUse(main)") && raiseBody.contains("switchMainHandAway(bot)"),
                "a main-hand item that takes the use forces a hotbar change first");
    }

    @Test
    void componentBackedShieldsFlowThroughTheEquipAndHoldPath() throws IOException {
        String equip = read("action/EquipAction.java");
        assertTrue(equip.contains("ShieldBlockability.isShield(bot.getOffhandItem())")
                        && equip.contains("ShieldBlockability.isShield(inventory.getNonEquipmentItems().get(slot))"),
                "the carried component-backed item is selected for the offhand without an Items.SHIELD identity check");
        assertFalse(equip.contains("Items.SHIELD"), "the equip path has no vanilla-shield item-identity gate");

        String offhand = read("action/OffhandPolicy.java");
        assertTrue(offhand.contains("ShieldBlockability.isShield(offhand)"),
                "the standing offhand policy retains a component-backed held shield");

        String guard = read("task/ShieldGuard.java");
        assertTrue(guard.contains("ShieldBlockability.isShield(bot.getUseItem())")
                        && guard.contains("ShieldBlockability.isShield(offhand)")
                        && guard.contains("ShieldBlockability.isShield(stack)"),
                "the reactive guard recognizes the component-backed item when held, in either hand, or carried");
        assertFalse(guard.contains("Items.SHIELD"), "the reactive hold path has no vanilla-shield item-identity gate");
    }

    @Test
    void whileTheGuardHoldsTheShieldTasksCannotMineAttackOrDropIt() throws IOException {
        String pack = read("action/ActionPack.java");
        int stopAll = pack.indexOf("public void stopAll(");
        assertTrue(pack.substring(stopAll, pack.indexOf("public boolean hasActiveActions()", stopAll))
                        .contains("ShieldGuard.holdsShield(player)"),
                "stopAll spares the shield the reactive owner holds");
        int breakTick = pack.indexOf("public ActionResult tickBreak(");
        assertTrue(pack.substring(breakTick, pack.indexOf("controller.tick(this)", breakTick)).contains("ShieldGuard.usingShield(player)"),
                "no block is broken while the shield is up");
        String interact = read("action/InteractAction.java");
        int attack = interact.indexOf("public static ActionResult attackEntity(");
        assertTrue(interact.substring(attack, interact.indexOf("StrikeLegality.strikeRefusal", attack))
                        .contains("if (player.isUsingItem())"),
                "no attack is made while an item is in use (a raised shield included)");
        String core = read("task/CombatCore.java");
        int strike = core.indexOf("public static boolean strikeIfReady(");
        String strikeBody = core.substring(strike, core.indexOf("public static float safeStrafeInput", strike));
        int aim = strikeBody.indexOf("HumanAim.isUnderCrosshair(bot, target)");
        int lower = strikeBody.indexOf("ShieldGuard.lower(bot);");
        assertTrue(aim > 0 && lower > aim, "the shield comes down only for a swing that can land (the aim is on the target first)");
        assertTrue(strikeBody.substring(lower).startsWith("ShieldGuard.lower(bot);\n            return false;"),
                "the swing is the next tick's: never lowered and struck in the same tick");
    }

    @Test
    void theMeleeRhythmSwingsOnlyReadyOnTargetAndOnTheTickAfterTheLowering() throws IOException {
        String combat = read("task/CombatTask.java");
        int block = combat.indexOf("private void block(AIPlayerEntity bot)");
        String body = combat.substring(block, combat.indexOf("private void reposition(AIPlayerEntity bot)", block));
        assertTrue(body.contains("ShieldRules.meleeStep(ready, onTarget, true, ShieldGuard.shieldUsable(bot)"),
                "the rhythm is the pure state machine");
        assertTrue(body.contains("HumanAim.isUnderCrosshair(bot, target)"), "a swing needs the target under the crosshair");
        int swing = body.indexOf("case SWING ->");
        String swingCase = body.substring(swing, body.indexOf("case RAISE ->", swing));
        assertTrue(swingCase.contains("ShieldGuard.lower(bot);") && swingCase.contains("phase = Phase.STRIKE;")
                        && !swingCase.contains("strike(bot)"),
                "SWING lowers the shield and leaves the strike to the next tick");
        int reposition = combat.indexOf("private void reposition(AIPlayerEntity bot)");
        assertTrue(combat.substring(reposition, combat.indexOf("private void retreat(", reposition))
                        .contains("PaceRules.inputScale(false, bot.isUsingItem())"),
                "the raw strafe keys carry the vanilla use-item slowdown");
    }

    @Test
    void aProjectileFromAShooterNobodyNoticedWaitsTheReactionTime() throws IOException {
        String guard = read("task/ShieldGuard.java");
        assertTrue(guard.contains("ShieldRules.reacted(") && guard.contains("CreaturePerception.requiredSeconds(params"),
                "a first sighting waits the shared reaction formula");
        assertTrue(guard.contains("ObservableWorldQuery.canNoticeCreature(bot, living)"),
                "a projectile is anticipated only when its shooter is a creature the bot is tracking");
        assertFalse(guard.contains("SHOOTER_HOLD_MAX_TICKS"), "the pre-emptive raise is held until the shot lands or the draw stops");
        assertTrue(guard.contains("OffhandPolicy.apply(bot)") && !guard.contains("EquipAction.equipShieldOffhand"),
                "the offhand content is the equipment rule's (an empty offhand or a totem takes the best shield, nothing else moves)");
    }

    @Test
    void theHandPolicyFinishesEatingAndDrawsUnlessTheHitIsLethalAndTheRangedExchangeKeepsTheHands() throws IOException {
        String guard = read("task/ShieldGuard.java");
        assertTrue(guard.contains("ShieldRules.mayInterrupt(use, lethal)"), "the hand policy is the pure rule");
        assertTrue(guard.contains("combat.isRangedExchange()") && guard.contains("task instanceof CreeperDefenseTask")
                        && guard.contains("task instanceof EmergencyShelterTask"),
                "the tasks that own the hands are left alone");
        assertFalse(guard.contains("LookAction.lookAt("), "the head never turns instantly");
    }

    @Test
    void aTaskShieldNeverOutlivesTheTaskPhaseThatRaisedIt() throws IOException {
        String guard = read("task/ShieldGuard.java");
        int tick = guard.indexOf("private void tick(AIPlayerEntity bot)");
        String body = guard.substring(tick, guard.indexOf("public void standDown(", tick));
        assertTrue(body.indexOf("releaseStaleTaskShield(bot, state, active);") > 0
                        && body.indexOf("releaseStaleTaskShield(bot, state, active);") < body.indexOf("handsOwnedByTask(active)"),
                "every tick, before anything else, a task shield whose task left its shield phase comes down");
        assertTrue(guard.contains("combat.holdsItsShield()") && guard.contains("guard.holdsItsShield(bot)")
                        && guard.contains("creeper.holdsItsShield()"),
                "every task that owns a melee or dedicated shield phase is accounted for");
        String combat = read("task/CombatTask.java");
        assertTrue(combat.contains("return phase == Phase.BLOCK && state == TaskState.RUNNING;"), "only the BLOCK phase holds it");
        int timeout = combat.indexOf("fail(\"combat_timeout\");");
        assertTrue(combat.lastIndexOf("ShieldGuard.lowerIfOwner(bot, ShieldGuard.Owner.TASK);", timeout) > combat.lastIndexOf("elapsed > 2400", timeout),
                "a combat timeout lowers its own shield");
    }

    @Test
    void sprintingTasksOnlyBrieflyBlockProjectilesAlreadyInFlight() throws IOException {
        String guard = read("task/ShieldGuard.java");
        int follow = guard.indexOf("if (active instanceof FollowTask || active instanceof EvadeTask || active instanceof CombatRegroupTask");
        assertTrue(follow > guard.indexOf("// 1. What is in flight") && follow < guard.indexOf("// 2. A late creeper fuse"),
                "a following, escaping, regrouping or combat-retreating bot blocks only what is already in flight, never a pre-emptive hold");
        assertTrue(guard.contains("combat.retreating()") && read("task/CombatTask.java").contains("boolean retreating()"),
                "CombatTask RETREAT is a sprinting state, not a pre-emptive shield hold");
        int owned = guard.indexOf("private static boolean handsOwnedByTask(Task task)");
        assertFalse(guard.substring(owned, guard.indexOf("private void releaseIfOwned(", owned)).contains("task instanceof EvadeTask"),
                "an escape yields only for an already-in-flight projectile; it otherwise resumes sprinting");
    }

    @Test
    void lateFuseCreeperReachComesFromVanillaExplosionRadiusIncludingPoweredCreepers() throws IOException {
        assertEquals(6.0D, ShieldGuard.creeperBlastReach(false), 1.0E-9D);
        assertEquals(12.0D, ShieldGuard.creeperBlastReach(true), 1.0E-9D);
        String guard = read("task/ShieldGuard.java");
        assertTrue(guard.contains("isVanillaCreeper(creeper)")
                        && guard.contains("bot.distanceTo(creeper) <= creeperBlastReach(creeper.isPowered())")
                        && !guard.contains("CREEPER_FUSE_RANGE"),
                "only a registered vanilla charged creeper is assessed through its real twelve-block damage reach, not an invented cutoff");
    }

    @Test
    void guardianBeamLethalityIncludesItsUnblockableMagicBeforeTheBlockableBite() throws IOException {
        assertEquals(7.0F, ShieldGuard.guardianBeamDamage(6.0D, false, false));
        assertEquals(9.0F, ShieldGuard.guardianBeamDamage(6.0D, true, false));
        // Vanilla ElderGuardian#createAttributes gives the elder an 8.0 mob-attack attribute;
        // GuardianAttackGoal then adds 1 indirect magic, 2 on Hard, and 2 for elder status.
        assertEquals(13.0F, ShieldGuard.guardianBeamDamage(8.0D, true, true));
        assertTrue(read("task/ShieldGuard.java").contains("isVanillaGuardian(guardian)"),
                "the vanilla guardian beam formula declines a modded Guardian subclass rather than guessing its damage");
    }

    @Test
    void guardSharesTheTaskOwnedMeleeRhythmAndHeardShotsUseTheirLaunchPoint() throws IOException {
        String guardTask = read("task/GuardTask.java");
        assertTrue(guardTask.contains("ShieldGuard.holdMeleeBetweenSwings(bot, target)")
                        && guardTask.contains("boolean holdsItsShield(AIPlayerEntity bot)")
                        && guardTask.contains("boolean meleeRhythmAgainst(")
                        && guardTask.contains("boolean ownsOrdinaryThreat(AIPlayerEntity bot, Threat threat)")
                        && guardTask.contains("if (ShieldGuard.INSTANCE.holding(bot))")
                        && guardTask.contains("ShieldGuard.lowerIfOwner(bot, ShieldGuard.Owner.TASK);"),
                "guard lowers to swing and raises a task-owned shield between its own ready strikes");
        String watcher = read("task/DangerWatcher.java");
        assertTrue(watcher.contains("shouldAssignThreatTask(bot, active, top)")
                        && watcher.contains("guard.ownsOrdinaryThreat(bot, threat)"),
                "DangerWatcher preserves only the exact threat a persistent GuardTask can own, not unrelated ranged pressure");
        String guard = read("task/ShieldGuard.java");
        assertTrue(guard.contains("static boolean holdMeleeBetweenSwings(")
                        && guard.contains("static boolean meleeShieldEligible(")
                        && guard.contains("ObservableWorldQuery.canNoticeCreature(bot, attacker)")
                        && guard.contains("ShieldBlockability.meleeBlockable(level, shieldStack(bot), attacker)")
                        && guard.contains("state.taskRetryAt")
                        && guard.contains("activeMeleeRhythmAgainst(active, bot, shooter)"),
                "the reactive owner does not freeze a locally-noticed, blockable guard melee exchange or delay an urgent reactive scan");
        String combat = read("task/CombatTask.java");
        assertTrue(combat.contains("return ShieldGuard.meleeShieldEligible(bot, target);")
                        && combat.contains("if (!ShieldGuard.meleeShieldEligible(bot, target))"),
                "CombatTask shares the same noticed-and-blockable melee gate as GuardTask");
        String hunt = read("task/HuntTask.java");
        assertTrue(hunt.contains("if (ShieldGuard.holdsShield(bot))") && !hunt.contains("holdMeleeBetweenSwings"),
                "hunt yields to a real reactive shield but never raises a task shield against its passive prey");
        String senses = read("perception/CreatureSenses.java");
        assertTrue(senses.contains("backProjectedBallisticLaunch")
                        && senses.contains("couldHaveHeardShotDuringFlight")
                        && senses.contains("hasVanillaHearingBallisticCourse(type)")
                        && senses.contains("minecraft:arrow")
                        && senses.contains("minecraft:spectral_arrow")
                        && senses.contains("minecraft:trident")
                        && senses.contains("minecraft:llama_spit")
                        && senses.contains("matchesHeardShot(shot.block(), estimatedLaunch)"),
                "a heard shot is matched only for exact vanilla flight types to its physically timed, back-projected event block");
        assertFalse(senses.contains("4.0D + 3.2D * Math.max(0, projectile.tickCount)"),
                "the old loose expanding-radius association cannot return");
    }

    @Test
    void theShieldNeverDropsBehindTheBacksOfEatingPlacingOrTheHotbar() throws IOException {
        String guard = read("task/ShieldGuard.java");
        assertTrue(guard.contains("active instanceof EatTask || active instanceof CombatTask combat && combat.healing() ? UseKind.CONSUMING"),
                "a pending heal counts as eating for the hand policy");
        assertTrue(read("action/EatAction.java").contains("ShieldGuard.usingShield(player)"), "a bite never replaces the raised shield");
        String eat = read("task/EatTask.java");
        assertTrue(eat.indexOf("ShieldGuard.usingShield(bot)") > 0 && eat.indexOf("ShieldGuard.usingShield(bot)") < eat.indexOf("itemElapsed++;"),
                "the eating pass waits (no budget, no watchdog) while the shield is up");
        String build = read("action/BuildAction.java");
        assertTrue(build.split("ShieldGuard.usingShield\\(player\\)", -1).length - 1 >= 3, "no block placed nor item used on a block with the shield up");
        int sw = guard.indexOf("private static boolean switchMainHandAway(");
        String swBody = guard.substring(sw, guard.indexOf("static UseKind classifyUse(", sw));
        assertFalse(swBody.contains("ensureMeleeWeapon") || swBody.contains("equipFromSlot"), "a hotbar change only, never the backpack");
        assertTrue(read("task/GuardTask.java").contains("PaceRules.inputScale(false, bot.isUsingItem())"), "the guard's raw strafe is slowed too");
        String attack = read("task/AttackEntityTask.java");
        assertTrue(attack.indexOf("InteractAction.HANDS_BUSY.equals(lastRefusal)") > 0, "an attack command waits for the hands");
    }

    @Test
    void reactiveShieldOwnershipIsAnExplicitDeferredActionRatherThanAPlacementFailure() throws IOException {
        String build = read("action/BuildAction.java");
        int placeAt = build.indexOf("public static ActionResult placeBlockAt(");
        int firstProbe = build.indexOf("ActionResult lastFailure", placeAt);
        String placeAtPreamble = build.substring(placeAt, firstProbe);
        assertTrue(placeAtPreamble.contains("ShieldGuard.usingShield(player)")
                        && placeAtPreamble.contains("return ActionResult.IN_PROGRESS;"),
                "placeBlockAt defers before it can turn a busy hand into no_adjacent_block");
        assertTrue(build.contains("public static Use useItemOnHit")
                        && build.contains("InteractionResult.PASS, false, destination"),
                "already-aimed clicks are not sent through Baritone while the shield owns use");

        String farm = read("action/FarmAction.java");
        assertEquals(3, farm.split("return ActionResult.IN_PROGRESS;", -1).length - 1,
                "every farming item use propagates a deferred hand instead of inspecting a nonexistent world change");
        String buildTask = read("task/BuildTask.java");
        int buildMethod = buildTask.indexOf("private void build(");
        int deferredBuild = buildTask.indexOf("if (result.isInProgress())", buildMethod);
        int refreshedTargetClock = buildTask.indexOf("buildTargetTick = elapsed;", deferredBuild);
        int buildRetry = buildTask.indexOf("retryTicks++;", deferredBuild);
        assertTrue(deferredBuild >= 0 && refreshedTargetClock > deferredBuild && buildRetry > refreshedTargetClock,
                "a deferred block placement keeps its target alive and returns before consuming retry budget");
        String stations = read("task/PlaceStationsTask.java");
        int deferred = stations.indexOf("if (result.isInProgress())");
        int used = stations.indexOf("used.add(spot);");
        assertTrue(deferred >= 0 && deferred < used,
                "a deferred station placement keeps its candidate rather than blacklisting it");
        String path = read("pathfinding/PathExecutor.java");
        int pillar = path.indexOf("private ActionResult tickPillar(");
        assertTrue(path.indexOf("ShieldGuard.usingShield(player)", pillar) < path.indexOf("pillarAttemptTicks++", pillar),
                "the pillar path does not spend jump/replan budget during shield ownership");
    }

    @Test
    void deferredShieldPlacementsPauseEveryOwningWatchdogRatherThanTimingOut() throws IOException {
        String path = read("pathfinding/PathExecutor.java");
        int inProgress = path.indexOf("if (!result.isInProgress())");
        int shieldWait = path.indexOf("ShieldGuard.usingShield(pack.player())", inProgress);
        int resetStuck = path.indexOf("stuckTicks = 0;", shieldWait);
        int progress = path.indexOf("return checkProgress(pack, progressNode);", resetStuck);
        assertTrue(inProgress >= 0 && shieldWait > inProgress && resetStuck > shieldWait && progress > resetStuck,
                "every in-progress path node bypasses stuck/replan accounting while the shield owns use");

        String shelter = read("task/EmergencyShelterTask.java");
        int build = shelter.indexOf("private void tickBuild(AIPlayerEntity bot)");
        int buildWait = shelter.indexOf("if (result.isInProgress())", build);
        int buildShield = shelter.indexOf("ShieldGuard.usingShield(bot)", buildWait);
        int buildCredit = shelter.indexOf("creditBuildClock(1);", buildShield);
        int reseal = shelter.indexOf("private boolean resealObservedPressure");
        int exitWait = shelter.indexOf("if (result.isInProgress())", reseal);
        int exitCredit = shelter.indexOf("creditExitClock(1);", exitWait);
        assertTrue(buildWait > build && buildShield > buildWait && buildCredit > buildShield
                        && reseal > buildCredit && exitWait > reseal && exitCredit > exitWait,
                "shelter BUILD and pressured-exit reseals retain their exact target without aging their clocks");

        String service = read("task/MiningServiceTask.java");
        int depot = service.indexOf("private void placeMissionDepot");
        int depotWait = service.indexOf("if (placed.isInProgress())", depot);
        int depotCredit = service.indexOf("noteProgress();", depotWait);
        assertTrue(depotWait > depot && depotCredit > depotWait,
                "a shield-deferred mission depot does not trip MiningService's outer no-progress lease");

        String digDown = read("task/DigDownTask.java");
        int returnRepair = digDown.indexOf("private boolean tryRepairReturnSupport");
        int returnWait = digDown.indexOf("if (placed.isInProgress())", returnRepair);
        int returnCredit = digDown.indexOf("resetReturnProgressLease(bot.blockPosition());", returnWait);
        int waterSeal = digDown.indexOf("private boolean trySealWater");
        int waterWait = digDown.indexOf("if (sealed.isInProgress())", waterSeal);
        int waterCredit = digDown.indexOf("noteWorkProgress();", waterWait);
        int cavitySeal = digDown.indexOf("private boolean trySealOpenCavityLanding");
        int cavityWait = digDown.indexOf("if (sealed.isInProgress())", cavitySeal);
        int cavityCredit = digDown.indexOf("noteWorkProgress();", cavityWait);
        assertTrue(returnWait > returnRepair && returnCredit > returnWait
                        && waterWait > waterSeal && waterCredit > waterWait
                        && cavityWait > cavitySeal && cavityCredit > cavityWait,
                "DigDown pauses return and descent watchdogs only during a real shield handoff");

        String descend = read("task/DescendToYTask.java");
        int edge = descend.indexOf("ActionResult placed = BuildAction.placeBlock(");
        int edgeWait = descend.indexOf("if (placed.isInProgress())", edge);
        int edgeCredit = descend.indexOf("lastProgressTick = totalBudget();", edgeWait);
        int descendWater = descend.indexOf("private boolean trySealWater");
        int descendWaterWait = descend.indexOf("if (sealed.isInProgress())", descendWater);
        int descendWaterCredit = descend.indexOf("lastProgressTick = totalBudget();", descendWaterWait);
        int descendCavity = descend.indexOf("private boolean trySealOpenCavityLanding");
        int descendCavityWait = descend.indexOf("if (sealed.isInProgress())", descendCavity);
        int descendCavityCredit = descend.indexOf("lastProgressTick = totalBudget();", descendCavityWait);
        assertTrue(edgeWait > edge && edgeCredit > edgeWait
                        && descendWaterWait > descendWater && descendWaterCredit > descendWaterWait
                        && descendCavityWait > descendCavity && descendCavityCredit > descendCavityWait,
                "DescendToY preserves its edge and sealing transactions across reactive shield ownership");
    }
}
