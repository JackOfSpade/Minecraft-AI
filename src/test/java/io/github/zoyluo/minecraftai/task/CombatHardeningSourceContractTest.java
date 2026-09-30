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
                "the shield is only ever up with movement stopped");
        assertTrue(blockBody.contains("bot.getAttackStrengthScale(0.5F) >= 0.95F")
                        && blockBody.contains("strike(bot);"),
                "BLOCK must hand back to a swing the moment the cooldown completes");
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
    void neverMeleeTableCoversWardenWitherAndHeartBoundCreakingAndGuardHonoursIt() throws IOException {
        String core = read("task/CombatCore.java");
        int table = core.indexOf("static boolean isMeleeForbiddenThreat");
        String body = core.substring(table, core.indexOf("public static boolean isFriendly"));
        for (String forbidden : new String[]{"Creeper", "EnderMan", "Warden", "WitherBoss", "Ghast",
                "Shulker", "EnderDragon", "Creaking creaking && creaking.isHeartBound()"}) {
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
        assertTrue(read("task/EvadeTask.java").contains("CombatCore.WARDEN_ESCAPE_DISTANCE"),
                "a warden flight must clear the sonic boom range");
    }

    @Test
    void rangedOptionShieldFallbackAndFootingChecksStayWired() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        assertTrue(watcher.contains("CombatTask.canShootFromWhereItStands(bot, target)"),
                "an out-of-leash shootable hostile must not be held off");
        String combat = read("task/CombatTask.java");
        assertTrue(combat.contains("&& !canShootFromWhereItStands(bot, target)"));
        assertTrue(combat.contains("StrikeLegality.shotRefusal(bot, target, RangedWeapon.shapeOf("),
                "a ranged weapon must never be shot into the owner or another bot (or without a clear line)");
        assertTrue(read("action/StrikeLegality.java").contains("\"friendly_on_line_of_fire\""),
                "the shot refusal must name a friend on the line of fire");
        assertTrue(combat.contains("CombatCore.safeStrafeInput("),
                "the REPOSITION strafe needs a footing check");
        assertTrue(combat.contains("\"shooter_draw\""));

        String creeper = read("task/CreeperDefenseTask.java");
        assertTrue(creeper.contains("SHIELD") && creeper.contains("shouldRaiseShield")
                        && creeper.contains("EquipAction.hasShield(bot)"),
                "the creeper shield fallback must exist and only fire with a shield");
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
                "a friend on the line of fire must latch ranged out of the plan, not loop into RANGED");
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
    void coverPhasesCountFriendlyBlockedPeeksAndLeaveThroughTheRangedGiveUpPath() throws IOException {
        String combat = read("task/CombatTask.java");
        assertTrue(combat.contains("FRIENDLY_PEEK_LIMIT") && combat.contains("friendlyBlockedPeeks"));
        int give = combat.indexOf("private void giveUpRangedForBlockedShot(");
        assertTrue(give > 0 && combat.substring(give).contains("rangedSuppressedUntil = elapsed + RANGED_SUPPRESS_TICKS"));
        int ranged = combat.indexOf("private void ranged(");
        assertTrue(combat.substring(ranged, combat.indexOf("private void giveUpRangedForBlockedShot(")).contains("giveUpRangedForBlockedShot(bot, refusal)"),
                "ranged() gives the weapon up through the shared path");
        int peek = combat.indexOf("private void coverPeek(");
        assertTrue(combat.substring(peek, combat.indexOf("private boolean shouldBlock(")).contains("giveUpRangedForBlockedShot(bot, refusal)"),
                "a peek that a friend keeps blocking gives the weapon up through the same path");
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
        assertTrue(body.contains("isAnyBotOwner(player.getUUID())"), "a human that owns a bot is not a foreign bot");
        String strike = legality.substring(legality.indexOf("public static String strikeRefusal"),
                legality.indexOf("public static boolean friendlyOnLineOfFire"));
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
