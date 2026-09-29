package io.github.zoyluo.minecraftai.network;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Tells server-side "bot" players apart from real humans, so that vanilla rules that count every
 * {@link ServerPlayerEntity} (the sleep vote) can be limited to humans.
 *
 * <p>A player is a bot when it is one of our {@link AIPlayerEntity} bots, or when its connection runs
 * over a netty {@link EmbeddedChannel}: that is how every fake-player mod (this one and PvP BOT's
 * HeroBot alike) fakes a client that never touches a socket. A real player's channel is a socket
 * (multiplayer, LAN) or a {@code LocalChannel} (singleplayer), never an embedded one. No other mod's
 * class is referenced.
 */
public final class PlayerKind {
    private PlayerKind() {
    }

    /** True for a channel that has no real client behind it. */
    public static boolean isBotChannel(Channel channel) {
        return channel instanceof EmbeddedChannel;
    }

    public static boolean isBot(ServerPlayerEntity player) {
        if (player instanceof AIPlayerEntity) {
            return true;
        }
        ServerPlayNetworkHandler handler = player.networkHandler;
        if (!(handler instanceof ServerConnectionAccessor accessor)) {
            return false;
        }
        ClientConnection connection = accessor.minecraftai$getConnection();
        return connection instanceof ClientConnectionAccessor channelAccess
                && isBotChannel(channelAccess.minecraftai$getChannel());
    }

    /**
     * The entries of {@code players} that are not bots, in order. Returns {@code players} itself
     * (no allocation) when it holds no bot, which is the common case on a server without bots.
     */
    public static <T> List<T> withoutBots(List<T> players, Predicate<? super T> isBot) {
        List<T> humans = null;
        for (int i = 0; i < players.size(); i++) {
            T player = players.get(i);
            if (isBot.test(player)) {
                if (humans == null) {
                    humans = new ArrayList<>(players.subList(0, i));
                }
            } else if (humans != null) {
                humans.add(player);
            }
        }
        return humans == null ? players : humans;
    }

    public static List<ServerPlayerEntity> humansOnly(List<ServerPlayerEntity> players) {
        return withoutBots(players, PlayerKind::isBot);
    }
}
