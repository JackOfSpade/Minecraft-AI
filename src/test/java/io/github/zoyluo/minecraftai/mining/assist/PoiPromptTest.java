package io.github.zoyluo.minecraftai.mining.assist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.brain.ChatResponse;
import io.github.zoyluo.minecraftai.brain.ChatToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design 6.6's R4 confirmation protocol: the fixed tool schema, the user payload's id/number validity, and
 * {@link PoiPrompt#validate}'s rejection/consistency rules. */
class PoiPromptTest {
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9_.\\-/]+(:[a-z0-9_.\\-/]+)?$");

    private static PoiPrompt.PayloadInput minimalInput() {
        return new PoiPrompt.PayloadInput(
                "minecraft:overworld", -38, "OreDigTask", "minecraft:overworld/deep_dark",
                0.62D, 0.41D, 0.55D, "structure",
                List.of(new PoiPrompt.EvidenceItem("rail", 3), new PoiPrompt.EvidenceItem("web", 4)),
                List.of(new PoiPrompt.EntityItem("observed_entity", 1)),
                0.31D, 18.0D, 0.31D, 9.4D,
                List.of(new PoiPrompt.PriorPoi("dungeon", 88.0D, "stop")));
    }

    private static ChatResponse toolCallResponse(String name, JsonObject args) {
        return new ChatResponse(null, List.of(new ChatToolCall("call_1", name, args.toString())),
                "tool_calls", 100, 20, 0);
    }

    private static JsonObject validArgs(String decision, String label, String confidence, String why) {
        JsonObject args = new JsonObject();
        args.addProperty("decision", decision);
        args.addProperty("label", label);
        args.addProperty("confidence", confidence);
        args.addProperty("why", why);
        return args;
    }

    // ---- payload: ids and numbers -----------------------------------------------------------------

    @Test
    void everyIdInTheDefaultPayloadIsRegexValid() {
        String json = PoiPrompt.userPayload(minimalInput());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(ID_PATTERN.matcher(root.get("dimension").getAsString()).matches());
        root.getAsJsonArray("evidence").forEach(e -> assertTrue(
                ID_PATTERN.matcher(e.getAsJsonObject().get("block").getAsString()).matches()));
        root.getAsJsonArray("entities").forEach(e -> assertTrue(
                ID_PATTERN.matcher(e.getAsJsonObject().get("type").getAsString()).matches()));
    }

    @Test
    void numbersInThePayloadAreFiniteAndWithinExpectedRanges() {
        JsonObject root = JsonParser.parseString(PoiPrompt.userPayload(minimalInput())).getAsJsonObject();
        JsonObject score = root.getAsJsonObject("score");
        assertTrue(score.get("total").getAsDouble() >= 0.0D && score.get("total").getAsDouble() <= 1.0D);
        assertEquals(-38, root.get("y").getAsInt());
        assertFalse(Double.isNaN(root.get("nearest_evidence_distance").getAsDouble()));
    }

    @Test
    void aNonRegexBlockIdIsDroppedIntoOtherUnlisted() {
        PoiPrompt.PayloadInput in = new PoiPrompt.PayloadInput(
                "minecraft:overworld", 60, "OreDigTask", null,
                0.5D, 0.3D, 0.0D, "structure",
                List.of(new PoiPrompt.EvidenceItem("Not A Valid Id!", 5), new PoiPrompt.EvidenceItem("rail", 2)),
                List.of(), 0.0D, -1.0D, 0.0D, 3.0D, List.of());
        JsonObject root = JsonParser.parseString(PoiPrompt.userPayload(in)).getAsJsonObject();
        boolean sawOtherUnlisted = false;
        boolean sawBadId = false;
        for (var element : root.getAsJsonArray("evidence")) {
            String block = element.getAsJsonObject().get("block").getAsString();
            if ("other_unlisted".equals(block)) {
                sawOtherUnlisted = true;
                assertEquals(5, element.getAsJsonObject().get("cells").getAsInt());
            }
            if (block.equals("Not A Valid Id!")) {
                sawBadId = true;
            }
        }
        assertTrue(sawOtherUnlisted, "the invalid id's count is folded into other_unlisted");
        assertFalse(sawBadId, "the invalid id itself never reaches the wire");
    }

    @Test
    void anOverLongOrEmptyDimensionFallsBackToOverworldRatherThanLeakingBadInput() {
        PoiPrompt.PayloadInput in = new PoiPrompt.PayloadInput(
                "", 0, "OreDigTask", null, 0.0D, 0.0D, 0.0D, "structure",
                List.of(), List.of(), 0.0D, -1.0D, 0.0D, 0.0D, List.of());
        JsonObject root = JsonParser.parseString(PoiPrompt.userPayload(in)).getAsJsonObject();
        assertEquals("minecraft:overworld", root.get("dimension").getAsString());
    }

    @Test
    void nullBiomeAtFeetSerializesAsJsonNullNotAMissingKey() {
        PoiPrompt.PayloadInput in = new PoiPrompt.PayloadInput(
                "minecraft:overworld", 0, "OreDigTask", null, 0.0D, 0.0D, 0.0D, "structure",
                List.of(), List.of(), 0.0D, -1.0D, 0.0D, 0.0D, List.of());
        JsonObject root = JsonParser.parseString(PoiPrompt.userPayload(in)).getAsJsonObject();
        assertTrue(root.has("biome_at_feet"));
        assertTrue(root.get("biome_at_feet").isJsonNull());
    }

    // ---- validate(): shape rejections ---------------------------------------------------------------

    @Test
    void aValidCallWithAPlainLabelParsesAsItsLiteralDecision() {
        ChatResponse response = toolCallResponse(PoiPrompt.TOOL_NAME,
                validArgs("continue_mining", "natural_cave", "high", "just a cave"));
        Optional<PoiPrompt.Verdict> verdict = PoiPrompt.validate(response);
        assertTrue(verdict.isPresent());
        assertEquals(PoiPrompt.Decision.CONTINUE, verdict.get().decision());
        assertEquals("natural_cave", verdict.get().label());
    }

    @Test
    void zeroToolCallsFailsValidation() {
        ChatResponse response = new ChatResponse("just text, no call", List.of(), "stop", 10, 5, 0);
        assertTrue(PoiPrompt.validate(response).isEmpty());
    }

    @Test
    void twoToolCallsFailsValidation() {
        ChatToolCall call = new ChatToolCall("id", PoiPrompt.TOOL_NAME,
                validArgs("continue_mining", "natural_cave", "high", "x").toString());
        ChatResponse response = new ChatResponse(null, List.of(call, call), "tool_calls", 10, 5, 0);
        assertTrue(PoiPrompt.validate(response).isEmpty());
    }

    @Test
    void theWrongToolNameFailsValidation() {
        ChatResponse response = toolCallResponse("some_other_tool",
                validArgs("continue_mining", "natural_cave", "high", "x"));
        assertTrue(PoiPrompt.validate(response).isEmpty());
    }

    @Test
    void anOutOfEnumLabelFailsValidation() {
        ChatResponse response = toolCallResponse(PoiPrompt.TOOL_NAME,
                validArgs("stop_and_notify", "atlantis", "high", "x"));
        assertTrue(PoiPrompt.validate(response).isEmpty());
    }

    @Test
    void anOutOfEnumDecisionFailsValidation() {
        ChatResponse response = toolCallResponse(PoiPrompt.TOOL_NAME,
                validArgs("maybe_stop", "natural_cave", "high", "x"));
        assertTrue(PoiPrompt.validate(response).isEmpty());
    }

    @Test
    void anOutOfEnumConfidenceFailsValidation() {
        ChatResponse response = toolCallResponse(PoiPrompt.TOOL_NAME,
                validArgs("continue_mining", "natural_cave", "very_sure", "x"));
        assertTrue(PoiPrompt.validate(response).isEmpty());
    }

    @Test
    void aMissingRequiredFieldFailsValidation() {
        JsonObject args = new JsonObject();
        args.addProperty("decision", "continue_mining");
        args.addProperty("label", "natural_cave");
        args.addProperty("confidence", "high");
        // "why" omitted
        assertTrue(PoiPrompt.validate(toolCallResponse(PoiPrompt.TOOL_NAME, args)).isEmpty());
    }

    @Test
    void aNullResponseFailsValidationWithoutThrowing() {
        assertTrue(PoiPrompt.validate(null).isEmpty());
    }

    // ---- validate(): the 6.6 consistency rule and why-sanitising -------------------------------------

    @Test
    void continueNamingAStructureLabelBecomesStop() {
        ChatResponse response = toolCallResponse(PoiPrompt.TOOL_NAME,
                validArgs("continue_mining", "mineshaft", "low", "looked empty"));
        Optional<PoiPrompt.Verdict> verdict = PoiPrompt.validate(response);
        assertTrue(verdict.isPresent());
        assertEquals(PoiPrompt.Decision.STOP, verdict.get().decision(),
                "a structure label always means stop, regardless of the literal decision field");
    }

    @Test
    void continueNamingANonStructureLabelStaysContinue() {
        for (String label : new String[] {"geode", "large_cavern", "ravine", "lava_lake", "natural_cave", "player_or_bot_made"}) {
            ChatResponse response = toolCallResponse(PoiPrompt.TOOL_NAME,
                    validArgs("continue_mining", label, "medium", "x"));
            assertEquals(PoiPrompt.Decision.CONTINUE, PoiPrompt.validate(response).orElseThrow().decision(),
                    "label " + label + " should not force a stop");
        }
    }

    @Test
    void stopAndNotifyIsAlwaysStopRegardlessOfLabel() {
        ChatResponse response = toolCallResponse(PoiPrompt.TOOL_NAME,
                validArgs("stop_and_notify", "geode", "low", "x"));
        assertEquals(PoiPrompt.Decision.STOP, PoiPrompt.validate(response).orElseThrow().decision());
    }

    @Test
    void whyIsSanitisedToAsciiWhitespaceCollapsedAndTruncated() {
        String raw = "line one\nline\ttwo   three§k weird😀" + "x".repeat(200);
        String clean = PoiPrompt.sanitiseWhy(raw);
        assertTrue(clean.length() <= PoiPrompt.WHY_MAX_LENGTH);
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            assertTrue(c >= 0x20 && c <= 0x7E, "character '" + c + "' is outside ASCII 0x20-0x7E");
        }
        assertFalse(clean.contains("  "), "whitespace runs must be collapsed to a single space");
    }

    @Test
    void whyIsCarriedThroughInAValidVerdict() {
        ChatResponse response = toolCallResponse(PoiPrompt.TOOL_NAME,
                validArgs("continue_mining", "natural_cave", "high", "  just   a cave  "));
        assertEquals("just a cave", PoiPrompt.validate(response).orElseThrow().why());
    }

    // ---- tool() schema -------------------------------------------------------------------------------

    @Test
    void theToolNameMatchesDesignExactly() {
        assertEquals("confirm_point_of_interest", PoiPrompt.tool().name());
        assertEquals(PoiPrompt.TOOL_NAME, PoiPrompt.tool().name());
    }

    @Test
    void theToolSchemaForbidsAdditionalPropertiesAndRequiresAllFour() {
        JsonObject schema = PoiPrompt.tool().parametersSchema();
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        var required = schema.getAsJsonArray("required");
        assertEquals(4, required.size());
    }

    @Test
    void theToolIsNotDispatchable() {
        var result = PoiPrompt.tool().handler().invoke(null, new JsonObject());
        assertFalse(result.ok());
    }

    // ---- isValidId ------------------------------------------------------------------------------------

    @Test
    void isValidIdAcceptsNamespacedAndBareIdsAndRejectsBadOnes() {
        assertTrue(PoiPrompt.isValidId("minecraft:oak_planks"));
        assertTrue(PoiPrompt.isValidId("rail"));
        assertFalse(PoiPrompt.isValidId("Not Valid"));
        assertFalse(PoiPrompt.isValidId(""));
        assertFalse(PoiPrompt.isValidId(null));
        assertFalse(PoiPrompt.isValidId("a".repeat(65)));
    }
}
