package io.github.zoyluo.minecraftai.gametest;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Self-tests of the isolation every other GameTest relies on: a test runs alone ({@link GameTestIsolation}), starts in the suite's
 * ambient clock, weather and game rules whatever the test before it did ({@link GameTestSweeper}), and sees the light of a block
 * change by the next tick ({@link GameTestLightSync}). They use the default test environment on purpose: that is the environment
 * vanilla would batch 50 tests into.
 */
public final class GameTestIsolationSelfTests {
    /** The sky light of a cell open to the sky. */
    private static final int FULL_SKY_LIGHT = 15;
    /** Vanilla's default, which the suite does not change. */
    private static final int VANILLA_RANDOM_TICK_SPEED = 3;

    @GameTest(maxTicks = 5)
    public void defaultEnvironmentTestRunsAlone(GameTestHelper context) {
        GameTestInfo self = GameTestCleanup.infoOf(context);
        List<GameTestInfo> running = GameTestSweeper.runningTests();
        require(context, running.size() == 1 && running.get(0) == self,
                "another test runs at the same time as this one: " + running.stream().map(info -> info.id().getPath()).toList());
        context.succeed();
    }

    /**
     * Two identical scenarios: each checks that it starts in the ambient and then leaves night, rain, a running clock and no random
     * ticks behind, so whichever of the two runs second proves that the first one's changes did not reach it (and so does every test
     * that runs after either of them).
     */
    @GameTest(maxTicks = 5)
    public void firstAmbientScenarioStartsInTheSuiteAmbientAndChangesIt(GameTestHelper context) {
        ambientScenario(context);
    }

    @GameTest(maxTicks = 5)
    public void secondAmbientScenarioStartsInTheSuiteAmbientAndChangesIt(GameTestHelper context) {
        ambientScenario(context);
    }

    private static void ambientScenario(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        GameRules rules = world.getGameRules();
        require(context, world.getDayTime() == GameTestSweeper.AMBIENT_DAY_TIME,
                "the test started at day time " + world.getDayTime() + ", not the ambient " + GameTestSweeper.AMBIENT_DAY_TIME);
        require(context, !world.isRaining() && !world.isThundering() && world.getRainLevel(1.0F) == 0.0F,
                "the test started in rain or thunder");
        require(context, !rules.get(GameRules.ADVANCE_TIME) && !rules.get(GameRules.ADVANCE_WEATHER),
                "the test started with the clock or the weather advancing");
        require(context, rules.get(GameRules.RANDOM_TICK_SPEED) == VANILLA_RANDOM_TICK_SPEED,
                "the test started with random tick speed " + rules.get(GameRules.RANDOM_TICK_SPEED));
        world.setDayTime(18000L);
        world.setWeatherParameters(0, 6000, true, true);
        world.setRainLevel(1.0F);
        world.setThunderLevel(1.0F);
        rules.set(GameRules.ADVANCE_TIME, true, world.getServer());
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, world.getServer());
        context.succeed();
    }

    /**
     * A roof put over an open cell darkens it by the next tick, and taking the roof away lights it again by the tick after: the light
     * engine runs on its own thread, and the GameTest server does not wait between ticks for it.
     */
    @GameTest(maxTicks = 20)
    public void lightOfBlockChangeIsPublishedByTheNextTick(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos cell = context.absolutePos(new BlockPos(2, 60, 2));
        require(context, world.canSeeSky(cell), "fixture: the open cell does not see the sky");
        world.setBlock(cell.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        long[] changedAt = {world.getGameTime()};
        boolean[] roofed = {true};
        context.onEachTick(() -> {
            if (world.getGameTime() == changedAt[0]) {
                return;
            }
            long ticksLater = world.getGameTime() - changedAt[0];
            if (roofed[0]) {
                require(context, world.getBrightness(LightLayer.SKY, cell) < FULL_SKY_LIGHT,
                        "the roof's shadow was not published " + ticksLater + " tick(s) later: sky light "
                                + world.getBrightness(LightLayer.SKY, cell));
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                changedAt[0] = world.getGameTime();
                roofed[0] = false;
            } else {
                require(context, world.canSeeSky(cell),
                        "the open sky was not published " + ticksLater + " tick(s) later: sky light " + world.getBrightness(LightLayer.SKY, cell));
                context.succeed();
            }
        });
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }
}
