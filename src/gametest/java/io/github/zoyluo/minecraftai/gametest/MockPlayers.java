package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
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
    private MockPlayers() {
    }

    /** A mock player in SURVIVAL, at full health and able to take damage. */
    public static ServerPlayer survivalMock(GameTestHelper context) {
        ServerPlayer mock = context.makeMockServerPlayerInLevel();
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
