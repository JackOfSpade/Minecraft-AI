package dev.spawnbotswrapper.inhabitants.engine;

import java.util.UUID;

/**
 * wrapperA-r5: the poll/classify/timeout shape shared by {@link PopulationDriver}'s fresh-spawn polling
 * ({@code pollOne}) and {@link DormancyRestorer}'s dormancy-restore polling ({@code pollDormantOne}). Both
 * wrap {@code ctx.bots.poll(job.handle())} in an identical try/catch-log-and-treat-as-pending, then classify
 * the result the same way; only what each caller then DOES with that classification differs (a fresh spawn is
 * permanently failed with a persisted reason; a dormancy restore just drops back to DORMANT for a later
 * retry, silently), so only the shared classification moves here, not the differing reaction to it.
 */
final class SpawnPollClassifier {
    private SpawnPollClassifier() {
    }

    /** What happened to one in-flight job on one poll. */
    sealed interface Outcome permits Ready, Failed, TimedOut, StillPending {
    }

    record Ready(UUID uuid) implements Outcome {
    }

    record Failed(String reason) implements Outcome {
    }

    record TimedOut() implements Outcome {
    }

    /** Not ready, not failed, not timed out yet: no bookkeeping change, retried next tick. */
    record StillPending() implements Outcome {
    }

    /**
     * @param errorTag                 the {@link ThrottledLog} site name to log an unexpected poll failure under
     * @param timeoutAppliesOnPollError whether an in-flight job can still time out on a tick where {@code
     *                                  ctx.bots.poll} itself threw (fresh spawns do; dormancy restores do not --
     *                                  this is the one place the two callers' original behaviour genuinely
     *                                  differed, so it stays a caller choice rather than being unified away)
     */
    static Outcome classify(EngineContext ctx, SpawnJob job, long now, int appearTimeoutTicks, String errorTag,
                            boolean timeoutAppliesOnPollError) {
        BotGateway.SpawnPoll result;
        try {
            result = ctx.bots.poll(job.handle());
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            ctx.log.error(errorTag, job.name(), t);
            if (!timeoutAppliesOnPollError) {
                return new StillPending();
            }
            result = new BotGateway.SpawnPoll.Pending(); // fall through to the same timeout check as a real Pending()
        }
        if (result instanceof BotGateway.SpawnPoll.Ready ready) {
            return new Ready(ready.uuid());
        }
        if (result instanceof BotGateway.SpawnPoll.Failed failed) {
            return new Failed(failed.reason());
        }
        if (now - job.startedAtTick() >= appearTimeoutTicks) {
            return new TimedOut();
        }
        return new StillPending();
    }
}
