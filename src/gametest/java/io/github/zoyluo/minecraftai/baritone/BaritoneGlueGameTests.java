package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.HostEnvironment;
import baritone.api.utils.PathCalculationResult;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The glue around Baritone that is not a movement: headless boot, the per-bot registry and its lifecycle, the bounded worker
 * pool, the fixed settings, the observable-entity list.
 */
public final class BaritoneGlueGameTests {

    @GameTest(maxTicks = 200)
    public void headlessServerLoadsEveryBaritoneClassAndGivesBotAnInstance(GameTestHelper context) throws IOException {
        require(context, FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER, "this is not a dedicated-server environment");
        boolean clientClassLoaded;
        try {
            Class.forName("net.minecraft.client.Minecraft", false, getClass().getClassLoader());
            clientClassLoaded = true;
        } catch (ClassNotFoundException | RuntimeException expected) {
            // Fabric refuses (or the class is absent): the client half of Minecraft is not part of this server, exactly what this test wants
            clientClassLoaded = false;
        }
        require(context, !clientClassLoaded, "net.minecraft.client.Minecraft is on the classpath: the boot is not headless");
        // Loading a class (verification + static initialisation) is what turns a stray client reference into a
        // NoClassDefFoundError, so initialise every class of the Baritone build.
        BaritoneHost.configure(context.getLevel().getServer());
        ClassLoader loader = getClass().getClassLoader();
        List<String> failures = new ArrayList<>();
        int loaded = 0;
        for (Path root : FabricLoader.getInstance().getModContainer("minecraftai").orElseThrow().getRootPaths()) {
            Path baritonePackage = root.resolve("baritone");
            if (!Files.isDirectory(baritonePackage)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(baritonePackage)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String name = root.relativize(file).toString().replace('\\', '/').replace('/', '.');
                    name = name.substring(0, name.length() - ".class".length());
                    try {
                        // Upstream's registry-reloading stand-in level (BlockOptionalMeta.ServerLevelStub) is dead code in this build (patch
                        // 0010: loot is rolled on the host's real level) and cannot initialise on a server; it must still load, so it is
                        // loaded without running its static initialiser.
                        Class.forName(name, !name.endsWith("BlockOptionalMeta$ServerLevelStub"), loader);
                        loaded++;
                    } catch (LinkageError | ClassNotFoundException e) {
                        failures.add(name + ": " + e);
                    }
                }
            }
        }
        System.out.println("BARITONE_HEADLESS classes_initialised=" + loaded + " failures=" + failures);
        require(context, failures.isEmpty(), "classes that do not load on a client-free server: " + failures);
        require(context, loaded >= 300, "only " + loaded + " Baritone classes were found in the mod (expected about 350)");

        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(4, 40, 4));
        BaritoneServerGameTests.preparePlatform(world, feet, 4);
        AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, "BaritoneBootGT", feet);
        try {
            IBaritone baritone = BaritoneRegistry.INSTANCE.get(bot);
            require(context, baritone != null && baritone.getPlayerContext().player() == bot, "the bot did not get its instance");
        } finally {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), "BaritoneBootGT");
        }
        context.succeed();
    }

    @GameTest(maxTicks = 100)
    public void oneInstancePerBotAndItGoesWithTheBot(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(4, 40, 4));
        BaritoneServerGameTests.preparePlatform(world, feet, 4);
        AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, "BaritoneRegistryGT", feet);
        int before = BaritoneRegistry.INSTANCE.size();
        IBaritone first = BaritoneRegistry.INSTANCE.get(bot);
        require(context, BaritoneRegistry.INSTANCE.get(bot) == first, "a second get() made a second instance");
        require(context, BaritoneRegistry.INSTANCE.find(bot.getUUID()) == first, "find() does not see the instance");
        require(context, BaritoneRegistry.INSTANCE.size() == before + 1, "registry size " + BaritoneRegistry.INSTANCE.size());
        require(context, BaritoneHost.of(bot) == first, "Baritone's own provider does not know the instance");
        AIPlayerManager.INSTANCE.despawn(world.getServer(), "BaritoneRegistryGT");
        require(context, BaritoneRegistry.INSTANCE.find(bot.getUUID()) == null, "the instance survived despawn");
        require(context, BaritoneHost.of(bot) == null, "the destroyed instance is still known to Baritone's provider");
        require(context, BaritoneRegistry.INSTANCE.size() == before, "registry size after despawn " + BaritoneRegistry.INSTANCE.size());
        context.succeed();
    }

    @GameTest(maxTicks = 100)
    public void resetKeepsTheInstanceButDropsItsGoalAndPath(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(6, 40, 6));
        BaritoneServerGameTests.preparePlatform(world, feet, 5);
        AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, "BaritoneResetGT", feet);
        try {
            IBaritone baritone = BaritoneRegistry.INSTANCE.get(bot);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(feet.offset(4, 0, 0)));
            for (int i = 0; i < 3; i++) {
                BaritoneRegistry.INSTANCE.tick(bot);
            }
            require(context, baritone.getPathingBehavior().getGoal() != null, "no goal was set");
            BaritoneRegistry.INSTANCE.reset(bot, "test");
            require(context, BaritoneRegistry.INSTANCE.find(bot.getUUID()) == baritone, "reset destroyed the instance");
            require(context, !baritone.getCustomGoalProcess().isActive(), "the goal process is still active after the reset");
            require(context, !baritone.getPathingBehavior().isPathing(), "the bot is still pathing after the reset");
            require(context, baritone.getPathingBehavior().getCurrent() == null, "the path survived the reset");
        } finally {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), "BaritoneResetGT");
        }
        context.succeed();
    }

    @GameTest(maxTicks = 200)
    public void workerPoolIsBoundedNamedAndDaemon(GameTestHelper context) {
        BaritoneHost.configure(context.getLevel().getServer());
        int workers = BaritoneExecutor.workers();
        int tasks = workers * 4;
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        List<String> threadNames = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Boolean> daemon = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < tasks; i++) {
            HostEnvironment.executor().execute(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                threadNames.add(Thread.currentThread().getName());
                daemon.add(Thread.currentThread().isDaemon());
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                finished.incrementAndGet();
            });
        }
        context.onEachTick(() -> {
            if (finished.get() < tasks) {
                return;
            }
            System.out.println("BARITONE_POOL workers=" + workers + " tasks=" + tasks + " peak_concurrency=" + peak.get()
                    + " max_queue_wait_ms=" + BaritoneExecutor.maxQueueWaitMillis());
            require(context, peak.get() <= workers, "peak concurrency " + peak.get() + " exceeds the " + workers + " workers");
            require(context, peak.get() >= 2, "the pool never ran two tasks at once (peak " + peak.get() + ")");
            require(context, threadNames.stream().allMatch(n -> n.startsWith("minecraftai-baritone-")), "unexpected worker threads " + threadNames);
            require(context, daemon.stream().allMatch(Boolean::booleanValue), "a worker thread is not a daemon");
            context.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void queuedPlanIsCancelledWithoutRunningWhenItsBotGoesAway(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, 40, 8));
        BaritoneServerGameTests.preparePlatform(world, feet, 7);
        AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, "BaritoneQueuedGT", feet);
        IBaritone baritone = BaritoneRegistry.INSTANCE.get(bot);
        CountDownLatch release = new CountDownLatch(1);
        occupyEveryWorker(release);
        CompletableFuture<BaritonePlanner.Plan> plan = BaritonePlanner.plan(baritone, new GoalBlock(feet.offset(6, 0, 3)));
        require(context, !plan.isDone(), "the plan finished although every worker was busy");
        AIPlayerManager.INSTANCE.despawn(world.getServer(), "BaritoneQueuedGT");
        release.countDown();
        context.onEachTick(() -> {
            if (!plan.isDone()) {
                return;
            }
            BaritonePlanner.Plan result = plan.join();
            System.out.println("BARITONE_QUEUED type=" + result.type() + " search_ms=" + result.searchMillis() + " queue_ms=" + result.queueMillis());
            require(context, result.type() == PathCalculationResult.Type.CANCELLATION, "expected CANCELLATION, got " + result.type());
            require(context, result.nodesConsidered() == 0 && result.searchMillis() < 100,
                    "the cancelled search still ran: " + result.searchMillis() + " ms");
            require(context, BaritonePlanner.inFlight() == 0, "the planner still lists a search in flight");
            context.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void newPlanSupersedesTheQueuedOneOfTheSameBot(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, 40, 8));
        BaritoneServerGameTests.preparePlatform(world, feet, 7);
        AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, "BaritoneSupersedeGT", feet);
        IBaritone baritone = BaritoneRegistry.INSTANCE.get(bot);
        CountDownLatch release = new CountDownLatch(1);
        occupyEveryWorker(release);
        CompletableFuture<BaritonePlanner.Plan> first = BaritonePlanner.plan(baritone, new GoalBlock(feet.offset(6, 0, 3)));
        CompletableFuture<BaritonePlanner.Plan> second = BaritonePlanner.plan(baritone, new GoalBlock(feet.offset(-5, 0, 2)));
        release.countDown();
        context.onEachTick(() -> {
            if (!first.isDone() || !second.isDone()) {
                return;
            }
            try {
                require(context, first.join().type() == PathCalculationResult.Type.CANCELLATION, "the superseded plan was " + first.join().type());
                require(context, second.join().reachesGoal(), "the new plan was " + second.join().type());
                require(context, second.join().path().getDest().equals(new baritone.api.utils.BetterBlockPos(feet.offset(-5, 0, 2))),
                        "the new plan went to " + second.join().path().getDest());
            } finally {
                AIPlayerManager.INSTANCE.despawn(world.getServer(), "BaritoneSupersedeGT");
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = 40)
    public void fixedServerSettingsAreApplied(GameTestHelper context) {
        BaritoneHost.configure(context.getLevel().getServer());
        BaritoneSettings.applyNavLimits();
        Settings s = BaritoneAPI.getSettings();
        int safeFall = MinecraftAiConfig.get().nav().maxSafeFall();
        MinecraftAiConfig.BaritoneCaps caps = MinecraftAiConfig.get().nav().baritoneCaps();
        require(context, s.allowParkour.value == caps.parkourEnabled() && s.allowParkourPlace.value == (caps.parkourEnabled() && caps.parkourPlaceEnabled())
                && s.allowParkourAscend.value == (caps.parkourEnabled() && caps.parkourAscendEnabled()), "parkour does not follow nav.baritone");
        require(context, s.allowWaterBucketFall.value == caps.waterBucketFallEnabled(), "the water-bucket fall does not follow nav.baritone");
        require(context, s.allowVines.value == caps.vinesEnabled(), "vines do not follow nav.baritone");
        require(context, s.mobSpawnerAvoidanceCoefficient.value == 1.0D, "mob spawners are avoided (that needs the world cache)");
        require(context, s.maxFallHeightNoWater.value == safeFall, "maxFallHeightNoWater=" + s.maxFallHeightNoWater.value + " nav safe fall=" + safeFall);
        require(context, !s.chunkCaching.value, "chunk caching is on");
        require(context, s.avoidance.value == caps.mobAvoidanceEnabled(), "mob avoidance does not follow nav.baritone");
        require(context, !s.freeLook.value && !s.blockFreeLook.value, "free look is on");
        require(context, !s.chatDebug.value && !s.logAsToast.value && !s.desktopNotifications.value, "chat/toast/desktop output is on");
        context.succeed();
    }

    @GameTest(maxTicks = 100)
    public void observableEntitiesAreTheBotAndNearbyDropsAndReadableFromWorker(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(6, 40, 6));
        BaritoneServerGameTests.preparePlatform(world, feet, 5);
        AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, "BaritoneEntitiesGT", feet);
        IBaritone baritone = BaritoneRegistry.INSTANCE.get(bot);
        ItemEntity drop = new ItemEntity(world, feet.getX() + 3.5, feet.getY() + 0.2, feet.getZ() + 0.5, new ItemStack(Items.COBBLESTONE));
        drop.setDeltaMovement(0, 0, 0);
        world.addFreshEntity(drop);
        var ctx = baritone.getPlayerContext();
        BaritoneRegistry.INSTANCE.tick(bot); // refreshes the observable list
        List<Entity> onServerThread = new ArrayList<>();
        ctx.entities().forEach(onServerThread::add);
        AtomicReference<List<Entity>> fromWorker = new AtomicReference<>();
        CompletableFuture<Void> read = CompletableFuture.runAsync(() -> {
            List<Entity> list = new ArrayList<>();
            ctx.entities().forEach(list::add);
            fromWorker.set(list);
        }, HostEnvironment.executor());
        context.runAfterDelay(5, () -> {
            try {
                require(context, onServerThread.contains(bot), "the bot itself is missing from ctx.entities()");
                require(context, onServerThread.contains(drop), "the nearby drop is missing: " + onServerThread);
                require(context, onServerThread.stream().allMatch(e -> e == bot || e instanceof ItemEntity), "entities other than dropped items were offered: " + onServerThread);
                require(context, read.isDone() && fromWorker.get() != null && fromWorker.get().contains(drop),
                        "a worker thread could not read the snapshot: " + fromWorker.get());
            } finally {
                drop.discard();
                AIPlayerManager.INSTANCE.despawn(world.getServer(), "BaritoneEntitiesGT");
            }
            context.succeed();
        });
    }

    /** Keeps every worker of the shared pool busy until {@code release} is counted down (the tasks are short-lived after that). */
    private static void occupyEveryWorker(CountDownLatch release) {
        int workers = BaritoneExecutor.workers();
        CountDownLatch started = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) {
            HostEnvironment.executor().execute(() -> {
                started.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        try {
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the workers did not pick up the blocking tasks");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        BaritoneServerGameTests.require(context, condition, message);
    }
}
