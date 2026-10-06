package io.github.zoyluo.minecraftai.mining;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observable searches test the cheap conjuncts first (palette prefilter, state match) and cast the
 * observation rays last. The result is the same set (matching AND observable); only the ray count drops.
 */
class ObservableScanOrderSourceTest {
    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/" + relative));
    }

    @Test
    void prospectorObservableScanPrefiltersThenMatchesThenRays() throws IOException {
        String source = read("mining/OreProspector.java");
        int step = source.indexOf("private void stepObservable");
        int end = source.indexOf("private void initRaw", step);
        String body = source.substring(step, end);
        int section = body.indexOf("candidateSection(");
        int match = body.indexOf("match.test(");
        int ray = body.indexOf("ObservableWorldQuery.canObserveBlock(bot, pos)");
        int accept = body.indexOf("best = pos.immutable()");
        assertTrue(section > 0 && section < match && match < ray && ray < accept,
                "prefilter -> state match -> ray -> accept");
        assertFalse(body.contains("world.getBlockState("), "the state comes from the prefiltered section");
    }

    @Test
    void workshopLocatorNoLongerRaysEveryCellBeforeTestingItsBlock() throws IOException {
        String source = read("task/WorkshopLocator.java");
        assertFalse(source.contains(".filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))"),
                "no stream that ray-checks every cell first");
        int helper = source.indexOf("observableMatches(\n            AIPlayerEntity bot, BlockPos min");
        assertTrue(helper > 0);
        String body = source.substring(helper);
        assertTrue(body.indexOf("candidateSection(") < body.indexOf("matches.test(")
                && body.indexOf("matches.test(") < body.indexOf("ObservableWorldQuery.canObserveBlock(bot, cell)"));
        assertEquals(1, source.split("ObservableWorldQuery\\.canObserveBlock\\(", -1).length - 1,
                "a single ray gate remains, after the state match");
    }

    @Test
    void harvestSurveyAndPillarScansPrefilterThenMatchThenRay() throws IOException {
        String source = read("action/HarvestCore.java");
        String survey = source.substring(source.indexOf("private void enumerate(long deadline)"),
                source.indexOf("private void verify(long deadline)"));
        String pillar = source.substring(source.indexOf("public static final class PillarApproachScan"),
                source.indexOf("public static PillarApproach pillarApproachFor("));
        for (String scan : new String[] {survey, pillar}) {
            int section = scan.indexOf("candidateSection(");
            int match = scan.indexOf("targetBlocks.contains(SectionPrefilter.stateIn(");
            int ray = scan.indexOf("canObserveHarvestTarget(bot, cursor,");
            assertTrue(section > 0 && section < match && match < ray,
                    "prefilter -> state match -> ray: a survey of ground without a tree must not cast a ray per cell");
            assertFalse(scan.contains("getBlockState("), "the state comes from the prefiltered section");
        }
    }
}
