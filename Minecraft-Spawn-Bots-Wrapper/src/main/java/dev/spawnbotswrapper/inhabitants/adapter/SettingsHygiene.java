package dev.spawnbotswrapper.inhabitants.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns PvP BOT's GLOBAL settings (read-only) into findings about conditions that will surprise an
 * operator. The addon never changes these settings: they are one global singleton, and flipping one
 * to vary a single bot would change every bot on the server.
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
                            Boolean autoTargetEnabled) {
        static SettingsSnapshot unreadable() {
            return new SettingsSnapshot(null, null, null, null);
        }
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
                        "PvP BOT setting autoTarget is OFF (its default): inhabitants stay passive until they are "
                                + "attacked. It is a global PvP BOT setting that this addon never changes; enable "
                                + "it in PvP BOT if inhabitants should attack on sight"));
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
