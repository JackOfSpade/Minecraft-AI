package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Which foreign bots (fake players that are not ours, such as PvP BOT inhabitants) have shown hostility to the Minecraft-AI side, and
 * how sure we are.
 *
 * <p>Minecraft-AI bots never target their owner or another Minecraft-AI bot. A foreign bot is left alone (it is friendly in
 * {@code StrikeLegality.isFriendly}) until it acts against a PROTECTED victim (any {@link AIPlayerEntity}, or any player who owns a
 * Minecraft-AI bot):</p>
 * <ul>
 *   <li>{@link Level#MARKED}: it damaged a protected victim (hit, or the killing blow; the shooter of an arrow counts), or it held a
 *       bow drawn / a charged crossbow aimed at one exclusively for a sustained time (see {@link HostileBotIntent}). A marked bot is a
 *       strike target while a bot or the owner can see it.</li>
 *   <li>{@link Level#SUSPECT}: a fresh swing at, or an armed charge toward, a victim with no mob in reach and the victim the nearest
 *       thing in its view cone. Pressure only (the bots run), never a target. A SUSPECT becomes MARKED when one of its hits lands.</li>
 * </ul>
 * A mark lasts {@code behaviour.targeting.aggressorMemoryTicks} (600) level game ticks after the last qualifying act and is cleared
 * when the aggressor dies or the server stops. The ledger is GLOBAL, keyed by the aggressor's UUID: an aggressor that hit one owner's
 * bot is a target for every Minecraft-AI bot that sees it.
 *
 * <p>{@link Core} is the pure part (UUIDs and game ticks only). The adapters below read the config, the level game time and the
 * player kinds. Time is ALWAYS the level game time ({@code level.getGameTime()}); a mock player's {@code tickCount} never advances.</p>
 */
public final class HostileBotLedger {
    public enum Level {
        MARKED,
        SUSPECT
    }

    /** What is known about one aggressor: its level, the game tick of its last qualifying act and why. */
    public record Entry(Level level, long lastActTick, String reason) {
    }

    /** The pure ledger: no Minecraft types, testable without a server. */
    public static final class Core {
        private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

        /** MARKED as of {@code tick} (a mark refreshes the tick and promotes a SUSPECT). */
        public void mark(UUID aggressor, long tick, String reason) {
            if (aggressor == null) {
                return;
            }
            entries.put(aggressor, new Entry(Level.MARKED, tick, reason));
        }

        /**
         * SUSPECT as of {@code tick}. An existing MARK is never downgraded: a still-live mark only has its tick refreshed (a
         * swing or a charge is one more hostile act), but a mark that has already expired ({@code memoryTicks} old) is replaced.
         */
        public void suspect(UUID aggressor, long tick, String reason, int memoryTicks) {
            if (aggressor == null) {
                return;
            }
            entries.compute(aggressor, (id, old) -> {
                if (old != null && old.level() == Level.MARKED && tick - old.lastActTick() <= memoryTicks) {
                    return new Entry(Level.MARKED, Math.max(tick, old.lastActTick()), old.reason());
                }
                return new Entry(Level.SUSPECT, tick, reason);
            });
        }

        /** True while the aggressor is MARKED and its last act is at most {@code memoryTicks} old. */
        public boolean isMarked(UUID aggressor, long now, int memoryTicks) {
            Entry entry = aggressor == null ? null : entries.get(aggressor);
            return entry != null && entry.level() == Level.MARKED && now - entry.lastActTick() <= memoryTicks;
        }

        /** True while the aggressor is a SUSPECT (not marked) and its last act is at most {@code memoryTicks} old. */
        public boolean isSuspect(UUID aggressor, long now, int memoryTicks) {
            Entry entry = aggressor == null ? null : entries.get(aggressor);
            return entry != null && entry.level() == Level.SUSPECT && now - entry.lastActTick() <= memoryTicks;
        }

        /** The stored entry (even an expired one); null when none. */
        public Entry entry(UUID aggressor) {
            return aggressor == null ? null : entries.get(aggressor);
        }

        public void clearAggressor(UUID aggressor) {
            if (aggressor != null) {
                entries.remove(aggressor);
            }
        }

        public void clearAll() {
            entries.clear();
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /** Drops every entry whose last act is more than {@code memoryTicks} old. */
        public void prune(long now, int memoryTicks) {
            entries.values().removeIf(entry -> now - entry.lastActTick() > memoryTicks);
        }
    }

    private static final Core CORE = new Core();
    private static boolean installed;

    private HostileBotLedger() {
    }

    // ------------------------------------------------------------------ global ledger (config-aware)

    public static int memoryTicks() {
        return MinecraftAiConfig.get().behaviour().targeting().aggressorMemoryTicks();
    }

    public static boolean hostileBotsEnabled() {
        return MinecraftAiConfig.get().behaviour().targeting().hostileBotsEnabled();
    }

    public static void mark(UUID aggressor, long tick, String reason) {
        CORE.mark(aggressor, tick, reason);
    }

    public static void suspect(UUID aggressor, long tick, String reason) {
        CORE.suspect(aggressor, tick, reason, memoryTicks());
    }

    public static boolean isMarked(UUID aggressor, long now) {
        return CORE.isMarked(aggressor, now, memoryTicks());
    }

    public static boolean isSuspect(UUID aggressor, long now) {
        return CORE.isSuspect(aggressor, now, memoryTicks());
    }

    public static Entry entry(UUID aggressor) {
        return CORE.entry(aggressor);
    }

    public static void prune(long now) {
        CORE.prune(now, memoryTicks());
    }

    /**
     * MARKED because {@code aggressor} acted against {@code victim} (a hit, an aim). Logs once per new mark (not on a refresh of a
     * live one), so the log shows when and why a foreign bot became a target.
     */
    public static void markPlayer(ServerPlayer aggressor, Entity victim, long tick, String reason) {
        boolean fresh = !isMarked(aggressor.getUUID(), tick);
        mark(aggressor.getUUID(), tick, reason);
        if (fresh) {
            io.github.zoyluo.minecraftai.log.BotLog.danger(botOf(victim), "hostile_bot_marked",
                    "aggressor", aggressor.getGameProfile().name(), "victim", victim.getName().getString(), "reason", reason);
        }
    }

    /** The victim as the bot whose log the event belongs to (null when the victim is the owner: the event is then a global one). */
    private static AIPlayerEntity botOf(Entity victim) {
        return victim instanceof AIPlayerEntity bot ? bot : null;
    }

    /** SUSPECT (pressure only) because {@code aggressor} showed intent toward {@code victim}; logs once per new suspicion. */
    public static void suspectPlayer(ServerPlayer aggressor, Entity victim, long tick, String reason) {
        boolean fresh = !isMarkedOrSuspect(aggressor);
        suspect(aggressor.getUUID(), tick, reason);
        if (fresh) {
            io.github.zoyluo.minecraftai.log.BotLog.danger(botOf(victim), "hostile_bot_suspected",
                    "aggressor", aggressor.getGameProfile().name(), "victim", victim.getName().getString(), "reason", reason);
        }
    }

    public static void clearAggressor(UUID aggressor) {
        CORE.clearAggressor(aggressor);
    }

    public static void clearAll() {
        CORE.clearAll();
        SEEN_THIS_TICK.clear();
        seenTick = Long.MIN_VALUE;
        HostileBotIntent.clearAll();
    }

    /** {@link #isMarked(UUID, long)} for a live player, on the game time of its own level. */
    public static boolean isMarked(ServerPlayer player) {
        return !CORE.isEmpty() && isMarked(player.getUUID(), player.level().getGameTime());
    }

    /** MARKED or SUSPECT (either still inside its memory). */
    public static boolean isMarkedOrSuspect(ServerPlayer player) {
        if (CORE.isEmpty()) {
            return false;
        }
        long now = player.level().getGameTime();
        return isMarked(player.getUUID(), now) || isSuspect(player.getUUID(), now);
    }

    // ------------------------------------------------------------------ who can be marked, who is protected

    /** A bot that is not ours and does not own one of ours: the only kind of player the ledger ever holds. */
    public static boolean isMarkableForeignBot(Entity entity) {
        return entity instanceof ServerPlayer player
                && !(player instanceof AIPlayerEntity)
                && PlayerKind.isBot(player)
                && !AIPlayerManager.INSTANCE.isAnyBotOwner(player.getUUID());
    }

    /** A victim whose attacker becomes hostile: any Minecraft-AI bot, or any player who owns one. */
    public static boolean isProtectedVictim(Entity entity) {
        return entity instanceof AIPlayerEntity
                || entity instanceof ServerPlayer player && AIPlayerManager.INSTANCE.isAnyBotOwner(player.getUUID());
    }

    /**
     * True when {@code player} is a marked foreign bot that this bot may treat as an enemy right now: the ledger marks it AND the
     * bot or its owner can see it ({@link SharedVision#seenByBotOrOwner}). Cheap for everyone else (an empty ledger returns at once).
     */
    public static boolean isVisibleAggressor(AIPlayerEntity bot, ServerPlayer player) {
        if (player == null || player == bot || CORE.isEmpty() || !hostileBotsEnabled()) {
            return false;
        }
        if (player.level() != bot.level() || !player.isAlive() || !isMarked(player) || !isMarkableForeignBot(player)) {
            return false;
        }
        return seenThisTick(bot, player);
    }

    /**
     * One vision result per (observer bot, aggressor, game time): {@code StrikeLegality.isFriendly} and {@code hostileTo} both ask
     * within a tick. This is a server-thread cache only: every production caller reads world/entity state on that thread, so the
     * volatile tick and concurrent map are not a licence to call it from a worker. The result is intentionally up to one game tick
     * stale (the map clear and {@code seenTick} update are not one atomic world snapshot); a fixture that moves an entity, turns an
     * owner or changes a wall inside that tick must call {@link #invalidateVisionCache()}.
     */
    private record SeenKey(UUID bot, UUID aggressor) {
    }

    private static final Map<SeenKey, Boolean> SEEN_THIS_TICK = new ConcurrentHashMap<>();
    private static volatile long seenTick = Long.MIN_VALUE;

    /** Drops the cached vision results; for a test that moves an entity, turns the owner or changes a wall inside one tick. */
    static void invalidateVisionCache() {
        SEEN_THIS_TICK.clear();
    }

    private static boolean seenThisTick(AIPlayerEntity bot, ServerPlayer player) {
        long now = bot.level().getGameTime();
        if (now != seenTick) {
            SEEN_THIS_TICK.clear();
            seenTick = now;
        }
        return SEEN_THIS_TICK.computeIfAbsent(new SeenKey(bot.getUUID(), player.getUUID()),
                ignored -> SharedVision.seenByBotOrOwner(bot, player));
    }

    // ------------------------------------------------------------------ mark sources: real damage

    /** Registers the damage and death handlers; called once from {@code MinecraftAiMod.onInitialize}. Repeated calls do nothing. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) ->
                onProtectedVictimHurt(entity, source));
        // AFTER_DAMAGE is not fired for a killing blow: the death event marks the killer of a protected victim too.
        ServerLivingEntityEvents.AFTER_DEATH.register(HostileBotLedger::onProtectedVictimHurt);
        RecentDamage.addDeathListener(death -> {
            CORE.clearAggressor(death.victim());
            HostileBotIntent.forget(death.victim());
        });
    }

    private static void onProtectedVictimHurt(LivingEntity victim, DamageSource source) {
        if (source == null || !hostileBotsEnabled() || !isProtectedVictim(victim)) {
            return;
        }
        Entity attacker = source.getEntity();
        if (attacker == null || attacker == victim || !isMarkableForeignBot(attacker)) {
            return;
        }
        markPlayer((ServerPlayer) attacker, victim, victim.level().getGameTime(), "hit");
    }
}
