package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Repays a sealed shelter's cancellation debt on {@link FollowTask}'s behalf, before it resumes
 * ordinary land/swim/boat routing.  It can mine only a block whose exact state was recorded as
 * bot-owned by {@link EmergencyShelterTask}; arbitrary enclosure blocks are rejected rather than
 * punched through.  This is a self-contained mini state machine with its own dedicated fields,
 * unrelated to FollowTask's mode-routing state.
 */
final class ShelterExitDebtRepayer {
    private final BlockMiner shelterExitMiner = new BlockMiner();
    private final Set<BlockPos> rejectedShelterEgress = new HashSet<>();
    private EmergencyShelterTask.ExitDebt shelterExitDebt;
    private BlockPos activeShelterEgress;
    private boolean waiting;
    /** The walk out through the doorway is running (a real walked step over several ticks). */
    private boolean stepping;
    /** Exact ActionPack admission for the exit walk; global step results belong to other owners. */
    private ActionPack.StepLease stepLease;
    private WalkedStep step;

    /** Adopts (or clears) any exit debt outstanding for the bot at the start of a follow task. */
    void reset(AIPlayerEntity bot) {
        cancelOwnedStep(bot);
        shelterExitMiner.cancel(bot);
        rejectedShelterEgress.clear();
        shelterExitDebt = EmergencyShelterTask.pendingExitDebt(bot).orElse(null);
        activeShelterEgress = null;
        waiting = false;
    }

    void cancel(AIPlayerEntity bot) {
        shelterExitMiner.cancel(bot);
        cancelOwnedStep(bot);
    }

    /**
     * @return true if a debt was outstanding and consumed this tick's routing -- the caller must
     * return immediately, with {@link #isWaiting()} already reflecting the tick's outcome; false
     * if there was no debt (or one was just fully repaid this tick), so the caller should continue
     * its own ordinary follow routing.
     */
    boolean repay(AIPlayerEntity bot, ServerPlayer target, int elapsed) {
        if (shelterExitDebt == null) {
            return false;
        }
        if (stepping) {
            return tickWalkOut(bot);
        }
        if (!shelterExitDebt.matchesDimension(bot)
                || !bot.blockPosition().equals(shelterExitDebt.anchor())) {
            finish(bot);
            return false;
        }
        BlockPos egress = activeShelterEgress == null
                ? selectShelterEgress(bot, target)
                : activeShelterEgress;
        if (egress == null) {
            if (elapsed % 200 == 1) {
                BotLog.action(bot, "follow_shelter_egress_unavailable", "anchor", shelterExitDebt.anchor().toShortString());
            }
            bot.getActionPack().stopMovement();
            waiting = true;
            return true;
        }
        activeShelterEgress = egress;
        // Every egress cell is durable shelter metadata, not a present-tense terrain fact.  A
        // cancelled shelter may open its exact recorded opaque doorway in two stages, but must
        // not turn its recorded side list into a scan of the world beyond the shell.
        BlockPos stagedObstruction = stagedOwnedDoorwayObstruction(bot, egress);
        if (stagedObstruction != null) {
            mineOwnedEgressObstruction(bot, egress, stagedObstruction);
            return true;
        }
        if (!canObserveEgressEnvelope(bot, egress)) {
            bot.getActionPack().stopMovement();
            waiting = true;
            return true;
        }
        BlockPos obstruction = firstShelterExitObstruction(bot, egress);
        if (obstruction != null) {
            if (!shelterExitDebt.ownsCurrentPlacement(bot, obstruction)) {
                rejectedShelterEgress.add(egress);
                activeShelterEgress = null;
                waiting = true;
                return true;
            }
            mineOwnedEgressObstruction(bot, egress, obstruction);
            return true;
        }
        Standability.clearCache();
        WalkedStep.Kind kind = WalkedStepRules.walkKindFor(egress.getY() - bot.blockPosition().getY());
        // The destination's feet/head/support were proved above.  Prove every extra validator
        // cell before WalkedStep reads it too (corners, hop headroom, or a descent column).
        if (kind == null
                || !SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, egress, kind)
                || !Standability.isStandable(bot.level(), egress)
                || WalkedStep.refusal(bot, egress, kind) != null) {
            rejectedShelterEgress.add(egress);
            activeShelterEgress = null;
            waiting = true;
            return true;
        }
        // The bot walks out through the opened doorway with its own keys (never placed there); the walk runs over the next ticks.
        WalkedStep next = WalkedStep.begin(bot, egress, kind, "follow_shelter_exit");
        ExitStepAdmission admission = new ExitStepAdmission(bot.blockPosition(), egress, kind);
        ActionPack.StepLease lease = bot.getActionPack().runStep(next,
                (guardBot, guardedStep) -> canContinueObservedEgressStep(guardBot, guardedStep, admission));
        if (lease == null) {
            // A guarded controller still owns the handoff. Keep this exact egress selected and
            // retry it, rather than marking it bad from another controller's outcome.
            waiting = true;
            return true;
        }
        step = next;
        stepLease = lease;
        stepping = true;
        waiting = true;
        return true;
    }

    /**
     * One tick with the walk out in flight. It carries on by itself; when it has ended in the doorway cell the body is physically
     * outside the cancelled shell: only this exact owned state proof is promoted to low-priority cleanup before the doorway debt is
     * forgotten (no player-built blocks can enter the registry). A failed walk rejects that doorway and tries another.
     */
    private boolean tickWalkOut(AIPlayerEntity bot) {
        ActionPack pack = bot.getActionPack();
        ActionPack.StepLease lease = stepLease;
        if (pack.stepInFlightFor(lease)) {
            waiting = true;
            return true;
        }
        if (!pack.stepIdle()) {
            // A safety successor owns the pack. Forget only this repayer's old admission and
            // leave its inputs and result completely untouched.
            pack.releaseStepLease(lease);
            stepping = false;
            step = null;
            stepLease = null;
            waiting = true;
            return true;
        }
        WalkedStep.Result result = pack.stepResultFor(lease);
        // A guarded step keeps its fence after natural completion until its exact owner
        // reconciles the outcome. Retire it before this repayer selects another doorway or
        // lets Follow start the next controller.
        pack.releaseStepLease(lease);
        stepping = false;
        step = null;
        stepLease = null;
        // Use this exact lease only. A successor may legitimately occupy ActionPack after this
        // task was displaced, and its global result must not settle this shelter debt.
        if (result == null || !result.succeeded()) {
            if (activeShelterEgress != null) {
                rejectedShelterEgress.add(activeShelterEgress);
            }
            activeShelterEgress = null;
            waiting = true;
            return true;
        }
        EmergencyShelterTask.promoteExitDebtForCleanup(bot, shelterExitDebt);
        finish(bot);
        waiting = false;
        return false;
    }

    boolean isWaiting() {
        return waiting;
    }

    private BlockPos selectShelterEgress(AIPlayerEntity bot, ServerPlayer target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : shelterExitDebt.egressCandidates()) {
            if (rejectedShelterEgress.contains(candidate)) {
                continue;
            }
            boolean stagedDoorway = stagedOwnedDoorwayObstruction(bot, candidate) != null;
            if (!stagedDoorway && !canObserveEgressEnvelope(bot, candidate)) {
                continue;
            }
            if (!stagedDoorway && !hasSafeShelterExitSupport(bot, candidate)) {
                continue;
            }
            if (!stagedDoorway) {
                BlockPos obstruction = firstShelterExitObstruction(bot, candidate);
                if (obstruction != null && !shelterExitDebt.ownsCurrentPlacement(bot, obstruction)) {
                    rejectedShelterEgress.add(candidate);
                    continue;
                }
            }
            double targetDistance = candidate.distSqr(target.blockPosition());
            if (best == null || targetDistance < bestDistance) {
                best = candidate;
                bestDistance = targetDistance;
            }
        }
        return best;
    }

    /**
     * A cancelled shelter can hide its own doorway from the full cell envelope. Its exact owned
     * head/foot pair is still durable provenance, so open the head first; only after that head
     * port is visibly open may the exact owned foot be mined. Movement never uses this exception.
     */
    private BlockPos stagedOwnedDoorwayObstruction(AIPlayerEntity bot, BlockPos egress) {
        if (shelterExitDebt.ownsCurrentPlacement(bot, egress)
                && shelterExitDebt.ownsCurrentPlacement(bot, egress.above())) {
            return egress.above().immutable();
        }
        if (shelterExitDebt.ownsCurrentPlacement(bot, egress)
                && ObservableWorldQuery.canObserveCell(bot, egress.above())
                && isPassableShelterExitCell(bot, egress.above())) {
            return egress.immutable();
        }
        return null;
    }

    private void mineOwnedEgressObstruction(AIPlayerEntity bot, BlockPos egress, BlockPos obstruction) {
        if (!obstruction.equals(shelterExitMiner.target())) {
            shelterExitMiner.begin(bot, obstruction);
        }
        BlockMiner.Status status = shelterExitMiner.tick(bot);
        if (status == BlockMiner.Status.FAILED) {
            rejectedShelterEgress.add(egress);
            activeShelterEgress = null;
        }
        waiting = true;
    }

    /**
     * State-free proof for every world cell the egress selector and its walked-step validator may
     * inspect.  The side may be an owned wall (rather than open air), so use cell visibility that
     * accepts the target block itself as the first ray hit; do not infer openness before reading
     * its state.
     */
    private static boolean canObserveEgressEnvelope(AIPlayerEntity bot, BlockPos egress) {
        return egress != null
                && ObservableWorldQuery.canObserveCell(bot, egress)
                && ObservableWorldQuery.canObserveCell(bot, egress.above())
                && ObservableWorldQuery.canObserveCell(bot, egress.below());
    }

    /**
     * Re-proves the complete visible egress before {@link WalkedStep} performs its next terrain
     * validation.  A later occlusion or controller relocation therefore releases the guarded
     * step rather than letting a remembered shelter side retain movement input.
     */
    private static boolean canContinueObservedEgressStep(AIPlayerEntity bot, WalkedStep step,
                                                          ExitStepAdmission admission) {
        return step.kind() == admission.kind()
                && step.cell().equals(admission.destination())
                && withinEgressStepEnvelope(bot.blockPosition(), admission, step.ticks())
                && canObserveEgressEnvelope(bot, admission.destination())
                && SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, admission.destination(), step.kind());
    }

    /** No-world-read provenance check matching WalkedStep's narrow first-tick source normalization. */
    private static boolean withinEgressStepEnvelope(BlockPos feet, ExitStepAdmission admission,
                                                    int activeStepTicks) {
        BlockPos origin = admission.origin();
        BlockPos destination = admission.destination();
        if (between(feet.getX(), origin.getX(), destination.getX())
                && between(feet.getY(), origin.getY(), destination.getY())
                && between(feet.getZ(), origin.getZ(), destination.getZ())) {
            return true;
        }
        return (admission.kind() == WalkedStep.Kind.FLAT
                || admission.kind() == WalkedStep.Kind.STEP_UP
                || admission.kind() == WalkedStep.Kind.STEP_DOWN)
                && activeStepTicks >= 0 && activeStepTicks <= 1
                && feet.getX() == origin.getX()
                && feet.getZ() == origin.getZ()
                && Math.abs(feet.getY() - origin.getY()) == 1;
    }

    private static boolean between(int value, int first, int second) {
        return value >= Math.min(first, second) && value <= Math.max(first, second);
    }

    private static BlockPos firstShelterExitObstruction(AIPlayerEntity bot, BlockPos egress) {
        if (!isPassableShelterExitCell(bot, egress.above())) {
            return egress.above().immutable();
        }
        if (!isPassableShelterExitCell(bot, egress)) {
            return egress.immutable();
        }
        return null;
    }

    private static boolean hasSafeShelterExitSupport(AIPlayerEntity bot, BlockPos egress) {
        var world = bot.level();
        var support = world.getBlockState(egress.below());
        return support.getFluidState().isEmpty()
                && !support.getCollisionShape(world, egress.below()).isEmpty()
                && !Standability.isDangerous(support);
    }

    private static boolean isPassableShelterExitCell(AIPlayerEntity bot, BlockPos position) {
        var world = bot.level();
        var state = world.getBlockState(position);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(world, position).isEmpty()
                && !Standability.isDangerous(state);
    }

    /** Immutable provenance held by the ActionPack continuation closure for one exit step. */
    private record ExitStepAdmission(BlockPos origin, BlockPos destination, WalkedStep.Kind kind) {
        private ExitStepAdmission {
            origin = origin.immutable();
            destination = destination.immutable();
        }
    }

    private void finish(AIPlayerEntity bot) {
        shelterExitMiner.cancel(bot);
        EmergencyShelterTask.clearExitDebt(bot, shelterExitDebt);
        shelterExitDebt = null;
        rejectedShelterEgress.clear();
        activeShelterEgress = null;
    }

    private void cancelOwnedStep(AIPlayerEntity bot) {
        if (stepLease != null) {
            // The opaque lease makes this harmless when a successor has already taken over and
            // releases the continuation fence when this repayer still owns it.
            bot.getActionPack().cancelStep(stepLease);
        }
        stepping = false;
        stepLease = null;
        step = null;
    }
}
