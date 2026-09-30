package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The ONE place that ends a live inhabitant, so that "no loot or XP farm" is a property of one class:
 * <ul>
 *   <li><b>Removal is not death.</b> {@link #retire} takes the bot out through {@link BotGateway#remove}, which empties it
 *       first and disconnects it without a death: no item drops, no XP orbs, no death message, and never a death in the
 *       books. The structure's slot is NOT spent by it.</li>
 *   <li><b>A seen bot sleeps</b> (its whole state, position and identity are kept, the record says DORMANT); <b>an unseen
 *       bot is deleted</b> (no record, no snapshot, no profile: its slot is vacant and re-rollable).</li>
 *   <li><b>A death is permanent.</b> {@link #died} turns the record into DEAD; only ever more dead, never a free slot.</li>
 * </ul>
 * Ordering of every removal, so a crash can never leave both a live inventory and a restorable copy of it, and can never
 * turn a removal into a death: (1) the record is marked {@code removing} (a seen bot also gets its snapshot and position and
 * the state DORMANT) and made durable through the store's write-ahead journal -- one small append, never a full save in the
 * middle of a lag spike; (2) only then is the bot emptied and removed; (3) the mark is cleared (seen) or the record dropped
 * (unseen). A crash after (1) leaves a record that says "removing": on the next start a live bot with that mark is removed
 * again ({@link #finishInterrupted}), an absent one is a vacant slot and never a death, and a bot of such a record that
 * rejoins late is removed too ({@link #tickLate}; the snapshot wins, there is never a second copy). A crash before (1)
 * leaves the unchanged live bot.
 * <p>
 * Server thread only.
 */
final class Retirer {
    enum Reason {
        /** The structure left the allocation (or its budget went to a nearer one). */
        ALLOCATION,
        /** The TPS governor shed it under lag. */
        TPS,
        /** The legacy distance rule (used while the allocation is not running). */
        DORMANCY
    }

    enum Result {
        SLEPT, DELETED,
        /**
         * Could not be done now (the store could not be written, or the bot could not be removed). The bot is left as it was:
         * when the removal itself failed after it had been emptied, the adapter puts everything it carried back.
         */
        KEPT
    }

    /** How often (ticks) bots of interrupted removals are checked for a late rejoin. */
    static final int LATE_CHECK_TICKS = 20;

    private final EngineContext ctx;
    private final BotRoster roster;
    /** Lower-case names being removed right now: a death event that arrives meanwhile is not a real death. */
    private final Set<String> removing = new HashSet<>();
    /** Lower-case names of bots already reported as put to sleep without a readable state (once per bot is enough). */
    private final Set<String> noStateReported = new HashSet<>();
    /** Bots of records that were "removing" at the last start: watched for a late rejoin (see {@link #tickLate}). */
    private final Map<String, Late> late = new LinkedHashMap<>();
    private Predicate<String> inFlight = name -> false;
    private long nextLateCheck;

    Retirer(EngineContext ctx, BotRoster roster) {
        this.ctx = ctx;
        this.roster = roster;
    }

    /** Whether a spawn or a wake of this bot name is in flight (set once by the engine): such a bot is not a stray. */
    void setInFlight(Predicate<String> inFlight) {
        this.inFlight = inFlight == null ? name -> false : inFlight;
    }

    boolean isRemoving(String name) {
        return name != null && removing.contains(name.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ removal (never a death)

    /** One bot to take out: its structure and its record. */
    record Item(StructureKey key, BotRecord bot) {
    }

    /** Puts a live bot to sleep when a player has seen it, deletes it otherwise. */
    Result retire(StructureKey key, BotRecord bot, Reason reason, InhabitantsConfig cfg) {
        return retireAll(List.of(new Item(key, bot)), reason, cfg).get(0);
    }

    /**
     * Takes several bots out, in order. The records of ALL of them (a seen one: state, position, the {@code removing} mark; an
     * unseen one: the mark) are made durable with ONE journal append before any of them is emptied, so a batch costs one
     * small write instead of one per bot and the crash ordering still holds for every bot: a bot is only ever emptied
     * after its own record is on disk. If that write fails nothing is emptied and every bot of the batch is left exactly as
     * it was.
     */
    List<Result> retireAll(List<Item> items, Reason reason, InhabitantsConfig cfg) {
        List<Result> results = new ArrayList<>(items.size());
        List<Marked> marked = new ArrayList<>(items.size());
        Set<StructureKey> keys = new LinkedHashSet<>();
        for (Item item : items) {
            removing.add(item.bot().name.toLowerCase(Locale.ROOT));
            marked.add(item.bot().seen ? prepareSleep(item.key(), item.bot()) : prepareDelete(item.key(), item.bot()));
            keys.add(item.key());
        }
        List<Marked> undone = new ArrayList<>();
        try {
            boolean durable = !items.isEmpty() && ctx.journalNow(keys);
            for (Marked m : marked) {
                if (!durable) {
                    m.undo();
                    results.add(Result.KEPT); // nothing was emptied: the bot is exactly as it was
                } else {
                    Result r = m.seen ? finishSleep(m, reason, cfg) : finishDelete(m, reason, cfg);
                    if (r == Result.KEPT) {
                        undone.add(m);
                    }
                    results.add(r);
                }
            }
            if (!undone.isEmpty()) {
                // The bots that could not be removed live on with the state they had: make that durable as well (best effort),
                // so a crash does not roll them back to the state the journal recorded for the removal.
                Set<StructureKey> again = new LinkedHashSet<>();
                for (Marked m : undone) {
                    again.add(m.key);
                }
                ctx.journalNow(again);
            }
        } finally {
            for (Item item : items) {
                removing.remove(item.bot().name.toLowerCase(Locale.ROOT));
            }
        }
        return results;
    }

    /** What a bot was before its removal was recorded, so a failed removal puts it back exactly. */
    private final class Marked {
        final StructureKey key;
        final BotRecord bot;
        final boolean seen;
        final BotState state;
        final boolean removingBefore;
        final BotSnapshot snapshot;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final String dimension;

        Marked(StructureKey key, BotRecord bot) {
            this.key = key;
            this.bot = bot;
            this.seen = bot.seen;
            this.state = bot.state;
            this.removingBefore = bot.removing;
            this.snapshot = bot.snapshot;
            this.x = bot.x;
            this.y = bot.y;
            this.z = bot.z;
            this.yaw = bot.yaw;
            this.dimension = bot.dimension;
        }

        void undo() {
            bot.state = state;
            bot.removing = removingBefore;
            bot.snapshot = snapshot;
            bot.x = x;
            bot.y = y;
            bot.z = z;
            bot.yaw = yaw;
            bot.dimension = dimension;
            ctx.store.markDirty();
        }
    }

    /** Step 1 of a sleep: the live state and the place go into the record, marked "removing" (made durable by the caller). */
    private Marked prepareSleep(StructureKey key, BotRecord bot) {
        Marked before = new Marked(key, bot);
        if (roster.isRestored(bot)) {
            BotSnapshot snapshot = ctx.snapshot(bot.name);
            if (snapshot != null) {
                bot.snapshot = snapshot;
            } else if (bot.snapshot == null && noStateReported.add(bot.name.toLowerCase(Locale.ROOT))) {
                ctx.info("Inhabitant {} went to sleep without a saved state (its state could not be read); it will be dressed "
                        + "from its profile when it wakes", bot.name);
            }
        }
        BotGateway.PlayerPos where = ctx.position(bot.name);
        if (where != null) {
            bot.x = where.x();
            bot.y = where.y();
            bot.z = where.z();
            bot.yaw = where.yaw();
            bot.dimension = key.dimension().equals(where.dimension()) ? null : where.dimension();
        }
        bot.state = BotState.DORMANT;
        bot.removing = true;
        ctx.store.markDirty();
        return before;
    }

    /** Step 1 of a deletion: only the mark, so a crash before the record is gone can never read as a death. */
    private Marked prepareDelete(StructureKey key, BotRecord bot) {
        Marked before = new Marked(key, bot);
        bot.removing = true;
        ctx.store.markDirty();
        return before;
    }

    /** Steps 2 and 3 of a sleep: the bot is emptied and removed (no drops, no death), then the mark is cleared. */
    private Result finishSleep(Marked s, Reason reason, InhabitantsConfig cfg) {
        BotRecord bot = s.bot;
        ctx.discard(bot.name);
        Boolean stillThere = ctx.online(bot.name);
        if (stillThere == null || stillThere) {
            // It could not be removed (the adapter put back what it had emptied). The record must not keep saying "asleep with
            // a saved copy" while the bot lives on.
            s.undo();
            return Result.KEPT;
        }
        bot.removing = false;
        ctx.store.markDirty();
        roster.untrackOne(bot);
        ctx.debug(cfg, "Inhabitant {} sleeps ({}): seen by a player, its state and place are kept", bot.name, reason);
        return Result.SLEPT;
    }

    /** Steps 2 and 3 of a deletion: emptied and removed first; the record goes only once the bot is really gone. */
    private Result finishDelete(Marked m, Reason reason, InhabitantsConfig cfg) {
        BotRecord bot = m.bot;
        ctx.discard(bot.name);
        Boolean stillThere = ctx.online(bot.name);
        if (stillThere == null || stillThere) {
            m.undo();
            return Result.KEPT;
        }
        roster.untrackOne(bot);
        dropRecord(m.key, bot);
        ctx.debug(cfg, "Inhabitant {} deleted ({}): no player ever saw it, nothing is kept and its slot is free again",
                bot.name, reason);
        return Result.DELETED;
    }

    /** Takes an unseen bot out of its structure's record: its slot is vacant (its index is never reused). */
    private void dropRecord(StructureKey key, BotRecord bot) {
        StructureRecord rec = ctx.store.find(key).orElse(null);
        if (rec != null) {
            rec.nextBotIndex = Math.max(rec.nextBotIndex, bot.index + 1);
            rec.bots.remove(bot);
            ctx.store.reindex(key);
            ctx.store.markDirty();
        }
    }

    // ------------------------------------------------------------------ death (permanent)

    /**
     * The bot died while alive (any cause) or is gone for good without this addon having removed it. Its record becomes
     * DEAD: it keeps its name and index, drops everything else, and the slot is spent for good.
     */
    void died(StructureKey key, BotRecord bot, String how) {
        if (bot.state == BotState.DEAD) {
            return;
        }
        bot.state = BotState.DEAD;
        bot.profile = null;
        bot.snapshot = null;
        bot.removing = false;
        bot.failure = null;
        ctx.store.markDirty();
        roster.untrackOne(bot);
        late.remove(EngineContext.lower(bot.name));
        ctx.guard("forget-dead", () -> ctx.bots.forget(bot.name));
        StructureRecord rec = ctx.store.find(key).orElse(null);
        InhabitantsConfig cfg = ctx.config();
        if (cfg != null) {
            ctx.debug(cfg, "Inhabitant {} of {} died ({}); its slot is never refilled ({} of {} planned are dead)", bot.name, key, how,
                    rec == null ? 0 : rec.deadCount(), rec == null ? 0 : rec.plannedBots);
        }
    }

    // ------------------------------------------------------------------ after a crash

    /** A bot of a record that was "removing" when the server went down; see {@link #finishInterrupted}. */
    private record Late(StructureKey key, BotRecord bot, long until) {
    }

    /**
     * Finishes the removals that a crash or a stop interrupted, once the restore settle period is over (PvP BOT has brought
     * its bots back by then): a record still marked {@code removing} whose bot is online has the bot emptied and removed
     * now (the record already holds the state that counts, so there is never a second copy); one whose bot is offline just
     * loses the mark (seen) or stays a vacant slot until the gone period ends (unseen; then the record goes). Either way it is
     * never a death. The bots of such records are then watched for {@code goneConfirmTicks}: one that rejoins late is handled
     * the same way ({@link #tickLate}).
     *
     * @return true when every marked record was dealt with (false: the gateway could not tell for some; call again later)
     */
    boolean finishInterrupted(InhabitantsConfig cfg) {
        long now = ctx.now();
        long until = now + Math.max(1, EngineContext.processing(cfg).goneConfirmTicks);
        int finished = 0;
        boolean all = true;
        List<StructureKey> keys = new ArrayList<>();
        List<BotRecord> bots = new ArrayList<>();
        for (Map.Entry<StructureKey, StructureRecord> e : ctx.store.nonAbandoned()) {
            for (BotRecord bot : e.getValue().bots) {
                if (bot.removing && bot.name != null) {
                    keys.add(e.getKey());
                    bots.add(bot);
                }
            }
        }
        for (int i = 0; i < bots.size(); i++) {
            StructureKey key = keys.get(i);
            BotRecord bot = bots.get(i);
            Boolean online = ctx.online(bot.name);
            if (online == null) {
                all = false; // cannot tell: try again next time
                continue;
            }
            if (online && !discardStray(bot)) {
                all = false; // could not be removed now: try again next time
                continue;
            }
            if (bot.seen) {
                bot.removing = false; // the snapshot in the record is what counts; the bot may wake now
                ctx.store.markDirty();
                late.put(EngineContext.lower(bot.name), new Late(key, bot, until));
            } else if (online) {
                dropRecord(key, bot);
            } else {
                late.put(EngineContext.lower(bot.name), new Late(key, bot, until)); // a vacant slot until the period ends
            }
            finished++;
        }
        if (finished > 0) {
            ctx.info("Finished {} removal(s) that a restart had interrupted (nothing is a death; the bot was emptied and removed once more)", finished);
        }
        return all;
    }

    /**
     * Bots of interrupted removals that came back after {@link #finishInterrupted} ran (PvP BOT restores its bots one by one,
     * possibly late): a seen one that is online while its record says DORMANT is a second copy of what the snapshot holds,
     * so it is emptied and removed (the snapshot wins); an unseen one is removed and its record dropped. After the gone
     * period a still-absent unseen bot's record is dropped (a vacant slot, never a death).
     */
    void tickLate(long now) {
        if (late.isEmpty() || now < nextLateCheck) {
            return;
        }
        nextLateCheck = now + LATE_CHECK_TICKS;
        for (Late l : new ArrayList<>(late.values())) {
            String lower = EngineContext.lower(l.bot().name);
            BotRecord bot = l.bot();
            if (bot.state == BotState.DEAD || (bot.state == BotState.SPAWNED && bot.seen)) {
                late.remove(lower); // it died, or its wake completed: nothing to watch any more
                continue;
            }
            Boolean online = ctx.online(bot.name);
            if (online == null) {
                continue;
            }
            if (online && !inFlight.test(bot.name)) {
                if (discardStray(bot)) {
                    ctx.info("Inhabitant {} rejoined after an interrupted removal; it was emptied and removed again (its saved state "
                            + "or its deleted record stands, there is no second copy)", bot.name);
                    if (!bot.seen) {
                        dropRecord(l.key(), bot);
                        late.remove(lower);
                    }
                }
                continue;
            }
            if (now >= l.until()) {
                late.remove(lower);
                if (!online && !bot.seen && bot.removing) {
                    dropRecord(l.key(), bot); // gone for the whole period: a vacant slot
                }
            }
        }
    }

    /** How many bots of interrupted removals are being watched for a late rejoin (diagnostics and tests). */
    int lateWatched() {
        return late.size();
    }

    /** Empties and removes an online bot the record says is not to exist as a live one; true when it is gone. */
    private boolean discardStray(BotRecord bot) {
        String lower = EngineContext.lower(bot.name);
        removing.add(lower);
        try {
            ctx.discard(bot.name);
        } finally {
            removing.remove(lower);
        }
        Boolean still = ctx.online(bot.name);
        return still != null && !still;
    }
}
