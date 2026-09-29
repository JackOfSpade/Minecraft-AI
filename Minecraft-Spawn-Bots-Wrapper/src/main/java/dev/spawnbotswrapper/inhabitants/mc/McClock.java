package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.engine.Clock;
import net.minecraft.server.MinecraftServer;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * {@link Clock} for the running server. Game logic uses the server tick counter (it stands still while a
 * dedicated server is paused with nobody online, and so do delays and timeouts); wall-clock milliseconds are
 * only ever stored for information.
 */
public final class McClock implements Clock {
    private final IntSupplier ticks;
    private final LongSupplier millis;

    public McClock(IntSupplier ticks, LongSupplier millis) {
        this.ticks = ticks;
        this.millis = millis;
    }

    public static McClock of(MinecraftServer server) {
        return new McClock(server::getTickCount, System::currentTimeMillis);
    }

    @Override
    public long tick() {
        return ticks.getAsInt();
    }

    @Override
    public long nowMillis() {
        return millis.getAsLong();
    }
}
