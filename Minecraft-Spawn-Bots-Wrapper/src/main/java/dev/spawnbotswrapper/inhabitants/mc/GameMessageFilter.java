package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.text.Text;

import java.util.function.Supplier;

/**
 * Suppresses vanilla's "X joined the game" / "X left the game" / advancement-announcement game messages for
 * this addon's own inhabitants, so a structure spawning a handful of PvP BOT fighters does not spam every
 * online player's chat -- an inhabited village should feel populated, not like a server restart log. Real
 * players, and any other mod's own bots (this project's own separate AIBot companions included), are
 * completely untouched: only names this addon itself is currently tracking as an inhabitant are ever
 * suppressed.
 * <p>
 * Registers {@link ServerMessageEvents#ALLOW_GAME_MESSAGE}, which is exactly the vanilla-plus-Fabric-API hook
 * these three message kinds already go through (via Fabric API's own {@code PlayerManager} mixin) -- no
 * mixin of our own is needed, and there is nothing here that could conflict with another mod's mixin on
 * {@code PlayerManager} the way a hand-written one could.
 * <p>
 * <b>How a message is attributed to a bot.</b> The event hands over the fully rendered {@link Text}, not the
 * player who triggered it. Vanilla's join/leave/advancement messages are always {@code "<name> ..."} (a
 * Minecraft username can never contain a space), so the first whitespace-delimited token of the rendered
 * string is the player name; that gets checked against {@link CommandServices#population()}'s live bot
 * registry ({@link dev.spawnbotswrapper.inhabitants.engine.PopulationView#findBot}), which is name-keyed
 * (case-insensitive) for exactly this kind of lookup. A bot's name is reserved and persisted before it ever
 * spawns, so this reliably catches its very first join message, and a name that was ever a bot's stays
 * findable (and therefore stays suppressed) even long after that bot has died -- structures are never
 * repopulated, so there is nothing to "un-suppress" later.
 */
public final class GameMessageFilter {
    private GameMessageFilter() {
    }

    /** Registers the filter; call once from the mod entrypoint. */
    public static void register(Supplier<CommandServices> services) {
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> {
            String rendered = message.getString();
            CommandServices current = services.get();
            return !isFromOurBot(current, rendered);
        });
    }

    /**
     * The decision, pure and independent of any real {@link Text}: a rendered vanilla join/leave/advancement
     * line is always {@code "<name> ..."} (a Minecraft username can never contain a space), so the first
     * whitespace-delimited token is the player name, checked against the live bot registry. Package-visible
     * so it can be unit tested directly against a fake {@link CommandServices} -- everything it touches is
     * plain Java, nothing here needs a running Minecraft instance.
     */
    static boolean isFromOurBot(CommandServices services, String renderedMessage) {
        if (services == null || renderedMessage.isEmpty()) {
            return false;
        }
        int space = renderedMessage.indexOf(' ');
        String name = space < 0 ? renderedMessage : renderedMessage.substring(0, space);
        if (name.isEmpty()) {
            return false;
        }
        return services.population().findBot(name).isPresent();
    }
}
