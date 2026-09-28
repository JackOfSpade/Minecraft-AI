package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;

import java.util.ArrayList;
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

    /** Drops everything in-flight for a forgotten structure (admin reset). */
    void forget(StructureKey key) {
        dormantInFlight.values().removeIf(j -> j.structure().equals(key));
    }

    /**
     * Re-requests every DORMANT bot of this structure at its exact remembered position; on success its exact
     * remembered profile is re-applied (never regenerated). Deliberately outside the {@link PendingStructure} /
     * write-ahead machinery that a fresh roll needs: nothing here is durable mid-flight, because a restore is
     * safely retriable (the next time this structure's chunk loads) rather than something that must never
     * happen twice, so a crash mid-restore just leaves the bot DORMANT for another attempt.
     */
    void restoreDormant(StructureKey key, StructureRecord rec, long now) {
        for (BotRecord b : rec.bots) {
            if (b.state != BotState.DORMANT || b.name == null) {
                continue;
            }
            String lower = EngineContext.lower(b.name);
            if (dormantInFlight.containsKey(lower) || freshInFlight.containsKey(lower)) {
                continue;
            }
            BotGateway.SpawnHandle handle;
            try {
                handle = ctx.bots.requestSpawn(new BotGateway.SpawnRequest(key.dimension(), b.name, b.x, b.y, b.z, b.yaw));
                if (handle == null) {
                    throw new IllegalStateException("the bot gateway returned no spawn handle");
                }
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                ctx.log.error("restoreDormant", b.name, t);
                continue; // stays DORMANT; retried the next time this structure's chunk loads
            }
            dormantInFlight.put(lower, new SpawnJob(key, b.index, b.name, handle, now));
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
            ctx.bots.applyProfile(bot.name, bot.profile);
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
