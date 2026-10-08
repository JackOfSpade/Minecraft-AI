package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class MinecraftAiCommand {
    private MinecraftAiCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        registerRoot(dispatcher, "minecraftai");
        // Keep the established compact spelling and offer the naturally typed hyphenated alias.
        registerRoot(dispatcher, "minecraft-ai");
    }

    private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
        dispatcher.register(literal(root)
                .then(literal("spawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("despawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> despawn(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("list")
                        .executes(context -> list(context.getSource())))
                .then(literal("recall")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> recallCompanion(context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(literal("companions")
                        .then(literal("recall")
                                .executes(context -> recallCompanions(context.getSource()))
                                .then(argument("name", StringArgumentType.word())
                                        .executes(context -> recallCompanion(context.getSource(),
                                                StringArgumentType.getString(context, "name"))))))
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

    private static int recallCompanions(CommandSourceStack source) {
        ServerPlayer owner = source.getPlayer();
        if (owner == null) {
            source.sendFailure(Component.literal("[Minecraft-AI] Companion recall must be run by the companion owner."));
            return 0;
        }
        int recalled = AIPlayerManager.INSTANCE.recallOwnedCompanions(owner);
        if (recalled == 0) {
            source.sendFailure(Component.literal("[Minecraft-AI] No live companions could be recalled."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] Recalled " + recalled + " companion(s)."), false);
        return recalled;
    }

    private static int recallCompanion(CommandSourceStack source, String name) {
        ServerPlayer owner = source.getPlayer();
        if (owner == null) {
            source.sendFailure(Component.literal("[Minecraft-AI] Companion recall must be run by the companion owner."));
            return 0;
        }
        if (!AIPlayerManager.INSTANCE.recallOwnedCompanion(owner, name)) {
            source.sendFailure(Component.literal("[Minecraft-AI] No recallable companion named " + name + " belongs to you."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] Recalled " + name + "."), false);
        return 1;
    }

    private static int spawn(CommandSourceStack source, String name) {
        if (!BotAuthorizationGate.INSTANCE.canProvisionPersonalBot(source, "command:spawn")) {
            return 0;
        }
        ServerPlayer executor = source.getPlayer();
        GameType gameMode = executor == null ? GameType.SURVIVAL : executor.gameMode.getGameModeForPlayer();
        UUID ownerUuid = executor == null ? null : executor.getUUID();
        var rotation = source.getRotation();
        var spawned = AIPlayerManager.INSTANCE.spawn(
                source.getServer(),
                name,
                source.getLevel(),
                source.getPosition(),
                rotation.y,
                rotation.x,
                gameMode,
                ownerUuid);

        if (spawned.isPresent()) {
            source.sendSuccess(() -> Component.literal("[Minecraft-AI] Spawned " + name), true);
            return 1;
        }

        source.sendFailure(Component.literal("[Minecraft-AI] Failed to spawn " + name + " (name already in use)"));
        return 0;
    }

    private static int despawn(CommandSourceStack source, String name) {
        var bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:despawn");
        if (bot.isEmpty()) {
            return 0;
        }
        boolean removed = AIPlayerManager.INSTANCE.despawn(source.getServer(), name);
        if (removed) {
            source.sendSuccess(() -> Component.literal("[Minecraft-AI] Despawned " + name), true);
            return 1;
        }

        source.sendFailure(Component.literal("[Minecraft-AI] No such bot: " + name));
        return 0;
    }

    private static int list(CommandSourceStack source) {
        var bots = AIPlayerManager.INSTANCE.all().stream()
                .filter(bot -> BotAuthorizationGate.INSTANCE.canView(source, bot))
                .toList();
        String names = bots.stream()
                .map(player -> player.getGameProfile().name())
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] " + bots.size() + " bot(s): " + names), false);
        return bots.size();
    }
}
