package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class MinecraftAiCommand {
    private MinecraftAiCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(literal("minecraftai")
                .then(literal("spawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("despawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> despawn(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("list")
                        .executes(context -> list(context.getSource())))
                .then(MinecraftAiBrainSubcommand.build())
                .then(MinecraftAiLogSubcommand.build())
                .then(MinecraftAiPersistSubcommand.build())
                .then(MinecraftAiJobSubcommand.build())
                .then(MinecraftAiMemorySubcommand.build())
                .then(MinecraftAiObserveSubcommand.profile())
                .then(MinecraftAiObserveSubcommand.replay())
                .then(MinecraftAiObserveSubcommand.tps())
                .then(MinecraftAiTaskSubcommand.build())
                .then(MinecraftAiDeplintSubcommand.build())
                .then(MinecraftAiSnapshotSubcommand.build()));
    }

    private static int spawn(ServerCommandSource source, String name) {
        if (!BotAuthorizationGate.INSTANCE.canProvisionPersonalBot(source, "command:spawn")) {
            return 0;
        }
        ServerPlayerEntity executor = source.getPlayer();
        GameMode gameMode = executor == null ? GameMode.SURVIVAL : executor.interactionManager.getGameMode();
        UUID ownerUuid = executor == null ? null : executor.getUuid();
        var rotation = source.getRotation();
        var spawned = AIPlayerManager.INSTANCE.spawn(
                source.getServer(),
                name,
                source.getWorld(),
                source.getPosition(),
                rotation.y,
                rotation.x,
                gameMode,
                ownerUuid);

        if (spawned.isPresent()) {
            source.sendFeedback(() -> Text.literal("[Minecraft-AI] Spawned " + name), true);
            return 1;
        }

        source.sendError(Text.literal("[Minecraft-AI] Failed to spawn " + name + " (name already in use)"));
        return 0;
    }

    private static int despawn(ServerCommandSource source, String name) {
        var bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:despawn");
        if (bot.isEmpty()) {
            return 0;
        }
        boolean removed = AIPlayerManager.INSTANCE.despawn(source.getServer(), name);
        if (removed) {
            source.sendFeedback(() -> Text.literal("[Minecraft-AI] Despawned " + name), true);
            return 1;
        }

        source.sendError(Text.literal("[Minecraft-AI] No such bot: " + name));
        return 0;
    }

    private static int list(ServerCommandSource source) {
        var bots = AIPlayerManager.INSTANCE.all().stream()
                .filter(bot -> BotAuthorizationGate.INSTANCE.canView(source, bot))
                .toList();
        String names = bots.stream()
                .map(player -> player.getGameProfile().name())
                .collect(Collectors.joining(", "));
        source.sendFeedback(() -> Text.literal("[Minecraft-AI] " + bots.size() + " bot(s): " + names), false);
        return bots.size();
    }
}
