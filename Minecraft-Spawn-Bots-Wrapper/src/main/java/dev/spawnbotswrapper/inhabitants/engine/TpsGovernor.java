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
 * measured tick time on a fixed interval, feeds it to {@link TickHealth} (which decides, with a baseline learned
 * from the server itself, sustained-excess timing and hysteresis, whether the server is GENUINELY degraded) and,
 * only while it is, sheds a batch of inhabitants farthest from the nearest real player first, then waits a full
 * interval before checking again so each round's effect is actually measured. A despawn here is permanent,
 * exactly like a death -- see {@link DormancyGovernor} for the separate, reversible mechanism. Ordinary busy
 * operation (this pack idles at 53-59 ms per tick) never sheds anything.
 * <p>
 * While degraded, {@link #blocksNewSpawns()} hard-blocks every new spawn (both freshly-discovered structures
 * and already-pending ones) rather than approximating a reduced numeric ceiling. A numeric ceiling has a hole
 * in it: the instant any live bot leaves the roster for an unrelated reason (a death, a dormancy cycle) a
 * sliver of capacity opens up, and exploration-driven structure discovery reclaims it before the next check
 * can react -- population never actually settles, it just leaks growth between checks. A flat boolean has no
 * such hole: nothing new can spawn at all until the state machine has recovered.
 * <p>
 * The shed batch escalates with {@link #consecutiveShedChecks}: the first check of a degraded spell sheds one base
 * batch, a second consecutive one sheds two, and so on, resetting when the server recovers. Between the exit and
 * the enter level (still degraded, no longer overloaded) nothing more is shed but spawns stay blocked.
 * Every state transition is logged at INFO with the numbers that caused it.
 */
final class TpsGovernor {
    private final EngineContext ctx;
    private final BotRoster roster;
    private final Retirer retirer;
    private final TpsGateway tps;
    private final TickHealth health = new TickHealth();
    private long nextCheckTick = PendingStructure.NEVER;
    private int consecutiveShedChecks;

    TpsGovernor(EngineContext ctx, BotRoster roster, Retirer retirer, TpsGateway tps) {
        this.ctx = ctx;
        this.roster = roster;
        this.retirer = retirer;
        this.tps = tps;
    }

    /**
     * True while the server is degraded: {@link PopulationDriver#capacityLeft} must return 0 unconditionally
     * for as long as this holds, not merely a reduced number -- see the class doc for why a number leaks and a
     * flag does not.
     */
    boolean blocksNewSpawns() {
        return health.degraded();
    }

    void tick(long now, InhabitantsConfig cfg) {
        InhabitantsConfig.TpsThrottle t = EngineContext.tpsThrottle(cfg);
        if (!t.enabled) {
            if (health.degraded()) {
                ctx.info("TPS governor switched off while degraded; spawning is unblocked");
            }
            health.clearState();
            consecutiveShedChecks = 0;
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
        TickHealth.Transition change = health.observe(now, avg, t);
        if (change != null) {
            logTransition(change);
            if (change.to() == TickHealth.State.NORMAL) {
                // New spawns unblocking cannot itself respawn anything -- only a fresh structure or a dormancy
                // restore ever creates a bot -- so releasing the moment the state machine says so is safe.
                consecutiveShedChecks = 0;
            }
        }
        if (health.degraded() && avg > health.enterLevel(t)) {
            consecutiveShedChecks++;
            shed(t, avg);
        }
        // Else: recovered/normal (nothing to do), or degraded but already below the enter level (hold the
        // spawn block and the escalation level; do not shed more for a load that has eased).
    }

    private void logTransition(TickHealth.Transition c) {
        if (c.to() == TickHealth.State.DEGRADED) {
            ctx.info("TPS governor: server DEGRADED at tick {} -- smoothed tick time {} ms stayed above the enter level "
                            + "{} ms for {} ticks (baseline {} ms); new spawns are blocked and inhabitants will be shed "
                            + "farthest-first until it recovers below {} ms",
                    c.tick(), ms(c.averageMillis()), ms(c.enterMillis()), c.heldTicks(), ms(c.baselineMillis()),
                    ms(c.exitMillis()));
        } else {
            ctx.info("TPS governor: server RECOVERED at tick {} -- smoothed tick time {} ms stayed at or below the exit "
                            + "level {} ms for {} ticks (baseline {} ms, enter level {} ms); new spawns are allowed again",
                    c.tick(), ms(c.averageMillis()), ms(c.exitMillis()), c.heldTicks(), ms(c.baselineMillis()),
                    ms(c.enterMillis()));
        }
    }

    private static String ms(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.1f", v);
    }

    private record Ranked(StructureKey key, BotRecord bot, double distance) {
    }

    private void shed(InhabitantsConfig.TpsThrottle t, double avgMillis) {
        List<Map.Entry<StructureKey, BotRecord>> online = roster.onlineEntries();
        int currentLive = online.size();
        int effectiveBatch = Math.max(1, t.despawnBatchSize) * consecutiveShedChecks;
        int toRemove = Math.min(currentLive, effectiveBatch);
        if (toRemove <= 0) {
            return;
        }
        List<Ranked> ranked = new ArrayList<>(online.size());
        for (Map.Entry<StructureKey, BotRecord> e : online) {
            ranked.add(new Ranked(e.getKey(), e.getValue(), ctx.distanceToNearestPlayer(e.getValue().name)));
        }
        // Farthest first; an unknown distance (-1, e.g. no real player online) sorts last, i.e. is kept
        // preferentially over one we know for certain is far away.
        ranked.sort((a, b) -> Double.compare(b.distance, a.distance));
        StringBuilder names = new StringBuilder();
        InhabitantsConfig cfg = ctx.config();
        int shed = 0;
        for (int i = 0; i < ranked.size() && shed < toRemove; i++) {
            Ranked r = ranked.get(i);
            // Shedding is not a death and takes nothing from the world: a bot a player has seen sleeps with its whole state
            // (it wakes when its structure is allocated again), one nobody saw is deleted and its slot is free for a fresh roll.
            Retirer.Result result = retirer.retire(r.key, r.bot, Retirer.Reason.TPS, cfg);
            if (result == Retirer.Result.KEPT) {
                continue;
            }
            names.append(shed == 0 ? "" : ", ").append(r.bot.name).append(result == Retirer.Result.SLEPT ? " (asleep)" : "");
            shed++;
        }
        ctx.info("TPS governor: shed {} of {} inhabitant(s) [{}] -- degraded, smoothed tick time {} ms is above the enter "
                        + "level {} ms (shed round {}); seen bots sleep, unseen ones are deleted, nothing drops", shed, currentLive, names, ms(avgMillis),
                ms(health.enterLevel(t)), consecutiveShedChecks);
    }
}
