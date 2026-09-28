package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.persist.AtomicSnapshotFile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter and sidecar I/O for the bot's own edits (mining-assist design 2.4 and 3.3). Blocks the bots
 * placed must never look like a structure, so every placement site reports here and the sensor asks
 * before it records a cell as POI evidence.
 *
 * <ul>
 *   <li><b>Placed cells</b> live in one server-wide {@link BotEditsLedger} keyed by dimension id
 *       ({@code minecraft:overworld}), persisted to {@code config/minecraftai/mining_assist_edits.json}.
 *       The server thread captures the JSON string when the ledger is dirty and at least
 *       {@value BotEditsLedger#SNAPSHOT_MIN_INTERVAL_TICKS} ticks have passed, and hands only that
 *       string to a single daemon writer thread (G2: no file I/O on the server thread while ticking).
 *       The one synchronous write is {@link #flushSync} at server stop. Loading fails open.</li>
 *   <li><b>Dug cells</b> are a per-bot runtime ring (8192 cells) used by the break peek to tell a cell the
 *       bot dug itself from a real breakthrough into open space.</li>
 * </ul>
 *
 * <p>Every entry point is cheap and exception-free (a failing note must never break a placement) and
 * does nothing at all while the assist mode is {@code off}. Called from the server thread only.</p>
 */
public final class BotEdits {
    /** Directory below the Fabric config dir. */
    public static final String SIDECAR_DIRECTORY = "minecraftai";
    /** Refuse to read a sidecar larger than this (the codec is bounded, the file should be too). */
    public static final long MAX_SIDECAR_BYTES = 8L * 1024L * 1024L;

    private static volatile BotEditsLedger ledger = new BotEditsLedger();
    private static long lastSnapshotTick = Long.MIN_VALUE / 2;
    private static ExecutorService writer;
    private static int failureNotes;
    /** Set by the writer thread when a write fails, read and cleared on the server thread (see {@link #snapshotIfDue}). */
    private static final AtomicBoolean writeFailed = new AtomicBoolean();

    private BotEdits() {
    }

    // ---------------------------------------------------------------------------------------
    // Hooks: placements and breaks
    // ---------------------------------------------------------------------------------------

    /** The bot placed a block at {@code pos}. Called after the placement succeeded. */
    public static void notePlaced(AIPlayerEntity bot, BlockPos pos) {
        if (bot == null) {
            return;
        }
        notePlaced(bot.getEntityWorld(), pos);
    }

    /** A bot placed a block at {@code pos} in {@code world}. Origin independent: safety seals count too. */
    public static void notePlaced(ServerWorld world, BlockPos pos) {
        if (!MiningAssistRuntime.senseConfigured() || world == null || pos == null) {
            return;
        }
        try {
            ledger.notePlaced(dimensionKey(world), pos);
        } catch (RuntimeException exception) {
            noteFailure("note_placed", exception);
        }
    }

    /**
     * The bot's break at {@code pos} was observed to have opened the cell (called by the break peek, not by the
     * break hook). Runtime only, feeds the breakthrough test of the break peek.
     */
    public static void noteDug(AIPlayerEntity bot, BlockPos pos) {
        if (!MiningAssistRuntime.senseConfigured() || bot == null || pos == null) {
            return;
        }
        try {
            ledger.noteDug(botKey(bot.getUuid()), pos);
        } catch (RuntimeException exception) {
            noteFailure("note_dug", exception);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------

    /** True when a bot placed a block at {@code packedPos} in the dimension. Pure query: never dirties the ledger. */
    public static boolean wasPlaced(String dimensionKey, long packedPos) {
        return ledger.wasPlaced(dimensionKey, packedPos);
    }

    public static boolean wasPlaced(ServerWorld world, BlockPos pos) {
        return ledger.wasPlaced(dimensionKey(world), pos);
    }

    /** True when this bot has itself broken the block at {@code pos} (runtime ring). */
    public static boolean wasDug(AIPlayerEntity bot, BlockPos pos) {
        return ledger.wasDug(botKey(bot.getUuid()), pos);
    }

    /** Forgets one bot's dug ring (bot unload). The placed ledger is server-wide and stays. */
    public static void clearBot(UUID botId) {
        ledger.clearBot(botKey(botId));
    }

    /** The dimension id used everywhere as the ledger key and for {@code poi.cavernDimensions}. */
    public static String dimensionKey(World world) {
        return world.getRegistryKey().getValue().toString();
    }

    /** Stable long for a bot's dug ring. */
    public static long botKey(UUID botId) {
        return botId.getMostSignificantBits() * 31L + botId.getLeastSignificantBits();
    }

    // ---------------------------------------------------------------------------------------
    // Sidecar
    // ---------------------------------------------------------------------------------------

    /** {@code <config dir>/minecraftai/mining_assist_edits.json}. Needs the Fabric loader, so not for unit tests. */
    public static Path defaultSidecarPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(SIDECAR_DIRECTORY).resolve(BotEditsLedger.SIDECAR_FILE_NAME);
    }

    /**
     * Replaces the in-memory ledger with the one in {@code file}. A missing file, or
     * {@code edits.sidecar == false}, gives an empty ledger; unreadable, oversized or corrupt content also
     * gives an empty ledger (fail open) with a warning. Meant for {@code onServerStarted}, when nothing is
     * ticking yet; the file is small and bounded by {@link #MAX_SIDECAR_BYTES}.
     *
     * @return true when a file was read and parsed cleanly
     */
    public static boolean loadFromDisk(Path file) {
        BotEditsLedger loaded = new BotEditsLedger();
        boolean clean = false;
        // With the switch off nothing records placements, so there is nothing to read the file for.
        if (MiningAssistRuntime.senseConfigured() && MiningAssistRuntime.config().edits().sidecar()
                && file != null && Files.isRegularFile(file)) {
            try {
                long size = Files.size(file);
                if (size > MAX_SIDECAR_BYTES) {
                    BotLog.warn(LogCategory.CONFIG, null, "assist_edits_load_problem",
                            "path", file, "problem", "too_large", "bytes", size);
                } else {
                    String json;
                    try (InputStream in = Files.newInputStream(file)) {
                        json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    loaded = BotEditsLedger.fromJson(json);
                    clean = loaded.loadedCleanly();
                    if (!clean) {
                        BotLog.warn(LogCategory.CONFIG, null, "assist_edits_load_problem",
                                "path", file, "problem", loaded.loadProblem());
                    }
                }
            } catch (IOException | RuntimeException exception) {
                BotLog.warn(LogCategory.CONFIG, null, "assist_edits_load_problem",
                        "path", file, "problem", "io_" + exception.getClass().getSimpleName());
                loaded = new BotEditsLedger();
            }
        }
        ledger = loaded;
        lastSnapshotTick = Long.MIN_VALUE / 2;
        return clean;
    }

    /**
     * Server-thread call, cheap when nothing changed: when the ledger is dirty and due, captures its JSON,
     * marks it clean and hands the string to the writer thread. Does nothing when {@code edits.sidecar}
     * is false (the in-memory ledger keeps working). Returns true when a snapshot was queued.
     */
    public static boolean snapshotIfDue(long nowTick, Path file) {
        BotEditsLedger current = ledger;
        if (file == null || !MiningAssistRuntime.config().edits().sidecar()) {
            return false;
        }
        // A capture marks the ledger clean before the writer has run, so a failed write would otherwise be
        // forgotten until the next placement. A failure flag (set by the writer, read here) makes the next
        // snapshot slot retry with the full ledger.
        boolean retry = writeFailed.get() && (nowTick < lastSnapshotTick
                || nowTick - lastSnapshotTick >= BotEditsLedger.SNAPSHOT_MIN_INTERVAL_TICKS);
        if (!retry && !current.snapshotDue(nowTick, lastSnapshotTick)) {
            return false;
        }
        String json = current.toJson();
        writeFailed.set(false);
        try {
            writerThread().execute(() -> writeQuietly(file, json));
        } catch (RejectedExecutionException rejected) {
            // The writer is shutting down: stay dirty, the synchronous flush at server stop still runs.
            writeFailed.set(true);
            return false;
        }
        current.markClean();
        lastSnapshotTick = nowTick;
        return true;
    }

    /**
     * Synchronous flush for {@code onServerStopping}: waits (briefly) for a queued write, then writes
     * the ledger once more if it is still dirty, and stops the writer thread. Safe to call repeatedly.
     */
    public static void flushSync(Path file) {
        ExecutorService pending;
        synchronized (BotEdits.class) {
            pending = writer;
            writer = null;
        }
        boolean interrupted = false;
        if (pending != null) {
            pending.shutdown();
            try {
                if (!pending.awaitTermination(5L, TimeUnit.SECONDS)) {
                    // A write is stuck: interrupt it, give it a moment to end so it cannot land after ours,
                    // and write the ledger again ourselves (the interrupted capture may not have been stored).
                    pending.shutdownNow();
                    interrupted = true;
                    pending.awaitTermination(2L, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                pending.shutdownNow();
                interrupted = true;
                Thread.currentThread().interrupt();
            }
        }
        BotEditsLedger current = ledger;
        if (file != null && MiningAssistRuntime.config().edits().sidecar()
                && (current.isDirty() || interrupted || writeFailed.get())) {
            String json = current.toJson();
            current.markClean();
            writeFailed.set(false);
            writeQuietly(file, json);
        }
    }

    /** True when the last background write of the sidecar failed and has not been retried yet (tests, diagnostics). */
    static boolean lastWriteFailed() {
        return writeFailed.get();
    }

    /** Test and world-reset helper: an empty ledger, no writer, no dirty state. */
    public static void resetForTests() {
        synchronized (BotEdits.class) {
            if (writer != null) {
                writer.shutdownNow();
                writer = null;
            }
        }
        ledger = new BotEditsLedger();
        lastSnapshotTick = Long.MIN_VALUE / 2;
        failureNotes = 0;
        writeFailed.set(false);
    }

    /** The live ledger, for tests and diagnostics. */
    public static BotEditsLedger ledger() {
        return ledger;
    }

    private static synchronized ExecutorService writerThread() {
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MinecraftAiAssistEditsWriter");
                thread.setDaemon(true);
                return thread;
            });
        }
        return writer;
    }

    private static void writeQuietly(Path file, String json) {
        try {
            AtomicSnapshotFile.write(file, json);
        } catch (IOException | RuntimeException exception) {
            writeFailed.set(true);
            BotLog.error("assist_edits_save_failed", exception, "path", file);
        }
    }

    private static void noteFailure(String where, RuntimeException exception) {
        if (failureNotes < 3) {
            failureNotes++;
            BotLog.error("assist_edits_hook_failed", exception, "where", where);
        }
    }
}
