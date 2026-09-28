package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reactive safety valve for server load; see the doc on {@link InhabitantsConfig.TpsThrottle}. Checks the
 * measured tick rate on a fixed interval and, while it is degraded, sheds a batch of inhabitants farthest from
 * the nearest real player first, then waits a full interval before checking again so each round's effect is
 * actually measured. A despawn here is permanent, exactly like a death -- see {@link DormancyGovernor} for the
 * separate, reversible mechanism.
 * <p>
 * While degraded, {@link #blocksNewSpawns()} hard-blocks every new spawn (both freshly-discovered structures
 * and already-pending ones) rather than approximating a reduced numeric ceiling. A numeric ceiling has a hole
 * in it: the instant any live bot leaves the roster for an unrelated reason (a death, a dormancy cycle) a
 * sliver of capacity opens up, and exploration-driven structure discovery reclaims it before the next check
 * can react -- population never actually settles, it just leaks growth between checks. A flat boolean has no
 * such hole: nothing new can spawn at all until a check finds the server healthy again.
 * <p>
 * The shed batch escalates with {@link #consecutiveDegradedChecks}: a single bad check sheds one base batch, a
 * second consecutive bad check sheds two, and so on, resetting the moment the server is healthy again. A single
 * transient blip is still handled gently; a sustained, genuine overload gets a rapidly-growing response instead
 * of nibbling at a fixed rate indefinitely.
 */
final class TpsGovernor {
    private final EngineContext ctx;
    private final BotRoster roster;
    private final TpsGateway tps;
    private long nextCheckTick = PendingStructure.NEVER;
    private boolean degraded;
    private int consecutiveDegradedChecks;

    TpsGovernor(EngineContext ctx, BotRoster roster, TpsGateway tps) {
        this.ctx = ctx;
        this.roster = roster;
        this.tps = tps;
    }

    /**
     * True while the server is degraded: {@link PopulationDriver#capacityLeft} must return 0 unconditionally
     * for as long as this holds, not merely a reduced number -- see the class doc for why a number leaks and a
     * flag does not.
     */
    boolean blocksNewSpawns() {
        return degraded;
    }

    void tick(long now, InhabitantsConfig cfg) {
        InhabitantsConfig.TpsThrottle t = EngineContext.tpsThrottle(cfg);
        if (!t.enabled) {
            degraded = false;
            consecutiveDegradedChecks = 0;
            return;
        }
        if (nextCheckTick != PendingStructure.NEVER && now < nextCheckTick) {
            return;
        }
        nextCheckTick = now + Math.max(1, t.checkIntervalTicks);
        double avg = tps.averageTickMillis();
        if (avg < 0) {
            return; // not enough samples yet
        }
        if (avg > t.degradedMillis) {
            consecutiveDegradedChecks++;
            degraded = true;
            shed(t, cfg, avg);
        } else if (avg <= t.healthyMillis) {
            // New spawns unblocking cannot itself respawn anything -- only a fresh structure or a dormancy
            // restore ever creates a bot -- so releasing the instant the server is healthy is safe.
            degraded = false;
            consecutiveDegradedChecks = 0;
        }
        // Else: the dead zone between healthyMillis and degradedMillis. Hold both the current block and the
        // current escalation level steady; react to neither extreme so a server hovering at the boundary
        // does not flip-flop every check.
    }

    private record Ranked(BotRecord bot, double distance) {
    }

    private void shed(InhabitantsConfig.TpsThrottle t, InhabitantsConfig cfg, double avgMillis) {
        List<Map.Entry<StructureKey, BotRecord>> online = roster.onlineEntries();
        int currentLive = online.size();
        int effectiveBatch = Math.max(1, t.despawnBatchSize) * consecutiveDegradedChecks;
        int toRemove = Math.min(currentLive, effectiveBatch);
        if (toRemove <= 0) {
            return;
        }
        List<Ranked> ranked = new ArrayList<>(online.size());
        for (Map.Entry<StructureKey, BotRecord> e : online) {
            ranked.add(new Ranked(e.getValue(), ctx.distanceToNearestPlayer(e.getValue().name)));
        }
        // Farthest first; an unknown distance (-1, e.g. no real player online) sorts last, i.e. is kept
        // preferentially over one we know for certain is far away.
        ranked.sort((a, b) -> Double.compare(b.distance, a.distance));
        for (int i = 0; i < toRemove; i++) {
            BotRecord bot = ranked.get(i).bot;
            ctx.discard(bot.name);
            roster.untrackOne(bot);
            ctx.debug(cfg, "Inhabitant {} despawned: server tick rate degraded ({}ms/tick avg, {} consecutive bad check(s))",
                    bot.name, String.format(Locale.ROOT, "%.1f", avgMillis), consecutiveDegradedChecks);
        }
    }
}
