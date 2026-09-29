package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shared target lookup for the follow task family: an explicit player-manager lookup by name, or
 * (when no name was given) whichever player owns the bot.  Used by both {@link FollowTask} and
 * {@link BoatFollowTask}, which must otherwise keep two copies of the same resolver in sync.
 */
final class FollowTargetResolver {
    private FollowTargetResolver() {
    }

    /** Normalizes a possibly-null, possibly-blank requested target name for storage. */
    static String normalize(String targetName) {
        return targetName == null ? "" : targetName.trim();
    }

    static Optional<ServerPlayer> resolve(AIPlayerEntity bot, String targetName) {
        if (!targetName.isBlank()) {
            return Optional.ofNullable(bot.level().getServer().getPlayerList().getPlayerByName(targetName));
        }
        return AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.level().getServer().getPlayerList().getPlayer(uuid));
    }
}
