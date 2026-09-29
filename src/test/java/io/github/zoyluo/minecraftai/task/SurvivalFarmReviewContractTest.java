package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.action.MilkCowAction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Locks the independent-review fixes for farming and survival: cheap-first survey, expiring failed cells,
 * a bounded bone-meal craft, real-attempt counting in IrrigateTask, the fire reflex's water recovery on every
 * exit, the side-effect-free food predicate and the critical-fight preemption of the rescue reflexes.
 */
final class SurvivalFarmReviewContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void failedFarmCellsExpireInsteadOfBeingPermanent() throws IOException {
        assertFalse(FarmTask.failureExpired(100, 100 + FarmTask.FAILED_CELL_TTL_TICKS - 1));
        assertTrue(FarmTask.failureExpired(100, 100 + FarmTask.FAILED_CELL_TTL_TICKS));
        String farm = read("task/FarmTask.java");
        assertTrue(farm.contains("private final Map<BlockPos, Integer> failedCells"));
        assertFalse(farm.contains("failedCells.add("), "failures are recorded with their tick (markFailed)");
    }

    @Test
    void surveyJudgesBlockStateBeforeItRaysTheOutline() throws IOException {
        String farm = read("task/FarmTask.java");
        int survey = farm.indexOf("private void survey(");
        String body = farm.substring(survey, farm.indexOf("private Kind classify(", survey));
        assertTrue(body.indexOf("classify(world, pos, hasSeeds)") > 0);
        assertTrue(body.indexOf("classify(world, pos, hasSeeds)") < body.indexOf("canObserveFarmCell"),
                "the cheap block-state verdict must run before the outline ray tests");
    }

    @Test
    void boneMealIsCraftedFromCarriedBonesThroughTheCraftingPath() throws IOException {
        String farm = read("task/FarmTask.java");
        assertTrue(farm.contains("new CraftTask(Items.BONE_MEAL,"));
        assertTrue(farm.contains("Items.BONE"), "the craft only starts from carried bones");
        assertTrue(farm.contains("boneMealCraftFailed"), "a failed craft is not retried forever");
        assertTrue(farm.contains("BONE_CRAFT_MAX_BONES"), "the craft is bounded");
        String recipes = read("craft/RecipeRegistry.java");
        assertTrue(recipes.contains("new Recipe(Items.BONE_MEAL, 3, List.of(new Ingredient(List.of(Items.BONE), 1)), false)"),
                "1 bone -> 3 bone meal, no crafting table");
    }

    @Test
    void irrigateCountsRealAttemptsNotEveryTickOfAWalk() throws IOException {
        String irrigate = read("task/IrrigateTask.java");
        assertTrue(irrigate.contains("MAX_REPOSITIONS"));
        assertFalse(irrigate.contains("!positional || ++placeAttempts"),
                "the per-tick positional counter is gone");
        int place = irrigate.indexOf("private void place(");
        String body = irrigate.substring(place);
        assertTrue(body.indexOf("isPathExecutorIdle()") < body.indexOf("placeRepositions++"),
                "a walk in progress returns before anything is counted");
        assertTrue(body.indexOf("startPathTo(stand).isFailed()") > 0
                        && body.indexOf("startPathTo(stand).isFailed()") < body.indexOf("placeRepositions++"),
                "a reposition is counted only once the path executor actually started a walk");
    }

    @Test
    void fireReflexRecoversItsWaterOnEveryExit() throws IOException {
        String fire = read("task/FireExtinguishTask.java");
        assertTrue(fire.contains("private void recoverPlacedWater("));
        assertTrue(fire.contains("fire_extinguish_water_left"));
        int abort = fire.indexOf("protected void onAbort(");
        assertTrue(fire.substring(abort, fire.indexOf("@Override", abort + 10)).contains("recoverPlacedWater(bot, \"aborted\")"));
        assertTrue(fire.contains("recoverPlacedWater(bot, \"timeout\")"));
        assertTrue(fire.contains("recoverPlacedWater(bot, \"no_means\")"));
        assertTrue(fire.contains("recoverPlacedWater(bot, \"in_lava\")"));
    }

    @Test
    void foodPredicateHasNoSideEffectAndCallersUseIt() throws IOException {
        String inventory = read("action/InventoryAction.java");
        int hasFood = inventory.indexOf("public static boolean hasFood(");
        String body = inventory.substring(hasFood, inventory.indexOf("}", hasFood));
        assertTrue(body.contains("pickFood(player, false) != null"));
        assertFalse(body.contains("promote"), "the predicate must not promote the offhand stack");
        assertTrue(inventory.contains("Health dependent by design"), "the health dependence is documented");
        for (String file : new String[] {"task/CombatTask.java", "task/DangerWatcher.java", "task/EatTask.java",
                "task/EmergencyShelterTask.java", "task/ResupplyTask.java", "task/ShelterCleanupTask.java"}) {
            assertFalse(read(file).contains("findFoodSlot"), file + " asks a yes/no question and must use hasFood");
        }
        assertTrue(read("action/EatAction.java").contains("findFoodSlot"), "promotion happens only inside EatAction");
    }

    @Test
    void aCriticalFightPreemptsAndPausesTheRescueReflexes() throws IOException {
        String watcher = read("task/DangerWatcher.java");
        assertTrue(watcher.contains("static boolean shouldFightBeforeRescue("));
        int rescue = watcher.indexOf("private boolean maybeSelfRescue(");
        String body = watcher.substring(rescue, watcher.indexOf("private static void startSelfRescue(", rescue));
        assertTrue(body.indexOf("shouldFightBeforeRescue") < body.indexOf("instanceof FireExtinguishTask"),
                "the fight check runs before a running rescue claims the scan");
        int preserve = watcher.indexOf("private static boolean shouldPreserveActiveWork(");
        String preserveBody = watcher.substring(preserve, watcher.indexOf("/**", preserve));
        assertTrue(preserveBody.contains("FireExtinguishTask") && preserveBody.contains("PowderSnowEscapeTask"),
                "a preempted rescue is paused (resumable), not replaced");
        assertTrue(watcher.contains("MAX_PAUSED_FRAMES_FOR_RESCUE"), "pausing safety work is bounded");
    }

    @Test
    void milkingFailsTypedBeyondReach() throws IOException {
        String milk = read("action/MilkCowAction.java");
        assertTrue(milk.contains("\"cow_out_of_reach\""));
        assertTrue(milk.indexOf("cow_out_of_reach") < milk.indexOf("InteractAction.useItemOnEntity"));
        assertEquals(4.0D, MilkCowAction.REACH, 0.0D,
                "the search radius is wider than vanilla's entity interaction range, so the reach proof matters");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
