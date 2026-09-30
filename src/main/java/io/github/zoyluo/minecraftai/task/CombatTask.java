package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.EatAction;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.HumanAim;
import io.github.zoyluo.minecraftai.action.RangedWeapon;
import io.github.zoyluo.minecraftai.action.PaceRules;
import io.github.zoyluo.minecraftai.action.ShieldRules;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CombatTask extends AbstractTask {
    private enum Phase {
        ACQUIRE,
        APPROACH,
        RANGED,
        STRIKE,
        BLOCK,
        REPOSITION,
        RETREAT,
        HEAL,
        /** Placing a one-wide, two-tall cover column between the bot and a ranged threat. */
        COVER_BUILD,
        /** Standing behind an already-built column, drawing the bow out of sight. */
        COVER_HIDE,
        /** Stepping clear of the column just long enough to release the drawn shot, then hiding. */
        COVER_PEEK
    }

    private static final int SEARCH_RANGE = 20;
    private static final double RANGED_MELEE_SWITCH_DISTANCE = CombatCore.ATTACK_RANGE * 1.5D;
    private static final int BLOCK_TICKS = 12;
    private static final int HEAL_WAIT_TICKS = 200;
    private static final double HEAL_SAFE_DISTANCE = CombatCore.ATTACK_RANGE + 2.0D;
    // Vanilla Creeper ignition only backs off beyond seven blocks (or after LOS is broken).
    private static final double CREEPER_HEAL_SAFE_DISTANCE = 8.0D;
    private static final int RETREAT_STEP_DISTANCE = 6;
    private static final int LOST_SIGHT_LIMIT = 50; // Target blocked by a wall (no line of sight) for 2.5s straight -> end combat instead of foolishly fighting until timeout
    private static final int DEFENSIVE_MAX_VERTICAL_DROP = 2;
    private static final double DEFENSIVE_MAX_HORIZONTAL_DISTANCE = 8.0D;
    /** A shot the line of fire forbids (a friend on it, no clear sight) holds the drawn or loaded weapon this long before falling back to melee. */
    private static final int BLOCKED_SHOT_HOLD_LIMIT = 60;
    /** After a shot stayed blocked this long, the ranged weapon stays out of the plan this many ticks. */
    private static final int RANGED_SUPPRESS_TICKS = 200;
    /** Peek cycles in a row whose shot a friend on the line of fire (or the lack of a clear line) held back, before the ranged weapon is given up. */
    private static final int FRIENDLY_PEEK_LIMIT = 3;
    /** A warden's flight must clear its sonic boom range, not the generic six-block retreat step. */
    private static final int WARDEN_RETREAT_STEP_DISTANCE = CombatCore.WARDEN_ESCAPE_DISTANCE;
    /** "there are other ranged enemies" -- at least one besides whichever one is currently targeted. */
    private static final int PEEKABOO_MIN_RANGED_THREATS = 2;
    private static final double PEEKABOO_SCAN_RANGE = 24.0D;
    private static final int PEEKABOO_BUILD_RETRY_LIMIT = 3;
    /**
     * Ticks the bot holds its aim once it stands in the exposed cell before it releases the shot.
     * The peek out and the duck back are real walks (each bounded by CombatCore.STEP_TIMEOUT_TICKS),
     * so this only has to cover the aim settling after the walk, not the movement itself.
     */
    private static final int PEEKABOO_EXPOSE_TICKS = 3;
    private static final int PEEKABOO_RETRY_COOLDOWN_TICKS = 100;
    /** A peek stays exposed at most this many ticks waiting for the human-speed aim to settle on the target. */
    private static final int PEEKABOO_EXPOSE_LIMIT_TICKS = 40;

    private final EntityType<?> targetType;
    private final int targetKills;
    private final float retreatHpThreshold;
    private final LivingEntity fixedDefensiveTarget;
    private final BlockPos defensiveAnchor;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private int kills;
    private int repositionTicks;
    private int blockTicks;
    private int healTicks;
    private boolean eating;
    /** Owns only the temporary arrow/offhand swap for one bow engagement. */
    private EquipAction.RangedLoadout rangedLoadout;
    /** Transient safety pressure; never owns primary kill credit. */
    private LivingEntity retreatThreat;
    private BlockPos retreatDestination;
    private int lostSightTicks; // Consecutive tick count with no line of sight to the target (blocked by a wall)
    private int blockedShotTicks;
    /** Consecutive peeks that ended with a friend on the line of fire (the drawn bow was kept, not shot). */
    private int friendlyBlockedPeeks;
    /** Consecutive peeks that ended with no clear line to the target ("no_line_of_sight": the drawn weapon was kept, not shot). */
    private int sightBlockedPeeks;
    /** Consecutive strikes whose crosshair held the target's (non-hostile or unreachable) mount instead of the target. */
    private int mountBlockedTicks;
    private BlockPos peekHideSpot;
    private BlockPos peekCoverFeet;
    private BlockPos peekExposeSpot;
    private int peekBuildAttempts;
    private int peekCycleTicks;
    private int nextPeekabooAttemptElapsed;
    /** Where a peekaboo cycle is: walking out, holding the aim exposed, or walking back behind cover. */
    private enum PeekStage {
        OUT,
        EXPOSED,
        BACK
    }

    private PeekStage peekStage = PeekStage.OUT;
    private int peekExposedTicks;
    /** The walked step of the current peekaboo stage (or of a return to the hide spot), if one is under way. */
    private CombatCore.InputStep peekStep;
    /** Ranged shooters counted in sight while last exposed (the cover column hides them the rest of the time). */
    private int peekThreatsAtLastPeek;
    /** The bow stays out of the plan until this elapsed tick (a friend kept blocking the line of fire). */
    private int rangedSuppressedUntil;

    public CombatTask(EntityType<?> targetType, int targetKills, float retreatHpThreshold) {
        this(targetType, targetKills, retreatHpThreshold, null, null);
    }

    private CombatTask(EntityType<?> targetType,
                       int targetKills,
                       float retreatHpThreshold,
                       LivingEntity fixedDefensiveTarget,
                       BlockPos defensiveAnchor) {
        this.targetType = targetType;
        this.targetKills = Math.max(1, targetKills);
        this.retreatHpThreshold = retreatHpThreshold;
        this.fixedDefensiveTarget = fixedDefensiveTarget;
        this.defensiveAnchor = defensiveAnchor == null ? null : defensiveAnchor.immutable();
    }

    public static CombatTask defensive(LivingEntity threat,
                                       float retreatHpThreshold,
                                       BlockPos anchor) {
        if (threat == null || anchor == null) {
            throw new IllegalArgumentException("defensive_combat_requires_target_and_anchor");
        }
        return new CombatTask(threat.getType(), 1, retreatHpThreshold, threat, anchor);
    }

    @Override
    public String name() {
        return "combat";
    }

    /** True while the bow is latched out of the plan because a friend kept blocking the line of fire. */
    boolean isRangedSuppressed() {
        return rangedSuppressedUntil > 0 && elapsed < rangedSuppressedUntil;
    }

    @Override
    public String describe() {
        return "Attacking " + BuiltInRegistries.ENTITY_TYPE.getKey(targetType) + " " + kills + "/" + targetKills + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) kills / targetKills);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        CombatCore.equipMelee(bot);
        EquipAction.equipShieldOffhand(bot);
        phase = Phase.ACQUIRE;
        peekHideSpot = null;
        peekCoverFeet = null;
        peekExposeSpot = null;
        peekBuildAttempts = 0;
        peekCycleTicks = 0;
        nextPeekabooAttemptElapsed = 0;
        peekStage = PeekStage.OUT;
        peekExposedTicks = 0;
        peekStep = null;
        rangedSuppressedUntil = 0;
        peekThreatsAtLastPeek = 0;
        blockedShotTicks = 0;
        friendlyBlockedPeeks = 0;
        sightBlockedPeeks = 0;
        mountBlockedTicks = 0;
    }

    /**
     * Combat logging wrapper: records phase transitions, target changes and kill credit (with the bot's hp) so a
     * fight can be reconstructed from the log. Purely observational -- all behaviour lives in {@link #tickCombat}.
     */
    @Override
    protected void onTick(AIPlayerEntity bot) {
        Phase phaseBefore = phase;
        int killsBefore = kills;
        LivingEntity targetBefore = target;
        try {
            tickCombat(bot);
        } finally {
            logCombatDelta(bot, phaseBefore, killsBefore, targetBefore);
        }
    }

    private void logCombatDelta(AIPlayerEntity bot, Phase phaseBefore, int killsBefore, LivingEntity targetBefore) {
        try {
            if (target != targetBefore && target != null) {
                BotLog.danger(bot, "combat_target",
                        "type", target.getType().toString(),
                        "id", target.getId(),
                        "target_hp", target.getHealth(),
                        "dist", String.format(java.util.Locale.ROOT, "%.1f", bot.distanceTo(target)),
                        "hp", bot.getHealth());
            }
            if (phase != phaseBefore) {
                BotLog.danger(bot, "combat_phase",
                        "from", phaseBefore, "to", phase,
                        "kills", kills + "/" + targetKills,
                        "hp", bot.getHealth(),
                        "target_id", target == null ? -1 : target.getId());
            }
            if (kills != killsBefore) {
                BotLog.danger(bot, "combat_kill",
                        "kills", kills + "/" + targetKills,
                        "hp", bot.getHealth(),
                        "elapsed", elapsed);
            }
        } catch (RuntimeException ignored) {
            // Logging must never affect combat.
        }
    }

    private void tickCombat(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            finishRangedLoadout(bot);
            fail("combat_timeout");
            return;
        }
        if (peekStep != null && phase != Phase.COVER_PEEK && phase != Phase.COVER_HIDE) {
            // A walked cover step never outlives the cover phases (a retreat or a leash exit took over).
            cancelPeekStep(bot);
        }
        if (settleDeadPrimary(bot)) {
            if (state == TaskState.RUNNING && phase == Phase.RETREAT) {
                retreat(bot);
            }
            return;
        }
        if (!defensiveEngagementAllowed(bot)) {
            return;
        }
        // Target is blocked by a block and unreachable (behind a wall/tunnel) -> end combat, don't
        // foolishly keep swinging/chasing into empty air until timeout (observed bug: a blocked
        // hostile kept the bot stuck "in combat", interrupting normal mining). A momentary occlusion
        // doesn't count; only a sustained 2.5s of no line of sight ends it. Unreachable is inherently
        // safe, so use complete() to end cleanly and let the original task resume, instead of fail()
        // which would alarm the brain.
        boolean hidingOnPurpose = phase == Phase.COVER_BUILD
                || phase == Phase.COVER_HIDE || phase == Phase.COVER_PEEK;
        if (hidingOnPurpose) {
            // Peekaboo puts the bot's own column between it and the target: no sight is the plan.
            lostSightTicks = 0;
        } else if (target != null && target.isAlive() && !CombatCore.hasLineOfSightOrOwnerSees(bot, target)) {
            if (++lostSightTicks > LOST_SIGHT_LIMIT) {
                lostSightTicks = 0;
                finishRangedLoadout(bot);
                complete();
                return;
            }
        } else {
            lostSightTicks = 0;
        }
        if (target != null && target.isAlive()
                && bot.getHealth() <= retreatHpThreshold
                && phase != Phase.RETREAT && phase != Phase.HEAL) {
            beginRetreat(bot);
        }
        if (isMeleeOrientedPhase() && ShieldGuard.INSTANCE.holding(bot)) {
            // The shield guard has the use hand for a noticed, blockable threat: no striking and no mining, as for a player with the
            // use key down. An approach keeps walking and a reposition keeps strafing, both at the vanilla use-item slowdown; every
            // other phase stands.
            if (phase == Phase.APPROACH) {
                approach(bot);
            } else if (phase == Phase.REPOSITION) {
                reposition(bot);
            } else {
                bot.getActionPack().stopMovement();
            }
            return;
        }
        switch (phase) {
            case ACQUIRE -> acquire(bot);
            case APPROACH -> approach(bot);
            case RANGED -> ranged(bot);
            case STRIKE -> strike(bot);
            case BLOCK -> block(bot);
            case REPOSITION -> reposition(bot);
            case RETREAT -> retreat(bot);
            case HEAL -> heal(bot);
            case COVER_BUILD -> buildPeekabooCover(bot);
            case COVER_HIDE -> coverHide(bot);
            case COVER_PEEK -> coverPeek(bot);
        }
    }

    /**
     * "Melee mode" for the purposes of shield reactions: any phase where the bot is not already
     * mid-draw on a bow (the shield guard never takes the hand from a ranged exchange) and
     * is not already in a dedicated recovery phase.
     */
    private boolean isMeleeOrientedPhase() {
        return phase == Phase.ACQUIRE || phase == Phase.APPROACH || phase == Phase.STRIKE
                || phase == Phase.REPOSITION || phase == Phase.BLOCK;
    }

    /**
     * True while the fight is a ranged exchange (the bow or crossbow is out, drawn or loaded, with its arrow in the offhand): the
     * shooter keeps shooting, and {@link ShieldGuard} leaves the hands alone (decided per case, documented there).
     */
    boolean isRangedExchange() {
        return rangedLoadout != null || phase == Phase.RANGED || phase == Phase.COVER_BUILD
                || phase == Phase.COVER_HIDE || phase == Phase.COVER_PEEK;
    }

    /**
     * True while {@code entity} is this fight's target within striking reach in a melee phase: the melee rhythm ({@link #block}) owns
     * the shield against it (up between the bot's swings, down for each swing), so the shield guard's pre-emptive hold against a
     * shooter never freezes a melee exchange with that same shooter.
     */
    boolean meleeRhythmAgainst(AIPlayerEntity bot, LivingEntity entity) {
        return entity != null && entity == target && target.isAlive()
                && (phase == Phase.STRIKE || phase == Phase.BLOCK || phase == Phase.REPOSITION)
                && bot.distanceTo(target) <= CombatCore.ATTACK_RANGE + 1.0F;
    }

    /** A noticed hostile shooter that visibly has its ranged weapon up at the bot: see {@link ShieldGuard#drawingShooterAt}. */
    static LivingEntity nearbyDrawingShooter(AIPlayerEntity bot) {
        return ShieldGuard.drawingShooterAt(bot);
    }

    /** True when {@code shooter} has a bow (or crossbow) up and its head is aimed at {@code bot}: see {@link ShieldGuard#isDrawingBowAt}. */
    static boolean isDrawingBowAt(LivingEntity shooter, AIPlayerEntity bot) {
        return ShieldGuard.isDrawingBowAt(shooter, bot);
    }

    private void acquire(AIPlayerEntity bot) {
        target = fixedDefensiveTarget != null
                ? fixedDefensiveTarget.isAlive() ? fixedDefensiveTarget : null
                : CombatCore.nearestTarget(bot, targetType, SEARCH_RANGE).orElse(null);
        if (target == null) {
            if (kills > 0) {
                complete();
            } else {
                fail("no_target_in_range");
            }
            return;
        }
        if (!defensiveEngagementAllowed(bot)) {
            return;
        }
        if (CombatCore.isMeleeForbiddenThreat(target)) {
            beginRetreat(bot);
            retreat(bot);
            return;
        }
        if (phase == Phase.RETREAT) {
            retreat(bot);
            return;
        }
        // A newly assigned defensive task has not populated target during the generic onTick
        // health gate. Do not spend its first physical tick approaching a contact hostile.
        if (bot.getHealth() <= retreatHpThreshold) {
            beginRetreat(bot);
            retreat(bot);
            return;
        }
        chooseEngagement(bot);
    }

    private void approach(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire(bot);
            return;
        }
        CombatCore.lookAt(bot, target);
        boolean useBow = shouldUseRanged(bot);
        if (useBow) {
            beginRanged(bot);
            return;
        }
        // The distance boundary is deliberately broader than hit reach.  At or inside it, select
        // the best physical melee weapon before either closing the remaining gap or striking.
        finishRangedLoadout(bot);
        CombatCore.ensureMeleeWeapon(bot, target);
        // Contact means a legal strike pose (reach to the target's box and no wall between), not
        // just feet-distance: a mob on the far side of a window keeps being approached, never struck.
        if (CombatCore.canStrikeNow(bot, target)) {
            bot.getActionPack().stopAll();
            phase = Phase.STRIKE;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            startApproach(bot);
        }
    }

    private void ranged(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire(bot);
            return;
        }
        if (!shouldUseRanged(bot)) {
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot, target);
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        if (rangedLoadout == null) {
            beginRanged(bot);
            return;
        }
        if (!RangedWeapon.isRanged(bot.getMainHandItem())) {
            // Something else took the hand (a building block, a melee swap): take the ranged weapon back.
            beginRanged(bot);
            return;
        }
        // The aim turns at human speed and a shot only leaves once it is on target (never an instant spin-and-shoot).
        boolean aimed = CombatCore.aimForShot(bot, target);
        // One vanilla item-use cycle for both weapons: draw (getTicksUsingItem() is Minecraft's own count of ticks since the
        // CURRENT draw began, so it cannot fire early even if another task interrupted and restarted the use), a crossbow
        // is released the moment it is charged (loaded), and a fully drawn bow or a loaded crossbow is ready to shoot.
        RangedWeapon.Readiness readiness = RangedWeapon.prepare(bot);
        if (readiness == RangedWeapon.Readiness.FAILED) {
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot, target);
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        if (readiness != RangedWeapon.Readiness.READY) {
            return;
        }
        String refusal = StrikeLegality.shotRefusal(bot, target, RangedWeapon.shapeOf(bot.getMainHandItem()));
        if (refusal != null) {
            // Never shoot into the owner or another bot, nor without a clear line: hold the drawn (or loaded) weapon until
            // the line clears, then give up on ranged if it does not. approach() would re-enter RANGED at once
            // through shouldUseRanged(), so the give-up latches ranged out of the plan for a while
            // and the fight really continues in melee.
            if (++blockedShotTicks > BLOCKED_SHOT_HOLD_LIMIT) {
                giveUpRangedForBlockedShot(bot, refusal);
            }
            return;
        }
        blockedShotTicks = 0;
        if (aimed && RangedWeapon.shoot(bot)) {
            BotLog.action(bot, "ranged_shot", "target_type", target.getType(),
                    "dist", String.format(java.util.Locale.ROOT, "%.1f", bot.distanceTo(target)));
            // The next shot starts from the top (weapon and ammunition re-derived, ranged still in the plan, cover
            // eligibility re-checked), in the same tick: the cycle is the weapon's own, with no idle tick between shots.
            beginRanged(bot);
        }
    }

    /**
     * The one give-up path for a ranged weapon whose shot stays blocked (a friend keeps standing on the line of fire, or no
     * clear line to the target; the ranged hold and the cover peeks both end here): latches ranged out of the plan for a
     * while, so the fight really continues in melee.
     */
    private void giveUpRangedForBlockedShot(AIPlayerEntity bot, String reason) {
        blockedShotTicks = 0;
        friendlyBlockedPeeks = 0;
        sightBlockedPeeks = 0;
        rangedSuppressedUntil = elapsed + RANGED_SUPPRESS_TICKS;
        BotLog.action(bot, "ranged_suppressed", "reason", reason,
                "until", rangedSuppressedUntil);
        cancelPeekStep(bot);
        // CANCEL the draw, never release it: releasing a charged bow fires it, and the reason for
        // giving up is a shot the line of fire forbids.
        bot.stopUsingItem();
        finishRangedLoadout(bot);
        CombatCore.ensureMeleeWeapon(bot, target);
        phase = Phase.APPROACH;
        startApproach(bot);
    }

    private void strike(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire(bot);
            return;
        }
        // DangerWatcher never elects melee against a Creeper. Preserve that invariant even when a
        // manual combat request or a target transition reaches STRIKE through another path.
        if (CombatCore.isMeleeForbiddenThreat(target)) {
            beginRetreat(bot);
            retreat(bot);
            return;
        }
        finishRangedLoadout(bot);
        if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            bot.stopUsingItem(); // a stale main-hand use (bow, food) must not keep the hands busy
        }
        CombatCore.lookAt(bot, target);
        if (bot.distanceTo(target) > CombatCore.ATTACK_RANGE || !CombatCore.canStrikeNow(bot, target)) {
            // Out of reach, or a wall/window in the way: close the distance instead of swinging.
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        CombatCore.ensureMeleeWeapon(bot, target);
        if (bot.getAttackStrengthScale(0.5F) >= 0.95F) {
            // A ready swing always comes first. The shield is only ever raised BETWEEN swings, so a
            // shield-holding bot at low health still fights instead of turtling forever.
            if (CombatCore.strikeIfReady(bot, target)) {
                // The hit itself may have consumed the final point of durability. Equip the physical
                // successor in the same task tick instead of allowing logged empty-hand attacks.
                CombatCore.ensureMeleeWeapon(bot, target);
                mountBlockedTicks = 0;
                if (shouldBlock(bot)) {
                    beginBlock(bot);
                } else {
                    repositionTicks = 8;
                    phase = Phase.REPOSITION;
                }
            } else {
                handleMountInTheWay(bot);
            }
            return;
        }
        if (shouldBlock(bot)) {
            beginBlock(bot);
        }
    }

    /** Ticks a strike may keep landing on the target's mount before the bot changes its angle. */
    private static final int MOUNT_BLOCKED_LIMIT = 6;

    /**
     * A ready, legal strike that did not land because the bot's crosshair holds the target's MOUNT or vehicle, nearer than the target
     * (a rider on a horse, a spider jockey): a human would hit the mount, and so does the bot, never through it
     * ({@link InteractAction#mountInTheWay}). A mount that is itself a legal hostile becomes the target; any other mount (a horse
     * that is nobody's enemy) is walked around: after a few ticks the bot strafes to another angle (REPOSITION), where the ray to
     * the rider may clear the mount.
     */
    private void handleMountInTheWay(AIPlayerEntity bot) {
        Entity mount = InteractAction.mountInTheWay(bot, target);
        if (mount == null) {
            mountBlockedTicks = 0;
            return;
        }
        if (mount instanceof LivingEntity living && living.isAlive() && CombatCore.hostileTo(bot, living)
                && !CombatCore.isMeleeForbiddenThreat(living) && !CombatCore.isFriendly(bot, living)) {
            BotLog.action(bot, "combat_target_switched_to_mount", "mount", mount.getType(), "rider", target.getType());
            target = living;
            mountBlockedTicks = 0;
            return;
        }
        if (++mountBlockedTicks > MOUNT_BLOCKED_LIMIT) {
            mountBlockedTicks = 0;
            BotLog.action(bot, "combat_mount_in_the_way", "mount", mount.getType(), "rider", target.getType());
            repositionTicks = 8;
            phase = Phase.REPOSITION;
        }
    }

    /**
     * The skilled player's rhythm between the bot's own swings ({@link ShieldRules#meleeStep}): the shield goes up right after a
     * swing and is held while the attack cooldown runs, with the bot standing its ground and facing the target at human aim speed
     * (so the attacker stays in the shield's front arc and in reach of the next swing); the moment the weapon is ready AND the target
     * is under the crosshair within reach, the shield comes down and the swing follows on the next tick (a player cannot strike while
     * using an item, and the client drops an attack click on the tick the use key is released). A shield disabled by an axe (vanilla's
     * item cooldown) is not raised and not retried: the fight goes on without it. The shield itself goes up through
     * {@link ShieldGuard#raise}, the vanilla use path every shield use shares. Moving with the shield up is slowed like a player's
     * ({@code PaceRules} for controller-driven movement, {@link #reposition} for its raw strafe keys).
     */
    private void block(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            ShieldGuard.lower(bot);
            kills++;
            finishOrAcquire(bot);
            return;
        }
        CombatCore.lookAt(bot, target);
        bot.getActionPack().stopMovement();
        blockTicks--;
        if (blockTicks <= 0 || bot.distanceTo(target) > CombatCore.ATTACK_RANGE + 1.5F) {
            ShieldGuard.lower(bot);
            phase = Phase.STRIKE;
            return;
        }
        boolean ready = bot.getAttackStrengthScale(0.5F) >= 0.95F;
        boolean onTarget = CombatCore.inMeleeRange(bot, target) && HumanAim.isUnderCrosshair(bot, target)
                && StrikeLegality.strikeRefusal(bot, target) == null;
        double cooldownTicksLeft = bot.getCurrentItemAttackStrengthDelay()
                * (1.0F - Math.min(1.0F, bot.getAttackStrengthScale(0.5F)));
        ShieldRules.MeleeStep step = ShieldRules.meleeStep(ready, onTarget, true, ShieldGuard.shieldUsable(bot),
                ShieldGuard.usingShield(bot), cooldownTicksLeft, ShieldGuard.blockDelayTicks(bot));
        switch (step) {
            case SWING -> {
                // Lower now, swing next tick (STRIKE): never an attack while the item is in use.
                ShieldGuard.lower(bot);
                phase = Phase.STRIKE;
            }
            case RAISE -> {
                ShieldGuard.Raise raised = ShieldGuard.raise(bot, ShieldGuard.Owner.TASK);
                if (raised == ShieldGuard.Raise.ON_COOLDOWN || raised == ShieldGuard.Raise.NO_SHIELD
                        || raised == ShieldGuard.Raise.REFUSED) {
                    // Disabled by an axe, or never there: no re-raise attempts, the fight goes on.
                    phase = Phase.STRIKE;
                }
            }
            case HOLD -> {
                // Up, and either the swing is still cooling down or the aim is still settling on the target.
            }
            case IDLE -> {
                ShieldGuard.lower(bot);
                phase = Phase.STRIKE;
            }
        }
    }

    private void reposition(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            bot.getActionPack().stopMovement();
            kills++;
            finishOrAcquire(bot);
            return;
        }
        CombatCore.lookAt(bot, target);
        // Never strafe off a ledge or into lava: the sideways input is checked for footing first. These are raw keys, so the vanilla
        // use-item slowdown (a raised shield) is applied here, as LocalPlayer applies it to a player's keys.
        float slowdown = PaceRules.inputScale(false, bot.isUsingItem());
        bot.getActionPack().setStrafing(CombatCore.safeStrafeInput(
                bot, elapsed % 40 < 20 ? 0.45F : -0.45F) * slowdown);
        repositionTicks--;
        if (repositionTicks <= 0) {
            bot.getActionPack().stopMovement();
            phase = Phase.STRIKE;
        }
    }

    private void retreat(AIPlayerEntity bot) {
        LivingEntity threat = refreshRetreatThreat(bot);
        if (threat == null) {
            bot.getActionPack().stopAll();
            retreatThreat = null;
            retreatDestination = null;
            healTicks = 0;
            eating = false;
            if (kills >= targetKills) {
                complete();
            } else {
                phase = Phase.HEAL;
            }
            return;
        }

        double distance = bot.distanceTo(threat);
        if (!isImmediatePressure(bot, threat)) {
            bot.getActionPack().stopAll();
            retreatThreat = null;
            retreatDestination = null;
            healTicks = 0;
            eating = false;
            if (kills >= targetKills) {
                complete();
            } else {
                phase = Phase.HEAL;
            }
            return;
        }

        // A blocked tunnel must not turn retreat into passive death. Keep trying to open distance,
        // but counterattack whenever the pursuer remains in melee range.
        boolean meleeForbidden = CombatCore.isMeleeForbiddenThreat(threat);
        if (!meleeForbidden && distance <= CombatCore.ATTACK_RANGE) {
            CombatCore.lookAt(bot, threat);
            CombatCore.ensureMeleeWeapon(bot, threat);
            if (CombatCore.strikeIfReady(bot, threat)) {
                CombatCore.ensureMeleeWeapon(bot, threat);
                if (!threat.isAlive()) {
                    bot.getActionPack().stopAll();
                    retreatThreat = null;
                    retreatDestination = null;
                    return;
                }
            }
        }

        if (retreatDestination == null || bot.getActionPack().isPathExecutorIdle()) {
            retreatDestination = EvadeTask.admitBestSurfaceEscapePath(
                    bot, threat, threat.blockPosition(),
                    threat instanceof Warden ? WARDEN_RETREAT_STEP_DISTANCE : RETREAT_STEP_DISTANCE);
            if (retreatDestination == null) {
                fail("combat_no_valid_retreat_route");
            }
        }
    }

    private void beginRetreat(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        bot.stopUsingItem();
        eating = false;
        healTicks = 0;
        retreatDestination = null;
        phase = Phase.RETREAT;
    }

    private void heal(AIPlayerEntity bot) {
        healTicks++;
        LivingEntity pressure = refreshRetreatThreat(bot);
        if (isImmediatePressure(bot, pressure)) {
            beginRetreat(bot);
            retreat(bot);
            return;
        }
        if (bot.getHealth() > retreatHpThreshold + 4.0F) {
            bot.getActionPack().stopAll();
            eating = false;
            phase = Phase.ACQUIRE;
            return;
        }
        if (!eating && InventoryAction.hasFood(bot)) {
            bot.getActionPack().stopAll();
            ActionResult result = EatAction.startEating(bot);
            eating = !result.isFailed();
            return;
        }
        if (eating && !bot.isUsingItem() && healTicks > 20) {
            eating = false;
        }
        if (healTicks > HEAL_WAIT_TICKS) {
            bot.getActionPack().stopAll();
            phase = bot.getHealth() > retreatHpThreshold ? Phase.ACQUIRE : Phase.RETREAT;
        }
    }

    private void startApproach(AIPlayerEntity bot) {
        if (!defensiveEngagementAllowed(bot) || phase == Phase.RETREAT) {
            return;
        }
        CombatCore.startApproach(bot, target);
    }

    private boolean defensiveEngagementAllowed(AIPlayerEntity bot) {
        if (defensiveAnchor == null) {
            return true;
        }
        BlockPos here = bot.blockPosition();
        if (!isWithinDefensiveLeash(defensiveAnchor, here)) {
            return disengageOrRetreatFromImmediatePressure(
                    bot, "bot_left_leash", here);
        }
        if (target != null && target.isAlive()) {
            BlockPos targetPos = target.blockPosition();
            // A hostile outside the melee leash that can be shot from here is not a reason to
            // disengage: the bow does not need the leash (it cannot reach, and does not chase).
            if (!isWithinDefensiveLeash(defensiveAnchor, targetPos)
                    && !canShootFromWhereItStands(bot, target)) {
                return disengageOrRetreatFromImmediatePressure(
                        bot, "target_left_leash", targetPos);
            }
        }
        int visibleHostiles = observableActiveHostiles(bot).size();
        if (visibleHostiles > MinecraftAiConfig.get().combat().maxEnemiesToFight()) {
            return disengageOrRetreatFromImmediatePressure(
                    bot, "enemy_limit_exceeded", here);
        }
        return true;
    }

    private boolean disengageOrRetreatFromImmediatePressure(AIPlayerEntity bot,
                                                              String reason,
                                                              BlockPos observed) {
        LivingEntity pressure = refreshRetreatThreat(bot);
        if (isImmediatePressure(bot, pressure)) {
            if (phase != Phase.RETREAT) {
                beginRetreat(bot);
            }
            return true;
        }
        endDefensiveEngagement(bot, reason, observed);
        return false;
    }

    /**
     * Credits only the bound primary. A secondary pressure target may be counterattacked during
     * retreat, but its death never advances the requested kill quota.
     */
    private boolean settleDeadPrimary(AIPlayerEntity bot) {
        if (target == null || target.isAlive()) {
            return false;
        }
        LivingEntity defeated = target;
        target = null;
        if (retreatThreat == defeated) {
            retreatThreat = null;
            retreatDestination = null;
        }
        kills++;
        finishRangedLoadout(bot);
        bot.stopUsingItem();
        eating = false;

        LivingEntity pressure = refreshRetreatThreat(bot);
        if (isImmediatePressure(bot, pressure)) {
            beginRetreat(bot);
            return true;
        }

        bot.getActionPack().stopAll();
        retreatThreat = null;
        retreatDestination = null;
        if (kills >= targetKills) {
            complete();
        } else {
            phase = Phase.ACQUIRE;
        }
        return true;
    }

    private LivingEntity refreshRetreatThreat(AIPlayerEntity bot) {
        LivingEntity next = nearestObservablePressure(bot);
        if (retreatThreat != next) {
            retreatThreat = next;
            retreatDestination = null;
        }
        return retreatThreat;
    }

    private LivingEntity nearestObservablePressure(AIPlayerEntity bot) {
        LivingEntity nearest = isObservablePressure(bot, target) ? target : null;
        double nearestDistance = nearest == null
                ? Double.POSITIVE_INFINITY : bot.distanceToSqr(nearest);
        for (LivingEntity hostile : observableActiveHostiles(bot)) {
            double distance = bot.distanceToSqr(hostile);
            if (distance < nearestDistance) {
                nearest = hostile;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private List<LivingEntity> observableActiveHostiles(AIPlayerEntity bot) {
        return bot.level().getEntitiesOfClass(
                LivingEntity.class,
                bot.getBoundingBox().inflate(CombatCore.hostilePressureScanRange()),
                entity -> entity != bot
                        && DangerWatcher.isActiveHostileThreat(bot, entity)
                        && SharedVision.seenByBotOrOwner(bot, entity)
                        && CombatCore.isWithinHostilePressureEnvelope(bot, entity)
                        && CombatCore.hasLineOfSightOrOwnerSees(bot, entity));
    }

    private static boolean isObservablePressure(AIPlayerEntity bot, LivingEntity entity) {
        return entity != null
                && entity.isAlive()
                && ObservableWorldQuery.canNoticeCreature(bot, entity)
                && CombatCore.hasLineOfSight(bot, entity);
    }

    private static boolean isImmediatePressure(AIPlayerEntity bot, LivingEntity entity) {
        if (!isObservablePressure(bot, entity)) {
            return false;
        }
        // A ranged mob can still deal damage while food occupies the main hand. Distance alone is
        // not a healing boundary; only losing factual LOS releases this pressure.
        if (CombatCore.isRangedThreat(entity)) {
            return true;
        }
        double safeDistance = CombatCore.safeDistanceFrom(
                entity, HEAL_SAFE_DISTANCE, CREEPER_HEAL_SAFE_DISTANCE);
        return bot.distanceTo(entity) < safeDistance;
    }

    /**
     * The fight's leash, shared with DangerWatcher so the threat range and the leash agree: a spot is
     * inside when it is within {@code DEFENSIVE_MAX_HORIZONTAL_DISTANCE} of the anchor horizontally and
     * not more than {@code DEFENSIVE_MAX_VERTICAL_DROP} below it.
     */
    static boolean isWithinDefensiveLeash(BlockPos anchor, BlockPos pos) {
        if (pos.getY() < anchor.getY() - DEFENSIVE_MAX_VERTICAL_DROP) {
            return false;
        }
        double dx = pos.getX() - anchor.getX();
        double dz = pos.getZ() - anchor.getZ();
        return dx * dx + dz * dz
                <= DEFENSIVE_MAX_HORIZONTAL_DISTANCE * DEFENSIVE_MAX_HORIZONTAL_DISTANCE;
    }

    /** The entity a defensive fight is bound to; {@code null} for an ordinary (non-defensive) fight. */
    LivingEntity defensiveTarget() {
        return defensiveAnchor == null ? null : fixedDefensiveTarget;
    }

    /** True when this defensive fight's bound target already stands outside its leash. */
    boolean defensiveTargetOutsideLeash() {
        return defensiveAnchor != null
                && fixedDefensiveTarget != null
                && !isWithinDefensiveLeash(defensiveAnchor, fixedDefensiveTarget.blockPosition());
    }

    static boolean isImmediatePressureOn(AIPlayerEntity bot, LivingEntity entity) {
        return isImmediatePressure(bot, entity);
    }

    private void endDefensiveEngagement(AIPlayerEntity bot, String reason, BlockPos observed) {
        finishRangedLoadout(bot);
        bot.getActionPack().stopAll();
        BotLog.danger(bot, "defensive_combat_disengaged",
                "reason", reason,
                "anchor", defensiveAnchor.toShortString(),
                "observed", observed.toShortString());
        if ("target_left_leash".equals(reason)) {
            // Per-target cooldown so DangerWatcher does not re-assign the same fight next scan.
            DangerWatcher.INSTANCE.noteTargetLeftLeash(bot, fixedDefensiveTarget);
        }
        complete();
    }

    private void chooseEngagement(AIPlayerEntity bot) {
        if (shouldUseRanged(bot)) {
            beginRanged(bot);
            return;
        }
        finishRangedLoadout(bot);
        CombatCore.ensureMeleeWeapon(bot, target);
        phase = Phase.APPROACH;
        startApproach(bot);
    }

    /**
     * True when {@code target} is beyond the melee boundary but within arrow range, observed with a
     * clear line of sight, and the bot holds a bow or a crossbow that can fire (real arrows, or a loaded
     * crossbow), and no friend stands on the line of fire (of that weapon's shot): a shot from where the
     * bot already stands, with no approach needed. Never for a never-melee threat (a creeper or enderman
     * is evaded, not provoked).
     */
    static boolean canShootFromWhereItStands(AIPlayerEntity bot, LivingEntity target) {
        if (target == null || !target.isAlive() || CombatCore.isMeleeForbiddenThreat(target)) {
            return false;
        }
        double distance = bot.distanceTo(target);
        if (distance <= RANGED_MELEE_SWITCH_DISTANCE
                || distance > SEARCH_RANGE
                || !ObservableWorldQuery.canNoticeCreature(bot, target)
                || !CombatCore.hasLineOfSight(bot, target)) {
            return false;
        }
        OptionalInt weaponSlot = EquipAction.bestRangedSlot(bot, target);
        if (weaponSlot.isEmpty()) {
            return false;
        }
        RangedWeapon.Shape shape = RangedWeapon.shapeOf(bot.getInventory().getNonEquipmentItems().get(weaponSlot.getAsInt()));
        return !StrikeLegality.friendlyOnLineOfFire(bot, target, shape.spreadDeg(), shape.piercing());
    }

    private boolean shouldUseRanged(AIPlayerEntity bot) {
        return elapsed >= rangedSuppressedUntil
                && target != null
                && target.isAlive()
                && bot.distanceTo(target) > RANGED_MELEE_SWITCH_DISTANCE
                && EquipAction.bestRangedSlot(bot, target).isPresent();
    }

    private void beginRanged(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        rangedLoadout = target == null
                ? null : EquipAction.equipBestRangedLoadout(bot, target).orElse(null);
        if (rangedLoadout == null) {
            CombatCore.ensureMeleeWeapon(bot, target);
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        bot.getActionPack().stopMovement();
        if (shouldUsePeekaboo(bot)) {
            beginPeekaboo(bot);
        } else {
            phase = Phase.RANGED;
        }
    }

    /** "in ranged mode ... and there are other ranged enemies" -- build/use a cover column instead
     *  of standing in the open while multiple attackers can hit the bot from range. */
    private boolean shouldUsePeekaboo(AIPlayerEntity bot) {
        return elapsed >= nextPeekabooAttemptElapsed
                && target != null && target.isAlive()
                && CombatCore.rangedThreatsAround(bot, PEEKABOO_SCAN_RANGE).size()
                >= PEEKABOO_MIN_RANGED_THREATS;
    }

    private void beginPeekaboo(AIPlayerEntity bot) {
        if (peekCoverFeet != null && peekHideSpot != null
                && bot.blockPosition().equals(peekHideSpot)
                && isObservableSolid(bot, peekCoverFeet) && isObservableSolid(bot, peekCoverFeet.above())) {
            // Cover from an earlier cycle is still standing right where the bot already is.
            phase = Phase.COVER_HIDE;
            peekCycleTicks = 0;
            return;
        }
        peekBuildAttempts = 0;
        phase = Phase.COVER_BUILD;
        buildPeekabooCover(bot);
    }

    private void buildPeekabooCover(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire(bot);
            return;
        }
        Direction towardTarget = CombatCore.dominantHorizontalDirection(
                bot.blockPosition(), target.blockPosition());
        if (towardTarget == null) {
            abandonPeekaboo(bot, "peekaboo_missing_direction");
            return;
        }
        BlockPos hideSpot = bot.blockPosition().immutable();
        BlockPos coverFeet = hideSpot.relative(towardTarget).immutable();
        Direction sideStep = towardTarget.getClockWise();
        BlockPos exposeSpot = hideSpot.relative(sideStep).immutable();
        if (!canStandAt(bot, exposeSpot)) {
            exposeSpot = hideSpot.relative(sideStep.getOpposite()).immutable();
            if (!canStandAt(bot, exposeSpot)) {
                abandonPeekaboo(bot, "peekaboo_no_expose_spot");
                return;
            }
        }
        String failure = placePeekabooColumnBlock(bot, coverFeet);
        if (failure == null) {
            failure = placePeekabooColumnBlock(bot, coverFeet.above());
        }
        if (failure != null) {
            peekBuildAttempts++;
            if (peekBuildAttempts >= PEEKABOO_BUILD_RETRY_LIMIT) {
                abandonPeekaboo(bot, "peekaboo_build_failed:" + failure);
            }
            return;
        }
        peekHideSpot = hideSpot;
        peekCoverFeet = coverFeet;
        peekExposeSpot = exposeSpot;
        BotLog.action(bot, "peekaboo_cover_built",
                "hide", peekHideSpot, "cover", peekCoverFeet, "expose", peekExposeSpot,
                "ranged_threats", CombatCore.rangedThreatsAround(bot, PEEKABOO_SCAN_RANGE).size());
        phase = Phase.COVER_HIDE;
        peekCycleTicks = 0;
    }

    private String placePeekabooColumnBlock(AIPlayerEntity bot, BlockPos target) {
        if (bot.getBoundingBox().intersects(new AABB(target))) {
            return "target_intersects_bot";
        }
        OptionalInt slot = MaterialPalette.pickSacrificialBlockSlot(bot);
        if (slot.isEmpty()) {
            return "missing_material";
        }
        if (InventoryAction.equipFromSlot(bot, slot.getAsInt()) < 0) {
            return "equip_failed";
        }
        ActionResult result = BuildAction.placeBlockAt(bot, target);
        if (!result.isSuccess()) {
            return "place_failed:" + result.reason();
        }
        return null;
    }

    private static boolean canStandAt(AIPlayerEntity bot, BlockPos feet) {
        return ObservableWorldQuery.canObserveCell(bot, feet)
                && ObservableWorldQuery.canObserveCell(bot, feet.above())
                && ObservableWorldQuery.canObserveColliderWithInsetFaces(bot, feet.below());
    }

    private static boolean isObservableSolid(AIPlayerEntity bot, BlockPos pos) {
        if (!ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos)) {
            return false;
        }
        return !bot.level().getBlockState(pos)
                .getCollisionShape(bot.level(), pos).isEmpty();
    }

    /** Lets go of the movement keys of a walked cover step that is being given up mid-walk. */
    private void cancelPeekStep(AIPlayerEntity bot) {
        if (peekStep != null) {
            CombatCore.cancelStep(bot, peekStep);
            peekStep = null;
        }
    }

    private void abandonPeekaboo(AIPlayerEntity bot, String reason) {
        cancelPeekStep(bot);
        peekStage = PeekStage.OUT;
        BotLog.action(bot, "peekaboo_abandoned", "reason", reason);
        peekHideSpot = null;
        peekCoverFeet = null;
        peekExposeSpot = null;
        peekThreatsAtLastPeek = 0;
        nextPeekabooAttemptElapsed = elapsed + PEEKABOO_RETRY_COOLDOWN_TICKS;
        phase = Phase.RANGED;
    }

    private void coverHide(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire(bot);
            return;
        }
        if (!shouldUseRanged(bot)) {
            // Ranged left the plan (a friend kept blocking the line, or the target closed in): leave cover.
            cancelPeekStep(bot);
            // Cancel the draw (never release it: releasing a drawn bow fires it without the line-of-fire check).
            bot.stopUsingItem();
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot, target);
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        if (!bot.blockPosition().equals(peekHideSpot) && bot.getActionPack().isPathExecutorIdle()) {
            // Knocked out of its hiding cell: walk back in, by inputs, like a player would.
            if (peekStep == null || !peekStep.cell().equals(peekHideSpot)) {
                peekStep = CombatCore.beginStepByInput(peekHideSpot, false, false);
            }
            CombatCore.StepStatus returning = CombatCore.stepByInput(bot, peekStep);
            if (returning == CombatCore.StepStatus.FAILED) {
                String why = peekStep.failure();
                peekStep = null;
                abandonPeekaboo(bot, "peekaboo_hide_step_failed:" + why);
                return;
            }
            if (returning == CombatCore.StepStatus.ARRIVED) {
                peekStep = null;
            }
        } else {
            cancelPeekStep(bot);
        }
        if (rangedLoadout == null) {
            rangedLoadout = EquipAction.equipBestRangedLoadout(bot, target).orElse(null);
            if (rangedLoadout == null) {
                CombatCore.ensureMeleeWeapon(bot, target);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        } else if (!RangedWeapon.isRanged(bot.getMainHandItem())) {
            // Placing the cover column put a building block in the main hand: take the ranged weapon back
            // (the arrow stays in the offhand; equipping an already selected slot is a no-op).
            OptionalInt weaponSlot = EquipAction.bestRangedSlot(bot, target);
            if (weaponSlot.isEmpty() || InventoryAction.equipFromSlot(bot, weaponSlot.getAsInt()) < 0) {
                finishRangedLoadout(bot);
                CombatCore.ensureMeleeWeapon(bot, target);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        }
        if (peekStep == null) {
            // Behind the column the aim already settles on the target, so the peek starts on it.
            CombatCore.aimForShot(bot, target);
        }
        // The same vanilla draw cycle as in ranged(): a crossbow is loaded here, behind cover, and a bow is drawn.
        if (RangedWeapon.prepare(bot) == RangedWeapon.Readiness.FAILED) {
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot, target);
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        // The peek only starts from the hiding cell itself (a bow drawn, or a crossbow loaded, while still walking back in waits).
        if (RangedWeapon.isReadyToShoot(bot)
                && bot.blockPosition().equals(peekHideSpot)
                && peekStep == null) {
            phase = Phase.COVER_PEEK;
            peekCycleTicks = 0;
            peekStage = PeekStage.OUT;
            peekExposedTicks = 0;
        }
    }

    /**
     * One peek cycle, all of it real walking: strafe out of the hiding cell into the exposed cell
     * with the drawn bow kept aimed at the target, hold the aim a few ticks, release, and strafe
     * back behind the column. Each walk is a {@link CombatCore.InputStep} (bounded by
     * {@link CombatCore#STEP_TIMEOUT_TICKS}, re-proving the footing and hazards every tick), so the
     * exposure lasts as long as the walk really takes instead of one teleported tick.
     */
    /** True when a mob's blow (an arrow, a hit) landed on the bot during the current peek cycle. */
    private boolean hitDuringThisPeek(AIPlayerEntity bot) {
        return bot.getLastHurtByMob() != null && bot.tickCount - bot.getLastHurtByMobTimestamp() <= peekCycleTicks;
    }

    private void coverPeek(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            cancelPeekStep(bot);
            bot.stopUsingItem(); // the target is gone: cancel the draw, never fire it into the void
            kills++;
            finishOrAcquire(bot);
            return;
        }
        peekCycleTicks++;
        if (peekStage == PeekStage.OUT) {
            // Aim first: the strafe below is computed in the current yaw frame, so the weapon stays on target.
            CombatCore.aimForShot(bot, target);
            if (peekStep == null) {
                peekStep = CombatCore.beginStepByInput(peekExposeSpot, true, false);
            }
            CombatCore.StepStatus status = CombatCore.stepByInput(bot, peekStep);
            if (status == CombatCore.StepStatus.FAILED) {
                String why = peekStep.failure();
                peekStep = null;
                bot.stopUsingItem(); // the peek was not completed: cancel the draw, no unchecked shot
                abandonPeekaboo(bot, "peekaboo_peek_step_failed:" + why);
                return;
            }
            if (status == CombatCore.StepStatus.ARRIVED) {
                peekStep = null;
                peekStage = PeekStage.EXPOSED;
                peekExposedTicks = 0;
            }
            return;
        }
        if (peekStage == PeekStage.EXPOSED) {
            boolean aimed = CombatCore.aimForShot(bot, target);
            // Realistic perception (docs/PERCEPTION.md): the shooters behind the cover are not known while the bot hides (a creature out of
            // sight for more than a tick is forgotten and is noticed again only after the reaction time of looking at it), so the peek lasts
            // at least that long: what the bot decides from (how many shooters there are, whether to hide again) is what it has noticed.
            // Zero extra with perception off.
            int exposeTicks = Math.max(PEEKABOO_EXPOSE_TICKS,
                    io.github.zoyluo.minecraftai.perception.CreatureSenses.noticeDwellTicks(bot.distanceTo(target)));
            if (++peekExposedTicks <= exposeTicks) {
                return;
            }
            if (!aimed && peekExposedTicks <= PEEKABOO_EXPOSE_LIMIT_TICKS) {
                return; // the aim is still settling (human turn speed): stay exposed for it, bounded
            }
            // The only moment the shooters are in sight: remember how many there are, because the
            // bot's own column hides them again as soon as it ducks back.
            peekThreatsAtLastPeek = CombatCore.rangedThreatsAround(bot, PEEKABOO_SCAN_RANGE).size();
            String refusal = StrikeLegality.shotRefusal(bot, target, RangedWeapon.shapeOf(bot.getMainHandItem()));
            if ("friendly_on_line_of_fire".equals(refusal)) {
                // With a friend on the line of fire the drawn (or loaded) weapon is kept, never shot into them; past a
                // few such peeks it is given up, through the same path ranged() uses.
                if (++friendlyBlockedPeeks > FRIENDLY_PEEK_LIMIT) {
                    giveUpRangedForBlockedShot(bot, refusal);
                    return;
                }
            } else if ("no_line_of_sight".equals(refusal)) {
                // No clear line from the exposed cell either: counted like a friend on the line, or expose, refuse and hide would
                // repeat for ever. Past the same limit the ranged weapon is given up and the fight goes on in melee.
                if (++sightBlockedPeeks > FRIENDLY_PEEK_LIMIT) {
                    giveUpRangedForBlockedShot(bot, refusal);
                    return;
                }
            } else if (refusal == null) {
                friendlyBlockedPeeks = 0;
                sightBlockedPeeks = 0;
                if (aimed && RangedWeapon.shoot(bot)) {
                    BotLog.action(bot, "peekaboo_shot_released", "target_type", target.getType());
                }
            }
            peekStage = PeekStage.BACK;
        }
        if (peekStep == null) {
            peekStep = CombatCore.beginStepByInput(peekHideSpot, true, false);
        }
        CombatCore.aimForShot(bot, target);
        CombatCore.StepStatus back = CombatCore.stepByInput(bot, peekStep);
        if (back == CombatCore.StepStatus.FAILED) {
            String why = peekStep.failure();
            peekStep = null;
            if (("timeout".equals(why) || "left_course".equals(why)) && hitDuringThisPeek(bot)) {
                // A blow (a shooter's arrow) knocked the bot about while it ducked back: like a player, it keeps going for its cover
                // from where it landed. The hide phase walks it back in (one more bounded step; a failure there gives the peek up).
                BotLog.action(bot, "peekaboo_knocked_on_the_way_back", "reason", why, "at", bot.blockPosition().toShortString());
                peekStage = PeekStage.OUT;
                phase = Phase.COVER_HIDE;
                return;
            }
            abandonPeekaboo(bot, "peekaboo_hide_step_failed:" + why);
            return;
        }
        if (back != CombatCore.StepStatus.ARRIVED) {
            return;
        }
        peekStep = null;
        peekStage = PeekStage.OUT;
        if (!shouldUseRanged(bot)) {
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot, target);
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        if (peekThreatsAtLastPeek >= PEEKABOO_MIN_RANGED_THREATS
                && isObservableSolid(bot, peekCoverFeet) && isObservableSolid(bot, peekCoverFeet.above())) {
            // Hidden again with the shooters still out there. The column now blocks the sight that
            // shouldUsePeekaboo() counts, so re-deriving eligibility below would wrongly see none:
            // the next cycle follows from what was seen while exposed.
            phase = Phase.COVER_HIDE;
            peekCycleTicks = 0;
            return;
        }
        // Reuses the same single entry point every reload goes through: it restores/re-derives the
        // ranged loadout and re-checks peekaboo eligibility, exactly like leaving RANGED normally.
        beginRanged(bot);
    }

    /**
     * A noticed attacker (the target) within reach and a shield that can be raised: the rhythm of {@link #block}. No health
     * condition: a competent player blocks between swings at full health too.
     */
    private boolean shouldBlock(AIPlayerEntity bot) {
        return target != null
                && target.isAlive()
                && bot.distanceTo(target) <= CombatCore.ATTACK_RANGE + 1.0F
                && ShieldGuard.shieldUsable(bot);
    }

    private void beginBlock(AIPlayerEntity bot) {
        // Only worth raising when the cooldown leaves the shield its block delay (vanilla: five ticks for a shield).
        double cooldownTicksLeft = bot.getCurrentItemAttackStrengthDelay()
                * (1.0F - Math.min(1.0F, bot.getAttackStrengthScale(0.5F)));
        ShieldRules.MeleeStep step = ShieldRules.meleeStep(false, false, true, true, false,
                cooldownTicksLeft, ShieldGuard.blockDelayTicks(bot));
        if (step != ShieldRules.MeleeStep.RAISE) {
            return;
        }
        blockTicks = BLOCK_TICKS;
        bot.getActionPack().stopMovement();
        phase = Phase.BLOCK;
    }

    private void finishRangedLoadout(AIPlayerEntity bot) {
        if (rangedLoadout == null) {
            return;
        }
        // Releasing a charged bow here could fire after the enemy crossed the melee boundary.
        // The regular full-charge branch owns intentional releases; exits only cancel use.
        bot.stopUsingItem();
        if (!rangedLoadout.restore(bot)) {
            BotLog.action(bot, "restore_ranged_offhand_skipped", "reason", "inventory_changed");
        }
        rangedLoadout = null;
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        cancelPeekStep(bot);
        ShieldGuard.lowerIfOwner(bot, ShieldGuard.Owner.TASK);
        super.onPause(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        cancelPeekStep(bot);
        ShieldGuard.lowerIfOwner(bot, ShieldGuard.Owner.TASK);
        super.onAbort(bot);
    }

    private void finishOrAcquire(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        target = null;
        retreatThreat = null;
        retreatDestination = null;
        if (kills >= targetKills) {
            complete();
        } else {
            phase = Phase.ACQUIRE;
        }
    }
}
