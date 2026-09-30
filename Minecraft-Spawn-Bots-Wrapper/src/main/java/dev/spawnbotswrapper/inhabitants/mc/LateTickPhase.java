package dev.spawnbotswrapper.inhabitants.mc;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.resources.Identifier;

/**
 * Registers a listener so that it runs AFTER every listener in the event's default phase.
 * <p>
 * Why this exists: PvP BOT ticks all of its bots from a listener it registers on
 * {@code ServerTickEvents.END_SERVER_TICK} in the default phase ({@code BotTicker.register}), and its input
 * (idle wandering, patrol movement, combat movement) is written during that listener. Two listeners of the same
 * phase run in registration order, which follows mod load order and is not something this addon controls. The
 * aggro walk back writes its own look and move input and must be the LAST writer, so it goes in a phase ordered
 * after {@link Event#DEFAULT_PHASE}: Fabric guarantees every listener of an earlier phase runs before every
 * listener of a later one, whatever the mod load order.
 */
public final class LateTickPhase {
    /** The phase after the default one. */
    public static final Identifier PHASE = Identifier.fromNamespaceAndPath("pvpbot_inhabitants", "after_default");

    private LateTickPhase() {
    }

    /** Orders {@link #PHASE} after the default phase of {@code event} and registers {@code listener} in it. */
    public static <T> void register(Event<T> event, T listener) {
        event.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        event.register(PHASE, listener);
    }
}
