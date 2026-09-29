package io.github.zoyluo.minecraftai.auth;

import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy.Actor;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy.BotTarget;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy.Decision;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy.GlobalTarget;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy.Operation;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

/** Minecraft-facing resolver, denial response, and audit adapter for {@link BotAuthorizationPolicy}. */
public final class BotAuthorizationGate {
    public static final BotAuthorizationGate INSTANCE = new BotAuthorizationGate();

    private static final int OPERATOR_LEVEL = 2;
    private static final int TRUSTED_CONSOLE_LEVEL = 4;
    private static final String GENERIC_NOT_FOUND = "[Minecraft-AI] Bot not found or insufficient permission.";
    private final BotAuthorizationPolicy policy = new BotAuthorizationPolicy();

    private BotAuthorizationGate() {
    }

    public Optional<AIPlayerEntity> resolveAuthorized(CommandSourceStack source,
                                                      String botName,
                                                      Operation operation,
                                                      String channel) {
        Optional<AIPlayerEntity> bot = resolveForSource(source, botName);
        if (bot.isEmpty()) {
            auditMissing(actor(source), source.getTextName(), botName, operation, channel);
            source.sendFailure(Component.literal(GENERIC_NOT_FOUND));
            return Optional.empty();
        }
        if (!authorize(source, bot.get(), operation, channel)) {
            source.sendFailure(Component.literal(GENERIC_NOT_FOUND));
            return Optional.empty();
        }
        return bot;
    }

    public Optional<AIPlayerEntity> resolveAuthorized(ServerPlayer player,
                                                      String botName,
                                                      Operation operation,
                                                      String channel) {
        Optional<AIPlayerEntity> bot = resolveForPlayer(player, botName);
        if (bot.isEmpty()) {
            auditMissing(Actor.player(player.getUUID(), hasLevel(player.permissions(), OPERATOR_LEVEL)),
                    player.getGameProfile().name(), botName, operation, channel);
            return Optional.empty();
        }
        if (!authorize(player, bot.get(), operation, channel)) {
            return Optional.empty();
        }
        return bot;
    }

    public boolean authorize(CommandSourceStack source,
                             AIPlayerEntity bot,
                             Operation operation,
                             String channel) {
        Actor actor = actor(source);
        Decision decision = policy.evaluate(actor, target(bot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, source.getTextName(), bot, operation, channel, decision);
        }
        return decision.allowed();
    }

    public boolean authorize(ServerPlayer player,
                             AIPlayerEntity bot,
                             Operation operation,
                             String channel) {
        Actor actor = Actor.player(player.getUUID(), hasLevel(player.permissions(), OPERATOR_LEVEL));
        Decision decision = policy.evaluate(actor, target(bot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, player.getGameProfile().name(), bot, operation, channel, decision);
        }
        return decision.allowed();
    }

    /**
     * Checks whether a player may command a bot without producing a denial audit entry.
     * Recipient discovery uses this before an AI routing request so other players' bot names
     * and state are neither exposed to the model nor logged as spurious authorization failures.
     */
    public boolean canCommand(ServerPlayer player, AIPlayerEntity bot) {
        Actor actor = Actor.player(player.getUUID(), hasLevel(player.permissions(), OPERATOR_LEVEL));
        return policy.evaluate(actor, target(bot), Operation.COMMAND).allowed();
    }

    public boolean authorizeBot(AIPlayerEntity actorBot,
                                AIPlayerEntity targetBot,
                                Operation operation,
                                String channel) {
        Actor actor = Actor.bot(actorBot.getUUID(), AIPlayerManager.INSTANCE.ownerOf(actorBot).orElse(null));
        Decision decision = policy.evaluate(actor, target(targetBot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, actorBot.getGameProfile().name(), targetBot, operation, channel, decision);
        }
        return decision.allowed();
    }

    public boolean requireGlobalAdmin(CommandSourceStack source, String channel) {
        Actor actor = actor(source);
        Decision decision = policy.evaluate(actor, GlobalTarget.INSTANCE, Operation.ADMIN);
        if (decision.allowed()) {
            return true;
        }
        BotLog.security("authorization_denied",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", source.getTextName(),
                "bot_uuid", "-",
                "bot_name", "-",
                "operation", Operation.ADMIN,
                "channel", cleanChannel(channel),
                "reason", decision.reason());
        source.sendFailure(Component.literal("[Minecraft-AI] This operation requires server administrator permission."));
        return false;
    }

    public boolean canProvisionPersonalBot(CommandSourceStack source, String channel) {
        if (source.getPlayer() != null) {
            return true;
        }
        return requireGlobalAdmin(source, channel);
    }

    public boolean canView(CommandSourceStack source, AIPlayerEntity bot) {
        return policy.evaluate(actor(source), target(bot), Operation.VIEW).allowed();
    }

    private Optional<AIPlayerEntity> resolveForSource(CommandSourceStack source, String botName) {
        ServerPlayer player = source.getPlayer();
        Optional<AIPlayerEntity> resolved = BotTargetSelector.resolve(player == null ? null : player.getUUID(), botName,
                AIPlayerManager.INSTANCE::botOf, AIPlayerManager.INSTANCE::getByName);
        if (resolved.isPresent() || player == null || botName == null || botName.isBlank()) {
            return resolved;
        }
        // The owner index is authoritative for the player's one personal assistant.
        // Use it only as a recovery path when the derived name index is briefly stale,
        // and still require the requested name to match before returning the bot.
        return AIPlayerManager.INSTANCE.botOf(player.getUUID())
                .filter(bot -> bot.getGameProfile().name().equalsIgnoreCase(botName));
    }

    private Optional<AIPlayerEntity> resolveForPlayer(ServerPlayer player, String botName) {
        return BotTargetSelector.resolve(player.getUUID(), botName,
                AIPlayerManager.INSTANCE::botOf, AIPlayerManager.INSTANCE::getByName);
    }

    /** The 1.21.11 replacement of {@code hasPermissionLevel(int)}: "at least this vanilla op level". */
    private static boolean hasLevel(PermissionSet permissions, int level) {
        return permissions.hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }

    private Actor actor(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return Actor.player(player.getUUID(), hasLevel(source.permissions(), OPERATOR_LEVEL));
        }
        return hasLevel(source.permissions(), TRUSTED_CONSOLE_LEVEL) ? Actor.console() : Actor.unknown();
    }

    private BotTarget target(AIPlayerEntity bot) {
        return new BotTarget(bot.getUUID(), AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null));
    }

    private static void auditDenied(Actor actor,
                                    String actorName,
                                    AIPlayerEntity bot,
                                    Operation operation,
                                    String channel,
                                    Decision decision) {
        BotLog.security("authorization_denied",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", actorName == null ? "-" : actorName,
                "bot_uuid", bot.getUUID(),
                "bot_name", bot.getGameProfile().name(),
                "operation", operation,
                "channel", cleanChannel(channel),
                "reason", decision.reason());
    }

    private static void auditMissing(Actor actor,
                                     String actorName,
                                     String requestedName,
                                     Operation operation,
                                     String channel) {
        BotLog.security("authorization_target_unresolved",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", actorName == null ? "-" : actorName,
                "bot_uuid", "-",
                "bot_name", requestedName == null || requestedName.isBlank() ? "<owned>" : requestedName,
                "operation", operation,
                "channel", cleanChannel(channel),
                "reason", "target_not_found");
    }

    private static String safe(UUID value) {
        return value == null ? "-" : value.toString();
    }

    private static String cleanChannel(String channel) {
        return channel == null || channel.isBlank() ? "unknown" : channel;
    }
}
