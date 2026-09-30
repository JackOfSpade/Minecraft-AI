package dev.spawnbotswrapper.inhabitants.combat;

import java.util.List;

/**
 * The world as the {@link AggroController} sees it: inhabitants, players and the few facts about them that
 * the decisions need. Implemented over real {@code ServerPlayer}s / entities by the Minecraft glue; tests use
 * plain fakes.
 */
public interface AggroWorld {

    /** A point in a level. */
    record Pos(double x, double y, double z) {
        /** Distance ignoring height, the measure the walks use. */
        public double horizontalTo(Pos other) {
            double dx = x - other.x;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        /** Straight-line distance. */
        public double distanceTo(Pos other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * What noticing needs to know about a body (see {@link Perception}): where its eyes are, where it looks, and what
     * it is doing this tick.
     *
     * @param eye     eye position
     * @param look    look direction (head yaw and pitch), a vector of any length
     * @param subject its stance, noise and visibility
     */
    record Senses(Pos eye, Pos look, Perception.Subject subject) {
    }

    /** Something that can be a target: a real player, another bot or a mob. */
    interface Body {
        String name();

        /** False when dead, removed or logged out. */
        boolean alive();

        boolean spectator();

        boolean creative();

        boolean invulnerable();

        boolean isPlayer();

        /** Identity of the dimension it is in; two bodies are in the same dimension when these are equal. */
        Object dimension();

        Pos position();

        /** Straight-line distance in blocks, the same measure PvP BOT uses for its own range checks. */
        double distanceTo(Body other);

        /** The underlying entity, opaque to the controller; what {@link TargetControl.Target#entity()} refers to. */
        Object handle();

        /**
         * Eye, look direction and stance for realistic noticing, or null when unknown (a view that cannot tell: the
         * controller then falls back to plain line of sight, omnidirectional, as before perception existed).
         */
        default Senses senses() {
            return null;
        }
    }

    /** An inhabitant, which also has eyes. */
    interface Watcher extends Body {
        /**
         * Whether nothing blocks the view to {@code other}: a ray from the eye to its eye and, if that is blocked, a
         * second one to its body centre (a head over a wall or a body peeking round cover counts). Occlusion only:
         * the view cone, sneaking and noise are {@link Perception}'s business.
         */
        boolean canSee(Body other);

        /**
         * Exactly vanilla {@code LivingEntity.hasLineOfSight(other)}: the same level, within 128 blocks, one collider ray
         * from eye to eye. What noticing uses when perception is switched off. Defaults to {@link #canSee}.
         */
        default boolean plainLineOfSight(Body other) {
            return canSee(other);
        }

        /**
         * The attacker of a hit this inhabitant took since this was last asked (a player, a bot or a mob), or null. It
         * is how a hit is noticed WITHOUT relying on PvP BOT's revenge memory (which is only set when PvP BOT's own
         * settings allow it); each hit is reported once.
         */
        default Body newHitAttacker() {
            return null;
        }
    }

    /** A cell worth walking to when searching, with how much hidden space it would open up (see {@link SearchPlanner}). */
    record SearchSpot(Pos pos, double opening) {
    }

    /** Every online inhabitant (dead or alive). */
    List<? extends Watcher> inhabitants();

    /** The online inhabitant with this name (case-insensitive), or null. */
    Watcher inhabitant(String name);

    /** A view of a target entity that PvP BOT reported (see {@link TargetControl.Target#entity()}). */
    Body bodyOf(Object entity);

    /**
     * Players and bots in the same dimension as {@code bot}, at most {@code range} blocks from it, excluding
     * the bot itself. The implementation applies the range so a server full of bots costs no allocation for the
     * ones far away.
     */
    List<? extends Body> playersWithin(Watcher bot, double range);

    /**
     * Cells around {@code focus} an inhabitant could walk to and look around from, best guesses first not required: the
     * controller scores them ({@link SearchPlanner}). At most a few dozen, standable, each with its opening value.
     * Bounded work: this is called once per search point, not per tick.
     */
    default List<SearchSpot> searchSpots(Watcher bot, Pos focus, double radius) {
        return List.of();
    }
}
