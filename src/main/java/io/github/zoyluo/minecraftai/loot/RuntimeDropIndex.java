package io.github.zoyluo.minecraftai.loot;

import io.github.zoyluo.minecraftai.action.GatherToolPolicy;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime drop index (knowledge-layer, data-driven; mirrors
 * {@link io.github.zoyluo.minecraftai.craft.RuntimeRecipeIndex}'s lifecycle): after the server
 * starts, evaluates every block's own loot table -- at its default state, its own optimal tool
 * (see {@link GatherToolPolicy}), no Silk Touch, luck 0 -- a fixed number of times, classifying
 * each dropped item as <b>deterministic</b> (dropped on every trial, e.g. dirt from grass_block)
 * or <b>probabilistic</b> (dropped on only some trials, e.g. flint from gravel).
 *
 * <p>"Gather X" (see GatherQuotaTask) means "break a block that DROPS X" -- not "break the one
 * block whose registry name equals X". This index is the runtime source of truth for that
 * mapping, replacing same-name guessing; deterministic sources are always preferred, with
 * probabilistic sources kept only as a lower-priority fallback (e.g. gathering flint by breaking
 * gravel when nothing else drops it reliably).
 *
 * <p>Armed at server start (see RuntimeLifecycleCoordinator), built once by an idle-time warm-up
 * {@link #WARMUP_DELAY_TICKS} ticks later (MinecraftAiMod's END_SERVER_TICK hook) or, if a query
 * arrives first, lazily on the first server-thread use; thread-safe, cleared on server stop --
 * same shape as RuntimeRecipeIndex. A caller that reads it before the first
 * {@link #rebuild} (unit tests / very early startup) gets {@link Optional#empty()} and should fall
 * back to its own minimal default.
 */
public final class RuntimeDropIndex {
    private static final int TRIALS = 24;

    // Player-infrastructure blocks deliberately excluded as gather sources even though their loot
    // table genuinely drops the item (farmland and dirt_path both drop dirt when broken): breaking
    // a player's farm or path to satisfy a generic "gather dirt" is almost never what they meant.
    // This is the one place that exclusion is applied.
    private static final Set<Block> EXCLUDED_SOURCES = Set.of(Blocks.FARMLAND, Blocks.DIRT_PATH);

    private static final Map<Item, Set<Block>> DETERMINISTIC = new HashMap<>();
    private static final Map<Item, Set<Block>> PROBABILISTIC = new HashMap<>();
    private static volatile boolean ready;
    private static volatile MinecraftServer armedServer;

    private RuntimeDropIndex() {
    }

    /** Server ticks after {@link #arm} at which the idle warm-up build runs (5 s at 20 tps). */
    static final int WARMUP_DELAY_TICKS = 100;

    // Only ever touched on the server thread (arm from SERVER_STARTED, tickWarmup from the tick hook).
    // > 0: counting down; 0: delay elapsed, waiting for an idle moment; -1: no warm-up pending.
    private static int warmupTicksLeft = -1;

    /**
     * Registers the server without building: the build costs 200-350 ms (build_ms in
     * runtime_drop_index_built; ~660 ms cold), too long for the server-start critical path. It is
     * built by {@link #tickWarmup} a few seconds after start (an idle moment, on the server thread)
     * so the hitch does not land inside the player's first gather request; the lazy build on the
     * first server-thread query stays as the fallback for a request that arrives before the warm-up
     * fires. A query from any other thread before either has run simply sees "not built" and uses
     * the caller's fallback.
     */
    public static void arm(MinecraftServer server) {
        armedServer = server;
        ready = false;
        warmupTicksLeft = WARMUP_DELAY_TICKS;
    }

    /**
     * Server-thread tick hook: counts down from {@link #arm}, then builds the index once at the first
     * moment {@code idle} says no bot task is running (a 200-650 ms build inside a running task is a
     * visible hitch and skews any timing-sensitive work); no-op if a first query already built it
     * lazily. Cheap when nothing is pending. If the server is never idle the lazy first-use build
     * remains the fallback.
     */
    public static void tickWarmup(MinecraftServer server, java.util.function.BooleanSupplier idle) {
        if (warmupTicksLeft < 0) {
            return;
        }
        if (ready || server != armedServer) {
            warmupTicksLeft = -1;
            return;
        }
        if (warmupTicksLeft > 0) {
            warmupTicksLeft--;
            return;
        }
        if (!idle.getAsBoolean()) {
            return;
        }
        warmupTicksLeft = -1;
        rebuild(server);
    }

    private static void ensureBuilt() {
        if (ready) {
            return;
        }
        MinecraftServer server = armedServer;
        if (server != null && server.isSameThread()) {
            rebuild(server);
        }
    }

    public static void rebuild(MinecraftServer server) {
        ServerLevel world = server.overworld();
        if (world == null) {
            return;
        }
        long startNanos = System.nanoTime();
        Map<Item, Set<Block>> deterministic = new HashMap<>();
        Map<Item, Set<Block>> probabilistic = new HashMap<>();
        int scanned = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            scanned++;
            if (EXCLUDED_SOURCES.contains(block)) {
                continue;
            }
            try {
                classifyBlock(world, block, deterministic, probabilistic);
            } catch (RuntimeException ignored) {
                // A single block's loot table failing to evaluate (mod-custom loot function, a
                // block that needs a BlockEntity we don't supply, etc.) must not take down the
                // whole index -- same defensive shape as RuntimeRecipeIndex.rebuild.
            }
        }
        synchronized (RuntimeDropIndex.class) {
            DETERMINISTIC.clear();
            DETERMINISTIC.putAll(deterministic);
            PROBABILISTIC.clear();
            PROBABILISTIC.putAll(probabilistic);
        }
        ready = true;
        BotLog.comm(null, "runtime_drop_index_built",
                "scanned", scanned,
                "deterministic_items", deterministic.size(),
                "probabilistic_items", probabilistic.size(),
                "build_ms", (System.nanoTime() - startNanos) / 1_000_000L);
    }

    public static void clear() {
        synchronized (RuntimeDropIndex.class) {
            DETERMINISTIC.clear();
            PROBABILISTIC.clear();
        }
        ready = false;
        armedServer = null;
        warmupTicksLeft = -1;
    }

    /**
     * Blocks proven to always drop {@code item} (no Fortune, no Silk Touch) using their own
     * optimal tool. An empty (but present) set means the index is built and genuinely knows of no
     * such block. {@link Optional#empty()} means the index has not been built yet -- callers
     * should use their own fallback rather than treating that as "no sources".
     */
    public static Optional<Set<Block>> deterministicSourcesFor(Item item) {
        ensureBuilt();
        if (!ready) {
            return Optional.empty();
        }
        synchronized (RuntimeDropIndex.class) {
            return Optional.of(DETERMINISTIC.getOrDefault(item, Set.of()));
        }
    }

    /** Lower-priority fallback sources that only sometimes drop {@code item} (e.g. gravel -> flint). */
    public static Optional<Set<Block>> probabilisticSourcesFor(Item item) {
        ensureBuilt();
        if (!ready) {
            return Optional.empty();
        }
        synchronized (RuntimeDropIndex.class) {
            return Optional.of(PROBABILISTIC.getOrDefault(item, Set.of()));
        }
    }

    private static void classifyBlock(ServerLevel world, Block block,
                                       Map<Item, Set<Block>> deterministic, Map<Item, Set<Block>> probabilistic) {
        BlockState state = block.defaultBlockState();
        ItemStack tool = representativeTool(GatherToolPolicy.categoryFor(state));
        List<Map<Item, Integer>> trials = new ArrayList<>(TRIALS);
        for (int i = 0; i < TRIALS; i++) {
            trials.add(oneTrial(world, state, tool));
        }
        DropClassification.merge(block, DropClassification.classify(trials), deterministic, probabilistic);
    }

    private static Map<Item, Integer> oneTrial(ServerLevel world, BlockState state, ItemStack tool) {
        LootParams.Builder builder = new LootParams.Builder(world)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(BlockPos.ZERO))
                .withParameter(LootContextParams.TOOL, tool)
                .withLuck(0.0F);
        List<ItemStack> drops = state.getDrops(builder);
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static ItemStack representativeTool(GatherToolPolicy.Category category) {
        return switch (category) {
            case PICKAXE -> new ItemStack(Items.DIAMOND_PICKAXE);
            case AXE -> new ItemStack(Items.DIAMOND_AXE);
            case SHOVEL -> new ItemStack(Items.DIAMOND_SHOVEL);
            case HOE -> new ItemStack(Items.DIAMOND_HOE);
            case SHEARS -> new ItemStack(Items.SHEARS);
            case SWORD -> new ItemStack(Items.DIAMOND_SWORD);
            case NONE -> ItemStack.EMPTY;
        };
    }
}
