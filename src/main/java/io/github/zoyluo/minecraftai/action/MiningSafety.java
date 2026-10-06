package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.BreakRule;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/**
 * Live safety checks shared by every direct and route-driven block-break controller.
 *
 * <p>A support block is not an expendable mining target while any real player or bot's live foot
 * collision overlaps it. This is deliberately based on current player positions rather than a
 * remembered placement: a player may stand on natural stone just as easily as on a bot-built
 * bridge, including across a slab or block seam.</p>
 *
 * <p>It also says whether a cell may be opened by a digger who tunnels through rock, from nothing
 * but what the digger has seen ({@link #openingRefusal(OpeningView, BlockPos)}).</p>
 */
public final class MiningSafety {
    /** Typed refusal when the acting bot must first leave the target's support footprint. */
    public static final String SELF_SUPPORT = "self_support";
    /** Typed refusal when another live player occupies the target's support footprint. */
    public static final String PLAYER_SUPPORT = "player_support";
    /** Typed refusal when opening a cell would let a fluid that was seen flow into it. */
    public static final String ADJACENT_FLUID = "adjacent_fluid";
    /** Typed refusal when a falling block was seen directly over the cell, or is the cell. */
    public static final String GRAVITY = "gravity";

    /** Which live actor, if any, is physically supported by a prospective break target. */
    public enum SupportOccupancy {
        NONE,
        SELF,
        PLAYER
    }

    private MiningSafety() {
    }

    /**
     * What the digger can see around a cell it is about to open: the seam that keeps the verdict of
     * {@link #openingRefusal(OpeningView, BlockPos)} a pure function of observations, so it can be
     * tested without a world and can never read a cell the digger has not seen.
     */
    public interface OpeningView {
        /** Whether the cell, or a face of the block in it, is in the digger's view right now. */
        boolean observed(BlockPos pos);

        /**
         * Whether a fluid in the cell would be seen: the cell, or a face of it, including the inset
         * faces through which a thin fluid surface shows. At least what {@link #observed} sees.
         */
        default boolean observedForFluid(BlockPos pos) {
            return observed(pos);
        }

        /** The state of a cell for which {@link #observed} or {@link #observedForFluid} holds; never asked for any other. */
        BlockState stateAt(BlockPos pos);

        /** Whether a live player other than the digger stands on the block in the cell. */
        boolean playerSupports(BlockPos pos);
    }

    /**
     * Why the actor must not open {@code cell} (mine it, or walk through it once it is open), or null
     * when nothing it can see forbids it. Built for a stair or tunnel dug through rock, where the
     * cells ahead are hidden until the cells before them are open: a cell that is not in view is
     * judged once it is, and what opening a cell reveals (the cell above it, its sides) is judged
     * before the next cell is.
     *
     * <ul>
     *   <li>the cell holds a fluid, or a falling block that would come down on the digger;</li>
     *   <li>the cell above holds one: a fluid floods the opened cell, a falling block drops into it;</li>
     *   <li>a fluid is seen beside, over or under the cell, <em>whether the cell is rock or already
     *       open</em>: an open cell next to a lava or water cell that an earlier opening revealed is
     *       the one the flow comes in through;</li>
     *   <li>the cell is rock that {@link BreakRule} does not let a bot dig, or that a player stands on.</li>
     * </ul>
     */
    public static String openingRefusal(OpeningView view, BlockPos cell) {
        if (!view.observed(cell)) {
            return null;
        }
        BlockState state = view.stateAt(cell);
        String hazard = openingHazard(state);
        if (hazard != null) {
            return hazard;
        }
        BlockPos above = cell.above();
        if (view.observed(above)) {
            hazard = openingHazard(view.stateAt(above));
            if (hazard != null) {
                return hazard;
            }
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = cell.relative(direction);
            if (view.observedForFluid(adjacent) && isDangerFluid(view.stateAt(adjacent).getFluidState())) {
                return ADJACENT_FLUID;
            }
        }
        if (state.isAir()) {
            return null;
        }
        String denial = BreakRule.legacyDenialOf(state);
        if (denial != null) {
            return "break_refused:" + denial;
        }
        return view.playerSupports(cell) ? PLAYER_SUPPORT : null;
    }

    /** {@link #openingRefusal(OpeningView, BlockPos)} for what {@code actor} sees of the world now. */
    public static String openingRefusal(AIPlayerEntity actor, BlockPos cell) {
        Level world = actor.level();
        return openingRefusal(new OpeningView() {
            @Override
            public boolean observed(BlockPos pos) {
                return ObservableWorldQuery.canObserveCell(actor, pos)
                        || ObservableWorldQuery.canObserveBlock(actor, pos);
            }

            @Override
            public boolean observedForFluid(BlockPos pos) {
                return observed(pos) || ObservableWorldQuery.canObserveBlockWithInsetFaces(actor, pos);
            }

            @Override
            public BlockState stateAt(BlockPos pos) {
                return world.getBlockState(pos);
            }

            @Override
            public boolean playerSupports(BlockPos pos) {
                return supportOccupancy(actor, pos) == SupportOccupancy.PLAYER;
            }
        }, cell);
    }

    /** Fluid, or a block that falls, in a cell that is about to be opened or sits over one. */
    private static String openingHazard(BlockState state) {
        if (isDangerFluid(state.getFluidState())) {
            return state.getFluidState().is(FluidTags.WATER) ? "water" : "lava";
        }
        return state.getBlock() instanceof FallingBlock ? GRAVITY : null;
    }

    private static boolean isDangerFluid(FluidState fluid) {
        return fluid.is(FluidTags.LAVA) || fluid.is(FluidTags.WATER);
    }

    /**
     * Returns the live support occupancy for {@code target}. Another player wins over the bot's
     * own occupancy: stepping aside cannot make it safe to break a floor that still holds a
     * human, so callers must leave that terrain alone and choose another route.
     */
    public static SupportOccupancy supportOccupancy(AIPlayerEntity actor, BlockPos target) {
        if (actor == null || target == null) {
            return SupportOccupancy.NONE;
        }
        boolean self = isSupporting(actor, target);
        for (ServerPlayer player : actor.level().players()) {
            if (player == actor || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (isSupporting(player, target)) {
                return SupportOccupancy.PLAYER;
            }
        }
        return self ? SupportOccupancy.SELF : SupportOccupancy.NONE;
    }

    /** True when {@code target} is supporting the acting bot or another live player. */
    public static boolean isOccupiedSupport(AIPlayerEntity actor, BlockPos target) {
        return supportOccupancy(actor, target) != SupportOccupancy.NONE;
    }

    /** Stable public failure/log reason for a support occupancy. */
    public static String refusalReason(SupportOccupancy occupancy) {
        return switch (occupancy) {
            case SELF -> SELF_SUPPORT;
            case PLAYER -> PLAYER_SUPPORT;
            case NONE -> "no_support";
        };
    }

    /**
     * Includes thin floors such as slabs and every block under a body straddling a seam.
     *
     * <p>{@link net.minecraft.world.level.CollisionGetter#findSupportingBlock} deliberately
     * returns only one nearest collision block. That makes it useful for normal movement, but
     * unsafe as a mining veto: a player whose feet overlap two blocks can be supported by either
     * one, while the nearest-block tie break exposes the other to a break. Ask the same
     * collision iterator for <em>all</em> shapes intersecting the player's infinitesimal foot
     * slice instead. This is local live body geometry, not an unguarded target-state lookup.</p>
     */
    private static boolean isSupporting(ServerPlayer player, BlockPos target) {
        AABB body = player.getBoundingBox();
        AABB footProbe = new AABB(body.minX, body.minY - 1.0E-6D, body.minZ,
                body.maxX, body.minY, body.maxZ);
        BlockCollisions<Boolean> supports = new BlockCollisions<>(
                player.level(), player, footProbe, false,
                (position, ignoredShape) -> target.equals(position));
        while (supports.hasNext()) {
            if (supports.next()) {
                return true;
            }
        }
        return false;
    }
}
