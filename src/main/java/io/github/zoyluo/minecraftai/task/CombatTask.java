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
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

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
    /** "there are other ranged enemies" -- at least one besides whichever one is currently targeted. */
    private static final int PEEKABOO_MIN_RANGED_THREATS = 2;
    private static final double PEEKABOO_SCAN_RANGE = 24.0D;
    private static final int PEEKABOO_BUILD_RETRY_LIMIT = 3;
    private static final int PEEKABOO_EXPOSE_TICKS = 2;
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
    private BlockPos peekHideSpot;
    private BlockPos peekCoverFeet;
    private BlockPos peekExposeSpot;
    private int peekBuildAttempts;
    private int peekCycleTicks;
    private int nextPeekabooAttemptElapsed;

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
        this.defensiveAnchor = defensiveAnchor == null ? null : defensiveAnchor.toImmutable();
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

    @Override
    public String describe() {
        return "Attacking " + Registries.ENTITY_TYPE.getId(targetType) + " " + kills + "/" + targetKills + " phase=" + phase;
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
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            finishRangedLoadout(bot);
            fail("combat_timeout");
            return;
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
        if (target != null && target.isAlive() && !CombatCore.hasLineOfSight(bot, target)) {
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
        if (!bot.getOffHandStack().isOf(Items.SHIELD) && !EquipAction.equipShieldOffhand(bot)) {
            return false;
        }
        ProjectileThreat.Incoming incoming = ProjectileThreat.mostImminent(bot).orElse(null);
        LivingEntity fusingCreeper = incoming == null ? nearbyImminentCreeper(bot) : null;
        if (incoming == null && fusingCreeper == null) {
            if (reactiveShieldRaised) {
                lowerReactiveShield(bot);
                BotLog.action(bot, "reactive_shield_lowered");
            }
            return false;
        }
        Vec3d faceTowards = incoming != null ? incoming.projectile().getEntityPos() : fusingCreeper.getEntityPos();
        LookAction.lookAt(bot, faceTowards);
        if (!bot.isUsingItem() || bot.getActiveHand() != Hand.OFF_HAND) {
            InteractAction.useItemInAir(bot, Hand.OFF_HAND);
        }
        bot.getActionPack().stopMovement();
        if (!reactiveShieldRaised) {
            reactiveShieldRaised = true;
            BotLog.action(bot, "reactive_shield_raised",
                    "reason", incoming != null ? "incoming_projectile" : "creeper_fuse",
                    "source", incoming != null
                            ? Registries.ENTITY_TYPE.getId(incoming.projectile().getType()).toString()
                            : "creeper");
        }
        return true;
    }

    private static LivingEntity nearbyImminentCreeper(AIPlayerEntity bot) {
        return bot.getEntityWorld().getEntitiesByClass(CreeperEntity.class,
                        bot.getBoundingBox().expand(SHIELD_CREEPER_FUSE_RANGE),
                        creeper -> creeper.isAlive()
                                && ObservableWorldQuery.canObserveEntity(bot, creeper)
                                && creeper.getLerpedFuseTime(1.0F) >= SHIELD_CREEPER_FUSE_THRESHOLD)
                .stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
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
        if (CombatCore.inMeleeRange(bot, target)) {
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
            ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
            if (result.isFailed()) {
                finishRangedLoadout(bot);
                CombatCore.ensureMeleeWeapon(bot);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        }
        // getItemUseTime() is Minecraft's own count of ticks since the CURRENT draw began -- it
        // self-resets if something upstream (e.g. ActionPack#stopAll from another task) interrupted
        // and restarted the use. A hand-rolled tick counter here would desync from that and could
        // release a shot well before it's actually at full pull.
        if (bot.getItemUseTime() >= BOW_CHARGE_TICKS) {
            bot.stopUsingItem();
            phase = Phase.APPROACH;
        }
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
        CombatCore.lookAt(bot, target);
        if (shouldBlock(bot)) {
            beginBlock(bot);
            return;
        }
        if (bot.distanceTo(target) > CombatCore.ATTACK_RANGE) {
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        CombatCore.ensureMeleeWeapon(bot);
        if (CombatCore.strikeIfReady(bot, target)) {
            // The hit itself may have consumed the final point of durability. Equip the physical
            // successor in the same task tick instead of allowing logged empty-hand attacks.
            CombatCore.ensureMeleeWeapon(bot);
            repositionTicks = 8;
            phase = Phase.REPOSITION;
        }
    }

    private void block(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            bot.stopUsingItem();
            kills++;
            finishOrAcquire(bot);
            return;
        }
        CombatCore.lookAt(bot, target);
        if (!bot.isUsingItem()) {
            InteractAction.useItemInAir(bot, Hand.OFF_HAND);
        }
        blockTicks--;
        if (blockTicks <= 0 || bot.distanceTo(target) > CombatCore.ATTACK_RANGE + 1.5F) {
            bot.stopUsingItem();
            phase = Phase.STRIKE;
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
        bot.getActionPack().setStrafing(elapsed % 40 < 20 ? 0.45F : -0.45F);
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
                    bot, threat, threat.getBlockPos(), RETREAT_STEP_DISTANCE);
            if (retreatDestination == null) {
                fail("combat_no_valid_retreat_route");
            }
        }
    }

    private void beginRetreat(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        bot.clearActiveItem();
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
        if (!eating && InventoryAction.findFoodSlot(bot) >= 0) {
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
        BlockPos here = bot.getBlockPos();
        if (belowDefenseFloor(here) || outsideDefenseRadius(here)) {
            return disengageOrRetreatFromImmediatePressure(
                    bot, "bot_left_leash", here);
        }
        if (target != null && target.isAlive()) {
            BlockPos targetPos = target.getBlockPos();
            if (belowDefenseFloor(targetPos) || outsideDefenseRadius(targetPos)) {
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
                ? Double.POSITIVE_INFINITY : bot.squaredDistanceTo(nearest);
        for (LivingEntity hostile : observableActiveHostiles(bot)) {
            double distance = bot.squaredDistanceTo(hostile);
            if (distance < nearestDistance) {
                nearest = hostile;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private List<LivingEntity> observableActiveHostiles(AIPlayerEntity bot) {
        return bot.getEntityWorld().getEntitiesByClass(
                LivingEntity.class,
                bot.getBoundingBox().expand(CombatCore.hostilePressureScanRange()),
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
        double safeDistance = CombatCore.isMeleeForbiddenThreat(entity)
                ? CREEPER_HEAL_SAFE_DISTANCE : HEAL_SAFE_DISTANCE;
        return bot.distanceTo(entity) < safeDistance;
    }

    private boolean belowDefenseFloor(BlockPos pos) {
        return pos.getY() < defensiveAnchor.getY() - DEFENSIVE_MAX_VERTICAL_DROP;
    }

    private boolean outsideDefenseRadius(BlockPos pos) {
        double dx = pos.getX() - defensiveAnchor.getX();
        double dz = pos.getZ() - defensiveAnchor.getZ();
        return dx * dx + dz * dz
                > DEFENSIVE_MAX_HORIZONTAL_DISTANCE * DEFENSIVE_MAX_HORIZONTAL_DISTANCE;
    }

    private void endDefensiveEngagement(AIPlayerEntity bot, String reason, BlockPos observed) {
        finishRangedLoadout(bot);
        bot.getActionPack().stopAll();
        BotLog.danger(bot, "defensive_combat_disengaged",
                "reason", reason,
                "anchor", defensiveAnchor.toShortString(),
                "observed", observed.toShortString());
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

    private boolean shouldUseBow(AIPlayerEntity bot) {
        return target != null
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
                && bot.getBlockPos().equals(peekHideSpot)
                && isObservableSolid(bot, peekCoverFeet) && isObservableSolid(bot, peekCoverFeet.up())) {
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
                bot.getBlockPos(), target.getBlockPos());
        if (towardTarget == null) {
            abandonPeekaboo(bot, "peekaboo_missing_direction");
            return;
        }
        BlockPos hideSpot = bot.getBlockPos().toImmutable();
        BlockPos coverFeet = hideSpot.offset(towardTarget).toImmutable();
        Direction sideStep = towardTarget.rotateYClockwise();
        BlockPos exposeSpot = hideSpot.offset(sideStep).toImmutable();
        if (!canStandAt(bot, exposeSpot)) {
            exposeSpot = hideSpot.offset(sideStep.getOpposite()).toImmutable();
            if (!canStandAt(bot, exposeSpot)) {
                abandonPeekaboo(bot, "peekaboo_no_expose_spot");
                return;
            }
        }
        String failure = placePeekabooColumnBlock(bot, coverFeet);
        if (failure == null) {
            failure = placePeekabooColumnBlock(bot, coverFeet.up());
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
        if (bot.getBoundingBox().intersects(new Box(target))) {
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
                && ObservableWorldQuery.canObserveCell(bot, feet.up())
                && ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, feet.down());
    }

    private static boolean isObservableSolid(AIPlayerEntity bot, BlockPos pos) {
        if (!ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos)) {
            return false;
        }
        return !bot.getEntityWorld().getBlockState(pos)
                .getCollisionShape(bot.getEntityWorld(), pos).isEmpty();
    }

    private void abandonPeekaboo(AIPlayerEntity bot, String reason) {
        BotLog.action(bot, "peekaboo_abandoned", "reason", reason);
        peekHideSpot = null;
        peekCoverFeet = null;
        peekExposeSpot = null;
        nextPeekabooAttemptElapsed = elapsed + PEEKABOO_RETRY_COOLDOWN_TICKS;
        phase = Phase.RANGED;
    }

    private void coverHide(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire(bot);
            return;
        }
        if (!bot.getBlockPos().equals(peekHideSpot) && bot.getActionPack().isPathExecutorIdle()) {
            FakePlayerMotion.stepToStandable(bot, peekHideSpot, "peekaboo_return_to_hide");
        }
        if (rangedLoadout == null) {
            rangedLoadout = EquipAction.equipBestRangedLoadout(bot, target).orElse(null);
            if (rangedLoadout == null) {
                CombatCore.ensureMeleeWeapon(bot);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        }
        if (!bot.isUsingItem()) {
            ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
            if (result.isFailed()) {
                finishRangedLoadout(bot);
                CombatCore.ensureMeleeWeapon(bot);
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        }
        // See the identical check in ranged(): getItemUseTime() tracks the CURRENT draw, so it
        // can't fire early even if this draw was interrupted and restarted mid-charge.
        if (bot.getItemUseTime() >= BOW_CHARGE_TICKS) {
            phase = Phase.COVER_PEEK;
            peekCycleTicks = 0;
        }
    }

    private void coverPeek(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            bot.stopUsingItem();
            kills++;
            finishOrAcquire(bot);
            return;
        }
        peekCycleTicks++;
        if (peekCycleTicks == 1) {
            if (!FakePlayerMotion.stepToStandable(bot, peekExposeSpot, "peekaboo_peek_out")) {
                bot.stopUsingItem();
                abandonPeekaboo(bot, "peekaboo_peek_step_failed");
                return;
            }
            CombatCore.lookAtForBowShot(bot, target);
            return;
        }
        if (peekCycleTicks <= PEEKABOO_EXPOSE_TICKS) {
            CombatCore.lookAtForBowShot(bot, target);
            return;
        }
        if (bot.isUsingItem()) {
            bot.stopUsingItem();
            BotLog.action(bot, "peekaboo_shot_released", "target_type", target.getType());
        }
        if (!FakePlayerMotion.stepToStandable(bot, peekHideSpot, "peekaboo_duck_back")) {
            abandonPeekaboo(bot, "peekaboo_hide_step_failed");
            return;
        }
        if (!shouldUseBow(bot)) {
            finishRangedLoadout(bot);
            CombatCore.ensureMeleeWeapon(bot);
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        // Reuses the same single entry point every reload goes through: it restores/re-derives the
        // ranged loadout and re-checks peekaboo eligibility, exactly like leaving RANGED normally.
        beginRanged(bot);
    }

    private boolean shouldBlock(AIPlayerEntity bot) {
        return bot.getOffHandStack().isOf(Items.SHIELD)
                && target != null
                && target.isAlive()
                && bot.distanceTo(target) <= CombatCore.ATTACK_RANGE + 1.0F
                && bot.getHealth() <= retreatHpThreshold + 6.0F;
    }

    private void beginBlock(AIPlayerEntity bot) {
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
        bot.clearActiveItem();
        if (!rangedLoadout.restore(bot)) {
            BotLog.action(bot, "restore_ranged_offhand_skipped", "reason", "inventory_changed");
        }
        rangedLoadout = null;
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        lowerReactiveShield(bot);
        super.onPause(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        finishRangedLoadout(bot);
        lowerReactiveShield(bot);
        super.onAbort(bot);
    }

    private void lowerReactiveShield(AIPlayerEntity bot) {
        if (reactiveShieldRaised) {
            bot.stopUsingItem();
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
