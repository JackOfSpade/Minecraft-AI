package io.github.zoyluo.minecraftai.gametest;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;

/**
 * Mock players for GameTests that need a human on the scene (an owner, an attacker, a followed player).
 *
 * <p>{@code GameTestHelper.makeMockServerPlayerInLevel()} gives a {@link ServerPlayer} whose {@code gameMode()} method is overridden
 * to return CREATIVE and whose connection is an {@code EmbeddedChannel}. It is placed in the level but NOT ticked by anything:
 * {@code tickCount}, {@code swingTime} and {@code useItemRemaining} never advance, and the connection's "client loaded" countdown
 * never runs, so a fresh mock is protected as a not-yet-loaded client (invulnerable to every damage source) until it is marked
 * loaded. {@link #survivalMock} therefore switches it to SURVIVAL and marks it loaded, which makes it take damage like a player.</p>
 *
 * <p>{@code isCreative()} of {@link ServerPlayer} reads the {@code ServerPlayerGameMode}, so it is false after
 * {@code setGameMode(SURVIVAL)}; the overridden {@code gameMode()} keeps answering CREATIVE, so production code must not decide
 * anything from {@code gameMode()} or {@code isCreative()} of a player (it must treat a mock as any other human), and must never
 * read time from a mock's {@code tickCount} (use the level game time).</p>
 */
public final class MockPlayers {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private MockPlayers() {
    }

    /**
     * A mock player in the level with its own, unique profile name and UUID, removed from the server when the test ends.
     *
     * <p>{@code GameTestHelper.makeMockServerPlayerInLevel()} names every mock {@code test-mock-player}, never removes it, and the
     * server's name lookups ({@code PlayerList.getPlayerByName}, which {@code FollowTask} uses) return the OLDEST player with a
     * name. A follower told to follow {@code test-mock-player} therefore followed a mock a finished test had left behind, kilometres
     * away. This is the same construction as the vanilla helper (a {@link ServerPlayer} whose {@code gameMode()} answers CREATIVE,
     * on an {@link EmbeddedChannel} connection, placed by {@code PlayerList.placeNewPlayer}), with a per-mock name and a
     * disconnect at the end of the test. Every mock in a GameTest must come from here, never from the vanilla helper.</p>
     */
    public static ServerPlayer mock(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        MinecraftServer server = level.getServer();
        UUID uuid = UUID.randomUUID();
        // A profile name is at most 16 characters: "mk" + a process-wide counter + the UUID's leading hex digits.
        String name = "mk" + SEQUENCE.incrementAndGet() + "_" + uuid.toString().replace("-", "").substring(0, 6);
        GameProfile profile = new GameProfile(uuid, name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer mock = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public GameType gameMode() {
                return GameType.CREATIVE;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, mock, cookie);
        CREATED.add(mock);
        GameTestCleanup.whenFinished(context, () -> disconnect(server, mock));
        return mock;
    }

    /** Every mock this class made that may still be connected (server thread only). */
    private static final java.util.Set<ServerPlayer> CREATED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Disconnects every mock a finished test left behind; returns how many there were. */
    static int disconnectLeaked(MinecraftServer server) {
        int leaked = 0;
        for (ServerPlayer mock : new java.util.ArrayList<>(CREATED)) {
            if (server.getPlayerList().getPlayer(mock.getUUID()) != null) {
                leaked++;
                disconnect(server, mock);
            }
        }
        CREATED.clear();
        return leaked;
    }

    /** Removes a mock from the server; safe to call twice and for a mock a fixture already disconnected. */
    public static void disconnect(MinecraftServer server, ServerPlayer mock) {
        if (server.getPlayerList().getPlayer(mock.getUUID()) == null) {
            return;
        }
        try {
            mock.connection.onDisconnect(new DisconnectionDetails(Component.literal("gametest done")));
        } catch (RuntimeException failed) {
            mock.discard();
        }
    }

    /** A mock player in SURVIVAL, at full health and able to take damage. */
    public static ServerPlayer survivalMock(GameTestHelper context) {
        ServerPlayer mock = mock(context);
        mock.setGameMode(GameType.SURVIVAL);
        // Not ticked: the client-loaded countdown never runs, so tell the listener the (imaginary) client has loaded.
        if (!mock.connection.hasClientLoaded()) {
            mock.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        }
        mock.setHealth(mock.getMaxHealth());
        return mock;
    }

    /**
     * A fresh swing of the mock's main hand as an observer sees it ({@code swinging} with {@code swingTime == 0}). A mock is not
     * ticked, so the swing stays "fresh" until {@link #endSwing}; call {@code endSwing} then {@code swingOnce} for the next swing.
     */
    public static void swingOnce(ServerPlayer mock) {
        mock.swinging = true;
        mock.swingTime = 0;
        mock.swingingArm = InteractionHand.MAIN_HAND;
    }

    /** Ends the swing started by {@link #swingOnce}. */
    public static void endSwing(ServerPlayer mock) {
        mock.swinging = false;
        mock.swingTime = 0;
    }

    /**
     * Starts drawing the item in {@code hand} (a bow or crossbow the caller has put there). The draw does not advance on its own
     * (the mock is not ticked); {@link #advanceDraw} moves it on.
     */
    public static void startDraw(ServerPlayer mock, InteractionHand hand) {
        mock.startUsingItem(hand);
    }

    /** Advances a draw started by {@link #startDraw} by {@code ticks} (a mock is not ticked, so this is how a bow gets drawn). */
    public static void advanceDraw(ServerPlayer mock, int ticks) {
        try {
            Field remaining = LivingEntity.class.getDeclaredField("useItemRemaining");
            remaining.setAccessible(true);
            remaining.setInt(mock, remaining.getInt(mock) - ticks);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("LivingEntity.useItemRemaining is not reachable", exception);
        }
    }

    /** Turns the mock's body and head to look at the eyes of {@code target}. */
    public static void faceTowards(ServerPlayer mock, Entity target) {
        mock.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        mock.setYHeadRot(mock.getYRot());
    }

    /**
     * Makes {@code owner} the owner of {@code bot} the way {@code AIPlayerManager.spawn(..., ownerUuid)} does (the manager keeps the
     * mapping in two private maps and has no setter), for a bot that was spawned without one.
     */
    public static void ownerFor(ServerPlayer owner, AIPlayerEntity bot) {
        try {
            Field ownersField = AIPlayerManager.class.getDeclaredField("botOwners");
            Field indexField = AIPlayerManager.class.getDeclaredField("ownerIndex");
            ownersField.setAccessible(true);
            indexField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, UUID> owners = (Map<UUID, UUID>) ownersField.get(AIPlayerManager.INSTANCE);
            @SuppressWarnings("unchecked")
            Map<UUID, LinkedHashSet<UUID>> index = (Map<UUID, LinkedHashSet<UUID>>) indexField.get(AIPlayerManager.INSTANCE);
            owners.put(bot.getUUID(), owner.getUUID());
            index.computeIfAbsent(owner.getUUID(), ignored -> new LinkedHashSet<>()).add(bot.getUUID());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("AIPlayerManager owner maps are not reachable", exception);
        }
    }

    /** A survival mock registered as the owner of {@code bot} ({@link #survivalMock} plus {@link #ownerFor}). */
    public static ServerPlayer ownerFor(GameTestHelper context, AIPlayerEntity bot) {
        ServerPlayer owner = survivalMock(context);
        ownerFor(owner, bot);
        return owner;
    }
}
