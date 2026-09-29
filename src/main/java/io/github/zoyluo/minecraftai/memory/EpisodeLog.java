package io.github.zoyluo.minecraftai.memory;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * Episodic memory (layer 2 of the three-layer memory model): per-bot event timeline —
 * "what happened, when, and where".
 * Short-term (cleared on restart, a 256-entry ring buffer); what persists is the distilled
 * output (see {@link KnowledgeBase}: episodes are the ore, knowledge is the smelted ingot).
 * Every call to record also triggers rule-based distillation (deterministic, zero LLM cost).
 */
public final class EpisodeLog {
    public static final EpisodeLog INSTANCE = new EpisodeLog();
    private static final int CAP = 256;

    public enum Type { DEATH, THREAT, RESOURCE_FOUND, GOAL_DONE, GOAL_FAILED }

    public record EpisodeEvent(long gameTick, Type type, BlockPos pos, String detail) {
    }

    private final Map<UUID, Deque<EpisodeEvent>> events = new ConcurrentHashMap<>();

    private EpisodeLog() {
    }

    public void record(AIPlayerEntity bot, Type type, BlockPos pos, String detail) {
        Deque<EpisodeEvent> deque = events.computeIfAbsent(bot.getUUID(), k -> new ArrayDeque<>());
        EpisodeEvent event = new EpisodeEvent(bot.level().getServer().getTickCount(), type,
                pos.immutable(), detail == null ? "" : detail);
        synchronized (deque) {
            deque.addLast(event);
            while (deque.size() > CAP) {
                deque.pollFirst();
            }
        }
        // Distillation hook: episodes flow in -> semantic knowledge is deposited
        // (death clustering -> danger zones / resource found -> resource points / failure -> lessons).
        KnowledgeBase.INSTANCE.distill(bot, event, snapshot(bot.getUUID()));
    }

    /** Test isolation: clears this bot's episode stream (suite scenarios cross-contaminate:
     * a leftover RESOURCE_FOUND lets distillation dedup block the next scenario's warm-up point). */
    public void clearFor(UUID botId) {
        events.remove(botId);
    }

    public void clearAll() {
        events.clear();
    }

    /** The most recent n entries (newest to oldest). */
    public List<EpisodeEvent> recent(UUID botId, int n) {
        List<EpisodeEvent> all = snapshot(botId);
        int from = Math.max(0, all.size() - n);
        List<EpisodeEvent> out = new ArrayList<>(all.subList(from, all.size()));
        java.util.Collections.reverse(out);
        return out;
    }

    public List<EpisodeEvent> recentOfType(UUID botId, Type type, int n) {
        List<EpisodeEvent> out = new ArrayList<>();
        for (EpisodeEvent e : recent(botId, CAP)) {
            if (e.type() == type) {
                out.add(e);
                if (out.size() >= n) {
                    break;
                }
            }
        }
        return out;
    }

    private List<EpisodeEvent> snapshot(UUID botId) {
        Deque<EpisodeEvent> deque = events.get(botId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }
}
