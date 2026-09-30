package io.github.zoyluo.minecraftai.gametest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;

/**
 * Every GameTest of this suite runs alone in the world.
 *
 * <p>Vanilla runs the tests that share a test environment together, up to 50 at once, in the one shared test world, with their
 * structures laid out in a grid 13 blocks apart (the empty Fabric structure is 8 blocks wide, plus 5). About 400 of this suite's
 * tests use the default environment, and their scenes are nowhere near that small: a follow field, a pond, a cave-roof corridor
 * 24 blocks long, a lake with a 10-block radius. Scenes of neighbouring tests therefore overlap, and whether they do depends on which
 * tests happen to share a batch and a grid row, which changes whenever a test is added. The full-suite failures this caused could
 * not be seen from inside a test: two followers walked into water that a bankless lake of a goal-snap test in the next grid row had
 * let spill down onto their banks; a descent left its corridor onto a neighbour's scene and met stone it had no tool for. The same goes for everything that is
 * JVM-wide or world-wide and that a scenario changes for its own premise (the clock, the weather, a configuration, a test hook).</p>
 *
 * <p>So the batches are split into batches of one test ({@code GameTestBatchFactoryOneTestPerBatchMixin}). The runner starts a
 * batch only when the previous one has finished, and {@link GameTestSweeper} and {@link GameTestWorldRestorer} clean up after every
 * test before the next one starts, so each test finds the pristine world, the ambient day and the suite's own configuration. The
 * tests that already had an environment of their own (more than half of them) always ran this way.</p>
 */
public final class GameTestIsolation {
    private GameTestIsolation() {
    }

    /** The same tests in the same order, one per batch, each numbered within its environment. */
    public static List<GameTestBatch> oneTestPerBatch(List<GameTestBatch> batches) {
        List<GameTestBatch> single = new ArrayList<>();
        Map<Holder<TestEnvironmentDefinition>, Integer> next = new HashMap<>();
        for (GameTestBatch batch : batches) {
            for (GameTestInfo info : batch.gameTestInfos()) {
                int index = next.merge(batch.environment(), 1, Integer::sum) - 1;
                single.add(new GameTestBatch(index, List.of(info), batch.environment()));
            }
        }
        return single;
    }
}
