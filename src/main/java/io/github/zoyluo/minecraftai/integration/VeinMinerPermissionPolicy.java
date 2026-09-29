package io.github.zoyluo.minecraftai.integration;

import net.fabricmc.fabric.api.util.TriState;

/**
 * The pure decision behind {@link PermissionsIntegration}: who may use VeinMiner. VeinMiner (with its
 * {@code permissionRestricted} setting on) asks fabric-permissions-api for {@value #NODE}. Bots must never
 * vein-mine: the extra blocks it breaks would bypass the bot's mining controller, mining-assist hooks,
 * drop tracking and safety checks. Real players are allowed, and every other node or source is left to
 * whatever else answers (the API's own default).
 */
public final class VeinMinerPermissionPolicy {
    public static final String NODE = "veinminer.use";

    private VeinMinerPermissionPolicy() {
    }

    /**
     * @param permission the permission node being checked
     * @param isPlayer   the source is a player entity
     * @param isBot      the source is a bot player (only meaningful when {@code isPlayer})
     */
    public static TriState decide(String permission, boolean isPlayer, boolean isBot) {
        if (!NODE.equals(permission) || !isPlayer) {
            return TriState.DEFAULT;
        }
        return isBot ? TriState.FALSE : TriState.TRUE;
    }
}
