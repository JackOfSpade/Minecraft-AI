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
    }

    /** An inhabitant, which also has eyes. */
    interface Watcher extends Body {
        /** Eye-to-eye line of sight to {@code other}. */
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
