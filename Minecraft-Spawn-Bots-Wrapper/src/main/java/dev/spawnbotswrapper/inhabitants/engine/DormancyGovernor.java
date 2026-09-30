package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The pre-allocation distance rule, kept as the FALLBACK: see the doc on {@link InhabitantsConfig.Dormancy}. It runs only
 * while the nearest-first {@link AllocationGovernor} is not (switched off with {@code allocation.enabled}, or no real player
 * is known), because the allocation replaces it: its relevance area (the players' simulation distance plus a margin, at
 * most {@code dormancy.distanceBlocks}) does the same job structure by structure, without the two fighting over the same
 * bots.
 * <p>
 * Periodically removes inhabitants that have stayed far from every real player for a while, through {@link Retirer}: a bot
 * a player has SEEN goes to sleep with its whole state and wakes when its structure is near a real player again, an unseen
 * one is deleted (its slot is vacant). Never a death, never a drop.
 */
final class DormancyGovernor {
    private final EngineContext ctx;
    private final BotRoster roster;
    private final Retirer retirer;
    /** Lower-case bot name -> tick first seen beyond the dormancy distance, continuously. */
    private final Map<String, Long> farSince = new HashMap<>();
    private long nextScanTick = PendingStructure.NEVER;

    DormancyGovernor(EngineContext ctx, BotRoster roster, Retirer retirer) {
        this.ctx = ctx;
        this.roster = roster;
        this.retirer = retirer;
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
                retirer.retire(e.getKey(), bot, Retirer.Reason.DORMANCY, cfg);
            }
        }
        farSince.keySet().retainAll(stillOnline);
    }
}
