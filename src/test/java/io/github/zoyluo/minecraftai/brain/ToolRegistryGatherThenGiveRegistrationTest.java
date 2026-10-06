package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * gather_then_give as the model sees it. The registry is built against the real item registry, so
 * these run the actual registration instead of reading the source.
 */
final class ToolRegistryGatherThenGiveRegistrationTest {
    private static ToolRegistry registry;

    @BeforeAll
    static void registry() {
        RegistryBootstrap.ensure();
        registry = new ToolRegistry();
    }

    private static List<String> requiredArguments(String tool) {
        JsonArray required = registry.get(tool).orElseThrow().parametersSchema().getAsJsonArray("required");
        List<String> names = new ArrayList<>();
        required.forEach(element -> names.add(element.getAsString()));
        return names;
    }

    @Test
    void gatherThenGiveIsOfferedWithOnlyItsItemRequired() {
        ToolDefinition tool = registry.tools(null, false, false, false).stream()
                .filter(candidate -> candidate.name().equals("gather_then_give")).findFirst().orElseThrow();

        JsonObject properties = tool.parametersSchema().getAsJsonObject("properties");
        assertEquals(List.of("item", "count", "player"), List.copyOf(properties.keySet()));
        assertEquals(List.of("item"), requiredArguments("gather_then_give"),
                "a number the player never stated must not have to be invented: without count it collects for the "
                        + "ten-minute window");
        assertEquals(1, properties.getAsJsonObject("count").get("minimum").getAsInt(),
                "a quota of zero or less is not a quota");
        assertTrue(tool.description().contains("omit count"), "the description says when the count is left out");
    }

    @Test
    void theLogsSentinelIsTheOnlyNonRegistrySpelling() {
        assertTrue(ToolRegistry.isGenericLogHandoff("logs"));
        assertTrue(ToolRegistry.isGenericLogHandoff(" Logs "));
        assertTrue(ToolRegistry.isGenericLogHandoff("minecraft:logs"));
        assertFalse(ToolRegistry.isGenericLogHandoff("log"));
        assertFalse(ToolRegistry.isGenericLogHandoff("minecraft:oak_log"));
        assertFalse(ToolRegistry.isGenericLogHandoff("oak_logs"));
        assertFalse(ToolRegistry.isGenericLogHandoff(null));
    }

    @Test
    void theToolsTheRoutingNamesAreRegisteredCoreTools() {
        for (String name : List.of(ToolRouting.GIVE_ITEM, ToolRouting.ACHIEVE_GOAL,
                ToolRouting.GATHER_THEN_GIVE, ToolRouting.FULFILL_ITEMS)) {
            ToolDefinition tool = registry.get(name).orElseThrow(() -> new AssertionError(name + " is not registered"));
            assertEquals(ToolDefinition.Group.CORE, tool.group(), name);
        }
    }

    @Test
    void giveItemAndTheCollectionToolsPointAtEachOther() {
        String give = registry.get("give_item").orElseThrow().description();
        String gatherThenGive = registry.get("gather_then_give").orElseThrow().description();
        String fulfill = registry.get("fulfill_items").orElseThrow().description();

        assertTrue(give.contains("already carries") && give.contains("gather_then_give") && give.contains("fulfill_items"),
                "give_item must say it never collects and where collection lives");
        assertTrue(gatherThenGive.contains("fulfill_items") && gatherThenGive.contains("give_item"),
                "gather_then_give must name the routes for ore, crafted results and a missing number");
        assertTrue(gatherThenGive.contains("give_item the part"),
                "gather_then_give hands over everything it collects; a partial handoff goes through give_item");
        assertTrue(fulfill.contains("give_item") && fulfill.contains("once per item"),
                "fulfill_items is production; a carried bundle is handed over item by item");
    }
}
