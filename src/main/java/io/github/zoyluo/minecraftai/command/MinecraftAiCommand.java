package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.zoyluo.minecraftai.autosprint.AutoSprintController;
import io.github.zoyluo.minecraftai.autosprint.AutoSprintDirection;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.pathfinding.TeleportSafety;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
                .then(literal("last-death")
                        .executes(context -> teleportToLastDeath(context.getSource())))
                .then(literal("recall")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> recallCompanion(context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(literal("teleport")
                        .then(literal("death")
                                .executes(context -> teleportToLastDeath(context.getSource()))))
                .then(literal("death")
                        .then(literal("teleport")
                                .executes(context -> teleportToLastDeath(context.getSource()))))
                .then(literal("companions")
                        .then(literal("recall")
                                .executes(context -> recallCompanions(context.getSource()))
                                .then(argument("name", StringArgumentType.word())
                                        .executes(context -> recallCompanion(context.getSource(),
                                                StringArgumentType.getString(context, "name"))))))
                .then(literal("autosprint")
                        .then(literal("north").executes(context -> autoSprint(context.getSource(), AutoSprintDirection.NORTH)))
                        .then(literal("south").executes(context -> autoSprint(context.getSource(), AutoSprintDirection.SOUTH)))
                        .then(literal("east").executes(context -> autoSprint(context.getSource(), AutoSprintDirection.EAST)))
                        .then(literal("west").executes(context -> autoSprint(context.getSource(), AutoSprintDirection.WEST)))
                        .then(literal("stop").executes(context -> stopAutoSprint(context.getSource()))))
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

    private static int autoSprint(CommandSourceStack source, AutoSprintDirection direction) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Minecraft-AI] Auto sprint must be run by a player."));
            return 0;
        }
        if (!AutoSprintController.INSTANCE.start(player, direction)) {
            source.sendFailure(Component.literal("[Minecraft-AI] Auto sprint needs the Minecraft-AI client mod and could not start Baritone."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] Auto-sprinting " + direction.name().toLowerCase(java.util.Locale.ROOT)
                + ". Use /minecraft-ai autosprint stop to stop."), false);
        return 1;
    }

    private static int stopAutoSprint(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Minecraft-AI] Auto sprint must be stopped by the player using it."));
            return 0;
        }
        boolean stopped = AutoSprintController.INSTANCE.stop(player);
        source.sendSuccess(() -> Component.literal(stopped
                ? "[Minecraft-AI] Auto sprint stopped; first-person view restored."
                : "[Minecraft-AI] Auto sprint was not running; first-person view restored."), false);
        return 1;
    }

    /** Teleports the caller near, rather than onto, their own vanilla last-death location. */
    private static int teleportToLastDeath(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Minecraft-AI] Teleport to death must be run by a player."));
            return 0;
        }
        GlobalPos death = player.getLastDeathLocation().orElse(null);
        if (death == null) {
            source.sendFailure(Component.literal("[Minecraft-AI] You do not have a recorded death location yet."));
            return 0;
        }
        ServerLevel deathWorld = source.getServer().getLevel(death.dimension());
        if (deathWorld == null) {
            source.sendFailure(Component.literal("[Minecraft-AI] Your last-death dimension is not available."));
            return 0;
        }
        return TeleportSafety.findNearestHostileSafeStandable(deathWorld, death.pos(), 48, 8, 8)
                .map(landing -> {
                    player.teleportTo(deathWorld, landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D,
                            java.util.Set.of(), player.getYRot(), player.getXRot(), true);
                    source.sendSuccess(() -> Component.literal("[Minecraft-AI] Teleported to a safe spot near your last death "
                            + "at " + landing.toShortString() + "."), false);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("[Minecraft-AI] No safe landing away from hostile mobs was found "
                            + "near your last death."));
                    return 0;
                });
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
