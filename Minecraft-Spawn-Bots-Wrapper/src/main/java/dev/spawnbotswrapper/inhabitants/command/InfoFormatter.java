package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.config.EffectiveRule;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl.EngineStats;
import dev.spawnbotswrapper.inhabitants.engine.PopulationView.PopulationCounts;

import java.util.ArrayList;
import java.util.List;

import static dev.spawnbotswrapper.inhabitants.command.Markup.bad;
import static dev.spawnbotswrapper.inhabitants.command.Markup.good;
import static dev.spawnbotswrapper.inhabitants.command.Markup.label;
import static dev.spawnbotswrapper.inhabitants.command.Markup.plain;
import static dev.spawnbotswrapper.inhabitants.command.Markup.title;
import static dev.spawnbotswrapper.inhabitants.command.Markup.warn;

/**
 * Output of {@code /inhabitants info}: one screen with the addon and integration status, the config
 * switches that matter, this session's engine counters and what has been persisted so far. Pure: takes
 * the plain data records the services already produce.
 */
final class InfoFormatter {
    /** Adapter warnings shown here; {@code /inhabitants adapter} has the complete list. */
    static final int MAX_WARNINGS = 3;

    private InfoFormatter() {
    }

    static List<String> format(String addonVersion, PvpBotOperations.Status adapter, InhabitantsConfig config,
                               EngineStats stats, PopulationCounts counts) {
        List<String> out = new ArrayList<>();
        out.add(title("PvP BOT Inhabitants") + " " + plain(Fmt.orDash(addonVersion)));
        adapterLines(out, adapter);
        configLine(out, config);
        engineLines(out, stats);
        populationLine(out, counts);
        return out;
    }

    private static void adapterLines(List<String> out, PvpBotOperations.Status s) {
        if (s == null) {
            out.add(label("Integration: ") + bad("unknown") + label(" (the PvP BOT adapter has not reported a status)"));
            return;
        }
        out.add(label("PvP BOT ") + plain(Fmt.orDash(s.pvpBotVersion()))
                + label("  |  HeroBot ") + plain(Fmt.orDash(s.heroBotVersion()))
                + label("  |  spawn tier ") + plain(Fmt.orDash(s.spawnTier())));
        String summary = s.summary() == null || s.summary().isBlank() ? "" : label(" - ") + plain(s.summary());
        out.add(label("Integration: ") + availability(s.availability()) + summary);
        if (!s.usable()) {
            out.add(bad("No structure is rolled and no bot is spawned while PvP BOT is unusable."));
        }
        List<String> warnings = Fmt.lines(s.warnings());
        if (!warnings.isEmpty()) {
            out.add(warn("Warnings (" + warnings.size() + "):"));
            List<String> shown = new ArrayList<>();
            for (String w : warnings) {
                shown.add(warn("  ! ") + plain(w));
            }
            Fmt.addCapped(out, shown, MAX_WARNINGS, "see /inhabitants adapter");
        }
    }

    static String availability(PvpBotOperations.Availability a) {
        if (a == null) {
            return bad("UNKNOWN");
        }
        return switch (a) {
            case AVAILABLE -> good("AVAILABLE");
            case DEGRADED -> warn("DEGRADED");
            case UNAVAILABLE -> bad("UNAVAILABLE");
        };
    }

    private static void configLine(List<String> out, InhabitantsConfig c) {
        String enabled = c.enabled ? good("enabled") : bad("DISABLED") + label(" (nothing is rolled)");
        String deterministic = c.deterministic != null && c.deterministic.enabled
                ? "deterministic on" + (c.deterministic.salt == null || c.deterministic.salt.isEmpty()
                        ? "" : " (salt \"" + Markup.esc(c.deterministic.salt) + "\")")
                : "deterministic off";
        out.add(label("Config: ") + enabled + label("  |  ") + plain(deterministic)
                + label("  |  ") + plain(defaultRule(c))
                + label("  |  commands level ") + plain(Fmt.permissionLevel(c.commandPermissionLevel)));
    }

    private static String defaultRule(InhabitantsConfig c) {
        InhabitantsConfig.Rule r = c.defaults;
        if (r == null) {
            return "default rule missing";
        }
        return "default " + Fmt.percent(r.occupiedChance) + " occupied, "
                + r.minBots + "-" + EffectiveRule.MAX_BOTS_PER_STRUCTURE + " bots (sized by structure)";
    }

    private static void engineLines(List<String> out, EngineStats s) {
        out.add(label("Engine (this session): ")
                + plain(Fmt.num(s.structuresSeen()) + " structures seen, " + Fmt.num(s.structuresRolled()) + " rolled"));
        out.add(label("Bots (this session): ")
                + plain(Fmt.num(s.botsRequested()) + " requested, " + Fmt.num(s.botsSpawned()) + " spawned, ")
                + (s.botsFailed() > 0 ? bad(Fmt.num(s.botsFailed()) + " failed") : plain("0 failed"))
                + label("  |  ")
                + plain(Fmt.num(s.queuedStructures()) + " queued, " + Fmt.num(s.botsInFlight()) + " in flight, "
                + Fmt.num(s.liveBots()) + " live"));
    }

    private static void populationLine(List<String> out, PopulationCounts p) {
        out.add(label("Population (saved): ") + plain(Fmt.plural(p.structures(), "structure") + " - "
                + Fmt.num(p.abandoned()) + " abandoned, " + Fmt.num(p.pending()) + " pending, "
                + Fmt.num(p.populated()) + " populated, " + Fmt.num(p.gaveUp()) + " gave up"));
        out.add(label("Inhabitants (saved): ") + plain(Fmt.num(p.botsSpawned()) + " spawned, "
                + Fmt.num(p.botsFailed()) + " failed"));
    }
}
