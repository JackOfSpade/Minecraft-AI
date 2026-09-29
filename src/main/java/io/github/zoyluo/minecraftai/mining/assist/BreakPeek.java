package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.observe.BotProfiler;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The exact, cheap peek after a block break (mining-assist design 3.3). A break exposes up to six
 * new faces the sweep would only reach by chance, so the coordinator drains the pending-break ring
 * ({@link MiningAssistHooks#onBotBreak}) a few cells per tick. Every cell it looks at goes through
 * {@code OreScan.observe}, the same proof the mining tasks use: the cell must be observable through the bot's
 * ordinary perception before its state is looked at, and an unobservable cell is skipped and stays unknown.
 * Nothing here reads the world directly.
 *
 * <p><b>The broken cell is looked at too.</b> The break hook fires when the bot has finished its side of the
 * break, not when the world has confirmed it (a protected region or a restriction can refuse it), so the peek
 * never assumes the cell became air. It observes the cell and folds what it really holds: only a cell observed
 * as air (or fluid that flowed in) becomes AIR in the occupancy window and enters the bot's dug ring; a cell
 * that still holds its block is folded like any first hit, and a cell that cannot be observed is left
 * unknown and counted as {@code breaks_unconfirmed}.</p>
 *
 * <p><b>Neighbours.</b> Each observed neighbour gets the same folds as a first hit (valuables to the ledger,
 * lava, water and traps to the hazard field, non-natural blocks to the POI window) plus its occupancy mark. A
 * neighbour that is open space (air or a fluid), was not dug by the bot itself and was <em>not already seen
 * as open space</em> is a breakthrough: the sweep restarts with a fresh rotation at the raised ray rate for
 * one sweep, at most once per {@value MiningAssistState#MIN_BREAKTHROUGH_GAP_TICKS} ticks. Space the sweep has
 * already seen is not new, so a cave wall that opens into a cavern the rays already crossed costs nothing.</p>
 */
public final class BreakPeek {
    /** Breaks drained per bot per tick (design 3.3). */
    public static final int MAX_BREAKS_PER_TICK = 4;

    private BreakPeek() {
    }

    /**
     * Drains at most {@code maxBreaks} queued breaks of {@code state} and peeks at their cells. Returns how
     * many breaks were processed. Call only while the assist gate is open for the bot.
     */
    public static int drain(AIPlayerEntity bot, MiningAssistState state, int serverTick, int maxBreaks) {
        if (state.pendingBreaks().isEmpty() || maxBreaks <= 0) {
            return 0;
        }
        long started = System.nanoTime();
        BlockPos feet = bot.blockPosition();
        ObservedOccupancy occupancy = state.occupancy(feet.getX(), feet.getY(), feet.getZ());
        String dimension = BotEdits.dimensionKey(bot.level());
        state.enterDimension(dimension);
        LongPredicate placed = packed -> BotEdits.wasPlaced(dimension, packed);
        boolean lush = state.lush();
        BlockState[] seen = new BlockState[1];

        int processed = 0;
        while (processed < maxBreaks) {
            long packed = state.pendingBreaks().poll();
            if (packed == PendingBreakRing.EMPTY) {
                break;
            }
            processed++;
            peekOne(bot, state, occupancy, placed, lush, seen, BlockPos.of(packed), serverTick);
        }
        state.counters().peekedBreaks += processed;
        BotProfiler.INSTANCE.record(bot, ViewSweeper.SECTION_FOLD, System.nanoTime() - started);
        return processed;
    }

    private static void peekOne(AIPlayerEntity bot, MiningAssistState state, ObservedOccupancy occupancy,
                                LongPredicate placed, boolean lush, BlockState[] seen,
                                BlockPos broken, int tick) {
        peekBrokenCell(bot, state, occupancy, placed, lush, seen, broken, tick);

        boolean breakthrough = false;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = broken.relative(direction);
            seen[0] = null;
            OreScan.Observation observation = OreScan.observe(bot, neighbour, candidate -> {
                seen[0] = candidate;
                return true;
            });
            state.counters().peekNeighbours++;
            BlockState observed = seen[0];
            if (observation != OreScan.Observation.OBSERVED_PRESENT || observed == null) {
                continue;
            }
            int before = occupancy.get(neighbour);
            BlockFacts facts = BlockFactsAdapter.of(observed, lush);
            EvidenceFold.foldHit(state, neighbour, facts, BlockFactsAdapter.fluidKind(observed), placed, tick);
            boolean open = isOpenSpace(observed);
            markOccupancy(occupancy, neighbour, observed);
            if (open && !alreadySeenOpen(before) && !BotEdits.wasDug(bot, neighbour)) {
                breakthrough = true;
            }
        }
        if (breakthrough) {
            state.requestBreakthrough(tick);
        }
    }

    /** Observes the broken cell itself and folds what it really holds (see the class comment). */
    private static void peekBrokenCell(AIPlayerEntity bot, MiningAssistState state, ObservedOccupancy occupancy,
                                       LongPredicate placed, boolean lush, BlockState[] seen, BlockPos broken, int tick) {
        seen[0] = null;
        OreScan.Observation observation = OreScan.observe(bot, broken, candidate -> {
            seen[0] = candidate;
            return true;
        });
        BlockState observed = seen[0];
        if (observation != OreScan.Observation.OBSERVED_PRESENT || observed == null) {
            state.counters().breaksUnconfirmed++;
            return;
        }
        BlockFacts facts = BlockFactsAdapter.of(observed, lush);
        EvidenceFold.foldHit(state, broken, facts, BlockFactsAdapter.fluidKind(observed), placed, tick);
        markOccupancy(occupancy, broken, observed);
        if (isOpenSpace(observed)) {
            BotEdits.noteDug(bot, broken);
        } else {
            state.counters().breaksUnconfirmed++;
        }
    }

    private static boolean isOpenSpace(BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock;
    }

    /** Space a ray or an earlier peek already saw as open (air or fluid) is not new. */
    static boolean alreadySeenOpen(int occupancyBefore) {
        return occupancyBefore == ObservedOccupancy.AIR || occupancyBefore == ObservedOccupancy.FLUID;
    }

    private static void markOccupancy(ObservedOccupancy occupancy, BlockPos pos, BlockState observed) {
        if (observed.isAir()) {
            occupancy.markAir(pos);
        } else if (BlockFactsAdapter.holdsFluid(observed)) {
            occupancy.markFluid(pos);
        } else if (observed.canOcclude()) {
            occupancy.markSolid(pos);
        }
    }
}
