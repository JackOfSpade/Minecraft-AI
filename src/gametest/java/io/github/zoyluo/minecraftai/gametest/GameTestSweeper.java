package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clears what one test leaves behind before the next one starts.
 *
 * <p>Every test runs in a batch of its own ({@link GameTestIsolation}); the runner starts the next batch in the very tick the last
 * one finishes and lays its structure out in the grid cell next to the old one (13 blocks apart), while scenes reach well past their
 * cell. A zombie, an arrow, a dropped item or a bot that a finished (and above all a failed) test did not remove therefore stood a few
 * blocks from the next test, and a hostile mob 6 blocks away is exactly what a bot under test reacts to. Nothing in a test can see
 * this: the victim has no way to know that the neighbour's leftovers are not part of its own scene.</p>
 *
 * <p>Every non-player entity that enters the world is remembered. The moment a batch starts while no test of the previous batch is
 * still running, that is, before any test of the new batch has executed a single line (the runner only starts a test on a later
 * tick than the one that created it), every remembered entity that is still alive is discarded, every Minecraft-AI bot that is
 * still online is despawned and every mock player that is still connected is disconnected. Each of those is a leak of the test that
 * just ended, so it is logged with its name: {@code GameTestSweeper} is also how a test that does not clean up is found. At the same
 * moment {@link GameTestWorldRestorer} puts the blocks back, the ambient (clock, weather, game rules) is reset to the suite's own and
 * the JVM-wide configurations and test hooks a test may have swapped for its premise are put back, loudly when that was a leak.</p>
 */
public final class GameTestSweeper {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-gametest-sweeper");
    private static final Field TEST_INFOS = testInfosField();
    /** Chunks force-loaded around the structure of every test (3 chunks = 48 blocks each way). */
    private static final int FORCED_RADIUS_CHUNKS = 3;
    /** The ambient world clock every test starts in (morning, full daylight). */
    public static final long AMBIENT_DAY_TIME = 1000L;

    /** Every non-player entity that entered the world since the last sweep (server thread only). */
    private static final Set<Entity> TRACKED = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The tests of the batch that is running or just ran (server thread only). */
    private static final Set<GameTestInfo> KNOWN = Collections.newSetFromMap(new IdentityHashMap<>());

    /** What the suite started with; a test that leaves something else behind is reported and undone (server thread only). */
    private static io.github.zoyluo.minecraftai.MinecraftAiConfig baselineConfig;
    private static io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig baselineAssistConfig;
    private static GameRules baselineRules;
    private static boolean baselinePerception;

    private GameTestSweeper() {
    }

    /** {@code SERVER_STARTED}, after the harness has set the ambient: remembers what every test is entitled to find. */
    public static void captureBaseline(MinecraftServer server) {
        baselineConfig = io.github.zoyluo.minecraftai.MinecraftAiConfig.get();
        baselineAssistConfig = io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime.config();
        ServerLevel world = server.overworld();
        baselineRules = world.getGameRules().copy(world.enabledFeatures());
        baselinePerception = io.github.zoyluo.minecraftai.perception.CreatureSenses.enabled();
    }

    /**
     * The clock, the weather and the game rules are world-wide, and a test sets them for its premise (a night, rain, the clock or
     * random ticks running): put the suite's ambient back for the next test. This is not a leak, so it is not reported.
     */
    private static void restoreAmbient(MinecraftServer server) {
        ServerLevel world = server.overworld();
        if (world.getDayTime() != AMBIENT_DAY_TIME) {
            world.setDayTime(AMBIENT_DAY_TIME);
        }
        if (world.isRaining() || world.isThundering() || world.getRainLevel(1.0F) > 0.0F || world.getThunderLevel(1.0F) > 0.0F) {
            world.setWeatherParameters(6000, 0, false, false);
            world.setRainLevel(0.0F);
            world.setThunderLevel(0.0F);
        }
        if (baselineRules != null) {
            GameRules rules = world.getGameRules();
            baselineRules.availableRules().forEach(rule -> restoreRule(rules, rule, server));
        }
    }

    private static <T> void restoreRule(GameRules rules, GameRule<T> rule, MinecraftServer server) {
        T suite = baselineRules.get(rule);
        if (!java.util.Objects.equals(rules.get(rule), suite)) {
            rules.set(rule, suite, server);
        }
    }

    /**
     * Several fixtures swap the JVM-wide {@code MinecraftAiConfig} or the mining assist config, or switch a test hook (perception on,
     * a degraded-TPS verdict), for their own run and undo it in their cleanup; one that fails before it gets there leaves it behind for
     * every later test. Put the suite's own back and say which test it was.
     */
    private static void guardGlobals(List<String> tests) {
        if (baselineConfig != null && io.github.zoyluo.minecraftai.MinecraftAiConfig.get() != baselineConfig) {
            LOG.warn("a test left a different MinecraftAiConfig behind, restoring the suite's own; tests: {}", tests);
            try {
                Field instance = io.github.zoyluo.minecraftai.MinecraftAiConfig.class.getDeclaredField("instance");
                instance.setAccessible(true);
                instance.set(null, baselineConfig);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("cannot restore MinecraftAiConfig", failed);
            }
        }
        if (baselineAssistConfig != null && io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime.config() != baselineAssistConfig) {
            LOG.warn("a test left a different mining assist config behind, restoring the suite's own; tests: {}", tests);
            io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime.install(baselineAssistConfig);
        }
        if (io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime.testTpsDegraded() != null) {
            LOG.warn("a test left a TPS verdict override behind ({}), clearing it; tests: {}",
                    io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime.testTpsDegraded(), tests);
            io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime.setTestTpsDegraded(null);
        }
        if (io.github.zoyluo.minecraftai.perception.CreatureSenses.enabled() != baselinePerception) {
            LOG.warn("a test left realistic perception switched {}, switching it back; tests: {}",
                    io.github.zoyluo.minecraftai.perception.CreatureSenses.enabled() ? "on" : "off", tests);
            io.github.zoyluo.minecraftai.perception.CreatureSenses.forceEnabledForTests(false);
        }
    }

    /** {@code ServerEntityEvents.ENTITY_LOAD}: remembers an entity that is not a player. */
    public static void track(Entity entity) {
        if (!(entity instanceof Player)) {
            TRACKED.add(entity);
        }
    }

    /** {@code ServerTickEvents.END_SERVER_TICK}: detects the start of a new batch and sweeps the previous one. */
    public static void endTick(MinecraftServer server) {
        List<GameTestInfo> infos = infos();
        List<GameTestInfo> fresh = new ArrayList<>();
        boolean previousStillRunning = false;
        for (GameTestInfo info : KNOWN) {
            if (!info.isDone()) {
                previousStillRunning = true;
                break;
            }
        }
        for (GameTestInfo info : infos) {
            if (!KNOWN.contains(info)) {
                fresh.add(info);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        if (!previousStillRunning) {
            // The structures the runner has just placed for the new batch are the one thing that must stay.
            List<AABB> keep = new ArrayList<>();
            for (GameTestInfo info : fresh) {
                AABB bounds;
                try {
                    bounds = info.getStructureBounds();
                } catch (RuntimeException notPlacedYet) {
                    bounds = null;
                }
                keep.add((bounds == null ? new AABB(info.getTestOrigin()) : bounds).inflate(3.0D));
            }
            int blocks = GameTestWorldRestorer.restore(keep);
            // Path and standability memos describe the blocks of the batch that just ended.
            io.github.zoyluo.minecraftai.pathfinding.Standability.invalidateAll();
            sweep(server, blocks);
            List<String> finished = new ArrayList<>();
            for (GameTestInfo info : KNOWN) {
                finished.add(info.id().getPath());
            }
            guardGlobals(finished.size() > 6 ? finished.subList(0, 6) : finished);
            restoreAmbient(server);
            KNOWN.clear();
        }
        forceSurroundings(fresh);
        KNOWN.addAll(fresh);
    }

    /**
     * The runner force-loads only the chunks under the structure of each test, and the structure is a few blocks wide. A scene (a
     * platform, a mob six blocks off, a pond, a corridor) that reaches into the next chunk stands in a chunk no player keeps entity-ticking
     * and no ticket keeps loaded: a mob there does not tick (a skeleton never advances its bow draw, an item is never picked up), light
     * is not published, and a path through it stops at the border. Whether that happens depends only on where the batch landed, which
     * is random. Every test therefore gets the chunks around its structure force-loaded too; like the runner's own they are released when
     * the batch ends.
     */
    private static void forceSurroundings(List<GameTestInfo> fresh) {
        for (GameTestInfo info : fresh) {
            net.minecraft.core.BlockPos origin = info.getTestOrigin();
            int chunkX = origin.getX() >> 4;
            int chunkZ = origin.getZ() >> 4;
            for (int dx = -FORCED_RADIUS_CHUNKS; dx <= FORCED_RADIUS_CHUNKS; dx++) {
                for (int dz = -FORCED_RADIUS_CHUNKS; dz <= FORCED_RADIUS_CHUNKS; dz++) {
                    GameTestWorldRestorer.keepLoaded(info.getLevel(), chunkX + dx, chunkZ + dz);
                }
            }
        }
    }

    private static void sweep(MinecraftServer server, int restoredBlocks) {
        int entities = 0;
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (Entity entity : new ArrayList<>(TRACKED)) {
            if (!entity.isRemoved()) {
                kinds.merge(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), 1, Integer::sum);
                entity.discard();
                entities++;
            }
        }
        TRACKED.clear();
        List<String> bots = new ArrayList<>();
        for (AIPlayerEntity bot : new ArrayList<>(AIPlayerManager.INSTANCE.all())) {
            bots.add(bot.getGameProfile().name());
            AIPlayerManager.INSTANCE.despawn(server, bot.getGameProfile().name());
        }
        int mocks = MockPlayers.disconnectLeaked(server);
        if (entities > 0 || !bots.isEmpty() || mocks > 0 || restoredBlocks > 0) {
            List<String> tests = new ArrayList<>();
            for (GameTestInfo info : KNOWN) {
                tests.add(info.id().getPath());
            }
            LOG.info("swept {} leftover entities {}, {} leaked bots {} and {} leaked mock players, restored {} blocks, after {}", entities,
                    kinds, bots.size(), bots, mocks, restoredBlocks, tests.size() > 4 ? tests.subList(0, 4) + " (+" + (tests.size() - 4) + ")" : tests);
        }
    }

    /** The tests the runner is running right now (server thread only); see {@code GameTestIsolationSelfTests}. */
    static List<GameTestInfo> runningTests() {
        List<GameTestInfo> running = new ArrayList<>();
        for (GameTestInfo info : infos()) {
            if (!info.isDone()) {
                running.add(info);
            }
        }
        return running;
    }

    @SuppressWarnings("unchecked")
    private static List<GameTestInfo> infos() {
        try {
            return new ArrayList<>((Collection<GameTestInfo>) TEST_INFOS.get(GameTestTicker.SINGLETON));
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("cannot read GameTestTicker.testInfos", impossible);
        }
    }

    private static Field testInfosField() {
        try {
            Field field = GameTestTicker.class.getDeclaredField("testInfos");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException missing) {
            throw new ExceptionInInitializerError(missing);
        }
    }
}
