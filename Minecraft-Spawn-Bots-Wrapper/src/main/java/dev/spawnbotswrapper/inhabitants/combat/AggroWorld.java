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
        /** Distance ignoring height, the measure the leash and the return use. */
        public double horizontalTo(Pos other) {
            double dx = x - other.x;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dz * dz);
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
}
