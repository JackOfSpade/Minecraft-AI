package dev.spawnbotswrapper.inhabitants.combat;

/**
 * What the {@link AggroController} needs from PvP BOT, as a plain interface: read a few of its combat settings,
 * read a bot's current and forced target, set or clear a target, and steer a bot toward a point. Implemented by the
 * adapter (the only class that may know PvP BOT exists); tests implement it with a fake.
 * <p>
 * Every method may throw {@link UpstreamFailure} when the upstream call did not work; the controller treats that
 * as "unknown", never as "no target". Server thread only.
 */
public interface TargetControl {

    /** An upstream call failed (missing member, thrown exception). The message is for logs. */
    final class UpstreamFailure extends RuntimeException {
        public UpstreamFailure(String message, Throwable cause) {
            super(message, cause);
        }

        public UpstreamFailure(String message) {
            super(message);
        }
    }

    /**
     * The PvP BOT settings that decide who a bot may attack and how far it can chase.
     *
     * @param combatEnabled     PvP BOT's combat routine is on at all (off: its per-bot target is not even refreshed)
     * @param autoTarget        PvP BOT already picks targets by itself (nearest entity within maxTargetDistance)
     * @param targetPlayers     real players are valid targets
     * @param targetOtherBots   other PvP BOT bots are valid targets
     * @param attackInvincible  spectator / creative / invulnerable players are valid targets
     * @param factionsEnabled   faction rules are on (allies are skipped unless friendlyFire)
     * @param friendlyFire      faction allies may be attacked
     * @param maxTargetDistance PvP BOT's targeting radius: a forced target or revenge attacker farther away is ignored. The wrapper
     *                          manages it at 16 for new targets and close combat, then keeps confirmed, visible targets moving
     *                          through its own line-of-sight chase when they pass that radius.
     */
    record Settings(boolean combatEnabled, boolean autoTarget, boolean targetPlayers, boolean targetOtherBots,
                    boolean attackInvincible, boolean factionsEnabled, boolean friendlyFire,
                    double maxTargetDistance) {
    }

    /** False when a member the controller cannot do without (set/get/clear target, forced-target access) is missing. */
    boolean available();

    /** Why {@link #available()} is false, for the one log line; null when it is true. */
    String unavailableReason();

    /** The current settings, or null when they cannot be read. */
    Settings settings();

    /** True when PvP BOT lists {@code name} as one of its bots (as opposed to a real player). */
    boolean isPvpBotBot(String name);

    /** True when the two names are in the same faction. Only ever called while {@code factionsEnabled}. */
    boolean areAllies(String a, String b);

    /**
     * A bot's current target as PvP BOT resolved it this tick.
     *
     * @param entity  the target entity, opaque; pass it to {@link AggroWorld#bodyOf}
     * @param name    its display name, for logs
     * @param revenge true when it is the bot's last attacker (PvP BOT's revenge memory), false when it came from a
     *                forced name or anything else
     */
    record Target(Object entity, String name, boolean revenge) {
    }

    /** The name forced on this bot by {@code setTarget}, or null when none. */
    String forcedTarget(String bot);

    /** The entity this bot targets right now (PvP BOT's per-tick result), or null when it has none. */
    Target currentTarget(String bot);

    /** Forces {@code target} on {@code bot}: PvP BOT chases and fights it while it is valid and within range. */
    void setTarget(String bot, String target);

    /**
     * PvP BOT's full reset of this bot's target state (forced target, current target, revenge memory, retreat), so
     * it does not go back to the fight by itself.
     */
    void clearTarget(String bot);

    /** False when walking cannot be done (PvP BOT's look/move-toward calls are missing). */
    boolean steeringAvailable();

    /** Why {@link #steeringAvailable()} is false, for the one log line; null when it is true. */
    String steeringProblem();

    /**
     * One tick of walking {@code bot} toward {@code to}: PvP BOT's own look and move-toward input, never a
     * teleport. Must be called after PvP BOT's own tick of that bot so this input is the one that stands.
     *
     * @param bot the inhabitant, opaque ({@link AggroWorld.Watcher#handle()})
     */
    void steer(Object bot, AggroWorld.Pos to, double speed);

    /** One tick of turning {@code bot} to look at {@code at} (PvP BOT's own look call), without walking. */
    void look(Object bot, AggroWorld.Pos at);

    /** Stops the walking input of {@code bot} (forward and sideways movement and sprint), so it stands still. */
    void halt(Object bot);

    /**
     * Keeps PvP BOT's own patrol from walking {@code bot} while a hunt walks it (PvP BOT's patrol movement is applied
     * every tick and would pull the bot toward its next patrol point against the hunt's steering). Idempotent, fail-soft,
     * and a no-op for a bot that has no patrol of this addon.
     */
    void pausePatrol(String bot);

    /** Lets the patrol walk the bot again after {@link #pausePatrol}; a no-op when it was not paused. */
    void resumePatrol(String bot);

    /** A control that is never available; what callers get when there is no PvP BOT. */
    TargetControl NONE = new TargetControl() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String unavailableReason() {
            return "PvP BOT is not available";
        }

        @Override
        public Settings settings() {
            return null;
        }

        @Override
        public boolean isPvpBotBot(String name) {
            return false;
        }

        @Override
        public boolean areAllies(String a, String b) {
            throw new UpstreamFailure("PvP BOT is not available");
        }

        @Override
        public String forcedTarget(String bot) {
            throw new UpstreamFailure("PvP BOT is not available");
        }

        @Override
        public Target currentTarget(String bot) {
            throw new UpstreamFailure("PvP BOT is not available");
        }

        @Override
        public void setTarget(String bot, String target) {
            throw new UpstreamFailure("PvP BOT is not available");
        }

        @Override
        public void clearTarget(String bot) {
            throw new UpstreamFailure("PvP BOT is not available");
        }

        @Override
        public boolean steeringAvailable() {
            return false;
        }

        @Override
        public String steeringProblem() {
            return "PvP BOT is not available";
        }

        @Override
        public void steer(Object bot, AggroWorld.Pos to, double speed) {
            throw new UpstreamFailure("PvP BOT is not available");
        }

        @Override
        public void look(Object bot, AggroWorld.Pos at) {
            throw new UpstreamFailure("PvP BOT is not available");
        }

        @Override
        public void halt(Object bot) {
            throw new UpstreamFailure("PvP BOT is not available");
        }

        @Override
        public void pausePatrol(String bot) {
        }

        @Override
        public void resumePatrol(String bot) {
        }
    };
}
