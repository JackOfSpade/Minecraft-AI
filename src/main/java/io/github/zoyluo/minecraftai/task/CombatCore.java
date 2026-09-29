package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.DangerCheck;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class CombatCore {
    public static final float ATTACK_RANGE = 3.0F;
    /**
     * Shared "am I already in a melee exchange" boundary. Kept independent from
     * {@code CombatTask.BOW_MELEE_SWITCH_DISTANCE} (same value) so target-priority decisions made
     * outside CombatTask (e.g. DangerWatcher's top-threat ranking) do not require a dependency on
     * CombatTask's private phase machinery.
     */
    public static final double MELEE_ENGAGEMENT_RANGE = ATTACK_RANGE * 1.5D;
    private static final double CLOSE_HOSTILE_PRESSURE_RANGE = 10.0D;
    /** Creepers can erase a zero-death mission before ordinary melee pressure is re-entered. */
    private static final double CREEPER_PRESSURE_RANGE = 16.0D;
    private static final double RANGED_HOSTILE_PRESSURE_RANGE = 20.0D;
    /** Vanilla Warden sonic boom: horizontal reach (SonicBoom behaviour closerThan(15, 20)). */
    public static final double WARDEN_SONIC_BOOM_RANGE = 15.0D;
    /** A warden remains pressure a little beyond its boom range, with or without line of sight. */
    private static final double WARDEN_PRESSURE_RANGE = WARDEN_SONIC_BOOM_RANGE + 2.0D;
    /** A flight from a warden must end outside its boom range, not merely at the generic escape distance. */
    public static final int WARDEN_ESCAPE_DISTANCE = (int) WARDEN_SONIC_BOOM_RANGE + 5;
    /** How long a mob that hurt the bot (or its owner) stays a known aggressor: 10 seconds. */
    private static final int HURT_MEMORY_TICKS = 200;

    private CombatCore() {
    }

    public static void equipMelee(AIPlayerEntity bot) {
        EquipAction.equipBestArmor(bot);
        ensureMeleeWeapon(bot);
    }

    /**
     * Re-evaluates the complete inventory at an attack boundary. This is intentionally above the
     * low-level interaction primitive: a melee hit may consume the held weapon's final durability,
     * and the combat owner must atomically select its physical successor before another tick can
     * continue empty-handed.
     */
    public static void ensureMeleeWeapon(AIPlayerEntity bot) {
        EquipAction.equipBestWeapon(bot);
    }

    /** Shared combat policy: projectile-capable mobs keep pressure while line of sight remains. */
    static boolean isRangedThreat(LivingEntity entity) {
        return entity instanceof RangedAttackMob;
    }

    /**
     * Mobs that hurt from range but are never a bow or melee target for this bot (ghast fireballs,
     * shulker bullets) still keep pressure while line of sight remains, exactly like a shooter.
     */
    static boolean isLongRangeDanger(LivingEntity entity) {
        return isRangedThreat(entity) || entity instanceof Ghast || entity instanceof Shulker;
    }

    /**
     * How far from {@code entity} a heal/settle pause is safe: the ordinary contact margin, the
     * explosive margin for creepers (vanilla ignition backs off beyond seven blocks) and the sonic
     * boom range for a warden.
     */
    static double safeDistanceFrom(LivingEntity entity, double contactSafeDistance,
                                   double explosiveSafeDistance) {
        if (entity instanceof Warden) {
            return WARDEN_PRESSURE_RANGE;
        }
        return isMeleeForbiddenThreat(entity) ? explosiveSafeDistance : contactSafeDistance;
    }

    /** Observable, reachable ranged attackers around the bot -- used to decide when cover/peekaboo
     *  tactics are warranted instead of plain kiting. */
    public static List<LivingEntity> rangedThreatsAround(AIPlayerEntity bot, double range) {
        return bot.level()
                .getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(range),
                        entity -> entity != bot
                                && entity.isAlive()
                                && isRangedThreat(entity)
                                && ObservableWorldQuery.canObserveEntity(bot, entity)
                                && hasLineOfSight(bot, entity));
    }

    /** Counts live hostiles whose current AI target is this bot -- the "aggro count" used to decide
     *  when a swarmed bot should fall back toward its owning player. */
    public static int countAggroedHostiles(AIPlayerEntity bot, double range) {
        return bot.level()
                .getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(range),
                        mob -> mob.isAlive() && mob.getTarget() == bot)
                .size();
    }

    /** Coarse 4-way compass direction from one block position toward another, ties broken toward
     *  the larger axis. Used to orient a peekaboo cover column between the bot and its target. */
    public static Direction dominantHorizontalDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    static double hostilePressureScanRange() {
        return RANGED_HOSTILE_PRESSURE_RANGE;
    }

    /**
     * A close hostile remains pressure even while rounding a corner. Beyond that close envelope,
     * only an observable ranged attacker with factual line of sight can still deal damage while
     * the bot is eating or settling another combat target.
     */
    static boolean isWithinHostilePressureEnvelope(AIPlayerEntity bot, LivingEntity entity) {
        double distanceSquared = bot.distanceToSqr(entity);
        if (distanceSquared
                <= CLOSE_HOSTILE_PRESSURE_RANGE * CLOSE_HOSTILE_PRESSURE_RANGE) {
            return true;
        }
        if (entity instanceof Creeper
                && distanceSquared <= CREEPER_PRESSURE_RANGE * CREEPER_PRESSURE_RANGE) {
            return true;
        }
        if (entity instanceof Warden
                && distanceSquared <= WARDEN_PRESSURE_RANGE * WARDEN_PRESSURE_RANGE) {
            // A sonic boom needs no melee contact and ignores armour: hold the whole boom range.
            return true;
        }
        return isLongRangeDanger(entity)
                && distanceSquared
                <= RANGED_HOSTILE_PRESSURE_RANGE * RANGED_HOSTILE_PRESSURE_RANGE
                && hasLineOfSight(bot, entity);
    }

    /**
     * Shared combat policy for mobs that need a dedicated tactic instead of generic melee.
     * Creepers require explosive spacing; Endermen require a low ceiling/water/leg trap strategy
     * that the ordinary approach/strike loop does not own.  Until those tactics exist, survival
     * safety must create distance rather than turn an interrupted mining mission into a duel.
     */
    static boolean isMeleeForbiddenThreat(LivingEntity entity) {
        // Never melee: a Warden (30 damage per hit, and a sonic boom that ignores armour), the
        // Wither (boss), ghasts and shulkers (out of reach or evade-only until they have their own
        // tactic), and a heart-bound Creaking, which cannot be damaged by the bot at all.
        // isHeartBound() only removes an attack option, so it can never hand the bot an advantage.
        return entity instanceof Creeper
                || entity instanceof EnderMan
                || entity instanceof Warden
                || entity instanceof WitherBoss
                || entity instanceof Ghast
                || entity instanceof Shulker
                || entity instanceof Creaking creaking && creaking.isHeartBound();
    }

    /** Owner or another bot: never a target of any strike or shot. */
    public static boolean isFriendly(AIPlayerEntity bot, Entity entity) {
        return StrikeLegality.isFriendly(bot, entity);
    }

    /**
     * True when {@code entity} is a hostile the bot may defend against.
     *
     * <ul>
     *   <li>Anything implementing {@link Enemy} or {@link Monster} counts (slimes, magma cubes,
     *       ghasts, phantoms, shulkers and hoglins are not {@code Monster}s), except a tiny slime,
     *       which deals no damage, and a piglin while the bot wears gold.</li>
     *   <li>A neutral mob (zombified piglin, enderman, ...) is a threat only once it has hurt the
     *       bot or its owner, or is provably angry at this exact bot.</li>
     *   <li>A spider is neutral in daylight unless it is visibly aggressive.</li>
     *   <li>Any other mob (a wolf, a golem, a bee) becomes a threat only when it hurt the bot or
     *       its owner: the victim knows who hurt it. A calm bystander is never auto-attacked.</li>
     * </ul>
     * Owner and other bots are never hostile. This is for DEFENCE decisions only.
     */
    public static boolean hostileTo(AIPlayerEntity bot, LivingEntity entity) {
        if (entity == null || entity == bot || !entity.isAlive() || isFriendly(bot, entity)) {
            return false;
        }
        if (hasHurtBotOrOwner(bot, entity)) {
            return true;
        }
        if (entity instanceof NeutralMob neutral) {
            // isAngryAt() binds persistent/universal anger to this exact bot; the live target is the
            // legacy signal the Enderman rule has always used.
            return neutral.isAngryAt(bot, bot.level())
                    || entity instanceof Mob mob && mob.getTarget() == bot;
        }
        if (entity instanceof Piglin && PiglinAi.isWearingSafeArmor(bot)) {
            return false;
        }
        if (entity instanceof Slime slime && !(entity instanceof MagmaCube) && slime.getSize() <= 1) {
            return false;
        }
        if (entity instanceof Spider spider
                && spider.getLightLevelDependentMagicValue() >= 0.5F
                && !spider.isAggressive()) {
            return false;
        }
        return entity instanceof Enemy || entity instanceof Monster;
    }

    /** True when {@code entity} hurt the bot, or the bot's owner, within the last ten seconds. */
    public static boolean hasHurtBotOrOwner(AIPlayerEntity bot, LivingEntity entity) {
        if (recentlyHurtBy(bot, entity)) {
            return true;
        }
        Optional<UUID> ownerId = AIPlayerManager.INSTANCE.ownerOf(bot);
        if (ownerId.isEmpty() || bot.level().getServer() == null) {
            return false;
        }
        ServerPlayer owner = bot.level().getServer().getPlayerList().getPlayer(ownerId.get());
        return owner != null && owner.level() == bot.level() && recentlyHurtBy(owner, entity);
    }

    private static boolean recentlyHurtBy(LivingEntity victim, LivingEntity attacker) {
        return victim.getLastHurtByMob() == attacker
                && victim.tickCount - victim.getLastHurtByMobTimestamp() <= HURT_MEMORY_TICKS;
    }

    public static Optional<LivingEntity> nearestTarget(AIPlayerEntity bot, EntityType<?> targetType, double range) {
        return bot.level()
                .getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(range),
                        entity -> entity.isAlive() && entity.getType().equals(targetType) && entity != bot
                                && !isFriendly(bot, entity))
                .stream()
                .filter(entity -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    public static Optional<LivingEntity> nearestHostileAround(AIPlayerEntity bot, BlockPos center, double range) {
        AABB box = new AABB(center).inflate(range);
        return bot.level()
                .getEntitiesOfClass(LivingEntity.class, box,
                        entity -> entity != bot
                                && hostileTo(bot, entity)
                                && !isMeleeForbiddenThreat(entity))
                .stream()
                .filter(entity -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    public static boolean inMeleeRange(AIPlayerEntity bot, LivingEntity target) {
        return bot.distanceTo(target) <= ATTACK_RANGE;
    }

    // Line-of-sight/reachability check: casts one block raycast from the bot's eyes to the target's
    // eyes; a solid block blocking the middle (result is not MISS) is treated as unreachable
    // (blocked by a wall/tunnel). The raycast only tests blocks, not entities, which is exactly
    // what's needed to determine "is a wall in the way". A blocked hostile can't land a melee hit,
    // can't land a ranged shot, and a creeper can't explode on the bot, so it should not
    // trigger/sustain combat (observed bug: a mob blocked by blocks left the bot stuck "in combat"
    // forever).
    public static boolean hasLineOfSight(AIPlayerEntity bot, LivingEntity mob) {
        HitResult hit = bot.level().clip(new ClipContext(
                bot.getEyePosition(), mob.getEyePosition(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                bot));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void lookAt(AIPlayerEntity bot, LivingEntity target) {
        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        LookAction.lookAt(bot, targetCenter);
    }

    /**
     * Aims for a bow shot at {@code target}'s center: same yaw as {@link #lookAt}, but the pitch is
     * solved via {@link ProjectileBallistics} so a fully-drawn arrow actually lands there instead of
     * dropping short with increasing range. {@link #lookAt} points straight at the target, which is
     * correct for melee (and negligibly different from this at melee range) but is the aim of a
     * human archer who never compensates for arrow drop -- fine at a few blocks, an increasingly
     * clear miss-low at the far end of ranged engagement.
     */
    public static void lookAtForBowShot(AIPlayerEntity bot, LivingEntity target) {
        Vec3 eye = bot.getEyePosition();
        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        double dx = targetCenter.x - eye.x;
        double dz = targetCenter.z - eye.z;
        double dy = targetCenter.y - eye.y;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D));
        double pitch = ProjectileBallistics.pitchForShot(
                horizontalDistance, dy, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
        LookAction.setYawPitch(bot, yaw, (float) pitch);
    }

    public static void startApproach(AIPlayerEntity bot, LivingEntity target) {
        ActionResult result = bot.getActionPack().startPathTo(target.blockPosition());
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(target.position());
        }
    }

    /**
     * True when a swing at {@code target} would be legal right now: inside the melee boundary,
     * the target's box inside vanilla's entity interaction range, no colliding block in the way, and
     * not a friend. A wall or window between the bot and its target is never struck through.
     */
    public static boolean canStrikeNow(AIPlayerEntity bot, LivingEntity target) {
        return inMeleeRange(bot, target) && StrikeLegality.strikeRefusal(bot, target) == null;
    }

    public static boolean strikeIfReady(AIPlayerEntity bot, LivingEntity target) {
        lookAt(bot, target);
        if (isFriendly(bot, target) || isMeleeForbiddenThreat(target)) {
            return false;
        }
        if (!inMeleeRange(bot, target)) {
            return false;
        }
        if (bot.getAttackStrengthScale(0.5F) < 0.95F) {
            return false;
        }
        if (bot.isUsingItem()) {
            // A raised shield is lowered to swing, exactly as a player releases it; any other use in
            // progress (a drawn bow, food) keeps the hands busy and forbids the attack.
            if (bot.getUsedItemHand() != net.minecraft.world.InteractionHand.OFF_HAND
                    || !bot.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) {
                return false;
            }
            bot.releaseUsingItem();
        }
        return InteractAction.attackEntity(bot, target).isSuccess();
    }

    /**
     * A sideways input that cannot walk the bot off a ledge or into lava: the desired direction if
     * both the near and the far cell it would cross are standable and free of hazards, else the
     * opposite direction under the same test, else no strafe at all.
     */
    public static float safeStrafeInput(AIPlayerEntity bot, float desired) {
        if (desired == 0.0F) {
            return 0.0F;
        }
        if (canStrafeToward(bot, desired)) {
            return desired;
        }
        return canStrafeToward(bot, -desired) ? -desired : 0.0F;
    }

    private static boolean canStrafeToward(AIPlayerEntity bot, float input) {
        double yaw = Math.toRadians(bot.getYRot());
        double sign = Math.signum(input);
        double lateralX = Math.cos(yaw) * sign;
        double lateralZ = Math.sin(yaw) * sign;
        for (double reach : new double[]{0.9D, 1.6D}) {
            BlockPos cell = BlockPos.containing(
                    bot.getX() + lateralX * reach, bot.getY(), bot.getZ() + lateralZ * reach);
            if (!isSafeStrafeCell(bot.level(), cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeStrafeCell(ServerLevel level, BlockPos feet) {
        if (DangerCheck.scan(level, feet) != null) {
            return false;
        }
        if (Standability.isStandableFresh(level, feet)) {
            return true;
        }
        // A one-block step down is still a safe footing; a longer drop is a ledge.
        BlockPos lower = feet.below();
        return DangerCheck.scan(level, lower) == null && Standability.isStandableFresh(level, lower);
    }
}
