package io.github.zoyluo.minecraftai.integration;

import io.github.zoyluo.minecraftai.network.PlayerKind;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional fabric-permissions-api integration. This class references the API and is therefore loaded only
 * through {@link #registerIfPresent()}'s guarded call: the mod starts fine without the API (production gets
 * it from VeinMiner's jar-in-jar; it is compile-only here). It answers exactly one node,
 * {@link VeinMinerPermissionPolicy#NODE}: no for bots, yes for human players.
 */
public final class PermissionsIntegration {
    private static final String API_MOD_ID = "fabric-permissions-api-v0";

    private PermissionsIntegration() {
    }

    /** Registers the listener when the API is present; safe to call unconditionally. */
    public static void registerIfPresent() {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(API_MOD_ID)) {
            Listener.register();
        }
    }

    /** Holds every reference to the API, so nothing touches it unless {@link #registerIfPresent()} decided to. */
    private static final class Listener {
        static void register() {
            PermissionCheckEvent.EVENT.register(Listener::onPermissionCheck);
        }

        static TriState onPermissionCheck(SharedSuggestionProvider source, String permission) {
            ServerPlayer player = source instanceof CommandSourceStack stack && stack.getEntity() instanceof ServerPlayer p ? p : null;
            return VeinMinerPermissionPolicy.decide(permission, player != null, player != null && PlayerKind.isBot(player));
        }
    }
}
