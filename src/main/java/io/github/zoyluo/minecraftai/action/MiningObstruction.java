package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mining.BreakRule;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.ReachObstructions;
import io.github.zoyluo.minecraftai.mode.ReachObstructions.Line;
import io.github.zoyluo.minecraftai.mode.ReachObstructions.Obstruction;
import io.github.zoyluo.minecraftai.mode.SeeThrough;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A block the bot has seen through leaves, a fence or a pane can only be broken once those are out of the way, and this decides
 * what to do about it. A hand needs a clear line, so when the strict proof (the vanilla pick ray: {@link
 * MiningController#currentObservedTarget}) finds none but the bot's eyes do see the block, the plan names the see-through block to
 * break first: the one nearest the eye on the best line, that being the line with the fewest see-through blocks on it, then the one
 * whose first block is nearest ({@link ReachObstructions} lists them per line, exactly as the strict gate would meet them). A block
 * seen through water needs nothing cleared: a hand passes water, so the target is mined as a player mines it from the shore. It is
 * re-planned after every break, so each step removes one real block and the target itself is only started once the strict proof
 * passes, never while an intact see-through block is what a hand would hit.
 *
 * <p>What may be broken is the mod-wide {@link BreakRule}: natural terrain, which for something in the way means leaves and the
 * small plants that grow in the way. A fence, a gate, glass, a pane, bars, a ladder or a chain was put there by somebody, so a
 * line that crosses one is never cleared: the line is skipped, even if a leaf stands in front of it, and when no line is left the
 * target is refused with the typed {@link MiningController#TARGET_OBSTRUCTED}. The same goes for a block the bot or another player
 * stands on, a block next to lava the bot can see (breaking it would let the lava out) or water that would flow into its cell, and one this very operation already broke
 * and that is still there (a break the server did not carry out must not loop). Whatever it breaks, the bot must also be able to
 * reach it with the strict proof and within arm's length, so the step that follows cannot be refused.</p>
 */
final class MiningObstruction {
    enum Kind {
        /** The strict proof passes: nothing to clear, mine the target. */
        REACHABLE,
        /** Break {@link Plan#obstruction()} first. */
        CLEAR,
        /** The eyes see the target but every line is crossed by a block that may not be broken. */
        PROTECTED,
        /** No line to the target that only see-through blocks hide: not seen, or hidden by something opaque or by lava. */
        NOT_SEEN
    }

    /**
     * @param obstruction the block to break next ({@link Kind#CLEAR}), or the first block that may not be broken ({@link Kind#PROTECTED})
     * @param reason      why it may not ({@link Kind#PROTECTED})
     * @param remaining   how many see-through blocks the chosen line has in all, the one to break included ({@link Kind#CLEAR})
     * @param targetBlock registry id of the target, read once the eyes had proved the cell
     */
    record Plan(Kind kind, Obstruction obstruction, String reason, int remaining, String targetBlock) {
        static final Plan REACHABLE = new Plan(Kind.REACHABLE, null, null, 0, null);
        static final Plan NOT_SEEN = new Plan(Kind.NOT_SEEN, null, null, 0, null);

        /** Whether the break cannot start from here, the one case a caller turns into a typed failure. */
        boolean refused() {
            return kind == Kind.PROTECTED || kind == Kind.NOT_SEEN;
        }

        /** The typed failure of a refused plan, {@code null} otherwise. */
        String refusal() {
            return switch (kind) {
                case PROTECTED -> MiningController.TARGET_OBSTRUCTED;
                case NOT_SEEN -> MiningController.TARGET_NOT_OBSERVED;
                case REACHABLE, CLEAR -> null;
            };
        }
    }

    /** The neighbours water can flow into a cleared cell from. */
    private static final Direction[] WATER_FEEDS = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private MiningObstruction() {
    }

    /**
     * What a break of {@code target} needs from here, once the strict proof has failed. {@code cleared} are the blocks this
     * operation has already broken for it.
     */
    static Plan plan(AIPlayerEntity player, BlockPos target, Collection<BlockPos> cleared) {
        // The strict proof starts state-free, and so does this one, with sight: it earns the read of the target's state below.
        if (!(ObservableWorldQuery.canObserveBlockCellFace(player, target) || ObservableWorldQuery.canObserveCell(player, target))) {
            return Plan.NOT_SEEN;
        }
        Vec3 eye = player.getEyePosition();
        double reach = reach(player);
        if (eye.distanceTo(target.getCenter()) > reach) {
            return Plan.NOT_SEEN; // out of arm's length: clearing a leaf for a block that cannot be mined from here would be wasted
        }
        BlockState state = player.level().getBlockState(target);
        if (state.isAir()) {
            return Plan.NOT_SEEN;
        }
        List<Line> lines = ReachObstructions.lines(player.level(), CollisionContext.of(player), player.blockPosition(), eye, target);
        return choose(lines, eye, obstruction -> refusalOf(player, obstruction, cleared),
                obstruction -> breakableFromHere(player, obstruction, reach), blockId(state));
    }

    /**
     * The pure choice: among the lines that have something to clear, the one with the fewest see-through blocks, then the one whose
     * first block is nearest the eye (ties keep the order of the lines, so the choice is stable). A line is only worth starting when
     * every block on it may be broken ({@code refusalOf} returns {@code null}); its first block must also be {@code breakable}
     * now. A line that is already clear is not a line to clear: the strict proof failed for another reason, and breaking
     * something would not help.
     */
    static Plan choose(List<Line> lines, Vec3 eye, Function<Obstruction, String> refusalOf,
                       Predicate<Obstruction> breakable, String targetBlock) {
        Map<BlockPos, String> verdicts = new HashMap<>();
        Function<Obstruction, String> refusal = obstruction ->
                verdicts.computeIfAbsent(obstruction.pos(), pos -> {
                    String denial = refusalOf.apply(obstruction);
                    return denial == null ? "" : denial;
                });
        Obstruction protectedBlock = null;
        String protectedReason = null;
        List<Line> ordered = lines.stream()
                .filter(line -> !line.obstructions().isEmpty())
                .sorted(Comparator.<Line>comparingInt(line -> line.obstructions().size())
                        .thenComparingDouble(line -> eye.distanceToSqr(line.obstructions().get(0).pos().getCenter())))
                .toList();
        for (Line line : ordered) {
            Obstruction denied = null;
            String reason = "";
            for (Obstruction obstruction : line.obstructions()) {
                reason = refusal.apply(obstruction);
                if (!reason.isEmpty()) {
                    denied = obstruction;
                    break;
                }
            }
            if (denied != null) {
                if (protectedBlock == null) {
                    protectedBlock = denied;
                    protectedReason = reason;
                }
                continue;
            }
            Obstruction next = line.obstructions().get(0);
            if (breakable.test(next)) {
                return new Plan(Kind.CLEAR, next, null, line.obstructions().size(), targetBlock);
            }
        }
        return protectedBlock == null ? Plan.NOT_SEEN : new Plan(Kind.PROTECTED, protectedBlock, protectedReason, 0, targetBlock);
    }

    private static double reach(AIPlayerEntity player) {
        // The same arm's length MiningController.tick enforces before it starts a break.
        return player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 0.5D;
    }

    /** Null if the bot may break {@code obstruction} to get at the target, else the typed reason it may not. */
    private static String refusalOf(AIPlayerEntity player, Obstruction obstruction, Collection<BlockPos> cleared) {
        if (cleared.contains(obstruction.pos())) {
            return "obstruction_persisted"; // this operation broke it already and it is still there: the break did not happen
        }
        String denial = BreakRule.denialOf(obstruction.state());
        if (denial != null) {
            return denial;
        }
        MiningSafety.SupportOccupancy support = MiningSafety.supportOccupancy(player, obstruction.pos());
        if (support != MiningSafety.SupportOccupancy.NONE) {
            return MiningSafety.refusalReason(support);
        }
        if (exposesLava(player, obstruction.pos())) {
            return "exposes_lava";
        }
        // A waterlogged leaf leaves its own water in the cell whatever its neighbours hold: nothing new flows in.
        boolean wet = obstruction.state().getFluidState().is(FluidTags.WATER);
        return !wet && exposesWater(player, obstruction.pos()) ? "exposes_water" : null;
    }

    /** Whether a neighbour of {@code pos} that the bot can see holds lava: breaking {@code pos} would let it out. */
    private static boolean exposesLava(AIPlayerEntity player, BlockPos pos) {
        return visibleNeighbourHolds(player, pos, FluidTags.LAVA, Direction.values());
    }

    /**
     * Whether a neighbour of {@code pos} that the bot can see holds water that would flow into it once it is broken. Water runs
     * down and sideways, never up, so only the cell above and the four beside it count. A flood would not stop the break (a hand
     * passes water) but it destroys a block for a log and leaves a stream behind, so the bot looks for a line that does not.
     */
    private static boolean exposesWater(AIPlayerEntity player, BlockPos pos) {
        return visibleNeighbourHolds(player, pos, FluidTags.WATER, WATER_FEEDS);
    }

    private static boolean visibleNeighbourHolds(AIPlayerEntity player, BlockPos pos, TagKey<Fluid> fluid, Direction[] directions) {
        for (Direction direction : directions) {
            BlockPos neighbour = pos.relative(direction);
            if (ObservableWorldQuery.canObserveCell(player, neighbour) && player.level().getFluidState(neighbour).is(fluid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a hand gets past a block that stands on the line to a target: water (a hand passes it) or a see-through block the bot
     * breaks first (leaves and the small plants of the {@link BreakRule}). A planner that judges a stance before the bot stands in it
     * asks this of what it has seen on the line, so that it does not rule out a stance whose line only leaves and water cross, which
     * this class would clear or pass. Anything else (a fence, a pane, a ledge, a wall) is in the way, or the plan would refuse it.
     */
    static boolean handGetsPast(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.WATER)) {
            return true;
        }
        return SeeThrough.cell(state) && BreakRule.denialOf(state) == null;
    }

    /** The step after this plan is a normal break of {@code obstruction}: within arm's length and passing the strict proof. */
    private static boolean breakableFromHere(AIPlayerEntity player, Obstruction obstruction, double reach) {
        return player.getEyePosition().distanceTo(obstruction.pos().getCenter()) <= reach
                && MiningController.currentObservedTarget(player, obstruction.pos());
    }

    static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** The bot found a see-through block in the way and starts breaking it. */
    static void logDetected(AIPlayerEntity player, BlockPos target, Plan plan) {
        BotLog.action(player, "mining_obstruction_detected",
                "pos", LogFields.pos(target),
                "block", plan.targetBlock(),
                "obstruction", LogFields.pos(plan.obstruction().pos()),
                "obstruction_block", blockId(plan.obstruction().state()),
                "obstructions", plan.remaining());
    }

    /** One see-through block of the way is gone; the line is re-planned from the strict proof. */
    static void logCleared(AIPlayerEntity player, BlockPos target, Obstruction cleared, int ticks) {
        BotLog.action(player, "mining_obstruction_cleared",
                "pos", LogFields.pos(target),
                "obstruction", LogFields.pos(cleared.pos()),
                "obstruction_block", blockId(cleared.state()),
                "ticks", ticks);
    }

    /** The target cannot be mined from here: a block in the way may not be broken, or could not be. */
    static void logRefused(AIPlayerEntity player, BlockPos target, Obstruction obstruction, String reason) {
        BotLog.action(player, "mining_obstruction_refused",
                "pos", LogFields.pos(target),
                "obstruction", LogFields.pos(obstruction.pos()),
                "obstruction_block", blockId(obstruction.state()),
                "reason", reason);
    }
}
