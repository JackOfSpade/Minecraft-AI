package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds out which inhabitants a real player has SEEN (the gateway answers with the player's own view: the bot inside the
 * view cone, nothing in the way, within vanilla sight range) and records it, for good, in the bot's record: a seen bot is
 * never deleted or lost to an eviction, it sleeps with its whole state instead (see {@link Retirer}). A bot the player
 * never saw is ephemeral.
 * <p>
 * Cheap on purpose: one gateway question per live inhabitant every {@code allocation.seenCheckTicks} ticks (the gateway
 * does the cone test first and the rays last, and answers at once when no player is near); a bot that is already seen is
 * only asked again every {@link #RESEEN_TICKS} to keep its "last seen" time current.
 */
final class SeenTracker {
    /** A bot that is already seen is looked at again this often (ticks) for its "last seen" time. */
    static final int RESEEN_TICKS = 600;

    private final EngineContext ctx;
    private final BotRoster roster;
    private final AllocationGovernor governor;
    private long nextTick = PendingStructure.NEVER;
    /** Lower-case name -> tick it was last looked at while already seen. */
    private final Map<String, Long> lastLook = new HashMap<>();

    SeenTracker(EngineContext ctx, BotRoster roster, AllocationGovernor governor) {
        this.ctx = ctx;
        this.roster = roster;
        this.governor = governor;
    }

    /** Server-thread nanoseconds spent here so far (diagnostics and the cost test). */
    long nanos;

    void tick(long now, InhabitantsConfig cfg) {
        long started = System.nanoTime();
        try {
            look(now, cfg);
        } finally {
            nanos += System.nanoTime() - started;
        }
    }

    private void look(long now, InhabitantsConfig cfg) {
        if (nextTick != PendingStructure.NEVER && now < nextTick) {
            return;
        }
        nextTick = now + Math.max(1, EngineContext.allocation(cfg).seenCheckTicks);
        Map<String, Long> stillLive = new HashMap<>();
        for (Map.Entry<StructureKey, BotRecord> e : roster.onlineEntries()) {
            BotRecord bot = e.getValue();
            String lower = EngineContext.lower(bot.name);
            if (bot.seen) {
                Long last = lastLook.get(lower);
                if (last != null) {
                    stillLive.put(lower, last);
                }
                if (last != null && now - last < RESEEN_TICKS) {
                    continue;
                }
                stillLive.put(lower, now);
                if (ctx.seenByHuman(bot.name)) {
                    bot.lastSeenMillis = ctx.clock.nowMillis();
                    ctx.store.markDirty();
                }
                continue;
            }
            if (ctx.seenByHuman(bot.name)) {
                long millis = ctx.clock.nowMillis();
                bot.seen = true;
                bot.firstSeenMillis = millis;
                bot.lastSeenMillis = millis;
                stillLive.put(lower, now);
                ctx.store.markDirty();
                governor.markDirty();
                ctx.debug(cfg, "Inhabitant {} of {} was seen by a player: it is kept from now on (asleep when it must go, never deleted)",
                        bot.name, e.getKey());
            }
        }
        lastLook.clear();
        lastLook.putAll(stillLive);
    }
}
