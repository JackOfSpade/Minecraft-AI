package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * In-memory index of every SPAWNED inhabitant, used for three things the persisted records cannot answer
 * cheaply: how many inhabitants are alive right now (the {@code maxLiveBots} cap), re-establishing state that
 * upstream does not persist (path following) after a restart, and noticing bots that are gone for good.
 * <p>
 * Death is permanent, so "gone" only ever releases upstream leftovers ({@code forget}); it never touches the
 * structure's status and never leads to a respawn.
 * <p>
 * Cost model: asking the server whether a name is online is a linear player-list scan, so the roster is
 * examined in a round-robin slice (one full pass per {@link #SCAN_PERIOD_TICKS}) rather than every bot every
 * tick. Bots known to be offline sit on a separate short watch list whose deadlines are checked every tick, so
 * the {@code goneConfirmTicks} timing stays precise without asking the server anything.
 * <p>
 * Nothing here runs before the settle period is over: upstream restores its bots one by one after a start, and a
 * bot that has merely not been restored yet must not be mistaken for a dead one.
 */
final class BotRoster {
    /** A complete pass over the roster takes this many ticks. */
    static final int SCAN_PERIOD_TICKS = 20;

    private static final class Tracked {
        final StructureKey structure;
        final BotRecord bot;
        boolean online;
        /** Tick at which it was first seen offline (after settling), or -1 while online / already forgotten. */
        long offlineSince = -1;
        boolean restored;
        boolean forgotten;

        Tracked(StructureKey structure, BotRecord bot) {
            this.structure = structure;
            this.bot = bot;
        }
    }

    private final EngineContext ctx;
    private final List<Tracked> entries = new ArrayList<>();
    private final Map<String, Tracked> byName = new HashMap<>();
    private final List<Tracked> offlineWatch = new ArrayList<>();
    private int onlineCount;
    private int cursor;
    private boolean built;

    BotRoster(EngineContext ctx) {
        this.ctx = ctx;
    }

    /** Loads the SPAWNED bots of every non-abandoned record once. Retried next tick if the store throws. */
    void build() {
        if (built) {
            return;
        }
        List<Map.Entry<StructureKey, StructureRecord>> all = ctx.store.nonAbandoned();
        for (Map.Entry<StructureKey, StructureRecord> e : all) {
            for (BotRecord b : e.getValue().bots) {
                if (b.state == BotState.SPAWNED && b.name != null) {
                    add(e.getKey(), b, false);
                }
            }
        }
        built = true;
    }

    /** A bot that has just been spawned and dressed by this engine: alive, and its path is already set. */
    void trackSpawned(StructureKey structure, BotRecord bot) {
        Tracked existing = byName.get(EngineContext.lower(bot.name));
        if (existing != null) {
            setOnline(existing, true);
            existing.restored = true;
            existing.offlineSince = -1;
            existing.forgotten = false;
            offlineWatch.remove(existing);
            return;
        }
        add(structure, bot, true);
    }

    private void add(StructureKey structure, BotRecord bot, boolean freshlySpawned) {
        String key = EngineContext.lower(bot.name);
        if (byName.containsKey(key)) {
            return;
        }
        Tracked t = new Tracked(structure, bot);
        t.online = freshlySpawned;
        t.restored = freshlySpawned;
        if (freshlySpawned) {
            onlineCount++;
        }
        entries.add(t);
        byName.put(key, t);
    }

    /** Drops every bot of a structure (admin reset). */
    void untrack(StructureKey structure) {
        for (Iterator<Tracked> it = entries.iterator(); it.hasNext(); ) {
            Tracked t = it.next();
            if (t.structure.equals(structure)) {
                it.remove();
                byName.remove(EngineContext.lower(t.bot.name));
                offlineWatch.remove(t);
                if (t.online) {
                    onlineCount--;
                }
            }
        }
        if (cursor > entries.size()) {
            cursor = 0;
        }
    }

    /**
     * Drops tracking for one bot without touching its siblings: the caller has already despawned it on
     * purpose (dormancy, TPS throttling) and knows why, so there is nothing left for this roster to reconcile.
     */
    void untrackOne(BotRecord bot) {
        String key = EngineContext.lower(bot.name);
        Tracked t = byName.remove(key);
        if (t == null) {
            return;
        }
        entries.remove(t);
        offlineWatch.remove(t);
        if (t.online) {
            onlineCount--;
        }
        if (cursor > entries.size()) {
            cursor = 0;
        }
    }

    /** Every inhabitant currently believed online, with which structure it belongs to. */
    List<Map.Entry<StructureKey, BotRecord>> onlineEntries() {
        List<Map.Entry<StructureKey, BotRecord>> out = new ArrayList<>();
        for (Tracked t : entries) {
            if (t.online) {
                out.add(Map.entry(t.structure, t.bot));
            }
        }
        return out;
    }

    int size() {
        return entries.size();
    }

    /** Cached number of inhabitants believed online; refreshed by {@link #tick}. */
    int online() {
        return onlineCount;
    }

    /** Exact number of inhabitants online right now (asks the server for each; for admin statistics only). */
    int countOnlineNow() {
        int n = 0;
        for (Tracked t : entries) {
            Boolean o = ctx.online(t.bot.name);
            if (o != null && o) {
                n++;
            }
        }
        return n;
    }

    /**
     * Learns who is online right after the settle period, so the live-bot cap is accurate from the first
     * spawn decision on, and starts the offline clocks. Restoration itself is spread by {@link #tick}.
     */
    void refreshAll(long now) {
        for (Tracked t : new ArrayList<>(entries)) {
            observe(t, now);
        }
    }

    /** One step of reconciliation: deadlines first, then the next slice of the round-robin scan. */
    void tick(long now, InhabitantsConfig cfg) {
        checkGone(now, cfg);
        int n = entries.size();
        if (n == 0) {
            return;
        }
        int slice = Math.min(n, Math.max(1, (n + SCAN_PERIOD_TICKS - 1) / SCAN_PERIOD_TICKS));
        for (int i = 0; i < slice; i++) {
            if (cursor >= entries.size()) {
                cursor = 0;
            }
            Tracked t = entries.get(cursor++);
            observe(t, now);
            maybeRestore(t, cfg);
        }
    }

    private void observe(Tracked t, long now) {
        Boolean online = ctx.online(t.bot.name);
        if (online == null) {
            return;
        }
        setOnline(t, online);
        if (online) {
            t.offlineSince = -1;
            offlineWatch.remove(t);
            if (t.forgotten) {
                // Came back after being released: upstream's per-bot state is gone, so it must be re-applied.
                t.forgotten = false;
                t.restored = false;
            }
        } else {
            t.restored = false;
            if (t.offlineSince < 0 && !t.forgotten) {
                t.offlineSince = now;
                offlineWatch.add(t);
            }
        }
    }

    private void maybeRestore(Tracked t, InhabitantsConfig cfg) {
        // profiles.reapplyOnRestore only gates re-dressing (loadout/vitals) inside BotGateway.restore() itself;
        // it must NOT skip the call altogether, because that call is also what re-establishes patrol following,
        // which PvP BOT never persists across a restart regardless of that setting.
        if (!t.online || t.restored || t.bot.profile == null) {
            return;
        }
        Boolean managed = ctx.managed(t.bot.name);
        if (managed == null || !managed) {
            return;
        }
        t.restored = true;
        try {
            boolean changed = ctx.bots.restore(t.bot.name, t.bot.profile);
            ctx.debug(cfg, "Restored upstream state of inhabitant {} (changed: {})", t.bot.name, changed);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            ctx.log.error("restore", t.bot.name, e);
        }
    }

    private void checkGone(long now, InhabitantsConfig cfg) {
        if (offlineWatch.isEmpty()) {
            return;
        }
        long confirm = Math.max(1, EngineContext.processing(cfg).goneConfirmTicks);
        for (Iterator<Tracked> it = offlineWatch.iterator(); it.hasNext(); ) {
            Tracked t = it.next();
            if (now - t.offlineSince < confirm) {
                continue;
            }
            it.remove();
            Boolean online = ctx.online(t.bot.name);
            if (online != null && online) {
                setOnline(t, true);
                t.offlineSince = -1;
                continue;
            }
            t.forgotten = true;
            t.offlineSince = -1;
            try {
                ctx.bots.forget(t.bot.name);
                ctx.debug(cfg, "Inhabitant {} has been offline for {} ticks; released its upstream state", t.bot.name, confirm);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable e) {
                ctx.log.error("forget", t.bot.name, e);
            }
        }
    }

    private void setOnline(Tracked t, boolean online) {
        if (t.online != online) {
            t.online = online;
            onlineCount += online ? 1 : -1;
        }
    }
}
