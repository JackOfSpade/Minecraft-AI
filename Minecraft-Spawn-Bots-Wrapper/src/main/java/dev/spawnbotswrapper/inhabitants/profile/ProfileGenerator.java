package dev.spawnbotswrapper.inhabitants.profile;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.ProfileFactory;
import dev.spawnbotswrapper.inhabitants.sample.CoverageSampler;
import dev.spawnbotswrapper.inhabitants.sample.DeckStore;
import dev.spawnbotswrapper.inhabitants.sample.TransientDeckStore;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Generates one independent randomized {@link BotProfile} per call using coverage sampling.
 * <p>
 * Every random choice goes through a {@link CoverageSampler} keyed by a stable, namespaced name, so a
 * numeric range is covered bucket by bucket (very low through very high, both endpoints reachable) and
 * every boolean and category is balanced across the world's bots rather than piling up around the middle.
 * The result depends only on the bot seed, the global capabilities, the state of the deck store and the
 * current options - never on the clock or on call order elsewhere.
 * <p>
 * The profile only expresses what PvP BOT lets an addon vary per bot without touching its global settings
 * singleton: inventory, starting health and hunger, and its own path system for stance and patrol. Never an attribute
 * modifier: an inhabitant has the stats of a vanilla player. There is no combat AI here; see {@link LoadoutRoller} for how capability switches gate items.
 */
public final class ProfileGenerator implements ProfileFactory {

    private static final List<String> STANCES = List.of(
            BotProfile.Stance.STAND, BotProfile.Stance.GUARD_POST,
            BotProfile.Stance.PATROL_PINGPONG, BotProfile.Stance.PATROL_CYCLE);

    private static final List<String> WALK_TYPES = List.of(
            BotProfile.WalkType.BHOP, BotProfile.WalkType.SPRINT, BotProfile.WalkType.WALK);

    private static final int MAX_BUCKETS = 64;

    private final Supplier<InhabitantsConfig.Profiles> options;

    public ProfileGenerator(Supplier<InhabitantsConfig.Profiles> options) {
        this.options = options;
    }

    @Override
    public BotProfile create(long botSeed, GlobalCapabilities capabilities, DeckStore decks) {
        return generate(botSeed, capabilities, decks).profile();
    }

    /** A finished profile together with the categorical facts it was built from. */
    record Generation(BotProfile profile, Facts facts) {
    }

    Generation generate(long botSeed, GlobalCapabilities capabilities, DeckStore decks) {
        InhabitantsConfig.Profiles o = options == null ? null : options.get();
        InhabitantsConfig.Profiles opts = o == null ? new InhabitantsConfig.Profiles() : o;
        GlobalCapabilities caps = capabilities == null ? GlobalCapabilities.upstreamDefaults() : capabilities;

        if (!opts.randomize) {
            return new Generation(new BotProfile(BotProfile.CURRENT_VERSION, botSeed, Archetypes.UNEQUIPPED,
                    null, null, null), Facts.empty());
        }

        int buckets = Math.max(2, Math.min(MAX_BUCKETS, opts.coverageBuckets));
        Roller roller = new Roller(new CoverageSampler(new SplitMix64(botSeed),
                decks == null ? new TransientDeckStore() : decks, buckets), buckets);

        LoadoutRoller.Rolled loadout = LoadoutRoller.roll(roller, caps, opts);
        BotProfile.Vitals vitals = vitals(roller, caps);
        BotProfile.Behavior behavior = opts.behaviorVariation ? behavior(roller) : BotProfile.Behavior.standing();
        String archetype = Archetypes.label(loadout.facts(), behavior);

        BotProfile profile = new BotProfile(BotProfile.CURRENT_VERSION, botSeed, archetype,
                loadout.loadout(), vitals, behavior);
        return new Generation(profile, loadout.facts());
    }

    /**
     * Health fraction and food level only. An inhabitant has the attributes of a vanilla player: earlier versions also
     * rolled permanent modifiers for max health, reach, attack speed and knockback resistance, stats with no item or
     * effect behind them that no player can have, so nothing of the kind is generated or applied any more. Those four
     * rolls are still DRAWN (and dropped), exactly as before, so every other roll of a seeded world stays where it was and
     * an existing deterministic world keeps its loadouts and behaviours.
     * <p>
     * Movement speed was never varied: PvP BOT applies its own moveSpeed as a velocity scalar, so the vanilla attribute
     * would change nothing it reads.
     */
    private BotProfile.Vitals vitals(Roller r, GlobalCapabilities caps) {
        double health = r.fraction("profile.vitals.health", 0.35, 1.0, 2);
        // Starting hunger is only interesting while PvP BOT's eating logic is on to react to it.
        int food = caps.autoEatEnabled() ? r.count("profile.vitals.food", 6, 20) : 20;
        r.count("profile.attr.maxHealth", -10, 20);
        r.fraction("profile.attr.reach", -1.0, 3.0, 1);
        r.fraction("profile.attr.attackSpeed", -0.6, 0.0, 2);
        r.fraction("profile.attr.knockbackResistance", 0.0, 1.0, 2);
        return new BotProfile.Vitals(health, food, Map.of());
    }

    /**
     * Stance, walk type and patrol size for PvP BOT's path system. Waypoints stay empty: only the spawn
     * planner knows the bot's home position and which cells are walkable. A guard post is one waypoint at
     * home (radius 0); patrols need two or more, since a one-point path with PvP BOT's ping-pong flag
     * crashes its tick.
     */
    private BotProfile.Behavior behavior(Roller r) {
        String stance = r.pick("profile.behavior.stance", STANCES);
        if (BotProfile.Stance.STAND.equals(stance)) {
            return BotProfile.Behavior.standing();
        }
        // Every inhabitant fights. The card is still drawn (and dropped) so every other draw of a deterministic
        // world keeps its place in the random stream; only the pacifist half of the deck has been retired.
        r.flag("profile.behavior.combatant");
        boolean combatant = true;
        String walk = r.pick("profile.behavior.walkType", WALK_TYPES);
        if (BotProfile.Stance.GUARD_POST.equals(stance)) {
            return new BotProfile.Behavior(stance, combatant, walk, 0.0, 1, List.of());
        }
        double radius = r.fraction("profile.behavior.radius", 3.0, 50.0, 1);
        int waypoints = r.count("profile.behavior.waypoints", 2, 6);
        return new BotProfile.Behavior(stance, combatant, walk, radius, waypoints, List.of());
    }
}
