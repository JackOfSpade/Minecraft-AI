package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.EatAction;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
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
    private static final double BOW_MELEE_SWITCH_DISTANCE = CombatCore.ATTACK_RANGE * 1.5D;
    private static final int BOW_CHARGE_TICKS = 20;
    private static final int BLOCK_TICKS = 12;
    private static final int HEAL_WAIT_TICKS = 200;
    private static final double HEAL_SAFE_DISTANCE = CombatCore.ATTACK_RANGE + 2.0D;
    // Vanilla Creeper ignition only backs off beyond seven blocks (or after LOS is broken).
    private static final double CREEPER_HEAL_SAFE_DISTANCE = 8.0D;
    private static final int RETREAT_STEP_DISTANCE = 6;
    private static final int LOST_SIGHT_LIMIT = 50; // Target blocked by a wall (no line of sight) for 2.5s straight -> end combat instead of foolishly fighting until timeout
    private static final int DEFENSIVE_MAX_VERTICAL_DROP = 2;
    private static final double DEFENSIVE_MAX_HORIZONTAL_DISTANCE = 8.0D;
    /** Raise the shield a little ahead of CreeperDefenseTask's own late-fuse wall threshold. */
    private static final float SHIELD_CREEPER_FUSE_THRESHOLD = 0.35F;
    private static final double SHIELD_CREEPER_FUSE_RANGE = 10.0D;
    /**
     * A skeleton is "visibly drawing" once its bow has been up this long (a full draw is 20 ticks);
     * the shield needs five ticks to start blocking, so this leaves it time to come up.
     */
    private static final int SHOOTER_DRAW_TICKS = 12;
    /** Only shooters this close or closer give too little warning from the flying arrow alone. */
    private static final double SHOOTER_DRAW_RANGE = 10.0D;
    /** Head-aim tolerance: the shooter must face the bot within roughly twenty degrees. */
    private static final double SHOOTER_AIM_DOT = 0.94D;
    /** Longest single pause for one drawing shooter, then a cooldown before the next one. */
    private static final int SHOOTER_SHIELD_MAX_TICKS = 40;
    private static final int SHOOTER_SHIELD_COOLDOWN_TICKS = 40;
    /** The shield only helps between swings once it can be up for its five warm-up ticks. */
    private static final float MIN_BLOCK_COOLDOWN_TICKS = 6.0F;
    /** A friend on the line of fire holds the drawn bow this long before falling back to melee. */
    private static final int FRIENDLY_LINE_HOLD_LIMIT = 60;
    /** After a friend blocked the line of fire this long, the bow stays out of the plan this many ticks. */
    private static final int BOW_SUPPRESS_TICKS = 200;
    /** Peek cycles in a row whose shot a friend on the line of fire held back, before the bow is given up. */
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
    /** True while a reactive shield block (projectile/creeper fuse) is currently being held up. */
    private boolean reactiveShieldRaised;
    private int shooterShieldTicks;
    private int shooterShieldCooldownUntil;
    private int friendlyLineTicks;
    /** Consecutive peeks that ended with a friend on the line of fire (the drawn bow was kept, not shot). */
    private int friendlyBlockedPeeks;
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
    private int bowSuppressedUntil;

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
    boolean isBowSuppressed() {
        return bowSuppressedUntil > 0 && elapsed < bowSuppressedUntil;
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
        reactiveShieldRaised = false;
        peekHideSpot = null;
        peekCoverFeet = null;
        peekExposeSpot = null;
        peekBuildAttempts = 0;
        peekCycleTicks = 0;
        nextPeekabooAttemptElapsed = 0;
        peekStage = PeekStage.OUT;
        peekExposedTicks = 0;
        peekStep = null;
        bowSuppressedUntil = 0;
        peekThreatsAtLastPeek = 0;
        friendlyLineTicks = 0;
        friendlyBlockedPeeks = 0;
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
        } else if (target != null && target.isAlive() && !CombatCore.hasLineOfSight(bot, target)) {
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
        if (isMeleeOrientedPhase() && handleReactiveShield(bot)) {
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
     * mid-draw on a bow (raising the offhand shield would cancel that single active-hand use) and
     * is not already in a dedicated recovery phase.
     */
    private boolean isMeleeOrientedPhase() {
        return phase == Phase.ACQUIRE || phase == Phase.APPROACH || phase == Phase.STRIKE
                || phase == Phase.REPOSITION || phase == Phase.BLOCK;
    }

    /**
     * Turns to face, and raises the offhand shield against, an imminent incoming projectile or a
     * creeper whose fuse is far enough along to be a real risk. Returns true while the reaction is
     * active (the caller should skip its normal phase logic that tick); lowers the shield and
     * returns false once neither threat remains.
     */
    private boolean handleReactiveShield(AIPlayerEntity bot) {
        if (!bot.getOffhandItem().is(Items.SHIELD) && !EquipAction.equipShieldOffhand(bot)) {
            return false;
        }
        ProjectileThreat.Incoming incoming = ProjectileThreat.mostImminent(bot).orElse(null);
        LivingEntity fusingCreeper = incoming == null ? nearbyImminentCreeper(bot) : null;
        LivingEntity drawingShooter = incoming == null && fusingCreeper == null
                ? nextDrawingShooter(bot) : null;
        if (incoming == null && fusingCreeper == null && drawingShooter == null) {
            shooterShieldTicks = 0;
            if (reactiveShieldRaised) {
                lowerReactiveShield(bot);
                BotLog.action(bot, "reactive_shield_lowered");
            }
            return false;
        }
        if (drawingShooter != null && ++shooterShieldTicks > SHOOTER_SHIELD_MAX_TICKS) {
            // Bounded pause: a shooter that keeps its bow drawn cannot freeze the fight forever.
            shooterShieldTicks = 0;
            shooterShieldCooldownUntil = elapsed + SHOOTER_SHIELD_COOLDOWN_TICKS;
            lowerReactiveShield(bot);
            BotLog.action(bot, "reactive_shield_lowered", "reason", "shooter_draw_timeout");
            return false;
        }
        Vec3 faceTowards = incoming != null
                ? incoming.projectile().position()
                : fusingCreeper != null ? fusingCreeper.position() : drawingShooter.getEyePosition();
        LookAction.lookAt(bot, faceTowards);
        if (!bot.isUsingItem() || bot.getUsedItemHand() != InteractionHand.OFF_HAND) {
            InteractAction.useItemInAir(bot, InteractionHand.OFF_HAND);
        }
        bot.getActionPack().stopMovement();
        if (!reactiveShieldRaised) {
            reactiveShieldRaised = true;
            BotLog.action(bot, "reactive_shield_raised",
                    "reason", incoming != null ? "incoming_projectile"
                            : fusingCreeper != null ? "creeper_fuse" : "shooter_draw",
                    "source", incoming != null
                            ? BuiltInRegistries.ENTITY_TYPE.getKey(incoming.projectile().getType()).toString()
                            : fusingCreeper != null ? "creeper"
                            : BuiltInRegistries.ENTITY_TYPE.getKey(drawingShooter.getType()).toString());
        }
        return true;
    }

    private LivingEntity nextDrawingShooter(AIPlayerEntity bot) {
        return elapsed < shooterShieldCooldownUntil ? null : nearbyDrawingShooter(bot);
    }

    /**
     * An observed skeleton that is visibly drawing a bow at the bot: its use pose is synced, its
     * bow has been up long enough for the arrow to be imminent, and its head is aimed at the bot.
     * Crossbows (a charged shot is held for a long time) and trident throwers are excluded by the
     * bow check. Decided only on synced pose and head aim, never on the mob's hidden target.
     */
    static LivingEntity nearbyDrawingShooter(AIPlayerEntity bot) {
        return bot.level().getEntitiesOfClass(AbstractSkeleton.class,
                        bot.getBoundingBox().inflate(SHOOTER_DRAW_RANGE),
                        skeleton -> skeleton.isAlive()
                                && ObservableWorldQuery.canObserveEntity(bot, skeleton)
                                && isDrawingBowAt(skeleton, bot))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    static boolean isDrawingBowAt(LivingEntity shooter, AIPlayerEntity bot) {
        if (!shooter.isUsingItem()
                || !shooter.getUseItem().is(Items.BOW)
                || shooter.getTicksUsingItem() < SHOOTER_DRAW_TICKS) {
            return false;
        }
        Vec3 toBot = bot.getEyePosition().subtract(shooter.getEyePosition());
        if (toBot.lengthSqr() < 1.0E-6D) {
            return true;
        }
        return shooter.getViewVector(1.0F).dot(toBot.normalize()) >= SHOOTER_AIM_DOT;
    }

    private static LivingEntity nearbyImminentCreeper(AIPlayerEntity bot) {
        return bot.level().getEntitiesOfClass(Creeper.class,
                        bot.getBoundingBox().inflate(SHIELD_CREEPER_FUSE_RANGE),
                        creeper -> creeper.isAlive()
                                && ObservableWorldQuery.canObserveEntity(bot, creeper)
                                && creeper.getSwelling(1.0F) >= SHIELD_CREEPER_FUSE_THRESHOLD)
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
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
        boolean useBow = shouldUseBow(bot);
        if (useBow) {
            beginRanged(bot);
            return;
        }
        // The distance boundary is deliberately broader than hit reach.  At or inside it, select
        // the best physical melee weapon before either closing the remaining gap or striking.
        finishRangedLoadout(bot);
        CombatCore.ensureMeleeWeapon(bot);
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
        if (!shouldUseBow(bot)) {
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot);
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        if (rangedLoadout == null) {
            beginRanged(bot);
            return;
        }
        CombatCore.lookAtForBowShot(bot, target);
        if (!bot.isUsingItem()) {
            ActionResult result = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
            if (result.isFailed()) {
                finishRangedLoadout(bot);
                CombatCore.ensureMeleeWeapon(bot);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        }
        // getTicksUsingItem() is Minecraft's own count of ticks since the CURRENT draw began -- it
        // self-resets if something upstream (e.g. ActionPack#stopAll from another task) interrupted
        // and restarted the use. A hand-rolled tick counter here would desync from that and could
        // release a shot well before it's actually at full pull.
        if (bot.getTicksUsingItem() >= BOW_CHARGE_TICKS) {
            if (StrikeLegality.friendlyOnLineOfFire(bot, target)) {
                // Never release into the owner or another bot: hold the draw until the line clears,
                // then give up on the bow if it does not. approach() would re-enter RANGED at once
                // through shouldUseBow(), so the give-up latches the bow out of the plan for a while
                // and the fight really continues in melee.
                if (++friendlyLineTicks > FRIENDLY_LINE_HOLD_LIMIT) {
                    giveUpBowForFriendlyLine(bot);
                }
                return;
            }
            friendlyLineTicks = 0;
            bot.releaseUsingItem();
            phase = Phase.APPROACH;
        }
    }

    /**
     * The one give-up path for a bow that a friend keeps blocking (the ranged hold and the cover peeks both
     * end here): latches the bow out of the plan for a while, so the fight really continues in melee.
     */
    private void giveUpBowForFriendlyLine(AIPlayerEntity bot) {
        friendlyLineTicks = 0;
        friendlyBlockedPeeks = 0;
        bowSuppressedUntil = elapsed + BOW_SUPPRESS_TICKS;
        BotLog.action(bot, "bow_suppressed", "reason", "friendly_on_line_of_fire",
                "until", bowSuppressedUntil);
        cancelPeekStep(bot);
        // CANCEL the draw, never release it: releasing a charged bow fires it, and the reason for
        // giving up is a friend standing on the line of fire.
        bot.stopUsingItem();
        finishRangedLoadout(bot);
        CombatCore.ensureMeleeWeapon(bot);
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
        CombatCore.ensureMeleeWeapon(bot);
        if (bot.getAttackStrengthScale(0.5F) >= 0.95F) {
            // A ready swing always comes first. The shield is only ever raised BETWEEN swings, so a
            // shield-holding bot at low health still fights instead of turtling forever.
            if (CombatCore.strikeIfReady(bot, target)) {
                // The hit itself may have consumed the final point of durability. Equip the physical
                // successor in the same task tick instead of allowing logged empty-hand attacks.
                CombatCore.ensureMeleeWeapon(bot);
                if (shouldBlock(bot)) {
                    beginBlock(bot);
                } else {
                    repositionTicks = 8;
                    phase = Phase.REPOSITION;
                }
            }
            return;
        }
        if (shouldBlock(bot)) {
            beginBlock(bot);
        }
    }

    /**
     * Holds the raised shield only while the attack cooldown runs, with movement stopped (a raised
     * shield does not slow a fake player the way it slows a human, so it is never up while walking
     * or strafing). When the cooldown completes the shield drops and the swing happens.
     */
    private void block(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            bot.releaseUsingItem();
            kills++;
            finishOrAcquire(bot);
            return;
        }
        CombatCore.lookAt(bot, target);
        bot.getActionPack().stopMovement();
        blockTicks--;
        if (bot.getAttackStrengthScale(0.5F) >= 0.95F
                || blockTicks <= 0
                || bot.distanceTo(target) > CombatCore.ATTACK_RANGE + 1.5F) {
            bot.releaseUsingItem();
            phase = Phase.STRIKE;
            strike(bot);
            return;
        }
        if (!bot.isUsingItem()) {
            InteractAction.useItemInAir(bot, InteractionHand.OFF_HAND);
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
        // Never strafe off a ledge or into lava: the sideways input is checked for footing first.
        bot.getActionPack().setStrafing(CombatCore.safeStrafeInput(
                bot, elapsed % 40 < 20 ? 0.45F : -0.45F));
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
            CombatCore.ensureMeleeWeapon(bot);
            if (CombatCore.strikeIfReady(bot, threat)) {
                CombatCore.ensureMeleeWeapon(bot);
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
                        && ObservableWorldQuery.canObserveEntity(bot, entity)
                        && CombatCore.isWithinHostilePressureEnvelope(bot, entity)
                        && CombatCore.hasLineOfSight(bot, entity));
    }

    private static boolean isObservablePressure(AIPlayerEntity bot, LivingEntity entity) {
        return entity != null
                && entity.isAlive()
                && ObservableWorldQuery.canObserveEntity(bot, entity)
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
        if (shouldUseBow(bot)) {
            beginRanged(bot);
            return;
        }
        finishRangedLoadout(bot);
        CombatCore.ensureMeleeWeapon(bot);
        phase = Phase.APPROACH;
        startApproach(bot);
    }

    /**
     * True when {@code target} is beyond the melee boundary but within bow range, observed with a
     * clear line of sight, and the bot holds a bow with real arrows, and no friend stands on the
     * line of fire: a shot from where the bot already stands, with no approach needed. Never for a
     * never-melee threat (a creeper or enderman is evaded, not provoked).
     */
    static boolean canShootFromWhereItStands(AIPlayerEntity bot, LivingEntity target) {
        if (target == null || !target.isAlive() || CombatCore.isMeleeForbiddenThreat(target)) {
            return false;
        }
        double distance = bot.distanceTo(target);
        return distance > BOW_MELEE_SWITCH_DISTANCE
                && distance <= SEARCH_RANGE
                && ObservableWorldQuery.canObserveEntity(bot, target)
                && CombatCore.hasLineOfSight(bot, target)
                && EquipAction.bestRangedSlot(bot, target).isPresent()
                && !StrikeLegality.friendlyOnLineOfFire(bot, target);
    }

    private boolean shouldUseBow(AIPlayerEntity bot) {
        return elapsed >= bowSuppressedUntil
                && target != null
                && target.isAlive()
                && bot.distanceTo(target) > BOW_MELEE_SWITCH_DISTANCE
                && EquipAction.bestRangedSlot(bot, target).isPresent();
    }

    private void beginRanged(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        rangedLoadout = target == null
                ? null : EquipAction.equipBestRangedLoadout(bot, target).orElse(null);
        if (rangedLoadout == null) {
            CombatCore.ensureMeleeWeapon(bot);
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
        if (!shouldUseBow(bot)) {
            // The bow left the plan (a friend kept blocking the line, or the target closed in): leave cover.
            cancelPeekStep(bot);
            // Cancel the draw (never release it: releasing a drawn bow fires it without the line-of-fire check).
            bot.stopUsingItem();
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot);
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
                CombatCore.ensureMeleeWeapon(bot);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        } else if (!bot.getMainHandItem().is(Items.BOW)) {
            // Placing the cover column put a building block in the main hand: take the bow back
            // (the arrow stays in the offhand; equipping an already selected slot is a no-op).
            OptionalInt bowSlot = EquipAction.bestRangedSlot(bot, target);
            if (bowSlot.isEmpty() || InventoryAction.equipFromSlot(bot, bowSlot.getAsInt()) < 0) {
                finishRangedLoadout(bot);
                CombatCore.ensureMeleeWeapon(bot);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        }
        if (!bot.isUsingItem()) {
            ActionResult result = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
            if (result.isFailed()) {
                finishRangedLoadout(bot);
                CombatCore.ensureMeleeWeapon(bot);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        }
        // See the identical check in ranged(): getTicksUsingItem() tracks the CURRENT draw, so it
        // can't fire early even if this draw was interrupted and restarted mid-charge.
        // The peek only starts from the hiding cell itself (a bow drawn while still walking back in waits).
        if (bot.getTicksUsingItem() >= BOW_CHARGE_TICKS
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
            // Aim first: the strafe below is computed in the current yaw frame, so the bow stays on target.
            CombatCore.lookAtForBowShot(bot, target);
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
            CombatCore.lookAtForBowShot(bot, target);
            if (++peekExposedTicks <= PEEKABOO_EXPOSE_TICKS) {
                return;
            }
            // The only moment the shooters are in sight: remember how many there are, because the
            // bot's own column hides them again as soon as it ducks back.
            peekThreatsAtLastPeek = CombatCore.rangedThreatsAround(bot, PEEKABOO_SCAN_RANGE).size();
            if (StrikeLegality.friendlyOnLineOfFire(bot, target)) {
                // With a friend on the line of fire the drawn bow is kept, never released into them; past a
                // few such peeks the bow is given up, through the same path ranged() uses.
                if (++friendlyBlockedPeeks > FRIENDLY_PEEK_LIMIT) {
                    giveUpBowForFriendlyLine(bot);
                    return;
                }
            } else {
                friendlyBlockedPeeks = 0;
                if (bot.isUsingItem()) {
                    bot.releaseUsingItem();
                    BotLog.action(bot, "peekaboo_shot_released", "target_type", target.getType());
                }
            }
            peekStage = PeekStage.BACK;
        }
        if (peekStep == null) {
            peekStep = CombatCore.beginStepByInput(peekHideSpot, true, false);
        }
        CombatCore.lookAtForBowShot(bot, target);
        CombatCore.StepStatus back = CombatCore.stepByInput(bot, peekStep);
        if (back == CombatCore.StepStatus.FAILED) {
            String why = peekStep.failure();
            peekStep = null;
            abandonPeekaboo(bot, "peekaboo_hide_step_failed:" + why);
            return;
        }
        if (back != CombatCore.StepStatus.ARRIVED) {
            return;
        }
        peekStep = null;
        peekStage = PeekStage.OUT;
        if (!shouldUseBow(bot)) {
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot);
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

    private boolean shouldBlock(AIPlayerEntity bot) {
        return bot.getOffhandItem().is(Items.SHIELD)
                && target != null
                && target.isAlive()
                && bot.distanceTo(target) <= CombatCore.ATTACK_RANGE + 1.0F
                && bot.getHealth() <= retreatHpThreshold + 6.0F;
    }

    private void beginBlock(AIPlayerEntity bot) {
        // Only worth raising when the cooldown leaves the shield its five warm-up ticks.
        float cooldownTicksLeft = bot.getCurrentItemAttackStrengthDelay()
                * (1.0F - Math.min(1.0F, bot.getAttackStrengthScale(0.5F)));
        if (cooldownTicksLeft < MIN_BLOCK_COOLDOWN_TICKS) {
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
        lowerReactiveShield(bot);
        super.onPause(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        cancelPeekStep(bot);
        lowerReactiveShield(bot);
        super.onAbort(bot);
    }

    private void lowerReactiveShield(AIPlayerEntity bot) {
        if (reactiveShieldRaised) {
            bot.releaseUsingItem();
            reactiveShieldRaised = false;
        }
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
