package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InCellWalk;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.KnownCellPickupSweep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * No micro-teleports (R5) for the pickup, water, obsidian and craft moves: the bot walks with its movement keys where the old code
 * moved it a few tenths of a block (the pickup nudge, the walk back to the middle of the cell, the sneak-bridge lean, the last cells
 * to a drop). Every test resets {@link TeleportAudit} for the bot under test after the fixture is built (fixture moves are
 * {@code TEST} teleports) and asserts {@code TeleportAudit.corrections(bot) == 0} at the end, in the default strict-survival profile.
 * Each test has its own world layer.
 */
public final class PickupNaturalMovementGameTests {
    private static final int BASE_Y = 250;
    private static final int LAYER_STEP = 12;

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    /** A stone floor (top at feet level - 1) with air above, on its own layer. */
    private static final class Arena {
        final GameTestHelper context;
        final ServerLevel world;
        final BlockPos feet;

        private Arena(GameTestHelper context, BlockPos feet) {
            this.context = context;
            this.world = context.getLevel();
            this.feet = feet;
        }

        static Arena build(GameTestHelper context, int layer, int fromX, int toX, int fromZ, int toZ) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, 8));
            for (int dx = fromX; dx <= toX; dx++) {
                for (int dz = fromZ; dz <= toZ; dz++) {
                    for (int dy = -3; dy <= 7; dy++) {
                        Block block = dy == -1 ? Blocks.STONE : Blocks.AIR;
                        world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            return new Arena(context, feet);
        }

        BlockPos at(int dx, int dy, int dz) {
            return feet.offset(dx, dy, dz);
        }

        void set(int dx, int dy, int dz, Block block) {
            world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
        }

        AIPlayerEntity spawn(String name, BlockPos where) {
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                            world.getServer(), name, world, Vec3.atBottomCenterOf(where), 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.LEGACY);
            BotFixtureMoves.place(bot, where);
            bot.setOnGround(true);
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(20.0F);
            Standability.clearCache();
            TeleportAudit.reset(bot);
            return bot;
        }

        void finish(AIPlayerEntity bot) {
            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
            bot.getActionPack().stopAll();
            bot.getActionPack().clearPace();
            NavEngineSelector.clearBotEngine(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
            context.succeed();
        }
    }

    private static void requireNoCorrections(GameTestHelper context, AIPlayerEntity bot, String what) {
        require(context, TeleportAudit.corrections(bot) == 0, what + ": the bot was teleported (corrections="
                + TeleportAudit.corrections(bot) + " last=" + TeleportAudit.lastCaller(bot) + ")");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Water: the sneak-bridge lean over the edge is walked
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A climb through an open room needs an isolated pillar: the bot leans out over the edge of its support (sneaking), places the
     * base block against the side face and walks back to the middle of the cell. The old code teleported 0.55 blocks out and back in
     * one tick; now both are steps with the movement keys and the placement happens between them.
     */
    @GameTest(environment = "minecraftai-gametest:pickup_natural_movement_game_tests_acquire_water_scoops_from_edge_without_teleport", maxTicks = 900)
    public void acquireWaterScoopsFromEdgeWithoutTeleport(GameTestHelper context) {
        Arena arena = Arena.build(context, 0, -6, 6, -6, 6);
        BlockPos start = arena.feet;
        AIPlayerEntity bot = arena.spawn("PickupWaterEdgeGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        AcquireWaterTask task = new AcquireWaterTask(start.above(2));
        task.start(bot);
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            task.tick(bot);
            require(context, task.state() != TaskState.FAILED && task.state() != TaskState.CANCELLED,
                    "the ascent failed: " + task.failureReason() + " checkpoint=" + task.checkpoint());
            if ("SEARCH".equals(task.checkpoint().get("phase"))) {
                requireNoCorrections(context, bot, "sneak-bridge ascent");
                int used = 8 - InventoryAction.countItem(bot, Items.COBBLESTONE);
                require(context, used >= 2, "the climb did not place the bridge blocks (used=" + used + ")");
                require(context, bot.blockPosition().getY() >= start.above(2).getY(),
                        "the climb ended below the surface anchor: " + bot.blockPosition().toShortString());
                task.cancel(bot, "gametest_complete");
                arena.finish(bot);
            }
            require(context, tick[0] < 880, "timed out at " + bot.blockPosition().toShortString() + " phase="
                    + task.checkpoint().get("phase"));
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Obsidian: the last cells to a drop are walked (dry cells and a water-filled one)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The pickup transaction closes the gap to the break cell with adjacent steps: a walk onto a dry cell, then a swim stroke into
     * the water-filled cell where the obsidian rests. Each is a walked step; the next one starts when the bot has arrived.
     */
    @GameTest(environment = "minecraftai-gametest:pickup_natural_movement_game_tests_create_obsidian_rim_and_pickup_by_walking", maxTicks = 300)
    public void createObsidianRimAndPickupByWalking(GameTestHelper context) {
        Arena arena = Arena.build(context, 1, -3, 8, -3, 3);
        // A one-cell pool sunk into the floor east of the dry approach: water in the floor cell, stone under it and around it.
        BlockPos pool = arena.at(3, -1, 0);
        arena.set(3, -2, 0, Blocks.STONE);
        arena.set(3, -1, 0, Blocks.WATER);
        AIPlayerEntity bot = arena.spawn("PickupObsidianWalkGT", arena.at(0, 0, 0));
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            var pack = bot.getActionPack();
            if (bot.blockPosition().equals(pool)) {
                requireNoCorrections(context, bot, "pickup steps");
                arena.finish(bot);
                return;
            }
            if (pack.stepIdle() && tick[0] > 2) {
                // The two-cell gap first (a dry transit cell, then the pool), asked again as long as the bot is not in the pool.
                boolean started = CreateObsidianTask.stepTowardPickupCell(bot, pool);
                require(context, started, "no pickup step could be started from " + bot.blockPosition().toShortString());
            }
            require(context, tick[0] < 280, "timed out at " + bot.blockPosition().toShortString());
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hunt: a drop just out of pickup range is reached by walking toward it inside the cell
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The hunt pickup nudge: the bot stands in its cell and walks toward an item that lies just beyond vanilla's pickup box, close
     * enough for the box to reach it; vanilla's own pickup then collects the item. Nothing moves the bot but its inputs.
     */
    @GameTest(environment = "minecraftai-gametest:pickup_natural_movement_game_tests_hunt_drop_pickup_walks", maxTicks = 120)
    public void huntDropPickupWalks(GameTestHelper context) {
        Arena arena = Arena.build(context, 2, -3, 6, -3, 3);
        BlockPos stand = arena.at(0, 0, 0);
        AIPlayerEntity bot = arena.spawn("PickupHuntNudgeGT", stand);
        // The item lies 1.55 blocks east of the middle of the cell: outside the pickup box of a bot standing there, inside the box of
        // one that has leaned 0.4 blocks toward it.
        Vec3 dropAt = new Vec3(stand.getX() + 0.5D + 1.55D, stand.getY(), stand.getZ() + 0.5D);
        ItemEntity drop = new ItemEntity(arena.world, dropAt.x, dropAt.y, dropAt.z, new ItemStack(Items.PORKCHOP));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setNoGravity(true);
        arena.world.addFreshEntity(drop);
        int[] tick = {0};
        double[] startX = {bot.getX()};
        context.onEachTick(() -> {
            tick[0]++;
            if (InventoryAction.countItem(bot, Items.PORKCHOP) > 0) {
                requireNoCorrections(context, bot, "hunt nudge");
                require(context, bot.getX() - startX[0] >= 0.15D,
                        "the drop was collected without the bot walking toward it: x moved " + (bot.getX() - startX[0]));
                arena.finish(bot);
                return;
            }
            if (tick[0] > 12) {
                InCellWalk.nudgeToward(bot, stand, drop.position(), "physical_drop_pickup");
            }
            require(context, tick[0] < 100, "the drop was not collected, bot at " + bot.position());
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Known-cell pickup sweep
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The sweep around a remembered break cell: the same-cell nudges and the walks to the neighbouring cells are all inputs. The break
     * cell has a block above it (its own cell is not standable), like a log that still has a log above it.
     */
    @GameTest(environment = "minecraftai-gametest:pickup_natural_movement_game_tests_known_cell_sweep_walks", maxTicks = 500)
    public void knownCellSweepWalks(GameTestHelper context) {
        Arena arena = Arena.build(context, 3, -5, 5, -5, 5);
        arena.set(2, 1, 0, Blocks.STONE);
        BlockPos origin = arena.at(2, 0, 0);
        AIPlayerEntity bot = arena.spawn("PickupSweepGT", arena.at(1, 0, 0));
        KnownCellPickupSweep sweep = new KnownCellPickupSweep(origin);
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            var pack = bot.getActionPack();
            if (sweep.cellsVisited() >= 4) {
                requireNoCorrections(context, bot, "pickup sweep");
                arena.finish(bot);
                return;
            }
            if (pack.isPathExecutorIdle() && pack.isWalkToIdle() && pack.stepIdle()) {
                KnownCellPickupSweep.Step step = sweep.step(bot);
                require(context, step != KnownCellPickupSweep.Step.EXHAUSTED || sweep.cellsVisited() >= 4,
                        "the sweep ran out of cells after " + sweep.cellsVisited());
            }
            require(context, tick[0] < 480, "timed out: visited " + sweep.cellsVisited() + " at "
                    + bot.blockPosition().toShortString());
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Craft: the table reclaim settles by walking back to the middle of the cell
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A craft that needs a table places the carried one, crafts, mines it back and walks over the drop; the last move of the task is
     * the walk back to the middle of the cell (it used to teleport there). The table is back in the inventory, the bot never teleported.
     */
    @GameTest(environment = "minecraftai-gametest:pickup_natural_movement_game_tests_craft_table_reclaim_settles_without_teleport", maxTicks = 900)
    public void craftTableReclaimSettlesWithoutTeleport(GameTestHelper context) {
        Arena arena = Arena.build(context, 4, -6, 6, -6, 6);
        AIPlayerEntity bot = arena.spawn("PickupCraftSettleGT", arena.at(0, 0, 0));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        CraftTask task = new CraftTask(Items.STONE_PICKAXE, 1);
        task.start(bot);
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }

            if (task.state() == TaskState.COMPLETED) {
                requireNoCorrections(context, bot, "table reclaim");
                require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1, "no stone pickaxe was crafted");
                require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1, "the table was not reclaimed");
                double middleX = bot.blockPosition().getX() + 0.5D;
                double middleZ = bot.blockPosition().getZ() + 0.5D;
                require(context, Math.hypot(bot.getX() - middleX, bot.getZ() - middleZ) <= 0.25D,
                        "the bot was not left in the middle of its cell: " + bot.position());
                require(context, !bot.getActionPack().hasActiveActions(), "the task completed with a step or key still held");
                arena.finish(bot);
                return;
            }
            require(context, task.state() != TaskState.FAILED, "the craft failed: " + task.failureReason());
            require(context, tick[0] < 880, "timed out in " + task.describe());
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Gather: surfacing in strict survival looks at nothing
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Strict survival has no emergency teleport, so an underground gather that finds nothing must not even look at the cells above it
     * (an unobserved scan of up to 80 cells): the capability is decided before the scan and a denial returns at once.
     */
    @GameTest(environment = "minecraftai-gametest:pickup_natural_movement_game_tests_gather_surface_does_not_scan_in_strict", maxTicks = 40)
    public void gatherSurfaceDoesNotScanInStrict(GameTestHelper context) {
        Arena arena = Arena.build(context, 5, -2, 2, -2, 2);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 2; dy <= 7; dy++) {
                    arena.set(dx, dy, dz, Blocks.STONE);
                }
            }
        }
        AIPlayerEntity bot = arena.spawn("PickupGatherSurfaceGT", arena.feet);
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        context.runAtTickTime(2, () -> {
            require(context, !arena.world.canSeeSky(bot.blockPosition()), "fixture: the bot can see the sky");
            int before = GatherQuotaTask.surfaceScanLookups;
            boolean surfaced;
            try {
                var method = GatherQuotaTask.class.getDeclaredMethod("trySurface", AIPlayerEntity.class);
                method.setAccessible(true);
                surfaced = (Boolean) method.invoke(task, bot);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            require(context, !surfaced, "strict survival surfaced the bot");
            require(context, GatherQuotaTask.surfaceScanLookups == before,
                    "strict survival looked at the cells above the bot: " + (GatherQuotaTask.surfaceScanLookups - before));
            requireNoCorrections(context, bot, "gather surface");
            arena.finish(bot);
        });
    }
}
