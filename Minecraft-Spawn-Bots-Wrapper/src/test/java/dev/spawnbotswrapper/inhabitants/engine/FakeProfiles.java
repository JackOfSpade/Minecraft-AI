package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.sample.CoverageSampler;
import dev.spawnbotswrapper.inhabitants.sample.DeckStore;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A profile generator whose output depends on BOTH the seed and the state of the deck store it draws from
 * (through the real coverage sampler), so tests can tell whether the engine feeds it the right decks in the
 * right order.
 */
final class FakeProfiles implements ProfileFactory {

    record Call(long seed, DeckStore decks, GlobalCapabilities capabilities) {
    }

    final List<Call> calls = new ArrayList<>();
    boolean throwOnCreate;
    boolean returnNull;

    @Override
    public BotProfile create(long botSeed, GlobalCapabilities capabilities, DeckStore decks) {
        calls.add(new Call(botSeed, decks, capabilities));
        if (throwOnCreate) {
            throw new IllegalStateException("injected profile failure");
        }
        if (returnNull) {
            return null;
        }
        CoverageSampler s = new CoverageSampler(new SplitMix64(botSeed), decks, 8);
        int slot = s.nextInt("slot", 0, 9999);
        double speed = s.nextDouble("speed", 0.1, 2.0);
        boolean patrols = s.nextBoolean("patrols");
        BotProfile.Behavior behavior = patrols
                ? new BotProfile.Behavior(BotProfile.Stance.PATROL_PINGPONG, true, BotProfile.WalkType.WALK, 6.0, 3, List.of())
                : BotProfile.Behavior.standing();
        return new BotProfile(BotProfile.CURRENT_VERSION, botSeed, "fake-" + slot,
                new BotProfile.Loadout(List.of(new BotProfile.PlacedItem(BotProfile.Slot.HOTBAR, 0,
                        BotProfile.ItemSpec.of("minecraft:stone_sword")))),
                new BotProfile.Vitals(Math.min(1.0, speed / 2.0), 20, Map.of()),
                behavior);
    }
}
