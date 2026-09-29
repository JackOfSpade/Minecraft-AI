package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * give_item is the fix for a live bug: none of ToolRegistry's ~59 tools could hand an item to a
 * player (deposit/withdraw only move items in/out of containers, trade only works with
 * villagers), so a model asked to give the player an item stalled on repeated say(purpose=plan)
 * calls until model_call_budget_exhausted. ToolRegistry.registerDefaults() eagerly touches
 * Item/Block registry constants, so a plain JUnit test cannot construct {@code new ToolRegistry()}
 * (see ToolRegistryAssignTaskNullParamsTest) -- this pins the registration as a source-text
 * contract instead: give_item must exist, require its "item" argument (so a missing item is a
 * clear bad_arg, not a silent no-op or NPE), and default count/player like the other item tools.
 */
final class ToolRegistryGiveItemRegistrationTest {
    @Test
    void giveItemIsRegisteredWithRequiredItemArgumentAndDefaultedCountAndPlayer() throws IOException {
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));

        Matcher registration = Pattern.compile(
                "register\\(\"give_item\".*?\\}\\);", Pattern.DOTALL).matcher(registry);
        assertTrue(registration.find(), "expected a give_item registration in ToolRegistry.java");
        String block = registration.group();

        assertTrue(block.contains(".required(\"item\")"),
                "give_item must require its item argument (a missing item should be a clear bad_arg)");
        assertTrue(block.contains("requiredItem(args, \"item\")"),
                "give_item must resolve item through requiredItem (rejects unknown/missing item ids)");
        assertTrue(block.contains("optionalInt(args, \"count\", 1)"),
                "give_item's count should default to 1, like withdraw's");
        assertTrue(block.contains("optionalString(args, \"player\", \"\")"),
                "give_item's player should default to blank (GiveItemTask/FollowTargetResolver then resolve the owner)");
        assertTrue(block.contains("new GiveItemTask("),
                "give_item should assign a GiveItemTask");
    }

    @Test
    void giveItemIsClassifiedAsAGenuineActionAndWorkStartTool() throws IOException {
        String coordinator = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/BrainCoordinator.java"));

        Matcher genuineAction = Pattern.compile(
                "GENUINE_ACTION_TOOLS\\s*=\\s*Set\\.of\\(([^;]*)\\);", Pattern.DOTALL).matcher(coordinator);
        assertTrue(genuineAction.find(), "expected to find GENUINE_ACTION_TOOLS in BrainCoordinator.java");
        assertTrue(genuineAction.group(1).contains("\"give_item\""),
                "give_item must be a genuine action tool, or an LLM response giving an item would "
                        + "never satisfy the initial-action gate");

        Matcher workStart = Pattern.compile(
                "WORK_START_TOOLS\\s*=\\s*Set\\.of\\(([^;]*)\\);", Pattern.DOTALL).matcher(coordinator);
        assertTrue(workStart.find(), "expected to find WORK_START_TOOLS in BrainCoordinator.java");
        assertTrue(workStart.group(1).contains("\"give_item\""),
                "give_item must count as starting requested work");
    }
}
