package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;

import dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import dev.spawnbotswrapper.inhabitants.util.StableHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Re-requesting DORMANT bots at their exact remembered position and re-applying their exact remembered profile
 * (never regenerated) -- {@link PopulationDriver}'s wrapperA-r4 extraction of that one self-contained cluster.
 * <p>
 * Deliberately outside the {@link PendingStructure} / write-ahead machinery a fresh roll needs: nothing here is
 * durable mid-flight, because a restore is safely retriable (the next time this structure's chunk loads) rather
 * than something that must never happen twice, so a crash mid-restore just leaves the bot DORMANT for another
 * attempt.
 */
final class DormancyRestorer {
    /** Sub-stream of a bot's seed used to place it again when it cannot wake where it went to sleep. */
    private static final long WAKE_STREAM = 0x3A4EL;
    private final EngineContext ctx;
    private final BotRoster roster;
    /** The fresh-spawn in-flight map, read-only here: a bot in it is never also restored as dormant. */
    private final Map<String, SpawnJob> freshInFlight;
    private final Map<String, SpawnJob> dormantInFlight = new LinkedHashMap<>();

    DormancyRestorer(EngineContext ctx, BotRoster roster, Map<String, SpawnJob> freshInFlight) {
        this.ctx = ctx;
        this.roster = roster;
        this.freshInFlight = freshInFlight;
    }

    /** Wakes in flight (they use live slots until they are up). */
    int inFlightCount() {
        return dormantInFlight.size();
    }

    /** Wakes in flight for one structure. */
    int inFlightFor(StructureKey key) {
        int n = 0;
        for (SpawnJob j : dormantInFlight.values()) {
            if (j.structure().equals(key)) {
                n++;
            }
        }
        return n;
    }

    /** Drops everything in-flight for a forgotten structure (admin reset). */
    void forget(StructureKey key) {
        dormantInFlight.values().removeIf(j -> j.structure().equals(key));
    }


    /** True when this structure has a bot asleep that may be woken now. */
    static boolean hasSleepers(StructureRecord rec) {
        for (BotRecord b : rec.bots) {
            if (b.state == BotState.DORMANT && b.name != null && !b.removing) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wakes up to {@code limit} sleeping bots of this structure, SEEN ones first (they are the ones a player is waiting
     * for), each at its saved position when a bot can stand there, otherwise placed again by the structure's own spawn
     * logic (needs {@code snapshot}; without one the bot keeps sleeping until the structure is detected again). The bot
     * keeps its name, its identity and its saved state (the wake writes the snapshot onto the fresh, empty fake player;
     * nothing is dressed from the profile). Deliberately outside the {@link PendingStructure} / write-ahead machinery a
     * fresh roll needs: nothing here is durable mid-flight, because a wake is safely retriable rather than something that
     * must never happen twice, so a crash mid-wake just leaves the bot DORMANT for another attempt.
     *
     * @return how many wakes were started
     */
    int restoreDormant(StructureKey key, StructureRecord rec, long now, int limit, StructureSnapshot snapshot) {
        List<BotRecord> sleepers = new ArrayList<>();
        for (BotRecord b : rec.bots) {
            if (b.state == BotState.DORMANT && b.name != null && !b.removing) {
                sleepers.add(b);
            }
        }
        sleepers.sort(Comparator.comparing((BotRecord b) -> !b.seen).thenComparingInt(b -> b.index));
        int started = 0;
        for (BotRecord b : sleepers) {
            if (started >= limit) {
                break;
            }
            String lower = EngineContext.lower(b.name);
            if (dormantInFlight.containsKey(lower) || freshInFlight.containsKey(lower)) {
                continue;
            }
            String dimension = b.dimension != null ? b.dimension : key.dimension();
            SpawnPlanner.Position at = wakePosition(key, dimension, b, snapshot);
            if (at == null) {
                continue; // no safe place known yet: it keeps sleeping and is tried again on the next pass
            }
            BotGateway.SpawnHandle handle;
            try {
                handle = ctx.bots.requestSpawn(new BotGateway.SpawnRequest(dimension, b.name, at.x(), at.y(), at.z(), at.yaw()));
                if (handle == null) {
                    throw new IllegalStateException("the bot gateway returned no spawn handle");
                }
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                ctx.log.error("restoreDormant", b.name, t);
                continue; // stays DORMANT; retried on the next pass
            }
            b.x = at.x();
            b.y = at.y();
            b.z = at.z();
            b.yaw = at.yaw();
            dormantInFlight.put(lower, new SpawnJob(key, b.index, b.name, handle, now));
            started++;
        }
        return started;
    }

    /**
     * Where a sleeper wakes: its saved position when a bot can stand there ({@code standing} says SAFE, or cannot tell);
     * otherwise a fresh position from the structure's own spawn logic; null when there is none to be had right now.
     */
    private SpawnPlanner.Position wakePosition(StructureKey key, String dimension, BotRecord b, StructureSnapshot snapshot) {
        SpawnPlanner.Position saved = new SpawnPlanner.Position(b.x, b.y, b.z, b.yaw);
        SpawnSafety.Verdict verdict;
        try {
            verdict = ctx.world.standing(dimension, b.x, b.y, b.z);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("standing", b.name, t);
            verdict = SpawnSafety.Verdict.UNKNOWN;
        }
        if (verdict == null || verdict != SpawnSafety.Verdict.UNSAFE) {
            return saved;
        }
        if (snapshot == null || !dimension.equals(key.dimension())) {
            return null;
        }
        try {
            var probe = ctx.world.probe(dimension);
            if (probe == null) {
                return null;
            }
            SpawnPlanner.PositionResult found = ctx.planner.findPositions(snapshot, probe, 1, List.of(),
                    new SplitMix64(StableHash.combine(b.seed, WAKE_STREAM)));
            if (found == null || found.positions().isEmpty()) {
                return null;
            }
            ctx.info("Inhabitant {} cannot stand where it went to sleep any more; it wakes at a fresh spot of {}", b.name, key);
            return found.positions().get(0);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("wakePosition", b.name, t);
            return null;
        }
    }

    /** Polls every in-flight dormancy restore once. Runs every tick regardless of the settle period, like {@link PopulationDriver#poll}. */
    void pollDormant(long now, InhabitantsConfig cfg) {
        if (dormantInFlight.isEmpty()) {
            return;
        }
        for (SpawnJob job : new ArrayList<>(dormantInFlight.values())) {
            ctx.guard("pollDormant", () -> pollDormantOne(job, now, cfg));
        }
    }

    private void pollDormantOne(SpawnJob job, long now, InhabitantsConfig cfg) {
        int appearTimeoutTicks = EngineContext.processing(cfg).appearTimeoutTicks;
        SpawnPollClassifier.Outcome outcome =
                SpawnPollClassifier.classify(ctx, job, now, appearTimeoutTicks, "pollDormant", false);
        if (outcome instanceof SpawnPollClassifier.Ready ready) {
            dormantInFlight.remove(EngineContext.lower(job.name()));
            finishDormantRestore(job, ready.uuid(), cfg);
        } else if (outcome instanceof SpawnPollClassifier.Failed) {
            dormantInFlight.remove(EngineContext.lower(job.name())); // stays DORMANT; retried later
        } else if (outcome instanceof SpawnPollClassifier.TimedOut) {
            dormantInFlight.remove(EngineContext.lower(job.name())); // stays DORMANT; retried later
        }
        // StillPending: try again next tick.
    }

    private void finishDormantRestore(SpawnJob job, UUID uuid, InhabitantsConfig cfg) {
        StructureRecord rec = ctx.store.find(job.structure()).orElse(null);
        if (rec == null) {
            return;
        }
        BotRecord bot = null;
        for (BotRecord b : rec.bots) {
            if (b.index == job.botIndex() && job.name().equalsIgnoreCase(b.name)) {
                bot = b;
                break;
            }
        }
        if (bot == null || bot.state != BotState.DORMANT) {
            return; // reset or otherwise no longer ours: leave whatever exists alone
        }
        try {
            BotSnapshot snapshot = bot.snapshot;
            if (snapshot != null && snapshot.usable()) {
                // Exactly what it had, slot by slot; nothing is dressed from the profile (that would refill the quiver,
                // repair the gear and heal the bot).
                BotGateway.ApplyResult woke = ctx.bots.wake(bot.name, bot.profile, snapshot);
                if (woke != null && !woke.allApplied()) {
                    ctx.log.warn("wake-partial", "Inhabitant " + bot.name + " woke only partly (loadout " + woke.loadoutApplied()
                            + ", vitals " + woke.vitalsApplied() + ", behaviour " + woke.behaviorApplied() + "): " + woke.warnings());
                }
            } else {
                // A record from before snapshots existed (or a bot whose state could never be read): the profile is all
                // there is. Logged once per bot, because the snapshot taken below makes the next wake exact.
                ctx.info("Inhabitant {} has no saved state (its record predates them); it is dressed from its profile now "
                        + "and its state is saved from here on", bot.name);
                ctx.bots.applyProfile(bot.name, bot.profile);
                BotSnapshot fresh = ctx.snapshot(bot.name);
                if (fresh != null) {
                    bot.snapshot = fresh;
                }
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error("applyProfile", bot.name, t);
        }
        if (uuid != null) {
            bot.uuid = uuid.toString();
        }
        bot.state = BotState.SPAWNED;
        ctx.store.markDirty();
        roster.trackSpawned(job.structure(), bot);
        ctx.debug(cfg, "Inhabitant {} restored from dormancy in {}", bot.name, job.structure());
    }
}
