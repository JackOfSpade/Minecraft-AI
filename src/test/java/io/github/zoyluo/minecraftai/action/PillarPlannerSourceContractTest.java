package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Every pillar of the gatherer, the miner and the ore digger is planned by the one planner of {@link HarvestCore}: it
 * knows the floors, the columns, the sightline and the reach, and an ore digger narrows it with a filter on the cell the
 * pillar ends on instead of planning a pillar of its own.
 */
final class PillarPlannerSourceContractTest {
    private static final Path HARVEST_CORE = Path.of("src/main/java/io/github/zoyluo/minecraftai/action/HarvestCore.java");

    @Test
    void thereIsOneLoopOverTheLevelsOfAColumnAndTheGoalFilterIsAppliedInIt() throws IOException {
        String source = Files.readString(HARVEST_CORE);
        assertEquals(1, count(source, "for (int goalY"),
                "one planner picks the level a pillar ends on; a second loop would be a second pillar mechanism");
        assertFalse(source.contains("PILLAR_MAX_BASE_SLOPE") || source.contains("pillarBaseFor("),
                "the floors of other heights are the planner's (pillarApproachesOnOtherFloors), not a loop of their own");

        String column = body(source, "private static PillarApproach columnApproach(");
        assertTrue(column.indexOf("!canReachFromPillarGoal(bot, target, goal) || !goalFilter.test(goal)") > 0
                        && column.indexOf("goalFilter.test(goal)") < column.indexOf("isObservedClearPillarColumn("),
                "a goal the caller cannot work from is passed over before its column is proved: the next level up is tried");
    }

    @Test
    void theGoalFilterReachesEveryEntryOfThePlanner() throws IOException {
        String source = Files.readString(HARVEST_CORE);
        assertTrue(source.contains("public static PillarApproach pillarApproachFor(AIPlayerEntity bot, BlockPos target,\n"
                        + "                                                    Set<Block> targetBlocks, Predicate<BlockPos> goalFilter)"),
                "the ore digger asks for a pillar that ends on a pose it mines from");
        assertTrue(body(source, "private static PillarApproach pillarApproachFromFloor(").contains("columnApproaches(bot, target, goalFilter,"),
                "the floor of the bot's own level plans with the caller's filter");
        assertTrue(body(source, "private static List<PillarApproach> columnApproaches(").contains("columnApproach(bot, target, base, own, goalFilter)"),
                "every column of the ring plans with it");
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String body(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, () -> "missing method: " + signature);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new AssertionError("unterminated method: " + signature);
    }
}
