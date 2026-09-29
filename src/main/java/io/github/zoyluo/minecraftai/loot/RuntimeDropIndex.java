package io.github.zoyluo.minecraftai.loot;

import io.github.zoyluo.minecraftai.action.GatherToolPolicy;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootWorldContext;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
 * <p>Built lazily/once per server start (see RuntimeLifecycleCoordinator), thread-safe, cleared on
 * server stop -- same shape as RuntimeRecipeIndex. A caller that reads it before the first
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

    private RuntimeDropIndex() {
    }

    public static void rebuild(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (world == null) {
            return;
        }
        long startNanos = System.nanoTime();
        Map<Item, Set<Block>> deterministic = new HashMap<>();
        Map<Item, Set<Block>> probabilistic = new HashMap<>();
        int scanned = 0;
        for (Block block : Registries.BLOCK) {
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
    }

    /**
     * Blocks proven to always drop {@code item} (no Fortune, no Silk Touch) using their own
     * optimal tool. An empty (but present) set means the index is built and genuinely knows of no
     * such block. {@link Optional#empty()} means the index has not been built yet -- callers
     * should use their own fallback rather than treating that as "no sources".
     */
    public static Optional<Set<Block>> deterministicSourcesFor(Item item) {
        if (!ready) {
            return Optional.empty();
        }
        synchronized (RuntimeDropIndex.class) {
            return Optional.of(DETERMINISTIC.getOrDefault(item, Set.of()));
        }
    }

    /** Lower-priority fallback sources that only sometimes drop {@code item} (e.g. gravel -> flint). */
    public static Optional<Set<Block>> probabilisticSourcesFor(Item item) {
        if (!ready) {
            return Optional.empty();
        }
        synchronized (RuntimeDropIndex.class) {
            return Optional.of(PROBABILISTIC.getOrDefault(item, Set.of()));
        }
    }

    private static void classifyBlock(ServerWorld world, Block block,
                                       Map<Item, Set<Block>> deterministic, Map<Item, Set<Block>> probabilistic) {
        BlockState state = block.getDefaultState();
        ItemStack tool = representativeTool(GatherToolPolicy.categoryFor(state));
        List<Map<Item, Integer>> trials = new ArrayList<>(TRIALS);
        for (int i = 0; i < TRIALS; i++) {
            trials.add(oneTrial(world, state, tool));
        }
        DropClassification.merge(block, DropClassification.classify(trials), deterministic, probabilistic);
    }

    private static Map<Item, Integer> oneTrial(ServerWorld world, BlockState state, ItemStack tool) {
        LootWorldContext.Builder builder = new LootWorldContext.Builder(world)
                .add(LootContextParameters.ORIGIN, Vec3d.ofCenter(BlockPos.ORIGIN))
                .add(LootContextParameters.TOOL, tool)
                .luck(0.0F);
        List<ItemStack> drops = state.getDroppedStacks(builder);
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
