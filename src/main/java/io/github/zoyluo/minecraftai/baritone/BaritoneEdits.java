package io.github.zoyluo.minecraftai.baritone;

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
 * Ledger of every block a bot's Baritone instance broke or placed, written by {@link ServerPlayerController} at the two places
 * where Baritone's execution touches the world: a completed break of {@code MiningController} and a placement of
 * {@code BuildAction}. Baritone has no other way to change a block (its {@code BlockBreakHelper}/{@code BlockPlaceHelper} call
 * only the player controller), so a world diff that is not covered by this ledger would mean an edit that bypassed the
 * mod's break/place primitives; the end-to-end GameTests compare exactly that.
 *
 * <p>Bounded (the newest {@value #CAPACITY} edits per bot) and dropped with the bot's instance. Server thread only for
 * writes; reads copy.</p>
 */
public final class BaritoneEdits {
    private static final int CAPACITY = 512;
    private static final Map<UUID, Deque<Edit>> EDITS = new ConcurrentHashMap<>();

    private BaritoneEdits() {
    }

    public enum Kind {
        /** A block was destroyed by {@code MiningController}. */
        BREAK,
        /** A block was put down by {@code BuildAction}. */
        PLACE
    }

    /**
     * @param block  registry id of the block that was destroyed (BREAK) or of the item that was placed (PLACE)
     * @param tool   registry id of the held item at the time
     * @param ticks  ticks the break took (0 for a placement)
     */
    public record Edit(Kind kind, BlockPos pos, String block, String tool, int ticks, int serverTick) {
    }

    static void record(AIPlayerEntity bot, Edit edit) {
        PolicyRefusalStreak.reset(bot.getUUID());
        Deque<Edit> deque = EDITS.computeIfAbsent(bot.getUUID(), id -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= CAPACITY) {
                deque.removeFirst();
            }
            deque.addLast(edit);
        }
    }

    /** The bot's edits, oldest first. */
    public static List<Edit> of(UUID botId) {
        Deque<Edit> deque = EDITS.get(botId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public static List<Edit> of(UUID botId, Kind kind) {
        return of(botId).stream().filter(edit -> edit.kind() == kind).toList();
    }

    /** Forgets the bot's edits (its instance was destroyed). */
    static void clear(UUID botId) {
        EDITS.remove(botId);
        BaritoneRefusals.clear(botId);
    }

    /** Test hook: forgets every bot's edits. */
    public static void clearAll() {
        EDITS.clear();
        BaritoneRefusals.clearAll();
    }
}
