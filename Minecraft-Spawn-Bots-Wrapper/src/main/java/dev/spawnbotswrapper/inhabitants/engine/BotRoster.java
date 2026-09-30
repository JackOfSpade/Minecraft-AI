package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
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
 * A bot that is gone for good without this addon having removed it died (every removal of the addon untracks the bot
 * first): its record becomes DEAD ({@link Retirer#died}), its upstream leftovers are released, and it is never replaced.
 * <p>
 * Cost model: asking the server whether a name is online is a linear player-list scan, so the roster is
 * examined in a round-robin slice (one full pass per {@link #SCAN_PERIOD_TICKS}) rather than every bot every
 * tick. Bots known to be offline sit on a separate short watch list whose deadlines are checked every tick, so
 * the {@code goneConfirmTicks} timing stays precise without asking the server anything.
 * <p>
 * Nothing that concludes anything runs before the settle period is over: upstream restores its bots one by one
 * after a start, and a bot that has merely not been restored yet must not be mistaken for a dead one. The single
 * exception is {@link #restoreSettling}, which only gives already-restored bots their path back.
 */
final class BotRoster {
    /** A complete pass over the roster takes this many ticks. */
    static final int SCAN_PERIOD_TICKS = 20;

    /** Each online inhabitant's inventory is swept (ender pearls, disabled enchantments) at most this often (5 s). */
    static final int PEARL_SWEEP_TICKS = 100;

    /** Each online inhabitant's state (inventory, health, hunger, effects) is saved to its record at most this often (5 s), and only when it changed. */
    static final int SNAPSHOT_TICKS = 100;

    /** Each online inhabitant is checked for survival mode and for stats of this addon's older versions this often (5 s). */
    static final int VANILLA_SWEEP_TICKS = 100;

    private static final class Tracked {
        final StructureKey structure;
        final BotRecord bot;
        boolean online;
        /** Tick at which it was first seen offline (after settling), or -1 while online / already forgotten. */
        long offlineSince = -1;
        boolean restored;
        boolean forgotten;
        /** Tick from which the next inventory sweep is due. */
        long nextPearlSweep;
        /** The first removal of each kind is logged at INFO, later ones only in debug mode. */
        boolean pearlsLogged;
        boolean enchantmentsLogged;
        /** Tick from which the next state snapshot is due. */
        long nextSnapshot;
        /** Tick from which the next survival-mode / vanilla-attribute check is due. */
        long nextVanillaSweep;
        boolean gameModeLogged;
        boolean modifiersLogged;
        /** Tick at which it became a live inhabitant of this session (spawned, woken or first tracked). */
        long since;

        Tracked(StructureKey structure, BotRecord bot) {
            this.structure = structure;
            this.bot = bot;
        }
    }

    private Retirer retirer;
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

    /** The one place that ends an inhabitant; set once by the engine, right after construction. */
    void setRetirer(Retirer retirer) {
        this.retirer = retirer;
    }

    /** Tick at which the inhabitant became live in this session, or -1 when it is not tracked live. */
    long onlineSince(BotRecord bot) {
        Tracked t = byName.get(EngineContext.lower(bot.name));
        return t != null && t.online ? t.since : -1;
    }

    /** How many inhabitants of the structure are believed online. */
    int liveCount(StructureKey structure) {
        int n = 0;
        for (Tracked t : entries) {
            if (t.online && t.structure.equals(structure)) {
                n++;
            }
        }
        return n;
    }

    /** Loads the SPAWNED bots of every non-abandoned record once. Retried next tick if the store throws. */
    void build() {
        if (built) {
            return;
        }
        List<Map.Entry<StructureKey, StructureRecord>> all = ctx.store.nonAbandoned();
        for (Map.Entry<StructureKey, StructureRecord> e : all) {
            for (BotRecord b : e.getValue().bots) {
                if (b.state == BotState.SPAWNED && b.name != null && !b.removing) {
                    // A record still marked "removing" is an interrupted removal (a crash): {@link Retirer#finishInterrupted}
                    // deals with it, it is never tracked as a live bot (and so can never be concluded dead).
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
            existing.since = ctx.now();
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
        t.since = ctx.now();
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
            sweepVanilla(t, now, cfg);
            sweepPearls(t, now, cfg);
            snapshotIfDue(t, now);
        }
    }

    /**
     * The only thing that runs DURING the settle period: gives a bot that PvP BOT has already brought back (online
     * and listed) its path and follower again, so restored inhabitants patrol and fight within seconds of a restart
     * instead of standing idle for the whole settle window. It deliberately stops there: it never starts an offline
     * clock, never releases upstream state and never concludes anything about a bot that is not back yet, which is
     * exactly what the settle period protects (a bot merely not restored yet must not be mistaken for a dead one).
     * Same round-robin slice as {@link #tick}, so the cost per tick is the same; bots already restored cost nothing.
     */
    void restoreSettling(InhabitantsConfig cfg) {
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
            if (t.restored || t.bot.profile == null) {
                continue;
            }
            Boolean online = ctx.online(t.bot.name);
            if (online == null || !online) {
                continue;
            }
            setOnline(t, true);
            maybeRestore(t, cfg);
            sweepVanilla(t, ctx.now(), cfg);
            sweepPearls(t, ctx.now(), cfg);
            snapshotIfDue(t, ctx.now());
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
            boolean changed = ctx.bots.restore(t.bot.name, t.bot.profile, t.bot.snapshot);
            ctx.debug(cfg, "Restored upstream state of inhabitant {} (changed: {})", t.bot.name, changed);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            ctx.log.error("restore", t.bot.name, e);
        }
    }

    /**
     * Sweeps an online inhabitant's inventory, at most once per {@link #PEARL_SWEEP_TICKS} per bot (so a freshly
     * restored bot is cleaned on its first visit and something picked up later goes within seconds): ender pearls,
     * which make PvP BOT's cobweb escape loop switch the held slot every tick and cancel the bot's own crossbow charge
     * and attacks (see {@code LoadoutRoller}), and every enchantment the config disables (Piercing by default: a
     * piercing bolt ignores a raised shield). Each kind is contained on its own, and so is each bot: a failure is
     * logged and never reaches the caller or the other bots. The first removal per bot and kind is logged at INFO,
     * later ones only in debug mode. Never throws.
     */
    private void sweepPearls(Tracked t, long now, InhabitantsConfig cfg) {
        if (!t.online || now < t.nextPearlSweep) {
            return;
        }
        t.nextPearlSweep = now + PEARL_SWEEP_TICKS;
        try {
            int removed = ctx.bots.stripEnderPearls(t.bot.name);
            if (removed > 0) {
                if (!t.pearlsLogged) {
                    t.pearlsLogged = true;
                    ctx.info("Removed {} ender pearl(s) from inhabitant {} (they make PvP BOT's cobweb escape loop cancel its attacks)",
                            removed, t.bot.name);
                } else {
                    ctx.debug(cfg, "Removed {} more ender pearl(s) from inhabitant {}", removed, t.bot.name);
                }
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            ctx.log.error("stripEnderPearls", t.bot.name, e);
        }
        try {
            List<String> removed = ctx.bots.stripDisabledEnchantments(t.bot.name);
            if (removed != null && !removed.isEmpty()) {
                if (!t.enchantmentsLogged) {
                    t.enchantmentsLogged = true;
                    ctx.info("Removed disabled enchantment(s) from inhabitant {}: {} (profiles.disabledEnchantments)",
                            t.bot.name, removed);
                } else {
                    ctx.debug(cfg, "Removed more disabled enchantment(s) from inhabitant {}: {}", t.bot.name, removed);
                }
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            ctx.log.error("stripDisabledEnchantments", t.bot.name, e);
        }
    }

    /** True when this bot has been through {@link #maybeRestore} since it came online (its live state is its own again). */
    boolean isRestored(BotRecord bot) {
        Tracked t = byName.get(EngineContext.lower(bot.name));
        return t != null && t.online && t.restored;
    }

    /**
     * Writes the live state of every restored online inhabitant into its record now, changed or not; called when the
     * server stops so a restart brings each bot back with exactly the health, hunger and items it had. A bot that has
     * not been restored yet after a start is skipped: what it carries is not its own state yet (a fake player is created
     * at full health).
     */
    void snapshotAll() {
        for (Tracked t : new ArrayList<>(entries)) {
            if (t.online && t.restored && t.bot.profile != null) {
                ctx.guard("snapshot", () -> snapshot(t, true));
            }
        }
    }

    private void snapshotIfDue(Tracked t, long now) {
        if (!t.online || !t.restored || t.bot.profile == null || now < t.nextSnapshot) {
            return;
        }
        t.nextSnapshot = now + SNAPSHOT_TICKS;
        ctx.guard("snapshot", () -> snapshot(t, false));
    }

    private void snapshot(Tracked t, boolean force) {
        BotSnapshot next = ctx.snapshot(t.bot.name);
        if (next == null) {
            return;
        }
        BotSnapshot previous = t.bot.snapshot;
        if (BotSnapshot.worthPersisting(previous, next) || (force && !next.equals(previous))) {
            t.bot.snapshot = next;
            ctx.store.markDirty();
        }
    }

    /**
     * Keeps an online inhabitant an ordinary survival player with vanilla attributes: HeroBot spawns a fake player in
     * CREATIVE unless told otherwise (PvP BOT's own switch to survival can run before the player exists), and older
     * versions of this addon gave every bot permanent attribute modifiers (max health, reach, knockback resistance,
     * attack speed) that no item or effect stands behind. Both are corrected through vanilla's own paths, the first
     * occurrence per bot and kind at WARN/INFO, later ones only in debug mode. Never throws.
     */
    private void sweepVanilla(Tracked t, long now, InhabitantsConfig cfg) {
        if (!t.online || now < t.nextVanillaSweep) {
            return;
        }
        t.nextVanillaSweep = now + VANILLA_SWEEP_TICKS;
        BotGateway.StateFixes fixes = ctx.enforceVanilla(t.bot.name);
        if (fixes.previousGameMode() != null || !fixes.abilities().isEmpty()) {
            String what = (fixes.previousGameMode() != null ? "game mode " + fixes.previousGameMode() : "")
                    + (fixes.previousGameMode() != null && !fixes.abilities().isEmpty() ? ", " : "")
                    + (fixes.abilities().isEmpty() ? "" : "abilities " + fixes.abilities());
            if (!t.gameModeLogged) {
                t.gameModeLogged = true;
                ctx.warn("Inhabitant {} was not an ordinary survival player ({}); it was put into survival mode", t.bot.name, what);
            } else {
                ctx.debug(cfg, "Put inhabitant {} back into survival mode ({})", t.bot.name, what);
            }
        }
        if (!fixes.modifiers().isEmpty()) {
            if (!t.modifiersLogged) {
                t.modifiersLogged = true;
                ctx.info("Removed attribute modifiers that older versions of this addon gave inhabitant {}: {} (inhabitants have "
                        + "vanilla attributes only)", t.bot.name, fixes.modifiers());
            } else {
                ctx.debug(cfg, "Removed more attribute modifiers from inhabitant {}: {}", t.bot.name, fixes.modifiers());
            }
        }
    }

    private void checkGone(long now, InhabitantsConfig cfg) {
        if (offlineWatch.isEmpty()) {
            return;
        }
        long confirm = Math.max(1, EngineContext.processing(cfg).goneConfirmTicks);
        List<Tracked> gone = null;
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
            if (retirer != null && t.bot.state == BotState.SPAWNED) {
                // Offline for good and not taken out by this addon (every removal of ours untracks first): it died. Its
                // upstream leftovers are released by the death itself.
                if (gone == null) {
                    gone = new ArrayList<>();
                }
                gone.add(t);
                continue;
            }
            try {
                ctx.bots.forget(t.bot.name);
                ctx.debug(cfg, "Inhabitant {} has been offline for {} ticks; released its upstream state", t.bot.name, confirm);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable e) {
                ctx.log.error("forget", t.bot.name, e);
            }
        }
        if (gone != null) {
            for (Tracked t : gone) {
                retirer.died(t.structure, t.bot, "it was gone for " + confirm + " ticks");
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
