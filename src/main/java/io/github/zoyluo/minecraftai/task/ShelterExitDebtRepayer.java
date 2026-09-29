package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
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

    /** Adopts (or clears) any exit debt outstanding for the bot at the start of a follow task. */
    void reset(AIPlayerEntity bot) {
        shelterExitMiner.cancel(bot);
        rejectedShelterEgress.clear();
        shelterExitDebt = EmergencyShelterTask.pendingExitDebt(bot).orElse(null);
        activeShelterEgress = null;
        waiting = false;
    }

    void cancel(AIPlayerEntity bot) {
        shelterExitMiner.cancel(bot);
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
        BlockPos obstruction = firstShelterExitObstruction(bot, egress);
        if (obstruction != null) {
            if (!shelterExitDebt.ownsCurrentPlacement(bot, obstruction)) {
                rejectedShelterEgress.add(egress);
                activeShelterEgress = null;
                waiting = true;
                return true;
            }
            if (!obstruction.equals(shelterExitMiner.target())) {
                shelterExitMiner.begin(bot, obstruction);
            }
            BlockMiner.Status status = shelterExitMiner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                rejectedShelterEgress.add(egress);
                activeShelterEgress = null;
            }
            waiting = true;
            return true;
        }
        Standability.clearCache();
        if (!Standability.isStandable(bot.level(), egress)
                || !FakePlayerMotion.stepToStandable(bot, egress, "follow_shelter_exit")) {
            rejectedShelterEgress.add(egress);
            activeShelterEgress = null;
            waiting = true;
            return true;
        }
        // The body is now physically outside a cancelled shell.  Promote only this exact owned
        // state proof to low-priority cleanup before forgetting the doorway debt; no player-built
        // blocks can enter the registry.
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
            if (rejectedShelterEgress.contains(candidate)
                    || !hasSafeShelterExitSupport(bot, candidate)) {
                continue;
            }
            BlockPos obstruction = firstShelterExitObstruction(bot, candidate);
            if (obstruction != null && !shelterExitDebt.ownsCurrentPlacement(bot, obstruction)) {
                rejectedShelterEgress.add(candidate);
                continue;
            }
            double targetDistance = candidate.distSqr(target.blockPosition());
            if (best == null || targetDistance < bestDistance) {
                best = candidate;
                bestDistance = targetDistance;
            }
        }
        return best;
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

    private void finish(AIPlayerEntity bot) {
        shelterExitMiner.cancel(bot);
        EmergencyShelterTask.clearExitDebt(bot, shelterExitDebt);
        shelterExitDebt = null;
        rejectedShelterEgress.clear();
        activeShelterEgress = null;
    }
}
