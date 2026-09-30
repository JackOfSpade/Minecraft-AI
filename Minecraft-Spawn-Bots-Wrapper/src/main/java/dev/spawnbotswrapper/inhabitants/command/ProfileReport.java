package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.engine.PopulationView.BotLocation;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static dev.spawnbotswrapper.inhabitants.command.Markup.bad;
import static dev.spawnbotswrapper.inhabitants.command.Markup.good;
import static dev.spawnbotswrapper.inhabitants.command.Markup.id;
import static dev.spawnbotswrapper.inhabitants.command.Markup.label;
import static dev.spawnbotswrapper.inhabitants.command.Markup.plain;
import static dev.spawnbotswrapper.inhabitants.command.Markup.title;
import static dev.spawnbotswrapper.inhabitants.command.Markup.warn;

/**
 * Output of {@code /inhabitants profile <bot>}: where the bot lives and what state it is in, followed by
 * the rendered profile. The profile text itself comes from the profile package's own formatter; it is
 * passed in as a supplier so a failure there degrades to one line instead of losing the whole answer.
 */
final class ProfileReport {
    /** Bounds the profile body; a profile is a few dozen lines, so this only stops a runaway renderer. */
    static final int MAX_PROFILE_LINES = 80;

    private ProfileReport() {
    }

    /** Plain-text error for a name no inhabitant has. */
    static String unknownBot(String name) {
        return "No inhabitant named '" + Markup.esc(name) + "'. Only bots created by this addon have a profile "
                + "(names are matched case-insensitively; tab completion offers the ones near you).";
    }

    /**
     * @param profileLines produces the rendered profile; only called when the bot has one. Any failure is
     *                     reported inline.
     */
    static List<String> format(BotLocation location, Supplier<List<String>> profileLines) {
        BotRecord bot = location.bot();
        StructureKey key = location.structure();
        List<String> out = new ArrayList<>();
        out.add(title("Inhabitant ") + id(String.valueOf(bot.name)));
        out.add(label("  structure: ") + id(key.structureId()) + label(" at chunk "
                + Fmt.chunk(key.chunkX(), key.chunkZ()) + " in " + Markup.esc(key.dimension())));
        out.add(label("  state: ") + state(bot.state) + label("  |  index " + bot.index + "  |  spawn attempts "
                + bot.spawnAttempts));
        out.add(label("  uuid: ") + (bot.uuid == null || bot.uuid.isBlank() ? label("not seen yet") : plain(bot.uuid)));
        if (bot.state == BotState.REQUESTED || bot.state == BotState.SPAWNED || bot.state == BotState.DORMANT) {
            out.add(label("  position: ") + plain(Fmt.xyz(bot.x, bot.y, bot.z)) + label(", yaw "
                    + Fmt.decimal(bot.yaw, 0) + (bot.state == BotState.DORMANT ? " (remembered, not currently live)" : "")));
        } else {
            out.add(label("  position: not chosen yet"));
        }
        if (bot.failure != null && !bot.failure.isBlank()) {
            out.add(label("  last failure: ") + bad(bot.failure));
        }
        if (bot.profile == null) {
            out.add(warn("  No profile yet: it is generated when the bot spawns (state " + bot.state + ")."));
            return out;
        }
        out.add(label("  profile: ") + (bot.profileApplied
                ? good("applied to the live bot") : warn("generated, not applied yet"))
                + label(" (format v" + bot.profileVersion + ")"));
        List<String> body;
        try {
            body = profileLines.get();
        } catch (RuntimeException | LinkageError e) {
            out.add(bad("  The profile could not be rendered: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + " (see the server log)"));
            return out;
        }
        List<String> shown = new ArrayList<>();
        for (String line : Fmt.lines(body)) {
            shown.add(plain(line));
        }
        Fmt.addCapped(out, shown, MAX_PROFILE_LINES, "");
        return out;
    }

    private static String state(BotState s) {
        if (s == null) {
            return bad("UNKNOWN");
        }
        return switch (s) {
            case PLANNED -> label("PLANNED");
            case REQUESTED -> warn("REQUESTED");
            case SPAWNED -> good("SPAWNED");
            case FAILED -> bad("FAILED");
            case DORMANT -> warn("DORMANT");
            case DEAD -> bad("DEAD");
        };
    }
}
