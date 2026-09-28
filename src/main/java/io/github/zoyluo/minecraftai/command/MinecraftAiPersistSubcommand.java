package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.persist.BotPersistence;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

public final class MinecraftAiPersistSubcommand {
    private MinecraftAiPersistSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("persist")
                .then(literal("save")
                        .executes(context -> save(context.getSource())))
                .then(literal("reload")
                        .executes(context -> reload(context.getSource())));
    }

    private static int save(ServerCommandSource source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:persist_save")) {
            return 0;
        }
        int count = BotPersistence.INSTANCE.saveAll(source.getServer());
        if (!BotPersistence.INSTANCE.lastSaveSucceeded()) {
            source.sendError(Text.literal("[Minecraft-AI] persistence failed; existing runtime file was preserved"));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[Minecraft-AI] persisted " + count + " bot(s)"), false);
        return count;
    }

    private static int reload(ServerCommandSource source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:persist_reload")) {
            return 0;
        }
        int count = BotPersistence.INSTANCE.reloadIfIdle(source.getServer());
        if (count < 0) {
            source.sendError(Text.literal("[Minecraft-AI] reload rejected: despawn all bots and clear jobs first"));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[Minecraft-AI] restored " + count + " bot(s)"), false);
        return count;
    }
}
