package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.engine.Clock;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.server.network.ServerLoginNetworkHandler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a newly-connecting player at LOGIN, before their client leaves its own connecting/loading screen, for
 * a configurable number of ticks after server start ({@code connection.joinHoldTicks}), so nobody is placed
 * into the world mid-way through PvP BOT's own post-start bot-restore burst, when a tick stall could otherwise
 * let them fall through unloaded terrain. Self-contained: reads only its own config section and never touches
 * the population/roster engine (PopulationEngine, BotRoster) or any of its invariants.
 * <p>
 * Gates at LOGIN, not CONFIGURATION, on purpose -- the latter was tried first and does not work on this
 * Minecraft version. {@code ServerConfigurationConnectionEvents.CONFIGURE} looks like the natural hook, but by
 * the time it fires, vanilla's own {@code ServerLoginNetworkHandler} has ALREADY queued its own configuration
 * tasks (registry sync, spawn prep, and critically {@code JoinWorldTask} -- the task whose completion actually
 * creates the player entity) into the same single FIFO task queue. A task added from {@code CONFIGURE} lands
 * behind those, and {@code CONFIGURE} itself only fires nested inside {@code JoinWorldTask}'s own completion
 * handling -- after the join has already been committed to. The result: the added task just hangs there,
 * never gating anything, and the client never leaves its loading screen. Gating at LOGIN instead, via
 * {@link ServerLoginConnectionEvents#QUERY_START} and {@link ServerLoginNetworking.LoginSynchronizer#waitFor},
 * runs strictly before any of that is queued, so it actually blocks the join the way it is meant to.
 * <p>
 * {@code QUERY_START}/{@code DISCONNECT} fire on the connection's own Netty I/O thread, not the main server
 * thread, so this class never touches live server/world state from those callbacks: {@link #holdWindowOpen}
 * is a single-writer (main thread only) volatile flag, and {@link #pending} is a map safe for concurrent
 * adds/removals from Netty threads and a full drain from the main thread.
 */
public final class JoinHoldGate {
    private final Map<ServerLoginNetworkHandler, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
    private volatile boolean holdWindowOpen;
    private Clock clock;
    private long startupTick = -1;

    /** Registers the hold; call once from the mod entrypoint. */
    public void register() {
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            if (holdWindowOpen) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                pending.put(handler, future);
                synchronizer.waitFor(future);
            }
        });
        ServerLoginConnectionEvents.DISCONNECT.register((handler, server) -> {
            CompletableFuture<Void> future = pending.remove(handler);
            if (future != null) {
                future.complete(null);
            }
        });
    }

    /** Call once from SERVER_STARTED, with a fresh {@link Clock} for this server and the live config. */
    public void onServerStarted(Clock clock, int joinHoldTicksNow) {
        this.clock = clock;
        this.startupTick = clock.tick();
        this.holdWindowOpen = shouldHold(startupTick, startupTick, joinHoldTicksNow);
    }

    /** Call every END_SERVER_TICK with the live config value; releases held connections once the window elapses. */
    public void onEndServerTick(int joinHoldTicks) {
        Clock c = clock;
        if (c == null) {
            return;
        }
        holdWindowOpen = shouldHold(startupTick, c.tick(), joinHoldTicks);
        if (!holdWindowOpen && !pending.isEmpty()) {
            for (CompletableFuture<Void> future : pending.values()) {
                future.complete(null);
            }
            pending.clear();
        }
    }

    /** Call from SERVER_STOPPED so a fresh session in the same JVM starts clean. */
    public void onServerStopped() {
        clock = null;
        startupTick = -1;
        holdWindowOpen = false;
        for (CompletableFuture<Void> future : pending.values()) {
            future.complete(null);
        }
        pending.clear();
    }

    /** Pure decision, unit-testable without any Minecraft/Fabric type. Package-visible for JoinHoldGateTest. */
    static boolean shouldHold(long startupTick, long nowTick, int joinHoldTicks) {
        if (startupTick < 0 || joinHoldTicks <= 0 || nowTick < startupTick) {
            return false;
        }
        return nowTick - startupTick < joinHoldTicks;
    }
}
