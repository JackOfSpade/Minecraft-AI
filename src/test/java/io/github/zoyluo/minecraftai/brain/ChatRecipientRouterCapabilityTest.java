package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.perception.PerceptionSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatRecipientRouterCapabilityTest {
    @Test
    void routerPayloadIncludesFullCarriedInventoryAndEquippedGear() {
        ChatRecipientRouter.Candidate moss = candidate("Moss", UUID.fromString("00000000-0000-0000-0000-000000000001"));

        JsonObject payload = JsonParser.parseString(
                ChatRecipientRouter.routingPayload("Player", "Mine iron", List.of(moss))).getAsJsonObject();
        JsonObject companion = payload.getAsJsonArray("commandable_companions").get(0).getAsJsonObject();
        JsonObject capabilities = companion.getAsJsonObject("capabilities");

        assertEquals(2, capabilities.getAsJsonObject("carried_inventory")
                .get("minecraft:oak_log").getAsInt());
        assertEquals(3, capabilities.getAsJsonObject("carried_inventory")
                .get("minecraft:cobblestone").getAsInt());
        assertEquals("minecraft:diamond_pickaxe", capabilities.getAsJsonObject("equipment")
                .getAsJsonObject("mainHand").get("item").getAsString());
        assertEquals("minecraft:iron_chestplate", capabilities.getAsJsonObject("equipment")
                .getAsJsonObject("chest").get("item").getAsString());
        assertEquals(18, capabilities.get("hunger").getAsInt());
        assertEquals(12, capabilities.get("free_main_slots").getAsInt());
        assertEquals("diamond", capabilities.get("best_pickaxe_tier").getAsString());
        assertEquals("boat", capabilities.get("travel_mode").getAsString());
        assertEquals("follow", capabilities.getAsJsonObject("active_task").get("name").getAsString());
    }

    @Test
    void onlyOneEligibleCompanionSkipsTheRemoteRecipientRouter() {
        ChatRecipientRouter.Candidate moss = candidate("Moss", UUID.fromString("00000000-0000-0000-0000-000000000001"));

        ChatRecipientRouter.Decision decision = ChatRecipientRouter.deterministicDecision(List.of(moss)).orElseThrow();

        assertEquals(ChatRecipientRouter.Target.BOT, decision.target());
        assertEquals(moss.botId(), decision.botId());
        assertEquals(0, decision.routingModelCallCost());
        assertFalse(ChatRecipientRouter.deterministicDecision(List.of(moss,
                candidate("Rowan", UUID.fromString("00000000-0000-0000-0000-000000000002")))).isPresent());
    }

    @Test
    void remoteRecipientChoiceConsumesOneCallForEachActualRecipient() throws IOException {
        ChatRecipientRouter.Decision remote = ChatRecipientRouter.remoteDecision(
                ChatRecipientRouter.Target.EVERYONE, null);
        String capture = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/brain/ChatCaptureListener.java"));

        assertEquals(1, remote.routingModelCallCost());
        assertTrue(capture.contains("decision.routingModelCallCost()"));
        assertTrue(capture.contains("handleRoutedMessage(bot, sender, body, routingModelCallCost)"));
    }

    @Test
    void capabilityCanBreakAnUnnamedSingularTieWithoutTextNameMatching() {
        String prompt = ChatRecipientRouter.systemPrompt();

        assertTrue(prompt.contains("one same-dimension companion is clearly better suited"));
        assertTrue(prompt.contains("Otherwise choose target=nearest"));
        assertTrue(prompt.contains("Do not infer a bot name from words in the chat text"));
    }

    private static ChatRecipientRouter.Candidate candidate(String name, UUID id) {
        PerceptionSnapshot.Equipment equipment = new PerceptionSnapshot.Equipment(
                new PerceptionSnapshot.EquippedItem("minecraft:diamond_pickaxe", 1, 1200),
                new PerceptionSnapshot.EquippedItem("minecraft:shield", 1, 100),
                new PerceptionSnapshot.EquippedItem("minecraft:iron_helmet", 1, 150),
                new PerceptionSnapshot.EquippedItem("minecraft:iron_chestplate", 1, 200),
                new PerceptionSnapshot.EquippedItem("minecraft:iron_leggings", 1, 180),
                new PerceptionSnapshot.EquippedItem("minecraft:iron_boots", 1, 170));
        ChatRecipientRouter.CapabilitySummary capability = new ChatRecipientRouter.CapabilitySummary(
                Map.of("minecraft:oak_log", 2, "minecraft:cobblestone", 3),
                equipment,
                19.5F,
                18,
                12,
                "diamond",
                "boat",
                new ChatRecipientRouter.TaskSummary("follow", "RUNNING", 0.5D));
        return new ChatRecipientRouter.Candidate(id, name, "worker", true, 4.0D, true, capability);
    }
}
