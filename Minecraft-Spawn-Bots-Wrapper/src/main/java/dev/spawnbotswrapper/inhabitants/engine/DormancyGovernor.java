package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * See the doc on {@link InhabitantsConfig.Dormancy}: periodically despawns inhabitants that have stayed far
 * from every real player for a while, remembering them exactly so {@link PopulationDriver#restoreDormant}
 * brings them back unchanged the next time their structure is near a real player again. Always on (unlike
 * {@link TpsGovernor}, which only reacts to server load) and always reversible.
 */
final class DormancyGovernor {
    private final EngineContext ctx;
    private final BotRoster roster;
    /** Lower-case bot name -> tick first seen beyond the dormancy distance, continuously. */
    private final Map<String, Long> farSince = new HashMap<>();
    private long nextScanTick = PendingStructure.NEVER;

    DormancyGovernor(EngineContext ctx, BotRoster roster) {
        this.ctx = ctx;
        this.roster = roster;
    }

    void tick(long now, InhabitantsConfig cfg) {
        InhabitantsConfig.Dormancy d = EngineContext.dormancy(cfg);
        if (!d.enabled) {
            farSince.clear();
            return;
        }
        if (nextScanTick != PendingStructure.NEVER && now < nextScanTick) {
            return;
        }
        nextScanTick = now + Math.max(1, d.scanIntervalTicks);
        scan(now, d, cfg);
    }

    private void scan(long now, InhabitantsConfig.Dormancy d, InhabitantsConfig cfg) {
        Set<String> stillOnline = new HashSet<>();
        for (Map.Entry<StructureKey, BotRecord> e : roster.onlineEntries()) {
            BotRecord bot = e.getValue();
            String key = EngineContext.lower(bot.name);
            stillOnline.add(key);
            double dist = ctx.distanceToNearestPlayer(bot.name);
            if (dist < 0 || dist < d.distanceBlocks) {
                farSince.remove(key);
                continue;
            }
            Long since = farSince.get(key);
            if (since == null) {
                farSince.put(key, now);
            } else if (now - since >= Math.max(0, d.delayTicks)) {
                farSince.remove(key);
                goDormant(bot, cfg);
            }
        }
        farSince.keySet().retainAll(stillOnline);
    }

    private void goDormant(BotRecord bot, InhabitantsConfig cfg) {
        ctx.discard(bot.name);
        bot.state = BotState.DORMANT;
        ctx.store.markDirty();
        roster.untrackOne(bot);
        ctx.debug(cfg, "Inhabitant {} went dormant: far from every real player", bot.name);
    }
}
