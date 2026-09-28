package io.github.zoyluo.minecraftai.pathfinding;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.OreScan;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NeighborEnumerator {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };

    private final AIPlayerEntity bot;
    private final boolean canPillar;
    private final boolean allowDig;
    private BlockPos pathGoal; // Goal cell: used for the lava preflight exemption (lava adjacent to the goal is sealed off by the task layer; the only entrance to a cell must not be left unsolvable)
    // digEnterable()/adjacentHazardFluid() get asked about the same neighbor cell repeatedly within
    // a single tick by the same batch of candidate cells (especially noticeable during dig-through
    // flood fills), and OreScan.observeDangerFluid's observability check has to cast a real ray —
    // it can't be cached globally like Standability (the result depends on the bot's live eye
    // position/facing). This instance only serves a single findPath() call; since the world and the
    // bot's position don't change during the search, memoizing the result per cell is safe, and it
    // cuts the repeated raycast cost down to "at most one query per cell".
    private final Map<BlockPos, OreScan.Observation> hazardObservationCache = new HashMap<>();

    public NeighborEnumerator(AIPlayerEntity bot) {
        this(bot, false, true);
    }

    // NAV-9: when canPillar=true, allow the "place a block to rise" neighbor (only passed in by A* when the bot's inventory has a placeable block).
    public NeighborEnumerator(AIPlayerEntity bot, boolean canPillar) {
        this(bot, canPillar, true);
    }

    // NAV-OPT: when allowDig=false, **disable DIG_THROUGH neighbors** — search only on air cells,
    // i.e. "pure walking". Used for the first phase of two-phase pathfinding: most movement can
    // reach its destination by pure walking alone, so the search space is small (air cells only)
    // and converges fast; whereas enabling dig-through treats every adjacent solid block as a
    // neighbor, degrading the search into "3D volumetric diffusion" that, when trapped or
    // underground, very easily blows the SEARCH_LIMIT (this is the root cause behind moves as
    // short as 5 blocks hitting SEARCH_LIMIT, confirmed in testing). Only fall back to the
    // second, dig-through phase once pure walking has no solution.
    //
    // bot is only used for digEnterable()'s lava/water observation gate (may be null — without a
    // bot there's no way to prove a cell has "been observed", so adjacentHazardFluid will honestly
    // mark every neighbor UNKNOWN and let it through, rather than falling back to an ungated raw
    // read).
    public NeighborEnumerator(AIPlayerEntity bot, boolean canPillar, boolean allowDig) {
        this.bot = bot;
        this.canPillar = canPillar;
        this.allowDig = allowDig;
    }

    public void setPathGoal(BlockPos goal) {
        this.pathGoal = goal;
    }

    public List<NeighborCandidate> getNeighbors(BlockPos current, ServerWorld world) {
        List<NeighborCandidate> result = new ArrayList<>(HORIZONTAL.length);
        for (Direction direction : HORIZONTAL) {
            BlockPos target = current.offset(direction);
            if (Standability.isStandable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.WALK, 0));
                continue;
            }

            BlockPos jumpTarget = target.up();
            if (canJumpOnto(world, current, target) && Standability.isStandable(world, jumpTarget)) {
                result.add(new NeighborCandidate(jumpTarget, MoveType.JUMP_UP, 0));
                continue;
            }

            NeighborCandidate drop = findDrop(world, target);
            if (drop != null) {
                result.add(drop);
                continue;
            }

            // NAV-BRIDGE: horizontal bridging across a gap. target isn't walkable (not standable),
            // findDrop couldn't find a safe landing either (the gap is too deep / too far to drop
            // safely), but target and the cell above it really are open air (not a wall, just no
            // floor) — place a block directly under target and walk across it. Shares the same
            // canPillar gate as addPillar (both consume a block from the inventory).
            if (canPillar && bridgeable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.BRIDGE, 0));
                continue;
            }

            if (allowDig && digEnterable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.DIG_THROUGH, 0));
            }
            // Diagonal-up dig-climb (the upward vertical component of DIG): target = the neighbor
            // cell one block higher; mine open its foot and head cells, then jump in. Only generated
            // when the bot's own headroom is already clear (the executor only mines the target's two
            // cells, not the bot's own headroom above it) — good enough for slopes/open-air climbs;
            // fully enclosed vertical shafts going up are left to pillar. Fixes geo_slope: ore inside
            // a slope (3 blocks up) can never be reached by a horizontal DIG alone.
            BlockPos upTarget = target.up();
            if (allowDig && digEnterable(world, upTarget) && collisionEmpty(world, current.up(2))) {
                result.add(new NeighborCandidate(upTarget, MoveType.DIG_THROUGH, 0));
            }
        }
        // Vertical down dig-drop (the downward vertical component of DIG): mine open the cell
        // underfoot and drop down onto solid footing. Fixes the geo_deep/buried-ore family: when the
        // ore is several cells straight down, a horizontal DIG flood-fill on the current layer can
        // never reach it (confirmed by testing to be the same root cause as ore_dig_buried/deep).
        if (allowDig) {
            BlockPos below = current.down();
            if (isMineable(world, below) && !collisionEmpty(world, below.down())) {
                result.add(new NeighborCandidate(below, MoveType.DIG_THROUGH, 0));
            }
        }
        addDiagonals(current, world, result);
        addPillar(current, world, result);
        return result;
    }

    // NAV-3: same-height diagonal movement. Only allowed when the target cell is standable and both orthogonally adjacent cells are "passable" (no cutting through a wall corner).
    private static void addDiagonals(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        Direction[][] pairs = {
                {Direction.NORTH, Direction.EAST},
                {Direction.NORTH, Direction.WEST},
                {Direction.SOUTH, Direction.EAST},
                {Direction.SOUTH, Direction.WEST}
        };
        for (Direction[] pair : pairs) {
            BlockPos diag = current.offset(pair[0]).offset(pair[1]);
            if (!Standability.isStandable(world, diag)) {
                continue;
            }
            if (!passableColumn(world, diag)) {
                continue;
            }
            if (!passableColumn(world, current.offset(pair[0])) || !passableColumn(world, current.offset(pair[1]))) {
                continue;
            }
            result.add(new NeighborCandidate(diag, MoveType.DIAGONAL, 0));
        }
    }

    // NAV-9: rise one cell by placing a block (in place). The bot places a block underfoot and jumps onto it. Requires two clear cells of headroom.
    private void addPillar(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        if (!canPillar) {
            return;
        }
        BlockPos up1 = current.up();
        BlockPos up2 = current.up(2);
        // up1 = the new foot position (currently the head position, should be empty); up2 = the new head position, needs to be clear
        if (collisionEmpty(world, up1) && collisionEmpty(world, up2) && !Standability.isDangerous(world.getBlockState(up1))) {
            result.add(new NeighborCandidate(up1, MoveType.PILLAR_UP, 0));
        }
    }

    // NAV-BRIDGE: only counts as a "gap" when both the target cell and the one above it are truly
    // open air (collision fully empty) — not a case where a solid wall blocks headroom. Water/lava
    // also have fully empty collision, but are never a "gap" — following the same dry-land contract
    // as Standability, fluid cells are always excluded, otherwise a lake surface/lava pool would be
    // misjudged as open, bridgeable air and walk the bot straight into water or lava.
    private static boolean bridgeable(ServerWorld world, BlockPos target) {
        return collisionEmpty(world, target) && collisionEmpty(world, target.up())
                && world.getBlockState(target).getFluidState().isEmpty()
                && world.getBlockState(target.up()).getFluidState().isEmpty();
    }

    private static boolean collisionEmpty(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean passableColumn(ServerWorld world, BlockPos feet) {
        return collisionEmpty(world, feet) && collisionEmpty(world, feet.up());
    }

    private static boolean canJumpFrom(ServerWorld world, BlockPos current) {
        return collisionEmpty(world, current.up()) && collisionEmpty(world, current.up(2));
    }

    private static boolean canJumpOnto(ServerWorld world, BlockPos current, BlockPos front) {
        if (!canJumpFrom(world, current)) {
            return false;
        }
        BlockState frontState = world.getBlockState(front);
        if (frontState.getCollisionShape(world, front).isEmpty()) {
            return false;
        }
        if (frontState.getCollisionShape(world, front).getMax(Direction.Axis.Y) > 1.0D) {
            return false;
        }
        return collisionEmpty(world, front.up()) && collisionEmpty(world, front.up(2));
    }

    private static NeighborCandidate findDrop(ServerWorld world, BlockPos target) {
        if (!collisionEmpty(world, target)) {
            return null;
        }
        if (!collisionEmpty(world, target.up())) {
            return null;
        }
        int maxFall = MinecraftAiConfig.get().nav().maxSafeFall();
        for (int fall = 1; fall <= maxFall; fall++) {
            BlockPos landing = target.down(fall);
            if (Standability.isStandable(world, landing)) {
                return new NeighborCandidate(landing, MoveType.DROP_DOWN, fall);
            }
            if (!collisionEmpty(world, landing)) {
                return null;
            }
        }
        return null;
    }

    // DIG-enterable: the foot and head cells must each be "mineable or already passable" (but not
    // both fully empty — fully empty is WALK/JUMP territory), and there must be support underfoot
    // (so the bot can stand after mining). Fixes the "empty foot, solid head" dead end: when the
    // goal is directly below an ore block, the stand position is air and the headspace is ore; the
    // original isMineable required the foot cell to be non-air → all four neighbor kinds get
    // rejected, the goal node never enters the queue, and A* flood-fills tens of thousands of cells
    // before TIMEOUT (confirmed by testing on geo_wall).
    private boolean digEnterable(ServerWorld world, BlockPos target) {
        BlockPos head = target.up();
        boolean footOpen = collisionEmpty(world, target);
        boolean headOpen = collisionEmpty(world, head);
        if (footOpen && headOpen) {
            return false;
        }
        boolean footOk = footOpen || isMineable(world, target);
        boolean headOk = headOpen || isMineable(world, head);
        if (!footOk || !headOk || collisionEmpty(world, target.down())) {
            return false;
        }
        // P0 safety preflight (the #1 cause of death in deep mining): after mining open these two
        // cells, lava from the side/above could pour in — Y=-59, the diamond layer, is also the lava
        // layer, and this is the most common way players die mining diamonds in practice. If either
        // the foot or head cell has an exposed face adjacent to lava/water → don't mine this path,
        // and let A* naturally route around it.
        //
        // Strict-survival gate (companion fix to the DigDownTask/DescendToYTask x-ray closed in
        // a0c4edd): adjacentHazardFluid() below only rejects a direction on a hazard that is
        // ALREADY genuinely observable through the bot's own eyes right now (an open pocket, a
        // previously mined cavity, or a naturally exposed face) via OreScan.observeDangerFluid's
        // ObservableWorldQuery gate. A neighbour still hidden behind unmined rock reports UNKNOWN
        // and is never treated as a hazard here — that used to be unsound (the earlier code read
        // raw, un-mined fluid state with no gate at all, letting the bot "see" lava through solid
        // rock it had never observed). Leaving an unknown cell unrejected is safe now because
        // PathExecutor.tickDigThrough() (the sole executor of MoveType.DIG_THROUGH) reactively
        // re-checks every newly-exposed neighbour the instant mining actually opens each cell, and
        // aborts/replans on a real hazard there — see the comment on that method. This preflight is
        // therefore a proactive best-effort optimization (avoid a route the bot can already see is
        // dangerous), not the safety boundary; the reactive check is.
        boolean isGoal = pathGoal != null && (target.equals(pathGoal) || head.equals(pathGoal));
        if (!isGoal && (adjacentHazardFluid(target) || adjacentHazardFluid(head))) {
            return false; // Goal-cell exemption: ore adjacent to lava is still reachable — the task layer seals the lava before mining (ore_dig_lava_seal)
        }
        // P0 sand/gravel collapse preflight: if the cell above the head position is suspended sand/gravel (FallingBlock) → mining it triggers a chain fall, which can knock out/suffocate the bot and refill the tunnel.
        if (world.getBlockState(head.up()).getBlock() instanceof net.minecraft.block.FallingBlock) {
            return false;
        }
        return true;
    }

    // Hazardous fluid on an exposed face (lava/water): dangerous if any of the four horizontal
    // neighbors or the cell above has an observed hazardous fluid (the cell below is guaranteed
    // solid by target.down, so no leak there). See the gating explanation above digEnterable():
    // only reject when the hazard has actually been observed; any unobserved neighbor is always
    // let through as UNKNOWN, with PathExecutor.tickDigThrough()'s reactive recheck as the backstop.
    private boolean adjacentHazardFluid(BlockPos pos) {
        if (isObservedHazardFluid(pos.up())) {
            return true;
        }
        for (Direction d : HORIZONTAL) {
            if (isObservedHazardFluid(pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private boolean isObservedHazardFluid(BlockPos pos) {
        return cachedHazardObservation(pos) == OreScan.Observation.OBSERVED_PRESENT;
    }

    private OreScan.Observation cachedHazardObservation(BlockPos pos) {
        return hazardObservationCache.computeIfAbsent(
                pos.toImmutable(), p -> OreScan.observeDangerFluid(bot, p));
    }

    private static boolean hasHeadroom(ServerWorld world, BlockPos target) {
        // Head position under digging semantics: already empty OR mineable (the executor
        // tickDigThrough mines open both the foot and head cells). The original rule "the two cells
        // above the head must already be empty" judged tunneling through a solid mountain as
        // impassable — every step's head cell was stone, so not a single DIG neighbor could ever be
        // generated, which is exactly the root cause behind geo_slope/wall/pocket all getting stuck
        // on no_progress (dig-pathfinding could only scrape shallow pits along the ground, never
        // tunnel through a mountain).
        BlockPos head = target.up();
        return collisionEmpty(world, head) || isMineable(world, head);
    }

    private static boolean isMineable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getHardness(world, pos) < 0.0F || world.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            return false;
        }
        // The ore block itself is mineable (paired with the goal exemption: the target ore cell needs to be reachable on the path; OreScan also covers modded _ore suffixes).
        if (io.github.zoyluo.minecraftai.mining.OreScan.isOreBlock(state.getBlock())) {
            return true;
        }
        return state.isIn(BlockTags.STONE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DIRT)
                || state.isOf(Blocks.STONE)
                || state.isOf(Blocks.COBBLESTONE)
                || state.isOf(Blocks.GRANITE)
                || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.ANDESITE)
                || state.isOf(Blocks.SAND)
                || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.GRAVEL);
    }
}
