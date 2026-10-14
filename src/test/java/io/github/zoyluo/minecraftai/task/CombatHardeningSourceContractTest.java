package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the combat hardening invariants to their source shape: DPS weapon choice, the strike-before-
 * shield loop, strike legality (reach and collider line of sight), the never-melee table, the
 * friendly-fire exclusion, the creeper shield fallback and the ranged option. The behaviour itself
 * is proved live by {@code CombatHardeningGameTests}; these guard against a refactor quietly undoing it.
 */
final class CombatHardeningSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void weaponChoiceRanksByDpsAndExcludesSpearsAndMaceOnPurpose() throws IOException {
        String equip = read("action/EquipAction.java");
        assertTrue(equip.contains("double damage = 1.0D + attackDamage(stack) + sharpnessBonus(stack);"));
        assertTrue(equip.contains("double speed = Math.max(0.1D, 4.0D + attackSpeedModifier(stack));"));
        assertTrue(equip.contains("return damage * speed;"),
                "the melee score must be (1 + damage) * (4 + speed), not per-hit damage");
        int qualified = equip.indexOf("public static boolean isQualifiedMeleeWeapon");
        String body = equip.substring(qualified, equip.indexOf("private static int swordPriority"));
        assertTrue(body.contains("ItemTags.SPEARS") && body.contains("Items.MACE")
                        && body.contains("DataComponents.PIERCING_WEAPON")
                        && body.contains("DataComponents.KINETIC_WEAPON"),
                "spears and the mace must stay out of automatic melee choice on purpose");
        assertTrue(equip.contains("swordPriority > bestSwordPriority"),
                "ties must still go to swords, then durability");
    }

    @Test
    void aReadySwingComesBeforeTheShieldSoAShieldHoldingBotStillAttacks() throws IOException {
        String combat = read("task/CombatTask.java");
        int strike = combat.indexOf("private void strike(AIPlayerEntity bot)");
        String body = combat.substring(strike, combat.indexOf("private void block(AIPlayerEntity bot)"));
        int ready = body.indexOf("bot.getAttackStrengthScale(0.5F) >= 0.95F");
        int swing = body.indexOf("CombatCore.strikeIfReady(bot, target)");
        int shield = body.indexOf("shouldBlock(bot)");
        assertTrue(ready >= 0 && swing > ready && shield > swing,
                "a ready swing must be attempted before the shield can be raised");

        int block = combat.indexOf("private void block(AIPlayerEntity bot)");
        String blockBody = combat.substring(block, combat.indexOf("private void reposition(AIPlayerEntity bot)"));
        assertTrue(blockBody.contains("bot.getActionPack().stopMovement();"),
                "the melee rhythm stands its ground with the shield up (moving with it is slowed elsewhere)");
        int swingCase = blockBody.indexOf("case SWING ->");
        assertTrue(blockBody.contains("bot.getAttackStrengthScale(0.5F) >= 0.95F") && swingCase > 0
                        && blockBody.substring(swingCase).contains("phase = Phase.STRIKE;"),
                "BLOCK must hand back to a swing (STRIKE, the next tick) the moment the cooldown completes and the aim is on the target");
    }

    @Test
    void everyStrikeIsGatedOnReachAndColliderLineOfSightAndFriends() throws IOException {
        String interact = read("action/InteractAction.java");
        int refusal = interact.indexOf("StrikeLegality.strikeRefusal(player, target)");
        int attack = interact.indexOf("player.attack(target)");
        assertTrue(refusal >= 0 && attack > refusal,
                "the legality gate must run before ServerPlayer.attack");

        String legality = read("action/StrikeLegality.java");
        assertTrue(legality.contains("isWithinEntityInteractionRange(target, 0.0D)"));
        assertTrue(legality.contains("ClipContext.Block.COLLIDER"));
        assertFalse(legality.contains("ClipContext.Block.OUTLINE"),
                "an outline ray would let grass and flowers occlude a strike");
        assertTrue(legality.contains("PlayerKind.isBot(player)"));
        assertTrue(legality.contains("ownerOf(bot)"));

        String core = read("task/CombatCore.java");
        assertTrue(core.contains("!isFriendly(bot, entity))"), "nearestTarget must skip friends");
        assertTrue(core.contains("InteractAction.attackEntity(bot, target).isSuccess()"));
        String tool = read("brain/ToolRegistry.java");
        assertTrue(tool.contains("StrikeLegality.strikeRefusal(bot, entity) == null")
                        && tool.contains("!StrikeLegality.isFriendly(bot, entity)"),
                "the attack_entity tool must use the same legality gate");
    }

    @Test
    void neverMeleeTableCoversBossesAndHeartBoundCreakingAndGuardHonoursIt() throws IOException {
        String core = read("task/CombatCore.java");
        int table = core.indexOf("static boolean isMeleeForbiddenThreat");
        String body = core.substring(table, core.indexOf("public static boolean isFriendly"));
        for (String forbidden : new String[]{"Creeper", "EnderMan", "isBossThreat(entity)", "Ghast",
                "Shulker", "Creaking creaking && creaking.isHeartBound()"}) {
            assertTrue(body.contains(forbidden), "missing from the never-melee table: " + forbidden);
        }
        assertTrue(core.contains("entity instanceof Enemy || entity instanceof Monster"),
                "hostility must use the Enemy interface, not only Monster");
        assertTrue(core.contains("neutral.isAngryAt(bot, bot.level())"),
                "neutral mobs are threats only when angry at the bot or after they hurt it");

        String guard = read("task/GuardTask.java");
        assertTrue(guard.contains("CombatCore.isMeleeForbiddenThreat(target)")
                && guard.contains("CombatCore.hostileTo(bot, target)")
                && guard.contains("LOST_SIGHT_LIMIT")
                && guard.contains("CombatCore.canStrikeNow(bot, target)"),
                "GuardTask must apply the forbid rule, a lost-sight exit and the strike-pose gate");
        String watcher = read("task/DangerWatcher.java");
        assertTrue(watcher.contains("return CombatCore.hostileTo(bot, entity);"));
        assertTrue(read("task/EvadeTask.java").contains("return ESCAPE_DISTANCE;"),
                "bosses use the ordinary Evade escape leg");
    }

    @Test
    void rangedOptionShieldFallbackAndFootingChecksStayWired() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        assertTrue(watcher.contains("PvpBotCombatBrain.INSTANCE.tick(server, bot, aggressor)"),
                "a factual hostile player must hand combat to the PvP BOT brain immediately");
        assertTrue(watcher.contains("List<LivingEntity> pvpPlayerAggressors = selectPvpPlayerAggressors")
                        && watcher.contains("selectPvpBridgeAggressor(")
                        && watcher.contains("isPvpBotPlayerAggressor"),
                "only admitted marked players may enter PvP BOT; ordinary hostile mobs stay on deterministic combat");
        int bridgeAttempt = watcher.indexOf("PvpBotCombatBrain.INSTANCE.tick(server, bot, aggressor)");
        int bridgePreempt = watcher.indexOf("TaskManager.INSTANCE.pauseFor(bot, \"pvp_bot_combat\")");
        int bridgeFallback = watcher.indexOf("pvpAggressors.remove(bot.getUUID());", bridgeAttempt);
        assertTrue(bridgePreempt > bridgeAttempt && bridgeFallback > bridgePreempt,
                "a task must be preempted only after PvP BOT accepts the target, before fallback runs");
        int pvpBranchEnd = watcher.indexOf("        } else if (PvpBotCombatBrain.INSTANCE.isActive(bot))", bridgeAttempt);
        assertFalse(watcher.substring(bridgeAttempt, pvpBranchEnd).contains("resumeFromPause"),
                "a rejected PvP BOT handoff must not pause/resume a route each danger scan");
        assertTrue(watcher.contains("task instanceof CombatTask || task instanceof EvadeTask"),
                "an established deterministic safety owner must not be retried through the optional PvP bridge each scan");
        assertTrue(read("task/HostileBotLedger.java").contains("CombatCore.hasLineOfSight(bot, player)"),
                "the foreign-PvP-bot combat handoff must require the companion's direct physical line of sight");
        assertFalse(watcher.contains("target_outside_leash"),
                "defensive combat must not impose the retired owner-distance leash");
        String combat = read("task/CombatTask.java");
        assertTrue(combat.contains("if (refusal != null)"),
                "the ranged loop must hold a ready weapon whenever the consolidated shot-legality check refuses it");
        assertTrue(combat.contains("StrikeLegality.shotRefusal(bot, target, RangedWeapon.shapeOf("),
                "a ranged weapon must still verify a physical line to its target before it fires");
        String legality = read("action/StrikeLegality.java");
        String shotRefusal = legality.substring(legality.indexOf("public static String shotRefusal"));
        assertFalse(shotRefusal.contains("friendlyOnLineOfFire") || shotRefusal.contains("friendly_on_line_of_fire"),
                "allies are passed through by projectile collision, not treated as a blocked shot");
        assertTrue(combat.contains("CombatCore.safeStrafeInput("),
                "the REPOSITION strafe needs a footing check");
        assertTrue(read("task/ShieldGuard.java").contains("\"shooter_draw\""));

        String creeper = read("task/CreeperDefenseTask.java");
        assertTrue(creeper.contains("SHIELD") && creeper.contains("shouldRaiseShield")
                        && creeper.contains("EquipAction.hasShield(bot)"),
                "the creeper shield fallback must exist and only fire with a shield");
    }

    @Test
    void escortFollowKeepsAnOrdinaryFactualAggressorOutOfBothCombatHandoffs() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        int keep = watcher.indexOf("boolean followKeepsCurrentThreat = threat");
        int bridge = watcher.indexOf("PvpBotCombatBrain.INSTANCE.available()", keep);
        int bridgeEnd = watcher.indexOf("        } else if (PvpBotCombatBrain.INSTANCE.isActive(bot))", bridge);
        int dispatch = watcher.indexOf("if (threat.isPresent())", bridgeEnd);
        assertTrue(keep >= 0 && bridge > keep && bridgeEnd > bridge && dispatch > bridgeEnd,
                "the shared escort decision must be computed before both combat handoffs");
        assertTrue(watcher.substring(bridge, bridgeEnd).contains("&& !followKeepsCurrentThreat"),
                "an optional PvP bridge must not pause a follow whose escort owns ordinary aggression");
        assertTrue(watcher.substring(dispatch).contains("&& !followKeepsCurrentThreat) {"),
                "deterministic combat must not let an aggro marker bypass the escort stance");
        assertFalse(watcher.contains("aggroTarget.isPresent() || !followKeepsThreat"),
                "a factual aggressor is a threat signal, not an exception to escort ownership");
        int followGate = watcher.indexOf("private boolean followKeepsThreat");
        assertTrue(followGate > dispatch);
        String escortGate = watcher.substring(followGate, watcher.indexOf("\n    }", followGate));
        assertTrue(escortGate.contains("!followMayRetainOrdinaryHostile(bot, top.entity())"),
                "follow ownership must use the ordinary-melee retention policy");
        int retention = watcher.indexOf("private static boolean followMayRetainOrdinaryHostile");
        assertTrue(retention > followGate);
        String retentionPolicy = watcher.substring(retention, watcher.indexOf("\n    }", retention));
        String escort = read("task/FollowEscort.java");
        assertTrue(retentionPolicy.contains("CombatCore.isDrawnRangedForeignBot(entity)")
                        && retentionPolicy.contains("CombatCore.isMeleeForbiddenThreat(entity)")
                        && retentionPolicy.contains("bot.getHealth() <= MinecraftAiConfig.get().combat().retreatHp()")
                        && retentionPolicy.contains("shouldStartLastResortShelter(bot, hostileThreat(entity))")
                        && !retentionPolicy.contains("FollowEscort.canEngage")
                        && !retentionPolicy.contains("ObservableWorldQuery.canNoticeCreature")
                        && !retentionPolicy.contains("CombatCore.hasLineOfSight"),
                "task ownership must retain ordinary melee pressure independently of immediate strike acquisition");
        assertTrue(escort.contains("bot.distanceTo(entity) <= ENGAGED_RANGE")
                        && escort.contains("CombatCore.isMeleeForbiddenThreat(entity)")
                        && escort.contains("ObservableWorldQuery.canNoticeCreature(bot, entity)")
                        && escort.contains("CombatCore.hasLineOfSight(bot, entity)"),
                "escort swings still require nearby, noticed, physically visible melee targets");
    }

    @Test
    void ordinaryMeleeCannotMaskASimultaneousThreatThatMustEscalateFollow() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        int active = watcher.indexOf("Optional<Task> active = TaskManager.INSTANCE.getActive(bot);");
        int promotion = watcher.indexOf("threat = promoteFollowEscalationThreat", active);
        int shelterEpisode = watcher.indexOf("refreshShelterEpisode(bot, hostilePressure)", active);
        assertTrue(active >= 0 && promotion > active && shelterEpisode > promotion,
                "an escalation candidate must replace ordinary escort pressure before every safety/dispatch branch");

        int helper = watcher.indexOf("private static Optional<Threat> promoteFollowEscalationThreat");
        int hostileFactory = watcher.indexOf("private static Threat hostileThreat", helper);
        assertTrue(helper > shelterEpisode && hostileFactory > helper);
        String promotionHelper = watcher.substring(helper, hostileFactory);
        assertTrue(promotionHelper.contains("aggroTarget.filter(candidate -> !candidates.contains(candidate)).ifPresent(candidates::add)")
                        && promotionHelper.contains("pvpPlayerAggressors.stream()")
                        && promotionHelper.contains(".filter(candidate -> !candidates.contains(candidate))")
                        && promotionHelper.contains(".filter(entity -> !followMayRetainOrdinaryHostile(bot, entity))"),
                "the promotion must include the nearest factual aggressor and every admitted PvP aggressor, then exclude only pressure that follow may retain");
        assertTrue(promotionHelper.contains("entity instanceof Creeper || CombatCore.isBossThreat(entity)")
                        && promotionHelper.contains("CombatCore.isMeleeForbiddenThreat(entity) || CombatCore.isLongRangeDanger(entity)")
                        && promotionHelper.contains("thenComparingDouble(bot::distanceTo)"),
                "unescortable hazards must select creeper/boss first, then evade/ranged threats, then distance");

        int keep = watcher.indexOf("private boolean followKeepsThreat");
        String keepHelper = watcher.substring(keep, watcher.indexOf("\n    }", keep));
        assertTrue(keepHelper.contains("!followMayRetainOrdinaryHostile(bot, top.entity())"),
                "single- and multi-threat escort ownership must use one retention predicate");
        int selectPvp = watcher.indexOf("private static List<LivingEntity> selectPvpPlayerAggressors");
        assertTrue(selectPvp > helper);
        int bridgeSelector = watcher.indexOf("private static Optional<LivingEntity> selectPvpBridgeAggressor", selectPvp);
        assertTrue(bridgeSelector > selectPvp);
        String pvpSelector = watcher.substring(selectPvp, bridgeSelector);
        assertTrue(pvpSelector.contains("snapshot.candidates()")
                        && pvpSelector.contains("retained.filter(candidate -> !candidates.contains(candidate)).ifPresent(candidates::add)")
                        && pvpSelector.contains(".filter(DangerWatcher::isPvpBotPlayerAggressor)")
                        && pvpSelector.contains(".toList()")
                        && !pvpSelector.contains(".min("),
                "all admitted marked PvP targets survive the first selection rather than being reduced to the nearest one");
        String bridgeHelper = watcher.substring(bridgeSelector, watcher.indexOf("\n    }", bridgeSelector));
        assertTrue(bridgeHelper.contains("threat.map(Threat::entity)")
                        && bridgeHelper.contains(".filter(candidates::contains)")
                        && bridgeHelper.contains("boolean escortFollow = isEscortFollow(bot, active)")
                        && bridgeHelper.contains("!followMayRetainOrdinaryHostile(bot, candidate)")
                        && bridgeHelper.contains("if (escortFollow) {\n            return candidates.stream()"),
                "the bridge keeps a promoted PvP threat and never selects a retained ordinary marked player while land escort is active");
    }

    @Test
    void vanillaAggroMustBePerceivedAndHaveMossOwnOmnidirectionalPhysicalLineBeforeItCanDispatchCombat() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        int aggro = watcher.indexOf("ImmediateAggressors aggressorSnapshot = immediateAggressors(bot);");
        int fallback = watcher.indexOf("if (threat.isEmpty() && aggroTarget.isPresent())", aggro);
        assertTrue(aggro >= 0 && fallback > aggro);
        String aggroSelection = watcher.substring(aggro, fallback);
        assertTrue(aggroSelection.contains("nearestAdmittedAggressor(bot, aggressorSnapshot)\n                .or(() -> retainedPvpAggressor)")
                        && aggroSelection.contains("selectPvpPlayerAggressors(\n                bot, aggressorSnapshot, retainedPvpAggressor)"),
                "an invalid raw mob must be rejected before it can suppress the retained PvP fallback");
        assertTrue(aggroSelection.contains("pvpAggressors.get(bot.getUUID())")
                        && aggroSelection.contains(".filter(candidate -> isAdmittedCombatAggressor(bot, candidate))"),
                "the retained PvP target needs the same physical-line admission before it regains control");

        int admission = watcher.indexOf("private static boolean isAdmittedCombatAggressor");
        assertTrue(admission > fallback);
        String admissionHelper = watcher.substring(admission, watcher.indexOf("\n    }", admission));
        assertTrue(admissionHelper.contains("!(candidate instanceof Mob) || SharedVision.seenByBotOrOwner(bot, candidate)"),
                "a vanilla target alone must not bypass the companion's normal perception boundary");
        assertTrue(admissionHelper.contains("hasOmnidirectionalCombatLine(bot, candidate)"),
                "a perceived raw Mob target behind terrain must not start local combat or preempt follow");

        int lineCheck = watcher.indexOf("private static boolean hasOmnidirectionalCombatLine");
        assertTrue(lineCheck > fallback);
        String helper = watcher.substring(lineCheck, watcher.indexOf("\n    }", lineCheck));
        assertTrue(helper.contains("CombatCore.hasLineOfSight(bot, aggressor)"),
                "the gate is Moss's collider ray, not owner vision or perception");
        assertFalse(helper.contains("canNoticeCreature") || helper.contains("SharedVision")
                        || helper.contains("look") || helper.contains("facing"),
                "the combat line must be 360 degrees, with no FOV/facing requirement");
    }

    @Test
    void pvpBridgeForcesTheAcceptedPlayerTargetSoTheWrapperCanAuthorizeTheStrike() throws IOException {
        String bridge = read("task/PvpBotCombatBrain.java");
        assertTrue(bridge.contains("current.setTarget.invoke(null, name, desiredTarget)"));
        assertTrue(bridge.contains("current.forcedTargetName.get(state)"));
        assertTrue(bridge.contains("Method setTarget = combat.getMethod(\"setTarget\", String.class, String.class)"));
        assertTrue(bridge.contains("!(aggressor instanceof ServerPlayer)"),
                "the optional PvP controller must decline mobs so they cannot pause a companion without attacking");
    }

    @Test
    void combatMovesByInputsNeverByTeleportSteps() throws IOException {
        String combat = read("task/CombatTask.java");
        String creeper = read("task/CreeperDefenseTask.java");
        for (String source : new String[]{combat, creeper}) {
            assertFalse(source.contains("FakePlayerMotion") || source.contains("stepToStandable")
                            || source.contains("teleportTo("),
                    "combat and creeper defense must move by movement inputs, never a teleport step");
        }
        String core = read("task/CombatCore.java");
        assertTrue(core.contains("public static StepStatus stepByInput(")
                        && core.contains("STEP_TIMEOUT_TICKS")
                        && core.contains("stepHazard(")
                        && core.contains("STEP_SPRINT_FOOD_FLOOR"),
                "the walked step must re-prove its landing every tick, time out and never sprint when hungry");
        int stepBody = core.indexOf("public static StepStatus stepByInput(");
        int stepEnd = core.indexOf("public static void cancelStep", stepBody);
        assertFalse(core.substring(stepBody, stepEnd).contains("teleport")
                        || core.substring(stepBody, stepEnd).contains("setDeltaMovement"),
                "a walked step must not move or re-velocity the bot itself");
        assertTrue(combat.contains("rangedSuppressedUntil"),
                "a genuinely blocked enemy line must latch ranged out of the plan, not loop into RANGED");
        assertTrue(core.contains("enderman.getTarget() == bot")
                        && !core.contains("mob.getTarget() == bot ||"),
                "Mob.getTarget() stays only the legacy Enderman rule");
    }

    @Test
    void walkedStepsScaleTheirInputsByTheItemUseSlowdownAndAllowTheLongerWalk() throws IOException {
        String core = read("task/CombatCore.java");
        int stepBody = core.indexOf("public static StepStatus stepByInput(");
        int stepEnd = core.indexOf("public static void cancelStep", stepBody);
        String body = core.substring(stepBody, stepEnd);
        assertTrue(core.contains("STEP_ITEM_USE_SLOWDOWN = 0.2F"), "vanilla's item-use slowdown is 0.2");
        assertTrue(body.contains("bot.isUsingItem()") && body.contains("forward * scale") && body.contains("left * scale"),
                "a drawn bow or raised shield scales the walk inputs like a player's");
        assertTrue(body.contains("STEP_TIMEOUT_BUDGET") && body.contains("budgetSpent += usingItem ? 1 : STEP_FULL_TICK_COST"),
                "the step timeout is a budget spent per tick, the slowed ticks costing a fifth (not a sticky flag)");
    }


    @Test
    void everyRangedExitCancelsTheDrawAndOnlyTheCheckedShotsFire() throws IOException {
        String combat = read("task/CombatTask.java");
        // releasing a charged bow FIRES it (and a loaded crossbow is fired by a use); only RangedWeapon.shoot, called after the
        // two line-of-fire-checked shots (ranged and the peek), may do it, and the shield/reactive-shield releases are the only
        // releaseUsingItem calls of the task: every give-up and abort path cancels with stopUsingItem().
        for (String method : new String[]{"private void giveUpRangedForBlockedShot(", "private void coverHide(",
                "private boolean settleDeadPrimary("}) {
            int start = combat.indexOf(method);
            assertTrue(start > 0, method);
            int end = combat.indexOf("\n    }\n", start);
            assertFalse(combat.substring(start, end).contains("releaseUsingItem"),
                    method + " must cancel a drawn bow, not fire it");
        }
        int peek = combat.indexOf("private void coverPeek(");
        String peekBody = combat.substring(peek, combat.indexOf("private boolean shouldBlock("));
        assertFalse(peekBody.contains("releaseUsingItem"), "the peek fires only through RangedWeapon.shoot");
        assertTrue(peekBody.split(java.util.regex.Pattern.quote("RangedWeapon.shoot(bot)"), -1).length - 1 == 1,
                "the peek may shoot exactly once: the shot that passed the line-of-fire check");
        assertTrue(peekBody.indexOf("RangedWeapon.shoot(bot)") > peekBody.indexOf("} else if (refusal == null) {"),
                "that shot sits in the refusal-free branch");
        int ranged = combat.indexOf("private void ranged(");
        String rangedBody = combat.substring(ranged, combat.indexOf("private void giveUpRangedForBlockedShot("));
        assertTrue(rangedBody.indexOf("RangedWeapon.shoot(bot)") > rangedBody.indexOf("StrikeLegality.shotRefusal("),
                "ranged() shoots only after the line-of-fire check");
        String weapon = read("action/RangedWeapon.java");
        assertTrue(weapon.contains("public static boolean shoot(") && weapon.contains("if (!isReadyToShoot(bot))"),
                "shoot never fires a bow before its full draw nor a crossbow that is not loaded");
        assertTrue(weapon.contains("public static void cancel(") && weapon.contains("bot.stopUsingItem();"),
                "cancel is stopUsingItem, never a release");
        assertTrue(read("action/ActionPack.java").contains("player.stopUsingItem();")
                        && !read("action/ActionPack.java").contains("player.releaseUsingItem();"),
                "stopAll is an interruption: it must cancel a drawn bow, not fire it");
    }
    @Test
    void coverPhasesCountBlockedEnemyLinesAndLeaveThroughTheRangedGiveUpPath() throws IOException {
        String combat = read("task/CombatTask.java");
        assertTrue(combat.contains("BLOCKED_PEEK_LIMIT") && combat.contains("sightBlockedPeeks"));
        int give = combat.indexOf("private void giveUpRangedForBlockedShot(");
        assertTrue(give > 0 && combat.substring(give).contains("rangedSuppressedUntil = elapsed + RANGED_SUPPRESS_TICKS"));
        int ranged = combat.indexOf("private void ranged(");
        assertTrue(combat.substring(ranged, combat.indexOf("private void giveUpRangedForBlockedShot(")).contains("giveUpRangedForBlockedShot(bot, refusal)"),
                "ranged() gives the weapon up through the shared path");
        int peek = combat.indexOf("private void coverPeek(");
        assertTrue(combat.substring(peek, combat.indexOf("private boolean shouldBlock(")).contains("giveUpRangedForBlockedShot(bot, refusal)"),
                "a peek whose enemy line stays blocked gives the weapon up through the same path");
        int hide = combat.indexOf("private void coverHide(");
        assertTrue(combat.substring(hide, combat.indexOf("private void coverPeek(")).contains("!shouldUseRanged(bot)"),
                "cover-hide re-checks that ranged is still in the plan");
    }

    /**
     * R1: the bot-kind test of isFriendly is now "a foreign bot is friendly unless a visible marked aggressor" (the literal
     * PlayerKind.isBot(player) pin above stays), the owner is tested BEFORE the bot kind (a GameTest owner is a mock on an
     * EmbeddedChannel), hostileTo asks the ledger right after the friendly check, and the owner's eyes only ever nominate
     * (StrikeLegality.strikeRefusal is unchanged: reach and collider line of sight are still the bot's own).
     */
    @Test
    void foreignBotsAreFriendlyUnlessAVisibleMarkedAggressorAndOwnerVisionOnlyNominates() throws IOException {
        String legality = read("action/StrikeLegality.java");
        int friendly = legality.indexOf("public static boolean isFriendly");
        String body = legality.substring(friendly, legality.indexOf("public static boolean isWithinReach"));
        int ownerTest = body.indexOf("ownerOf(bot)");
        int botTest = body.indexOf("PlayerKind.isBot(player)");
        assertTrue(ownerTest > 0 && botTest > ownerTest, "the owner is tested before the bot kind");
        assertTrue(body.contains("entity instanceof AIPlayerEntity") && body.contains("HostileBotLedger.isVisibleAggressor(bot, player)"),
                "a Minecraft-AI bot is always friendly, a foreign bot only until it is a visible marked aggressor");
        assertTrue(body.contains("if (!PlayerKind.isBot(player))"),
                "real human players are allies of every Minecraft-AI companion");
        assertTrue(body.contains("isAnyBotOwner(player.getUUID())"), "a human that owns a bot is not a foreign bot");
        String strike = legality.substring(legality.indexOf("public static String strikeRefusal"),
                legality.indexOf("public static String shotRefusal"));
        assertFalse(strike.contains("SharedVision") && strike.contains("ownerSees"),
                "the owner's sight must never permit a strike: strikeRefusal stays on the bot's own reach and line of sight");

        String core = read("task/CombatCore.java");
        int hostile = core.indexOf("public static boolean hostileTo");
        String hostileBody = core.substring(hostile, core.indexOf("public static boolean hasHurtBotOrOwner"));
        assertTrue(hostileBody.indexOf("isFriendly(bot, entity)") < hostileBody.indexOf("HostileBotLedger.isVisibleAggressor(bot, foreign)")
                        && hostileBody.indexOf("HostileBotLedger.isVisibleAggressor(bot, foreign)") < hostileBody.indexOf("hasHurtBotOrOwner(bot, entity)"),
                "hostileTo asks the ledger right after the friendly check");
        assertTrue(core.contains("AIPlayerManager.INSTANCE.anySiblingMatches(ownerId.get(), bot,"), "hasHurtBotOrOwner also counts sibling bots");

        String vision = read("task/SharedVision.java");
        assertTrue(vision.contains("if (!(entity instanceof ServerPlayer) || entity == bot)"),
                "owner vision is used for players only, never for mobs");
        String watcher = read("task/DangerWatcher.java");
        assertTrue(watcher.contains("SharedVision.seenByBotOrOwner(bot, entity)") && watcher.contains("CombatCore.hasLineOfSightOrOwnerSees(bot, mob)"),
                "the threat pressure list and the reachability test accept an owner-seen foreign bot");
        String mod = read("MinecraftAiMod.java");
        assertTrue(mod.contains("HostileBotLedger.install()") && mod.contains("HostileBotIntent.tick(server)")
                        && mod.contains("HostileBotLedger.clearAll()"),
                "the ledger handlers, the once-per-tick intent sampler and the server-stop clear must stay wired");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
