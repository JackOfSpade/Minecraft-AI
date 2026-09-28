package io.github.zoyluo.minecraftai.brain;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.perception.PerceptionSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A small, separate control-plane model call which decides who a normal chat line addresses.
 *
 * <p>This intentionally does not share the per-bot planner.  The planner owns one bot's
 * perception and actions, while this router only receives a safe snapshot of the player's
 * commandable companions and returns one validated recipient choice.</p>
 */
public final class ChatRecipientRouter {
    public static final ChatRecipientRouter INSTANCE = new ChatRecipientRouter();

    private static final String TOOL_NAME = "select_chat_recipient";
    private static final Gson GSON = new Gson();
    private final Object lifecycleLock = new Object();
    private OpenAiCompatibleApiClient apiClient;
    private ExecutorService executor;
    private long generation;

    private ChatRecipientRouter() {
    }

    public void configure(MinecraftAiConfig config) {
        Objects.requireNonNull(config, "config");
        synchronized (lifecycleLock) {
            generation++;
            if (executor != null) {
                executor.shutdownNow();
            }
            apiClient = new OpenAiCompatibleApiClient(config.llm());
            executor = Executors.newFixedThreadPool(2);
        }
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            generation++;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            apiClient = null;
        }
    }

    /**
     * Sends one normal chat line to Gemini for a required structured recipient choice.
     * Callbacks always return to the Minecraft server thread.
     */
    public void select(ServerPlayerEntity sender,
                       String message,
                       List<Candidate> candidates,
                       Consumer<Decision> onDecision,
                       Consumer<Throwable> onFailure) {
        if (sender == null || message == null || message.isBlank() || candidates == null || candidates.isEmpty()) {
            return;
        }
        Objects.requireNonNull(onDecision, "onDecision");
        Objects.requireNonNull(onFailure, "onFailure");

        List<Candidate> snapshot = candidates.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Candidate::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (snapshot.isEmpty()) {
            return;
        }
        // With only one commandable companion, every semantic recipient choice resolves to the
        // same bot.  Avoid a remote routing call, but leave all multi-bot language to Gemini.
        Optional<Decision> directDecision = deterministicDecision(snapshot);
        if (directDecision.isPresent()) {
            onDecision.accept(directDecision.get());
            return;
        }

        OpenAiCompatibleApiClient client;
        ExecutorService worker;
        long requestGeneration;
        synchronized (lifecycleLock) {
            client = apiClient;
            worker = executor;
            requestGeneration = generation;
        }
        if (client == null || worker == null || worker.isShutdown()) {
            onFailure.accept(new IllegalStateException("chat_router_unavailable"));
            return;
        }

        List<ChatMessage> history = List.of(
                ChatMessage.system(systemPrompt()),
                ChatMessage.user(userPayload(sender, message, snapshot)));
        ToolDefinition routingTool = routingTool(snapshot);
        MinecraftServer server = sender.getEntityWorld().getServer();
        try {
            worker.submit(() -> {
                try {
                    ChatResponse response = client.chatRequiringToolCall(history, List.of(routingTool));
                    Optional<Decision> decision = parseDecision(response, snapshot);
                    invokeOnServer(server, requestGeneration, worker, () -> {
                        if (decision.isPresent()) {
                            onDecision.accept(decision.get());
                        } else {
                            onFailure.accept(new IllegalStateException("invalid_chat_recipient_response"));
                        }
                    });
                } catch (Exception exception) {
                    invokeOnServer(server, requestGeneration, worker, () -> onFailure.accept(exception));
                }
            });
        } catch (RuntimeException exception) {
            onFailure.accept(exception);
        }
    }

    private void invokeOnServer(MinecraftServer server,
                                long requestGeneration,
                                ExecutorService requestExecutor,
                                Runnable callback) {
        server.execute(() -> {
            synchronized (lifecycleLock) {
                if (generation != requestGeneration || executor != requestExecutor) {
                    return;
                }
            }
            callback.run();
        });
    }

    static String systemPrompt() {
        return """
                You are the recipient router for a Minecraft companion mod. You never chat, plan, or perform an action.
                Call select_chat_recipient exactly once and do not return ordinary text.

                Choose target=everyone only when the player semantically addresses the whole companion group, including wording such as everyone, everybody, all of you, all bots, y'all, you all, you guys, the crew, or an unambiguous collective request.
                Choose a listed bot name when the player is asking that companion to act. A name mentioned as an object, destination, recipient, comparison, or discussion subject is not automatically the addressee. For example, "Give Bob a sword" normally asks a suitable companion to give Bob a sword; it does not ask Bob to act.
                For an unnamed singular request, use the current server-supplied capability snapshots to choose a listed bot name only when one same-dimension companion is clearly better suited to do the work or avoid an unnecessary task interruption. Otherwise choose target=nearest, which is the specifically marked same-dimension companion. Do not infer a bot name from words in the chat text; any named choice in this case must come from the supplied live capability data.
                Each candidate snapshot includes carried inventory totals, held and worn gear, health, hunger, free inventory space, best pickaxe tier, travel mode, and active-task state. A clear direct addressee or collective instruction always takes precedence over capability.
                An active-task state of AWAITING_PLAYER_CONTINUE means that companion safely completed a bounded batch and is waiting for the player's approval. Route a short acknowledgement such as "yes", "continue", or "go ahead" to that waiting companion when it is the clear context; use everyone only for an explicit collective acknowledgement.
                You may choose only one value listed in the target enum. The original player chat is preserved verbatim by the server, so do not try to rewrite it in the function arguments.
                """;
    }

    private static String userPayload(ServerPlayerEntity sender, String message, List<Candidate> candidates) {
        return routingPayload(sender.getGameProfile().name(), message, candidates);
    }

    static String routingPayload(String playerName, String message, List<Candidate> candidates) {
        JsonObject payload = new JsonObject();
        payload.addProperty("player", playerName == null ? "player" : playerName);
        payload.addProperty("chat", message);
        JsonArray roster = new JsonArray();
        for (Candidate candidate : candidates) {
            JsonObject item = new JsonObject();
            item.addProperty("name", candidate.name());
            item.addProperty("role", candidate.role());
            item.addProperty("same_dimension", candidate.sameDimension());
            if (candidate.sameDimension()) {
                item.addProperty("distance_blocks", Math.round(candidate.distanceBlocks() * 10.0D) / 10.0D);
            }
            item.addProperty("nearest_same_dimension", candidate.nearestSameDimension());
            item.add("capabilities", capabilityPayload(candidate.capability()));
            roster.add(item);
        }
        payload.add("commandable_companions", roster);
        return payload.toString();
    }

    private static JsonObject capabilityPayload(CapabilitySummary capability) {
        JsonObject payload = new JsonObject();
        payload.add("carried_inventory", GSON.toJsonTree(capability.carriedInventory()));
        payload.add("equipment", GSON.toJsonTree(capability.equipment()));
        payload.addProperty("health", capability.health());
        payload.addProperty("hunger", capability.hunger());
        payload.addProperty("free_main_slots", capability.freeMainSlots());
        payload.addProperty("best_pickaxe_tier", capability.bestPickaxeTier());
        payload.addProperty("travel_mode", capability.travelMode());
        JsonObject activeTask = new JsonObject();
        activeTask.addProperty("name", capability.activeTask().name());
        activeTask.addProperty("state", capability.activeTask().state());
        activeTask.addProperty("progress", capability.activeTask().progress());
        payload.add("active_task", activeTask);
        return payload;
    }

    /**
     * A remote router has no useful choice when the authorized roster contains exactly one bot.
     * Do not expand this into lexical name/collective routing: multi-bot language is ambiguous.
     */
    static Optional<Decision> deterministicDecision(List<Candidate> candidates) {
        if (candidates == null || candidates.size() != 1 || candidates.getFirst() == null) {
            return Optional.empty();
        }
        Candidate onlyCandidate = candidates.getFirst();
        return Optional.of(new Decision(Target.BOT, onlyCandidate.botId()));
    }

    private static ToolDefinition routingTool(List<Candidate> candidates) {
        JsonArray targetChoices = new JsonArray();
        targetChoices.add("nearest");
        targetChoices.add("everyone");
        for (Candidate candidate : candidates) {
            targetChoices.add(candidate.name());
        }

        JsonObject target = new JsonObject();
        target.addProperty("type", "string");
        target.add("enum", targetChoices);
        target.addProperty("description", "One permitted recipient choice: nearest, everyone, or an exact listed companion name.");

        JsonObject properties = new JsonObject();
        properties.add("target", target);
        JsonArray required = new JsonArray();
        required.add("target");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);

        return new ToolDefinition(
                TOOL_NAME,
                "Select exactly one recipient for the player's normal Minecraft chat.",
                schema,
                (ignoredBot, ignoredArgs) -> new ToolDefinition.ToolResult(false, "routing_tool_is_not_dispatchable"));
    }

    private static Optional<Decision> parseDecision(ChatResponse response, List<Candidate> candidates) {
        if (response == null || response.toolCalls() == null || response.toolCalls().size() != 1) {
            return Optional.empty();
        }
        ChatToolCall call = response.toolCalls().getFirst();
        if (!TOOL_NAME.equals(call.name())) {
            return Optional.empty();
        }
        JsonObject args = call.parsedArguments();
        if (!args.has("target") || args.get("target").isJsonNull()) {
            return Optional.empty();
        }
        String target;
        try {
            target = args.get("target").getAsString().trim();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if ("everyone".equalsIgnoreCase(target)) {
            return Optional.of(remoteDecision(Target.EVERYONE, null));
        }
        if ("nearest".equalsIgnoreCase(target)) {
            return candidates.stream()
                    .filter(Candidate::nearestSameDimension)
                    .findFirst()
                    .map(candidate -> remoteDecision(Target.NEAREST, candidate.botId()));
        }
        return candidates.stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(target))
                .findFirst()
                .map(candidate -> remoteDecision(Target.BOT, candidate.botId()));
    }

    /** A successful remote router call consumes one of each recipient's instruction allowance. */
    static Decision remoteDecision(Target target, UUID botId) {
        return new Decision(target, botId, 1);
    }

    public enum Target {
        BOT,
        EVERYONE,
        NEAREST
    }

    public record Candidate(UUID botId,
                            String name,
                            String role,
                            boolean sameDimension,
                            double distanceBlocks,
                            boolean nearestSameDimension,
                            CapabilitySummary capability) {
        public Candidate {
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(capability, "capability");
            if (name.isBlank()) {
                throw new IllegalArgumentException("candidate_name_blank");
            }
            role = role == null || role.isBlank() ? "worker" : role;
        }
    }

    /** A complete carried inventory plus compact live facts used only to select a recipient. */
    public record CapabilitySummary(Map<String, Integer> carriedInventory,
                                    PerceptionSnapshot.Equipment equipment,
                                    float health,
                                    int hunger,
                                    int freeMainSlots,
                                    String bestPickaxeTier,
                                    String travelMode,
                                    TaskSummary activeTask) {
        public CapabilitySummary {
            Map<String, Integer> orderedInventory = new TreeMap<>();
            if (carriedInventory != null) {
                carriedInventory.forEach((item, count) -> {
                    if (item != null && !item.isBlank() && count != null && count > 0) {
                        orderedInventory.put(item, count);
                    }
                });
            }
            carriedInventory = Collections.unmodifiableMap(new LinkedHashMap<>(orderedInventory));
            equipment = Objects.requireNonNull(equipment, "equipment");
            health = Math.max(0.0F, health);
            hunger = Math.max(0, hunger);
            freeMainSlots = Math.max(0, freeMainSlots);
            bestPickaxeTier = bestPickaxeTier == null || bestPickaxeTier.isBlank()
                    ? "none" : bestPickaxeTier;
            travelMode = travelMode == null || travelMode.isBlank() ? "on_foot" : travelMode;
            activeTask = Objects.requireNonNull(activeTask, "activeTask");
        }
    }

    /** No free-form task description or failure text is sent to the routing call. */
    public record TaskSummary(String name, String state, double progress) {
        public TaskSummary {
            name = name == null || name.isBlank() ? "idle" : name;
            state = state == null || state.isBlank() ? "IDLE" : state;
            progress = Double.isFinite(progress) ? Math.max(0.0D, Math.min(1.0D, progress)) : 0.0D;
        }
    }

    public record Decision(Target target, UUID botId, int routingModelCallCost) {
        public Decision(Target target, UUID botId) {
            this(target, botId, 0);
        }

        public Decision {
            Objects.requireNonNull(target, "target");
            if (target == Target.EVERYONE && botId != null) {
                throw new IllegalArgumentException("everyone_does_not_have_one_bot");
            }
            if (target != Target.EVERYONE && botId == null) {
                throw new IllegalArgumentException("single_target_needs_bot_id");
            }
            if (routingModelCallCost < 0 || routingModelCallCost > 1) {
                throw new IllegalArgumentException("routing_model_call_cost_out_of_range");
            }
        }
    }
}
