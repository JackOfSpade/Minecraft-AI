package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

public final class StripMineTask extends AbstractTask {
    public static final String STRICT_SURVIVAL_REJECTION =
            "legacy_strip_mine_unavailable_in_strict_survival:use_mine_ore_or_achieve_goal";
    /**
     * The coarse {@link #profileRejectionReason} check only looks at the operating profile enum.
     * An operator who enables OPERATOR mode for something unrelated (e.g. manual teleport) but has
     * not explicitly turned on hidden-block-scan capability must still be denied this legacy task's
     * unguarded {@code OreScan.adjacentHazard(ServerLevel, BlockPos)} raw-world hazard reads.
     */
    public static final String HIDDEN_BLOCK_SCAN_REJECTION =
            "legacy_strip_mine_requires_hidden_block_scan_capability:use_mine_ore_or_achieve_goal";

    private enum Phase {
        PREP,
        TUNNEL,
        MINE_BLOCK,
        SCAN_VEIN,
        MINE_VEIN,
        LIGHT,
        MOVE,
        RETURN,
        RETURN_DEPOSIT,
        RETURN_TO_WORK,
        DONE
    }

    private enum StepKind {
        TUNNEL,
        DESCEND,
        MOVE_ONLY
    }

    private static final int MAX_VEIN_BLOCKS = 64;
    private static final double REACH_SQUARED = 20.25D;

    private final Direction direction;
    private final int length;
    private final int branchSpacing;
    private final BlockPos depotChest;
    private final Set<Block> targetOres;
    private final boolean veinOnly;
    private final boolean autoDescend;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final Deque<BlockPos> blocksToMine = new ArrayDeque<>();
    private final Deque<BlockPos> veinBlocks = new ArrayDeque<>();
    private final Set<BlockPos> queuedVeinBlocks = new HashSet<>();
    private final BlockMiner miner = new BlockMiner();
    private Phase phase = Phase.PREP;
    private Step currentStep;
    private BlockPos origin;
    private BlockPos activeDepotChest;
    private BlockPos returnStand;
    private BlockPos currentMiningBlock;
    private BlockPos currentVeinBlock;
    private boolean miningStarted;
    private boolean returningForFinalStop;
    private int tunnelBlocksMined;
    private int veinBlocksMined;
    private int distanceCompleted;
    private int descentStepsPlanned;
    private String note = "";

    public StripMineTask(Direction direction, int length, int branchSpacing, BlockPos depotChest, Set<Block> targetOres) {
        this(direction, length, branchSpacing, depotChest, targetOres, false);
    }

    public static StripMineTask mineNearbyVein(Set<Block> targetOres) {
        return new StripMineTask(Direction.NORTH, 0, 0, null, targetOres, true);
    }

    public static StripMineTask forOre(Block targetOre, int count) {
        int length = Math.min(128, Math.max(64, count * 16));
        return new StripMineTask(Direction.NORTH, length, 4, null, OreScan.oreFamily(targetOre), false, true);
    }

    /**
     * The legacy implementation still contains raw world reads that have not crossed the
     * observable-world boundary. Keep the public constructors for operator/legacy callers, but
     * fail closed whenever a strict-survival entry reaches the task directly.
     */
    public static Optional<String> profileRejectionReason(OperatingProfile profile) {
        OperatingProfile effective = profile == null ? OperatingProfile.STRICT_SURVIVAL : profile;
        return effective == OperatingProfile.STRICT_SURVIVAL
                ? Optional.of(STRICT_SURVIVAL_REJECTION)
                : Optional.empty();
    }

    private StripMineTask(Direction direction,
                          int length,
                          int branchSpacing,
                          BlockPos depotChest,
                          Set<Block> targetOres,
                          boolean veinOnly) {
        this(direction, length, branchSpacing, depotChest, targetOres, veinOnly,
                !veinOnly && targetOres != null && !targetOres.isEmpty());
    }

    private StripMineTask(Direction direction,
                          int length,
                          int branchSpacing,
                          BlockPos depotChest,
                          Set<Block> targetOres,
                          boolean veinOnly,
                          boolean autoDescend) {
        this.direction = direction.getAxis() == Direction.Axis.Y ? Direction.NORTH : direction;
        this.length = Math.max(0, length);
        this.branchSpacing = Math.max(0, branchSpacing);
        this.depotChest = depotChest == null ? null : depotChest.immutable();
        this.targetOres = targetOres == null || targetOres.isEmpty() ? OreScan.COMMON_ORES : OreScan.expandOreFamilies(targetOres);
        this.veinOnly = veinOnly;
        this.autoDescend = autoDescend;
    }

    @Override
    public String name() {
        return veinOnly ? "mine_vein" : "strip_mine";
    }

    @Override
    public String describe() {
        String ores = targetOres.stream()
                .map(BuiltInRegistries.BLOCK::getKey)
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(","));
        return name() + " dir=" + direction
                + " distance=" + distanceCompleted + "/" + length
                + " tunnel_blocks=" + tunnelBlocksMined
                + " vein_blocks=" + veinBlocksMined
                + " phase=" + phase
                + (note.isBlank() ? "" : " note=" + note)
                + " ores=" + ores;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (veinOnly) {
            return veinBlocks.isEmpty() ? 0.0D : Math.min(0.95D, veinBlocksMined / (double) (veinBlocksMined + veinBlocks.size()));
        }
        return length == 0 ? 0.0D : Math.min(0.95D, (double) distanceCompleted / length);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        Optional<String> profileRejection = profileRejectionReason(MinecraftAiConfig.get().profile());
        if (profileRejection.isPresent()) {
            String reason = profileRejection.orElseThrow();
            fail(reason);
            BotLog.action(bot, "strip_mine_profile_gate",
                    "result", "rejected",
                    "profile", OperatingProfile.STRICT_SURVIVAL.configValue(),
                    "reason", reason,
                    "task", name());
            return;
        }
        // The coarse profile gate above only fails closed under STRICT_SURVIVAL. This task's
        // mineBlock/mineVein/safeStandTarget legacy paths still call the raw, un-gated
        // OreScan.adjacentHazard(ServerLevel, BlockPos) overload, so an OPERATOR-profile bot must
        // also hold the fine-grained HIDDEN_BLOCK_SCAN capability before this task may run at all;
        // otherwise an operator who enabled OPERATOR mode for something unrelated (e.g. manual
        // teleport) while leaving hiddenBlockScan unset/false would still get unguarded hazard
        // x-ray from this file.
        var hiddenBlockScanDecision = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "strip_mine_onStart");
        if (!hiddenBlockScanDecision.allowed()) {
            fail(HIDDEN_BLOCK_SCAN_REJECTION);
            BotLog.action(bot, "strip_mine_capability_gate",
                    "result", "rejected",
                    "capability", PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                    "reason", HIDDEN_BLOCK_SCAN_REJECTION,
                    "decision_reason", hiddenBlockScanDecision.reason(),
                    "task", name());
            return;
        }
        phase = Phase.PREP;
        origin = bot.blockPosition().immutable();
        activeDepotChest = resolveDepotChest(bot);
        steps.clear();
        blocksToMine.clear();
        veinBlocks.clear();
        queuedVeinBlocks.clear();
        currentStep = null;
        currentMiningBlock = null;
        currentVeinBlock = null;
        miningStarted = false;
        returningForFinalStop = false;
        descentStepsPlanned = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > Math.max(2400, (descentStepsPlanned + length + branchSpacing * Math.max(1, length / Math.max(1, branchSpacing))) * 400)) {
            // "strip_mine_timeout" alone does not say which phase it stalled in or how much of the
            // plan it actually got through (tunneling, a depot round-trip, walking back to work,
            // ...); log that breadcrumb once, right before the generic task_failed line fires.
            BotLog.warn(LogCategory.TASK, bot, "strip_mine_timeout_context", "phase", phase,
                    "distance", distanceCompleted, "tunnel_blocks", tunnelBlocksMined,
                    "vein_blocks", veinBlocksMined, "note", note);
            fail("strip_mine_timeout");
            return;
        }
        // F2: tool-prerequisite gate -- with no pickaxe at all, forbid tunneling bare-handed
        // (avoid "skipping toolmaking and mining by hand directly"). Guide the bot to use
        // mine_ore instead (a deterministic target auto-prepares a pickaxe before mining) --
        // staying fully self-sufficient throughout and never asking for help.
        if (ToolTier.bestPickaxeTier(bot) <= ToolTier.NONE) {
            BotLog.action(bot, "strip_mine_tool_gate", "result", "fail", "reason", "no_pickaxe");
            fail("need_pickaxe:use mine_ore to auto-prepare a pickaxe first");
            return;
        }
        switch (phase) {
            case PREP -> prep(bot);
            case TUNNEL -> tunnel(bot);
            case MINE_BLOCK -> mineBlock(bot);
            case SCAN_VEIN -> scanVein(bot);
            case MINE_VEIN -> mineVein(bot);
            case LIGHT -> light(bot);
            case MOVE -> move(bot);
            case RETURN -> returnToDepot(bot);
            case RETURN_DEPOSIT -> deposit(bot);
            case RETURN_TO_WORK -> returnToWork(bot);
            case DONE -> complete();
        }
    }

    private void prep(AIPlayerEntity bot) {
        if (veinOnly) {
            scanNearbyVeins(bot, bot.blockPosition(), 6);
            if (veinBlocks.isEmpty()) {
                fail("no_ore_vein_in_range");
                return;
            }
            phase = Phase.MINE_VEIN;
            return;
        }
        buildPlan(bot);
        phase = Phase.TUNNEL;
    }

    private void buildPlan(AIPlayerEntity bot) {
        steps.clear();
        descentStepsPlanned = 0;
        BlockPos miningOrigin = origin;
        if (shouldDescendToOreLayer(bot)) {
            int targetY = Math.max(bot.level().getMinY() + 6, OreScan.preferredMiningY(targetOres));
            descentStepsPlanned = Math.max(0, origin.getY() - targetY);
            // FLOW-1: staircase descent-mining -- each step moves 1 block horizontally + drops
            // 1 block (1:1 ratio), forming stairs the bot can walk back up, rather than falling
            // straight down vertically. Each DESCEND step mines the stand position plus the 2
            // blocks above it (body clearance), with solid ground beneath it.
            BlockPos cursor = origin;
            for (int step = 1; step <= descentStepsPlanned; step++) {
                cursor = cursor.relative(direction).below().immutable();
                steps.addLast(new Step(cursor, StepKind.DESCEND, 0));
            }
            miningOrigin = cursor;
            note = "descending_stairs_to_y:" + targetY;
        }
        Direction left = direction.getCounterClockWise();
        Direction right = direction.getClockWise();
        int branchDepth = branchSpacing <= 0 ? 0 : Math.min(branchSpacing, 8);
        for (int distance = 1; distance <= length; distance++) {
            BlockPos main = miningOrigin.relative(direction, distance);
            steps.addLast(new Step(main, StepKind.TUNNEL, distance));
            if (branchDepth > 0 && distance % branchSpacing == 0) {
                addBranch(main, left, branchDepth);
                addBranch(main, right, branchDepth);
            }
        }
    }

    private boolean shouldDescendToOreLayer(AIPlayerEntity bot) {
        if (!autoDescend || veinOnly || targetOres.stream().noneMatch(OreScan::isOreBlock)) {
            return false;
        }
        int targetY = Math.max(bot.level().getMinY() + 6, OreScan.preferredMiningY(targetOres));
        if (origin.getY() <= targetY + 2) {
            return false;
        }
        return !hasExposedOreNearby(bot, origin, 12, 8);
    }

    private boolean hasExposedOreNearby(AIPlayerEntity bot, BlockPos center, int horizontalRadius, int verticalRadius) {
        BlockPos min = center.offset(-horizontalRadius, -verticalRadius, -horizontalRadius);
        BlockPos max = center.offset(horizontalRadius, verticalRadius, horizontalRadius);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (Math.abs(pos.getY() - center.getY()) > 3) {
                continue;
            }
            if (OreScan.isOre(bot.level().getBlockState(pos), targetOres)
                    && isExposed(bot.level(), pos)
                    && io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExposed(ServerLevel world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (world.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private void addBranch(BlockPos base, Direction side, int depth) {
        for (int branch = 1; branch <= depth; branch++) {
            steps.addLast(new Step(base.relative(side, branch), StepKind.TUNNEL, base.distManhattan(origin)));
        }
        for (int branch = depth - 1; branch >= 0; branch--) {
            steps.addLast(new Step(base.relative(side, branch), StepKind.MOVE_ONLY, base.distManhattan(origin)));
        }
    }

    private void tunnel(AIPlayerEntity bot) {
        if (shouldReturn(bot)) {
            beginReturn(bot);
            return;
        }
        currentStep = steps.pollFirst();
        if (currentStep == null) {
            // The whole planned tunnel is drained here -- the only place that knows this run
            // finished the plan rather than bailing early. Without this, a successful
            // task_completed line carries no count of what was actually mined.
            BotLog.action(bot, "strip_mine_plan_complete", "distance", distanceCompleted,
                    "tunnel_blocks", tunnelBlocksMined, "vein_blocks", veinBlocksMined);
            note = "completed";
            phase = Phase.DONE;
            return;
        }
        distanceCompleted = Math.max(distanceCompleted, Math.min(length, currentStep.distance()));
        if (currentStep.kind() == StepKind.MOVE_ONLY) {
            phase = Phase.MOVE;
            move(bot);
            return;
        }
        if (!safeStandTarget(bot.level(), currentStep.stand())) {
            fail("unsafe_tunnel_target: " + shortPos(currentStep.stand()));
            return;
        }
        blocksToMine.clear();
        addIfSolid(bot.level(), currentStep.stand());
        addIfSolid(bot.level(), currentStep.stand().above());
        if (currentStep.kind() == StepKind.DESCEND) {
            addIfSolid(bot.level(), currentStep.stand().above(2));
        }
        if (blocksToMine.isEmpty()) {
            phase = Phase.SCAN_VEIN;
        } else {
            phase = Phase.MINE_BLOCK;
        }
    }

    private void mineBlock(AIPlayerEntity bot) {
        if (currentMiningBlock == null) {
            currentMiningBlock = blocksToMine.pollFirst();
            miningStarted = false;
            if (currentMiningBlock == null) {
                phase = Phase.SCAN_VEIN;
                return;
            }
        }
        if (bot.level().getBlockState(currentMiningBlock).isAir()) {
            Standability.clearCache();
            currentMiningBlock = null;
            tunnelBlocksMined++;
            return;
        }
        if (OreScan.adjacentHazard(bot.level(), currentMiningBlock)) {
            fail("hazard_near: " + shortPos(currentMiningBlock));
            return;
        }
        if (!canReach(bot, currentMiningBlock)) {
            fail("block_out_of_reach: " + shortPos(currentMiningBlock));
            return;
        }
        // P1-b: mining goes through the shared BlockMiner (only starts when idle, never
        // re-issues and resets progress, uses the correct face).
        if (miner.target() == null || !miner.target().equals(currentMiningBlock)) {
            miner.begin(bot, currentMiningBlock);
        }
        if (miner.tick(bot) == BlockMiner.Status.FAILED) {
            fail(miner.failureReason());
        }
    }

    private void scanVein(AIPlayerEntity bot) {
        scanNearbyVeins(bot, currentStep == null ? bot.blockPosition() : currentStep.stand(), 3);
        if (!veinBlocks.isEmpty()) {
            phase = Phase.MINE_VEIN;
            return;
        }
        phase = Phase.LIGHT;
    }

    private void mineVein(AIPlayerEntity bot) {
        if (currentVeinBlock == null) {
            currentVeinBlock = veinBlocks.pollFirst();
            miningStarted = false;
            if (currentVeinBlock == null) {
                if (veinOnly) {
                    complete();
                } else {
                    phase = Phase.LIGHT;
                }
                return;
            }
        }
        if (bot.level().getBlockState(currentVeinBlock).isAir()) {
            Standability.clearCache();
            currentVeinBlock = null;
            veinBlocksMined++;
            return;
        }
        if (!OreScan.isOre(bot.level().getBlockState(currentVeinBlock), targetOres)) {
            currentVeinBlock = null;
            return;
        }
        if (OreScan.adjacentHazard(bot.level(), currentVeinBlock)) {
            note = "skip_hazard_ore:" + shortPos(currentVeinBlock);
            currentVeinBlock = null;
            return;
        }
        if (!canReach(bot, currentVeinBlock)) {
            BlockPos stand = adjacentStand(bot, currentVeinBlock);
            if (stand == null) {
                note = "skip_unreachable_ore:" + shortPos(currentVeinBlock);
                currentVeinBlock = null;
                return;
            }
            if (!near(bot, stand)) {
                if (bot.getActionPack().isPathExecutorIdle()) {
                    ActionResult result = bot.getActionPack().startPathTo(stand);
                    if (result.isFailed()) {
                        note = "skip_unreachable_ore:" + result.reason();
                        currentVeinBlock = null;
                    }
                }
                return;
            }
            bot.getActionPack().stopAll();
        }
        // P1-b: vein-block mining also goes through BlockMiner.
        if (miner.target() == null || !miner.target().equals(currentVeinBlock)) {
            miner.begin(bot, currentVeinBlock);
        }
        if (miner.tick(bot) == BlockMiner.Status.FAILED) {
            note = miner.failureReason();
            currentVeinBlock = null;
        }
    }

    private void light(AIPlayerEntity bot) {
        if (!MinecraftAiConfig.get().mining().placeTorches()
                || distanceCompleted == 0
                || distanceCompleted % 8 != 0
                || bot.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, bot.blockPosition()) >= 8) {
            phase = Phase.MOVE;
            return;
        }
        int torchSlot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        if (torchSlot < 0) {
            note = "no_torch";
            phase = Phase.MOVE;
            return;
        }
        InventoryAction.equipFromSlot(bot, torchSlot);
        Optional<BlockPos> torchPos = torchPosition(bot);
        if (torchPos.isPresent()) {
            ActionResult result = BuildAction.placeBlockAt(bot, torchPos.get());
            if (result.isFailed()) {
                note = "torch_failed:" + result.reason();
            }
        }
        // Slot identity is not stable once a torch is equipped (mirrors
        // MineValuablesTask.maybePlaceTorch): restore the active mining tool now, using the
        // upcoming tunnel step (or the just-completed one if the plan is drained) as a reasonable
        // proxy target, rather than leaving the torch equipped through shouldReturn()'s very next
        // durability check.
        Step upcoming = steps.peekFirst();
        BlockPos toolTarget = upcoming != null ? upcoming.stand() : currentStep != null ? currentStep.stand() : null;
        if (toolTarget != null) {
            ToolSelector.equipBestTool(bot, bot.level().getBlockState(toolTarget));
        }
        phase = Phase.MOVE;
    }

    private void move(AIPlayerEntity bot) {
        if (currentStep == null || near(bot, currentStep.stand())) {
            bot.getActionPack().stopAll();
            phase = Phase.TUNNEL;
            return;
        }
        if ((currentStep.kind() == StepKind.TUNNEL || currentStep.kind() == StepKind.DESCEND)
                && currentStep.stand().distManhattan(bot.blockPosition()) <= 4) {
            if (bot.getActionPack().isWalkToIdle()) {
                bot.getActionPack().startWalkTo(currentStep.stand().getCenter());
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(currentStep.stand());
            if (result.isFailed()) {
                fail("path_to_tunnel_failed: " + result.reason());
            }
        }
    }

    private boolean shouldReturn(AIPlayerEntity bot) {
        MinecraftAiConfig.Mining mining = MinecraftAiConfig.get().mining();
        if (freeMainSlots(bot) < mining.returnWhenFreeSlots()) {
            note = "inventory_near_full";
            return true;
        }
        ItemStack selected = bot.getMainHandItem();
        if (selected.isDamageableItem()
                && selected.getMaxDamage() > 0
                && selected.getMaxDamage() - selected.getDamageValue() <= selected.getMaxDamage() * mining.toolDurabilityFloor()) {
            note = "tool_durability_low";
            return true;
        }
        return false;
    }

    private void beginReturn(AIPlayerEntity bot) {
        // Why the bot broke off tunneling for a depot round-trip is only known here, this tick --
        // by the time a subsequent RETURN/RETURN_TO_WORK step fails, the trigger (full inventory vs.
        // dying tool) would otherwise leave no trace behind the generic path-failure reason.
        BotLog.action(bot, "strip_mine_return", "reason", note, "distance", distanceCompleted,
                "tunnel_blocks", tunnelBlocksMined, "vein_blocks", veinBlocksMined);
        returnStand = bot.blockPosition().immutable();
        returningForFinalStop = "tool_durability_low".equals(note);
        if (activeDepotChest == null) {
            phase = Phase.DONE;
            return;
        }
        phase = Phase.RETURN;
    }

    private void returnToDepot(AIPlayerEntity bot) {
        BlockPos stand = adjacentStand(bot, activeDepotChest);
        if (stand == null) {
            fail("no_stand_position_for_depot");
            return;
        }
        if (near(bot, stand)) {
            bot.getActionPack().stopAll();
            phase = Phase.RETURN_DEPOSIT;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(stand);
            if (result.isFailed()) {
                fail("return_path_failed: " + result.reason());
            }
        }
    }

    private void deposit(AIPlayerEntity bot) {
        if (activeDepotChest == null
                || bot.getEyePosition().distanceToSqr(activeDepotChest.getCenter()) > REACH_SQUARED
                || !io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, activeDepotChest)) {
            phase = Phase.RETURN;
            return;
        }
        Container container = ContainerAction.resolve(bot, activeDepotChest).orElse(null);
        if (container == null) {
            fail("depot_missing");
            return;
        }
        ContainerAction.TransferResult result = ContainerAction.depositOne(container, bot, depositFilter(), 64);
        if (result.movedAny()) {
            return;
        }
        if (returningForFinalStop) {
            phase = Phase.DONE;
            return;
        }
        phase = Phase.RETURN_TO_WORK;
    }

    private void returnToWork(AIPlayerEntity bot) {
        if (returnStand == null || near(bot, returnStand)) {
            bot.getActionPack().stopAll();
            phase = Phase.TUNNEL;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(returnStand);
            if (result.isFailed()) {
                fail("return_to_work_failed: " + result.reason());
            }
        }
    }

    private Predicate<ItemStack> depositFilter() {
        return stack -> !ContainerAction.isReservedTool(stack)
                && !stack.is(Items.TORCH)
                && !stack.has(DataComponents.FOOD);
    }

    private BlockPos resolveDepotChest(AIPlayerEntity bot) {
        if (depotChest != null) {
            return depotChest;
        }
        return BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.level(), "depot", "home", "base", "chest")
                .flatMap(pos -> ContainerAction.resolve(bot, pos).isPresent()
                        ? Optional.of(pos.immutable())
                        : ContainerTask.nearestContainerNear(bot, pos, 4))
                .orElse(null);
    }

    private void addIfSolid(ServerLevel world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) {
            blocksToMine.addLast(pos.immutable());
        }
    }

    private void scanNearbyVeins(AIPlayerEntity bot, BlockPos center, int radius) {
        BlockPos.betweenClosedStream(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))
                .map(BlockPos::immutable)
                .filter(pos -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> OreScan.isOre(bot.level().getBlockState(pos), targetOres))
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition())))
                .findFirst()
                .ifPresent(seed -> OreScan.veinFrom(bot, seed, targetOres, MAX_VEIN_BLOCKS)
                        .stream()
                        .filter(pos -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                        .forEach(pos -> {
                            if (queuedVeinBlocks.add(pos)) {
                                veinBlocks.addLast(pos);
                            }
                        }));
    }

    private Optional<BlockPos> torchPosition(AIPlayerEntity bot) {
        BlockPos base = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = base.relative(direction);
            if (bot.level().getBlockState(pos).isAir()
                    && !bot.level().getBlockState(pos.below()).isAir()) {
                return Optional.of(pos.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean safeStandTarget(ServerLevel world, BlockPos stand) {
        if (OreScan.adjacentHazard(world, stand)) {
            return false;
        }
        if (!world.getFluidState(stand).isEmpty() || !world.getFluidState(stand.above()).isEmpty()) {
            return false;
        }
        return !world.getBlockState(stand.below()).isAir()
                && world.getFluidState(stand.below()).isEmpty();
    }

    private static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return bot.getEyePosition().distanceToSqr(target.getCenter()) <= REACH_SQUARED;
    }

    private static boolean near(AIPlayerEntity bot, BlockPos target) {
        return bot.blockPosition().distSqr(target) <= 1.0D;
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos target) {
        Standability.clearCache();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction);
            if (Standability.isStandable(bot.level(), candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private static int freeMainSlots(AIPlayerEntity bot) {
        int free = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record Step(BlockPos stand, StepKind kind, int distance) {
    }
}
