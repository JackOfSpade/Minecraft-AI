package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningFoodReserve;

/**
 * Immutable resource contract for one service boundary. Durability values are usable block
 * breaks: every damageable tool keeps its last point in reserve, matching ToolTier's
 * nearly-broken boundary.
 */
public record ServicePolicy(ServiceProfile profile,
                            int targetToolUsableDurability,
                            int channelToolUsableDurability,
                            int foodMinUnits,
                            int torchMinCount,
                            int freeSlotsMin,
                            int emergencyBlocksReserved,
                            int futureStickReserve,
                            boolean craftingTableRequired) {
    public ServicePolicy {
        if (profile == null || targetToolUsableDurability < 0
                || channelToolUsableDurability < 0 || foodMinUnits < 0
                || torchMinCount < 0
                || freeSlotsMin < 0 || emergencyBlocksReserved < 0
                || futureStickReserve < 0) {
            throw new IllegalArgumentException("invalid_mining_service_policy");
        }
        boolean valid = switch (profile) {
            case ORE_BATCH -> targetToolUsableDurability == 1
                    && (channelToolUsableDurability == 0
                    || channelToolUsableDurability
                    == MiningBudget.TUNNELING_SERVICE_TARGET
                    * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY)
                    && foodMinUnits == MiningFoodReserve.MIN_DEEP_MINE_UNITS
                    && torchMinCount == 0
                    && freeSlotsMin == MiningServiceTask.MIN_FREE_MAIN_SLOTS
                    // A terminal parent hand-off may bind a larger already-provisioned stone
                    // horizon. It never invents or refills that reserve; disposal and the final
                    // attestation merely prove it was not consumed as mining spoil.
                    && emergencyBlocksReserved >= MiningServiceTask.EMERGENCY_BLOCK_RESERVE
                    && futureStickReserve == 0
                    && !craftingTableRequired;
            case RARE_ORE_BATCH -> targetToolUsableDurability >= 1
                    && targetToolUsableDurability <= 8
                    && channelToolUsableDurability
                    == MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                    * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY
                    && torchMinCount >= MiningBudget.RARE_BATCH_TORCH_LIMIT
                    && torchMinCount % MiningBudget.RARE_BATCH_TORCH_LIMIT == 0
                    && freeSlotsMin == MiningServiceTask.MIN_FREE_MAIN_SLOTS
                    && (emergencyBlocksReserved
                    == MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE
                    || emergencyBlocksReserved == MiningServiceTask.EMERGENCY_BLOCK_RESERVE)
                    && foodMinUnits == MiningBudget.rareServiceFoodMinimum(
                    emergencyBlocksReserved == MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE
                            ? 0 : 1)
                    && futureStickReserve >= 0
                    && futureStickReserve % (MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                    * MiningServiceTask.STONE_PICKAXE_STICK_COST) == 0
                    && futureStickReserve == Math.max(0,
                    torchMinCount / MiningBudget.RARE_BATCH_TORCH_LIMIT - 1)
                    * MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                    * MiningServiceTask.STONE_PICKAXE_STICK_COST
                    && craftingTableRequired;
            case RARE_DESCENT_KIT -> targetToolUsableDurability == 8
                    && channelToolUsableDurability == 5 * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY
                    && foodMinUnits == MiningBudget.RARE_BOOTSTRAP_FOOD
                    && torchMinCount == MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES
                    && freeSlotsMin == MiningServiceTask.MIN_FREE_MAIN_SLOTS
                    && emergencyBlocksReserved == MiningBudget.RARE_BOOTSTRAP_STONE_LIKE
                    && futureStickReserve
                    == MiningBudget.DIAMOND_STACK_CHANNEL_REPAIR_STICKS
                    && craftingTableRequired;
            case OBSIDIAN_PREFLIGHT, OBSIDIAN_8 -> targetToolUsableDurability >= 1
                    && channelToolUsableDurability
                    == MiningBudget.TUNNELING_SERVICE_TARGET
                    * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY
                    && foodMinUnits == MiningFoodReserve.MIN_DEEP_MINE_UNITS
                    && torchMinCount == 0
                    && freeSlotsMin == MiningServiceTask.MIN_FREE_MAIN_SLOTS
                    && futureStickReserve == futureSticksForRemainingTarget(
                    targetToolUsableDurability)
                    && futureStickReserve % (MiningBudget.TUNNELING_SERVICE_TARGET
                    * MiningServiceTask.STONE_PICKAXE_STICK_COST) == 0
                    && emergencyBlocksReserved == stoneReserveFor(futureStickReserve)
                    && craftingTableRequired;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid_mining_service_policy:" + profile);
        }
    }

    public static ServicePolicy defaultOre(boolean maintainTunnelingTools) {
        return new ServicePolicy(
                ServiceProfile.ORE_BATCH,
                1,
                maintainTunnelingTools
                        ? MiningBudget.TUNNELING_SERVICE_TARGET
                        * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY : 0,
                MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                0,
                MiningServiceTask.MIN_FREE_MAIN_SLOTS,
                MiningServiceTask.EMERGENCY_BLOCK_RESERVE,
                0,
                false);
    }

    public static ServicePolicy capacityHandoff(int protectedStoneLike) {
        return new ServicePolicy(
                ServiceProfile.ORE_BATCH,
                1,
                0,
                MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                0,
                MiningServiceTask.MIN_FREE_MAIN_SLOTS,
                Math.max(MiningServiceTask.EMERGENCY_BLOCK_RESERVE, protectedStoneLike),
                0,
                false);
    }

    public static ServicePolicy rareOreBatch(int targetCount, int completedBoundary) {
        return rareOreBatch(targetCount, completedBoundary, 0);
    }

    public static ServicePolicy rareDescentKit(int targetCount) {
        if (targetCount != 64) {
            throw new IllegalArgumentException(
                    "invalid_rare_descent_kit_target:" + targetCount);
        }
        return new ServicePolicy(
                ServiceProfile.RARE_DESCENT_KIT,
                8,
                5 * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY,
                MiningBudget.RARE_BOOTSTRAP_FOOD,
                MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES,
                MiningServiceTask.MIN_FREE_MAIN_SLOTS,
                MiningBudget.RARE_BOOTSTRAP_STONE_LIKE,
                MiningBudget.DIAMOND_STACK_CHANNEL_REPAIR_STICKS,
                true);
    }

    public static ServicePolicy rareOreBatch(int targetCount,
                                             int completedBoundary,
                                             int resourceRetriesUsed) {
        if (targetCount < MiningBudget.EXPEDITION_THRESHOLD
                || completedBoundary < 0 || completedBoundary >= targetCount
                || resourceRetriesUsed < 0
                || resourceRetriesUsed
                >= MiningBudget.rareMissionResourceEpochCapacity(
                MiningBudget.rareMissionBatchCount(targetCount))) {
            throw new IllegalArgumentException("invalid_rare_ore_service_boundary:target="
                    + targetCount + ":boundary=" + completedBoundary
                    + ":resource_retries=" + resourceRetriesUsed);
        }
        // Margin epochs (>= 2) reuse the epoch-one retry shape: the open batch owns exactly
        // one more bounded epoch after this service, while the mission-level margin ledger
        // stays in GoalExecutor's checkpoint. Margin torches/sticks were bootstrapped extra,
        // so these per-boundary minimums remain floors, never refills.
        int shapeEpoch = Math.min(resourceRetriesUsed,
                MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH);
        int remainingBatches = remainingRareBatches(targetCount, completedBoundary);
        int remainingResourceEpochs = remainingBatches
                * MiningBudget.RARE_RESOURCE_EPOCHS_PER_BATCH - shapeEpoch;
        int sticksPerRepair = MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                * MiningServiceTask.STONE_PICKAXE_STICK_COST;
        return new ServicePolicy(
                ServiceProfile.RARE_ORE_BATCH,
                Math.min(8, targetCount - completedBoundary),
                MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                        * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY,
                MiningBudget.rareServiceFoodMinimum(shapeEpoch),
                remainingResourceEpochs * MiningBudget.RARE_BATCH_TORCH_LIMIT,
                MiningServiceTask.MIN_FREE_MAIN_SLOTS,
                shapeEpoch > 0
                        ? MiningServiceTask.EMERGENCY_BLOCK_RESERVE
                        : MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE,
                Math.max(0, remainingResourceEpochs - 1)
                        * sticksPerRepair,
                true);
    }

    public static int remainingRareBatches(int targetCount, int completedBoundary) {
        int remaining = Math.max(0, targetCount - completedBoundary);
        return (int) ((remaining + 7L) / 8L);
    }

    public static ServicePolicy obsidian8(int targetCount, int completedBoundary) {
        if (targetCount <= 0 || completedBoundary <= 0
                || completedBoundary % MiningServiceTask.OBSIDIAN_SERVICE_INTERVAL != 0
                || completedBoundary >= targetCount) {
            throw new IllegalArgumentException("invalid_obsidian_service_boundary:target="
                    + targetCount + ":boundary=" + completedBoundary);
        }
        int remainingTarget = targetCount - completedBoundary;
        int futureStickReserve = futureSticksForRemainingTarget(remainingTarget);
        return new ServicePolicy(
                ServiceProfile.OBSIDIAN_8,
                remainingTarget,
                MiningBudget.TUNNELING_SERVICE_TARGET
                        * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY,
                MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                0,
                MiningServiceTask.MIN_FREE_MAIN_SLOTS,
                stoneReserveFor(futureStickReserve),
                futureStickReserve,
                true);
    }

    public static ServicePolicy obsidianPreflight(int targetCount) {
        if (targetCount <= 0) {
            throw new IllegalArgumentException(
                    "invalid_obsidian_preflight_target:" + targetCount);
        }
        int remainingTarget = targetCount;
        int futureStickReserve = futureSticksForRemainingTarget(remainingTarget);
        return new ServicePolicy(
                ServiceProfile.OBSIDIAN_PREFLIGHT,
                remainingTarget,
                MiningBudget.TUNNELING_SERVICE_TARGET
                        * MiningServiceTask.STONE_PICKAXE_USABLE_DURABILITY,
                MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                0,
                MiningServiceTask.MIN_FREE_MAIN_SLOTS,
                stoneReserveFor(futureStickReserve),
                futureStickReserve,
                true);
    }

    public boolean maintainsTunnelingTools() {
        return channelToolUsableDurability > 0;
    }

    public static int futureSticksBeforeFirstPool(int targetCount) {
        return futureSticksForRemainingTarget(Math.max(1, targetCount));
    }

    public static int bootstrapStickTarget(int targetCount) {
        return futureSticksBeforeFirstPool(targetCount)
                + MiningBudget.TUNNELING_SERVICE_TARGET * MiningServiceTask.STONE_PICKAXE_STICK_COST;
    }

    public static int bootstrapStoneLikeTarget(int targetCount) {
        return stoneReserveFor(futureSticksBeforeFirstPool(targetCount))
                + MiningBudget.TUNNELING_SERVICE_TARGET * MiningServiceTask.STONE_PICKAXE_HEAD_COST;
    }

    private static int futureSticksForRemainingTarget(int remainingTarget) {
        return (Math.max(1, remainingTarget) - 1) / MiningServiceTask.OBSIDIAN_SERVICE_INTERVAL
                * MiningBudget.TUNNELING_SERVICE_TARGET
                * MiningServiceTask.STONE_PICKAXE_STICK_COST;
    }

    private static int stoneReserveFor(int futureSticks) {
        return MiningServiceTask.EMERGENCY_BLOCK_RESERVE
                + futureSticks / MiningServiceTask.STONE_PICKAXE_STICK_COST * MiningServiceTask.STONE_PICKAXE_HEAD_COST;
    }
}
