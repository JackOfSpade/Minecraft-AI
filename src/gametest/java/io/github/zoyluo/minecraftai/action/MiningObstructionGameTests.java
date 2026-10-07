package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.MockPlayers;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.task.EpisodeMemory;
import io.github.zoyluo.minecraftai.task.GatherQuotaTask;
import io.github.zoyluo.minecraftai.task.MineTask;
import io.github.zoyluo.minecraftai.task.SensingArena;
import io.github.zoyluo.minecraftai.task.TaskState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A log the bot sees through leaves is mined by breaking the leaves first, in a real server with the real tags and a real bot: it
 * first tries the log (a line a hand reaches), and only when there is none breaks the see-through block nearest the eye on the
 * best line, one at a time, re-checking after each, and then the log. A block that may not be broken (a pane, a fence, a leaf
 * beside visible lava) is left alone and the target is refused with a typed reason. The bot stands at the origin of the arena, the
 * log is four cells east of it at eye level, and a wall of whatever the case needs stands in the cells between.
 */
public final class MiningObstructionGameTests {
    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    // Persistent: a leaf the random tick may decay would break a block the test did not count.
    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** A bot at {@code feet}, a stone floor under a runway east of it, and air above. The arena stays inside the 8x8 structure. */
    private record Arena(GameTestHelper context, ServerLevel world, BlockPos feet, String name, AIPlayerEntity bot) {
        static Arena begin(GameTestHelper context, String name) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(1, 2, 3));
            for (int dx = -1; dx <= 6; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 4; dy++) {
                        world.setBlock(feet.offset(dx, dy, dz), AIR, Block.UPDATE_ALL);
                    }
                    world.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            Vec3 pose = Vec3.atBottomCenterOf(feet);
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(world.getServer(), name, world, pose, 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            bot.teleportTo(world, pose.x, pose.y, pose.z, Set.of(), 0.0F, 0.0F, true);
            bot.setOnGround(true);
            return new Arena(context, world, feet, name, bot);
        }

        BlockPos at(int east, int up, int south) {
            return feet.offset(east, up, south);
        }

        /** The log the bot wants: four cells east, at eye level. */
        BlockPos log() {
            return at(4, 1, 0);
        }

        Arena set(BlockPos pos, BlockState state) {
            world.setBlock(pos, state, Block.UPDATE_ALL);
            return this;
        }

        /** A wall in the plane x = {@code east}: {@code halfWidth} cells either side of the bot's row, three cells tall. */
        List<BlockPos> wall(int east, int halfWidth, BlockState state) {
            List<BlockPos> cells = new ArrayList<>();
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = at(east, dy, dz);
                    set(pos, state);
                    cells.add(pos);
                }
            }
            return cells;
        }

        void giveAxe() {
            InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        }

        void finish() {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
            context.succeed();
        }

        void fail(String message) {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
            context.fail(Component.nullToEmpty(message));
        }

        void require(boolean condition, String message) {
            if (!condition) {
                fail(message);
            }
        }

        /** The bot's own log lines with {@code event}, oldest first. */
        List<String> events(String event) {
            List<String> lines = SensingArena.botLog(name);
            require(lines != null, "the per-bot log is unavailable");
            return lines.stream().filter(line -> line.contains("event=" + event + " ")).toList();
        }

        boolean sees(BlockPos pos) {
            return ObservableWorldQuery.canObserveBlock(bot, pos)
                    && ObservableWorldQuery.canObserveBlockCellFace(bot, pos);
        }

        boolean reaches(BlockPos pos) {
            return MiningController.currentObservedTarget(bot, pos);
        }
    }

    /** When each watched cell first turned to air, in the order it happened (one break per tick, so the order is strict). */
    private static final class BreakOrder {
        private final List<BlockPos> watched;
        private final Map<BlockPos, Integer> brokenAt = new LinkedHashMap<>();

        BreakOrder(List<BlockPos> watched) {
            this.watched = watched;
        }

        void observe(ServerLevel world, int tick) {
            for (BlockPos pos : watched) {
                if (!brokenAt.containsKey(pos) && world.getBlockState(pos).isAir()) {
                    brokenAt.put(pos, tick);
                }
            }
        }

        List<BlockPos> order() {
            return new ArrayList<>(brokenAt.keySet());
        }
    }

    private static String pos(BlockPos pos) {
        return "'" + LogFields.pos(pos) + "'";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The way is cleared, nearest block first, and the log only once it is clear
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_a_log_behind_two_leaves_is_mined_only_after_both_leaves_are_broken", maxTicks = 500)
    public void aLogBehindTwoLeavesIsMinedOnlyAfterBothLeavesAreBroken(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructTwoLeaf");
        AIPlayerEntity bot = arena.bot();
        arena.giveAxe();
        List<BlockPos> watched = new ArrayList<>(arena.wall(2, 2, LEAF));
        watched.addAll(arena.wall(3, 2, LEAF));
        BlockPos log = arena.log();
        arena.set(log, LOG);
        watched.add(log);
        arena.require(arena.sees(log), "the log behind two leaf walls is not seen");
        arena.require(!arena.reaches(log), "the strict gate admits the log through the leaves");

        ActionResult started = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(started.isInProgress(), "mining the log behind two leaves was not admitted: " + started);
        BreakOrder order = new BreakOrder(watched);
        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            order.observe(arena.world(), tick[0]);
            if (!arena.world().getBlockState(log).isAir()) {
                return;
            }
            List<BlockPos> broken = order.order();
            arena.require(broken.size() == 3, "expected two leaves and then the log, got " + broken);
            arena.require(broken.get(0).getX() == arena.at(2, 0, 0).getX() && broken.get(1).getX() == arena.at(3, 0, 0).getX(),
                    "the leaf nearest the eye must go first: " + broken);
            arena.require(broken.get(2).equals(log), "the log broke before both leaves: " + broken);
            List<String> detected = arena.events("mining_obstruction_detected");
            List<String> cleared = arena.events("mining_obstruction_cleared");
            arena.require(detected.size() == 2 && cleared.size() == 2, "events: detected " + detected + " cleared " + cleared);
            arena.require(cleared.get(0).contains("obstruction=" + pos(broken.get(0)))
                            && cleared.get(1).contains("obstruction=" + pos(broken.get(1)))
                            && cleared.get(0).contains("obstruction_block='minecraft:oak_leaves'"),
                    "cleared events do not name the leaves in order: " + cleared);
            // No break START was sent for the log before the last leaf was gone: its mine_start line follows the last clearing.
            List<String> lines = SensingArena.botLog(arena.name());
            int lastCleared = -1;
            int logStart = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("event=mining_obstruction_cleared ")) {
                    lastCleared = i;
                } else if (line.contains("event=mine_start ") && line.contains("pos=" + pos(log)) && logStart < 0) {
                    logStart = i;
                }
            }
            arena.require(lastCleared >= 0 && logStart > lastCleared,
                    "the log was started before the last leaf was cleared: cleared at " + lastCleared + ", started at " + logStart);
            long leafCompletes = lines.stream().filter(line -> line.contains("event=mine_complete ")
                    && line.contains("block='minecraft:oak_leaves'") && line.contains("obstruction_of=" + pos(log))).count();
            arena.require(leafCompletes == 2, "each cleared leaf is audited as a break for the log: " + leafCompletes);
            arena.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_a_log_behind_fence_line_gap_is_mined_directly_and_no_fence_is_broken", maxTicks = 400)
    public void aLogBehindFenceLineGapIsMinedDirectlyAndNoFenceIsBroken(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructFenceGap");
        AIPlayerEntity bot = arena.bot();
        arena.giveAxe();
        List<BlockPos> fences = new ArrayList<>();
        for (int dz : new int[] {-2, -1, 1, 2}) {
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos pos = arena.at(2, dy, dz);
                arena.set(pos, Blocks.OAK_FENCE.defaultBlockState());
                fences.add(pos);
            }
        }
        BlockPos log = arena.log();
        arena.set(log, LOG);
        arena.require(arena.reaches(log), "a log seen through the gap of a fence line is not reachable (the natural line)");

        ActionResult started = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(started.isInProgress(), "mining the log through the gap was not admitted: " + started);
        context.failIfEver(() -> {
            for (BlockPos fence : fences) {
                arena.require(arena.world().getBlockState(fence).is(Blocks.OAK_FENCE), "a fence was broken at " + fence);
            }
            if (!arena.world().getBlockState(log).isAir()) {
                return;
            }
            arena.require(arena.events("mining_obstruction_detected").isEmpty(), "a clearing started for a log with a natural line");
            arena.require(arena.events("mining_obstruction_refused").isEmpty(), "a refusal for a log with a natural line");
            arena.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_a_see_through_target_is_mined_directly", maxTicks = 300)
    public void aSeeThroughTargetIsMinedDirectly(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructLeafGoal");
        AIPlayerEntity bot = arena.bot();
        BlockPos leaf = arena.at(2, 1, 0);
        arena.set(leaf, LEAF);
        arena.require(arena.reaches(leaf), "the leaf, the block a hand meets first, is not reachable");

        ActionResult started = bot.getActionPack().startMining(leaf, Direction.WEST);
        arena.require(started.isInProgress(), "mining a leaf in plain view was not admitted: " + started);
        context.failIfEver(() -> {
            if (!arena.world().getBlockState(leaf).isAir()) {
                return;
            }
            arena.require(arena.events("mining_obstruction_detected").isEmpty(), "a leaf that is the target was cleared as an obstruction");
            arena.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_the_nearer_leaf_is_broken_before_the_leaf_behind_it", maxTicks = 300)
    public void theNearerLeafIsBrokenBeforeTheLeafBehindIt(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructLeafPair");
        AIPlayerEntity bot = arena.bot();
        List<BlockPos> watched = new ArrayList<>(arena.wall(2, 1, LEAF));
        BlockPos target = arena.at(3, 1, 0);
        arena.set(target, LEAF);
        watched.add(target);
        arena.require(arena.sees(target), "the leaf behind the leaves is not seen");
        arena.require(!arena.reaches(target), "the strict gate admits a leaf through a leaf");

        ActionResult started = bot.getActionPack().startMining(target, Direction.WEST);
        arena.require(started.isInProgress(), "mining a leaf behind a leaf was not admitted: " + started);
        BreakOrder order = new BreakOrder(watched);
        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            order.observe(arena.world(), tick[0]);
            if (!arena.world().getBlockState(target).isAir()) {
                return;
            }
            List<BlockPos> broken = order.order();
            arena.require(broken.size() == 2 && broken.get(0).getX() == arena.at(2, 0, 0).getX() && broken.get(1).equals(target),
                    "the leaf in front goes first, then the target leaf: " + broken);
            arena.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_a_torch_behind_grass_is_reached_by_breaking_the_grass_first", maxTicks = 300)
    public void aTorchBehindGrassIsReachedByBreakingTheGrassFirst(GameTestHelper context) {
        // A block with no collision shape is proven with an OUTLINE ray, which grass stops: grass is the obstruction here, and
        // grass (a small plant that grows in the way) is something the bot may clear.
        Arena arena = Arena.begin(context, "ObstructGrass");
        AIPlayerEntity bot = arena.bot();
        BlockPos torch = arena.at(4, 0, 0);
        arena.set(torch, Blocks.TORCH.defaultBlockState());
        List<BlockPos> watched = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            arena.set(arena.at(3, -1, dz), Blocks.DIRT.defaultBlockState());
            BlockPos grass = arena.at(3, 0, dz);
            arena.set(grass, Blocks.SHORT_GRASS.defaultBlockState());
            watched.add(grass);
        }
        watched.add(torch);
        arena.require(ObservableWorldQuery.canObserveBlock(bot, torch), "the torch behind the grass is not seen");
        arena.require(!arena.reaches(torch), "the strict gate admits the torch through the grass");

        ActionResult started = bot.getActionPack().startMining(torch, Direction.WEST);
        arena.require(started.isInProgress(), "mining the torch behind grass was not admitted: " + started);
        BreakOrder order = new BreakOrder(watched);
        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            order.observe(arena.world(), tick[0]);
            if (!arena.world().getBlockState(torch).isAir()) {
                return;
            }
            List<BlockPos> broken = order.order();
            arena.require(broken.size() == 2 && broken.get(1).equals(torch) && broken.get(0).getX() == arena.at(3, 0, 0).getX(),
                    "one grass block, then the torch: " + broken);
            List<String> cleared = arena.events("mining_obstruction_cleared");
            arena.require(cleared.size() == 1 && cleared.get(0).contains("obstruction_block='minecraft:short_grass'"),
                    "the clearing of the grass is not logged: " + cleared);
            arena.finish();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What may not be broken is left alone
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_protected_blocks_in_the_way_are_never_broken_and_the_target_is_refused", maxTicks = 100)
    public void protectedBlocksInTheWayAreNeverBrokenAndTheTargetIsRefused(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructPane");
        AIPlayerEntity bot = arena.bot();
        BlockPos log = arena.log();
        arena.set(log, LOG);
        List<BlockPos> panes = arena.wall(2, 2, Blocks.GLASS_PANE.defaultBlockState());
        arena.require(arena.sees(log), "the log behind the panes is not seen");

        ActionResult refused = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(refused.isFailed() && MiningController.TARGET_OBSTRUCTED.equals(refused.reason()),
                "a log behind a pane wall was not refused as obstructed: " + refused);
        arena.require(bot.getActionPack().isMiningIdle(), "a refused break left a controller running");
        for (BlockPos pane : panes) {
            arena.require(arena.world().getBlockState(pane).is(Blocks.GLASS_PANE), "a pane was broken at " + pane);
        }
        List<String> refusals = arena.events("mining_obstruction_refused");
        arena.require(refusals.size() == 1 && refusals.get(0).contains("reason='structure_block'")
                        && refusals.get(0).contains("obstruction_block='minecraft:glass_pane'")
                        && refusals.get(0).contains("pos=" + pos(log)),
                "the refusal is not logged with the target, the pane and why: " + refusals);

        // Leaves in front of the panes would open nothing: not one of them is broken either.
        List<BlockPos> leaves = arena.wall(1, 2, LEAF);
        ActionResult leafy = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(leafy.isFailed() && MiningController.TARGET_OBSTRUCTED.equals(leafy.reason()),
                "leaves in front of panes made the log clearable: " + leafy);
        for (BlockPos leaf : leaves) {
            arena.require(arena.world().getBlockState(leaf).is(Blocks.OAK_LEAVES), "a leaf in front of the panes was broken at " + leaf);
        }

        // The single-block miner reports the same typed refusal to its task, and breaks nothing either.
        BlockMiner miner = new BlockMiner();
        miner.begin(bot, log);
        BlockMiner.Status status = miner.tick(bot);
        arena.require(status == BlockMiner.Status.FAILED && MiningController.TARGET_OBSTRUCTED.equals(miner.failureReason()),
                "the block miner did not fail with target_obstructed: " + status + " " + miner.failureReason());
        arena.require(bot.getActionPack().isMiningIdle() && arena.world().getBlockState(log).is(Blocks.OAK_LOG),
                "the block miner started a break through protected blocks");
        arena.finish();
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_a_leaf_next_to_visible_lava_is_not_broken_for_the_log_behind_it", maxTicks = 100)
    public void aLeafNextToVisibleLavaIsNotBrokenForTheLogBehindIt(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructLava");
        AIPlayerEntity bot = arena.bot();
        BlockPos log = arena.log();
        arena.set(log, LOG);
        List<BlockPos> leaves = arena.wall(2, 2, LEAF);
        // A pocket of lava inside the wall, sealed by stone behind, above and below and by glass on the bot's side, so it
        // is seen through the glass and cannot flow anywhere. Its neighbour in the wall is the leaf a line to the log crosses.
        BlockPos lava = arena.at(2, 1, 1);
        arena.set(lava, Blocks.LAVA.defaultBlockState());
        arena.set(arena.at(3, 1, 1), Blocks.STONE.defaultBlockState());
        arena.set(arena.at(2, 2, 1), Blocks.STONE.defaultBlockState());
        arena.set(arena.at(2, 0, 1), Blocks.STONE.defaultBlockState());
        arena.set(arena.at(2, 1, 2), Blocks.STONE.defaultBlockState());
        arena.set(arena.at(1, 1, 1), Blocks.GLASS.defaultBlockState());
        arena.require(arena.sees(log), "the log behind the leaves is not seen");
        arena.require(ObservableWorldQuery.canObserveCell(bot, lava), "the lava behind the glass is not seen");

        ActionResult refused = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(refused.isFailed() && MiningController.TARGET_OBSTRUCTED.equals(refused.reason()),
                "a log behind a leaf that borders visible lava was not refused as obstructed: " + refused);
        for (BlockPos leaf : leaves) {
            if (!leaf.equals(lava) && !leaf.equals(arena.at(2, 1, 2)) && !leaf.equals(arena.at(2, 2, 1)) && !leaf.equals(arena.at(2, 0, 1))) {
                arena.require(arena.world().getBlockState(leaf).is(Blocks.OAK_LEAVES), "the leaf at " + leaf + " was broken beside lava");
            }
        }
        List<String> refusals = arena.events("mining_obstruction_refused");
        arena.require(refusals.size() == 1 && refusals.get(0).contains("reason='exposes_lava'"),
                "the refusal does not say the leaf borders lava: " + refusals);
        arena.finish();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // One coherent operation for the callers
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_the_block_miner_clears_the_leaves_and_finishes_the_log", maxTicks = 500)
    public void theBlockMinerClearsTheLeavesAndFinishesTheLog(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructMiner");
        AIPlayerEntity bot = arena.bot();
        arena.giveAxe();
        arena.wall(2, 2, LEAF);
        arena.wall(3, 2, LEAF);
        BlockPos log = arena.log();
        arena.set(log, LOG);
        BlockMiner miner = new BlockMiner();
        miner.begin(bot, log);
        boolean[] sawMining = {false};
        context.failIfEver(() -> {
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.MINING) {
                sawMining[0] = true;
                arena.require(arena.world().getBlockState(log).is(Blocks.OAK_LOG)
                                || bot.getActionPack().isMiningIdle(),
                        "the log broke while the miner still reported mining a different block");
                return;
            }
            arena.require(status == BlockMiner.Status.DONE, "the miner ended as " + status + " " + miner.failureReason());
            arena.require(sawMining[0] && arena.world().getBlockState(log).isAir(), "the miner is done but the log is still there");
            arena.require(arena.events("mining_obstruction_cleared").size() == 2, "the miner did not clear two leaves for the log");
            arena.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_cancelling_while_the_way_is_being_cleared_leaves_no_half_state", maxTicks = 600)
    public void cancellingWhileTheWayIsBeingClearedLeavesNoHalfState(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructCancel");
        AIPlayerEntity bot = arena.bot();
        arena.giveAxe();
        List<BlockPos> watched = new ArrayList<>(arena.wall(2, 2, LEAF));
        watched.addAll(arena.wall(3, 2, LEAF));
        BlockPos log = arena.log();
        arena.set(log, LOG);
        watched.add(log);
        ActionResult started = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(started.isInProgress(), "mining was not admitted: " + started);
        int[] phase = {0};
        int[] ticks = {0};
        BreakOrder order = new BreakOrder(watched);
        context.failIfEver(() -> {
            ticks[0]++;
            order.observe(arena.world(), ticks[0]);
            if (phase[0] == 0) {
                // Let the first leaf's break start (a started clearing step has its progress marked), then cancel the operation.
                boolean breaking = !arena.events("mine_start").isEmpty();
                if (!breaking) {
                    return;
                }
                bot.getActionPack().stopMining();
                arena.require(bot.getActionPack().isMiningIdle(), "cancelling left a controller running");
                arena.require(arena.world().getBlockState(log).is(Blocks.OAK_LOG), "the log was touched while it was being cleared for");
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                // Nothing runs on its own after the cancel: wait a few ticks, then ask again from scratch.
                if (ticks[0] % 10 != 0) {
                    return;
                }
                arena.require(bot.getActionPack().isMiningIdle(), "a cancelled operation kept running");
                arena.require(arena.world().getBlockState(log).is(Blocks.OAK_LOG), "the log was broken after the operation was cancelled");
                ActionResult again = bot.getActionPack().startMining(log, Direction.WEST);
                arena.require(again.isInProgress(), "mining could not start again after the cancel: " + again);
                phase[0] = 2;
                return;
            }
            if (!arena.world().getBlockState(log).isAir()) {
                return;
            }
            List<BlockPos> broken = order.order();
            long leavesBroken = broken.stream().filter(p -> !p.equals(log)).count();
            arena.require(leavesBroken == 2 && broken.get(broken.size() - 1).equals(log),
                    "after the restart exactly two leaves and then the log must be gone: " + broken);
            arena.finish();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The real-game case: a canopy log seen through leaves in a gather
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_gather_mines_the_canopy_log_it_sees_through_leaves", maxTicks = 1800)
    public void gatherMinesTheCanopyLogItSeesThroughLeaves(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructGather");
        AIPlayerEntity bot = arena.bot();
        arena.giveAxe();
        // A trunk log that only leaves show the bot: two leaves deep on the line to it, and the bot cannot walk through them.
        List<BlockPos> watched = new ArrayList<>(arena.wall(2, 1, LEAF));
        watched.addAll(arena.wall(3, 1, LEAF));
        BlockPos log = arena.log();
        arena.set(log, LOG);
        watched.add(log);
        arena.require(arena.sees(log) && !arena.reaches(log), "fixture: the log must be seen but not reachable");
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        BreakOrder order = new BreakOrder(watched);
        int[] tick = {0};
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            tick[0]++;
            order.observe(arena.world(), tick[0]);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                arena.fail("gather ended as " + task.state() + ":" + task.failureReason() + " " + task.describe());
                return;
            }
            List<String> lines = SensingArena.botLog(arena.name());
            arena.require(lines != null, "the per-bot log is unavailable");
            if (arena.world().getBlockState(log).is(Blocks.OAK_LOG)
                    && lines.stream().anyMatch(line -> line.contains("event=mine_start ") && line.contains("pos=" + pos(log)))) {
                // The log is being broken now: the vanilla crosshair (Entity.pick, which is what a player's click follows) must be
                // on it, however plainly the eyes saw it through the leaves. Not a mod predicate: the game's own ray.
                HitResult crosshair = bot.pick(bot.blockInteractionRange(), 1.0F, false);
                arena.require(crosshair.getType() == HitResult.Type.BLOCK && ((BlockHitResult) crosshair).getBlockPos().equals(log),
                        "the log was being broken while the vanilla pick ray ended on " + crosshair.getType()
                                + (crosshair instanceof BlockHitResult hit ? " " + hit.getBlockPos().toShortString() : ""));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            arena.require(InventoryAction.countItem(bot, Items.OAK_LOG) >= 1, "gather completed without the log in the inventory");
            arena.require(arena.world().getBlockState(log).isAir(), "the log is still standing");
            List<BlockPos> broken = order.order();
            arena.require(broken.size() >= 3 && broken.get(broken.size() - 1).equals(log),
                    "the log must go after the leaves in front of it: " + broken);
            arena.require(broken.get(0).getX() == arena.at(2, 0, 0).getX(), "the leaf nearest the eye must go first: " + broken);
            List<String> cleared = arena.events("mining_obstruction_cleared");
            arena.require(cleared.size() == broken.size() - 1, "every leaf that went was cleared for the log: " + cleared + " vs " + broken);
            int lastCleared = -1;
            int logStart = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("event=mining_obstruction_cleared ")) {
                    lastCleared = i;
                } else if (line.contains("event=mine_start ") && line.contains("pos=" + pos(log)) && logStart < 0) {
                    logStart = i;
                }
            }
            arena.require(lastCleared >= 0 && logStart > lastCleared,
                    "the log was started before the last leaf was cleared: cleared at " + lastCleared + ", started at " + logStart);
            long leafBreaks = lines.stream().filter(line -> line.contains("event=mine_complete ")
                    && line.contains("block='minecraft:oak_leaves'") && line.contains("obstruction_of=" + pos(log))).count();
            arena.require(leafBreaks == cleared.size(), "each leaf is audited as a break for the log: " + leafBreaks);
            arena.finish();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reach is a vanilla pick ray: outline shapes, fluids ignored
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_a_log_behind_cobweb_is_not_mined_through_it_and_a_chest_behind_it_is_not_opened", maxTicks = 100)
    public void aLogBehindCobwebIsNotMinedThroughItAndAChestBehindItIsNotOpened(GameTestHelper context) {
        // A cobweb collides with nothing, so a ray of collision shapes walks through it, but a click along the line lands on it:
        // the log is not the block a hand reaches, however plainly the bot sees it.
        Arena arena = Arena.begin(context, "ObstructWeb");
        AIPlayerEntity bot = arena.bot();
        BlockPos log = arena.log();
        BlockPos chest = arena.at(4, 0, 0);
        arena.set(log, LOG);
        arena.set(chest, Blocks.CHEST.defaultBlockState());
        List<BlockPos> webs = arena.wall(2, 2, Blocks.COBWEB.defaultBlockState());
        arena.require(arena.sees(log), "the log behind the cobweb is not seen");
        arena.require(!arena.reaches(log), "the mining gate admits the log through the cobweb: a click lands on the web");
        arena.require(!ContainerAction.canSee(bot, chest) && ContainerAction.open(bot, chest, false).isEmpty(),
                "the chest behind the cobweb was opened");

        ActionResult refused = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(refused.isFailed() && MiningController.TARGET_OBSTRUCTED.equals(refused.reason()),
                "a log behind a cobweb was not refused as obstructed: " + refused);
        arena.require(bot.getActionPack().isMiningIdle() && arena.world().getBlockState(log).is(Blocks.OAK_LOG),
                "a break was started through the cobweb");
        for (BlockPos web : webs) {
            arena.require(arena.world().getBlockState(web).is(Blocks.COBWEB), "a cobweb was broken at " + web);
        }

        for (BlockPos web : webs) {
            arena.set(web, AIR);
        }
        arena.require(arena.reaches(log) && ContainerAction.canSee(bot, chest),
                "control: the log and the chest are not reachable once the cobweb is gone");
        arena.finish();
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_a_log_and_a_chest_behind_water_are_reached_as_a_player_reaches_them", maxTicks = 700)
    public void aLogAndAChestBehindWaterAreReachedAsAPlayerReachesThem(GameTestHelper context) {
        // A pick ray ignores fluids: a player mines the log across two blocks of water and opens the chest under them, and the
        // bot that sees them through the water does the same, with nothing to break first (water is no block).
        Arena arena = Arena.begin(context, "ObstructWater");
        AIPlayerEntity bot = arena.bot();
        arena.giveAxe();
        BlockPos log = arena.log();
        BlockPos chest = arena.at(4, 0, 0);
        arena.set(log, LOG);
        arena.set(chest, Blocks.CHEST.defaultBlockState());
        arena.wall(2, 2, Blocks.WATER.defaultBlockState());
        arena.wall(3, 2, Blocks.WATER.defaultBlockState());
        arena.require(arena.sees(log), "the log behind the water is not seen");
        arena.require(arena.reaches(log), "a hand does not reach the log across the water, as a player's does");
        arena.require(ContainerAction.canSee(bot, chest), "a hand does not reach the chest across the water, as a player's does");

        ActionResult started = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(started.isInProgress(), "mining the log behind the water was not admitted: " + started);
        context.failIfEver(() -> {
            if (!arena.world().getBlockState(log).isAir()) {
                return;
            }
            arena.require(arena.events("mining_obstruction_detected").isEmpty() && arena.events("mining_obstruction_refused").isEmpty(),
                    "the water was treated as something to clear");
            arena.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_a_leaf_next_to_visible_water_is_not_broken_for_the_log_behind_it", maxTicks = 100)
    public void aLeafNextToVisibleWaterIsNotBrokenForTheLogBehindIt(GameTestHelper context) {
        // Breaking the leaf would let the water flow into its cell: a block for a log, and a stream left behind.
        Arena arena = Arena.begin(context, "ObstructFlood");
        AIPlayerEntity bot = arena.bot();
        BlockPos log = arena.log();
        arena.set(log, LOG);
        List<BlockPos> leaves = arena.wall(2, 2, LEAF);
        // A pocket of water inside the wall, sealed by stone behind, above, below and aside and by glass on the bot's side, so it
        // is seen through the glass and cannot flow anywhere. Its neighbour in the wall is the leaf a line to the log crosses.
        BlockPos water = arena.at(2, 1, 1);
        arena.set(water, Blocks.WATER.defaultBlockState());
        arena.set(arena.at(3, 1, 1), Blocks.STONE.defaultBlockState());
        arena.set(arena.at(2, 2, 1), Blocks.STONE.defaultBlockState());
        arena.set(arena.at(2, 0, 1), Blocks.STONE.defaultBlockState());
        arena.set(arena.at(2, 1, 2), Blocks.STONE.defaultBlockState());
        arena.set(arena.at(1, 1, 1), Blocks.GLASS.defaultBlockState());
        arena.require(arena.sees(log), "the log behind the leaves is not seen");
        arena.require(ObservableWorldQuery.canObserveCell(bot, water), "the water behind the glass is not seen");

        ActionResult refused = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(refused.isFailed() && MiningController.TARGET_OBSTRUCTED.equals(refused.reason()),
                "a log behind a leaf that borders visible water was not refused as obstructed: " + refused);
        for (BlockPos leaf : leaves) {
            if (!leaf.equals(water) && !leaf.equals(arena.at(2, 1, 2)) && !leaf.equals(arena.at(2, 2, 1)) && !leaf.equals(arena.at(2, 0, 1))) {
                arena.require(arena.world().getBlockState(leaf).is(Blocks.OAK_LEAVES), "the leaf at " + leaf + " was broken beside water");
            }
        }
        List<String> refusals = arena.events("mining_obstruction_refused");
        arena.require(refusals.size() == 1 && refusals.get(0).contains("reason='exposes_water'"),
                "the refusal does not say the leaf borders water: " + refusals);
        arena.finish();
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_an_owners_clear_line_does_not_let_the_bot_mine_through_a_leaf", maxTicks = 100)
    public void anOwnersClearLineDoesNotLetTheBotMineThroughALeaf(GameTestHelper context) {
        // The linked owner is a second pair of EYES, never a second hand: what the owner sees cleanly is no line for the bot's own
        // break, which a leaf in front of the bot still stops.
        Arena arena = Arena.begin(context, "ObstructOwner");
        AIPlayerEntity bot = arena.bot();
        ServerPlayer owner = MockPlayers.ownerFor(context, bot);
        BlockPos log = arena.log();
        arena.set(log, LOG);
        arena.wall(2, 1, LEAF);
        BlockPos ownerFeet = arena.at(4, 0, 3);
        owner.teleportTo(arena.world(), ownerFeet.getX() + 0.5D, ownerFeet.getY(), ownerFeet.getZ() + 0.5D, Set.of(), 180.0F, 0.0F, true);
        arena.world().getChunkSource().move(owner);
        Vec3 southFace = new Vec3(log.getX() + 0.5D, log.getY() + 0.5D, log.getZ() + 0.999D);
        BlockHitResult ownerLine = arena.world().clip(new ClipContext(owner.getEyePosition(), southFace,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        arena.require(ownerLine.getType() == HitResult.Type.BLOCK && ownerLine.getBlockPos().equals(log),
                "fixture: the owner has no clear line to the log");
        arena.require(arena.sees(log), "the log behind the leaf is not seen");
        arena.require(!ObservableWorldQuery.canObserveBlockStrict(bot, log) && !arena.reaches(log),
                "the owner's clear line let the bot's own gate through the leaf");
        ActionResult started = bot.getActionPack().startMining(log, Direction.WEST);
        arena.require(started.isInProgress(), "the log behind a leaf is cleared, not refused: " + started);
        bot.getActionPack().stopMining();
        arena.require(arena.world().getBlockState(log).is(Blocks.OAK_LOG), "the log was broken through the leaf");
        arena.finish();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The bot's own footing is never in the way of a break, and its own break keeps its dedicated path
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_the_block_the_bot_stands_on_is_never_cleared_for_a_log_below_it_and_only_the_own_support_break_takes_it", maxTicks = 300)
    public void theBlockTheBotStandsOnIsNeverClearedForALogBelowItAndOnlyTheOwnSupportBreakTakesIt(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructFooting");
        AIPlayerEntity bot = arena.bot();
        arena.giveAxe();
        BlockPos under = arena.at(0, -1, 0);
        BlockPos log = arena.at(0, -2, 0);
        List<BlockPos> sealed = new ArrayList<>();
        for (Direction side : Direction.values()) {
            if (side != Direction.UP) {
                sealed.add(log.relative(side));
                arena.set(log.relative(side), Blocks.STONE.defaultBlockState());
            }
        }
        arena.set(log, LOG);
        // The log's one open face is covered by the leaf the bot stands on: the eyes see it through that leaf, a hand cannot reach it
        // without the leaf, and the leaf is the one block the bot must not break.
        arena.set(under, LEAF);
        arena.require(arena.sees(log) && !arena.reaches(log), "fixture: the log must be seen through the leaf under the bot, not reachable");
        ActionResult refused = bot.getActionPack().startMining(log, Direction.UP);
        arena.require(refused.isFailed() && MiningController.TARGET_OBSTRUCTED.equals(refused.reason()),
                "a log under the leaf the bot stands on was not refused as obstructed: " + refused);
        arena.require(bot.getActionPack().isMiningIdle() && arena.world().getBlockState(under).is(Blocks.OAK_LEAVES),
                "the leaf the bot stands on was broken for the log under it");
        List<String> refusals = arena.events("mining_obstruction_refused");
        arena.require(refusals.size() == 1 && refusals.get(0).contains("reason='self_support'") && refusals.get(0).contains("obstruction=" + pos(under)),
                "the refusal does not say that the leaf is the bot's own footing: " + refusals);
        arena.require(arena.events("mining_obstruction_detected").isEmpty(), "a clearing step was planned for the bot's own footing");

        // The same cell, now one of the pillar's throwaway blocks: the ordinary break still refuses it, the dedicated path takes it.
        arena.set(under, Blocks.DIRT.defaultBlockState());
        ActionResult plain = bot.getActionPack().startMining(under, Direction.UP);
        arena.require(plain.isFailed() && MiningSafety.SELF_SUPPORT.equals(plain.reason()),
                "the ordinary break of the block under the bot was not refused as its own footing: " + plain);
        ActionResult own = bot.getActionPack().startOwnSupportMining(under);
        arena.require(own.isInProgress(), "the dedicated break of the bot's own pillar block was refused: " + own);
        context.failIfEver(() -> {
            if (!arena.world().getBlockState(under).isAir()) {
                return;
            }
            arena.require(arena.events("mining_obstruction_detected").isEmpty() && arena.events("mining_obstruction_cleared").isEmpty(),
                    "the own-support break was treated as an obstruction");
            arena.require(arena.world().getBlockState(log).is(Blocks.OAK_LOG), "the log under the bot's footing was broken with it");
            for (BlockPos stone : sealed) {
                arena.require(arena.world().getBlockState(stone).is(Blocks.STONE), "the break of the footing reached " + stone);
            }
            arena.finish();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Gather does not wait out its deadline for a target the miner has refused
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_gather_gives_up_a_log_behind_a_pane_at_once_and_does_not_wait_out_the_harvest_deadline", maxTicks = 220)
    public void gatherGivesUpALogBehindAPaneAtOnceAndDoesNotWaitOutTheHarvestDeadline(GameTestHelper context) {
        Arena arena = Arena.begin(context, "ObstructGatherPane");
        AIPlayerEntity bot = arena.bot();
        arena.giveAxe();
        arena.wall(2, 2, Blocks.GLASS_PANE.defaultBlockState());
        BlockPos log = arena.log();
        arena.set(log, LOG);
        arena.require(arena.sees(log) && !arena.reaches(log), "fixture: the log must be seen but not reachable");
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            arena.require(arena.events("gather_harvest_timeout").isEmpty(), "gather waited out the harvest deadline for a log it may not mine");
            if (arena.events("gather_harvest_refused").isEmpty()) {
                return;
            }
            List<String> refused = arena.events("gather_harvest_refused");
            arena.require(refused.get(0).contains("reason='target_obstructed'") && refused.get(0).contains("pos='" + log.toShortString() + "'"),
                    "the refusal is not logged with the target and why: " + refused);
            arena.require(arena.world().getBlockState(log).is(Blocks.OAK_LOG), "the log was broken through the panes");
            int now = arena.world().getServer().getTickCount();
            arena.require(EpisodeMemory.INSTANCE.excludedUntil(bot.getUUID(), log, now) - now > EpisodeMemory.TTL_SHORT,
                    "a log behind blocks the bot may not break is excluded no longer than one that merely left its sight");
            arena.finish();
        });
    }

    @GameTest(environment = "minecraftai-gametest:mining_obstruction_game_tests_mine_sets_aside_an_ore_behind_cobweb_and_mines_the_one_in_the_open", maxTicks = 700)
    public void mineSetsAsideAnOreBehindCobwebAndMinesTheOneInTheOpen(GameTestHelper context) {
        // A generic mine request nominates the nearest ore its eyes see, and the nearest is seen through a column of cobweb the
        // bot may not break. The miner refuses it as obstructed. The task took that for a finished break and waited 120 ticks for
        // a drop that cannot exist, then failed with pickup_timeout without ever trying the second ore, which lies in the open:
        // now the ore behind the web is set aside (for as long as an unreachable one) and the one in the open is mined.
        Arena arena = Arena.begin(context, "ObstructMineWeb");
        AIPlayerEntity bot = arena.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        List<BlockPos> webs = arena.wall(2, 0, Blocks.COBWEB.defaultBlockState());
        BlockPos behind = arena.log();
        BlockPos open = arena.at(5, 0, -3);
        arena.set(behind, Blocks.IRON_ORE.defaultBlockState());
        arena.set(open, Blocks.IRON_ORE.defaultBlockState());
        arena.require(arena.sees(behind) && !arena.reaches(behind), "fixture: the ore behind the web must be seen but not reachable");
        arena.require(arena.sees(open), "fixture: the ore in the open must be seen");
        MineTask task = new MineTask(Blocks.IRON_ORE, 1);
        task.start(bot);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                arena.fail("mine ended as " + task.state() + ":" + task.failureReason() + " " + arena.events("mine_target_refused"));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            List<String> refused = arena.events("mine_target_refused");
            arena.require(refused.size() == 1 && refused.get(0).contains("reason='target_obstructed'")
                            && refused.get(0).contains("pos='" + behind.toShortString() + "'"),
                    "the ore behind the web was not refused once, with the target and why: " + refused);
            arena.require(arena.world().getBlockState(behind).is(Blocks.IRON_ORE) && arena.world().getBlockState(open).isAir(),
                    "the ore behind the web was mined, or the one in the open was not");
            for (BlockPos web : webs) {
                arena.require(arena.world().getBlockState(web).is(Blocks.COBWEB), "a cobweb was broken at " + web);
            }
            arena.require(InventoryAction.countItem(bot, Items.RAW_IRON) == 1, "the mined ore's drop was not collected");
            int now = arena.world().getServer().getTickCount();
            arena.require(EpisodeMemory.INSTANCE.excludedUntil(bot.getUUID(), behind, now) - now > EpisodeMemory.TTL_SHORT,
                    "an ore behind blocks the bot may not break is excluded no longer than one that merely left its sight");
            arena.finish();
        });
    }
}
