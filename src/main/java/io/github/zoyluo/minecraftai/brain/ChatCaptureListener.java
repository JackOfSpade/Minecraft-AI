package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.coordination.MiningAssistCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.perception.PerceptionCollector;
import io.github.zoyluo.minecraftai.perception.PerceptionSnapshot;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Routes normal Minecraft chat through Gemini before any companion receives an instruction. */
public final class ChatCaptureListener {
    private static final Map<UUID, Long> ROUTING_EPOCHS = new ConcurrentHashMap<>();

    private ChatCaptureListener() {
    }

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (sender instanceof AIPlayerEntity) {
                return;
            }
            String text = message.decoratedContent().getString();
            if (text == null || text.isBlank()) {
                return;
            }

            List<ChatRecipientRouter.Candidate> candidates = candidatesFor(sender);
            if (candidates.isEmpty()) {
                return;
            }
            long epoch = nextEpoch(sender.getUUID());
            ChatRecipientRouter.INSTANCE.select(
                    sender,
                    text,
                    candidates,
                    decision -> applyDecision(sender, text, candidates, epoch, decision),
                    failure -> reportRoutingFailure(sender, epoch, failure));
        });
    }

    private static List<ChatRecipientRouter.Candidate> candidatesFor(ServerPlayer sender) {
        List<AIPlayerEntity> eligible = AIPlayerManager.INSTANCE.all().stream()
                .filter(AIPlayerEntity::isAlive)
                .filter(bot -> BotAuthorizationGate.INSTANCE.canCommand(sender, bot))
                .sorted(Comparator.comparing(bot -> bot.getGameProfile().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        Optional<AIPlayerEntity> nearest = eligible.stream()
                .filter(bot -> bot.level() == sender.level())
                .min(Comparator.comparingDouble(bot -> bot.distanceToSqr(sender)));
        UUID nearestId = nearest.map(AIPlayerEntity::getUUID).orElse(null);

        return eligible.stream()
                .map(bot -> {
                    boolean sameDimension = bot.level() == sender.level();
                    double distance = sameDimension ? Math.sqrt(bot.distanceToSqr(sender)) : -1.0D;
                    return new ChatRecipientRouter.Candidate(
                            bot.getUUID(),
                            bot.getGameProfile().name(),
                            sameDimension,
                            distance,
                            bot.getUUID().equals(nearestId),
                            capabilityFor(bot));
                })
                .toList();
    }

    /** Builds routing facts on the server thread without scanning terrain, entities, or containers. */
    private static ChatRecipientRouter.CapabilitySummary capabilityFor(AIPlayerEntity bot) {
        PerceptionSnapshot.SelfState self = PerceptionCollector.collectSelfState(bot);
        ChatRecipientRouter.TaskSummary taskSummary = MiningAssistCoordinator.awaitingContinue(bot)
                ? new ChatRecipientRouter.TaskSummary("poi_hold", "AWAITING_PLAYER_CONTINUE", 1.0D)
                : GoalExecutor.INSTANCE.batchCheckpointStatus(bot)
                        .filter(GoalExecutor.BatchCheckpointStatus::awaitingPlayer)
                        .map(ignored -> new ChatRecipientRouter.TaskSummary(
                                "goal_batch_checkpoint", "AWAITING_PLAYER_CONTINUE", 1.0D))
                        .orElseGet(() -> {
                            PerceptionSnapshot.TaskInfo task = PerceptionCollector.collectTaskInfo(bot);
                            return new ChatRecipientRouter.TaskSummary(task.name(), task.state(), task.progress());
                        });
        return new ChatRecipientRouter.CapabilitySummary(
                self.inventoryCount(),
                self.equipment(),
                self.hp(),
                self.hunger(),
                freeMainSlots(bot),
                pickaxeTierName(ToolTier.bestPickaxeTier(bot)),
                travelMode(bot),
                taskSummary);
    }

    private static int freeMainSlots(AIPlayerEntity bot) {
        int free = 0;
        for (var stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static String pickaxeTierName(int tier) {
        return switch (tier) {
            case ToolTier.NETHERITE -> "netherite";
            case ToolTier.DIAMOND -> "diamond";
            case ToolTier.IRON -> "iron";
            case ToolTier.STONE -> "stone";
            case ToolTier.WOOD -> "wood";
            default -> "none";
        };
    }

    private static String travelMode(AIPlayerEntity bot) {
        if (bot.getVehicle() instanceof AbstractBoat) {
            return "boat";
        }
        if (bot.isInWater() || bot.isUnderWater()) {
            return "swimming";
        }
        return "on_foot";
    }

    private static void applyDecision(ServerPlayer sender,
                                      String text,
                                      List<ChatRecipientRouter.Candidate> candidates,
                                      long epoch,
                                      ChatRecipientRouter.Decision decision) {
        if (!isCurrent(sender.getUUID(), epoch)) {
            return;
        }
        List<AIPlayerEntity> recipients = switch (decision.target()) {
            case EVERYONE -> candidates.stream()
                    .map(ChatRecipientRouter.Candidate::botId)
                    .map(AIPlayerManager.INSTANCE::getByUuid)
                    .flatMap(Optional::stream)
                    .filter(AIPlayerEntity::isAlive)
                    .toList();
            case BOT, NEAREST -> AIPlayerManager.INSTANCE.getByUuid(decision.botId())
                    .filter(AIPlayerEntity::isAlive)
                    .stream()
                    .toList();
        };
        String channel = "chat:gemini:" + decision.target().name().toLowerCase(java.util.Locale.ROOT);
        for (AIPlayerEntity bot : recipients) {
            route(sender, bot, text, channel, decision.routingModelCallCost());
        }
    }

    private static void reportRoutingFailure(ServerPlayer sender, long epoch, Throwable failure) {
        if (!isCurrent(sender.getUUID(), epoch)) {
            return;
        }
        String reason = failure == null || failure.getMessage() == null
                ? "unknown"
                : failure.getMessage();
        BotLog.warn(LogCategory.COMM, null, "chat_recipient_routing_failed",
                "sender", sender.getGameProfile().name(),
                "reason", reason);
        sender.displayClientMessage(Component.literal("[Minecraft-AI] I couldn't determine which companion you meant. Please try again."), false);
    }

    private static long nextEpoch(UUID senderId) {
        return ROUTING_EPOCHS.merge(senderId, 1L, Long::sum);
    }

    private static boolean isCurrent(UUID senderId, long epoch) {
        return ROUTING_EPOCHS.getOrDefault(senderId, 0L) == epoch;
    }

    private static void route(ServerPlayer sender,
                              AIPlayerEntity bot,
                              String body,
                              String channel,
                              int routingModelCallCost) {
        if (body == null || body.isBlank()
                || !BotAuthorizationGate.INSTANCE.authorize(
                sender, bot, BotAuthorizationPolicy.Operation.COMMAND, channel)) {
            return;
        }
        BotLog.comm(bot, "chat_in", "sender", sender.getGameProfile().name(), "text", body);
        if (io.github.zoyluo.minecraftai.runtime.IntentController.INSTANCE.routePlayerControlPhrase(
                bot, io.github.zoyluo.minecraftai.runtime.IntentController.ControlOrigin.PLAYER_COMMAND, body)) {
            return;
        }
        BrainCoordinator.INSTANCE.handleRoutedMessage(bot, sender, body, routingModelCallCost);
    }
}
