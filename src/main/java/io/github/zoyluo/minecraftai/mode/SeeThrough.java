package io.github.zoyluo.minecraftai.mode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BarrierBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Which blocks a bot's eyes pass through: foliage, fences, glass, water and the other things a player looks straight past in
 * the default game. Sight only. A see-through block still blocks a hand: {@link SightClipContext} reports it as an obstruction
 * of the pick ray, and every actuator keeps proving its own click with a vanilla {@link net.minecraft.world.level.ClipContext}.
 *
 * <p>The verdict is made per {@link Block} (never per state, never per fluid) from vanilla's own data, so a modded block falls
 * into a class or a tag instead of needing a list entry. It is the census of all 1166 vanilla blocks of 1.21.11
 * ({@code SeeThroughGoldenTest} pins every id):</p>
 * <ul>
 *   <li>the families the user named, by tag: {@code leaves}, {@code fences}, {@code fence_gates} (open or closed) and
 *       {@code crops};</li>
 *   <li>glass-like blocks: every {@link HalfTransparentBlock} except blue ice (glass, stained and tinted glass, ice, frosted
 *       ice, slime, honey, the copper grates), and bars, chains, ladders, scaffolding, mangrove roots, spawners, bamboo and
 *       barriers;</li>
 *   <li>every block with no collision in any of its states (plants, torches, rails, wire, webs, vines, fire, portals, air,
 *       water ...) except the ones that are plainly visible although nothing collides with them: buttons, pressure plates,
 *       signs, banners, levers, the end portal and gateway, powder snow, a moving piston and lava.</li>
 * </ul>
 * <p>Walls, doors, trapdoors, slabs, stairs, chests, beds, every solid cube (blue and packed ice, vault, beacon ...), azalea
 * bushes, lily pads and sea pickles stay opaque. At cell level {@link #cell(BlockState)} adds the fluid: a cell holding lava is
 * opaque whatever block it is, while water, waterlogged or not, never changes the verdict of its host block.</p>
 */
public final class SeeThrough {
    private static final Map<Block, Boolean> VERDICTS = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private SeeThrough() {
    }

    /** Whether eyes pass through {@code block}; cached per block until {@link #invalidate()}. */
    public static boolean block(Block block) {
        Boolean cached = VERDICTS.get(block);
        if (cached != null) {
            return cached;
        }
        int started = GENERATION.get();
        boolean computed = compute(block);
        // A verdict computed on another thread while a tag reload invalidates is returned but never kept.
        if (started == GENERATION.get()) {
            VERDICTS.put(block, computed);
            if (started != GENERATION.get()) {
                VERDICTS.remove(block, computed);
            }
        }
        return computed;
    }

    /** Whether eyes pass through the whole cell of {@code state}: its block is see-through and no lava fills it. */
    public static boolean cell(BlockState state) {
        return block(state.getBlock()) && !state.getFluidState().is(FluidTags.LAVA);
    }

    /**
     * Forgets every verdict. The family tags are data that is bound after the mod initialises and replaced by {@code /reload},
     * so a verdict made before that would be wrong for the rest of the JVM: wired to server start and to every tag load
     * ({@code MinecraftAiMod}).
     */
    public static void invalidate() {
        GENERATION.incrementAndGet();
        VERDICTS.clear();
    }

    private static boolean compute(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.CROPS)) {
            return true;
        }
        if (block instanceof HalfTransparentBlock && block != Blocks.BLUE_ICE) {
            return true;
        }
        if (block instanceof IronBarsBlock || block instanceof ChainBlock || block instanceof LadderBlock
                || block instanceof ScaffoldingBlock || block instanceof MangroveRootsBlock
                || block instanceof SpawnerBlock || block instanceof TrialSpawnerBlock
                || block instanceof BarrierBlock || block instanceof BambooStalkBlock) {
            return true;
        }
        if (!collisionEmptyInEveryState(block)) {
            return false;
        }
        return !(state.is(BlockTags.BUTTONS) || state.is(BlockTags.PRESSURE_PLATES) || state.is(BlockTags.ALL_SIGNS)
                || state.is(BlockTags.BANNERS) || block instanceof LeverBlock || block instanceof EndPortalBlock
                || block instanceof EndGatewayBlock || block instanceof PowderSnowBlock
                || block instanceof MovingPistonBlock || block == Blocks.LAVA);
    }

    private static boolean collisionEmptyInEveryState(Block block) {
        try {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (!state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            // A modded shape that needs a real world to answer: unknown means opaque, which is what vanilla sight does.
            return false;
        }
    }
}
