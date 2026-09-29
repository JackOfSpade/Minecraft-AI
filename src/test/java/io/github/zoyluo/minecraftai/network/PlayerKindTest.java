package io.github.zoyluo.minecraftai.network;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which players count for the sleep vote: humans only, bots (embedded-channel fake players) never. */
final class PlayerKindTest {
    @Test
    void anEmbeddedChannelIsABotConnection() {
        assertTrue(PlayerKind.isBotChannel(new EmbeddedChannel()),
                "every fake-player mod fakes its client with an embedded channel");
    }

    @Test
    void realClientChannelsAreNotBotConnections() {
        assertFalse(PlayerKind.isBotChannel(new LocalChannel()), "singleplayer / integrated server");
        NioSocketChannel socket = new NioSocketChannel();
        try {
            assertFalse(PlayerKind.isBotChannel(socket), "multiplayer / LAN");
        } finally {
            socket.unsafe().closeForcibly();
        }
        assertFalse(PlayerKind.isBotChannel(null), "a player without a connection yet is not assumed to be a bot");
    }

    @Test
    void withoutBotsKeepsTheHumansInOrder() {
        List<String> players = List.of("bot-a", "alice", "bot-b", "bob", "carol");
        assertEquals(List.of("alice", "bob", "carol"), PlayerKind.withoutBots(players, name -> name.startsWith("bot")));
    }

    @Test
    void withoutBotsHandlesLeadingTrailingAndAllBots() {
        assertEquals(List.of("alice"), PlayerKind.withoutBots(List.of("bot-a", "bot-b", "alice", "bot-c"),
                name -> name.startsWith("bot")));
        assertEquals(List.of(), PlayerKind.withoutBots(List.of("bot-a", "bot-b"), name -> name.startsWith("bot")),
                "zero humans is an empty list: vanilla then never skips the night");
        assertEquals(List.of(), PlayerKind.withoutBots(List.<String>of(), name -> true));
    }

    @Test
    void withoutBotsDoesNotCopyWhenThereIsNoBot() {
        List<String> players = List.of("alice", "bob");
        assertSame(players, PlayerKind.withoutBots(players, name -> name.startsWith("bot")));
    }
}
