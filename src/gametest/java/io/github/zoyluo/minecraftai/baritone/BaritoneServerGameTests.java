package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.accessor.IItemStack;
import baritone.api.utils.accessor.ILootTable;
import baritone.api.utils.input.Input;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.accessor.IClientChunkProvider;
import baritone.utils.accessor.IPalettedContainer;
import baritone.utils.pathing.Favoring;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Proves the server-only Baritone (third_party/baritone + tools/baritone patches) works against a real {@link ServerLevel}
 * and a real bot through the glue in this package: player context, chunk snapshot, tick pump.
 */
public final class BaritoneServerGameTests {

    @GameTest(maxTicks = 20)
    public void mixinsAreAppliedToTheChunkClasses(GameTestHelper context) {
        require(context, IClientChunkProvider.class.isAssignableFrom(ServerChunkCache.class),
                "ServerChunkCacheBaritoneMixin did not make ServerChunkCache implement IClientChunkProvider");
        require(context, java.util.Arrays.stream(ChunkMap.class.getInterfaces())
                        .anyMatch(i -> i.getName().equals("io.github.zoyluo.minecraftai.mixin.ChunkMapVisibleChunksAccessorMixin")),
                "ChunkMapVisibleChunksAccessorMixin was not applied to ChunkMap");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void accessorMixinsAreAppliedToTheirTargets(GameTestHelper context) {
        require(context, IItemStack.class.isAssignableFrom(net.minecraft.world.item.ItemStack.class),
                "BaritoneItemStackMixin was not applied to ItemStack");
        require(context, ILootTable.class.isAssignableFrom(net.minecraft.world.level.storage.loot.LootTable.class),
                "BaritoneLootTableMixin was not applied to LootTable");
        require(context, IPalettedContainer.class.isAssignableFrom(net.minecraft.world.level.chunk.PalettedContainer.class),
                "BaritonePalettedContainerMixin was not applied to PalettedContainer");
        context.succeed();
    }

    @GameTest(maxTicks = 100)
    public void oreScanFindsBlocksThroughTheSnapshotAndTheRawPalettes(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, 40, 8));
        preparePlatform(world, feet, 7);
        BlockPos ore = feet.offset(-4, -3, 2);
        world.setBlock(ore, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        String name = "BaritoneScanGT";
        AIPlayerEntity bot = spawn(context, name, feet);
        IBaritone baritone = BaritoneHost.create(bot);
        try {
            List<BlockPos> found = BaritoneAPI.getProvider().getWorldScanner()
                    .scanChunkRadius(baritone.getPlayerContext(), new BlockOptionalMetaLookup(Blocks.DIAMOND_ORE), 64, 10, 2);
            require(context, found.contains(ore), "the scan did not report the ore at " + ore + ", found " + found);
        } finally {
            BaritoneHost.destroy(baritone);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    @GameTest(maxTicks = 100)
    public void blockDropsAreLearnedFromTheLootTablesAndMatchedByItemHash(GameTestHelper context) {
        BaritoneHost.configure(context.getLevel().getServer());
        BlockOptionalMeta ironOre = new BlockOptionalMeta(Blocks.IRON_ORE);
        require(context, ironOre.matches(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RAW_IRON)),
                "iron ore's drop (raw iron) is not matched: the loot-table stub or the item hash is broken");
        require(context, !ironOre.matches(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE)),
                "cobblestone must not match iron ore's drops");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void aBaritoneInstanceIsCreatedForABotAndFoundByItsPlayer(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(4, 40, 4));
        preparePlatform(world, feet, 6);
        String name = "BaritoneCreateGT";
        AIPlayerEntity bot = spawn(context, name, feet);
        IBaritone baritone = BaritoneHost.create(bot);
        try {
            require(context, BaritoneHost.of(bot) == baritone, "getBaritoneForPlayer did not return the instance made for the bot");
            var ctx = baritone.getPlayerContext();
            require(context, ctx.player() == bot, "context player is not the bot");
            require(context, ctx.world() == world, "context world is not the bot's level");
            require(context, ctx.minecraft() == world.getServer() && ctx.minecraft().isSameThread(),
                    "context game-thread object is not the running server");
            require(context, ctx.playerFeet().equals(new BetterBlockPos(feet)),
                    "playerFeet=" + ctx.playerFeet() + " expected " + feet);
            boolean sawBot = false;
            for (var entity : ctx.entities()) {
                sawBot |= entity == bot;
            }
            require(context, sawBot, "ctx.entities() does not list the bot");
            require(context, ctx.worldData() != null, "no world data (the cache directory could not be set up)");
        } finally {
            BaritoneHost.destroy(baritone);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        require(context, BaritoneHost.of(bot) == null, "the destroyed instance is still registered");
        context.succeed();
    }

    @GameTest(maxTicks = 100)
    public void chunkSnapshotIsReadableFromAnotherThread(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(4, 40, 4));
        preparePlatform(world, feet, 6);
        BlockPos marker = feet.offset(3, -1, 2);
        world.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        String name = "BaritoneSnapshotGT";
        AIPlayerEntity bot = spawn(context, name, feet);
        IBaritone baritone = BaritoneHost.create(bot);

        // Built on the server thread (BlockStateInterface insists on it), read from a worker: this is what every path search does.
        BlockStateInterface bsi = new BlockStateInterface(baritone.getPlayerContext(), true);
        int farX = feet.getX() + 3_000_000; // nothing there is loaded
        CompletableFuture<List<Object>> read = CompletableFuture.supplyAsync(() -> List.<Object>of(
                bsi.get0(marker.getX(), marker.getY(), marker.getZ()),
                bsi.get0(feet.getX(), feet.getY(), feet.getZ()),
                bsi.isLoaded(marker.getX(), marker.getZ()),
                bsi.isLoaded(farX, feet.getZ())), baritoneExecutor());
        context.runAfterDelay(10, () -> {
            try {
                require(context, read.isDone(), "the worker did not finish reading the snapshot");
                List<Object> values = read.join();
                require(context, values.get(0) == Blocks.GOLD_BLOCK.defaultBlockState(), "snapshot read " + values.get(0) + " at the marker");
                require(context, ((BlockState) values.get(1)).isAir(), "snapshot read " + values.get(1) + " at the bot's feet");
                require(context, Boolean.TRUE.equals(values.get(2)), "isLoaded is false for a loaded chunk");
                require(context, Boolean.FALSE.equals(values.get(3)), "isLoaded is true for a chunk that is not loaded");
            } finally {
                BaritoneHost.destroy(baritone);
                AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = 600)
    public void aStarPlansAcrossThePlatformAndThroughAWallOnAnotherThread(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, 40, 8));
        preparePlatform(world, feet, 7);
        BlockPos openGoal = feet.offset(6, 0, -4);
        // A full-width wall two blocks east of the start, three high: the only way east is through it.
        BlockPos wallGoal = feet.offset(-6, 0, 0);
        for (int dz = -7; dz <= 7; dz++) {
            for (int dy = 0; dy < 3; dy++) {
                world.setBlock(feet.offset(-3, dy, dz), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        String name = "BaritoneAStarGT";
        AIPlayerEntity bot = spawn(context, name, feet);
        IBaritone baritone = BaritoneHost.create(bot);

        CompletableFuture<PathCalculationResult> open = search(baritone, feet, openGoal);
        CompletableFuture<PathCalculationResult> wall = search(baritone, feet, wallGoal);
        // The workers need real server time and may consume snapshot work while GameTest ticks
        // continue. Poll without blocking the server thread, leaving 40 ticks for framework
        // cleanup after the 560-tick bounded worker window.
        int[] waited = {0};
        boolean[] settled = {false};
        context.onEachTick(() -> {
            if (settled[0]) {
                return;
            }
            if (!open.isDone() || !wall.isDone()) {
                if (++waited[0] < 560) {
                    return;
                }
                settled[0] = true;
                try {
                    open.cancel(true);
                    wall.cancel(true);
                    require(context, false, "the searches did not finish in 560 ticks (open="
                            + open.isDone() + " wall=" + wall.isDone() + ")");
                } finally {
                    BaritoneHost.destroy(baritone);
                    AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                }
                return;
            }
            settled[0] = true;
            try {
                PathCalculationResult openResult = open.join();
                require(context, openResult.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL,
                        "open-floor search: " + openResult.getType());
                var openPath = openResult.getPath().orElseThrow();
                require(context, openPath.getDest().equals(new BetterBlockPos(openGoal)), "open path ends at " + openPath.getDest());
                require(context, openPath.positions().size() <= 12, "open path is not direct: " + openPath.positions().size() + " nodes");

                PathCalculationResult wallResult = wall.join();
                require(context, wallResult.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL,
                        "through-the-wall search: " + wallResult.getType());
                var wallPath = wallResult.getPath().orElseThrow();
                require(context, wallPath.getDest().equals(new BetterBlockPos(wallGoal)), "wall path ends at " + wallPath.getDest());
                boolean crossesWall = wallPath.positions().stream().anyMatch(p -> p.getX() == feet.getX() - 3);
                require(context, crossesWall, "the path to the far side never enters the wall column");
            } finally {
                BaritoneHost.destroy(baritone);
                AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = 120)
    public void theWholeBehaviorStackTicksOnTheServerAndPlansAndSteersWithoutAClient(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, 40, 8));
        preparePlatform(world, feet, 7);
        BlockPos goal = feet.offset(6, 0, 3);
        String name = "BaritoneStackGT";
        AIPlayerEntity bot = spawn(context, name, feet);
        IBaritone baritone = BaritoneHost.create(bot);
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));

        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger forwardTicks = new AtomicInteger();
        var nextTick = TickEvent.createNextProvider();
        context.onEachTick(() -> {
            // What the real integration does once per server tick, before the bot's own tick.
            baritone.getGameEventHandler().onTick(nextTick.apply(EventState.PRE, TickEvent.Type.IN));
            if (baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD)) {
                forwardTicks.incrementAndGet();
            }
            if (ticks.incrementAndGet() == 100) {
                try {
                    require(context, baritone.getPathingBehavior().getGoal() != null, "the goal was dropped");
                    require(context, baritone.getPathingBehavior().getCurrent() != null || baritone.getPathingBehavior().isPathing(),
                            "no path was ever planned/executed after 100 ticks");
                    require(context, forwardTicks.get() > 0, "the path executor never asked to walk forward");
                } finally {
                    BaritoneHost.destroy(baritone);
                    AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                }
                context.succeed();
            }
        });
    }

    private static CompletableFuture<PathCalculationResult> search(IBaritone baritone, BlockPos from, BlockPos to) {
        // Both are built on the server thread, like PathingBehavior does; only calculate() runs on the worker.
        CalculationContext calc = new CalculationContext(baritone, true);
        BetterBlockPos start = new BetterBlockPos(from);
        var finder = new AStarPathFinder(start, start.x, start.y, start.z, new GoalBlock(to),
                new Favoring(baritone.getPlayerContext(), null, calc), calc);
        return CompletableFuture.supplyAsync(() -> finder.calculate(3_000L, 6_000L), baritoneExecutor());
    }

    private static java.util.concurrent.Executor baritoneExecutor() {
        return baritone.Baritone.getExecutor();
    }

    static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        return bot;
    }

    /** Flat, open, solid-floored platform. */
    static void preparePlatform(ServerLevel world, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy < 4; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }
}
