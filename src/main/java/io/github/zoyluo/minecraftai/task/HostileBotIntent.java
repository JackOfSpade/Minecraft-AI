package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Visible hostile INTENT of foreign bots toward the Minecraft-AI side, sampled once per server tick (END_SERVER_TICK) over
 * markable foreign bots x protected victims in the same level within {@link #SAMPLE_RANGE} blocks. Every counter here is the
 * sampler's own, in level game ticks: a mock player is not ticked, so {@code swingTime}, {@code useItemRemaining} and
 * {@code tickCount} of the aggressor are never relied on (a swing START is an edge of {@code swinging} or a falling
 * {@code swingTime}).
 *
 * <p>Presence, patrolling or holding a weapon never counts. EXCLUSIVITY applies to every signal: the victim must be the nearest
 * living entity inside the aggressor's view cone, so a bot fighting a zombie next to the owner is never taken for hostile to the
 * owner. For a swing and a charge no hostile mob ({@link Enemy}) may additionally be within the aggressor's reach + 1.</p>
 * <ul>
 *   <li>WRAPPER AGGRO: when the hostile-inhabitants wrapper has confirmed one of its bots is hunting a protected player, that factual
 *       target immediately becomes MARKED ("aggro"), without requiring the companion to see through a wall.</li>
 *   <li>DRAW: a bow drawn, a trident raised, or a charged crossbow in hand, aimed at the victim (dot &gt;= {@link #AIM_DOT}, within
 *       {@link #AIM_RANGE}), exclusive: MARKED ("aim") on the first sampled tick.</li>
 *   <li>SWING: a swing starts while facing the victim (dot &gt;= {@link #FACING_DOT}) within the interaction range + 1, exclusive, no
 *       hostile mob in reach: SUSPECT ("swing").</li>
 *   <li>ARMED CHARGE: a sword, axe, mace, spear or trident in the main hand, facing the victim, within {@link #CHARGE_RANGE}, closing
 *       over {@link #CHARGE_WINDOW} ticks, exclusive, no hostile mob in reach: MARKED ("charge") before a melee hit lands.</li>
 * </ul>
 */
public final class HostileBotIntent {
    static final double SAMPLE_RANGE = 48.0D;
    static final int DRAW_TICKS = 1;
    static final double AIM_DOT = 0.94D;
    static final double AIM_RANGE = 40.0D;
    static final double FACING_DOT = 0.9D;
    static final double CHARGE_RANGE = 16.0D;
    static final double CHARGE_DROP = 0.25D;
    static final int CHARGE_WINDOW = 4;
    static final int CHARGE_SUSTAIN = 1;
    private static final int HISTORY = 16; // power of two, larger than CHARGE_WINDOW
    private static final int PRUNE_EVERY_TICKS = 200;

    /** Per victim state of one aggressor. */
    private static final class Pair {
        long lastTick = Long.MIN_VALUE;
        int drawTicks;
        int chargeTicks;
        final double[] distance = new double[HISTORY];
        final long[] distanceTick = new long[HISTORY];

        void push(long tick, double value) {
            int slot = (int) (tick & (HISTORY - 1));
            distance[slot] = value;
            distanceTick[slot] = tick;
            lastTick = tick;
        }

        /** The distance sampled exactly {@code ticksAgo} before {@code now}, or NaN when that tick was not sampled. */
        double distanceAgo(long now, int ticksAgo) {
            long wanted = now - ticksAgo;
            int slot = (int) (wanted & (HISTORY - 1));
            return distanceTick[slot] == wanted ? distance[slot] : Double.NaN;
        }
    }

    /** Per aggressor state: the swing edge detector and the pairs. */
    private static final class Track {
        long lastTick = Long.MIN_VALUE;
        boolean lastSwinging;
        int lastSwingTime;
        final Map<UUID, Pair> pairs = new HashMap<>();
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    /** Optional, compile-free bridge to the hostile-inhabitants controller's factual confirmed target. */
    private static volatile Method wrapperConfirmedTarget;
    private static volatile boolean wrapperBridgeResolved;

    private HostileBotIntent() {
    }

    /** Called once per server tick from the END_SERVER_TICK hook. Never throws into the tick. */
    public static void tick(MinecraftServer server) {
        try {
            // The ledger is pruned whether or not there are intent tracks (or hostile-bot targeting is on): its marks come from
            // damage events, not from the tracks, so an idle or disabled sampler must not leave expired marks behind.
            boolean pruneTick = server.getTickCount() % PRUNE_EVERY_TICKS == 0;
            if (pruneTick) {
                HostileBotLedger.prune(server.overworld().getGameTime());
            }
            if (!HostileBotLedger.hostileBotsEnabled()) {
                if (!TRACKS.isEmpty()) {
                    TRACKS.clear();
                }
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                sampleLevel(level);
            }
            if (pruneTick && !TRACKS.isEmpty()) {
                prune(server.overworld().getGameTime());
            }
        } catch (RuntimeException exception) {
            io.github.zoyluo.minecraftai.log.BotLog.error("hostile_bot_intent_failed", exception);
        }
    }

    private static void sampleLevel(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.size() < 2) {
            return;
        }
        List<ServerPlayer> aggressors = null;
        List<ServerPlayer> victims = null;
        List<AIPlayerEntity> observers = null;
        for (ServerPlayer player : players) {
            if (HostileBotLedger.isMarkableForeignBot(player)) {
                if (aggressors == null) {
                    aggressors = new ArrayList<>(2);
                }
                aggressors.add(player);
            } else if (HostileBotLedger.isProtectedVictim(player)) {
                if (victims == null) {
                    victims = new ArrayList<>(4);
                }
                victims.add(player);
                if (player instanceof AIPlayerEntity bot) {
                    if (observers == null) {
                        observers = new ArrayList<>(2);
                    }
                    observers.add(bot);
                }
            }
        }
        if (aggressors == null) {
            return;
        }
        long now = level.getGameTime();
        for (ServerPlayer aggressor : aggressors) {
            Track track = TRACKS.computeIfAbsent(aggressor.getUUID(), ignored -> new Track());
            if (track.lastTick == now) {
                continue; // one sample per game tick, however often the hook runs
            }
            boolean swingStart = aggressor.swinging && (!track.lastSwinging || aggressor.swingTime < track.lastSwingTime);
            track.lastSwinging = aggressor.swinging;
            track.lastSwingTime = aggressor.swingTime;
            track.lastTick = now;
            if (victims == null || !aggressor.isAlive() || aggressor.isSpectator()) {
                continue;
            }
            // The wrapper owns the hostile PvP bot's target state. It has already applied its own
            // observation and reaction rules, so this is a factual aggro relationship—not an
            // omniscient proximity guess. Record it before the companion-perception gate; the
            // combat handoff separately requires the defending companion's direct 360-degree
            // physical line of sight, without changing normal perception.
            markWrapperAggroTargets(aggressor, victims, now);
            if (!perceived(aggressor, observers)) {
                // Nobody on the protected side has noticed this player (no bot has seen or heard it, no owner is looking at it): its
                // intent is not something anyone could witness, so nothing is sampled and the sustained counters start over.
                for (Pair pair : track.pairs.values()) {
                    pair.drawTicks = 0;
                    pair.chargeTicks = 0;
                }
                continue;
            }
            for (ServerPlayer victim : victims) {
                if (!victim.isAlive() || aggressor.distanceToSqr(victim) > SAMPLE_RANGE * SAMPLE_RANGE) {
                    continue;
                }
                samplePair(aggressor, victim, track.pairs.computeIfAbsent(victim.getUUID(), ignored -> new Pair()), now, swingStart);
            }
        }
    }

    /**
     * Whether the protected side can witness {@code aggressor}: some Minecraft-AI bot in the level has NOTICED it (realistic
     * perception: seen for the reaction time, heard and in view, or struck by it) or its owner is looking at it
     * ({@link SharedVision#ownerSees}). The sampler reads the aggressor's swing, draw and closing speed, which only a witness could
     * know, so it is gated on someone noticing it. With perception off everything is sampled, as before.
     */
    private static boolean perceived(ServerPlayer aggressor, List<AIPlayerEntity> observers) {
        if (!CreatureSenses.enabled()) {
            return true;
        }
        if (observers == null) {
            return false;
        }
        for (AIPlayerEntity bot : observers) {
            if (bot.level() == aggressor.level()
                    && (CreatureSenses.INSTANCE.noticed(bot, aggressor) || SharedVision.ownerSees(bot, aggressor))) {
                return true;
            }
        }
        return false;
    }

    private static void samplePair(ServerPlayer aggressor, ServerPlayer victim, Pair pair, long now, boolean swingStart) {
        double distance = aggressor.distanceTo(victim);
        pair.push(now, distance);
        double facing = facing(aggressor, victim);

        // DRAW: a drawn bow / raised trident / charged crossbow aimed at the victim, exclusively.
        if (holdsDrawnRanged(aggressor) && distance <= AIM_RANGE && facing >= AIM_DOT && isNearestInCone(aggressor, victim, AIM_DOT)) {
            if (++pair.drawTicks >= DRAW_TICKS) {
                HostileBotLedger.markPlayer(aggressor, victim, now, "aim");
            }
        } else {
            pair.drawTicks = 0;
        }

        // SWING: a fresh swing at the victim inside the reach (+1), exclusively, with no hostile mob in reach.
        if (swingStart && facing >= FACING_DOT && aggressor.isWithinEntityInteractionRange(victim, 1.0D)
                && isNearestInCone(aggressor, victim, FACING_DOT) && !hostileMobInReach(aggressor)) {
            HostileBotLedger.suspectPlayer(aggressor, victim, now, "swing");
        }

        // ARMED CHARGE: closing on the victim with a melee weapon, facing it. This is a
        // pre-hit defence signal, not merely pressure: an attacker at melee range should not get
        // a free first swing while the companion waits for a damage callback.
        boolean charging = false;
        if (distance <= CHARGE_RANGE && facing >= FACING_DOT && holdsMeleeWeapon(aggressor)) {
            double before = pair.distanceAgo(now, CHARGE_WINDOW);
            charging = !Double.isNaN(before) && before - distance >= CHARGE_DROP
                    && isNearestInCone(aggressor, victim, FACING_DOT) && !hostileMobInReach(aggressor);
        }
        if (charging) {
            if (++pair.chargeTicks >= CHARGE_SUSTAIN) {
                HostileBotLedger.markPlayer(aggressor, victim, now, "charge");
            }
        } else {
            pair.chargeTicks = 0;
        }
    }

    /** Records the wrapper's actual confirmed hunt target, if the optional hostile-inhabitants addon is loaded. */
    private static void markWrapperAggroTargets(ServerPlayer aggressor, List<ServerPlayer> victims, long now) {
        String targetName = wrapperConfirmedTarget(aggressor);
        if (targetName == null) {
            return;
        }
        for (ServerPlayer victim : victims) {
            if (victim.isAlive() && targetName.equalsIgnoreCase(victim.getGameProfile().name())) {
                HostileBotLedger.markPlayer(aggressor, victim, now, "aggro");
                return;
            }
        }
    }

    /**
     * Reads {@code InhabitantsMod.aggroConfirmedTarget} reflectively so Minecraft-AI remains usable without the wrapper.
     * A missing or changed optional addon simply returns no target and leaves the visible weapon fallbacks active.
     */
    private static String wrapperConfirmedTarget(ServerPlayer aggressor) {
        Method method = wrapperConfirmedTarget;
        if (!wrapperBridgeResolved) {
            synchronized (HostileBotIntent.class) {
                if (!wrapperBridgeResolved) {
                    try {
                        Class<?> wrapper = Class.forName("dev.spawnbotswrapper.inhabitants.InhabitantsMod", false,
                                HostileBotIntent.class.getClassLoader());
                        wrapperConfirmedTarget = wrapper.getMethod("aggroConfirmedTarget", String.class);
                    } catch (ReflectiveOperationException | LinkageError ignored) {
                        wrapperConfirmedTarget = null;
                    }
                    wrapperBridgeResolved = true;
                }
                method = wrapperConfirmedTarget;
            }
        }
        if (method == null) {
            return null;
        }
        try {
            Object target = method.invoke(null, aggressor.getGameProfile().name());
            return target instanceof String name && !name.isBlank() ? name : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ geometry and item predicates

    /** Dot product of the looker's view direction with the direction to the middle of the target's box. */
    static double facing(LivingEntity looker, LivingEntity target) {
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(looker.getEyePosition());
        double length = toTarget.length();
        if (length < 1.0E-6D) {
            return 1.0D;
        }
        return looker.getViewVector(1.0F).dot(toTarget.scale(1.0D / length));
    }

    /**
     * True when no other living entity is nearer to {@code looker} than {@code victim} inside the looker's view cone
     * ({@code facing >= minDot}): the victim is the nearest thing it could be aiming or swinging at.
     */
    static boolean isNearestInCone(ServerPlayer looker, LivingEntity victim, double minDot) {
        Vec3 eye = looker.getEyePosition();
        double victimDistanceSquared = eye.distanceToSqr(victim.getBoundingBox().getCenter());
        AABB box = looker.getBoundingBox().inflate(Math.sqrt(victimDistanceSquared) + 1.0D);
        return looker.level().getEntitiesOfClass(LivingEntity.class, box,
                other -> other != looker && other != victim && other.isAlive() && !other.isSpectator()
                        && eye.distanceToSqr(other.getBoundingBox().getCenter()) < victimDistanceSquared
                        && facing(looker, other) >= minDot).isEmpty();
    }

    /** True when a hostile mob ({@link Enemy}) is within the aggressor's entity interaction range + 1. */
    static boolean hostileMobInReach(ServerPlayer aggressor) {
        double reach = aggressor.entityInteractionRange() + 1.0D;
        return !aggressor.level().getEntitiesOfClass(Mob.class, aggressor.getBoundingBox().inflate(reach),
                mob -> mob instanceof Enemy && mob.isAlive() && aggressor.isWithinEntityInteractionRange(mob, 1.0D)).isEmpty();
    }

    /** A bow being drawn, a trident raised, or a charged crossbow in either hand. */
    static boolean holdsDrawnRanged(LivingEntity shooter) {
        if (shooter.isUsingItem()) {
            ItemStack used = shooter.getUseItem();
            if (used.is(Items.BOW) || used.is(Items.TRIDENT)) {
                return true;
            }
        }
        return isChargedCrossbow(shooter.getMainHandItem()) || isChargedCrossbow(shooter.getOffhandItem());
    }

    private static boolean isChargedCrossbow(ItemStack stack) {
        return stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack);
    }

    /** A sword, axe, mace, spear or trident in the main hand (item tags, so modded tiers count). */
    static boolean holdsMeleeWeapon(LivingEntity entity) {
        ItemStack stack = entity.getMainHandItem();
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS)
                || stack.is(Items.MACE) || stack.is(Items.TRIDENT);
    }

    // ------------------------------------------------------------------ housekeeping

    private static void prune(long now) {
        Iterator<Map.Entry<UUID, Track>> tracks = TRACKS.entrySet().iterator();
        while (tracks.hasNext()) {
            Track track = tracks.next().getValue();
            if (now - track.lastTick > PRUNE_EVERY_TICKS) {
                tracks.remove();
                continue;
            }
            track.pairs.values().removeIf(pair -> now - pair.lastTick > PRUNE_EVERY_TICKS);
        }
    }

    /** Forgets everything one aggressor was doing (its death). */
    static void forget(UUID aggressor) {
        TRACKS.remove(aggressor);
    }

    static void clearAll() {
        TRACKS.clear();
    }

}
