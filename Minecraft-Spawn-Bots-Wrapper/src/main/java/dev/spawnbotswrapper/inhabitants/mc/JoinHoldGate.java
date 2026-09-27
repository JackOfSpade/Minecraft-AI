package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.engine.Clock;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationNetworkHandler;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayerConfigurationTask;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Holds a newly-connecting player on their client's native loading screen for a configurable number of
 * ticks after server start ({@code connection.joinHoldTicks}), so nobody is placed into the world mid-way
 * through PvP BOT's own post-start bot-restore burst, when a tick stall could otherwise let them fall
 * through unloaded terrain. Self-contained: reads only its own config section and never touches the
 * population/roster engine (PopulationEngine, BotRoster) or any of its invariants.
 * <p>
 * Uses the vanilla configuration-phase task mechanism ({@link ServerConfigurationConnectionEvents#CONFIGURE}
 * plus {@link FabricServerConfigurationNetworkHandler#addTask}/{@code completeTask}), so a held player's
 * entity is never created until the hold ends -- the client simply stays on its own loading screen the
 * whole time, there is no teleport-then-freeze/rubber-band.
 * <p>
 * {@code CONFIGURE}/{@code DISCONNECT} fire on the connection's own Netty I/O thread, not the main server
 * thread, so this class never touches live server/world state from those callbacks: {@link #holdWindowOpen}
 * is a single-writer (main thread only) volatile flag, and {@link #pending} is a queue safe for concurrent
 * adds from Netty threads and drains from the main thread.
 */
public final class JoinHoldGate {
    private static final ServerPlayerConfigurationTask.Key KEY =
            new ServerPlayerConfigurationTask.Key("spawnbotswrapper:join_hold");

    private final Queue<ServerConfigurationNetworkHandler> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean holdWindowOpen;
    private Clock clock;
    private long startupTick = -1;

    /** Registers the hold; call once from the mod entrypoint. */
    public void register() {
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (holdWindowOpen) {
                pending.add(handler);
                ((FabricServerConfigurationNetworkHandler) handler).addTask(new Task());
            }
        });
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> pending.remove(handler));
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
        if (!holdWindowOpen) {
            ServerConfigurationNetworkHandler handler;
            while ((handler = pending.poll()) != null) {
                ((FabricServerConfigurationNetworkHandler) handler).completeTask(KEY);
            }
        }
    }

    /** Call from SERVER_STOPPED so a fresh session in the same JVM starts clean. */
    public void onServerStopped() {
        clock = null;
        startupTick = -1;
        holdWindowOpen = false;
        pending.clear();
    }

    /** Pure decision, unit-testable without any Minecraft/Fabric type. Package-visible for JoinHoldGateTest. */
    static boolean shouldHold(long startupTick, long nowTick, int joinHoldTicks) {
        if (startupTick < 0 || joinHoldTicks <= 0 || nowTick < startupTick) {
            return false;
        }
        return nowTick - startupTick < joinHoldTicks;
    }

    private static final class Task implements ServerPlayerConfigurationTask {
        @Override
        public void sendPacket(Consumer<Packet<?>> sender) {
            // No packet needed; this task only ever blocks completion until completeTask(KEY) is called.
        }

        @Override
        public Key getKey() {
            return KEY;
        }
    }
}
