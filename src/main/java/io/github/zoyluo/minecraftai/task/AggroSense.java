package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * "Is something after us?", judged only from what an observer can see: who hit whom ({@link RecentDamage}), the synced aggressive
 * flag, who is closing on and facing a victim, a bow drawn at a victim, a swelling creeper, and the foreign bots of
 * {@link HostileBotLedger}. It never reads {@code Mob.getTarget()} or any hidden AI state, and it never uses a mob's live health or
 * attack attributes (only the type's DEFAULT values, which are the same for every observer).
 *
 * <p>The protected victims are the bot, its owner and every {@link AIPlayerEntity} within {@link #VICTIM_RANGE} blocks (any owner).
 * Mob candidates are living entities within {@link #SCAN_RANGE} blocks that the bot's OWN eyes see and that
 * {@link CombatCore#hostileTo} calls hostile; owner vision is used only for foreign-bot candidates. A calm warden is excluded unless
 * {@link WardenState#isHunting}. One entity query per bot per server tick; the snapshot is cached for that tick.</p>
 */
public final class AggroSense {
    public static final double SCAN_RANGE = 24.0D;
    public static final double VICTIM_RANGE = 24.0D;
    /** A mob with the synced aggressive flag counts within this distance of a victim. */
    static final double AGGRESSIVE_RANGE = 16.0D;
    /** Closing on a victim: within this distance, facing it (dot) and nearer by {@link #CLOSING_DROP} over about {@link #CLOSING_TICKS}. */
    static final double CLOSING_RANGE = 12.0D;
    static final double CLOSING_FACING_DOT = 0.8D;
    static final double CLOSING_DROP = 0.8D;
    static final int CLOSING_TICKS = 10;
    /** The samples kept per entity: a little more than the window, in case the sense is not asked every tick. */
    private static final int CLOSING_HISTORY_TICKS = 24;
    /** A hit on a victim this recent (game ticks) makes its author an aggressor. */
    static final int HIT_WINDOW_TICKS = 100;
    /** The owner counts as under attack for this long after an entity hurt it. */
    static final int OWNER_ATTACK_WINDOW_TICKS = 60;
    static final double CREEPER_SWELL_RANGE = 8.0D;
    /** Same recipe as {@code CombatTask.isDrawingBowAt}: a bow used this long and aimed at the victim. */
    private static final int DRAW_TICKS = 12;
    private static final double DRAW_AIM_DOT = 0.94D;
    private static final float PLAYER_DEFAULT_MAX_HEALTH = 20.0F;

    /**
     * {@code strongestType} / {@code strongestDefaultMaxHealth}: the aggressor type with the highest DEFAULT max health (never a live
     * value; null and 0 when there is none). {@code playerKindAggressor}: a MARKED or SUSPECT foreign bot the bot or its owner sees.
     * {@code rangedOrExplosive}: a shooter, a ghast or shulker, a swelling creeper or a foreign bot with a drawn ranged weapon.
     */
    public record Snapshot(boolean pressure,
                           int aggressorCount,
                           boolean playerKindAggressor,
                           EntityType<?> strongestType,
                           float strongestDefaultMaxHealth,
                           boolean rangedOrExplosive,
                           List<LivingEntity> aggressors,
                           boolean ownerUnderAttack) {
        public static final Snapshot NONE = new Snapshot(false, 0, false, null, 0.0F, false, List.of(), false);
    }

    private record Cached(int serverTick, Snapshot snapshot) {
    }

    private record Sample(long tick, double distance) {
    }

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();
    /** Per bot: entity id to the recent distances to its nearest victim (closing detection). */
    private static final Map<UUID, Map<Integer, ArrayDeque<Sample>>> CLOSING = new ConcurrentHashMap<>();

    private AggroSense() {
    }

    /** The snapshot of this bot for the current server tick (computed on the first request of the tick). */
    public static Snapshot snapshot(AIPlayerEntity bot) {
        MinecraftServer server = bot.level().getServer();
        if (server == null || !bot.isAlive()) {
            return Snapshot.NONE;
        }
        int tick = server.getTickCount();
        Cached cached = CACHE.get(bot.getUUID());
        if (cached != null && cached.serverTick() == tick) {
            return cached.snapshot();
        }
        Snapshot fresh = compute(bot);
        CACHE.put(bot.getUUID(), new Cached(tick, fresh));
        return fresh;
    }

    /** Forgets the cached snapshot and distance history of one bot (it despawned or was unloaded). */
    public static void clear(AIPlayerEntity bot) {
        CACHE.remove(bot.getUUID());
        CLOSING.remove(bot.getUUID());
    }

    /** Forgets every cached snapshot and distance history (server stop). */
    public static void clearAll() {
        CACHE.clear();
        CLOSING.clear();
    }

    private static Snapshot compute(AIPlayerEntity bot) {
        long now = bot.level().getGameTime();
        ServerPlayer owner = SharedVision.ownerOnline(bot);
        boolean ownerHere = owner != null && owner.level() == bot.level() && owner.isAlive();

        List<LivingEntity> victims = new ArrayList<>(4);
        victims.add(bot);
        if (ownerHere) {
            victims.add(owner);
        }
        // The player the bot is following is protected like its owner (they may be another player than the owner).
        ServerPlayer followed = TaskManager.INSTANCE.getActive(bot)
                .filter(FollowTask.class::isInstance).map(FollowTask.class::cast)
                .flatMap(FollowTask::currentTarget).orElse(null);
        if (followed != null && followed != owner && followed != bot && followed.isAlive() && followed.level() == bot.level()) {
            victims.add(followed);
        }
        for (ServerPlayer player : bot.level().players()) {
            if (player instanceof AIPlayerEntity other && other != bot && other.isAlive()
                    && other.distanceToSqr(bot) <= VICTIM_RANGE * VICTIM_RANGE) {
                victims.add(other);
            }
        }
        Set<UUID> victimIds = new HashSet<>();
        for (LivingEntity victim : victims) {
            victimIds.add(victim.getUUID());
        }

        List<LivingEntity> aggressors = new ArrayList<>();
        boolean playerKind = false;
        boolean rangedOrExplosive = false;
        EntityType<?> strongestType = null;
        float strongestHealth = 0.0F;

        Map<Integer, ArrayDeque<Sample>> closing = CLOSING.computeIfAbsent(bot.getUUID(), ignored -> new HashMap<>());
        Set<Integer> seen = new HashSet<>();

        for (LivingEntity entity : bot.level().getEntitiesOfClass(LivingEntity.class,
                bot.getBoundingBox().inflate(SCAN_RANGE), e -> e != bot && e.isAlive() && !victimIds.contains(e.getUUID()))) {
            boolean foreignBot = entity instanceof ServerPlayer player && HostileBotLedger.isMarkableForeignBot(player);
            if (entity instanceof ServerPlayer && !foreignBot) {
                continue; // another human (or a bot owner) is never an aggressor here: only the ledger names player-kind aggressors
            }
            boolean aggressor;
            if (foreignBot) {
                ServerPlayer player = (ServerPlayer) entity;
                aggressor = HostileBotLedger.isMarkedOrSuspect(player) && SharedVision.seenByBotOrOwner(bot, player);
                if (aggressor) {
                    playerKind = true;
                    rangedOrExplosive |= HostileBotIntent.holdsDrawnRanged(player);
                }
            } else {
                if (entity instanceof Warden warden && !WardenState.isHunting(warden, victims, now)) {
                    continue; // a calm warden is no aggressor (and its distance history is not kept)
                }
                if (!CombatCore.hostileTo(bot, entity) || !ObservableWorldQuery.canNoticeCreature(bot, entity)) {
                    continue;
                }
                seen.add(entity.getId());
                aggressor = isMobAggressor(entity, victims, now, closing);
                if (aggressor) {
                    rangedOrExplosive |= CombatCore.isLongRangeDanger(entity)
                            || entity instanceof Creeper creeper && creeper.getSwellDir() > 0;
                }
            }
            if (!aggressor) {
                continue;
            }
            aggressors.add(entity);
            float health = defaultMaxHealth(entity.getType());
            if (strongestType == null || health > strongestHealth) {
                strongestType = entity.getType();
                strongestHealth = health;
            }
        }
        closing.keySet().retainAll(seen);

        boolean ownerUnderAttack = ownerHere
                && ObservableWorldQuery.canObserveEntity(bot, owner)
                && RecentDamage.tookEntityDamage(owner.getUUID(), now, OWNER_ATTACK_WINDOW_TICKS);
        boolean pressure = !aggressors.isEmpty() || ownerUnderAttack;
        return new Snapshot(pressure, aggressors.size(), playerKind, strongestType, strongestHealth,
                rangedOrExplosive, List.copyOf(aggressors), ownerUnderAttack);
    }

    /** The default MAX_HEALTH of the type (what any observer knows), 20 for a player, 0 when the type has no attributes. */
    static float defaultMaxHealth(EntityType<?> type) {
        if (type == EntityType.PLAYER) {
            return PLAYER_DEFAULT_MAX_HEALTH;
        }
        try {
            @SuppressWarnings("unchecked")
            EntityType<? extends LivingEntity> living = (EntityType<? extends LivingEntity>) type;
            if (!DefaultAttributes.hasSupplier(living)) {
                return 0.0F;
            }
            return (float) DefaultAttributes.getSupplier(living).getBaseValue(Attributes.MAX_HEALTH);
        } catch (RuntimeException exception) {
            return 0.0F;
        }
    }

    private static boolean isMobAggressor(LivingEntity entity, List<LivingEntity> victims, long now,
                                          Map<Integer, ArrayDeque<Sample>> closing) {
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity victim : victims) {
            double distance = entity.distanceTo(victim);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = victim;
            }
        }
        // The distance history is kept every tick this entity is a candidate, whatever else makes it an aggressor.
        boolean closingOnVictim = updateClosing(entity, nearest, nearestDistance, now, closing);
        // (a) it hurt a victim recently
        for (LivingEntity victim : victims) {
            if (RecentDamage.lastHitBy(victim.getUUID(), entity.getUUID(), now, HIT_WINDOW_TICKS).isPresent()) {
                return true;
            }
        }
        // (b) the synced aggressive flag near a victim
        if (entity instanceof Mob mob && mob.isAggressive() && nearestDistance <= AGGRESSIVE_RANGE) {
            return true;
        }
        // (c) closing on and facing a victim
        if (closingOnVictim) {
            return true;
        }
        // (d) a bow drawn at a victim
        if (isDrawingBowAtAny(entity, victims)) {
            return true;
        }
        // (e) a creeper that is swelling near a victim
        return entity instanceof Creeper creeper && creeper.getSwellDir() > 0 && nearestDistance <= CREEPER_SWELL_RANGE;
    }

    private static boolean updateClosing(LivingEntity entity, LivingEntity nearest, double nearestDistance, long now,
                                         Map<Integer, ArrayDeque<Sample>> closing) {
        if (nearest == null) {
            return false;
        }
        ArrayDeque<Sample> samples = closing.computeIfAbsent(entity.getId(), ignored -> new ArrayDeque<>());
        Sample last = samples.peekLast();
        if (last == null || last.tick() != now) {
            samples.addLast(new Sample(now, nearestDistance));
        }
        while (!samples.isEmpty() && now - samples.peekFirst().tick() > CLOSING_HISTORY_TICKS) {
            samples.pollFirst();
        }
        if (nearestDistance > CLOSING_RANGE || HostileBotIntent.facing(entity, nearest) < CLOSING_FACING_DOT) {
            return false;
        }
        // The newest sample that is at least CLOSING_TICKS old (the history holds 24 ticks; the window is about 10).
        Sample reference = null;
        for (Sample sample : samples) {
            if (now - sample.tick() >= CLOSING_TICKS) {
                reference = sample;
            } else {
                break;
            }
        }
        return reference != null && reference.distance() - nearestDistance >= CLOSING_DROP;
    }

    /**
     * A bow drawn, a crossbow being charged, or a loaded crossbow held, aimed at a victim: weapon-neutral
     * ({@link CombatCore#isRangedWeaponUp}), the same recipe as {@code CombatTask.isDrawingBowAt}.
     */
    private static boolean isDrawingBowAtAny(LivingEntity shooter, List<LivingEntity> victims) {
        if (!CombatCore.isRangedWeaponUp(shooter, DRAW_TICKS)) {
            return false;
        }
        for (LivingEntity victim : victims) {
            Vec3 toVictim = victim.getEyePosition().subtract(shooter.getEyePosition());
            if (toVictim.lengthSqr() < 1.0E-6D
                    || shooter.getViewVector(1.0F).dot(toVictim.normalize()) >= DRAW_AIM_DOT) {
                return true;
            }
        }
        return false;
    }

}
