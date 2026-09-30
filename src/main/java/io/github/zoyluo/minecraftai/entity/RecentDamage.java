package io.github.zoyluo.minecraftai.entity;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Who hurt whom, recorded from Fabric's {@code ServerLivingEntityEvents.AFTER_DAMAGE}, for every victim that is a
 * {@link ServerPlayer} (a bot, a player, a GameTest mock player). It is observed fact: the damage the victim actually took, who the
 * source entity was (for a projectile, the shooter) and when. Nothing here reads a mob's hidden state.
 *
 * <p>Time is always the LEVEL GAME TIME ({@code level.getGameTime()}) and callers pass their own {@code now} from the same clock.
 * {@code tickCount} is never used: a mock player is not ticked and its {@code tickCount} never advances.</p>
 *
 * <p>Each victim keeps a bounded ring of {@link #RING_SIZE} hits. Rings are cleared on server stop, and a victim's own ring is
 * dropped when it dies. AFTER_DAMAGE is not fired for a lethal hit, so the killing blow is recorded from the death event (see
 * {@code onDeath}): the hit listeners receive it, and the ring is dropped right after. Everything runs on the server thread; the maps are concurrent only so a stray reader cannot corrupt them.</p>
 */
public final class RecentDamage {
    /** Hits kept per victim. */
    public static final int RING_SIZE = 16;

    /**
     * One hit. {@code attacker} is {@code source.getEntity()} (null for fire, fall, an unknown shooter...), {@code attackerId} its
     * entity id (-1 for none), {@code directId} the id of {@code source.getDirectEntity()} (the arrow, the melee attacker; -1 for
     * none). {@code amount} is the damage taken (after armour and absorption; 0 for a fully blocked hit), {@code blocked} whether a
     * shield stopped it.
     */
    public record Hit(UUID victim, UUID attacker, int attackerId, int directId, float amount, boolean blocked, long gameTime) {
        /** True when some entity (not the environment) caused this hit. */
        public boolean byEntity() {
            return attackerId >= 0 || directId >= 0;
        }
    }

    /** A death of a player-kind entity, published for ledgers that hold per-aggressor state. */
    public record Death(UUID victim, UUID killer, long gameTime) {
    }

    private static final Map<UUID, ArrayDeque<Hit>> RINGS = new ConcurrentHashMap<>();
    /** The damage of a lethal hit, remembered from ALLOW_DEATH until AFTER_DEATH (AFTER_DAMAGE does not fire for a killing blow). */
    private static final Map<UUID, Float> LETHAL_AMOUNTS = new ConcurrentHashMap<>();
    private static final List<Consumer<Hit>> HIT_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Death>> DEATH_LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean registered;

    private RecentDamage() {
    }

    /** Registers the Fabric events; called once from {@code MinecraftAiMod.onInitialize}. Repeated calls do nothing. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) ->
                onDamage(entity, source, damageTaken, blocked));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damageAmount) -> {
            if (entity instanceof ServerPlayer victim) {
                LETHAL_AMOUNTS.put(victim.getUUID(), damageAmount);
            }
            return true; // only observes: never vetoes a death
        });
        ServerLivingEntityEvents.AFTER_DEATH.register(RecentDamage::onDeath);
    }

    private static void onDamage(LivingEntity entity, DamageSource source, float damageTaken, boolean blocked) {
        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }
        Entity attacker = source == null ? null : source.getEntity();
        Entity direct = source == null ? null : source.getDirectEntity();
        record(new Hit(victim.getUUID(),
                attacker == null ? null : attacker.getUUID(),
                attacker == null ? -1 : attacker.getId(),
                direct == null ? -1 : direct.getId(),
                damageTaken,
                blocked,
                victim.level().getGameTime()));
    }

    private static void onDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }
        Entity killer = source == null ? null : source.getEntity();
        Entity direct = source == null ? null : source.getDirectEntity();
        Float lethalAmount = LETHAL_AMOUNTS.remove(victim.getUUID());
        // Fabric fires AFTER_DAMAGE only for damage the victim survives, so the killing blow would be missing from the ring: record
        // it here, from the death event, before the ring is dropped below. The hit listeners see it (a ledger marks the killer of a
        // protected victim); the ring itself is gone right after, so RecentDamage queries never return the killing blow of a victim
        // that is dead.
        if (killer != null || direct != null) {
            record(new Hit(victim.getUUID(),
                    killer == null ? null : killer.getUUID(),
                    killer == null ? -1 : killer.getId(),
                    direct == null ? -1 : direct.getId(),
                    lethalAmount == null ? 0.0F : lethalAmount,
                    false,
                    victim.level().getGameTime()));
        }
        Death death = new Death(victim.getUUID(), killer == null ? null : killer.getUUID(), victim.level().getGameTime());
        for (Consumer<Death> listener : DEATH_LISTENERS) {
            try {
                listener.accept(death);
            } catch (RuntimeException ignored) {
                // A listener must never break the death event.
            }
        }
        RINGS.remove(victim.getUUID());
    }

    /** Stores a hit and tells the listeners (the event handler, and tests that feed the ring directly). */
    static void record(Hit hit) {
        ArrayDeque<Hit> ring = RINGS.computeIfAbsent(hit.victim(), ignored -> new ArrayDeque<>(RING_SIZE));
        synchronized (ring) {
            if (ring.size() >= RING_SIZE) {
                ring.pollFirst();
            }
            ring.addLast(hit);
        }
        for (Consumer<Hit> listener : HIT_LISTENERS) {
            try {
                listener.accept(hit);
            } catch (RuntimeException ignored) {
                // A listener must never break the damage event.
            }
        }
    }

    private static List<Hit> within(UUID victim, long now, int withinTicks) {
        ArrayDeque<Hit> ring = victim == null ? null : RINGS.get(victim);
        if (ring == null) {
            return List.of();
        }
        List<Hit> result = new ArrayList<>();
        synchronized (ring) {
            for (Hit hit : ring) {
                if (now - hit.gameTime() <= withinTicks) {
                    result.add(hit);
                }
            }
        }
        return result;
    }

    /** The newest hit {@code attacker} (its UUID, shooters included) landed on {@code victim} within {@code withinTicks} of {@code now}. */
    public static Optional<Hit> lastHitBy(UUID victim, UUID attacker, long now, int withinTicks) {
        if (attacker == null) {
            return Optional.empty();
        }
        Hit best = null;
        for (Hit hit : within(victim, now, withinTicks)) {
            if (attacker.equals(hit.attacker()) && (best == null || hit.gameTime() >= best.gameTime())) {
                best = hit;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Every hit {@code victim} took within {@code withinTicks} of {@code now}, oldest first. */
    public static List<Hit> recentHits(UUID victim, long now, int withinTicks) {
        return within(victim, now, withinTicks);
    }

    /** The largest single hit {@code victim} took within the window (0 when none). */
    public static float maxSingleHit(UUID victim, long now, int withinTicks) {
        float max = 0.0F;
        for (Hit hit : within(victim, now, withinTicks)) {
            max = Math.max(max, hit.amount());
        }
        return max;
    }

    /** True when an entity (not fire, fall or the like) hurt {@code victim} within the window. */
    public static boolean tookEntityDamage(UUID victim, long now, int withinTicks) {
        for (Hit hit : within(victim, now, withinTicks)) {
            if (hit.byEntity()) {
                return true;
            }
        }
        return false;
    }

    /** Subscribes to every recorded hit; returns the call that removes the subscription. */
    public static Runnable addListener(Consumer<Hit> listener) {
        HIT_LISTENERS.add(listener);
        return () -> HIT_LISTENERS.remove(listener);
    }

    /** Subscribes to the deaths of players and bots (ledger cleanup); returns the call that removes the subscription. */
    public static Runnable addDeathListener(Consumer<Death> listener) {
        DEATH_LISTENERS.add(listener);
        return () -> DEATH_LISTENERS.remove(listener);
    }

    /** Forgets every ring (server stop). Listeners stay: they are registered once at start-up. */
    public static void clear() {
        RINGS.clear();
        LETHAL_AMOUNTS.clear();
    }
}
