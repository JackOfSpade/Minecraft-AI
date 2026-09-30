package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.util.RangedDistances;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns PvP BOT's GLOBAL settings (read here, written only for the few the addon manages, see
 * {@link ManagedSettings}) into findings about conditions that will surprise an operator. The settings are one
 * global singleton, so flipping one to vary a single bot would change every bot on the server.
 */
final class SettingsHygiene {

    /** PvP BOT's dead-bot cleanup only runs when its check interval is at least this (see analysis 05, 2.5). */
    static final int MIN_CHECK_INTERVAL = 10;

    private SettingsHygiene() {
    }

    /** {@link #WARN} needs the operator to act; {@link #NOTE} is a documented default worth knowing about. */
    enum Severity {
        NOTE,
        WARN
    }

    record Finding(Severity severity, String text) {
    }

    /**
     * The settings that matter here; a null field means "could not be read", which produces no finding
     * (a wrong warning is worse than none).
     */
    record SettingsSnapshot(Boolean botsRelogs, Boolean botLeaveOnDeath, Integer checkInterval,
                            Boolean autoTargetEnabled, Double maxTargetDistance, Double rangedMinRange) {
        /** The snapshot of the four switches alone (the ranged distances then count as unreadable). */
        SettingsSnapshot(Boolean botsRelogs, Boolean botLeaveOnDeath, Integer checkInterval, Boolean autoTargetEnabled) {
            this(botsRelogs, botLeaveOnDeath, checkInterval, autoTargetEnabled, null, null);
        }

        static SettingsSnapshot unreadable() {
            return new SettingsSnapshot(null, null, null, null, null, null);
        }
    }

    /**
     * The ranged minimum above the targeting radius: an archer backs off whenever the target is closer than its
     * minimum range, and a target is only ever picked within the targeting radius, so with the minimum beyond the
     * radius every archer would back out of its own targeting range. Null when fine or unreadable.
     */
    static Finding rangedFinding(Double maxTargetDistance, Double rangedMinRange) {
        if (maxTargetDistance != null && rangedMinRange != null && rangedMinRange > maxTargetDistance) {
            return new Finding(Severity.WARN, "PvP BOT setting rangedMinRange (" + RangedDistances.text(rangedMinRange)
                    + ") is above maxTargetDistance (" + RangedDistances.text(maxTargetDistance) + "): ranged inhabitants "
                    + "would back away from every target inside their own targeting radius. Lower rangedMinRange or "
                    + "raise maxTargetDistance (pvpbotSettings in this addon's config manages both)");
        }
        return null;
    }

    static List<Finding> findings(SettingsSnapshot s, TelemetryProbe.Telemetry telemetry) {
        List<Finding> out = new ArrayList<>();
        if (s != null) {
            if (Boolean.FALSE.equals(s.botsRelogs())) {
                out.add(new Finding(Severity.WARN,
                        "PvP BOT setting botsRelogs is OFF: PvP BOT forgets all its bots at every server start, "
                                + "so inhabitants vanish on restart and their structures are never re-populated. "
                                + "Turn it on in PvP BOT's per-world settings"));
            }
            if (Boolean.FALSE.equals(s.botLeaveOnDeath())) {
                out.add(new Finding(Severity.WARN,
                        "PvP BOT setting botLeaveOnDeath is OFF: a removed or killed inhabitant respawns and "
                                + "stays online unmanaged instead of leaving, so removal and death accounting "
                                + "break. Turn it on in PvP BOT's settings"));
            }
            if (s.checkInterval() != null && s.checkInterval() < MIN_CHECK_INTERVAL) {
                out.add(new Finding(Severity.WARN,
                        "PvP BOT setting checkInterval is " + s.checkInterval() + " (below " + MIN_CHECK_INTERVAL
                                + "): PvP BOT's dead-bot cleanup then never runs and dead inhabitants stay listed. "
                                + "Use " + MIN_CHECK_INTERVAL + " or more"));
            }
            if (Boolean.FALSE.equals(s.autoTargetEnabled())) {
                out.add(new Finding(Severity.NOTE,
                        "PvP BOT setting autoTarget is OFF (its default): inhabitants only fight what attacked them (or "
                                + "what an order or a faction names) and never open fire on sight. This addon manages only the "
                                + "settings listed under pvpbotSettings in its config, not this one; enable it in PvP BOT "
                                + "(pvpbot settings auto-target true) if inhabitants should attack players in range"));
            }
            Finding ranged = rangedFinding(s.maxTargetDistance(), s.rangedMinRange());
            if (ranged != null) {
                out.add(ranged);
            }
        }
        if (telemetry != null && telemetry.sends()) {
            out.add(new Finding(Severity.NOTE,
                    "PvP BOT anonymous statistics are ON (" + TelemetryProbe.RELATIVE_PATH + "): PvP BOT uploads "
                            + "the NAME of every bot it spawns, the bot count and damage totals over plain HTTP, "
                            + "and the receiver sees your server's IP address. This addon creates many bots and "
                            + "so increases that traffic. To opt out set \"" + TelemetryProbe.KEY + "\": false in "
                            + "that file and restart (this addon never edits it)"));
        }
        return out;
    }
}
