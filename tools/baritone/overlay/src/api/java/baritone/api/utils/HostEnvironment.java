package baritone.api.utils;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Upstream-neutral seam between Baritone and the program that embeds it.
 * <p>
 * Stock Baritone reaches for {@code Minecraft.getInstance()} to learn the game directory and to hop onto the
 * game thread before it logs. There is no such singleton on a server, so the two facts live here and the host
 * (the mod) sets them once at start-up. Everything has a working default, so a host that sets nothing still gets
 * a functioning Baritone (files go under {@code ./baritone}, log calls run on the calling thread).
 * <p>
 * This class is <b>not</b> part of upstream Baritone: it is added by {@code tools/baritone/overlay}.
 */
public final class HostEnvironment {

    private static volatile Path gameDirectory = Path.of("").toAbsolutePath();
    private static volatile Consumer<Runnable> gameThreadExecutor = Runnable::run;

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
}
