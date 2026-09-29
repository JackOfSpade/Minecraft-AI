package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.craft.SmeltChain;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Visible, local workstation lookup shared by the deterministic task executors.
 *
 * <p>A workstation found here is always an actual world block the bot can observe.  This keeps
 * recipe planning from inventing a second table or furnace when a player has already made one
 * nearby.  Tasks still own the last-mile pathing/placement decision, because a visible station
 * can disappear or become inaccessible after planning.</p>
 */
public final class WorkshopLocator {
    public static final int CRAFTING_TABLE_RADIUS = 8;
    public static final int FURNACE_RADIUS = 10;

    /** Vanilla cooking times: a normal furnace needs 200 ticks; smoker/blast furnace need 100. */
    private static final int NORMAL_COOK_TICKS = 200;
    private static final int FAST_COOK_TICKS = 100;
    /** Conservative walking estimate used only to break a local station-choice tie. */
    private static final double ESTIMATED_WALK_TICKS_PER_BLOCK = 5.0D;

    private static final Set<Item> BLAST_FURNACE_INPUTS = Set.of(
            Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD);

    private WorkshopLocator() {
    }

    /** Returns the nearest visible crafting table in the local workshop radius. */
    public static Optional<BlockPos> nearestCraftingTable(AIPlayerEntity bot) {
        return nearestBlock(bot, CRAFTING_TABLE_RADIUS,
                state -> state.is(Blocks.CRAFTING_TABLE));
    }

    public static boolean hasNearbyCraftingTable(AIPlayerEntity bot) {
        return nearestCraftingTable(bot).isPresent();
    }

    /** Returns the nearest visible normal furnace. A normal furnace is the universal fallback. */
    public static Optional<BlockPos> nearestFurnace(AIPlayerEntity bot) {
        return nearestBlock(bot, FURNACE_RADIUS, state -> state.is(Blocks.FURNACE));
    }

    public static boolean hasNearbyFurnace(AIPlayerEntity bot) {
        return nearestFurnace(bot).isPresent();
    }

    /**
     * Finds an empty or already-compatible local furnace-family block for the supplied recipe.
     * A smoker is preferred for food, a blast furnace for raw metals, and a normal furnace is
     * always a compatible fallback.  Existing incompatible input/output is never selected.
     */
    public static Optional<BlockPos> nearestCompatibleFurnace(
            AIPlayerEntity bot, Item input, Item output) {
        return nearestCompatibleFurnace(bot, input, output, 1, Set.of());
    }

    /**
     * Returns the local furnace-family station with the lowest estimated completion time.
     *
     * <p>For a supported food recipe, a smoker contributes 100 cook ticks per item; for a raw
     * metal recipe, a blast furnace does the same.  A normal furnace contributes 200 ticks and
     * remains the universal fallback.  The score includes a conservative local walking estimate
     * so distance decides close calls instead of an arbitrary stream order.  Callers can exclude
     * a station that pathing has already proven unusable during this task.</p>
     */
    public static Optional<BlockPos> nearestCompatibleFurnace(
            AIPlayerEntity bot,
            Item input,
            Item output,
            int requestedItems,
            Set<BlockPos> excluded) {
        BlockPos origin = bot.blockPosition();
        Set<BlockPos> rejected = excluded == null ? Set.of() : Set.copyOf(excluded);
        int itemCount = Math.max(1, requestedItems);
        return BlockPos.betweenClosedStream(origin.offset(-FURNACE_RADIUS, -3, -FURNACE_RADIUS),
                        origin.offset(FURNACE_RADIUS, 4, FURNACE_RADIUS))
                .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> !rejected.contains(pos))
                .filter(pos -> isCompatibleFurnace(bot, pos, input, output))
                .map(BlockPos::immutable)
                .min(Comparator
                        .comparingLong((BlockPos pos) -> estimatedCompletionTicks(
                                pos, origin, bot.level().getBlockState(pos), input, itemCount))
                        .thenComparingDouble(pos -> pos.distSqr(origin)));
    }

    public static boolean hasNearbyCompatibleFurnace(AIPlayerEntity bot, Item input, Item output) {
        return nearestCompatibleFurnace(bot, input, output).isPresent();
    }

    public static boolean isCompatibleFurnace(
            AIPlayerEntity bot, BlockPos pos, Item input, Item output) {
        BlockState state = bot.level().getBlockState(pos);
        if (!isCompatibleFurnaceType(state, input)) {
            return false;
        }
        if (!(bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }
        ItemStack queuedInput = furnace.getItem(0);
        if (!queuedInput.isEmpty() && !queuedInput.is(input)) {
            return false;
        }
        ItemStack queuedOutput = furnace.getItem(2);
        return queuedOutput.isEmpty() || queuedOutput.is(output);
    }

    private static boolean isCompatibleFurnaceType(BlockState state, Item input) {
        if (state.is(Blocks.FURNACE)) {
            return true;
        }
        if (state.is(Blocks.SMOKER)) {
            return SmeltChain.RAW_FOODS.contains(input);
        }
        return state.is(Blocks.BLAST_FURNACE) && BLAST_FURNACE_INPUTS.contains(input);
    }

    private static long estimatedCompletionTicks(
            BlockPos pos, BlockPos origin, BlockState state, Item input, int itemCount) {
        long cooking = (long) cookingTicks(state, input) * itemCount;
        long walking = Math.round(Math.sqrt(pos.distSqr(origin))
                * ESTIMATED_WALK_TICKS_PER_BLOCK);
        return cooking + walking;
    }

    private static int cookingTicks(BlockState state, Item input) {
        if (state.is(Blocks.SMOKER) && SmeltChain.RAW_FOODS.contains(input)) {
            return FAST_COOK_TICKS;
        }
        if (state.is(Blocks.BLAST_FURNACE) && BLAST_FURNACE_INPUTS.contains(input)) {
            return FAST_COOK_TICKS;
        }
        return NORMAL_COOK_TICKS;
    }

    private static Optional<BlockPos> nearestBlock(
            AIPlayerEntity bot, int horizontalRadius, java.util.function.Predicate<BlockState> matches) {
        BlockPos origin = bot.blockPosition();
        return BlockPos.betweenClosedStream(origin.offset(-horizontalRadius, -3, -horizontalRadius),
                        origin.offset(horizontalRadius, 4, horizontalRadius))
                .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> matches.test(bot.level().getBlockState(pos)))
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
    }
}
