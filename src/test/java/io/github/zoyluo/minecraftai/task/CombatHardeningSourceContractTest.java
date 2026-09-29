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
        assertTrue(combat.contains("StrikeLegality.friendlyOnLineOfFire(bot, target)"),
                "a bow must never be released into the owner or another bot");
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
        assertTrue(combat.contains("bowSuppressedUntil"),
                "a friend on the line of fire must latch the bow out of the plan, not loop into RANGED");
        assertTrue(core.contains("enderman.getTarget() == bot")
                        && !core.contains("mob.getTarget() == bot ||"),
                "Mob.getTarget() stays only the legacy Enderman rule");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
