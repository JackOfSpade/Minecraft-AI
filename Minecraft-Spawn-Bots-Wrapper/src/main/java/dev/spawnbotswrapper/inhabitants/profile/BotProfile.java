package dev.spawnbotswrapper.inhabitants.profile;

import java.util.List;
import java.util.Map;

/**
 * The complete randomized description of ONE bot, generated once, persisted with the bot, and never
 * re-rolled. Plain immutable records so it serialises to readable JSON and needs no Minecraft classes
 * to create or test; everything Minecraft-specific (item ids, attribute ids, ...) is a STRING id that
 * is resolved only when the profile is applied.
 * <p>
 * What a profile can express is limited to what PvP BOT (v0.0.15) genuinely lets an addon vary PER
 * BOT without touching its global settings singleton:
 * <ul>
 *   <li>{@link Loadout} - inventory contents. PvP BOT chooses weapon mode, shield/totem/potion/food use
 *       etc. from what the bot carries, so loadout is the main per-bot behaviour lever.</li>
 *   <li>{@link Vitals} - starting health/hunger and a few vanilla entity attributes (max health,
 *       reach, attack speed, knockback resistance ...). Only attributes PvP BOT actually reads.</li>
 *   <li>{@link Behavior} - stance (stand / guard post / patrol), patrol radius and waypoints, walk
 *       type, and whether the bot may fight at all, all realised through PvP BOT's own path system.</li>
 * </ul>
 * See {@code SettingCatalog} for the authoritative per-setting classification.
 */
public record BotProfile(
        int version,
        long seed,
        String archetype,
        Loadout loadout,
        Vitals vitals,
        Behavior behavior) {

    /** Bump when the meaning of stored fields changes incompatibly; readers must tolerate older versions. */
    public static final int CURRENT_VERSION = 1;

    public BotProfile {
        archetype = archetype == null ? "" : archetype;
        loadout = loadout == null ? new Loadout(List.of()) : loadout;
        vitals = vitals == null ? new Vitals(1.0, 20, Map.of()) : vitals;
        behavior = behavior == null ? Behavior.standing() : behavior;
    }

    public BotProfile withBehavior(Behavior b) {
        return new BotProfile(version, seed, archetype, loadout, vitals, b);
    }

    /** Slot names used by {@link PlacedItem#slot()}. */
    public static final class Slot {
        public static final String HEAD = "head";
        public static final String CHEST = "chest";
        public static final String LEGS = "legs";
        public static final String FEET = "feet";
        public static final String OFFHAND = "offhand";
        /** Hotbar slot {@code index} 0..8; index 0 is the selected (main hand) slot. */
        public static final String HOTBAR = "hotbar";
        /** Main inventory; {@code index} -1 means "first free slot". */
        public static final String INVENTORY = "inventory";

        private Slot() {
        }
    }

    /** {@link Behavior#stance()} values. */
    public static final class Stance {
        /** Stays where it spawned (PvP BOT's global bhop/idle settings apply as usual). */
        public static final String STAND = "STAND";
        /** One-waypoint path: stands at its post and returns to it after a fight. */
        public static final String GUARD_POST = "GUARD_POST";
        /** Multi-waypoint path walked back and forth (PvP BOT "loop=true"). */
        public static final String PATROL_PINGPONG = "PATROL_PINGPONG";
        /** Multi-waypoint path walked as a ring (PvP BOT "loop=false"). */
        public static final String PATROL_CYCLE = "PATROL_CYCLE";

        private Stance() {
        }
    }

    /** {@link Behavior#walkType()} values, exactly PvP BOT's path walk types. */
    public static final class WalkType {
        public static final String BHOP = "bhop";
        public static final String SPRINT = "sprint";
        public static final String WALK = "walk";

        private WalkType() {
        }
    }

    /** {@link AttributeMod#operation()} values, mirroring vanilla attribute modifier operations. */
    public static final class Op {
        public static final String ADD_VALUE = "add_value";
        public static final String ADD_MULTIPLIED_BASE = "add_multiplied_base";
        public static final String ADD_MULTIPLIED_TOTAL = "add_multiplied_total";

        private Op() {
        }
    }

    /**
     * One item stack described by ids only.
     *
     * @param item           registry id, e.g. {@code minecraft:diamond_chestplate}
     * @param count          stack size (1..max stack)
     * @param enchantments   enchantment registry id to level; never null
     * @param damageFraction 0.0 = pristine ... 0.95 = nearly broken; only for damageable items
     * @param potion         potion registry id (e.g. {@code minecraft:strong_healing}) for potion items, else null
     */
    public record ItemSpec(String item, int count, Map<String, Integer> enchantments, double damageFraction, String potion) {
        public ItemSpec {
            enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
            count = Math.max(1, count);
            damageFraction = Math.max(0.0, Math.min(0.95, damageFraction));
        }

        public static ItemSpec of(String item, int count) {
            return new ItemSpec(item, count, Map.of(), 0.0, null);
        }

        public static ItemSpec of(String item) {
            return of(item, 1);
        }
    }

    /** An item together with where it goes. */
    public record PlacedItem(String slot, int index, ItemSpec spec) {
    }

    /** Everything the bot carries and wears. */
    public record Loadout(List<PlacedItem> items) {
        public Loadout {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** A vanilla attribute modifier to apply under a fixed addon-owned modifier id. */
    public record AttributeMod(String operation, double value) {
    }

    /**
     * @param healthFraction initial health as a fraction of max health (0.05..1.0). PvP BOT/HeroBot reset
     *                       health at every spawn, so this only shapes the first encounter.
     * @param foodLevel      initial hunger points 0..20
     * @param attributes     attribute registry id (e.g. {@code minecraft:max_health}) to modifier
     */
    public record Vitals(double healthFraction, int foodLevel, Map<String, AttributeMod> attributes) {
        public Vitals {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            healthFraction = Math.max(0.05, Math.min(1.0, healthFraction));
            foodLevel = Math.max(0, Math.min(20, foodLevel));
        }
    }

    public record Waypoint(double x, double y, double z) {
    }

    /**
     * @param stance        one of {@link Stance}
     * @param combatant     false makes the bot a pacifist: PvP BOT skips its whole combat AI for path
     *                      followers whose path has attack=false. Only effective when a path is used
     *                      (every stance except STAND); ignored for STAND.
     * @param walkType      one of {@link WalkType}; only effective while following a path
     * @param patrolRadius  planned patrol radius in blocks (0 for STAND)
     * @param waypointCount planned number of waypoints (1 for GUARD_POST, 0 for STAND)
     * @param waypoints     absolute waypoint positions, filled in after the bot's home position is known
     *                      (empty until then) and persisted so a restart never re-plans them
     */
    public record Behavior(String stance, boolean combatant, String walkType, double patrolRadius,
                           int waypointCount, List<Waypoint> waypoints) {
        public Behavior {
            stance = stance == null ? Stance.STAND : stance;
            walkType = walkType == null ? WalkType.BHOP : walkType;
            waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        }

        public static Behavior standing() {
            return new Behavior(Stance.STAND, true, WalkType.BHOP, 0.0, 0, List.of());
        }

        public boolean usesPath() {
            return !Stance.STAND.equals(stance);
        }

        public Behavior withWaypoints(List<Waypoint> w) {
            return new Behavior(stance, combatant, walkType, patrolRadius, waypointCount, w);
        }
    }
}
