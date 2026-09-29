package baritone.api.utils;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

/**
 * Upstream-neutral seam between Baritone and the program that embeds it.
 * <p>
 * Stock Baritone reaches for {@code Minecraft.getInstance()} to learn the game directory and to hop onto the
 * game thread before it logs, and it builds a private registry-only level to learn what blocks drop because a client has
 * no server to ask. There is no such singleton on a server, and a server can answer all three questions directly, so the
 * host (the mod) sets them once at start-up. Everything has a working default, so a host that sets nothing still gets
 * a functioning Baritone (files go under {@code ./baritone}, log calls run on the calling thread, drops come from the
 * private level).
 * <p>
 * This class is <b>not</b> part of upstream Baritone: it is added by {@code tools/baritone/overlay}.
 */
public final class HostEnvironment {

    private static volatile Path gameDirectory = Path.of("").toAbsolutePath();
    private static volatile Consumer<Runnable> gameThreadExecutor = Runnable::run;
    private static volatile Supplier<ServerLevel> lootLevel = () -> null;

    private HostEnvironment() {}

    /** Directory that contains {@code baritone/settings.txt}, {@code baritone/<cache>} and {@code schematics/}. */
    public static Path gameDirectory() {
        return gameDirectory;
    }

    public static void setGameDirectory(Path directory) {
        gameDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
    }

    /**
     * Runs {@code task} the way {@code Minecraft.getInstance().execute(task)} would: on the game thread if we are
     * elsewhere. Only used to deliver chat, toast and notification callbacks.
     */
    public static void execute(Runnable task) {
        gameThreadExecutor.accept(task);
    }

    public static void setGameThreadExecutor(Consumer<Runnable> executor) {
        gameThreadExecutor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * A real level whose server's loot tables Baritone may roll to learn what a block drops, or null to use Baritone's
     * own registry-only stand-in (which reloads every data-pack registry once, and cannot run inside a Fabric server).
     */
    public static ServerLevel lootLevel() {
        return lootLevel.get();
    }

    public static void setLootLevel(Supplier<ServerLevel> level) {
        lootLevel = Objects.requireNonNull(level, "level");
    }
}
