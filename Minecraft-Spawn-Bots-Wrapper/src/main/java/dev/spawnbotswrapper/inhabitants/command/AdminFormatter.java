package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.engine.EngineControl.ProcessOutcome;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.spawnbotswrapper.inhabitants.command.Markup.bad;
import static dev.spawnbotswrapper.inhabitants.command.Markup.good;
import static dev.spawnbotswrapper.inhabitants.command.Markup.id;
import static dev.spawnbotswrapper.inhabitants.command.Markup.label;
import static dev.spawnbotswrapper.inhabitants.command.Markup.plain;
import static dev.spawnbotswrapper.inhabitants.command.Markup.title;
import static dev.spawnbotswrapper.inhabitants.command.Markup.warn;

/**
 * Output of the state-changing and housekeeping commands: {@code process}, {@code reset},
 * {@code reload} and the bare-command usage text. Pure. The reset text is deliberately explicit about what
 * did and did not happen, because it is the one command that can make the addon forget a decision.
 */
final class AdminFormatter {
    static final int MAX_RELOAD_MESSAGES = 20;
    static final int MAX_RESET_NAMES = 8;

    private AdminFormatter() {
    }

    // ---------------------------------------------------------------- process

    /**
     * @param structuresInRange how many structures the locator found at all (all of them already processed
     *                          when this is shown)
     */
    static List<String> noProcessCandidate(int radiusChunks, int structuresInRange) {
        List<String> out = new ArrayList<>();
        if (structuresInRange == 0) {
            out.add(bad("No structure found within " + radiusChunks + " chunks."));
            out.add(label("Only structures in loaded chunks are searched; move closer to one (see "
                    + "/inhabitants structure here)."));
        } else {
            out.add(warn("All " + Fmt.plural(structuresInRange, "structure") + " within " + radiusChunks
                    + " chunks already " + (structuresInRange == 1 ? "has" : "have") + " a record."));
            out.add(label("See /inhabitants nearby; /inhabitants reset nearest forgets one so it can be rolled again."));
        }
        return out;
    }

    static List<String> processed(StructureSnapshot s, ForceMode mode, ProcessOutcome outcome) {
        List<String> out = new ArrayList<>();
        StructureKey key = s.key();
        String modeName = mode == null ? "roll" : mode.name().toLowerCase(Locale.ROOT);
        out.add(label("Processing ") + id(key.structureId()) + label(" at chunk " + Fmt.chunk(key.chunkX(), key.chunkZ())
                + " (mode " + modeName + ")"));
        String message = outcome.message() == null || outcome.message().isBlank()
                ? "" : label(" - ") + plain(outcome.message());
        out.add("  " + outcomeText(outcome.kind()) + message);
        return out;
    }

    static boolean processSucceeded(ProcessOutcome outcome) {
        return outcome.kind() == ProcessOutcome.Kind.ABANDONED || outcome.kind() == ProcessOutcome.Kind.OCCUPIED_QUEUED;
    }

    private static String outcomeText(ProcessOutcome.Kind kind) {
        if (kind == null) {
            return bad("no outcome reported");
        }
        return switch (kind) {
            case ABANDONED -> label("rolled ABANDONED (permanent: no bots here)");
            case OCCUPIED_QUEUED -> good("rolled OCCUPIED") + label(" - population queued; bots appear over the next ticks");
            case ALREADY_PROCESSED -> warn("already processed - nothing changed");
            case REJECTED -> bad("REJECTED");
        };
    }

    // ---------------------------------------------------------------- reset

    /** What the record looked like before a reset, captured first because the reset removes it. */
    record Before(StructureStatus status, int plannedBots, int spawnedBots, List<String> spawnedNames) {
        Before {
            spawnedNames = List.copyOf(spawnedNames);
        }

        static Before of(StructureRecord r) {
            if (r == null) {
                return null;
            }
            List<String> names = new ArrayList<>();
            if (r.bots != null) {
                for (BotRecord b : r.bots) {
                    if (b.state == BotState.SPAWNED && b.name != null) {
                        names.add(b.name);
                    }
                }
            }
            return new Before(r.status, Math.max(r.plannedBots, names.size()), names.size(), names);
        }
    }

    static List<String> resetDone(StructureKey key, Before before, boolean removeBots, boolean deterministic) {
        List<String> out = new ArrayList<>();
        out.add(good("Reset ") + id(key.structureId()) + label(" at chunk " + Fmt.chunk(key.chunkX(), key.chunkZ())
                + " in " + Markup.esc(key.dimension())));
        int spawned = 0;
        if (before != null) {
            spawned = before.spawnedBots();
            out.add(label("  it was ") + StructureFormatter.status(before.status())
                    + (before.status() == StructureStatus.ABANDONED ? ""
                    : label(", " + before.spawnedBots() + "/" + before.plannedBots() + " bots spawned")));
        }
        if (spawned > 0) {
            String names = String.join(", ", before.spawnedNames().stream().limit(MAX_RESET_NAMES).toList());
            String more = before.spawnedNames().size() > MAX_RESET_NAMES
                    ? " and " + (before.spawnedNames().size() - MAX_RESET_NAMES) + " more" : "";
            if (removeBots) {
                out.add(label("  removal of ") + plain(Fmt.plural(spawned, "inhabitant")) + label(" was requested through "
                        + "PvP BOT: ") + plain(names + more));
            } else {
                out.add(warn("  " + Fmt.plural(spawned, "inhabitant") + (spawned == 1 ? " stays" : " stay") + " in the world untracked: ")
                        + plain(names + more));
                out.add(label("  add removeBots to remove them; if the structure is rolled occupied again, new bots "
                        + "are spawned besides them."));
            }
        }
        out.add(label("  the record is gone: the structure is rolled again when its chunks next load, or with "
                + "/inhabitants process nearest."));
        if (deterministic) {
            out.add(warn("  Deterministic mode is ON: ") + label("the roll is derived from the world seed, so the same "
                    + "roll reproduces (same occupied/abandoned decision, bot count, names, profiles) unless the salt "
                    + "or the configured chance changed."));
        } else {
            out.add(label("  Deterministic mode is off: the next roll is random and may differ."));
        }
        return out;
    }

    static List<String> resetNothing(StructureKey key) {
        List<String> out = new ArrayList<>();
        out.add(warn("Nothing to reset: ") + id(key.structureId()) + label(" at chunk "
                + Fmt.chunk(key.chunkX(), key.chunkZ()) + " in " + Markup.esc(key.dimension()) + " has no record."));
        out.add(label("Structures are listed by /inhabitants nearby; the id and chunk must match exactly."));
        return out;
    }

    // ---------------------------------------------------------------- reload

    static List<String> reloaded(List<String> messages) {
        List<String> flat = Fmt.lines(messages);
        List<String> out = new ArrayList<>();
        if (flat.isEmpty()) {
            out.add(good("Config reloaded."));
            return out;
        }
        out.add(warn("Config reloaded with " + Fmt.plural(flat.size(), "message") + ":"));
        List<String> shown = new ArrayList<>();
        for (String m : flat) {
            shown.add(label("  - ") + plain(m));
        }
        Fmt.addCapped(out, shown, MAX_RELOAD_MESSAGES, "");
        return out;
    }

    // ---------------------------------------------------------------- usage

    static List<String> help(String root) {
        String r = "/" + root;
        List<String> out = new ArrayList<>();
        out.add(title("PvP BOT Inhabitants") + label(" - admin and testing commands"));
        out.add(usage(r, "info", "status, config, counters"));
        out.add(usage(r, "adapter", "PvP BOT / HeroBot API report"));
        out.add(usage(r, "structure here", "structure(s) at your position"));
        out.add(usage(r, "nearby [radiusChunks]", "processed structures around you"));
        out.add(usage(r, "process nearest [roll|occupied|abandoned]", "force-process the nearest new structure"));
        out.add(usage(r, "reset here|nearest [removeBots]", "forget one structure's roll"));
        out.add(usage(r, "reset structure <id> <chunkX> <chunkZ> [removeBots]", "same, by identity"));
        out.add(usage(r, "profile <bot>", "an inhabitant's randomized profile"));
        out.add(usage(r, "catalog [category]", "PvP BOT setting classification"));
        out.add(usage(r, "reload", "re-read the config file"));
        String other = CommandArgs.ROOT.equals(root) ? CommandArgs.ALIAS : CommandArgs.ROOT;
        out.add(label("Also available as /" + other));
        return out;
    }

    private static String usage(String root, String syntax, String what) {
        return label("  ") + plain(root + " " + syntax) + label("  " + what);
    }
}
