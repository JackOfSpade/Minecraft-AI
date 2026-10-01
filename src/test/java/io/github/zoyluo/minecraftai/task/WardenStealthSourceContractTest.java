package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the structure of R3/R4 (wardens are never fought, a calm one is crept away from, a retreat has the follower's escort) that a
 * GameTest would only notice by luck: the three commanded-attack doors refuse a warden before building a combat task, the flee gait is a
 * lease (not the sprint flag) requested after a route starts and renewed every tick, the 400-tick evade budget is scaled while creeping,
 * and the retreat escort runs after the movement decisions.
 */
final class WardenStealthSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void everyCommandedAttackDoorRefusesAWardenBeforeBuildingACombatTask() throws IOException {
        String tools = read("brain/ToolRegistry.java");
        int attackTool = tools.indexOf("register(\"attack\"");
        int refuse = tools.indexOf("WardenRefusal.refuses(attackType)", attackTool);
        int build = tools.indexOf("new CombatTask(", attackTool);
        assertTrue(attackTool >= 0 && refuse > attackTool && build > refuse, "the attack tool refuses a warden before it builds the task");
        int attackDescriptionEnd = tools.indexOf("objectSchema()", attackTool);
        assertTrue(attackDescriptionEnd > attackTool
                        && tools.substring(attackTool, attackDescriptionEnd).contains("never fights a warden"),
                "the attack tool tells the planner that wardens are never a valid combat target");
        int assign = tools.indexOf("register(\"assign_task\"");
        int refuseTask = tools.indexOf("WardenRefusal.refuses(requiredEntityType(params, \"entity_type\"))", assign);
        int create = tools.indexOf("Task task = createTask(bot, taskType, params);", assign);
        assertTrue(assign >= 0 && refuseTask > assign && create > refuseTask, "assign_task attack refuses a warden before createTask");
        String command = read("command/MinecraftAiTaskSubcommand.java");
        int door = command.indexOf("private static int assignAttack(");
        int refuseCommand = command.indexOf("WardenRefusal.refuses(type)", door);
        int buildCommand = command.indexOf("new CombatTask(", door);
        assertTrue(door >= 0 && refuseCommand > door && buildCommand > refuseCommand, "the attack command refuses a warden first");
        assertTrue(read("task/WardenRefusal.java").contains("I won't fight a warden. I'll sneak away from it instead."));
    }

    @Test
    void theFleeGaitIsALeaseRenewedEveryTickAndRequestedAfterTheRouteStarts() throws IOException {
        String evade = read("task/EvadeTask.java");
        int admit = evade.indexOf("static BlockPos admitBestSurfaceEscapePath(");
        int start = evade.indexOf("startSurfacePathTo(candidate)", admit);
        int lease = evade.indexOf("requestFleeRoutePace(bot, source);", start);
        assertTrue(admit >= 0 && start > admit && lease > start, "the route lease is requested after the route has started");
        assertTrue(evade.contains("bot.getActionPack().requestPace(gait, owner)"), "a tick lease is renewed every tick");
        assertTrue(evade.contains("import io.github.zoyluo.minecraftai.action.PacePolicy;"));
        assertFalse(evade.contains("io.github.zoyluo.minecraftai.action.PacePolicy.DAMAGE_WINDOW_TICKS"),
                "EvadeTask imports the shared pace policy rather than hiding its dependency inline");
        assertTrue(evade.contains("WardenState.isCalm(warden, QuietZone.victimsOf(bot)"), "calm is judged by WardenState, never getTarget");
        assertFalse(evade.contains("getTarget()") || evade.contains("getEntityAngryAt"), "hidden warden state is never read");
        assertTrue(evade.contains("private static final int BUDGET_TICKS = 400;") && evade.contains("SNEAK_BUDGET_FACTOR = 4"),
                "the 400-tick evade budget is 1600 ticks while creeping");
    }

    @Test
    void theRetreatEscortRunsAfterTheMovementDecisions() throws IOException {
        String evade = read("task/EvadeTask.java");
        int tick = evade.indexOf("protected void onTick(");
        int flee = evade.indexOf("flee(bot);", tick);
        int escort = evade.indexOf("escort.tick(bot, null);", tick);
        assertTrue(tick >= 0 && flee > tick && escort > flee, "flee, then escort.tick: a swing never decides a movement");
        assertTrue(evade.contains("startRunAwayFrom("), "the Baritone engine flees with its own run-away goal");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
