package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.task.GatherThenGiveTask;
import io.github.zoyluo.minecraftai.task.TaskState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The per-conversation routing state: what a fresh instruction withholds and what lifts it again. */
final class ToolRoutingTest {
    private static final Set<String> COLLECT = Set.of("give_item", "achieve_goal");

    @BeforeAll
    static void bootstrap() {
        RegistryBootstrap.ensure();
    }

    private static ToolRouting routingFor(String instruction) {
        ToolRouting routing = new ToolRouting();
        routing.beginInstruction(RequestIntent.parse(instruction));
        return routing;
    }

    @Test
    void aNewInstructionStartsFromItsOwnWording() {
        ToolRouting routing = routingFor("get 32 logs");
        assertEquals(COLLECT, routing.withheldTools());

        routing.beginInstruction(RequestIntent.parse("give me 32 logs"));
        assertEquals(Set.of(), routing.withheldTools(),
                "the next message must not inherit the previous request's restriction");

        routing.beginInstruction(RequestIntent.parse("mine some coal"));
        assertEquals(COLLECT, routing.withheldTools());
    }

    @Test
    void aNewInstructionForgetsWhatTheLastOneStartedAndFinished() {
        ToolRouting routing = routingFor("gather 32 logs");
        routing.noteTaskStarted("gather");
        routing.observeTask("gather", TaskState.COMPLETED);
        assertEquals(Set.of(), routing.withheldTools());

        routing.beginInstruction(RequestIntent.parse("get 16 coal"));
        assertEquals(COLLECT, routing.withheldTools(), "the lift belongs to the instruction chain that earned it");
        routing.observeTask("gather", TaskState.COMPLETED);
        assertEquals(COLLECT, routing.withheldTools(), "a task of the previous chain is not this chain's collection");
    }

    @Test
    void anAutonomousGoalContinuationDoesNotInheritTheLastInstructionsRestriction() {
        ToolRouting routing = routingFor("get 32 logs");
        assertEquals(COLLECT, routing.withheldTools());

        routing.beginGoalContinuation();

        assertEquals(Set.of(), routing.withheldTools());
        assertNull(routing.blockedResult("give_item"));
        assertNull(routing.blockedResult("achieve_goal"));
    }

    @Test
    void theCollectionTheChainStartedEndsTheRestrictionWhenItCompletes() {
        ToolRouting routing = routingFor("gather 32 logs, then slide them over to me");
        assertEquals(COLLECT, routing.withheldTools(), "a handoff phrase the classifier missed");
        routing.noteTaskStarted("gather");

        routing.observeTask("gather", TaskState.RUNNING);
        assertEquals(COLLECT, routing.withheldTools());
        routing.observeTask("gather", TaskState.FAILED);
        assertEquals(COLLECT, routing.withheldTools(), "a failed collection never opens the carried stock");
        routing.observeTask("idle", TaskState.COMPLETED);
        assertEquals(COLLECT, routing.withheldTools(), "the idle placeholder is not a finished collection");
        routing.observeTask("follow", TaskState.COMPLETED);
        assertEquals(COLLECT, routing.withheldTools(), "an unrelated task finishing is not this collection");

        routing.observeTask("gather", TaskState.COMPLETED);
        assertEquals(Set.of(), routing.withheldTools());
        assertNull(routing.blockedResult("give_item"));
    }

    @Test
    void aTaskCompletionBeforeAnyTaskWasStartedByTheChainIsIgnored() {
        ToolRouting routing = routingFor("get 32 logs");

        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(COLLECT, routing.withheldTools(),
                "the cached status of an earlier instruction's task must not unlock this one");
    }

    @Test
    void aCompletedGoalMissionEndsTheRestriction() {
        ToolRouting routing = routingFor("mine 10 iron and give them to me");
        assertTrue(routing.withheldTools().contains("mine_ore"));

        routing.observeGoalCompleted();

        assertEquals(Set.of(), routing.withheldTools());
    }

    @Test
    void aHandoffThatFailedAfterTheCollectionOpensGiveItemButNotAnotherCollection() {
        ToolRouting routing = routingFor("gather 32 logs and give them to me");
        routing.observeFailure("fresh_gather_failed:gather_timeout");
        assertTrue(routing.withheldTools().contains("give_item"), "the gather phase failed: nothing is carried");

        routing.observeFailure(GatherThenGiveTask.HANDOFF_FAILED_PREFIX + "give_item_player_not_found");

        assertEquals(Set.of("gather_then_give"), routing.withheldTools(),
                "the quota is carried: hand it over, do not gather a second one");
        assertNull(routing.blockedResult("give_item"));
        assertTrue(routing.blockedResult("gather_then_give").contains("give_item"));
    }

    @Test
    void aCompoundRequestUnlocksTheCraftAndHandoffStagesWhenItsCollectionCompletes() {
        ToolRouting routing = routingFor("gather 32 logs then craft a table and give it to me");
        assertEquals(Set.of("give_item", "achieve_goal", "gather_then_give"), routing.withheldTools(),
                "fulfill_items stays: it carries the raw quota and the crafted result in one call");
        String blocked = routing.blockedResult("gather_then_give");
        assertTrue(blocked.contains("fulfill_items") && blocked.contains("gather (count)"), blocked);
        assertNull(routing.blockedResult("fulfill_items"));
        routing.noteTaskStarted("gather");

        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(Set.of(), routing.withheldTools());
    }

    private static JsonObject manifest(JsonObject... allocations) {
        JsonArray items = new JsonArray();
        for (JsonObject allocation : allocations) {
            items.add(allocation);
        }
        JsonObject arguments = new JsonObject();
        arguments.add("items", items);
        return arguments;
    }

    private static JsonObject allocation(String item, int count, String recipient) {
        JsonObject allocation = new JsonObject();
        allocation.addProperty("item", item);
        allocation.addProperty("count", count);
        if (recipient != null) {
            allocation.addProperty("recipient", recipient);
        }
        return allocation;
    }

    @Test
    void aCraftedResultTheBotKeepsIsMadeAfterTheCollection() {
        ToolRouting routing = routingFor("gather 32 logs and craft a table");
        assertEquals(Set.of("give_item", "achieve_goal", "gather_then_give", "fulfill_items"), routing.withheldTools(),
                "fulfill_items counts a table the bot already carries as done, so the new quota would be dropped");
        String text = routing.blockedResult("fulfill_items");
        assertTrue(text.contains("counts a crafted item you already carry") && text.contains("gather (count)"), text);
        assertNull(routing.blockedResult("gather"));
        routing.noteTaskStarted("gather", Set.of("minecraft:oak_log"));

        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(Set.of(), routing.withheldTools());
    }

    @Test
    void aManifestForACraftedResultMustKeepTheRawQuotaInIt() {
        ToolRouting routing = routingFor("gather 32 logs then craft a table and give it to me");
        JsonObject tableOnly = manifest(allocation("minecraft:crafting_table", 1, "Steve"));

        String blocked = routing.blockedResult("fulfill_items", tableOnly);

        assertTrue(blocked != null && blocked.contains("raw") && blocked.contains("without a recipient"), blocked);
        assertNull(routing.blockedResult("fulfill_items",
                manifest(allocation("minecraft:oak_log", 32, null), allocation("minecraft:crafting_table", 1, "Steve"))),
                "the raw quota kept on the bot beside the crafted result is the expected shape");
        assertNull(routing.blockedResult("fulfill_items",
                manifest(allocation("minecraft:oak_log", 32, "Steve"), allocation("minecraft:crafting_table", 1, "Steve"))),
                "a raw quota handed over is a quota of new units as well");
        assertTrue(routing.blockedResult("fulfill_items", manifest(allocation("minecraft:crafting_table", 1, "Steve"),
                allocation("minecraft:iron_pickaxe", 1, null))) != null, "crafted items are no raw quota");
        assertTrue(routing.blockedResult("fulfill_items", new JsonObject()) != null, "no manifest at all keeps no quota");
    }

    @Test
    void theManifestIsOnlyCheckedWhileARawQuotaIsOwedBesideACraftedResult() {
        JsonObject tableOnly = manifest(allocation("minecraft:crafting_table", 1, "Steve"));
        // A plain request for a table has no raw quota to protect.
        assertNull(routingFor("make me a crafting table").blockedResult("fulfill_items", tableOnly));
        // Neither does a plain collection: fulfill_items is not withheld there.
        assertNull(routingFor("get 32 logs").blockedResult("fulfill_items", tableOnly));
        // Once the collection finished the crafted stage runs from what was collected.
        ToolRouting routing = routingFor("gather 32 logs then craft a table and give it to me");
        routing.noteTaskStarted("gather", Set.of("minecraft:oak_log"));
        routing.observeTask("gather", TaskState.COMPLETED);
        assertNull(routing.blockedResult("fulfill_items", tableOnly));
    }

    @Test
    void theLogsAndTheCoalOfOneRequestAreEachCollectedBeforeTheCarriedStockOpens() {
        ToolRouting routing = routingFor("get 32 logs and 10 coal");
        assertEquals(COLLECT, routing.withheldTools());

        routing.noteTaskStarted("gather", Set.of("minecraft:oak_log"));
        routing.observeTask("gather", TaskState.COMPLETED);
        assertEquals(COLLECT, routing.withheldTools(), "the logs are in; carried coal must still not stand in for the coal");
        assertNotNull(routing.blockedResult("give_item"));

        routing.noteGoalStarted(Set.of("minecraft:coal_ore"));
        routing.observeGoalCompleted();
        assertEquals(Set.of(), routing.withheldTools());
    }

    @Test
    void aFinishedCollectionAnswersItsResourceOnceHoweverOftenItsStatusIsRead() {
        ToolRouting routing = routingFor("get 32 logs and 10 coal");
        routing.noteTaskStarted("gather", Set.of("minecraft:oak_log"));

        routing.observeTask("gather", TaskState.COMPLETED);
        routing.observeTask("gather", TaskState.COMPLETED);
        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(COLLECT, routing.withheldTools(), "the same finished gather cannot also stand for the coal");
    }

    @Test
    void oneCollectionOfSeveralItemsAnswersEachOfThem() {
        ToolRouting routing = routingFor("get 32 logs and 10 coal and 5 iron");
        routing.noteGoalStarted(Set.of("minecraft:oak_log", "minecraft:coal", "minecraft:raw_iron"));

        routing.observeGoalCompleted();

        assertEquals(Set.of(), routing.withheldTools(), "one fulfill_items manifest can cover the whole request");
    }

    @Test
    void aCollectionThatNamesNoItemOrAnotherOneAnswersTheWholeRequest() {
        // Never leave a handoff withheld that nothing could unlock: only a collection of a known, different resource
        // keeps the others pending, and one that matches nothing the player asked for is read as the request's own.
        ToolRouting unknown = routingFor("get 32 logs and 10 coal");
        unknown.noteTaskStarted("gather");
        unknown.observeTask("gather", TaskState.COMPLETED);
        assertEquals(Set.of(), unknown.withheldTools());

        ToolRouting other = routingFor("get 32 logs and 10 coal");
        other.noteTaskStarted("gather", Set.of("minecraft:sand"));
        other.observeTask("gather", TaskState.COMPLETED);
        assertEquals(Set.of(), other.withheldTools());

        ToolRouting empty = routingFor("get 32 logs and 10 coal");
        empty.noteGoalStarted(Set.of());
        empty.observeGoalCompleted();
        assertEquals(Set.of(), empty.withheldTools());
    }

    @Test
    void aRequestThatNamesNoResourceIsAnsweredByAnyCollection() {
        ToolRouting routing = routingFor("start gathering");
        routing.noteTaskStarted("gather", Set.of("minecraft:oak_log"));

        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(Set.of(), routing.withheldTools());
    }

    @Test
    void aHandoffThatFailedAfterTheCollectionAnswersEveryResource() {
        ToolRouting routing = routingFor("get 32 logs and 10 coal and give them to me");

        routing.observeFailure(GatherThenGiveTask.HANDOFF_FAILED_PREFIX + "give_item_unreachable");

        assertEquals(Set.of("gather_then_give"), routing.withheldTools(), "the quota is carried: hand it over");
    }

    private static List<ChatToolCall> calls(ChatToolCall... calls) {
        return List.of(calls);
    }

    private static ChatToolCall call(String id, String name, String arguments) {
        return new ChatToolCall(id, name, arguments);
    }

    @Test
    void aSuccessfulCollectionToolNamesTheItemsItCollects() {
        Optional<Set<String>> started = ToolRouting.collectionStartedBy(calls(
                call("1", "gather", "{\"item\":\"minecraft:oak_log\",\"count\":32}"),
                call("2", "mine_ore", "{\"ore\":\"minecraft:coal_ore\"}"),
                call("3", "fulfill_items", "{\"items\":[{\"item\":\"minecraft:oak_log\",\"count\":32},"
                        + "{\"item\":\"minecraft:crafting_table\",\"recipient\":\"Steve\"}]}"),
                call("4", "gather_then_give", "{\"item\":\"logs\",\"count\":5}"),
                call("5", "harvest_crop", "{\"crop\":\"wheat\"}")), Set.of("1", "2", "3", "4", "5"));

        assertEquals(Optional.of(Set.of("minecraft:oak_log", "minecraft:coal_ore", "minecraft:crafting_table", "logs", "wheat")),
                started);
    }

    @Test
    void onlyTheCallsThatSucceededAndCouldCollectCount() {
        assertEquals(Optional.empty(), ToolRouting.collectionStartedBy(calls(
                call("1", "gather", "{\"item\":\"minecraft:oak_log\",\"count\":32}")), Set.of()),
                "a call that failed started nothing");
        assertEquals(Optional.empty(), ToolRouting.collectionStartedBy(calls(
                call("1", "follow", "{}"), call("2", "craft", "{\"item\":\"minecraft:oak_planks\"}"),
                call("3", "move_to", "{\"x\":1,\"y\":2,\"z\":3}"), call("4", "say", "{\"message\":\"ok\"}"),
                call("5", "inventory", "{}")), Set.of("1", "2", "3", "4", "5")),
                "a walk, a craft or a say never ends the collection a request is waiting for");
        assertEquals(Optional.of(Set.of()), ToolRouting.collectionStartedBy(calls(
                call("1", "provision_food", "{}")), Set.of("1")),
                "a collecting tool that names no item still counts as a started collection, of unknown items");
        assertEquals(Optional.of(Set.of("minecraft:oak_log")), ToolRouting.collectionStartedBy(calls(
                call("1", "gather", "{\"item\":\"minecraft:oak_log\"}"), call("2", "gather", "{\"item\":\"minecraft:sand\"}")),
                Set.of("1")));
    }

    @Test
    void aPartialHandoffCollectsFirstBecauseGatherThenGiveHandsOverEverything() {
        ToolRouting routing = routingFor("gather 32 logs and give me 16");
        assertEquals(Set.of("give_item", "achieve_goal", "gather_then_give", "fulfill_items"), routing.withheldTools());
        assertNull(routing.blockedResult("gather"), "gather with the count is the new quota");
        String text = routing.blockedResult("gather_then_give");
        assertTrue(text.contains("part") && text.contains("gather (count)") && text.contains("give_item"), text);
        assertEquals(text, routing.blockedResult("fulfill_items"));
        routing.noteTaskStarted("gather");

        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(Set.of(), routing.withheldTools(), "the part is handed over with give_item afterwards");
    }

    @Test
    void theBlockedTextsNameTheToolsThatCanServeTheRequest() {
        ToolRouting collect = routingFor("get 32 logs");
        String give = collect.blockedResult("give_item");
        assertTrue(give.contains("gather_then_give") && give.contains("fulfill_items"), give);
        assertTrue(give.startsWith("blocked:"));
        String goal = collect.blockedResult("achieve_goal");
        assertTrue(goal.contains("fulfill_items") && goal.contains("gather"), goal);
        assertNull(collect.blockedResult("gather"));
        assertNull(collect.blockedResult("gather_then_give"));

        ToolRouting handoff = routingFor("gather 32 logs and give them to me");
        String split = handoff.blockedResult("gather");
        assertTrue(split.contains("gather_then_give") && split.contains("fulfill_items"), split);
        assertNull(handoff.blockedResult("gather_then_give"));
        assertNull(handoff.blockedResult("fulfill_items"));
    }

    @Test
    void withholdingOnlyAppliesWhileThereIsSomethingToCollect() {
        for (String instruction : new String[] {"give me 32 logs", "hello", "make an iron pickaxe", ""}) {
            ToolRouting routing = routingFor(instruction);
            assertEquals(Set.of(), routing.withheldTools(), instruction);
            assertFalse(routing.withheldTools().contains("give_item"));
        }
    }
}
