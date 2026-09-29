package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.persist.BotPersistence;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.literal;

public final class MinecraftAiPersistSubcommand {
    private MinecraftAiPersistSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("persist")
                .then(literal("save")
                        .executes(context -> save(context.getSource())))
                .then(literal("reload")
                        .executes(context -> reload(context.getSource())));
    }

    private static int save(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:persist_save")) {
            return 0;
        }
        int count = BotPersistence.INSTANCE.saveAll(source.getServer());
        if (!BotPersistence.INSTANCE.lastSaveSucceeded()) {
            source.sendFailure(Component.literal("[Minecraft-AI] persistence failed; existing runtime file was preserved"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] persisted " + count + " bot(s)"), false);
        return count;
    }

    private static int reload(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:persist_reload")) {
            return 0;
        }
        int count = BotPersistence.INSTANCE.reloadIfIdle(source.getServer());
        if (count < 0) {
            source.sendFailure(Component.literal("[Minecraft-AI] reload rejected: despawn all bots and clear jobs first"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Minecraft-AI] restored " + count + " bot(s)"), false);
        return count;
    }
}
