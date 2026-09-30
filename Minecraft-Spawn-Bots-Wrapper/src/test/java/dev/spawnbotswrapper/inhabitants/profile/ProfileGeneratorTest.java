package dev.spawnbotswrapper.inhabitants.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.sample.DeckStore;
import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.sample.TransientDeckStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.spawnbotswrapper.inhabitants.profile.ProfileTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Determinism, serialisation, the option switches and the generator's tolerance of odd inputs. */
class ProfileGeneratorTest {

    private static ProfileGenerator generator(InhabitantsConfig.Profiles options) {
        return new ProfileGenerator(() -> options);
    }

    @Test
    void sameSeedAndFreshDecksGiveIdenticalProfiles() {
        for (GlobalCapabilities caps : List.of(allOn(), GlobalCapabilities.upstreamDefaults(), allOff(),
                allOnExcept("maceEnabled", "rangedEnabled"))) {
            for (long seed : seeds(5, 150)) {
                BotProfile a = generator(everythingOptions()).create(seed, caps, new TransientDeckStore());
                BotProfile b = generator(everythingOptions()).create(seed, caps, new TransientDeckStore());
                assertEquals(a, b, "seed " + seed);
            }
        }
    }

    @Test
    void differentSeedsGiveDifferentProfiles() {
        Set<List<Object>> distinct = new HashSet<>();
        long[] seeds = seeds(77, 400);
        for (long seed : seeds) {
            BotProfile p = generator(everythingOptions()).create(seed, allOn(), new TransientDeckStore());
            assertEquals(seed, p.seed());
            distinct.add(List.of(p.loadout(), p.vitals(), p.behavior()));
        }
        assertTrue(distinct.size() >= seeds.length * 0.97, "only " + distinct.size() + " distinct of " + seeds.length);
    }

    @Test
    void resultDependsOnDeckStateAndTwoIdenticalStatesAgree() {
        // Warm a persistent store, then restore it twice: the same seed must then produce the same profile,
        // and it differs from what a fresh store would give (the decks are part of the input).
        ProfileGenerator generator = generator(everythingOptions());
        PersistentDeckStore warm = new PersistentDeckStore();
        for (long seed : seeds(9, 60)) {
            generator.create(seed, allOn(), warm);
        }
        var snapshot = warm.exportSnapshots();

        PersistentDeckStore first = new PersistentDeckStore();
        first.importSnapshots(snapshot);
        PersistentDeckStore second = new PersistentDeckStore();
        second.importSnapshots(snapshot);
        int different = 0;
        for (long seed : seeds(10, 40)) {
            BotProfile a = generator.create(seed, allOn(), first);
            BotProfile b = generator.create(seed, allOn(), second);
            assertEquals(a, b);
            if (!a.equals(generator.create(seed, allOn(), new TransientDeckStore()))) {
                different++;
            }
        }
        assertTrue(different > 0, "a warmed deck store should steer the result");
    }

    @Test
    void generationDoesNotDependOnAnythingButItsInputs() {
        // Interleaving other work (other bots, other generator instances) must not change a fresh-deck result.
        BotProfile alone = generator(defaultOptions()).create(4242L, allOn(), new TransientDeckStore());
        ProfileGenerator busy = generator(defaultOptions());
        for (long seed : seeds(3, 30)) {
            busy.create(seed, allOn(), new TransientDeckStore());
        }
        assertEquals(alone, busy.create(4242L, allOn(), new TransientDeckStore()));
    }

    @Test
    void deckKeysAreABoundedNamespacedSetThatDoesNotGrowWithBots() {
        // The world-wide persistent store keeps one deck per key forever: keys must not depend on the bot.
        PersistentDeckStore store = new PersistentDeckStore();
        ProfileGenerator generator = generator(everythingOptions());
        for (long seed : seeds(1, 1500)) {
            generator.create(seed, allOn(), store);
        }
        Set<String> keys = new HashSet<>(store.exportSnapshots().keySet());
        for (long seed : seeds(2, 2500)) {
            generator.create(seed, allOn(), store);
        }
        assertEquals(keys, store.exportSnapshots().keySet(), "a new key appeared after 1500 bots");
        assertTrue(keys.size() < 400, "suspiciously many keys: " + keys.size());
        for (String key : keys) {
            assertTrue(key.startsWith("profile."), key);
            assertTrue(key.matches("[A-Za-z0-9_./]+"), key);
        }
    }

    @Test
    void gsonRoundTripIsLossless() {
        Gson gson = new Gson();
        Gson pretty = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        int checked = 0;
        for (var cfg : List.of(
                new Object[]{allOn(), everythingOptions()},
                new Object[]{GlobalCapabilities.upstreamDefaults(), defaultOptions()})) {
            for (ProfileGenerator.Generation g : generate(600, (GlobalCapabilities) cfg[0],
                    (InhabitantsConfig.Profiles) cfg[1], 31L)) {
                BotProfile p = g.profile();
                assertEquals(p, gson.fromJson(gson.toJson(p), BotProfile.class));
                assertEquals(p, pretty.fromJson(pretty.toJson(p), BotProfile.class));
                checked++;
            }
        }
        assertEquals(1200, checked);
    }

    @Test
    void gsonRoundTripKeepsWaypointsAndPotions() {
        Gson gson = new Gson();
        BotProfile p = generate(1, allOn(), everythingOptions()).get(0).profile()
                .withBehavior(new BotProfile.Behavior(BotProfile.Stance.PATROL_CYCLE, false, BotProfile.WalkType.WALK,
                        12.5, 3, List.of(new BotProfile.Waypoint(1.5, 64, -3.5), new BotProfile.Waypoint(2, 64, -9))));
        assertEquals(p, gson.fromJson(gson.toJson(p), BotProfile.class));

        BotProfile potions = null;
        for (ProfileGenerator.Generation g : generate(200, allOn(), everythingOptions())) {
            if (has(g.profile(), i -> i.equals(NS + "splash_potion"))) {
                potions = g.profile();
                break;
            }
        }
        assertNotNull(potions);
        BotProfile back = gson.fromJson(gson.toJson(potions), BotProfile.class);
        assertEquals(potions, back);
        assertTrue(specs(back).stream().anyMatch(s -> s.potion() != null));
    }

    /**
     * Deterministic mode promises that a fresh copy of a world gets the same inhabitants. Immutable maps
     * iterate in a per-JVM randomised order, so this pins two whole profiles as text: it fails if any part of
     * generation ever depends on iteration order, and it flags an algorithm change (which would re-roll every
     * deterministic world) so it is a conscious decision - update the strings only when that is intended.
     */
    @Test
    void profilesAreStableAcrossJvmRuns() {
        ProfileGenerator generator = generator(defaultOptions());
        GlobalCapabilities caps = GlobalCapabilities.upstreamDefaults();
        assertEquals("Smasher | head:0=netherite_helmet+blast_protection4+thorns3~0.77"
                + " chest:0=netherite_chestplate+blast_protection3+thorns2+unbreaking3~0.46"
                + " legs:0=netherite_leggings~0.18 feet:0=netherite_boots+blast_protection4~0.67"
                + " offhand:0=shield hotbar:0=mace+breach2+unbreaking2~0.14"
                + " hotbar:8=cooked_porkchopx42 inventory:-1=totem_of_undying inventory:-1=totem_of_undying"
                + " inventory:-1=totem_of_undying inventory:-1=golden_applex2 inventory:-1=potion<healing> inventory:-1=potion<healing>"
                + " inventory:-1=potion<healing> inventory:-1=water_bucket"
                + " | hp 0.52 food 12 | STAND true bhop 0.0 0",
                fingerprint(generator.create(1L, caps, new TransientDeckStore())));
        assertEquals("Smasher | hotbar:0=mace+density5+fire_aspect1+unbreaking2+wind_burst3~0.15"
                + " hotbar:1=crossbow+quick_charge2 hotbar:8=golden_carrotx59 inventory:-1=wind_chargex31"
                + " inventory:-1=arrowx11 inventory:-1=spectral_arrowx13"
                + " inventory:-1=enchanted_golden_apple inventory:-1=potion<strong_healing>"
                + " inventory:-1=potion<strong_healing> inventory:-1=potion<strong_healing>"
                + " inventory:-1=potion<strong_healing> inventory:-1=splash_potion<swiftness>"
                + " inventory:-1=splash_potion<swiftness> inventory:-1=splash_potion<swiftness>"
                + " inventory:-1=splash_potion<swiftness> inventory:-1=cobwebx5 inventory:-1=water_bucket"
                + " | hp 0.85 food 7 | PATROL_PINGPONG true bhop 33.7 3",
                fingerprint(generator.create(987654321L, caps, new TransientDeckStore())));
    }

    /**
     * An inhabitant has the stats of a vanilla player: earlier versions rolled permanent attribute modifiers (max health,
     * reach, attack speed, knockback resistance, scale) that no item or effect stands behind, a buff or a nerf no player
     * can have. Under every option, no profile carries one.
     */
    @Test
    void noProfileCarriesAnAttributeModifier() {
        for (InhabitantsConfig.Profiles o : List.of(defaultOptions(), everythingOptions())) {
            for (BotProfile p : profiles(generate(600, allOn(), o))) {
                assertTrue(p.vitals().attributes().isEmpty(), p.vitals().toString());
            }
        }
    }

    /**
     * The four attribute rolls of the earlier versions are still drawn (and dropped): the coverage decks and the random
     * stream are consumed exactly as before, so a seeded (deterministic) world keeps every other roll where it was; the
     * pinned profiles in {@link #profilesAreStableAcrossJvmRuns} are the proof, this names the mechanism.
     */
    @Test
    void theHistoricAttributeRollsAreStillDrawnSoSeededWorldsStayIdentical() {
        Set<String> keys = new HashSet<>();
        DeckStore recording = (key, size) -> {
            keys.add(key);
            return new TransientDeckStore().deck(key, size);
        };
        generator(defaultOptions()).create(11L, allOn(), recording);
        assertTrue(keys.containsAll(Set.of("profile.attr.maxHealth", "profile.attr.reach", "profile.attr.attackSpeed",
                "profile.attr.knockbackResistance")), keys.toString());
        assertFalse(keys.contains("profile.attr.scale"));
    }

    @Test
    void behaviorVariationOffMeansEveryoneStandsStill() {
        InhabitantsConfig.Profiles o = everythingOptions();
        o.behaviorVariation = false;
        for (BotProfile p : profiles(generate(400, allOn(), o))) {
            assertEquals(BotProfile.Behavior.standing(), p.behavior());
        }
    }

    @Test
    void behaviorShapeMatchesTheStance() {
        for (BotProfile p : profiles(generate(1500, allOn(), defaultOptions()))) {
            BotProfile.Behavior b = p.behavior();
            assertTrue(b.waypoints().isEmpty(), "waypoints are filled in later by the spawn planner");
            switch (b.stance()) {
                case BotProfile.Stance.STAND -> {
                    assertEquals(0, b.waypointCount());
                    assertEquals(0.0, b.patrolRadius());
                    assertTrue(b.combatant());
                    assertEquals(BotProfile.WalkType.BHOP, b.walkType());
                }
                case BotProfile.Stance.GUARD_POST -> {
                    assertEquals(1, b.waypointCount());
                    assertEquals(0.0, b.patrolRadius());
                }
                case BotProfile.Stance.PATROL_PINGPONG, BotProfile.Stance.PATROL_CYCLE -> {
                    assertTrue(b.waypointCount() >= 2 && b.waypointCount() <= 6,
                            "a one-point ping-pong path crashes PvP BOT: " + b.waypointCount());
                    assertTrue(b.patrolRadius() >= 3.0 && b.patrolRadius() <= 50.0);
                }
                default -> fail("unknown stance " + b.stance());
            }
        }
    }

    @Test
    void randomizeOffProducesABlankProfileWithoutTouchingTheDecks() {
        InhabitantsConfig.Profiles o = everythingOptions();
        o.randomize = false;
        DeckStore forbidden = (key, size) -> {
            throw new AssertionError("decks must not be used when randomisation is off: " + key);
        };
        BotProfile p = generator(o).create(123L, allOn(), forbidden);
        assertEquals(123L, p.seed());
        assertEquals(Archetypes.UNEQUIPPED, p.archetype());
        assertTrue(p.loadout().items().isEmpty());
        assertTrue(p.vitals().attributes().isEmpty());
        assertEquals(1.0, p.vitals().healthFraction());
        assertEquals(20, p.vitals().foodLevel());
        assertEquals(BotProfile.Behavior.standing(), p.behavior());
        assertEquals(BotProfile.CURRENT_VERSION, p.version());
    }

    @Test
    void optionsAreReadLivePerCall() {
        InhabitantsConfig.Profiles o = defaultOptions();
        ProfileGenerator generator = generator(o);
        boolean someoneMoved = false;
        for (long seed = 1; seed <= 40; seed++) {
            someoneMoved |= !generator.create(seed, allOn(), new TransientDeckStore()).behavior().equals(BotProfile.Behavior.standing());
        }
        assertTrue(someoneMoved, "with behaviour variation on, some bots patrol");
        o.behaviorVariation = false;
        for (long seed = 1; seed <= 40; seed++) {
            assertEquals(BotProfile.Behavior.standing(), generator.create(seed, allOn(), new TransientDeckStore()).behavior());
        }
    }

    @Test
    void coverageBucketsAreClampedAndNeverBreakGeneration() {
        for (int buckets : new int[]{Integer.MIN_VALUE, -5, 0, 1, 2, 3, 8, 16, 64, 65, 1000, Integer.MAX_VALUE}) {
            InhabitantsConfig.Profiles o = everythingOptions();
            o.coverageBuckets = buckets;
            for (ProfileGenerator.Generation g : generate(120, allOn(), o, buckets)) {
                assertNotNull(g.profile().loadout());
                assertValid(g.profile());
            }
        }
    }

    @Test
    void moreBucketsStillCoverTheRange() {
        InhabitantsConfig.Profiles o = defaultOptions();
        o.coverageBuckets = 16;
        List<Double> health = new ArrayList<>();
        for (BotProfile p : profiles(generate(1600, allOn(), o))) {
            health.add(p.vitals().healthFraction());
        }
        int[] buckets = new int[16];
        for (double v : health) {
            buckets[Math.min(15, (int) ((v - 0.35) / (0.65 / 16)))]++;
        }
        for (int i = 0; i < buckets.length; i++) {
            assertTrue(buckets[i] > 0, "bucket " + i + " of 16 empty");
        }
    }

    @Test
    void nullInputsFallBackToDefaults() {
        BotProfile a = new ProfileGenerator(null).create(9L, null, null);
        assertNotNull(a);
        BotProfile b = new ProfileGenerator(() -> null).create(9L, GlobalCapabilities.upstreamDefaults(), new TransientDeckStore());
        BotProfile c = new ProfileGenerator(InhabitantsConfig.Profiles::new).create(9L,
                GlobalCapabilities.upstreamDefaults(), new TransientDeckStore());
        assertEquals(b, c);
        assertEquals(a.seed(), 9L);
    }

    @Test
    void extremeSeedsWork() {
        for (long seed : new long[]{0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertValid(generator(everythingOptions()).create(seed, allOn(), new TransientDeckStore()));
        }
    }

    @Test
    void versionIsStamped() {
        for (BotProfile p : profiles(generate(50, allOn(), defaultOptions()))) {
            assertEquals(BotProfile.CURRENT_VERSION, p.version());
            assertFalse(p.archetype().isBlank());
            assertTrue(Archetypes.ALL.contains(p.archetype()), p.archetype());
        }
    }

    @Test
    void generatorIsSafeToShareAcrossManyCalls() {
        AtomicInteger calls = new AtomicInteger();
        ProfileGenerator generator = new ProfileGenerator(() -> {
            calls.incrementAndGet();
            return defaultOptions();
        });
        DeckStore decks = new TransientDeckStore();
        for (long seed : seeds(8, 25)) {
            generator.create(seed, allOn(), decks);
        }
        assertEquals(25, calls.get(), "options are consulted exactly once per profile");
    }

    /** Minimal structural validity, reused by tests that sweep unusual configurations. */
    static void assertValid(BotProfile p) {
        assertEquals(BotProfile.CURRENT_VERSION, p.version());
        for (BotProfile.ItemSpec s : specs(p)) {
            assertTrue(ProfileVocabulary.items().contains(s.item()), s.item());
            assertTrue(s.count() >= 1 && s.count() <= vanillaMaxStack(s.item()), s.item() + " x" + s.count());
        }
    }
}
