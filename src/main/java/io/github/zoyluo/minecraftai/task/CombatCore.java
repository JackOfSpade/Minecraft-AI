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
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
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
        return entity instanceof RangedAttackMob || isDrawnRangedForeignBot(entity);
    }

    /**
     * A MARKED foreign bot that is drawing a bow (or raising a trident) or holds a charged crossbow counts as a ranged attacker,
     * like a skeleton. The hostility itself (and who can see it) is decided by {@link HostileBotLedger}; this is only its weapon.
     */
    static boolean isDrawnRangedForeignBot(LivingEntity entity) {
        return entity instanceof ServerPlayer player
                && HostileBotLedger.isMarked(player)
                && HostileBotIntent.holdsDrawnRanged(player);
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
        // Wither and Ender Dragon (bosses; the End is out of scope for melee), ghasts and shulkers
        // (out of reach or evade-only until they have their own tactic), and a heart-bound Creaking,
        // which cannot be damaged by the bot at all.
        // isHeartBound() only removes an attack option, so it can never hand the bot an advantage.
        return entity instanceof Creeper
                || entity instanceof EnderMan
                || entity instanceof Warden
                || entity instanceof WitherBoss
                || entity instanceof EnderDragon
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
     *   <li>A foreign bot (a fake player that is not ours, such as a PvP BOT inhabitant) is hostile only while it is a visible MARKED
     *       aggressor: it hit the owner or a Minecraft-AI bot (or aimed at one exclusively) and the bot or its owner sees it
     *       ({@link HostileBotLedger}). Sibling bots of the same owner count like the bot itself for the hurt-by rule.</li>
     * </ul>
     * The owner and every Minecraft-AI bot are never hostile. This is for DEFENCE decisions only.
     */
    public static boolean hostileTo(AIPlayerEntity bot, LivingEntity entity) {
        if (entity == null || entity == bot || !entity.isAlive() || isFriendly(bot, entity)) {
            return false;
        }
        // A foreign bot (a PvP BOT inhabitant) that hit, or visibly took aim at, the owner or a Minecraft-AI bot is an enemy while the
        // bot or its owner can see it (HostileBotLedger); it is friendly to every strike check until then.
        if (entity instanceof ServerPlayer foreign && HostileBotLedger.isVisibleAggressor(bot, foreign)) {
            return true;
        }
        if (hasHurtBotOrOwner(bot, entity)) {
            return true;
        }
        if (entity instanceof NeutralMob neutral) {
            // Server-state reads of this rule (documented, not hidden): isAngryAt() reads the mob's
            // persistent anger target (persisted, not AI-goal state; universal anger is a world
            // rule), hasHurtBotOrOwner() above reads the bot's own hurt-by memory. Mob.getTarget()
            // is unsynced AI state and is deliberately NOT generalised to every neutral (a
            // zombified piglin or wolf targeting the bot is already caught by the two facts
            // above once it is provoked); it stays only for the legacy Enderman rule, whose
            // anger is a stare the persistent-anger fields do not always carry.
            return neutral.isAngryAt(bot, bot.level())
                    || entity instanceof EnderMan enderman && enderman.getTarget() == bot;
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

    /**
     * True when {@code entity} hurt the bot, the bot's owner, or a sibling bot of the same owner within the last ten seconds. The owner
     * and the sibling list are looked up once per call.
     */
    public static boolean hasHurtBotOrOwner(AIPlayerEntity bot, LivingEntity entity) {
        if (recentlyHurtBy(bot, entity)) {
            return true;
        }
        Optional<UUID> ownerId = AIPlayerManager.INSTANCE.ownerOf(bot);
        if (ownerId.isEmpty() || bot.level().getServer() == null) {
            return false;
        }
        ServerPlayer owner = bot.level().getServer().getPlayerList().getPlayer(ownerId.get());
        if (owner != null && owner.level() == bot.level() && recentlyHurtBy(owner, entity)) {
            return true;
        }
        for (AIPlayerEntity sibling : AIPlayerManager.INSTANCE.botsOf(ownerId.get())) {
            if (sibling != bot && sibling.level() == bot.level() && recentlyHurtBy(sibling, entity)) {
                return true;
            }
        }
        return false;
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
        return nearestHostileAround(bot, center, range, entity -> true);
    }

    /** {@link #nearestHostileAround(AIPlayerEntity, BlockPos, double)} restricted to entities {@code allowed} accepts. */
    public static Optional<LivingEntity> nearestHostileAround(AIPlayerEntity bot,
                                                              BlockPos center,
                                                              double range,
                                                              java.util.function.Predicate<LivingEntity> allowed) {
        AABB box = new AABB(center).inflate(range);
        return bot.level()
                .getEntitiesOfClass(LivingEntity.class, box,
                        entity -> entity != bot
                                && hostileTo(bot, entity)
                                && !isMeleeForbiddenThreat(entity)
                                && allowed.test(entity))
                .stream()
                .filter(entity -> SharedVision.seenByBotOrOwner(bot, entity))
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

    /**
     * {@link #hasLineOfSight} for combat bookkeeping (the lost-sight timer, whether a threat is worth a task): the bot's own line
     * of sight, or, for a foreign bot only, the owner's nomination ({@link SharedVision#ownerSees}). It nominates, it never permits a
     * strike: {@code StrikeLegality.strikeRefusal} still needs the bot's own reach and collider line of sight.
     */
    public static boolean hasLineOfSightOrOwnerSees(AIPlayerEntity bot, LivingEntity target) {
        return hasLineOfSight(bot, target) || SharedVision.ownerSees(bot, target);
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

    // ------------------------------------------------------------------ walked one-cell steps

    /**
     * Longest a walked one-cell step may take. A player covers a block in about six ticks from a
     * standstill (walking 0.216 blocks per tick at steady state), so ten is a bound for a step that
     * is blocked or shoved, not a pace to plan on.
     */
    public static final int STEP_TIMEOUT_TICKS = 10;
    /**
     * Vanilla item-use slowdown (LocalPlayer.aiStep: movement input times 0.2 while an item is in use, a
     * drawn bow or a raised shield): the walked step scales its keys by this and, having done so, allows the
     * proportionally longer walk ({@link #STEP_TIMEOUT_TICKS} times the reciprocal).
     */
    private static final float STEP_ITEM_USE_SLOWDOWN = 0.2F;
    /** A full-speed tick costs this many budget units and a slowed tick one, so the budget is {@code STEP_TIMEOUT_TICKS} full-speed ticks. */
    private static final int STEP_FULL_TICK_COST = Math.round(1.0F / STEP_ITEM_USE_SLOWDOWN);
    private static final int STEP_TIMEOUT_BUDGET = STEP_TIMEOUT_TICKS * STEP_FULL_TICK_COST;
    /** A step ends once the bot stands this close to the centre of its cell (and has settled). */
    private static final double STEP_CENTER_TOLERANCE = 0.35D;
    /** Horizontal blocks per tick below which the bot counts as settled on its cell. */
    private static final double STEP_SETTLED_SPEED = 0.10D;
    /**
     * Ground friction leaves a released walker sliding about 1.2 ticks worth of its speed; keys are
     * let go that far before the centre so the slide lands on it, as a player taps the key early.
     */
    private static final double STEP_BRAKE_FACTOR = 1.3D;
    /** A sprint needs more than this many food points (LocalPlayer.aiStep: hunger above 6). */
    private static final int STEP_SPRINT_FOOD_FLOOR = 6;

    public enum StepStatus {
        MOVING,
        ARRIVED,
        FAILED
    }

    /**
     * One walked step to an adjacent cell, driven by ordinary movement inputs (forward and strafe
     * keys, exactly the fields a client's key state feeds vanilla physics with) instead of a
     * teleport. The caller owns the object and passes it to {@link #stepByInput} once per tick.
     */
    public static final class InputStep {
        private final BlockPos cell;
        private final boolean keepAim;
        private final boolean sprint;
        private int ticks;
        /** Timeout budget spent: {@link #STEP_FULL_TICK_COST} per full-speed tick, 1 per item-use-slowed tick. */
        private int budgetSpent;
        private Vec3 lastPosition;
        private String failure;

        private InputStep(BlockPos cell, boolean keepAim, boolean sprint) {
            this.cell = cell.immutable();
            this.keepAim = keepAim;
            this.sprint = sprint;
        }

        public BlockPos cell() {
            return cell;
        }

        /** Why the step failed, or {@code null} while it has not. */
        public String failure() {
            return failure;
        }

        public int ticks() {
            return ticks;
        }
    }

    /**
     * Starts a walked step to the adjacent, same-height {@code cell}.
     *
     * @param keepAim keep the current look direction and strafe/back up into the cell (a raised
     *                bow stays aimed at its target); otherwise the bot turns to face the cell
     * @param sprint  sprint while walking (never at six food points or fewer, never while using an item)
     */
    public static InputStep beginStepByInput(BlockPos cell, boolean keepAim, boolean sprint) {
        return new InputStep(cell, keepAim, sprint);
    }

    /**
     * Advances a walked step by one tick: re-proves the destination (standable, no fire, lava or
     * void) and the course every tick, writes the forward/strafe inputs, and releases the keys
     * early enough that the slide ends on the cell centre. There is no teleport and no velocity
     * reset: the bot moves at the speed vanilla physics gives its inputs, so it is never faster than
     * a player. While the bot uses an item (a drawn bow, a raised shield) the
     * inputs are scaled by vanilla's 0.2 item-use slowdown, so a peek or a duck covers a block at a
     * human's pace (and the step timeout grows to match); a general slowdown for all bot movement is
     * a separate concern of the input bridge.
     *
     * <p>Inputs are released on {@link StepStatus#ARRIVED} and {@link StepStatus#FAILED}; a caller
     * that abandons a step still in progress must call {@link #cancelStep}.</p>
     */
    public static StepStatus stepByInput(AIPlayerEntity bot, InputStep step) {
        step.ticks++;
        Vec3 position = bot.position();
        double speed = step.lastPosition == null ? 0.0D
                : Math.hypot(position.x - step.lastPosition.x, position.z - step.lastPosition.z);
        step.lastPosition = position;

        BlockPos cell = step.cell;
        BlockPos here = bot.blockPosition();
        if (step.ticks == 1) {
            if (here.getY() != cell.getY()
                    || Math.abs(here.getX() - cell.getX()) > 1
                    || Math.abs(here.getZ() - cell.getZ()) > 1) {
                return failStep(bot, step, "not_adjacent");
            }
            // The step takes the bot over from any route it was following.
            bot.getActionPack().stopNavigation();
        }
        String hazard = step.ticks == 1 ? stepRefusal(bot, cell) : stepHazard(bot.level(), cell);
        if (hazard != null) {
            return failStep(bot, step, hazard);
        }
        double dx = cell.getX() + 0.5D - position.x;
        double dz = cell.getZ() + 0.5D - position.z;
        double distance = Math.hypot(dx, dz);
        if (distance > 2.0D || bot.getY() < cell.getY() - 0.6D) {
            return failStep(bot, step, "left_course");
        }
        boolean inCell = here.equals(cell);
        if (inCell && distance <= STEP_CENTER_TOLERANCE && speed <= STEP_SETTLED_SPEED) {
            bot.getActionPack().stopMovement();
            return StepStatus.ARRIVED;
        }
        boolean usingItem = bot.isUsingItem();
        step.budgetSpent += usingItem ? 1 : STEP_FULL_TICK_COST;
        if (step.budgetSpent > STEP_TIMEOUT_BUDGET) {
            return failStep(bot, step, "timeout");
        }
        if (inCell && distance <= STEP_CENTER_TOLERANCE || distance <= speed * STEP_BRAKE_FACTOR) {
            // Close enough: let go of the keys and let friction finish the step.
            bot.getActionPack().stopMovement();
            return StepStatus.MOVING;
        }

        if (!step.keepAim) {
            LookAction.lookHorizontallyAt(bot, new Vec3(cell.getX() + 0.5D, bot.getY(), cell.getZ() + 0.5D));
        }
        double yaw = Math.toRadians(bot.getYRot());
        double ux = dx / distance;
        double uz = dz / distance;
        // Yaw frame of vanilla moveRelative: forward = (-sin, cos), left = (cos, sin).
        float forward = (float) (-Math.sin(yaw) * ux + Math.cos(yaw) * uz);
        float left = (float) (Math.cos(yaw) * ux + Math.sin(yaw) * uz);
        var pack = bot.getActionPack();
        float scale = usingItem ? STEP_ITEM_USE_SLOWDOWN : 1.0F;
        pack.setForward(forward * scale);
        pack.setStrafing(left * scale);
        boolean sprints = step.sprint
                && forward > 0.5F
                && !usingItem
                && bot.getFoodData().getFoodLevel() > STEP_SPRINT_FOOD_FLOOR;
        pack.setSprinting(sprints);
        return StepStatus.MOVING;
    }

    /** Lets go of every movement key of a walked step that the caller abandons before it ends. */
    public static void cancelStep(AIPlayerEntity bot, InputStep step) {
        if (step != null) {
            bot.getActionPack().stopMovement();
        }
    }

    private static StepStatus failStep(AIPlayerEntity bot, InputStep step, String reason) {
        step.failure = reason;
        bot.getActionPack().stopMovement();
        return StepStatus.FAILED;
    }

    /**
     * Why a walked step into {@code cell} is not legal right now, or {@code null} when it is: the
     * destination is a dry, supported, hazard-free landing and nothing (block or entity) occupies
     * the body volume the bot would take there. The same landing rules re-run every tick of the walk.
     */
    public static String stepRefusal(AIPlayerEntity bot, BlockPos cell) {
        String hazard = stepHazard(bot.level(), cell);
        if (hazard != null) {
            return hazard;
        }
        AABB landing = bot.getBoundingBox().move(
                cell.getX() + 0.5D - bot.getX(), cell.getY() - bot.getY(), cell.getZ() + 0.5D - bot.getZ());
        return bot.level().noCollision(bot, landing) ? null : "occupied";
    }

    /** The destination must still be a dry, supported, hazard-free landing every tick of the walk. */
    private static String stepHazard(ServerLevel level, BlockPos cell) {
        String danger = DangerCheck.scan(level, cell);
        if (danger != null) {
            return "hazard:" + danger;
        }
        return Standability.isStandableFresh(level, cell) ? null : "no_landing";
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
