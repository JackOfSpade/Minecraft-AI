package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * Ordering for a sleep, so a crash can never leave both a live inventory and a restorable copy of it: (1) the snapshot and
 * the position go into the record, marked {@code removing}, and the store is written; (2) only then is the bot emptied and
 * removed; (3) the mark is cleared. A crash after (1) leaves a record that says "removing" -- on the next start a live bot
 * with that mark is removed again ({@link #finishInterrupted}) -- and a crash before (1) leaves the unchanged live bot.
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
        /** Could not be done now (the store could not be written, or the bot could not be removed); nothing was lost. */
        KEPT
    }

    private final EngineContext ctx;
    private final BotRoster roster;
    /** Lower-case names being removed right now: a death event that arrives meanwhile is not a real death. */
    private final Set<String> removing = new HashSet<>();
    /** Lower-case names of bots already reported as put to sleep without a readable state (once per bot is enough). */
    private final Set<String> noStateReported = new HashSet<>();

    Retirer(EngineContext ctx, BotRoster roster) {
        this.ctx = ctx;
        this.roster = roster;
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
     * Takes several bots out, in order. The records of all the SEEN ones among them (state, position, the {@code removing}
     * mark) are made durable with ONE write of the store before any of them is emptied, so a batch of sleeps costs one save
     * instead of one per bot and the crash ordering still holds for every bot: a bot is only ever emptied after its own
     * record is on disk. If that write fails nothing is emptied and every seen bot of the batch is left exactly as it was.
     */
    List<Result> retireAll(List<Item> items, Reason reason, InhabitantsConfig cfg) {
        List<Result> results = new ArrayList<>(items.size());
        List<Sleeper> sleepers = new ArrayList<>();
        for (Item item : items) {
            removing.add(item.bot().name.toLowerCase(Locale.ROOT));
            sleepers.add(item.bot().seen ? prepareSleep(item.key(), item.bot()) : null);
        }
        try {
            boolean anySleeper = sleepers.stream().anyMatch(s -> s != null);
            boolean durable = !anySleeper || ctx.saveNow();
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                Sleeper sleeper = sleepers.get(i);
                if (sleeper == null) {
                    results.add(delete(item.key(), item.bot(), reason, cfg));
                } else if (!durable) {
                    sleeper.undo();
                    results.add(Result.KEPT); // nothing was emptied: the bot is exactly as it was
                } else {
                    results.add(finishSleep(sleeper, reason, cfg));
                }
            }
        } finally {
            for (Item item : items) {
                removing.remove(item.bot().name.toLowerCase(Locale.ROOT));
            }
        }
        return results;
    }

    /** What a bot was before its sleep was recorded, so a failed sleep puts it back exactly. */
    private final class Sleeper {
        final StructureKey key;
        final BotRecord bot;
        final BotState state;
        final BotSnapshot snapshot;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final String dimension;

        Sleeper(StructureKey key, BotRecord bot) {
            this.key = key;
            this.bot = bot;
            this.state = bot.state;
            this.snapshot = bot.snapshot;
            this.x = bot.x;
            this.y = bot.y;
            this.z = bot.z;
            this.yaw = bot.yaw;
            this.dimension = bot.dimension;
        }

        void undo() {
            bot.state = state;
            bot.removing = false;
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
    private Sleeper prepareSleep(StructureKey key, BotRecord bot) {
        Sleeper before = new Sleeper(key, bot);
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

    /** Steps 2 and 3: the bot is emptied and removed (no drops, no death), then the mark is cleared. */
    private Result finishSleep(Sleeper s, Reason reason, InhabitantsConfig cfg) {
        BotRecord bot = s.bot;
        ctx.discard(bot.name);
        Boolean stillThere = ctx.online(bot.name);
        if (stillThere == null || stillThere) {
            // It could not be removed. The record must not keep saying "asleep with a saved copy" while the bot lives on.
            s.undo();
            return Result.KEPT;
        }
        bot.removing = false;
        ctx.store.markDirty();
        roster.untrackOne(bot);
        ctx.debug(cfg, "Inhabitant {} sleeps ({}): seen by a player, its state and place are kept", bot.name, reason);
        return Result.SLEPT;
    }

    private Result delete(StructureKey key, BotRecord bot, Reason reason, InhabitantsConfig cfg) {
        // Unseen: nothing about it is kept. It is emptied and removed first; the record goes only once it is really gone.
        ctx.discard(bot.name);
        Boolean stillThere = ctx.online(bot.name);
        if (stillThere == null || stillThere) {
            return Result.KEPT;
        }
        roster.untrackOne(bot);
        StructureRecord rec = ctx.store.find(key).orElse(null);
        if (rec != null) {
            rec.nextBotIndex = Math.max(rec.nextBotIndex, bot.index + 1);
            rec.bots.remove(bot);
            ctx.store.reindex(key);
            ctx.store.markDirty();
        }
        ctx.debug(cfg, "Inhabitant {} deleted ({}): no player ever saw it, nothing is kept and its slot is free again",
                bot.name, reason);
        return Result.DELETED;
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
        ctx.guard("forget-dead", () -> ctx.bots.forget(bot.name));
        StructureRecord rec = ctx.store.find(key).orElse(null);
        InhabitantsConfig cfg = ctx.config();
        if (cfg != null) {
            ctx.debug(cfg, "Inhabitant {} of {} died ({}); its slot is never refilled ({} of {} planned are dead)", bot.name, key, how,
                    rec == null ? 0 : rec.deadCount(), rec == null ? 0 : rec.plannedBots);
        }
    }

    // ------------------------------------------------------------------ after a crash

    /**
     * A sleep that was recorded but not finished (a crash between its steps): a bot that is still online and whose record
     * says DORMANT and "removing" is emptied and removed now, and the mark cleared; an offline one only loses the mark.
     */
    void finishInterrupted(InhabitantsConfig cfg) {
        int finished = 0;
        for (Map.Entry<StructureKey, StructureRecord> e : ctx.store.nonAbandoned()) {
            for (BotRecord bot : e.getValue().bots) {
                if (!bot.removing || bot.name == null) {
                    continue;
                }
                Boolean online = ctx.online(bot.name);
                if (online == null) {
                    continue; // cannot tell: try again next time
                }
                if (online && bot.state == BotState.DORMANT) {
                    removing.add(bot.name.toLowerCase(Locale.ROOT));
                    try {
                        ctx.discard(bot.name);
                    } finally {
                        removing.remove(bot.name.toLowerCase(Locale.ROOT));
                    }
                }
                bot.removing = false;
                ctx.store.markDirty();
                finished++;
            }
        }
        if (finished > 0) {
            ctx.info("Finished {} sleep(s) that a restart had interrupted (the bot was emptied and removed once more)", finished);
        }
    }
}
