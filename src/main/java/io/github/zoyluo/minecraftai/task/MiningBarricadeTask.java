package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mining-specific hostile recovery: walk back through the already opened branch, then leave a
 * permanent two-block gate between the miner and the hostile cave.  Unlike a general emergency
 * shelter, this task never closes the known rear corridor and never reopens the hostile-facing
 * wall after its hold interval.
 */
public final class MiningBarricadeTask extends AbstractTask {
    private static final int RETREAT_LIMIT = 120;
    private static final int SEAL_NO_PROGRESS_LIMIT = 40;
    private static final int HOLD_TICKS = 20;
    private static final int OPEN_GATE_BLOCKS = 2;

    private enum Phase {
        RETREAT,
        SEAL,
        HOLD
    }

    private final BlockPos retreatFeet;
    private final BlockPos barrierFeet;
    private Phase phase = Phase.RETREAT;
    private boolean retreatStepLaunched;
    /** Exact ActionPack admission for the one-cell retreat. */
    private ActionPack.StepLease retreatStepLease;
    private WalkedStep retreatStep;
    private boolean retreatStepFailed;
    private int phaseStartedElapsed;
    private int lastProgressElapsed;
    private String lastPlacementFailure = "none";

    public MiningBarricadeTask(BlockPos retreatFeet, BlockPos barrierFeet) {
        this.retreatFeet = retreatFeet == null ? null : retreatFeet.immutable();
        this.barrierFeet = barrierFeet == null ? null : barrierFeet.immutable();
    }

    /** A freshly selected narrow mining gate always has one open feet and one open head cell. */
    static boolean hasMaterialsForOpenGate(AIPlayerEntity bot) {
        return MaterialPalette.countShelterBlocks(bot) >= OPEN_GATE_BLOCKS;
    }

    @Override
    public String name() {
        return "mining_barricade";
    }

    @Override
    public String describe() {
        return "Mining barricade phase=" + phase
                + " retreat=" + BlockPosText.compactOrElse(retreatFeet, "missing")
                + " gate=" + BlockPosText.compactOrElse(barrierFeet, "missing");
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case RETREAT -> 0.2D;
            case SEAL -> 0.5D;
            case HOLD -> 0.9D;
        };
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.RETREAT;
        retreatStepLaunched = false;
        retreatStepLease = null;
        retreatStep = null;
        retreatStepFailed = false;
        phaseStartedElapsed = 0;
        lastProgressElapsed = 0;
        lastPlacementFailure = "none";
        if (retreatFeet == null || barrierFeet == null
                || retreatFeet.getY() != barrierFeet.getY()
                || horizontalManhattan(retreatFeet, barrierFeet) != 1) {
            fail("mining_barricade_invalid_plan");
            return;
        }
        Standability.clearCache();
        if (!Standability.isStandable(bot.level(), retreatFeet)) {
            fail("mining_barricade_retreat_not_standable");
            return;
        }
        int required = (isSealed(bot, barrierFeet) ? 0 : 1)
                + (isSealed(bot, barrierFeet.above()) ? 0 : 1);
        int available = MaterialPalette.countShelterBlocks(bot);
        if (available < required) {
            fail("missing barricade_blocks required=" + required + " available=" + available);
            return;
        }
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        switch (phase) {
            case RETREAT -> tickRetreat(bot);
            case SEAL -> tickSeal(bot);
            case HOLD -> tickHold(bot);
        }
    }

    private void tickRetreat(AIPlayerEntity bot) {
        ActionPack pack = bot.getActionPack();
        BlockPos here = bot.blockPosition();
        if (retreatStepLaunched) {
            ActionPack.StepLease lease = retreatStepLease;
            if (pack.stepInFlightFor(lease)) {
                return; // the exact walked retreat step is still in flight
            }
            if (!pack.stepIdle()) {
                // A successor owns ActionPack. Forget only this task's stale retreat admission;
                // do not turn its terminal result into a barricade route failure.
                retreatStepLaunched = false;
                retreatStepLease = null;
                retreatStep = null;
                return;
            }
            WalkedStep.Result result = pack.stepResultFor(lease);
            // The exact retreat step ended. Arrival is handled below only after this lease has
            // settled, so a foreign successor can never promote the barricade phase.
            retreatStepLaunched = false;
            retreatStepLease = null;
            retreatStep = null;
            retreatStepFailed = result == null || !result.succeeded() || !here.equals(retreatFeet);
        }
        if (!pack.stepIdle()) {
            return;
        }
        if (bot.blockPosition().equals(retreatFeet)) {
            // This arrival is observed only after an idle pack (or this task's exact lease has
            // settled). A foreign successor that happened to enter the same cell is left alone.
            bot.getActionPack().stopAll();
            enterPhase(Phase.SEAL);
            return;
        }
        if (phaseAge() > RETREAT_LIMIT) {
            fail("mining_barricade_retreat_timeout");
            return;
        }
        if (!pack.isPathExecutorIdle()) {
            return;
        }
        if (!retreatStepFailed
                && here.getY() == retreatFeet.getY()
                && horizontalManhattan(here, retreatFeet) == 1
                && WalkedStep.refusal(bot, retreatFeet, WalkedStep.Kind.FLAT) == null) {
            // One block back is a walked step (forward key), never a teleport; the tunnel is sealed only once the bot stands there.
            WalkedStep next = WalkedStep.begin(bot, retreatFeet, WalkedStep.Kind.FLAT, "mining_barricade_retreat");
            ActionPack.StepLease lease = pack.runStep(next);
            if (lease == null) {
                // A guarded owner still owns the handoff fence. Keep the retreat unstarted and
                // retry its same physical route instead of treating another step as ours.
                return;
            }
            retreatStep = next;
            retreatStepLease = lease;
            retreatStepLaunched = true;
            lastProgressElapsed = elapsed;
            return;
        }
        ActionResult route = bot.getActionPack().startSurfacePathTo(retreatFeet);
        if (route.isFailed()) {
            fail("mining_barricade_retreat_unreachable:" + route.reason());
        }
    }

    private void tickSeal(AIPlayerEntity bot) {
        if (!bot.blockPosition().equals(retreatFeet)) {
            bot.getActionPack().stopAll();
            enterPhase(Phase.RETREAT);
            return;
        }
        BlockPos target = !isSealed(bot, barrierFeet) ? barrierFeet
                : !isSealed(bot, barrierFeet.above()) ? barrierFeet.above() : null;
        if (target == null) {
            bot.getActionPack().stopAll();
            enterPhase(Phase.HOLD);
            BotLog.danger(bot, "mining_barricade_sealed",
                    "gate", barrierFeet.toShortString(),
                    "retreat", retreatFeet.toShortString());
            return;
        }
        OptionalInt blockSlot = MaterialPalette.pickShelterBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            fail("missing barricade_block");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            fail("cannot_equip_barricade_block");
            return;
        }
        ActionResult placed = BuildAction.placeBlockAt(bot, target);
        if (placed.isInProgress()) {
            // A reactive shield is actively protecting the miner; pause this seal's no-progress budget.
            lastProgressElapsed = elapsed;
            return;
        }
        if (placed.isSuccess() && isSealed(bot, target)) {
            lastProgressElapsed = elapsed;
            lastPlacementFailure = "none";
            return;
        }
        lastPlacementFailure = placed.reason();
        if (elapsed - lastProgressElapsed > SEAL_NO_PROGRESS_LIMIT) {
            fail("mining_barricade_unsealable:" + target.toShortString()
                    + ":" + lastPlacementFailure);
        }
    }

    private void tickHold(AIPlayerEntity bot) {
        if (!isSealed(bot, barrierFeet) || !isSealed(bot, barrierFeet.above())) {
            enterPhase(Phase.SEAL);
            return;
        }
        bot.getActionPack().stopAll();
        if (phaseAge() >= HOLD_TICKS) {
            complete();
        }
    }

    private void enterPhase(Phase next) {
        phase = next;
        phaseStartedElapsed = elapsed;
        lastProgressElapsed = elapsed;
    }

    private int phaseAge() {
        return Math.max(0, elapsed - phaseStartedElapsed);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        ActionPack pack = bot.getActionPack();
        if (pack.stepInFlightFor(retreatStepLease)) {
            pack.cancelStep();
        }
        retreatStep = null;
        retreatStepLease = null;
        retreatStepLaunched = false;
        bot.getActionPack().stopAll();
    }

    private static boolean isSealed(AIPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) {
            return false;
        }
        var world = bot.level();
        BlockState state = world.getBlockState(pos);
        return !state.canBeReplaced() && !state.getCollisionShape(world, pos).isEmpty();
    }

    private static int horizontalManhattan(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX()) + Math.abs(first.getZ() - second.getZ());
    }

}
