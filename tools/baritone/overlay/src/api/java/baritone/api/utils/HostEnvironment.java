package baritone.api.utils;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

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

    private static volatile Executor executor;

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

    /**
     * Where Baritone runs its short background jobs: path searches, block rescans (mine, farm, get-to-block, explore),
     * region loads. Upstream keeps a private, unbounded, non-daemon pool here; a host that runs many bots wants the number
     * of concurrent searches capped and its threads named and daemonised. Defaults to a cached pool of daemon threads.
     * <p>
     * Every task must be short-lived: the two never-ending cache loops go to {@link #startDaemon} instead, so they do not
     * occupy a slot of a bounded executor for good.
     */
    public static Executor executor() {
        Executor current = executor;
        return current != null ? current : DefaultExecutor.INSTANCE;
    }

    public static void setExecutor(Executor newExecutor) {
        executor = Objects.requireNonNull(newExecutor, "executor");
    }

    /** Starts a background thread that lives as long as its loop does (the cache packer and the periodic cache save). */
    public static void startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The tool a host's players really break a block with. Upstream prices a break with the fastest tool on the hotbar, because
     * its own auto-tool equips that one; a host that equips by its own policy (which may keep a valuable pickaxe for the ores that
     * need it and wear a cheap one on stone) asks Baritone to price with the same tool, so a break takes as long as the plan says.
     * Only a cost model asks ({@code ToolSet(player, true)}), and it may do so on any thread after {@link #snapshot}.
     */
    public interface ToolPolicy {
        /** A private copy of what {@code player} carries. Called on the game thread when a cost model is created. */
        Object snapshot(Player player);

        /** The stack {@code snapshot}'s player would hold to break {@code state}, or null to price it the upstream way. Thread safe. */
        ItemStack toolFor(Object snapshot, BlockState state);
    }

    private static volatile ToolPolicy toolPolicy;

    /** The host's tool policy, or null (upstream's fastest-hotbar-tool pricing). */
    public static ToolPolicy toolPolicy() {
        return toolPolicy;
    }

    public static void setToolPolicy(ToolPolicy policy) {
        toolPolicy = policy;
    }

    private static final class DefaultExecutor {
        private static final AtomicInteger COUNTER = new AtomicInteger();
        static final Executor INSTANCE = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "baritone-worker-" + COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
