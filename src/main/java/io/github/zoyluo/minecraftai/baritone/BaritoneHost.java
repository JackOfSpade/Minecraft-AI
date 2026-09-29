package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.utils.HostEnvironment;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

/**
 * Entry point of the Baritone integration: one {@link IBaritone} per bot.
 *
 * <p>Baritone is vendored pristine (third_party/baritone) and compiled server-only from the patch series in
 * tools/baritone; everything Minecraft-client-shaped that upstream expects is supplied by this package
 * ({@link ServerPlayerContext}, {@link ServerPlayerController}, {@link LoadedChunkSnapshot}) and by the
 * {@code Baritone*Mixin} / {@code ServerChunkCacheBaritoneMixin} classes in the mixin package.</p>
 *
 * <p>Nothing in the mod calls this yet: creating an instance is opt-in, so shipping the integration changes no
 * behavior.</p>
 */
public final class BaritoneHost {
    private static boolean configured;

    private BaritoneHost() {
    }

    /**
     * Creates the Baritone instance that drives {@code bot}. The supplier form lets the caller hand over "the current
     * entity of this bot" so the instance survives the entity being replaced.
     */
    public static IBaritone create(Supplier<? extends AIPlayerEntity> bot) {
        configure(bot.get().getServer());
        return BaritoneAPI.getProvider().createBaritone(baritone -> new ServerPlayerContext(baritone, bot));
    }

    public static IBaritone create(AIPlayerEntity bot) {
        return create(() -> bot);
    }

    /** The instance controlling {@code player}, or null if it has none. */
    public static IBaritone of(Player player) {
        return BaritoneAPI.getProvider().getBaritoneForPlayer(player);
    }

    public static boolean destroy(IBaritone baritone) {
        return BaritoneAPI.getProvider().destroyBaritone(baritone);
    }

    /**
     * Points Baritone at {@code server} and applies the server defaults. Safe to call again (a later call re-points it at
     * the newer server, which matters when an integrated server is restarted in the same JVM); {@link #create} calls it, and
     * so should anything that uses Baritone's API without a bot yet (for example {@code BlockOptionalMeta}).
     *
     * <p>Must run before the first touch of {@code BaritoneAPI}: its static initializer reads
     * {@code <game dir>/baritone/settings.txt}, which is why the game directory is set first.</p>
     */
    public static synchronized void configure(MinecraftServer server) {
        HostEnvironment.setGameDirectory(FabricLoader.getInstance().getGameDir());
        // Block drops are learned from the server's own loot tables; Baritone's fallback (a private registry-only level) reloads
        // every data-pack registry and cannot run inside a Fabric server.
        HostEnvironment.setLootLevel(server::overworld);
        if (configured) {
            return;
        }
        Settings settings = BaritoneAPI.getSettings();
        // A bot's yaw is its real yaw (ActionPack and vanilla movement read it directly), so Baritone must set it for
        // real instead of the "free look" trick that only changes the direction of the next move.
        settings.freeLook.value = false;
        settings.blockFreeLook.value = false;
        // There is no chat, toast or desktop to tell.
        settings.desktopNotifications.value = false;
        settings.logAsToast.value = false;
        settings.chatControl.value = false;
        configured = true;
    }
}
