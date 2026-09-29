package io.github.zoyluo.minecraftai.mining.assist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;

/**
 * What the bot itself changed in the world, kept so its own residue (torches, cobble, dug tunnels)
 * is not mistaken for a structure by point-of-interest scoring. Pure data plus a JSON codec; there
 * is no I/O here, the {@code BotEdits} adapter reads and writes the sidecar file.
 *
 * <p>Two stores with different lifetimes:</p>
 * <ul>
 *   <li><b>Placed ledger</b> (persisted): per dimension key, an LRU of packed {@link BlockPos}
 *       cells, at most {@link #PLACED_CAP_PER_DIMENSION} each. Recency is the order of
 *       {@code notePlaced} calls; {@code wasPlaced} is a pure query so sensing never dirties the
 *       ledger. Serialised as {@code {"version":1,"dimensions":{"<dim>":[long,...]}}}, oldest cell
 *       first, so the eviction order survives a round trip.</li>
 *   <li><b>Dug ring</b> (runtime only): per bot key, the last {@link #DUG_CAP_PER_BOT} cells the bot
 *       mined. Never serialised and never marks the ledger dirty.</li>
 * </ul>
 *
 * <p>Loading fails open: anything unreadable yields an empty ledger with
 * {@link #loadedCleanly()} {@code false}. Server-thread only, not thread-safe: capture
 * {@link #toJson()} on the server thread and hand the immutable string to the writer.</p>
 */
public final class BotEditsLedger {
    /** Schema version written to and required from the sidecar file. */
    public static final int VERSION = 1;
    /** Placed-cell LRU capacity per dimension key. */
    public static final int PLACED_CAP_PER_DIMENSION = 8192;
    /** Dug-cell ring capacity per bot. */
    public static final int DUG_CAP_PER_BOT = 8192;
    /** Minimum ticks between two sidecar snapshots of a dirty ledger. */
    public static final int SNAPSHOT_MIN_INTERVAL_TICKS = 1200;
    /** File name under {@code config/minecraftai/}. */
    public static final String SIDECAR_FILE_NAME = "mining_assist_edits.json";

    private final Map<String, CellLru> placed = new HashMap<>();
    private final Map<Long, CellLru> dug = new HashMap<>();
    private boolean dirty;
    private final String loadProblem;

    /** An empty ledger that counts as cleanly loaded (the "no sidecar file yet" case). */
    public BotEditsLedger() {
        this("");
    }

    private BotEditsLedger(String loadProblem) {
        this.loadProblem = loadProblem;
    }

    // ---- placed ledger -------------------------------------------------------------------------

    /** Records that the bot placed a block at {@code pos}; the cell becomes the newest entry. */
    public void notePlaced(String dimensionKey, BlockPos pos) {
        if (pos != null) {
            notePlaced(dimensionKey, pos.asLong());
        }
    }

    /** As {@link #notePlaced(String, BlockPos)} for an already packed cell. Blank keys are ignored. */
    public void notePlaced(String dimensionKey, long packedPos) {
        if (dimensionKey == null || dimensionKey.isBlank()) {
            return;
        }
        CellLru lru = placed.computeIfAbsent(dimensionKey, key -> new CellLru(PLACED_CAP_PER_DIMENSION));
        if (lru.touch(packedPos)) {
            dirty = true;
        }
    }

    /** True if the bot placed a block at {@code pos} in that dimension and it has not aged out. */
    public boolean wasPlaced(String dimensionKey, BlockPos pos) {
        return pos != null && wasPlaced(dimensionKey, pos.asLong());
    }

    /** As {@link #wasPlaced(String, BlockPos)} for a packed cell. Does not refresh recency. */
    public boolean wasPlaced(String dimensionKey, long packedPos) {
        if (dimensionKey == null) {
            return false;
        }
        CellLru lru = placed.get(dimensionKey);
        return lru != null && lru.contains(packedPos);
    }

    /** Number of placed cells currently held for the dimension. */
    public int size(String dimensionKey) {
        CellLru lru = dimensionKey == null ? null : placed.get(dimensionKey);
        return lru == null ? 0 : lru.size();
    }

    /** True if the placed ledger changed since construction, load or the last {@link #markClean()}. */
    public boolean isDirty() {
        return dirty;
    }

    /** Call on the server thread right after capturing {@link #toJson()}. */
    public void markClean() {
        dirty = false;
    }

    /**
     * True when the ledger is dirty and at least {@link #SNAPSHOT_MIN_INTERVAL_TICKS} server ticks
     * have passed since {@code lastSnapshotTick}.
     *
     * <p>Two robustness rules keep a stale or sentinel {@code lastSnapshotTick} from silencing the
     * sidecar: a tick counter that went backwards (a new world after an old one in the same JVM)
     * counts as due, and a difference that wraps around {@code long} (a "never snapshotted"
     * sentinel such as {@link Long#MIN_VALUE}) also counts as due.</p>
     */
    public boolean snapshotDue(long nowTick, long lastSnapshotTick) {
        if (!dirty) {
            return false;
        }
        long elapsed = nowTick - lastSnapshotTick;
        return elapsed < 0 || elapsed >= SNAPSHOT_MIN_INTERVAL_TICKS;
    }

    /** False when {@link #fromJson(String)} had to discard its input. */
    public boolean loadedCleanly() {
        return loadProblem.isEmpty();
    }

    /** Short machine-readable reason the load failed, empty when {@link #loadedCleanly()}. */
    public String loadProblem() {
        return loadProblem;
    }

    // ---- dug ring (runtime only) ---------------------------------------------------------------

    /** Records that the bot mined {@code pos}. */
    public void noteDug(long botKey, BlockPos pos) {
        if (pos != null) {
            noteDug(botKey, pos.asLong());
        }
    }

    /** As {@link #noteDug(long, BlockPos)} for a packed cell. */
    public void noteDug(long botKey, long packedPos) {
        dug.computeIfAbsent(botKey, key -> new CellLru(DUG_CAP_PER_BOT)).touch(packedPos);
    }

    /** True if this bot mined {@code pos} and it is still within its last {@link #DUG_CAP_PER_BOT} cells. */
    public boolean wasDug(long botKey, BlockPos pos) {
        return pos != null && wasDug(botKey, pos.asLong());
    }

    /** As {@link #wasDug(long, BlockPos)} for a packed cell. */
    public boolean wasDug(long botKey, long packedPos) {
        CellLru ring = dug.get(botKey);
        return ring != null && ring.contains(packedPos);
    }

    /** Number of dug cells currently held for the bot. */
    public int dugSize(long botKey) {
        CellLru ring = dug.get(botKey);
        return ring == null ? 0 : ring.size();
    }

    /** Forgets everything the bot dug. The placed ledger is untouched. */
    public void clearBot(long botKey) {
        dug.remove(botKey);
    }

    // ---- codec ---------------------------------------------------------------------------------

    /**
     * Stable JSON of the placed ledger: dimensions sorted by key, cells oldest first, empty
     * dimensions omitted. The same ledger state always yields the same string.
     */
    public String toJson() {
        int cells = 0;
        for (CellLru lru : placed.values()) {
            cells += lru.size();
        }
        StringBuilder out = new StringBuilder(48 + cells * 12);
        out.append("{\"version\":").append(VERSION).append(",\"dimensions\":{");
        boolean firstDimension = true;
        for (Map.Entry<String, CellLru> entry : new TreeMap<>(placed).entrySet()) {
            CellLru lru = entry.getValue();
            if (lru.size() == 0) {
                continue;
            }
            if (!firstDimension) {
                out.append(',');
            }
            firstDimension = false;
            out.append(new JsonPrimitive(entry.getKey())).append(":[");
            boolean firstCell = true;
            for (long cell : lru) {
                if (!firstCell) {
                    out.append(',');
                }
                firstCell = false;
                out.append(cell);
            }
            out.append(']');
        }
        return out.append("}}").toString();
    }

    /**
     * Parses {@link #toJson()} output. Fails open: null, blank, malformed, wrong-version or
     * non-numeric input returns an empty ledger whose {@link #loadedCleanly()} is {@code false}
     * (all or nothing, a partly readable file is discarded whole). A dimension array longer than
     * the cap keeps its newest cells. The result is not dirty.
     */
    public static BotEditsLedger fromJson(String json) {
        if (json == null || json.isBlank()) {
            return failed("blank");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return failed("not_json");
        }
        if (root == null || !root.isJsonObject()) {
            return failed("not_object");
        }
        JsonObject object = root.getAsJsonObject();
        Long version = strictLong(object.get("version"));
        if (version == null || version != VERSION) {
            return failed("wrong_version");
        }
        JsonElement dimensionsElement = object.get("dimensions");
        if (dimensionsElement == null || !dimensionsElement.isJsonObject()) {
            return failed("bad_dimensions");
        }
        BotEditsLedger ledger = new BotEditsLedger();
        for (Map.Entry<String, JsonElement> entry : dimensionsElement.getAsJsonObject().entrySet()) {
            if (entry.getKey().isBlank()) {
                return failed("blank_dimension_key");
            }
            if (!entry.getValue().isJsonArray()) {
                return failed("bad_dimension_cells");
            }
            JsonArray cells = entry.getValue().getAsJsonArray();
            CellLru lru = new CellLru(PLACED_CAP_PER_DIMENSION);
            for (JsonElement cell : cells) {
                Long packed = strictLong(cell);
                if (packed == null) {
                    return failed("bad_cell");
                }
                lru.touch(packed);
            }
            if (lru.size() > 0) {
                ledger.placed.put(entry.getKey(), lru);
            }
        }
        ledger.dirty = false;
        return ledger;
    }

    private static BotEditsLedger failed(String problem) {
        return new BotEditsLedger(problem);
    }

    /** The integer value of a JSON number written as plain digits, or null for anything else. */
    private static Long strictLong(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return Long.parseLong(element.getAsString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Insertion-ordered set of packed cells with a hard cap; the oldest entry is evicted first. */
    private static final class CellLru implements Iterable<Long> {
        private final int cap;
        private final LinkedHashSet<Long> cells = new LinkedHashSet<>();
        private long newest;

        CellLru(int cap) {
            this.cap = cap;
        }

        /** Makes the cell the newest entry; returns false when it already was and nothing changed. */
        boolean touch(long packed) {
            if (!cells.isEmpty() && newest == packed) {
                return false;
            }
            if (!cells.remove(packed) && cells.size() >= cap) {
                Iterator<Long> oldest = cells.iterator();
                oldest.next();
                oldest.remove();
            }
            cells.add(packed);
            newest = packed;
            return true;
        }

        boolean contains(long packed) {
            return cells.contains(packed);
        }

        int size() {
            return cells.size();
        }

        @Override
        public Iterator<Long> iterator() {
            return cells.iterator();
        }
    }
}
