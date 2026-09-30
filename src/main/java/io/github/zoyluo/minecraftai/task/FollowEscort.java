package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * What a following bot does about a hostile that gets in its face: it knocks it away with a swing and keeps following.
 *
 * <p>It never stops the follow, never paths toward the hostile and never shoots. Candidates are what {@link CombatCore#hostileTo}
 * calls hostile (a MARKED foreign bot included, a SUSPECT one never), what the bot's own eyes see ({@link ObservableWorldQuery}) and
 * what melee is allowed against ({@link CombatCore#isMeleeForbiddenThreat}: no creeper, no warden...), within
 * {@value #CANDIDATE_RANGE} blocks. A swing is made only on a READY tick (the cooldown is full, no item is in use and the legal melee
 * reach is met): {@link CombatCore#strikeIfReady} turns the bot toward its target first, and on the legacy engine that yaw steers the
 * next physics tick, so it must not be called on ticks without a swing. The weapon is chosen when a candidate is near (at most every
 * {@value #WEAPON_SWAP_INTERVAL_TICKS} ticks). Next to a calm warden the escort is silent: no swing, no weapon swap (the sound of a
 * fight wakes it), unless the bot has just been hurt.</p>
 */
final class FollowEscort {
    /** A hostile this close is one the follower prepares for (weapon in hand) and may strike. */
    static final double CANDIDATE_RANGE = 4.5D;
    /** A hostile this close keeps the follow task critical (ticked even under a degraded TPS). */
    static final double ENGAGED_RANGE = 6.0D;
    /** Weapon swaps are at most this often. */
    static final int WEAPON_SWAP_INTERVAL_TICKS = 40;
    /** The attack-strength scale a swing needs (full cooldown, the same as {@code CombatCore.strikeIfReady}). */
    static final float READY_STRENGTH = 0.95F;
    /** A calm warden this close silences the escort. */
    static final double CALM_WARDEN_RANGE = 16.0D;
    /** The escort keeps striking next to a calm warden only when the bot took damage this recently. */
    static final int HURT_WINDOW_TICKS = 40;
    /** After a swing the follower does not turn back toward the player for this many ticks. */
    static final int NO_FACE_TICKS = 10;
    private static final int LOG_INTERVAL_TICKS = 40;

    private long nextWeaponSwapTick = Long.MIN_VALUE;
    private long lastStrikeTick = Long.MIN_VALUE;
    /** The last tick a ready swing found its target not yet under the crosshair (the aim is still turning, at human speed). */
    private long lastAimTick = Long.MIN_VALUE;
    private long nextLogTick = Long.MIN_VALUE;
    private boolean engaged;
    private int strikes;

    /** A hostile within {@value #ENGAGED_RANGE} blocks was seen on the last tick. */
    boolean engaged() {
        return engaged;
    }

    /** Swings landed since the task started. */
    int strikes() {
        return strikes;
    }

    /** The game tick of the last swing, or {@link Long#MIN_VALUE}. */
    long lastStrikeTick() {
        return lastStrikeTick;
    }

    /** The follow is not on foot right now (a boat, a swim, or the player is gone): nothing is engaged. */
    void disengage() {
        engaged = false;
    }

    void reset() {
        nextWeaponSwapTick = Long.MIN_VALUE;
        lastStrikeTick = Long.MIN_VALUE;
        nextLogTick = Long.MIN_VALUE;
        engaged = false;
        strikes = 0;
    }

    /** True while the follower should not turn toward the player because it has just swung at something. */
    boolean holdsFacing(long now) {
        return lastStrikeTick != Long.MIN_VALUE && now - lastStrikeTick < NO_FACE_TICKS
                || lastAimTick != Long.MIN_VALUE && now - lastAimTick <= 1L;
    }

    /** The game tick of the last ready swing that was still turning toward its target, or {@link Long#MIN_VALUE}. */
    long lastAimTick() {
        return lastAimTick;
    }

    /**
     * One tick of the escort, after the follower's movement decisions. Returns the swing's target when one landed this tick.
     */
    LivingEntity tick(AIPlayerEntity bot, ServerPlayer followed) {
        engaged = false;
        if (!MinecraftAiConfig.get().behaviour().followOrDefaults().escortOnlyEnabled()) {
            return null;
        }
        List<LivingEntity> near = bot.level().getEntitiesOfClass(LivingEntity.class,
                bot.getBoundingBox().inflate(ENGAGED_RANGE),
                entity -> entity != bot && entity != followed && entity.isAlive()
                        && bot.distanceTo(entity) <= ENGAGED_RANGE
                        && CombatCore.hostileTo(bot, entity)
                        && !CombatCore.isMeleeForbiddenThreat(entity)
                        && ObservableWorldQuery.canNoticeCreature(bot, entity));
        if (near.isEmpty()) {
            return null;
        }
        engaged = true;
        long now = bot.level().getGameTime();
        if (silenced(bot, now)) {
            return null;
        }
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity entity : near) {
            double distance = bot.distanceTo(entity);
            if (distance <= CANDIDATE_RANGE && distance < nearestDistance) {
                nearest = entity;
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            return null;
        }
        if (now >= nextWeaponSwapTick && !bot.isUsingItem()) {
            nextWeaponSwapTick = now + WEAPON_SWAP_INTERVAL_TICKS;
            CombatCore.ensureMeleeWeapon(bot);
        }
        // Only on a ready tick: the swing's own look-at must not steer the follower on the other ticks.
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity entity : near) {
            double distance = bot.distanceTo(entity);
            if (distance < best && CombatCore.canStrikeNow(bot, entity)) {
                target = entity;
                best = distance;
            }
        }
        if (target == null || bot.getAttackStrengthScale(0.5F) < READY_STRENGTH || bot.isUsingItem()) {
            return null;
        }
        if (!CombatCore.strikeIfReady(bot, target)) {
            // The swing is ready and legal but the aim is still on its way (human turn speed): the follower keeps its facing
            // on the target until the crosshair is on it, so the walker does not turn it back every tick.
            lastAimTick = now;
            return null;
        }
        lastStrikeTick = now;
        strikes++;
        if (now >= nextLogTick) {
            nextLogTick = now + LOG_INTERVAL_TICKS;
            BotLog.action(bot, "follow_escort_strike", "target", target.getType(), "distance", Math.round(best * 10.0D) / 10.0D,
                    "pos", LogFields.pos(bot.blockPosition()), "strikes", strikes);
        }
        return target;
    }

    /** A calm observed warden within 16 blocks silences the escort, unless the bot was hurt in the last 40 ticks. */
    private static boolean silenced(AIPlayerEntity bot, long now) {
        return io.github.zoyluo.minecraftai.action.QuietZone.calmWardenObservedWithin(bot, CALM_WARDEN_RANGE)
                && !RecentDamage.tookEntityDamage(bot.getUUID(), now, HURT_WINDOW_TICKS);
    }
}
