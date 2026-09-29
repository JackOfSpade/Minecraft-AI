package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static map of bot uuid to {@link MiningAssistState} (mining-assist design 2.4). A state is created
 * only when the assist gate opens for a bot ({@link #getOrCreate}), so bots the assist never senses for
 * cost nothing. Hooks that only queue work for an existing state use {@link #getIfPresent}.
 * Cleared per bot from {@code clearTransient} and wholesale on world unload.
 */
public final class MiningAssistRegistry {
    private static final Map<UUID, MiningAssistState> STATES = new ConcurrentHashMap<>();

    private static int activeCountTick = Integer.MIN_VALUE;
    private static int activeCount;

    private MiningAssistRegistry() {
    }

    /** The bot's state, or null when none was ever created (or it was cleared). */
    public static MiningAssistState getIfPresent(UUID botId) {
        return STATES.get(botId);
    }

    public static MiningAssistState getOrCreate(AIPlayerEntity bot) {
        return getOrCreate(bot.getUUID());
    }

    public static MiningAssistState getOrCreate(UUID botId) {
        return STATES.computeIfAbsent(botId, MiningAssistState::new);
    }

    /** Drops one bot's state and its dug-cells ring. */
    public static void clear(AIPlayerEntity bot) {
        clear(bot.getUUID());
    }

    public static void clear(UUID botId) {
        STATES.remove(botId);
        BotEdits.clearBot(botId);
    }

    /** World unload: drops every state. */
    public static void clearAll() {
        STATES.clear();
        activeCountTick = Integer.MIN_VALUE;
        activeCount = 0;
    }

    public static int size() {
        return STATES.size();
    }

    /** Live view of every state, for diagnostics and tests. */
    public static Collection<MiningAssistState> states() {
        return STATES.values();
    }

    /**
     * The number of bots sweeping around {@code nowTick} counting {@code self} (at least 1): those whose
     * last sweep step was this tick or the one before, plus {@code self} if it has not swept yet. The
     * base count is computed once per tick, so N bots cost O(N) per tick in total, not O(N squared).
     * The sensor uses it as the divisor of {@code sense.globalRaysPerTick} (design 3.4).
     */
    public static int activeSweepers(int nowTick, MiningAssistState self) {
        if (activeCountTick != nowTick) {
            int count = 0;
            for (MiningAssistState state : STATES.values()) {
                if (isSweeping(state, nowTick)) {
                    count++;
                }
            }
            activeCount = count;
            activeCountTick = nowTick;
        }
        int total = activeCount + (self != null && !isSweeping(self, nowTick) ? 1 : 0);
        return Math.max(1, total);
    }

    private static boolean isSweeping(MiningAssistState state, int nowTick) {
        int last = state.lastSweepTick();
        return last != MiningAssistState.NEVER && last <= nowTick && nowTick - last <= 1;
    }
}
