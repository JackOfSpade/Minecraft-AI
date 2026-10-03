package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the bounded, player-visible contract of the persistent find task. */
final class DiscoveryTaskSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void resolverSupportsSemanticAliasesAndAnyLiveRegistryBlock() throws IOException {
        // Standard JUnit intentionally has no bootstrapped Minecraft registry. Runtime registry
        // resolution is covered by the normal server/GameTest environment, so keep this contract
        // test source-only rather than depending on test execution order or static bootstrap.
        String task = Files.readString(MAIN.resolve("task/DiscoveryTask.java"));

        assertTrue(task.contains("case \"iron\", \"iron_ore\", \"raw_iron\"")
                        && task.contains("\"minecraft:iron_ore\"")
                        && task.contains("case \"wheat\", \"mature_wheat\"")
                        && task.contains("\"minecraft:wheat\"")
                        && task.contains("case \"plant\", \"plants\" -> PLANTS")
                        && task.contains("case \"bonus_chest\", \"container\"")
                        && task.contains("case \"sheep\", \"sheeps\", \"minecraft:sheep\""),
                "the established resource/entity/container vocabulary must remain available");
        assertTrue(task.contains("BuiltInRegistries.BLOCK.getOptional(id)")
                        && task.contains("identifierCandidates(normalized)")
                        && task.contains("addSingularIdentifierCandidate")
                        && task.contains("normalized.replace('-', '_')")
                        && task.contains("!block.defaultBlockState().isAir()"),
                "find must resolve any non-air registered block from an id, spaced name, hyphen form, or plural");
        assertTrue(task.contains("block.defaultBlockState().getFluidState().isEmpty()")
                        && task.contains("BlockObservation.OUTLINE_OR_CELL")
                        && task.contains("find_target_is_item"),
                "crops and visible fluids need the outline/cell observation path, while loose items remain distinct");
    }

    @Test
    void discoveryIsFiniteObservableAndDoesNotChangeTheWorld() throws IOException {
        String task = Files.readString(MAIN.resolve("task/DiscoveryTask.java"));

        assertTrue(task.contains("MAX_SEARCH_TICKS = 3_600")
                        && task.contains("HOP_DISTANCE = 12")
                        && task.contains("MAX_HOPS = 16"),
                "find must have a finite time, leg, and area budget");
        assertTrue(task.contains("searchStartedTick = bot.level().getServer().getTickCount()")
                        && task.contains("latestSearchAgeTicks = Math.max(0, now - searchStartedTick)")
                        && task.contains("if (latestSearchAgeTicks >= MAX_SEARCH_TICKS)"),
                "the three-minute cap must be measured in server time, not stretched by non-critical task throttling");
        assertTrue(task.contains("horizontalDistanceSquared(bot.blockPosition(), anchor)")
                        && task.contains("withinSearchDisc(candidate)")
                        && task.contains("finishNotFound(bot, \"search_boundary\")"),
                "a route or reported target that crosses the search disc must not turn the request into open-ended exploration");
        assertTrue(task.contains("double length = Math.hypot(direction[0], direction[1])")
                        && task.contains("int component = (int) Math.floor(distance / length)"),
                "diagonal headings must remain inside the declared circular search radius");
        assertTrue(task.contains("BuiltInRegistries.BLOCK.getOptional(id)")
                        && task.contains("BuiltInRegistries.ITEM.getOptional(id)")
                        && task.contains("Identifier.tryParse(candidate)")
                        && task.contains("Target.block(block.get())")
                        && task.contains("any registered non-air block id/name")
                        && task.contains("OreProspector.beginObservable(")
                        && task.contains("OreProspector.beginObservableFarmCells(")
                        && task.contains("ObservableWorldQuery.canObserveBlock")
                        && task.contains("ObservableWorldQuery.canObserveFarmCell"),
                "registered block matches must be surveyed without hidden-world capability and visibly re-proven");
        assertTrue(task.contains("ObservableWorldQuery.canObserveEntityWithin")
                        && task.contains("private Optional<BlockPos> surveyVisibleContainer")
                        && task.contains("now < nextSurveyTick")
                        && task.contains("nextSurveyTick = now + SURVEY_INTERVAL_TICKS"),
                "entity and container surveys must be observable, throttled, and able to advance");
        assertTrue(task.contains("startDirectionalPursuitTo(\n                heading, HOP_DISTANCE, false, false)"),
                "search legs are observed-only and never receive break permission");
        assertTrue(task.contains("BrainCoordinator.INSTANCE.sendBotReply")
                        && task.contains("fail(\"not_found:"),
                "the player and follow-up conversation receive a concrete result");
        assertFalse(task.contains("sendPanelChat") || task.contains("MiningAction")
                        || task.contains("StrikeAction") || task.contains("ContainerAction.open"),
                "finding must not hide its result in the panel or mutate the world");
    }

    @Test
    void modelToolsRouteFindAndTheLegacyContainerTaskAliasToTheBoundedSearch() throws IOException {
        String tools = Files.readString(MAIN.resolve("brain/ToolRegistry.java"));
        String brain = Files.readString(MAIN.resolve("brain/BrainCoordinator.java"));

        assertTrue(tools.contains("register(\"find\"")
                        && tools.contains("any registered non-air block")
                        && tools.contains("DiscoveryTask.find(requiredString(args, \"target\"), optionalInt(args, \"radius\", 64))"),
                "the model must have a first-class persistent finder for registered blocks");
        assertTrue(tools.contains("case \"find\" -> DiscoveryTask.find")
                        && tools.contains("case \"find_container\" -> DiscoveryTask.find"),
                "the prior assign_task(find_container) spelling must no longer leave the bot idle");
        assertTrue(brain.contains("For \"find/search iron ore\"")
                        && brain.contains("every registered non-air block")
                        && brain.contains("For \"find and mine iron\", first call find")
                        && brain.contains("then call mine_ore only after it reports visible ore"),
                "the planner must connect locate requests to mining without tunnelling toward unseen ore");
    }
}
