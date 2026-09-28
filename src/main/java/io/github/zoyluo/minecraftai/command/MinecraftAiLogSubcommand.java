package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.BotLogWriter;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class MinecraftAiLogSubcommand {
    private static final int DEFAULT_OVERFLOW_EVENTS = 6_000;

    private MinecraftAiLogSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("log")
                .then(literal("status")
                        .executes(context -> status(context.getSource())))
                .then(literal("rotate")
                        .executes(context -> rotate(context.getSource())))
                .then(literal("overflow")
                        .executes(context -> overflow(context.getSource(), DEFAULT_OVERFLOW_EVENTS))
                        .then(argument("count", IntegerArgumentType.integer(1, 50_000))
                                .executes(context -> overflow(context.getSource(), IntegerArgumentType.getInteger(context, "count")))));
    }

    private static int status(ServerCommandSource source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:log_status")) {
            return 0;
        }
        BotLogWriter writer = BotLogWriter.INSTANCE;
        source.sendFeedback(() -> Text.literal("[Minecraft-AI] log started="
                + writer.isStarted()
                + " queue="
                + writer.queueSize()
                + " dropped="
                + writer.droppedCount()
                + " dir="
                + writer.baseDir()), false);
        return 1;
    }

    private static int rotate(ServerCommandSource source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:log_rotate")) {
            return 0;
        }
        BotLog.config("log_rotate_requested", "source", "command");
        BotLogWriter.INSTANCE.forceRotateForTest();
        source.sendFeedback(() -> Text.literal("[Minecraft-AI] log rotation triggered"), false);
        return 1;
    }

    private static int overflow(ServerCommandSource source, int count) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:log_overflow")) {
            return 0;
        }
        BotLogWriter.INSTANCE.forceOverflowForTest(count);
        source.sendFeedback(() -> Text.literal("[Minecraft-AI] log overflow validation enqueued " + count + " events"), false);
        return 1;
    }
}
