package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * The startup report: what an operator needs to see once, in the server log, to know whether the
 * PvP BOT integration works - versions, the API compatibility result, the spawn tier and every warning -
 * as one block so it stays together in a busy log.
 */
final class ReportFormatter {

    private ReportFormatter() {
    }

    static List<String> render(Status s) {
        List<String> out = new ArrayList<>();
        out.add("PvP BOT integration: " + s.availability() + " - " + s.summary());
        out.add("  Addon:      PvP BOT Inhabitants " + s.addonVersion());
        out.add("  PvP BOT:    " + s.pvpBotVersion() + " (tested: " + VersionCheck.TESTED_PVP_BOT + ")");
        out.add("  HeroBot:    " + s.heroBotVersion());
        out.add("  Spawn tier: " + s.spawnTier());
        for (String detail : s.details()) {
            out.add("  " + detail);
        }
        if (s.warnings().isEmpty()) {
            out.add("  Warnings:   none");
        } else {
            out.add("  Warnings (" + s.warnings().size() + "):");
            for (String warning : s.warnings()) {
                out.add("    - " + warning);
            }
        }
        return out;
    }

    static String text(Status s) {
        return String.join("\n", render(s));
    }
}
