package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link TargetControl} over PvP BOT's combat routine, for the aggro hunter. Resolves the current probe result on
 * every call ({@code calls} may be replaced by a re-probe), reads settings through getters only and writes only through
 * PvP BOT's own {@code setTarget} / {@code clearTarget} and its look / move-toward navigation calls.
 * <p>
 * Failure policy. A missing member the controller cannot do without makes {@link #available()} false (one
 * reason, logged once by the controller). A settings getter that is missing degrades that one switch to PvP BOT's
 * shipped default (one WARN per missing name), except the ones whose default would make the controller act on a
 * guess (combat on, auto-target, chase limit): then {@link #settings()} is null and the controller idles. A call
 * that throws is reported once per distinct cause and surfaces as {@link TargetControl.UpstreamFailure}.
 */
final class PvpBotCombatControl implements TargetControl {

    /** Pauses and resumes the patrol of a bot (the adapter's patrol manager; the control never sees paths). */
    interface PatrolHold {
        void pause(String bot);

        void resume(String bot);
    }

    private final Supplier<UpstreamCalls> calls;
    private final Diagnostics log;
    private final Predicate<String> listed;
    private final PatrolHold hold;

    /**
     * @param calls  the current probe's call layer, or null when PvP BOT is not usable
     * @param listed whether PvP BOT currently lists a name as one of its bots
     */
    PvpBotCombatControl(Supplier<UpstreamCalls> calls, Diagnostics log, Predicate<String> listed, PatrolHold hold) {
        this.calls = calls;
        this.log = log;
        this.listed = listed;
        this.hold = hold;
    }

    /** Without a patrol hold (tests, or nothing to hold). */
    PvpBotCombatControl(Supplier<UpstreamCalls> calls, Diagnostics log, Predicate<String> listed) {
        this(calls, log, listed, null);
    }

    private UpstreamCalls usable() {
        UpstreamCalls c = calls.get();
        return c != null && c.contract().combatControlProblem() == null ? c : null;
    }

    @Override
    public boolean available() {
        return usable() != null;
    }

    @Override
    public String unavailableReason() {
        UpstreamCalls c = calls.get();
        if (c == null) {
            return "PvP BOT is not available";
        }
        String problem = c.contract().combatControlProblem();
        return problem;
    }

    private UpstreamCalls require() {
        UpstreamCalls c = usable();
        if (c == null) {
            throw new UpstreamFailure(unavailableReason() == null ? "PvP BOT target control is not available"
                    : unavailableReason());
        }
        return c;
    }

    @Override
    public Settings settings() {
        UpstreamCalls c = usable();
        if (c == null) {
            return null;
        }
        try {
            Object s = c.settingsInstance();
            if (s == null) {
                return null;
            }
            Boolean combat = c.readBoolean(s, "isCombatEnabled");
            Boolean auto = c.readBoolean(s, "isAutoTargetEnabled");
            Double max = c.readMaxTargetDistance(s);
            if (combat == null || auto == null || max == null) {
                log.warnOnce("aggro-settings|" + (combat == null) + "|" + (auto == null) + "|" + (max == null),
                        "PvP BOT integration: the settings isCombatEnabled / isAutoTargetEnabled / "
                                + "getMaxTargetDistance cannot all be read; the aggro hunter stays idle");
                return null;
            }
            return new Settings(combat, auto,
                    flag(c, s, "isTargetPlayers", true), flag(c, s, "isTargetOtherBots", false),
                    flag(c, s, "isAttackInvincible", false), flag(c, s, "isFactionsEnabled", false),
                    flag(c, s, "isFriendlyFireEnabled", false), max);
        } catch (Throwable t) {
            log.failure("reading PvP BOT's combat settings", t);
            return null;
        }
    }

    /** One boolean switch; PvP BOT's shipped default (with one WARN) when the getter is missing. */
    private boolean flag(UpstreamCalls c, Object settings, String getter, boolean fallback) throws Throwable {
        Boolean v = c.readCombatBoolean(settings, getter);
        if (v != null) {
            return v;
        }
        log.warnOnce("aggro-getter|" + getter, "PvP BOT integration: the setting getter " + getter
                + " is missing or unusable; the aggro hunter assumes PvP BOT's default (" + fallback + ")");
        return fallback;
    }

    @Override
    public boolean isPvpBotBot(String name) {
        return listed.test(name);
    }

    @Override
    public boolean areAllies(String a, String b) {
        UpstreamCalls c = require();
        try {
            return c.areAllies(a, b);
        } catch (Throwable t) {
            throw fail("asking PvP BOT whether two players are allies", t);
        }
    }

    @Override
    public String forcedTarget(String bot) {
        UpstreamCalls c = require();
        try {
            return c.forcedTarget(bot);
        } catch (Throwable t) {
            throw fail("reading a bot's forced target", t);
        }
    }

    @Override
    public Target currentTarget(String bot) {
        UpstreamCalls c = require();
        try {
            Entity e = c.currentTarget(bot);
            return e == null ? null : new Target(e, e.getName().getString(), c.isLastAttacker(bot, e));
        } catch (Throwable t) {
            throw fail("reading a bot's current target", t);
        }
    }

    @Override
    public void setTarget(String bot, String target) {
        UpstreamCalls c = require();
        try {
            c.setTarget(bot, target);
        } catch (Throwable t) {
            throw fail("forcing a target on a bot", t);
        }
    }

    @Override
    public void clearTarget(String bot) {
        UpstreamCalls c = require();
        try {
            c.clearTarget(bot);
        } catch (Throwable t) {
            throw fail("clearing a bot's target", t);
        }
    }

    @Override
    public boolean steeringAvailable() {
        UpstreamCalls c = calls.get();
        return c != null && c.contract().steeringProblem() == null;
    }

    @Override
    public String steeringProblem() {
        UpstreamCalls c = calls.get();
        return c == null ? "PvP BOT is not available" : c.contract().steeringProblem();
    }

    @Override
    public void steer(Object bot, AggroWorld.Pos to, double speed) {
        UpstreamCalls c = calls.get();
        if (c == null || c.contract().steeringProblem() != null || !(bot instanceof ServerPlayer player)) {
            throw new UpstreamFailure("PvP BOT's walk-toward calls are not available");
        }
        try {
            c.steer(player, new Vec3(to.x(), to.y(), to.z()), speed);
        } catch (Throwable t) {
            throw fail("walking a bot toward a point", t);
        }
    }

    @Override
    public void look(Object bot, AggroWorld.Pos at) {
        UpstreamCalls c = calls.get();
        if (c == null || c.contract().steeringProblem() != null || !(bot instanceof ServerPlayer player)) {
            throw new UpstreamFailure("PvP BOT's look call is not available");
        }
        try {
            c.look(player, new Vec3(at.x(), at.y(), at.z()));
        } catch (Throwable t) {
            throw fail("turning a bot toward a point", t);
        }
    }

    @Override
    public void pausePatrol(String bot) {
        if (hold != null) {
            hold.pause(bot);
        }
    }

    @Override
    public void resumePatrol(String bot) {
        if (hold != null) {
            hold.resume(bot);
        }
    }

    @Override
    public void halt(Object bot) {
        if (!(bot instanceof ServerPlayer player)) {
            throw new UpstreamFailure("not a player");
        }
        // Vanilla's own movement input of the entity (what PvP BOT's move-toward call sets every tick): no forward or
        // sideways push and no sprint, so the bot stands still instead of walking on in its last direction.
        player.zza = 0.0F;
        player.xxa = 0.0F;
        player.setSprinting(false);
    }

    private UpstreamFailure fail(String what, Throwable t) {
        log.failure(what, t);
        return new UpstreamFailure(what + " failed (" + Diagnostics.describe(t) + ")", t);
    }
}
