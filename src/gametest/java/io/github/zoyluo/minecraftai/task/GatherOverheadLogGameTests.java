package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.task.SensingArena.Room;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Gather's answer to a log it can see but not walk to: "all it had to do was build up". Every case is a
 * sealed stone room with one overhead resource, so the only way to it is an observed pillar of common
 * blocks, exactly the situation of the real session (a log seven blocks straight up, canopy logs
 * Baritone refused, a leaf in the way of the trunk). The pillar path never ran in any real log before.
 */
public final class GatherOverheadLogGameTests {
    private static final int ROOM_HEIGHT = 16;
    /** Every case builds in its own slab of the world; nothing else of the suite shares these heights. */
    private static final int SLAB_BASE = 140;
    private static final int SLAB_STEP = 30;
    private static final int TICK_BUDGET = 1100;

    @GameTest(environment = "minecraftai-gametest:gather_overhead_log_game_tests_carried_dirt_pillars_to_a_log_in_the_bots_own_column", maxTicks = 1300)
    public void carriedDirtPillarsToALogInTheBotsOwnColumn(GameTestHelper context) {
        // The real session: log at 17,130,-50, bot at 17,123,-50. Seven up is just out of reach (4.5), the cell
        // between is air, and no neighbouring column needs to exist: the plain answer is straight up.
        Case c = new Case(context, "GatherOverheadOwnGT", 0);
        BlockPos log = c.at(0, 7, 0);
        c.set(0, 7, 0, Blocks.OAK_LOG);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 8));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (InventoryAction.countItem(c.bot, Items.OAK_LOG) < 1) {
                return false;
            }
            c.require(c.world().getBlockState(log).isAir(), "the log is in the inventory but its block is still standing");
            c.require(c.maxFeetY >= c.feet.getY() + 2, "the bot never climbed: highest feet y " + c.maxFeetY);
            c.require(InventoryAction.countItem(c.bot, Items.DIRT) < 8, "no dirt was spent, so nothing was built");
            List<String> lines = c.log();
            c.require(c.count(lines, "gather_pillar_start", "target='" + log.toShortString() + "'") == 1,
                    "expected exactly one pillar for the log: " + c.tail(lines));
            c.require(c.count(lines, "gather_target_excluded") == 0,
                    "the log was written off instead of climbed: " + c.tail(lines));
            c.require(c.count(lines, "gather_tree_sighting_pursuit_refused") == 0,
                    "an overhead log must not go through a refused landmark pursuit: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_overhead_log_game_tests_log_six_up_beside_terrain_is_climbed_with_one_block", maxTicks = 1300)
    public void logSixUpBesideTerrainIsClimbedWithOneBlock(GameTestHelper context) {
        // Six up is the first height that needs a pillar at all, and it needs one block. The route goal then sits a
        // single cell above the bot's own stance; a stone step beside the column is a second standable cell within
        // one block of it. Neither may stand in for the goal, or the "route" ends before a block is placed.
        Case c = new Case(context, "GatherOverheadStepGT", 7);
        BlockPos log = c.at(0, 6, 0);
        c.set(0, 6, 0, Blocks.OAK_LOG);
        c.set(1, 0, 0, Blocks.STONE);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 8));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (InventoryAction.countItem(c.bot, Items.OAK_LOG) < 1) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "gather_pillar_start", "target='" + log.toShortString() + "'", "supports='1'") == 1,
                    "expected one single-block pillar for the log: " + c.tail(lines));
            c.require(c.count(lines, "gather_pillar_ended_short") == 0 && c.count(lines, "gather_target_excluded") == 0,
                    "the one-block pillar ended before it was built: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_overhead_log_game_tests_empty_handed_bot_fetches_nearby_dirt_then_pillars", maxTicks = 1300)
    public void emptyHandedBotFetchesNearbyDirtThenPillars(GameTestHelper context) {
        Case c = new Case(context, "GatherOverheadSupplyGT", 1);
        BlockPos log = c.at(0, 7, 0);
        c.set(0, 7, 0, Blocks.OAK_LOG);
        // A few dirt blocks lying on the floor within the supply radius: the bot has no supports at all.
        int[][] dirt = {{3, 0, 0}, {3, 0, 1}, {3, 0, -1}, {4, 0, 0}, {-3, 0, 2}};
        for (int[] cell : dirt) {
            c.set(cell[0], cell[1], cell[2], Blocks.DIRT);
        }
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.WOODEN_SHOVEL));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (InventoryAction.countItem(c.bot, Items.OAK_LOG) < 1) {
                return false;
            }
            List<String> lines = c.log();
            int resupply = c.indexOf(lines, "gather_scaffold_resupply", "item='minecraft:dirt'");
            int ready = c.indexOf(lines, "gather_scaffold_resupply_ready");
            int pillar = c.indexOf(lines, "gather_pillar_start", "target='" + log.toShortString() + "'");
            c.require(resupply >= 0 && ready > resupply && pillar > ready,
                    "expected dirt resupply, then ready, then the pillar (" + resupply + "," + ready + "," + pillar
                            + "): " + c.tail(lines));
            c.require(c.maxFeetY >= c.feet.getY() + 2, "the bot never climbed: highest feet y " + c.maxFeetY);
            c.require(c.count(lines, "gather_target_excluded") == 0,
                    "the log was written off instead of climbed: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_overhead_log_game_tests_leaf_overhead_cannot_stall_the_sweep_that_finds_the_trunk", maxTicks = 1300)
    public void leafOverheadCannotStallTheSweepThatFindsTheTrunk(GameTestHelper context) {
        // The real session's second loop: a leaf straight above (27,129,-52) answered the vertical ray of every
        // step for 174 steps while the trunk stood one or two blocks aside. The leaf has no heading; the sweep has
        // to move on, find the trunk, and the trunk (out of reach, too) has to be climbed.
        Case c = new Case(context, "GatherOverheadLeafGT", 2);
        BlockPos leaf = c.at(0, 6, 0);
        c.world().setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true),
                Block.UPDATE_ALL);
        for (int y = 6; y <= 9; y++) {
            c.set(2, y, 0, Blocks.OAK_LOG);
        }
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 8));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        int[] pillarTick = {-1};
        c.run(task, () -> {
            if (pillarTick[0] < 0 && c.ticks % 5 == 0 && c.count(c.log(), "gather_pillar_start") > 0) {
                pillarTick[0] = c.ticks;
            }
            if (InventoryAction.countItem(c.bot, Items.OAK_LOG) < 1) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "gather_tree_sighted", "kind='leaf'", "pos='" + leaf.toShortString() + "'") <= 1,
                    "the same overhead leaf was sighted again and again: " + c.tail(lines));
            c.require(pillarTick[0] >= 0 && pillarTick[0] <= 120,
                    "the trunk was not found within a few ticks of the leaf (pillar started at tick " + pillarTick[0]
                            + "): " + c.tail(lines));
            c.require(c.count(lines, "gather_tree_sighting_pursuit_refused") == 0,
                    "a leaf in the bot's own column must not be pursued as a landmark: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_overhead_log_game_tests_log_on_an_unreachable_ledge_is_climbed_before_it_is_excluded", maxTicks = 1300)
    public void logOnAnUnreachableLedgeIsClimbedBeforeItIsExcluded(GameTestHelper context) {
        ledgeLog(context, "GatherOverheadLedgeGT", 3, GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1));
    }

    @GameTest(environment = "minecraftai-gametest:gather_overhead_log_game_tests_fresh_handoff_quota_climbs_the_same_ledge_log_and_may_still_dig", maxTicks = 1300)
    public void freshHandoffQuotaClimbsTheSameLedgeLogAndMayStillDig(GameTestHelper context) {
        // A fresh quota protects its promised logs, which no route can place anyway: it keeps the dig approach.
        ledgeLog(context, "GatherOverheadFreshGT", 4, GatherQuotaTask.collectAdditionalLogsForHandoff(1));
    }

    private static void ledgeLog(GameTestHelper context, String name, int slab, GatherQuotaTask task) {
        // The survey picks this log: the floating stone ledge beside it is an observed stand cell (far enough away
        // that its top is in sight from the floor). Baritone refuses the route to that ledge (the real
        // "navigation_observed_corridor_unavailable": no placeable block in hand), the dig approach fails, and the
        // log used to be excluded for a minute before any pillar had been considered. The bot has to fetch its
        // supports from the dirt lying around, then build up under the log.
        Case c = new Case(context, name, slab, 9);
        BlockPos log = c.at(0, 7, 7);
        c.set(0, 7, 7, Blocks.OAK_LOG);
        c.set(1, 6, 7, Blocks.STONE);
        for (int[] cell : new int[][] {{-3, 0, 0}, {-3, 0, 1}, {-3, 0, -1}, {-4, 0, 0}}) {
            c.set(cell[0], cell[1], cell[2], Blocks.DIRT);
        }
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.WOODEN_SHOVEL));
        task.start(c.bot);

        c.run(task, () -> {
            if (!c.world().getBlockState(log).isAir()) {
                return false;
            }
            List<String> lines = c.log();
            int pillar = c.indexOf(lines, "gather_pillar_start", "target='" + log.toShortString() + "'");
            int excluded = c.indexOf(lines, "gather_target_excluded",
                    "pos='" + log.getX() + "," + log.getY() + "," + log.getZ() + "'");
            c.require(pillar >= 0, "the refused log was never climbed: " + c.tail(lines));
            c.require(excluded < 0 || excluded > pillar,
                    "the log was excluded before its pillar had a chance: " + c.tail(lines));
            c.require(c.count(lines, "gather_dig_approach") > 0,
                    "the tunnel approach must still be tried first, also for a promised log: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_overhead_log_game_tests_overhead_dirt_block_is_climbed_like_any_log", maxTicks = 1300)
    public void overheadDirtBlockIsClimbedLikeAnyLog(GameTestHelper context) {
        // The same hole in the non-tree sweep: a requested block straight above has no heading for a pursuit.
        // (Dirt, because the room itself is stone and a stone gather would simply break the floor.)
        Case c = new Case(context, "GatherOverheadDirtGT", 5);
        BlockPos dirt = c.at(0, 7, 0);
        c.set(0, 7, 0, Blocks.DIRT);
        c.give(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.COBBLESTONE, 8));
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.DIRT, 1);
        task.start(c.bot);

        c.run(task, () -> {
            if (task.state() != TaskState.COMPLETED) {
                return false;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "gather_pillar_start", "target='" + dirt.toShortString() + "'") == 1,
                    "the overhead block was not climbed: " + c.tail(lines));
            c.require(c.count(lines, "gather_target_sighting_pursuit_refused") == 0,
                    "an overhead block must not go through a refused landmark pursuit: " + c.tail(lines));
            return true;
        });
    }

    @GameTest(environment = "minecraftai-gametest:gather_overhead_log_game_tests_excluded_log_is_not_sighted_again_every_tick", maxTicks = 400)
    public void excludedLogIsNotSightedAgainEveryTick(GameTestHelper context) {
        Case c = new Case(context, "GatherOverheadExcludedGT", 6);
        BlockPos log = c.at(0, 7, 0);
        c.set(0, 7, 0, Blocks.OAK_LOG);
        c.give(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.DIRT, 8));
        EpisodeMemory.INSTANCE.exclude(c.bot.getUUID(), log, context.getLevel().getServer().getTickCount(),
                EpisodeMemory.TTL_UNREACHABLE);
        GatherQuotaTask task = GatherQuotaTask.collectAdditional(Items.OAK_LOG, 1);
        task.start(c.bot);

        int[] ticks = {0};
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(c.bot);
            }
            if (++ticks[0] < 150) {
                return;
            }
            List<String> lines = c.log();
            c.require(c.count(lines, "gather_tree_sighted", "pos='" + log.toShortString() + "'") <= 1,
                    "the excluded log answered the sweep over and over: " + c.tail(lines));
            c.require(c.count(lines, "gather_pillar_start") == 0,
                    "an excluded log was climbed anyway: " + c.tail(lines));
            c.finish();
        });
    }

    /** One sealed room, one bot standing at its floor, and the log-reading helpers shared by every case. */
    private static final class Case {
        final GameTestHelper context;
        final Room room;
        final AIPlayerEntity bot;
        final String name;
        final BlockPos feet;
        int ticks;
        int maxFeetY;
        private boolean done;

        Case(GameTestHelper context, String name, int slab) {
            this(context, name, slab, 4);
        }

        Case(GameTestHelper context, String name, int slab, int halfWidth) {
            this.context = context;
            this.name = name;
            this.room = new Room(context, SLAB_BASE + SLAB_STEP * slab, -halfWidth, halfWidth, -halfWidth, halfWidth,
                    ROOM_HEIGHT);
            this.feet = room.feet;
            // Light everywhere: a hostile in view would make the danger watcher pause the task under test.
            for (int dx = 1 - halfWidth; dx < halfWidth; dx += 3) {
                for (int dz = 1 - halfWidth; dz < halfWidth; dz += 3) {
                    room.set(dx, ROOM_HEIGHT - 1, dz, Blocks.LIGHT);
                }
            }
            var spawned = AIPlayerManager.INSTANCE.spawn(context.getLevel().getServer(), name, room.world,
                    Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL);
            this.bot = spawned.orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            bot.teleportTo(room.world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(5.0F);
            this.maxFeetY = feet.getY();
        }

        ServerLevel world() {
            return room.world;
        }

        BlockPos at(int dx, int dy, int dz) {
            return room.at(dx, dy, dz);
        }

        void set(int dx, int dy, int dz, Block block) {
            room.set(dx, dy, dz, block);
        }

        void give(ItemStack... stacks) {
            for (ItemStack stack : stacks) {
                InventoryAction.giveItem(bot, stack);
            }
        }

        /** Ticks the task each GameTest tick until {@code finished} says the case is over; a stuck case fails with its log. */
        void run(GatherQuotaTask task, BooleanSupplier finished) {
            context.failIfEver(() -> {
                if (done) {
                    return;
                }
                ticks++;
                maxFeetY = Math.max(maxFeetY, bot.blockPosition().getY());
                if (task.state() == TaskState.RUNNING) {
                    task.tick(bot);
                }
                if (finished.getAsBoolean()) {
                    finish();
                    return;
                }
                if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                    fail("gather ended as " + task.state() + ":" + task.failureReason() + " " + tail(log()));
                } else if (ticks > TICK_BUDGET) {
                    fail("gather did not finish in " + TICK_BUDGET + " ticks: " + task.describe() + " at "
                            + bot.blockPosition() + " " + tail(log()));
                }
            });
        }

        List<String> log() {
            List<String> lines = SensingArena.botLog(name);
            if (lines == null) {
                fail("the per-bot log is unavailable, so the recovery cannot be proven");
                throw new IllegalStateException("no bot log");
            }
            return lines;
        }

        long count(List<String> lines, String event, String... fields) {
            return lines.stream().filter(line -> matches(line, event, fields)).count();
        }

        int indexOf(List<String> lines, String event, String... fields) {
            for (int i = 0; i < lines.size(); i++) {
                if (matches(lines.get(i), event, fields)) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean matches(String line, String event, String... fields) {
            if (!line.contains("event=" + event + " ") && !line.endsWith("event=" + event)) {
                return false;
            }
            for (String field : fields) {
                if (!line.contains(field)) {
                    return false;
                }
            }
            return true;
        }

        /** The gather events of this bot, for a failure message. */
        String tail(List<String> lines) {
            List<String> gather = lines.stream().filter(line -> line.contains("event=gather_")).toList();
            return String.join(" | ", gather.subList(Math.max(0, gather.size() - 14), gather.size()).stream()
                    .map(line -> line.substring(Math.max(0, line.indexOf("event="))))
                    .toList());
        }

        void require(boolean condition, String message) {
            if (!condition) {
                fail(message);
                throw new IllegalStateException(message);
            }
        }

        void fail(String message) {
            cleanup();
            context.fail(Component.nullToEmpty(message));
        }

        void finish() {
            cleanup();
            context.succeed();
        }

        private void cleanup() {
            done = true;
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), name);
            room.clear();
        }
    }
}
