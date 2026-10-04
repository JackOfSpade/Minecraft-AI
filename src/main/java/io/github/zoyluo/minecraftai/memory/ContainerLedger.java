package io.github.zoyluo.minecraftai.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * What one bot has personally seen inside storage containers: block id, item counts, free slots
 * and the game time it last looked. Fair by construction: an entry is written only by
 * {@code ContainerAction} when the bot really opened the container (in reach and line of sight)
 * and deposited, withdrew or inspected it, so nothing here can be learned from a distance. Keyed
 * by (dimension, canonical position) where a double chest's two halves share the key of the
 * lower-sorting half. The bot's memory owns one ledger and persists it with the rest of the
 * memory.
 */
public final class ContainerLedger {
    /** Hard cap on remembered containers; the least recently verified entry is evicted first. */
    public static final int MAX_ENTRIES = 96;
    /**
     * How long "no free slot when I last looked" is believed. A player empties chests by hand and the
     * bot cannot see that from a distance, so a full flag is a hint that fades: after this many
     * ticks (two game minutes) since the entry was last verified the container counts as
     * possibly-roomy again and is tried (and re-verified on opening) like any other.
     */
    public static final long FULL_TRUST_TICKS = 2400L;
    private static final int MAX_ITEM_KINDS = 64;
    /**
     * Legacy or user-edited saves can contain a much larger compound than the writer produces.
     * Inspect at most twice the persisted item budget so malformed leading keys cannot make load
     * time proportional to arbitrary NBT input, while ordinary persisted ledgers remain exact.
     */
    private static final int MAX_LOADED_ITEM_KEYS = MAX_ITEM_KINDS * 2;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** A remembered container. {@code items} maps item id to the total count seen. */
    public record Entry(String dimension,
                        BlockPos pos,
                        String block,
                        Map<String, Integer> items,
                        int freeSlots,
                        int totalSlots,
                        long lastVerified) {
        public Entry {
            pos = pos.immutable();
            items = Map.copyOf(items);
        }

        public int count(String itemId) {
            return items.getOrDefault(itemId, 0);
        }

        /** True when every slot is occupied (a stack of the same item might still merge; treat as full). */
        public boolean full() {
            return totalSlots > 0 && freeSlots <= 0;
        }

        /**
         * True when the container was full at {@code lastVerified} AND that is recent enough to still
         * be believed at game time {@code now}. Never an exclusion: callers demote such a container
         * (try it last) and re-verify by opening it.
         */
        public boolean knownFull(long now) {
            return full() && now - lastVerified <= FULL_TRUST_TICKS;
        }

        public int totalItems() {
            int total = 0;
            for (int count : items.values()) {
                if (count > 0 && total > Integer.MAX_VALUE - count) {
                    return Integer.MAX_VALUE;
                }
                total += count;
            }
            return total;
        }
    }

    public synchronized void record(Entry entry) {
        String key = key(entry.dimension(), entry.pos());
        entries.remove(key); // re-insert so insertion order follows recency
        entries.put(key, entry);
        while (entries.size() > MAX_ENTRIES) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
                if (candidate.getValue().lastVerified() < oldestTime) {
                    oldestTime = candidate.getValue().lastVerified();
                    oldest = candidate.getKey();
                }
            }
            entries.remove(oldest);
        }
    }

    public synchronized Optional<Entry> get(String dimension, BlockPos canonicalPos) {
        return Optional.ofNullable(entries.get(key(dimension, canonicalPos)));
    }

    public synchronized boolean forget(String dimension, BlockPos canonicalPos) {
        return entries.remove(key(dimension, canonicalPos)) != null;
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized List<Entry> inDimension(String dimension) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.dimension().equals(dimension)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * The best remembered containers for {@code itemId}: those holding at least {@code wanted}
     * first, then any holding some, nearest first within each group. Only containers that held the
     * item when the bot last looked; the caller re-verifies on arrival.
     */
    public synchronized List<Entry> find(String dimension, String itemId, BlockPos from, int wanted, int limit) {
        return find(dimension, itemId, from, wanted, limit, entry -> true);
    }

    /** {@link #find(String, String, BlockPos, int, int)} restricted to entries {@code accept} allows (applied before the limit). */
    public synchronized List<Entry> find(String dimension, String itemId, BlockPos from, int wanted, int limit,
                                         Predicate<Entry> accept) {
        int need = Math.max(1, wanted);
        List<Entry> hits = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.dimension().equals(dimension) && entry.count(itemId) > 0 && accept.test(entry)) {
                hits.add(entry);
            }
        }
        hits.sort(Comparator
                .comparing((Entry entry) -> entry.count(itemId) < need)
                .thenComparingDouble(entry -> entry.pos().distSqr(from))
                .thenComparingLong(entry -> -entry.lastVerified()));
        return hits.size() <= limit ? hits : new ArrayList<>(hits.subList(0, Math.max(0, limit)));
    }

    /**
     * Remembered containers that had a free slot when last seen, or whose "full" observation is old
     * enough to have faded (see {@link #FULL_TRUST_TICKS}), nearest first, restricted to entries
     * {@code accept} allows (applied before the limit). {@code now} is the current game time.
     */
    public synchronized List<Entry> withRoom(String dimension, BlockPos from, long now, int limit,
                                             Predicate<Entry> accept) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.dimension().equals(dimension) && !entry.knownFull(now) && accept.test(entry)) {
                result.add(entry);
            }
        }
        result.sort(Comparator.comparingDouble(entry -> entry.pos().distSqr(from)));
        return result.size() <= limit ? result : new ArrayList<>(result.subList(0, Math.max(0, limit)));
    }

    /** One short context line, e.g. {@code Known storage: chest@10,64,3 (oak_log x40, ..., 5 free, 12s ago)}; empty when unknown. */
    public synchronized String summary(String dimension, BlockPos from, long now, int limit) {
        List<Entry> near = inDimension(dimension);
        if (near.isEmpty() || limit <= 0) {
            return "";
        }
        near.sort(Comparator.comparingDouble(entry -> entry.pos().distSqr(from)));
        StringBuilder builder = new StringBuilder("Known storage (from what I last saw inside):");
        int shown = 0;
        for (Entry entry : near) {
            if (shown++ >= limit) {
                break;
            }
            builder.append("\n- ").append(describe(entry, now));
        }
        return builder.toString();
    }

    /** Up to {@code limit} short descriptions of the nearest remembered containers, or null when there are none. */
    public synchronized List<String> nearestLines(String dimension, BlockPos from, long now, int limit) {
        List<Entry> near = inDimension(dimension);
        if (near.isEmpty() || limit <= 0) {
            return null;
        }
        near.sort(Comparator.comparingDouble(entry -> entry.pos().distSqr(from)));
        List<String> lines = new ArrayList<>();
        for (Entry entry : near) {
            if (lines.size() >= limit) {
                break;
            }
            lines.add(describe(entry, -1L)); // no age: a changing age would churn the perception digest
        }
        return lines;
    }

    public static String describe(Entry entry, long now) {
        StringBuilder builder = new StringBuilder();
        String block = entry.block().contains(":") ? entry.block().substring(entry.block().indexOf(':') + 1) : entry.block();
        builder.append(block).append(" at ").append(entry.pos().getX()).append(",")
                .append(entry.pos().getY()).append(",").append(entry.pos().getZ()).append(": ");
        List<Map.Entry<String, Integer>> top = new ArrayList<>(entry.items().entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        if (top.isEmpty()) {
            builder.append("empty");
        } else {
            int shown = 0;
            for (Map.Entry<String, Integer> item : top) {
                if (shown++ >= 5) {
                    builder.append(", +").append(top.size() - 5).append(" more kinds");
                    break;
                }
                if (shown > 1) {
                    builder.append(", ");
                }
                String id = item.getKey();
                builder.append(id.startsWith("minecraft:") ? id.substring(10) : id).append(" x").append(item.getValue());
            }
        }
        builder.append("; ").append(entry.freeSlots()).append("/").append(entry.totalSlots()).append(" slots free");
        if (now >= 0L) {
            builder.append("; seen ")
                    .append(ageText(now - entry.lastVerified())).append(" ago");
        }
        return builder.toString();
    }

    static String ageText(long ticks) {
        long seconds = Math.max(0L, ticks) / 20L;
        if (seconds < 90L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        if (minutes < 90L) {
            return minutes + "min";
        }
        return (minutes / 60L) + "h";
    }

    public synchronized ListTag toNbt() {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", entry.dimension());
            tag.putInt("x", entry.pos().getX());
            tag.putInt("y", entry.pos().getY());
            tag.putInt("z", entry.pos().getZ());
            tag.putString("block", entry.block());
            tag.putInt("free", entry.freeSlots());
            tag.putInt("slots", entry.totalSlots());
            tag.putLong("verified", entry.lastVerified());
            CompoundTag items = new CompoundTag();
            int kinds = 0;
            for (Map.Entry<String, Integer> item : entry.items().entrySet()) {
                if (kinds++ >= MAX_ITEM_KINDS) {
                    break;
                }
                items.putInt(item.getKey(), item.getValue());
            }
            tag.put("items", items);
            list.add(tag);
        }
        return list;
    }

    public synchronized void load(ListTag list) {
        entries.clear();
        // toNbt writes the recency-ordered ledger.  When loading an oversized legacy or edited
        // list, inspect its recent tail only: it preserves useful current records and bounds
        // recovery work instead of walking every untrusted entry merely to evict it afterwards.
        int firstLoaded = Math.max(0, list.size() - MAX_ENTRIES);
        for (int index = firstLoaded; index < list.size(); index++) {
            CompoundTag tag = list.getCompoundOrEmpty(index);
            String dimension = tag.getStringOr("dimension", "");
            if (dimension.isBlank()) {
                continue;
            }
            Map<String, Integer> items = new LinkedHashMap<>();
            CompoundTag itemTag = tag.getCompoundOrEmpty("items");
            int inspectedKeys = 0;
            for (String id : itemTag.keySet()) {
                if (inspectedKeys++ >= MAX_LOADED_ITEM_KEYS) {
                    break;
                }
                if (items.size() >= MAX_ITEM_KINDS) {
                    break;
                }
                int count = itemTag.getIntOr(id, 0);
                if (!id.isBlank() && count > 0) {
                    items.put(id, count);
                }
            }
            Entry entry = new Entry(
                    dimension,
                    new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)),
                    tag.getStringOr("block", ""),
                    items,
                    Math.max(0, tag.getIntOr("free", 0)),
                    Math.max(0, tag.getIntOr("slots", 0)),
                    tag.getLongOr("verified", 0L));
            // Persisted NBT is user-editable and may come from an older, uncapped version. Route
            // it through the normal insertion path so loading cannot bypass the memory bound.
            record(entry);
        }
    }

    private static String key(String dimension, BlockPos pos) {
        return dimension.toLowerCase(Locale.ROOT) + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
