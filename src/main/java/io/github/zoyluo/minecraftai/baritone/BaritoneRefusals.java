package io.github.zoyluo.minecraftai.baritone;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * Ledger of everything the strict-survival rules ({@link BaritoneBreakPlacePolicy}) refused a bot's Baritone instance: a break,
 * a placement, an item use, an inventory move, the start of a scanning process, a goal. The counterpart of
 * {@link BaritoneEdits}: that one records what happened to the world, this one what was stopped from happening. Every refusal is
 * also a {@code baritone_refused} line in the bot's log; the log line of a refusal that repeats (Baritone retries a click every
 * few ticks) is throttled, the ledger keeps counting.
 *
 * <p>Bounded (the newest {@value #CAPACITY} per bot) and dropped with the bot's edits.</p>
 */
public final class BaritoneRefusals {
    private static final int CAPACITY = 256;
    /** The same refusal is logged again only after this many server ticks. */
    private static final int LOG_INTERVAL_TICKS = 100;
    private static final Map<UUID, Deque<Refusal>> REFUSALS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LAST_LOGGED = new ConcurrentHashMap<>();

    private BaritoneRefusals() {
    }

    /** What kind of action was refused. */
    public enum Op {
        BREAK,
        PLACE,
        USE_ITEM,
        INVENTORY,
        SCAN_PROCESS,
        GOAL
    }

    /**
     * @param pos    the block concerned, or null
     * @param reason a short machine-readable reason ({@code protected_block}, {@code block_entity}, {@code not_observable},
     *               {@code support_face_not_visible}, {@code item_not_allowed}, ...)
     * @param detail the block, item, process or goal concerned
     */
    public record Refusal(Op op, BlockPos pos, String reason, String detail, int serverTick) {
    }

    static void record(AIPlayerEntity bot, Op op, BlockPos pos, String reason, String detail) {
        if (op == Op.BREAK || op == Op.PLACE) {
            PolicyRefusalStreak.refused(bot.getUUID());
        }
        int tick = bot.getServer() == null ? 0 : bot.getServer().getTickCount();
        Deque<Refusal> deque = REFUSALS.computeIfAbsent(bot.getUUID(), id -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= CAPACITY) {
                deque.removeFirst();
            }
            deque.addLast(new Refusal(op, pos == null ? null : pos.immutable(), reason, detail, tick));
        }
        String key = bot.getUUID() + "|" + op + "|" + reason + "|" + detail + "|" + (pos == null ? "-" : pos.asLong());
        Integer last = LAST_LOGGED.get(key);
        if (last == null || tick < last || tick - last >= LOG_INTERVAL_TICKS) {
            if (LAST_LOGGED.size() > 4096) {
                LAST_LOGGED.clear();
            }
            LAST_LOGGED.put(key, tick);
            BotLog.action(bot, "baritone_refused", "op", op.name().toLowerCase(java.util.Locale.ROOT),
                    "reason", reason, "detail", detail, "pos", pos == null ? "-" : LogFields.pos(pos));
        }
    }

    /** The bot's refusals, oldest first. */
    public static List<Refusal> of(UUID botId) {
        Deque<Refusal> deque = REFUSALS.get(botId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public static List<Refusal> of(UUID botId, Op op) {
        return of(botId).stream().filter(refusal -> refusal.op() == op).toList();
    }

    static void clear(UUID botId) {
        REFUSALS.remove(botId);
        PolicyRefusalStreak.reset(botId);
        String prefix = botId + "|";
        LAST_LOGGED.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Test hook: forgets every bot's refusals. */
    public static void clearAll() {
        REFUSALS.clear();
        PolicyRefusalStreak.clearAll();
        LAST_LOGGED.clear();
    }
}
