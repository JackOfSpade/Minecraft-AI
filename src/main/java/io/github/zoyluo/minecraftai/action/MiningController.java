package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.BreakRule;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistHooks;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.ReachObstructions;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class MiningController {
    private static final int MAX_TICKS = 600;
    /** A direct break may inspect or affect only a block the player can currently see. */
    static final String TARGET_NOT_OBSERVED = "target_not_observed";
    /** The target is seen, but every line to it crosses a see-through block that may not be broken (see {@link MiningObstruction}). */
    static final String TARGET_OBSTRUCTED = "target_obstructed";

    private final BlockPos pos;
    private final Direction face;
    /** The caller aims and picks the tool itself (see {@link #driven}); this controller only runs the break. */
    private final boolean driven;
    /**
     * Whether this controller breaks the see-through blocks in front of its target when no line to it is clear ({@link
     * MiningObstruction}). A driver's controller does not: Baritone clicks what its own pick ray meets, which is the leaf. Nor
     * does one of this controller's own clearing steps.
     */
    private final boolean clearsObstructions;
    private boolean started;
    private BlockState targetState;
    private float progress;
    private int elapsed;
    /** The break of the see-through block in front of the target that is running now; the target waits for it. */
    private MiningController clearing;
    private ReachObstructions.Obstruction clearingFor;
    /** The see-through blocks this controller has broken to get at its target. */
    private final List<BlockPos> cleared = new ArrayList<>();

    public MiningController(BlockPos pos, Direction face) {
        this(pos, face, false, true);
    }

    private MiningController(BlockPos pos, Direction face, boolean driven, boolean clearsObstructions) {
        this.pos = pos;
        this.face = face;
        this.driven = driven;
        this.clearsObstructions = clearsObstructions;
    }

    /**
     * A break run for a movement driver that owns the bot's aim and its hotbar (Baritone): it has already turned the bot toward
     * the block, checked with its own ray that the block is what the bot is looking at, and equipped the tool (the mod's tool policy,
     * ToolSelector, which its cost model prices the break with too). Re-aiming at the face center here would fight the driver's rotation every tick, and re-selecting
     * by this class's own tool score could swap the tool under it, so a driven controller does neither; everything else (the
     * vanilla START/STOP/ABORT handshake, progress, reach, timeout, cache invalidation, the assist hook) is identical.
     */
    public static MiningController driven(BlockPos pos, Direction face) {
        return new MiningController(pos, face, true, false);
    }

    /** The cell this controller is (or was) mining. Lets a caller react once it finishes. */
    public BlockPos pos() {
        return pos;
    }

    /**
     * The block state captured when mining started, i.e. before the break. Stays set after a
     * successful break (the world cell is air by then), so a caller can log what was actually
     * destroyed. Returns null if mining never actually started (e.g. it failed out of reach).
     */
    public BlockState brokenBlockState() {
        return targetState;
    }

    /**
     * Ticks spent actively breaking this cell, including the tick that completed the break ({@code elapsed}
     * itself only counts the ticks that were still in progress). Valid once {@link #tick} has returned success.
     */
    public int elapsedTicks() {
        return elapsed + 1;
    }

    /**
     * Current, state-free-first evidence for a direct break target. The shape-aware observers
     * necessarily inspect the target state to derive its outline, so a state-free current-cell
     * proof must remain first. A full unit-cell face ray is preferred, while the ordinary cell
     * ray covers an exposed partial block such as a snow layer whose physical outline never
     * reaches a unit-cell face. A fire or powder-snow cell intersecting the bot's body is one
     * exception: it is a direct physical hazard rather than hidden terrain. A crop's short
     * outline is the other: its observed outline earns a crop-only state check. This is
     * deliberately live rather than a remembered sighting: mining exposes terrain, and a stale
     * target must never keep a break packet alive. A solid natural-terrain cell that currently
     * intersects the player's own body is also direct evidence: escaping it does not discover a
     * neighbouring cell, and is needed when the player's eye is inside the collision shape.
     *
     * <p>This is the reach gate, so it asks the strict (vanilla clip) predicates: START/STOP/ABORT carry no pick ray and the
     * server checks only distance, so this is the one proof that stops a break through a leaf, a fence or a pane. The bot
     * may <em>see</em> the log behind a leaf (the sight predicates), but it only mines what a hand can reach.</p>
     */
    static boolean currentObservedTarget(AIPlayerEntity player, BlockPos pos) {
        return player != null && pos != null && (ownBodyEmergencyBlock(player, pos)
                || (ObservableWorldQuery.canObserveBlockCellFaceStrict(player, pos)
                || ObservableWorldQuery.canObserveCellStrict(player, pos))
                && (ObservableWorldQuery.canObserveBlockStrict(player, pos)
                || ObservableWorldQuery.canObserveBlockWithInsetFacesStrict(player, pos))
                || currentObservedCropTarget(player, pos));
    }

    /**
     * A crop has a real, player-visible outline but does not fill a block cell, so the generic
     * cell-face admission above intentionally cannot prove it. The outline proof comes before
     * the state read; only an actual crop may use this narrow path.
     */
    private static boolean currentObservedCropTarget(AIPlayerEntity player, BlockPos pos) {
        return ObservableWorldQuery.canObserveFarmCellStrict(player, pos)
                && player.level().getBlockState(pos).getBlock() instanceof CropBlock;
    }

    /**
     * The one no-block exception to {@link #currentObservedTarget}: after a player-visible block
     * has gone away, callers may settle their own mining state as complete. The cell ray is still
     * state-free and exact, and this helper performs no tool choice or break packet.
     */
    static boolean visiblyAir(AIPlayerEntity player, BlockPos pos) {
        return player != null
                && pos != null
                && ObservableWorldQuery.canObserveCell(player, pos)
                && player.level().getBlockState(pos).isAir();
    }

    /**
     * What a break of {@code pos} needs from here. A target a hand reaches (the strict proof above) needs nothing. One the bot sees
     * only through see-through blocks (a log behind leaves) is still admitted when {@link MiningObstruction} can clear the way:
     * the controller then breaks those blocks first, one at a time, and the target only once the strict proof passes. Otherwise
     * the plan is refused with the typed reason, which every caller turns into "not mineable from this stand".
     */
    static MiningObstruction.Plan admission(AIPlayerEntity player, BlockPos pos) {
        if (player == null || pos == null) {
            return MiningObstruction.Plan.NOT_SEEN;
        }
        return currentObservedTarget(player, pos)
                ? MiningObstruction.Plan.REACHABLE : MiningObstruction.plan(player, pos, List.of());
    }

    /**
     * {@link #admission} for a caller outside this package: {@code null} when the break may start, else the typed refusal (a
     * target behind a block that may not be broken is logged here, like at every other refusal).
     */
    public static String admissionRefusal(AIPlayerEntity player, BlockPos pos) {
        MiningObstruction.Plan plan = admission(player, pos);
        if (plan.kind() == MiningObstruction.Kind.PROTECTED) {
            MiningObstruction.logRefused(player, pos, plan.obstruction(), plan.reason());
        }
        return plan.refusal();
    }

    public ActionResult tick(ActionPack pack) {
        AIPlayerEntity player = pack.player();
        var world = player.level();
        // Do not read the target state merely because a caller retained a coordinate. The
        // state-free cell-face ray earns the shape-aware proof and this exact live read.
        // A visibly empty cell is the narrowly safe completion case (for example the controller
        // finished on the preceding scheduler tick); it cannot start a new break.
        if (visiblyAir(player, pos)) {
            abortClearing(player);
            return settleVisibleAir(player);
        }
        if (clearing != null) {
            return tickClearing(pack, player);
        }
        if (!currentObservedTarget(player, pos)) {
            return clearsObstructions && !started ? clearTheWay(pack, player) : visibilityRefused(player);
        }
        // A target can become a support after the controller was admitted (another player walks
        // onto it, or a bot lands on its own bridge). Stop the live break before another progress
        // tick can destroy that footing. The current target has already been ray-proven above,
        // so this live footing check cannot become a hidden-terrain probe.
        MiningSafety.SupportOccupancy support = MiningSafety.supportOccupancy(player, pos);
        if (support != MiningSafety.SupportOccupancy.NONE) {
            return supportRefused(player, support);
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return settleVisibleAir(player);
        }
        if (targetState != null && !state.equals(targetState)) {
            resetProgress(player);
        }

        if (!driven) {
            LookAction.lookAtBlock(player, pos, face);
        }
        double reach = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        if (player.getEyePosition().distanceTo(pos.getCenter()) > reach + 0.5D) {
            abort(player);
            return ActionResult.failed("out_of_reach");
        }

        if (!started) {
            if (driven) {
                if (!sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)) {
                    MiningSafety.SupportOccupancy liveSupport = MiningSafety.supportOccupancy(player, pos);
                    if (liveSupport != MiningSafety.SupportOccupancy.NONE) {
                        return supportRefused(player, liveSupport);
                    }
                    return visibilityRefused(player);
                }
                BotLog.action(player, "mine_start", "pos", LogFields.pos(pos), "face", face, "driver", "baritone");
            } else {
                ToolSelector.equipBestTool(player, state);
                if (!sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)) {
                    MiningSafety.SupportOccupancy liveSupport = MiningSafety.supportOccupancy(player, pos);
                    if (liveSupport != MiningSafety.SupportOccupancy.NONE) {
                        return supportRefused(player, liveSupport);
                    }
                    return visibilityRefused(player);
                }
                BotLog.action(player, "mine_start", "pos", LogFields.pos(pos), "face", face);
            }
            state.attack(world, pos, player);
            started = true;
            targetState = state;
        }

        progress += state.getDestroyProgress(player, world, pos);
        world.destroyBlockProgress(player.getId(), pos, Math.min(9, (int) (progress * 10.0F)));
        player.swing(InteractionHand.MAIN_HAND);
        player.resetLastActionTime();

        if (progress >= 1.0F) {
            if (!sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
                MiningSafety.SupportOccupancy liveSupport = MiningSafety.supportOccupancy(player, pos);
                if (liveSupport != MiningSafety.SupportOccupancy.NONE) {
                    return supportRefused(player, liveSupport);
                }
                return visibilityRefused(player);
            }
            return completeBreak(player);
        }

        elapsed++;
        if (elapsed > MAX_TICKS) {
            abort(player);
            return ActionResult.failed("timeout");
        }
        return ActionResult.IN_PROGRESS;
    }

    /**
     * No line to the target is clear. If the eyes do see it, the way is cleared one real block at a time: the see-through block
     * nearest the eye on the best line is broken like any other block (its own tool, break delay, safety and drops), and this
     * method runs again from the strict proof once it is gone. A target the eyes do not see is refused as before, and so is one
     * that every line only reaches through a block that may not be broken, with the typed {@link #TARGET_OBSTRUCTED}.
     */
    private ActionResult clearTheWay(ActionPack pack, AIPlayerEntity player) {
        MiningObstruction.Plan plan = MiningObstruction.plan(player, pos, cleared);
        switch (plan.kind()) {
            case CLEAR -> {
                clearing = new MiningController(plan.obstruction().pos(), faceToward(player, plan.obstruction().pos()), false, false);
                clearingFor = plan.obstruction();
                MiningObstruction.logDetected(player, pos, plan);
                return tickClearing(pack, player);
            }
            case PROTECTED -> {
                MiningObstruction.logRefused(player, pos, plan.obstruction(), plan.reason());
                clearProgress(player);
                return ActionResult.failed(TARGET_OBSTRUCTED);
            }
            default -> {
                return visibilityRefused(player);
            }
        }
    }

    /**
     * Runs the break of the see-through block in front of the target through the pack's own break tick, so it keeps vanilla's break
     * delay and the shield rule. A finished break is recorded like any other and the method returns in progress: like a hand,
     * the bot starts its next break on a later tick, and that is where the strict proof is asked again.
     */
    private ActionResult tickClearing(ActionPack pack, AIPlayerEntity player) {
        ActionResult result = pack.tickBreak(clearing);
        if (result.isInProgress()) {
            return ActionResult.IN_PROGRESS;
        }
        MiningController step = clearing;
        ReachObstructions.Obstruction obstruction = clearingFor;
        clearing = null;
        clearingFor = null;
        if (result.isSuccess()) {
            cleared.add(step.pos());
            if (step.brokenBlockState() != null) {
                pack.recordBreak(step, pos); // a block that was already gone when the step looked is nobody's break to audit
            }
            MiningObstruction.logCleared(player, pos, obstruction, step.elapsedTicks());
            return ActionResult.IN_PROGRESS;
        }
        MiningObstruction.logRefused(player, pos, obstruction, result.reason());
        clearProgress(player);
        return ActionResult.failed(TARGET_OBSTRUCTED);
    }

    /** Cancels the break of a see-through block in front of the target, if one is running: no half-broken block stays marked. */
    private void abortClearing(AIPlayerEntity player) {
        if (clearing != null) {
            clearing.abort(player);
            clearing = null;
            clearingFor = null;
        }
    }

    private static Direction faceToward(AIPlayerEntity player, BlockPos block) {
        return Direction.getApproximateNearest(player.getEyePosition().subtract(block.getCenter()));
    }

    /**
     * A cell overlapping the player's own body is direct physical evidence, never a hidden
     * terrain read. Fire and powder snow remain immediate hazards. A solid escape cell must also
     * be breakable natural terrain: this exception never authorizes an unbreakable, interactive,
     * structure, or player-built block merely because a player intersects it.
     */
    private static boolean ownBodyEmergencyBlock(AIPlayerEntity player, BlockPos pos) {
        if (player == null || pos == null
                || !player.getBoundingBox().deflate(0.001D).intersects(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D)) {
            return false;
        }
        BlockState state = player.level().getBlockState(pos);
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW)
                || (!state.getCollisionShape(player.level(), pos).isEmpty()
                && state.getDestroySpeed(player.level(), pos) >= 0.0F
                && BreakRule.denialOf(state) == null);
    }

    public void abort(AIPlayerEntity player) {
        abortClearing(player);
        if (!started) {
            return;
        }
        // An ABORT packet is still a packet naming a world cell. It is safe to clear the local
        // indicator unconditionally, but only send the packet while the target is freshly seen.
        sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        clearProgress(player);
    }

    private void resetProgress(AIPlayerEntity player) {
        if (started) {
            sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        }
        clearProgress(player);
    }

    /** Re-proves the live cell immediately before each vanilla break packet. */
    private boolean sendBreakActionIfObserved(AIPlayerEntity player,
                                              ServerboundPlayerActionPacket.Action action) {
        // Some instant-break blocks (for example a torch) turn into observed air during the
        // START/attack phase. STOP and ABORT have no packet left to send in that case, but the
        // completed controller still owns a valid observed break transaction. Treat it as a
        // successful settlement rather than misreporting a visibility refusal.
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && visiblyAir(player, pos)) {
            return true;
        }
        if (!currentObservedTarget(player, pos)) {
            return false;
        }
        if (action != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                && MiningSafety.supportOccupancy(player, pos) != MiningSafety.SupportOccupancy.NONE) {
            return false;
        }
        player.gameMode.handleBlockBreakAction(
                pos,
                action,
                face,
                Level.MAX_ENTITY_SPAWN_Y,
                -1);
        return true;
    }

    /** Clears an active local break without classifying a support-safety refusal as lost sight. */
    private ActionResult supportRefused(AIPlayerEntity player, MiningSafety.SupportOccupancy support) {
        abort(player);
        String reason = MiningSafety.refusalReason(support);
        BotLog.action(player, "mine_support_refused", "pos", LogFields.pos(pos),
                "reason", reason);
        return ActionResult.failed(reason);
    }

    /**
     * Settles the one controller that actually started an observed break when its target is now
     * visibly air. Instant blocks such as torches can disappear inside {@code state.attack}; do
     * not erase the captured pre-break state or turn that successful transaction into an
     * "unknown" completion. An unstarted race still clears its local indicator as before.
     */
    private ActionResult settleVisibleAir(AIPlayerEntity player) {
        if (started && targetState != null) {
            return completeBreak(player);
        }
        clearProgress(player);
        return ActionResult.SUCCESS;
    }

    /** Runs the common post-break receipt work without erasing the captured pre-break state. */
    private ActionResult completeBreak(AIPlayerEntity player) {
        player.level().destroyBlockProgress(player.getId(), pos, -1);
        AStarPathfinder.invalidateCache("block_break");
        MiningAssistHooks.onBotBreak(player, pos);
        return ActionResult.SUCCESS;
    }

    private ActionResult visibilityRefused(AIPlayerEntity player) {
        // Reset only our local progress marker. If sight was lost, do not send an unproved ABORT
        // packet as part of the cleanup.
        player.level().destroyBlockProgress(player.getId(), pos, -1);
        started = false;
        targetState = null;
        progress = 0.0F;
        elapsed = 0;
        BotLog.action(player, "mine_visibility_refused", "pos", LogFields.pos(pos), "reason", TARGET_NOT_OBSERVED);
        return ActionResult.failed(TARGET_NOT_OBSERVED);
    }

    private void clearProgress(AIPlayerEntity player) {
        player.level().destroyBlockProgress(player.getId(), pos, -1);
        started = false;
        targetState = null;
        progress = 0.0F;
        elapsed = 0;
    }
}
