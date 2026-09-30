package dev.spawnbotswrapper.inhabitants.mc;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ordering argument for the aggro walk back: PvP BOT writes its bot input from a default-phase listener of
 * END_SERVER_TICK, so this addon's listener must run after every default-phase listener no matter who registered
 * first. Proven on a real Fabric {@link Event} (same phase sorting as the server tick event).
 */
class LateTickPhaseTest {

    private static Event<Runnable> newEvent() {
        return EventFactory.createArrayBacked(Runnable.class, listeners -> () -> {
            for (Runnable r : listeners) {
                r.run();
            }
        });
    }

    @Test
    void theLateListenerRunsAfterDefaultPhaseListenersRegisteredBeforeIt() {
        List<String> order = new ArrayList<>();
        Event<Runnable> tick = newEvent();
        tick.register(() -> order.add("pvp bot"));
        LateTickPhase.register(tick, () -> order.add("aggro"));
        tick.invoker().run();
        assertEquals(List.of("pvp bot", "aggro"), order);
    }

    @Test
    void theLateListenerRunsAfterDefaultPhaseListenersRegisteredAfterIt() {
        List<String> order = new ArrayList<>();
        Event<Runnable> tick = newEvent();
        LateTickPhase.register(tick, () -> order.add("aggro"));
        tick.register(() -> order.add("pvp bot"));
        tick.invoker().run();
        assertEquals(List.of("pvp bot", "aggro"), order, "registration order must not matter (mod load order)");
    }

    @Test
    void theLateListenerRunsAfterExplicitlyNamedEarlierPhasesToo() {
        List<String> order = new ArrayList<>();
        Event<Runnable> tick = newEvent();
        Identifier early = Identifier.fromNamespaceAndPath("some_mod", "early");
        tick.addPhaseOrdering(early, Event.DEFAULT_PHASE);
        LateTickPhase.register(tick, () -> order.add("aggro"));
        tick.register(() -> order.add("default"));
        tick.register(early, () -> order.add("early"));
        tick.invoker().run();
        assertEquals(List.of("early", "default", "aggro"), order);
    }
}
