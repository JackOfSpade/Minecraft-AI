package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.coordination.Job;
import io.github.zoyluo.minecraftai.coordination.TaskBoard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class MinecraftAiJobSubcommand {
    private MinecraftAiJobSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("job")
                .then(literal("post")
                        .then(argument("kind", StringArgumentType.word())
                                .executes(context -> post(context.getSource(),
                                        StringArgumentType.getString(context, "kind"),
                                        ""))
                                .then(argument("params", StringArgumentType.greedyString())
                                        .executes(context -> post(context.getSource(),
                                                StringArgumentType.getString(context, "kind"),
                                                StringArgumentType.getString(context, "params"))))))
                .then(literal("list")
                        .executes(context -> list(context.getSource())))
                .then(literal("tell")
                        .then(argument("from_bot", StringArgumentType.word())
                                .then(argument("target_bot", StringArgumentType.word())
                                        .then(argument("message", StringArgumentType.greedyString())
                                                .executes(context -> tell(context.getSource(),
                                                        StringArgumentType.getString(context, "from_bot"),
                                                        StringArgumentType.getString(context, "target_bot"),
                                                        StringArgumentType.getString(context, "message")))))))
                .then(literal("clear")
                        .executes(context -> clear(context.getSource())));
    }

    private static int post(CommandSourceStack source, String kind, String paramsText) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:job_post")) {
            return 0;
        }
        UUID id = TaskBoard.INSTANCE.postGlobal(kind, parseParams(paramsText));
        io.github.zoyluo.minecraftai.persist.BotPersistence.INSTANCE.markDirty(source.getServer());
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] job posted " + id + " kind=" + kind), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:job_list")) {
            return 0;
        }
        var jobs = TaskBoard.INSTANCE.snapshot();
        if (jobs.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[Minecraft-AI] jobs: empty"), false);
            return 0;
        }
        String text = jobs.stream()
                .map(MinecraftAiJobSubcommand::format)
                .collect(Collectors.joining(" | "));
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] jobs: " + text), false);
        return jobs.size();
    }

    private static int clear(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:job_clear")) {
            return 0;
        }
        TaskBoard.INSTANCE.clear();
        io.github.zoyluo.minecraftai.persist.BotPersistence.INSTANCE.markDirty(source.getServer());
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] jobs cleared"), false);
        return 1;
    }

    private static int tell(CommandSourceStack source, String fromBot, String targetBot, String message) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:job_tell")) {
            return 0;
        }
        var target = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, targetBot, BotAuthorizationPolicy.Operation.COMMAND, "command:job_tell_target");
        if (target.isEmpty()) {
            return 0;
        }
        // Preserve the real OP/console provenance. The legacy from_bot argument is not used as an
        // identity because that would let an administrator silently impersonate a Bot.
        boolean queued = BrainCoordinator.INSTANCE.handleMessage(target.get(), source.getTextName(), message);
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] operator tell " + (queued ? "queued" : "busy")
                + " (legacy from_bot=" + fromBot + " ignored)"), false);
        return queued ? 1 : 0;
    }

    public static Map<String, String> parseParams(String text) {
        Map<String, String> params = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return params;
        }
        for (String part : text.split("[, ]+")) {
            if (part.isBlank()) {
                continue;
            }
            int equals = part.indexOf('=');
            if (equals <= 0 || equals == part.length() - 1) {
                continue;
            }
            params.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
        }
        return params;
    }

    private static String format(Job job) {
        String shortId = job.id().toString().substring(0, 8);
        String claimant = job.claimant() == null ? "-" : job.claimant().toString().substring(0, 8);
        String reason = job.failureReason() == null || job.failureReason().isBlank() ? "" : " reason=" + job.failureReason();
        return shortId + " " + job.kind() + " status=" + job.status() + " bot=" + claimant + reason;
    }
}
