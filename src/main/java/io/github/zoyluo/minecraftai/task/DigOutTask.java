package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BreakEffort;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.GatherToolPolicy;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.MiningSafety;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.craft.CraftingHelper;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Digs a stair up out of the dark, the way a player with a pickaxe and no torch leaves a cave pocket.
 * It is what a bot stuck in the dark does when it has nothing to light the cell with and cannot be
 * teleported out ({@link DangerWatcher}): climb one block per step, a block forward and a block up,
 * until the cell it stands in is no longer a dark trap, which is any cell with light enough to spawn
 * nothing and any cell under open sky ({@link DangerWatcher#isDarkTrapCell}). The surface is never
 * lit; it is simply where the stair ends.
 *
 * <p>The stair is {@link OreClimb}'s rise, repeated: each step opens the cell over the bot's head and
 * the landing with its head cell, and lands on a cell that was never dug, so it always has a floor
 * and the way back down is the stair itself. A rise needs a wall to start in, so a bot standing in
 * the middle of a room first walks to the nearest wall it can see. A step is opened only when what
 * the bot can see allows it ({@link StairDig}: no fluid or falling block over, beside or in the
 * cells, nothing a bot may not dig, no player standing on it); a heading that is refused is turned
 * from, to the right, then the left, then back, and the task ends when all four are. Every rise
 * gains a block, and a walk to a wall never goes back onto a cell the bot has stood in since the
 * last rise, so it cannot go in circles; it ends at the build limit if nothing else stops it.</p>
 *
 * <p>Nothing here knows where the sky is. Like a player in the dark it digs up, and what it learns on
 * the way (an open cave, a lit cell, the roof) is only what it sees. The one thing it does with what
 * it sees is to dig toward the brightest thing in view ({@link DigOutBearing}), when something is
 * brighter than the cell it stands in.</p>
 *
 * <p><b>A flow.</b> Opening a cell can show a lava or water cell that was hidden behind it, and the fluid
 * comes in through the opening. Like a player the bot puts a block it carries into that cell (the one
 * it opened, whether the fluid is in it already or is about to be) with the same placement as every
 * other sealing ({@link BuildAction#placeBlockAt}), never opens that cell again, and goes up another
 * way. With nothing to close it with, or when the block cannot be placed, it walks back down the stair
 * to where it started and ends. It never steps into, and never digs toward, a fluid it sees
 * ({@link StairDig}).</p>
 *
 * <p><b>The tool.</b> A cell is dug with the tool {@link ToolSelector} chooses for the block (the lowest
 * pickaxe that harvests it, never a sword), by hand when nothing suits. A bot with no pickaxe and
 * the makings of one makes it first, the way every other task does ({@link CraftTask}: table placed
 * and taken up again, planks and sticks crafted). A break the held tool cannot finish ({@link BreakEffort}: bedrock,
 * or a block that takes vanilla's own break time longer than {@link BlockMiner#MINE_TIMEOUT_TICKS})
 * is refused before the first swing with {@code break_refused:<reason>}, and the stair turns.</p>
 */
public final class DigOutTask extends AbstractTask {
    /**
     * A step opens at most three cells, and {@link BlockMiner} gives up on a cell after 200 ticks, so a
     * step that has made no progress after three of those is not going to.
     */
    private static final int STALL_TICKS = 600;
    /** The highest cell a rise opens is two over the bot's feet: the head cell of its landing. */
    private static final int RISE_HEAD_CELLS = 2;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** The reason of a step that was started and did not land, when nothing more specific is known. */
    private static final String STEP_FAILED = "step_failed";
    /** Typed refusal of a step through a cell that was closed against a flow: opening it again would let the flow in. */
    static final String SEALED_FLOW = "sealed_flow";
    /** Start of the reason a task ends with when a flow could not be closed: {@code dig_out_flow_unsealed:<why>}. */
    static final String FLOW_UNSEALED = "dig_out_flow_unsealed";

    private final BlockMiner miner = new BlockMiner();
    /** The steps that were started and did not land, each with the reason: never tried again from the same cell. */
    private final Map<String, String> failedSteps = new HashMap<>();
    /**
     * The cells stood in since the stair last rose. A walk to a wall never goes back onto one of them, so a room whose every wall
     * refuses the stair is crossed once and the task ends, instead of circling in it.
     */
    private final Set<BlockPos> visitedAtThisLevel = new HashSet<>();
    /** The cells this task broke: the only ones it closes again when a flow comes in through them. */
    private final Set<BlockPos> openedByUs = new HashSet<>();
    /** The cells closed against a flow: no step goes through one again. */
    private final Set<BlockPos> sealedCells = new HashSet<>();
    /** The cells the bot has stood in on the way, the first one first: the way back down the stair. */
    private final List<BlockPos> trail = new ArrayList<>();
    private BlockPos startCell;
    private Direction heading;
    /** The direction the brightest thing in sight lies in, or null when nothing seen is brighter than the bot's cell. */
    private Direction lit;
    /** The latest reason the rock itself was refused (anything but a missing floor), for the report when the task ends. */
    private String lastRockRefusal = StairDig.OPEN_DROP;
    private ActionPack.StepLease lease;
    private BlockPos stepOrigin;
    private BlockPos stepLanding;
    private boolean stepRises;
    private int lastProgressTick;
    private int risen;
    /** Set once a flow could not be closed: the reason the task ends with when the bot is back down the stair. */
    private String retreatReason;
    /** The pickaxe being made, or null. */
    private CraftTask toolCraft;
    /** Whether a pickaxe was made or found impossible to make: it is tried once. */
    private boolean toolSettled;

    @Override
    public String name() {
        return "dig_out";
    }

    @Override
    public String describe() {
        return "Digging a stair up out of the dark" + (startCell == null ? "" : " from " + startCell.toShortString());
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : 0.0D;
    }

    /** Standing still to mine, or to make the pickaxe, is this task's work, not a stall. */
    @Override
    public boolean isWaiting() {
        return miner.target() != null || toolCraft != null;
    }

    /** How many blocks the stair has gained so far. */
    int risen() {
        return risen;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        startCell = bot.blockPosition().immutable();
        heading = bot.getDirection();
        trail.add(startCell);
        resetMiner(bot);
        BotLog.action(bot, "dig_out_started", "at", startCell.toShortString(), "heading", heading.getSerializedName());
        takeBearing(bot, bot.level(), startCell);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (holdForStep(bot)) {
            return;
        }
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        visitedAtThisLevel.add(feet.immutable());
        if (!DangerWatcher.isDarkTrapCell(world, feet)) {
            BotLog.action(bot, "dig_out_done", "at", feet.toShortString(), "risen", risen);
            complete();
            return;
        }
        if (elapsed - lastProgressTick > STALL_TICKS) {
            end(bot, "dig_out_stalled");
            return;
        }
        if (retreatReason != null) {
            retreat(bot, world, feet);
            return;
        }
        if (toolCraft != null) {
            craftTool(bot);
            return;
        }
        if (feet.getY() + RISE_HEAD_CELLS > world.getMaxY()) {
            end(bot, "dig_out_build_limit");
            return;
        }
        Direction[] order = {heading, heading.getClockWise(), heading.getCounterClockWise(), heading.getOpposite()};
        for (Direction direction : order) {
            OreClimb.Move move = new OreClimb.Move(direction, true);
            String why = failedSteps.get(stepKey(feet, OreClimb.landing(feet, move)));
            if (why == null) {
                StairDig.Refusal refusal = StairDig.refusalAt(bot, world, feet, move);
                if (refusal != null && flowsIn(world, feet, move, refusal)) {
                    closeFlow(bot, feet, refusal);
                    return;
                }
                why = refusal != null ? refusal.reason() : touchesSealedCell(feet, move) ? SEALED_FLOW : null;
            }
            if (why == null) {
                if (direction != heading) {
                    BotLog.action(bot, "dig_out_turned", "at", feet.toShortString(),
                            "from", heading.getSerializedName(), "to", direction.getSerializedName());
                }
                heading = direction;
                rise(bot, world, feet, move);
                return;
            }
            if (!StairDig.OPEN_DROP.equals(why)) {
                // A room's open side is refused for want of a floor; what it is told about the rock is the reason worth giving.
                lastRockRefusal = why;
            }
        }
        // No rise from here. In the middle of a room there is no wall to start one in: walk to the nearest.
        Direction toWall = wallToWalkTo(bot, world, feet);
        if (toWall != null) {
            walk(bot, world, feet, toWall);
            return;
        }
        end(bot, "dig_out_blocked:" + lastRockRefusal);
    }

    /** Opens the next closed cell of {@code move}, or climbs onto its landing once every cell is open. */
    private void rise(AIPlayerEntity bot, ServerLevel world, BlockPos feet, OreClimb.Move move) {
        for (BlockPos cell : OreClimb.bodyCells(feet, move)) {
            if (!ObservableWorldQuery.canObserveCell(bot, cell) && !ObservableWorldQuery.canObserveBlock(bot, cell)) {
                // Not in view yet: the cells before it are opened first and bring it into view.
                continue;
            }
            BlockState state = world.getBlockState(cell);
            if (state.isAir()) {
                continue;
            }
            mine(bot, world, feet, move, cell, state);
            return;
        }
        resetMiner(bot);
        BlockPos landing = OreClimb.landing(feet, move);
        step(bot, world, feet, landing, WalkedStep.Kind.STEP_UP, true);
    }

    private void mine(AIPlayerEntity bot, ServerLevel world, BlockPos feet, OreClimb.Move move, BlockPos cell, BlockState state) {
        if (!cell.equals(miner.target())) {
            bot.getActionPack().stopMovement();
            if (!toolReady(bot, world, feet, move, cell, state)) {
                return;
            }
        }
        miner.begin(bot, cell);
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.DONE) {
            openedByUs.add(cell.immutable());
            lastProgressTick = elapsed;
        } else if (status == BlockMiner.Status.FAILED) {
            // The cell cannot be opened (a break the rule refuses, a block that will not give): this step is not made again.
            String reason = miner.failureReason().isEmpty() ? STEP_FAILED : miner.failureReason();
            failStep(feet, OreClimb.landing(feet, move), reason);
            BotLog.action(bot, "dig_out_mine_failed", "cell", cell.toShortString(), "reason", reason);
        }
    }

    // ---- the tool ----------------------------------------------------------------------------------------------------

    /**
     * Settles, once before the first swing at {@code cell}, what it is dug with: a pickaxe is made when the bot has none that suits the
     * block and can make one; then the tool {@link BlockMiner} will take is put in hand, and a break it cannot finish is refused
     * ({@link BreakEffort}), which fails this step so that the stair turns. False while the cell is not to be swung at this tick.
     */
    private boolean toolReady(AIPlayerEntity bot, ServerLevel world, BlockPos feet, OreClimb.Move move, BlockPos cell, BlockState state) {
        if (!toolSettled && needsPickaxe(bot, state) && startToolCraft(bot, state)) {
            return false;
        }
        if (!bot.onGround()) {
            return false; // a body in the air breaks at a fifth of the rate: the tool says nothing until it stands
        }
        ToolSelector.equipBestTool(bot, state, false);
        String refusal = BreakEffort.refusal(bot, world, cell);
        if (refusal == null) {
            return true;
        }
        failStep(feet, OreClimb.landing(feet, move), "break_refused:" + refusal);
        BotLog.action(bot, "dig_out_break_refused", "cell", cell.toShortString(),
                "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                "tool", BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).toString(), "reason", refusal);
        return false;
    }

    /** Whether the block wants a pickaxe to drop what it holds and the bot carries none that does. */
    private static boolean needsPickaxe(AIPlayerEntity bot, BlockState state) {
        if (!state.requiresCorrectToolForDrops() || GatherToolPolicy.categoryFor(state) != GatherToolPolicy.Category.PICKAXE) {
            return false;
        }
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.isCorrectToolForDrops(state)) {
                return false;
            }
        }
        return !bot.getItemBySlot(EquipmentSlot.OFFHAND).isCorrectToolForDrops(state);
    }

    /**
     * Starts making the cheapest pickaxe that harvests {@code state} out of what the bot carries (wooden, then stone: the order every
     * gather uses), or settles that there is none to make and the digging is done by hand. True when the craft was started.
     */
    private boolean startToolCraft(AIPlayerEntity bot, BlockState state) {
        boolean table = WorkshopLocator.hasNearbyCraftingTable(bot)
                || InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isPresent();
        for (Item candidate : GatherToolPolicy.craftCandidates(GatherToolPolicy.Category.PICKAXE)) {
            if (!new ItemStack(candidate).isCorrectToolForDrops(state)
                    || !CraftingHelper.plan(bot, candidate, 1, table).success()) {
                continue;
            }
            toolCraft = new CraftTask(candidate, 1);
            toolCraft.start(bot);
            BotLog.action(bot, "dig_out_tool_craft", "item", BuiltInRegistries.ITEM.getKey(candidate).toString(),
                    "for", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            return true;
        }
        toolSettled = true;
        BotLog.action(bot, "dig_out_tool_none", "for", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        return false;
    }

    /** One tick of the pickaxe craft; the stair goes on, with the pickaxe or by hand, once it has ended. */
    private void craftTool(AIPlayerEntity bot) {
        lastProgressTick = elapsed;
        toolCraft.tick(bot);
        if (toolCraft.state() == TaskState.COMPLETED) {
            BotLog.action(bot, "dig_out_tool_crafted", "ticks", toolCraft.elapsedTicks());
        } else if (toolCraft.state() == TaskState.FAILED) {
            BotLog.action(bot, "dig_out_tool_craft_failed", "reason", toolCraft.failureReason());
        } else {
            return;
        }
        toolCraft = null;
        toolSettled = true;
    }

    // ---- a flow ------------------------------------------------------------------------------------------------------

    /**
     * Whether a refusal of {@code move} is a fluid that comes in through a cell of the step that this task opened and has not closed:
     * the cell holds the fluid already, or a fluid was seen over or beside it. A cell it did not open (a cave's own air beside a pool, the
     * floor under the landing) is not its to close; that step is only refused.
     */
    private boolean flowsIn(ServerLevel world, BlockPos feet, OreClimb.Move move, StairDig.Refusal refusal) {
        BlockPos cell = refusal.cell();
        if (!isFlowReason(refusal.reason()) || !openedByUs.contains(cell) || sealedCells.contains(cell)
                || !OreClimb.bodyCells(feet, move).contains(cell)) {
            return false;
        }
        BlockState state = world.getBlockState(cell);
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    private static boolean isFlowReason(String reason) {
        return "lava".equals(reason) || "water".equals(reason) || MiningSafety.ADJACENT_FLUID.equals(reason);
    }

    /**
     * Closes the cell a flow comes in through with a block the bot carries, put there with a real pick ray like every sealing
     * ({@link MaterialPalette#pickSacrificialBlockSlot}, {@link BuildAction#placeBlockAt}); a cell that already holds the fluid is
     * filled, since a block replaces it. The cell is never opened again ({@link #SEALED_FLOW}). With no block to place, or a placement
     * that is refused, the bot backs down the stair instead.
     */
    private void closeFlow(AIPlayerEntity bot, BlockPos feet, StairDig.Refusal refusal) {
        BlockPos cell = refusal.cell();
        OptionalInt slot = MaterialPalette.pickSacrificialBlockSlot(bot);
        if (slot.isEmpty()) {
            retreatFrom(bot, feet, refusal, "no_block");
            return;
        }
        Item block = bot.getInventory().getNonEquipmentItems().get(slot.getAsInt()).getItem();
        resetMiner(bot);
        // A pickaxe in hand would have the click swallowed (a water cell answers PASS to it): the block goes in hand first.
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult placed = BuildAction.placeBlockAt(bot, cell);
        if (placed.isInProgress()) {
            return;
        }
        if (placed.isFailed()) {
            retreatFrom(bot, feet, refusal, placed.reason());
            return;
        }
        sealedCells.add(cell.immutable());
        lastProgressTick = elapsed;
        BotLog.action(bot, "dig_out_flow_sealed", "cell", cell.toShortString(), "fluid", refusal.reason(),
                "block", BuiltInRegistries.ITEM.getKey(block).toString(), "at", feet.toShortString());
    }

    /** No flow can be closed: the bot walks back down the stair, away from the opening, and the task ends with the typed reason. */
    private void retreatFrom(AIPlayerEntity bot, BlockPos feet, StairDig.Refusal refusal, String why) {
        resetMiner(bot);
        retreatReason = FLOW_UNSEALED + ":" + why;
        BotLog.action(bot, "dig_out_flow_unsealed", "cell", refusal.cell().toShortString(), "fluid", refusal.reason(),
                "why", why, "at", feet.toShortString(), "back_down", trail.size() - 1);
    }

    /** One step back toward where the stair began, along the cells it came by; the task ends there (or where a step cannot be made). */
    private void retreat(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        int at = trail.lastIndexOf(feet);
        if (at <= 0) {
            end(bot, retreatReason);
            return;
        }
        while (trail.size() > at + 1) {
            trail.remove(trail.size() - 1);
        }
        BlockPos back = trail.get(at - 1);
        if (failedSteps.containsKey(stepKey(feet, back))) {
            end(bot, retreatReason);
            return;
        }
        step(bot, world, feet, back, back.getY() < feet.getY() ? WalkedStep.Kind.STEP_DOWN : WalkedStep.Kind.FLAT, false);
        if (lease == null && !bot.getActionPack().stepAdmissionBlocked()) {
            end(bot, retreatReason);
        }
    }

    private boolean touchesSealedCell(BlockPos feet, OreClimb.Move move) {
        for (BlockPos cell : OreClimb.bodyCells(feet, move)) {
            if (sealedCells.contains(cell)) {
                return true;
            }
        }
        return false;
    }

    // ---- which way ---------------------------------------------------------------------------------------------------

    /** Turns the stair toward the brightest thing in sight, from {@code feet}; with nothing brighter in sight the heading is left as it is. */
    private void takeBearing(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        DigOutBearing.Bearing bearing = DigOutBearing.toward(bot, world, feet, heading);
        lit = bearing == null ? null : bearing.direction();
        if (bearing != null && bearing.direction() != heading) {
            BotLog.action(bot, "dig_out_bearing", "at", feet.toShortString(), "from", heading.getSerializedName(),
                    "to", bearing.direction().getSerializedName(), "seen_light", bearing.seen(), "own_light", bearing.own());
            heading = bearing.direction();
        }
    }

    /**
     * The direction to take one step in toward the nearest wall the bot can see at its own level, when
     * that wall is two or more cells away (a wall next to the bot is one a rise starts in) and the
     * first cell toward it can be walked onto. Each such step brings the nearest wall one closer, so
     * the walk ends beside one. The direction of the brightest thing in sight ({@link #lit}) goes first
     * when it has such a wall.
     */
    private Direction wallToWalkTo(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        Direction best = null;
        int bestDistance = Integer.MAX_VALUE;
        int range = ObservableWorldQuery.visibleRangeBlocks(bot);
        for (Direction direction : HORIZONTAL) {
            for (int distance = 1; distance <= range; distance++) {
                BlockPos cell = feet.relative(direction, distance);
                if (!ObservableWorldQuery.canObserveCell(bot, cell) && !ObservableWorldQuery.canObserveBlock(bot, cell)) {
                    break;
                }
                if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                    BlockPos first = feet.relative(direction);
                    if (distance >= 2 && distance < bestDistance && !failedSteps.containsKey(stepKey(feet, first))
                            && !visitedAtThisLevel.contains(first) && Standability.isStandable(world, first)) {
                        if (direction == lit) {
                            return direction;
                        }
                        best = direction;
                        bestDistance = distance;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private void walk(AIPlayerEntity bot, ServerLevel world, BlockPos feet, Direction direction) {
        step(bot, world, feet, feet.relative(direction), WalkedStep.Kind.FLAT, false);
    }

    /** Starts a walked step from {@code feet} onto {@code landing}, or records it as one that cannot be made. */
    private void step(AIPlayerEntity bot, ServerLevel world, BlockPos feet, BlockPos landing,
                      WalkedStep.Kind kind, boolean rises) {
        Standability.clearCache();
        if (!Standability.isStandable(world, landing)) {
            failStep(feet, landing, STEP_FAILED);
            return;
        }
        ActionPack pack = bot.getActionPack();
        if (pack.stepAdmissionBlocked()) {
            return;
        }
        String refused = WalkedStep.refusal(bot, landing, kind);
        if (refused != null) {
            failStep(feet, landing, STEP_FAILED);
            BotLog.action(bot, "dig_out_step_refused", "from", feet.toShortString(),
                    "to", landing.toShortString(), "why", refused);
            return;
        }
        lease = pack.runStep(WalkedStep.begin(bot, landing, kind, "dig_out_step"));
        if (lease != null) {
            stepOrigin = feet.immutable();
            stepLanding = landing.immutable();
            stepRises = rises;
        }
    }

    /** True while a step is in flight; settles it once it has ended. */
    private boolean holdForStep(AIPlayerEntity bot) {
        if (lease == null) {
            return false;
        }
        ActionPack pack = bot.getActionPack();
        if (pack.stepInFlightFor(lease)) {
            return true;
        }
        WalkedStep.Result result = pack.stepResultFor(lease);
        lease = null;
        if (result != null && result.succeeded() && bot.blockPosition().equals(stepLanding)) {
            if (retreatReason != null) {
                trail.remove(trail.size() - 1);
            } else {
                trail.add(stepLanding);
                if (stepRises) {
                    risen++;
                    visitedAtThisLevel.clear();
                    takeBearing(bot, bot.level(), stepLanding);
                }
            }
            lastProgressTick = elapsed;
            BotLog.action(bot, "dig_out_step", "to", stepLanding.toShortString(), "risen", risen);
            return false;
        }
        // Lost or failed: this step is not made again from there, and the bot is left to settle.
        failStep(stepOrigin, stepLanding, STEP_FAILED);
        BotLog.action(bot, "dig_out_step_failed", "from", stepOrigin.toShortString(), "to", stepLanding.toShortString(),
                "why", result == null ? "cancelled" : result.reason());
        return !pack.stepIdle();
    }

    private void failStep(BlockPos origin, BlockPos landing, String reason) {
        failedSteps.put(stepKey(origin, landing), reason);
    }

    private void end(AIPlayerEntity bot, String reason) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        BotLog.action(bot, "dig_out_ended", "reason", reason, "at", bot.blockPosition().toShortString(), "risen", risen);
        BrainCoordinator.INSTANCE.sendPanelChat(bot, "system", bot.getGameProfile().name()
                + " could not dig its way out of the dark at " + bot.blockPosition().toShortString() + ": " + reason);
        fail(reason);
    }

    private static String stepKey(BlockPos origin, BlockPos landing) {
        return origin.toShortString() + ">" + landing.toShortString();
    }

    /** Stops any break in flight and puts the miner back in the modes this task digs in: natural terrain only, no sword. */
    private void resetMiner(AIPlayerEntity bot) {
        miner.cancel(bot);
        miner.naturalTerrainOnly(true);
        miner.swordsMine(false);
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        stopWork(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        stopWork(bot);
    }

    private void stopWork(AIPlayerEntity bot) {
        resetMiner(bot);
        if (toolCraft != null) {
            toolCraft.cancel(bot, "dig_out_stopped");
            toolCraft = null;
        }
        if (lease != null && bot.getActionPack().stepInFlightFor(lease)) {
            bot.getActionPack().cancelStep();
        }
        lease = null;
        bot.getActionPack().stopAll();
    }
}
