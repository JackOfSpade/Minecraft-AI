package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.network.AINetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(PlayerList.class)
public abstract class PlayerManagerFakePlayerMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    /**
     * Was a {@code @Redirect} on the {@code new ServerGamePacketListenerImpl(...)} call itself, which
     * exclusively claims that one call site. That is incompatible with any other mod hooking the same
     * vanilla constructor for its own fake players (confirmed: PvP BOT's HeroBot does exactly this, and
     * its own mixin does not tolerate losing the resulting Mixin conflict -- the whole server refused to
     * start with both mods installed). {@code @ModifyVariable} instead reads and can replace the local
     * right after the vanilla call already assigned it, which is a different injection point that never
     * competes for the constructor call, so any other mod's own redirect of that call keeps working and
     * this mixin only overrides its result for our own bots.
     */
    @ModifyVariable(method = "placeNewPlayer", at = @At("STORE"), ordinal = 0)
    private ServerGamePacketListenerImpl minecraftai$replaceNetworkHandler(ServerGamePacketListenerImpl handler,
                                                                  Connection connection,
                                                                  ServerPlayer player,
                                                                  CommonListenerCookie clientData) {
        if (player instanceof AIPlayerEntity fakePlayer) {
            return new AINetworkHandler(this.server, connection, fakePlayer, clientData);
        }
        return handler;
    }
}
