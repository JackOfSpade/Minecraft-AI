package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Availability;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Status;
import dev.spawnbotswrapper.inhabitants.adapter.SettingsHygiene.Finding;
import dev.spawnbotswrapper.inhabitants.adapter.SettingsHygiene.Severity;
import dev.spawnbotswrapper.inhabitants.adapter.UpstreamContract.Member;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns everything the probe observed into the {@link Status} the rest of the addon sees, plus the
 * {@link Verdict} the adapter itself acts on. Pure: no reflection, no Minecraft, no logging, so the
 * classification rules (what is fatal, what merely degrades, what is only worth a warning) are tested
 * directly and the same inputs always produce the same report.
 * <p>
 * Classification:
 * <ul>
 *   <li>UNAVAILABLE: PvP BOT is not installed; a required member is missing or incompatible (the bot list
 *       and count, or both a spawn path and a removal path); HeroBot's {@code playerspawn} command is not
 *       registered (PvP BOT cannot create a bot without it); or the configured backend leaves no spawn path.</li>
 *   <li>DEGRADED: usable through a fallback: the position overload of spawn is missing (tier CLASS), only the
 *       command spawns (tier COMMAND), removal only works through the command, or the path API needed for
 *       patrols is incomplete (bots then simply stand).</li>
 *   <li>AVAILABLE: the complete contract R1..R5 is present and patrols are supported.</li>
 * </ul>
 * The version never decides anything: a newer PvP BOT whose probes all pass is AVAILABLE with a warning.
 */
final class StatusAssembler {

    static final String NOT_INSTALLED = "not installed";

    private static final Set<String> REQUIRED_IDS = Set.of("R1", "R3", "R4", "R5");

    private StatusAssembler() {
    }

    enum ReportLevel {
        INFO,
        WARN,
        ERROR
    }

    /**
     * @param contract null when PvP BOT is not loaded at all (nothing was reflected)
     * @param tree     the command literals, {@link CommandTree#UNKNOWN} when they could not be inspected
     * @param settings null when BotSettings could not be read
     */
    record ProbeInput(String addonVersion, String pvpBotVersion, String heroBotVersion, boolean carpetLoaded,
                      UpstreamContract contract, CommandTree tree, SettingsHygiene.SettingsSnapshot settings,
                      TelemetryProbe.Telemetry telemetry) {
    }

    /** What the adapter needs to act on the classification. */
    record Verdict(Availability availability, List<SpawnTier> tierOrder, boolean patrolCapable,
                   boolean removeByClass, boolean removeCommandRegistered, boolean canAdopt) {

        static final Verdict NONE = new Verdict(Availability.UNAVAILABLE, List.of(), false, false, false, false);

        boolean usable() {
            return availability != Availability.UNAVAILABLE;
        }
    }

    record Assembly(Status status, Verdict verdict, ReportLevel level) {
    }

    static Assembly assemble(ProbeInput in, SpawnBackend backendOrNull) {
        SpawnBackend backend = backendOrNull == null ? SpawnBackend.AUTO : backendOrNull;
        String pvp = in.pvpBotVersion() == null ? NOT_INSTALLED : in.pvpBotVersion();
        String hero = in.heroBotVersion() == null ? NOT_INSTALLED : in.heroBotVersion();
        String addon = in.addonVersion() == null ? "unknown" : in.addonVersion();
        List<String> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean actionable = false;

        if (in.carpetLoaded()) {
            warnings.add("Carpet is installed: PvP BOT declares a hard conflict with Carpet and may refuse to run "
                    + "or misbehave beside it");
            actionable = true;
        }

        if (in.contract() == null) {
            details.add("API compatibility: NOT CHECKED - PvP BOT (mod id " + UpstreamNames.MOD_PVP_BOT
                    + ") is not installed");
            Status s = new Status(Availability.UNAVAILABLE, pvp, hero, addon, SpawnTier.NONE.label(),
                    "PvP BOT (mod id " + UpstreamNames.MOD_PVP_BOT + ") is not installed; nothing is rolled or "
                            + "spawned until it is",
                    details, warnings);
            return new Assembly(s, Verdict.NONE, ReportLevel.ERROR);
        }

        UpstreamContract c = in.contract();
        CommandTree tree = in.tree() == null ? CommandTree.UNKNOWN : in.tree();
        boolean cmdSpawn = tree.known() && tree.pvpbotSpawn();
        boolean cmdRemove = tree.known() && tree.pvpbotRemove();
        List<SpawnTier> order = SpawnPolicy.tierOrder(backend, c.spawn4.ok(), c.spawn3.ok(), cmdSpawn);
        SpawnTier primary = SpawnPolicy.primary(order);
        boolean removeByClass = c.removeBot.ok();
        boolean canAdopt = c.spawn3.ok() || c.spawn4.ok();

        // ------------------------------------------------------------ fatal problems
        List<String> fatal = new ArrayList<>();
        if (!c.getAllBots.ok()) {
            fatal.add("required member unusable: " + c.getAllBots.failure());
        }
        if (!c.getBotCount.ok()) {
            fatal.add("required member unusable: " + c.getBotCount.failure());
        }
        if (order.isEmpty()) {
            fatal.add(noSpawnPath(c, backend, tree));
        }
        if (!removeByClass && !cmdRemove) {
            fatal.add("no way to remove bots: " + c.removeBot.failure() + (tree.known()
                    ? " and the command '" + UpstreamNames.removeCommand("<name>") + "' is not registered"
                    : "; the command tree could not be inspected"));
        }
        if (tree.known() && !tree.playerspawn()) {
            fatal.add("HeroBot's command '" + UpstreamNames.COMMAND_PLAYERSPAWN + "' is not registered, and PvP BOT "
                    + "cannot create a bot without it (is HeroBot installed and loaded?)");
        }

        // ------------------------------------------------------------ degradations
        // spawn3 (R1) and spawn4 (R2) are reported individually regardless of backend: even under COMMAND
        // (which spawns through the Brigadier fallback, not either overload) both still matter for the
        // adopt path, so a build missing just R1 must not read as a clean AVAILABLE simply because R2
        // still lets bots be re-listed.
        List<String> degraded = new ArrayList<>();
        if (!c.spawn4.ok()) {
            degraded.add(c.spawn4.failure() + (backend != SpawnBackend.COMMAND
                    ? "; spawn tier is " + primary.label()
                    : "; spawning uses the command fallback regardless"));
        }
        if (!c.spawn3.ok()) {
            degraded.add(c.spawn3.failure() + (c.spawn4.ok()
                    ? "; orphaned bots are re-listed through the position overload"
                    : "; orphaned bots cannot be re-listed"));
        }
        if (!removeByClass) {
            degraded.add(c.removeBot.failure() + "; removal uses the '" + UpstreamNames.removeCommand("<name>")
                    + "' command");
        }
        boolean patrolCapable = c.patrolCapable();
        if (!patrolCapable) {
            degraded.add("patrols are disabled (bots will stand), path API incomplete: " + pathProblems(c));
        }

        Availability availability = !fatal.isEmpty() ? Availability.UNAVAILABLE
                : degraded.isEmpty() ? Availability.AVAILABLE : Availability.DEGRADED;

        // ------------------------------------------------------------ warnings
        Optional<String> versionWarning = VersionCheck.pvpBotWarning(in.pvpBotVersion());
        if (versionWarning.isPresent()) {
            warnings.add("PvP BOT: " + versionWarning.get());
            actionable = true;
        }
        Optional<String> generationWarning = VersionCheck.heroBotWarning(in.heroBotVersion());
        if (generationWarning.isPresent()) {
            warnings.add(generationWarning.get());
            actionable = true;
        }
        if (!tree.known()) {
            warnings.add("the command tree could not be inspected: the COMMAND spawn tier is unavailable and "
                    + "HeroBot's command contract is unverified");
            actionable = true;
        } else {
            if (!tree.player()) {
                warnings.add("HeroBot's command '" + UpstreamNames.COMMAND_PLAYER + "' is not registered: PvP BOT "
                        + "steers and removes bots through it, so removal may not take effect");
                actionable = true;
            }
            if (!tree.herobot()) {
                warnings.add("HeroBot's command '" + UpstreamNames.COMMAND_HEROBOT + "' is not registered: PvP BOT "
                        + "cannot push its leave-on-death rule to HeroBot");
                actionable = true;
            }
        }
        for (String d : degraded) {
            warnings.add("degraded: " + d);
        }
        if (!c.settingsGet.ok()) {
            warnings.add("PvP BOT's settings cannot be read (" + c.settingsGet.failure()
                    + "): global switches are assumed to be PvP BOT's defaults and the settings checks are skipped");
            actionable = true;
        }
        for (Finding f : SettingsHygiene.findings(in.settings(), in.telemetry())) {
            warnings.add(f.text());
            if (f.severity() == Severity.WARN) {
                actionable = true;
            }
        }

        // ------------------------------------------------------------ details
        details.add("API compatibility: " + compatibility(availability, fatal, degraded));
        details.add("BotManager: " + memberLine(c.managerMembers()));
        for (Member m : c.managerMembers()) {
            if (!m.ok()) {
                details.add("  " + (REQUIRED_IDS.contains(m.id()) ? "missing (required): " : "missing (optional): ")
                        + m.failure());
            }
        }
        details.add("BotSettings: " + settingsLine(c));
        details.add("Paths: " + (patrolCapable ? "BotPath statics resolved, patrols supported"
                + (c.isFollowing.ok() ? "" : " (follower verification unavailable)")
                + (c.removeState.ok() ? "" : " (navigation cleanup unavailable)")
                : "patrols DISABLED: " + pathProblems(c)));
        String combatProblem = c.combatControlProblem();
        String steeringProblem = c.steeringProblem();
        details.add("Aggro hunter (BotCombat targets): " + (combatProblem == null
                ? "setTarget/getTarget/clearTarget and the forced-target field resolved"
                + (c.factionAreAllies.ok() ? "" : " (faction check unavailable)")
                + (c.lastAttackerField != null ? "" : " (revenge memory unreadable: hits count as 'other')")
                + (steeringProblem == null ? "; walking via BotNavigation.lookAtPosition/moveTowardPosition"
                : "; walking DISABLED, " + steeringProblem)
                : "DISABLED, " + combatProblem));
        details.add("Commands: " + commandLine(tree));
        details.add("Spawn tier: " + primary.label() + tierChain(order) + " (spawning.backend=" + backend + ")");
        details.add("Removal: " + (removeByClass ? "BotManager.removeBot"
                : cmdRemove ? "command '" + UpstreamNames.removeCommand("<name>") + "'" : "unavailable"));
        details.add("Statistics opt-out (read-only): " + telemetryLine(in.telemetry()));
        if (c.modIdNote != null) {
            details.add("Cross-check: " + c.modIdNote);
        }

        ReportLevel level = availability == Availability.UNAVAILABLE ? ReportLevel.ERROR
                : (availability == Availability.DEGRADED || actionable) ? ReportLevel.WARN : ReportLevel.INFO;

        String summary;
        SpawnTier reported = availability == Availability.UNAVAILABLE ? SpawnTier.NONE : primary;
        switch (availability) {
            case UNAVAILABLE -> summary = "PvP BOT integration unavailable: " + String.join("; ", fatal);
            case DEGRADED -> summary = "PvP BOT " + pvp + " is usable with limits (" + degraded.get(0)
                    + (degraded.size() > 1 ? "; +" + (degraded.size() - 1) + " more" : "") + "); spawn tier "
                    + reported.label();
            default -> summary = "PvP BOT " + pvp + " is usable; spawn tier " + reported.label()
                    + (warnings.isEmpty() ? "" : ", " + warnings.size() + " warning(s)");
        }

        Status status = new Status(availability, pvp, hero, addon, reported.label(), summary, details, warnings);
        Verdict verdict = new Verdict(availability, order, patrolCapable, removeByClass, cmdRemove, canAdopt);
        return new Assembly(status, verdict, level);
    }

    // ---------------------------------------------------------------- text helpers

    private static String noSpawnPath(UpstreamContract c, SpawnBackend backend, CommandTree tree) {
        List<String> why = new ArrayList<>();
        if (backend != SpawnBackend.COMMAND) {
            if (!c.spawn4.ok()) {
                why.add(c.spawn4.failure());
            }
            if (!c.spawn3.ok()) {
                why.add(c.spawn3.failure());
            }
        }
        if (backend != SpawnBackend.CLASS) {
            why.add(tree.known()
                    ? "command '" + UpstreamNames.spawnCommand("<name>") + "' is not registered"
                    : "the command tree could not be inspected");
        }
        String hint = "";
        if (backend == SpawnBackend.CLASS && tree.known() && tree.pvpbotSpawn()) {
            hint = " (set spawning.backend to AUTO or COMMAND to use '" + UpstreamNames.spawnCommand("<name>") + "')";
        } else if (backend == SpawnBackend.COMMAND && (c.spawn3.ok() || c.spawn4.ok())) {
            hint = " (set spawning.backend to AUTO or CLASS to use BotManager.spawnBot)";
        }
        return "no usable spawn path with spawning.backend=" + backend + ": " + String.join("; ", why) + hint;
    }

    private static String compatibility(Availability availability, List<String> fatal, List<String> degraded) {
        return switch (availability) {
            case AVAILABLE -> "COMPATIBLE - the complete contract was found";
            case DEGRADED -> "DEGRADED - " + String.join("; ", degraded);
            case UNAVAILABLE -> "INCOMPATIBLE - " + String.join("; ", fatal);
        };
    }

    private static String memberLine(List<Member> members) {
        StringBuilder ok = new StringBuilder();
        for (Member m : members) {
            if (m.ok()) {
                ok.append(ok.length() == 0 ? "" : " ").append(m.id());
            }
        }
        return ok.length() == 0 ? "no member resolved" : "resolved " + ok;
    }

    private static String settingsLine(UpstreamContract c) {
        if (!c.settingsGet.ok()) {
            return "unreadable, " + c.settingsGet.failure() + "; global switches assumed to be defaults";
        }
        List<String> missing = c.missingGetterNames();
        if (missing.isEmpty()) {
            return "R10 get() and all " + c.getters.size() + " getters resolved";
        }
        return "R10 get() resolved; " + missing.size() + " getter(s) missing, their switches assumed to be "
                + "defaults: " + String.join(", ", missing);
    }

    private static String pathProblems(UpstreamContract c) {
        List<String> out = new ArrayList<>();
        for (Member m : c.pathRequired()) {
            if (!m.ok()) {
                out.add(m.failure());
            }
        }
        return String.join("; ", out);
    }

    private static String commandLine(CommandTree tree) {
        if (!tree.known()) {
            return "not inspected";
        }
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        (tree.pvpbot() ? present : absent).add(UpstreamNames.COMMAND_PVPBOT);
        (tree.pvpbotSpawn() ? present : absent).add(UpstreamNames.COMMAND_PVPBOT + " " + UpstreamNames.SUBCOMMAND_SPAWN);
        (tree.pvpbotRemove() ? present : absent).add(UpstreamNames.COMMAND_PVPBOT + " " + UpstreamNames.SUBCOMMAND_REMOVE);
        (tree.playerspawn() ? present : absent).add(UpstreamNames.COMMAND_PLAYERSPAWN);
        (tree.player() ? present : absent).add(UpstreamNames.COMMAND_PLAYER);
        (tree.herobot() ? present : absent).add(UpstreamNames.COMMAND_HEROBOT);
        return absent.isEmpty()
                ? "all registered (" + String.join(", ", present) + ")"
                : "missing: " + String.join(", ", absent) + "; registered: " + (present.isEmpty() ? "none" : String.join(", ", present));
    }

    private static String tierChain(List<SpawnTier> order) {
        if (order.size() < 2) {
            return "";
        }
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < order.size(); i++) {
            rest.add(order.get(i).label());
        }
        return " [fallback: " + String.join(", ", rest) + "]";
    }

    private static String telemetryLine(TelemetryProbe.Telemetry t) {
        if (t == null) {
            return "unknown";
        }
        return switch (t) {
            case ENABLED -> "statistics ON";
            case ENABLED_BY_DEFAULT -> "statistics ON (no explicit opt-out found)";
            case DISABLED -> "statistics OFF";
            case UNKNOWN -> "unknown (config directory not available)";
        };
    }
}
