package dev.spawnbotswrapper.inhabitants.mc;

/**
 * Derives the fixed id of the attribute modifier the addon applies for each profile attribute.
 * <p>
 * The id must be stable, not random: vanilla refuses to add a modifier whose id is already present, and
 * player attribute modifiers are saved with the player, so applying a profile a second time (after a
 * restart, or by an admin re-apply) must REPLACE the earlier modifier rather than stack another on top. The
 * shape is {@code pvpbot_inhabitants:profile/<attribute>}, with the attribute's namespace inserted for
 * anything that is not a vanilla attribute so modded attributes stay readable in the player's NBT.
 */
public final class ModifierIds {
    /** Namespace of every modifier id the addon creates. */
    public static final String NAMESPACE = "pvpbot_inhabitants";
    private static final String VANILLA = "minecraft";

    private ModifierIds() {
    }

    /** The path part of the modifier id for the attribute {@code namespace:path}. */
    public static String pathFor(String namespace, String path) {
        return VANILLA.equals(namespace) ? "profile/" + path : "profile/" + namespace + "/" + path;
    }
}
