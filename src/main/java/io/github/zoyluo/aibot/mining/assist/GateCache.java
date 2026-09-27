package io.github.zoyluo.aibot.mining.assist;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot cache of the assist gate verdict (mining-assist design 2.1: "per-bot results are cached for
 * 20 ticks"). Pure: the caller supplies the server tick and computes the verdict itself. A cached
 * verdict is served while {@code since <= now < since + TTL}; a tick that moved backwards (world
 * reload) never serves a stale entry.
 */
public final class GateCache {
    public static final int TTL_TICKS = 20;

    /** One cached verdict. {@code denyReason} is null when the gate was open. */
    public record Verdict(boolean enabled, String denyReason, long sinceTick) {
        boolean freshAt(long nowTick) {
            return nowTick >= sinceTick && nowTick - sinceTick < TTL_TICKS;
        }
    }

    private final Map<UUID, Verdict> verdicts = new ConcurrentHashMap<>();

    /** The verdict for {@code bot} if it is still fresh at {@code nowTick}, else null. */
    public Verdict fresh(UUID bot, long nowTick) {
        Verdict verdict = verdicts.get(bot);
        return verdict != null && verdict.freshAt(nowTick) ? verdict : null;
    }

    /** The last stored verdict whether or not it is still fresh (for change detection), or null. */
    public Verdict last(UUID bot) {
        return verdicts.get(bot);
    }

    public Verdict put(UUID bot, long nowTick, boolean enabled, String denyReason) {
        Verdict verdict = new Verdict(enabled, denyReason, nowTick);
        verdicts.put(bot, verdict);
        return verdict;
    }

    public void remove(UUID bot) {
        verdicts.remove(bot);
    }

    public void clear() {
        verdicts.clear();
    }

    public int size() {
        return verdicts.size();
    }
}
