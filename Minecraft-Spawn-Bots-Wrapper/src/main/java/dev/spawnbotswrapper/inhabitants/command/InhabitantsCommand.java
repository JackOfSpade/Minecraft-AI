package dev.spawnbotswrapper.inhabitants.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Registers the admin/testing command tree {@code /inhabitants ...} (alias {@code /pvpbot_inhabitants}).
 * <pre>
 *   info | adapter | structure here | nearby [radiusChunks] | process nearest [roll|occupied|abandoned]
 *   reset here|nearest [removeBots] | reset structure &lt;structureId&gt; &lt;chunkX&gt; &lt;chunkZ&gt; [removeBots]
 *   profile &lt;botName&gt; | catalog [category] | reload
 * </pre>
 * This class is only the thin Brigadier layer: it declares the tree, applies the permission requirement,
 * and turns every run into "look up the services, call {@link CommandActions}, never throw". What the
 * commands say is built by the pure formatters; what they do is in {@code CommandActions}.
 * <p>
 * Every node carries the same requirement ({@link PermissionLevels#requirement}), evaluated per use against
 * the current config, so {@code commandPermissionLevel} changes with a reload, and {@code debugCommands=false}
 * makes every node refuse everybody even if the tree was registered - except {@code reload} itself, which
 * uses {@link PermissionLevels#reloadRequirement} instead so an operator always has a way to turn
 * {@code debugCommands} back on without a server restart. Since Brigadier requires every ANCESTOR on a path
 * to pass its own requirement (not just the leaf), the root and the alias entry point carry the WIDER of the
 * two requirements ({@link PermissionLevels#reloadRequirement}{@code .or(}{@link
 * PermissionLevels#requirement}{@code )}) so that traversal towards {@code reload} is never blocked before
 * it is even reached; the bare command's help text is reachable under that same wider check, which is a
 * deliberately accepted, harmless side effect. The alias must carry its own requirement too: Brigadier does
 * not re-check the requirement of a redirect's target node.
 */
public final class InhabitantsCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandArgs.ALIAS);
    static final String NOT_READY = "PvP BOT Inhabitants is not ready yet: the server is still starting. "
            + "Try again in a moment.";

    private InhabitantsCommand() {
    }

    /**
     * @param services yields the services once the server has finished starting, null before that (the
     *                 commands then answer "not ready yet" instead of failing)
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, Supplier<CommandServices> services) {
        register(dispatcher, services, Backends.standard());
    }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher, Supplier<CommandServices> services,
                         Backends backends) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(services, "services");
        Tree tree = new Tree(services, backends);
        LiteralCommandNode<ServerCommandSource> root = dispatcher.register(tree.root());
        dispatcher.register(tree.topLevelLiteral(CommandArgs.ALIAS).executes(tree.help(CommandArgs.ALIAS)).redirect(root));
    }

    /** What one command node does, given the actions for the current services and the way to answer. */
    @FunctionalInterface
    private interface Handler {
        int handle(CommandActions actions, Reply reply, CommandContext<ServerCommandSource> ctx);
    }

    /** {@code reset} variants share the trailing optional {@code removeBots}. */
    @FunctionalInterface
    private interface ResetHandler {
        int handle(CommandActions actions, Reply reply, CommandContext<ServerCommandSource> ctx, boolean removeBots);
    }

    private static final class Tree {
        private final Supplier<CommandServices> services;
        private final Backends backends;
        private final Predicate<ServerCommandSource> requirement;
        private final Predicate<ServerCommandSource> reloadRequirement;
        /**
         * Brigadier requires every ANCESTOR on the path to a node to pass its own {@code .requires()}, not
         * just the leaf, so a node that has {@code reload} as a descendant (the root, and the alias, which
         * is its own entry point) must accept whichever of the two lets that specific traversal continue.
         * Each leaf still enforces its own, narrower requirement, so this union widens reachability, not
         * what actually executes.
         */
        private final Predicate<ServerCommandSource> topLevelRequirement;

        Tree(Supplier<CommandServices> services, Backends backends) {
            this.services = services;
            this.backends = backends;
            this.requirement = PermissionLevels.requirement(services);
            this.reloadRequirement = PermissionLevels.reloadRequirement(services);
            this.topLevelRequirement = requirement.or(reloadRequirement);
        }

        LiteralArgumentBuilder<ServerCommandSource> literal(String name) {
            return LiteralArgumentBuilder.<ServerCommandSource>literal(name).requires(requirement);
        }

        /** The root ("inhabitants") or an alias entry point: see {@link #topLevelRequirement}. */
        LiteralArgumentBuilder<ServerCommandSource> topLevelLiteral(String name) {
            return LiteralArgumentBuilder.<ServerCommandSource>literal(name).requires(topLevelRequirement);
        }

        /**
         * {@code reload} carries its own requirement ({@link PermissionLevels#reloadRequirement}) instead of
         * the shared one: it must stay reachable at the configured permission level even when
         * {@code debugCommands=false}, or turning that flag off would need a server restart to undo.
         */
        LiteralArgumentBuilder<ServerCommandSource> reloadLiteral() {
            return LiteralArgumentBuilder.<ServerCommandSource>literal("reload").requires(reloadRequirement);
        }

        <T> RequiredArgumentBuilder<ServerCommandSource, T> argument(String name, ArgumentType<T> type) {
            return RequiredArgumentBuilder.<ServerCommandSource, T>argument(name, type).requires(requirement);
        }

        LiteralArgumentBuilder<ServerCommandSource> root() {
            return topLevelLiteral(CommandArgs.ROOT)
                    .executes(help(CommandArgs.ROOT))
                    .then(literal("info").executes(run((a, r, c) -> a.info(r))))
                    .then(literal("adapter").executes(run((a, r, c) -> a.adapter(r))))
                    .then(literal("structure")
                            .then(literal("here").executes(run((a, r, c) -> a.structureHere(sender(c), r)))))
                    .then(nearby())
                    .then(process())
                    .then(reset())
                    .then(literal("profile")
                            .then(argument(CommandArgs.ARG_BOT, StringArgumentType.word())
                                    .suggests(Suggesters.botNames(services, backends.senders()))
                                    .executes(run((a, r, c) -> a.profile(
                                            StringArgumentType.getString(c, CommandArgs.ARG_BOT), r)))))
                    .then(literal("catalog")
                            .executes(run((a, r, c) -> a.catalog(null, r)))
                            .then(argument(CommandArgs.ARG_CATEGORY, StringArgumentType.word())
                                    .suggests(Suggesters.categories())
                                    .executes(run((a, r, c) -> a.catalog(
                                            StringArgumentType.getString(c, CommandArgs.ARG_CATEGORY), r)))))
                    .then(reloadLiteral().executes(run(this::reload)));
        }

        private LiteralArgumentBuilder<ServerCommandSource> nearby() {
            return literal("nearby")
                    .executes(run((a, r, c) -> a.nearby(sender(c), CommandArgs.DEFAULT_NEARBY_RADIUS, r)))
                    .then(argument(CommandArgs.ARG_RADIUS, IntegerArgumentType.integer(1, CommandArgs.MAX_RADIUS))
                            .executes(run((a, r, c) -> a.nearby(sender(c),
                                    IntegerArgumentType.getInteger(c, CommandArgs.ARG_RADIUS), r))));
        }

        private LiteralArgumentBuilder<ServerCommandSource> process() {
            LiteralArgumentBuilder<ServerCommandSource> nearest = literal("nearest")
                    .executes(run((a, r, c) -> a.processNearest(sender(c), ForceMode.ROLL, r)));
            for (ForceMode mode : ForceMode.values()) {
                nearest.then(literal(CommandArgs.modeLiteral(mode))
                        .executes(run((a, r, c) -> a.processNearest(sender(c), mode, r))));
            }
            return literal("process").then(nearest);
        }

        private LiteralArgumentBuilder<ServerCommandSource> reset() {
            return literal("reset")
                    .then(withRemoveBots(literal("here"),
                            (a, r, c, remove) -> a.resetHere(sender(c), remove, r)))
                    .then(withRemoveBots(literal("nearest"),
                            (a, r, c, remove) -> a.resetNearest(sender(c), remove, r)))
                    .then(literal("structure")
                            .then(argument(CommandArgs.ARG_STRUCTURE, IdentifierArgumentType.identifier())
                                    .suggests(Suggesters.structureIds())
                                    .then(argument(CommandArgs.ARG_CHUNK_X, chunkCoordinate())
                                            .suggests(Suggesters.chunkX(services, backends.senders()))
                                            .then(withRemoveBots(
                                                    argument(CommandArgs.ARG_CHUNK_Z, chunkCoordinate())
                                                            .suggests(Suggesters.chunkZ(services, backends.senders())),
                                                    (a, r, c, remove) -> a.resetStructure(
                                                            sender(c),
                                                            IdentifierArgumentType.getIdentifier(c, CommandArgs.ARG_STRUCTURE).toString(),
                                                            IntegerArgumentType.getInteger(c, CommandArgs.ARG_CHUNK_X),
                                                            IntegerArgumentType.getInteger(c, CommandArgs.ARG_CHUNK_Z),
                                                            remove, r))))));
        }

        private static IntegerArgumentType chunkCoordinate() {
            return IntegerArgumentType.integer(-CommandArgs.MAX_CHUNK, CommandArgs.MAX_CHUNK);
        }

        /** Runs {@code handler} with {@code removeBots=false} at {@code node}, and with true after the literal. */
        private <B extends ArgumentBuilder<ServerCommandSource, B>> B withRemoveBots(B node, ResetHandler handler) {
            return node
                    .executes(run((a, r, c) -> handler.handle(a, r, c, false)))
                    .then(literal(CommandArgs.FLAG_REMOVE_BOTS)
                            .executes(run((a, r, c) -> handler.handle(a, r, c, true))));
        }

        private Sender sender(CommandContext<ServerCommandSource> ctx) {
            return backends.senders().apply(ctx.getSource());
        }

        private int reload(CommandActions actions, Reply reply, CommandContext<ServerCommandSource> ctx) {
            int result = actions.reload(reply);
            refreshCommandTrees(ctx.getSource());
            return result;
        }

        /** The bare command lists the subcommands; it needs no services, so it works before the server is ready. */
        Command<ServerCommandSource> help(String rootLiteral) {
            return ctx -> {
                Reply.to(ctx.getSource()).lines(AdminFormatter.help(rootLiteral));
                return 1;
            };
        }

        private Command<ServerCommandSource> run(Handler handler) {
            return ctx -> execute(ctx, handler);
        }

        private int execute(CommandContext<ServerCommandSource> ctx, Handler handler) {
            Reply reply = Reply.to(ctx.getSource());
            CommandServices current;
            try {
                current = services.get();
            } catch (RuntimeException e) {
                current = null;
            }
            if (current == null) {
                reply.error(NOT_READY);
                return 0;
            }
            try {
                return handler.handle(new CommandActions(current, backends), reply, ctx);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("Command '{}' failed", ctx.getInput(), e);
                reply.error("The command failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + " (details are in the server log)");
                return 0;
            }
        }
    }

    /**
     * After a config reload the permission level may have changed; Minecraft only sends the command tree
     * (which tab completion is built from) at join and on op changes, so resend it to everybody online.
     */
    private static void refreshCommandTrees(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (server == null || server.getPlayerManager() == null) {
            return;
        }
        try {
            PlayerManager players = server.getPlayerManager();
            for (ServerPlayerEntity player : players.getPlayerList()) {
                players.sendCommandTree(player);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Could not refresh the command trees after a reload", e);
        }
    }
}
