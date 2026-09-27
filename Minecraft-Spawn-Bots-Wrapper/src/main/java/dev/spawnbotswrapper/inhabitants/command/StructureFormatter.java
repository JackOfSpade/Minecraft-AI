package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.config.EffectiveRule;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.config.RuleResolver;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static dev.spawnbotswrapper.inhabitants.command.Markup.bad;
import static dev.spawnbotswrapper.inhabitants.command.Markup.good;
import static dev.spawnbotswrapper.inhabitants.command.Markup.id;
import static dev.spawnbotswrapper.inhabitants.command.Markup.label;
import static dev.spawnbotswrapper.inhabitants.command.Markup.plain;
import static dev.spawnbotswrapper.inhabitants.command.Markup.title;
import static dev.spawnbotswrapper.inhabitants.command.Markup.warn;

/**
 * Output of {@code /inhabitants structure here} and {@code /inhabitants nearby}: what structure is
 * where, and what this addon decided about it. Pure; the Brigadier layer supplies the data.
 */
final class StructureFormatter {
    /** Lines of {@code nearby} before the "... and N more" line. */
    static final int MAX_NEARBY_LINES = 15;
    /** Overlapping structures listed by {@code structure here} (a village around an outpost is two). */
    static final int MAX_STRUCTURES_HERE = 6;
    static final int MAX_BOTS_LISTED = 8;

    private StructureFormatter() {
    }

    /** A detected structure together with its record, if the addon has processed it. */
    record Found(StructureSnapshot snapshot, StructureRecord record) {
    }

    // ---------------------------------------------------------------- structure here

    static List<String> here(String dimensionId, int blockX, int blockY, int blockZ,
                             List<Found> found, InhabitantsConfig config) {
        List<String> out = new ArrayList<>();
        String where = blockX + ", " + blockY + ", " + blockZ + " in " + dimensionId;
        if (found.isEmpty()) {
            out.add(title("Structures here: ") + bad("none") + label(" (" + Markup.esc(where) + ")"));
            out.add(label("No registered structure's bounding box contains this position. Only structures in "
                    + "loaded chunks are searched."));
            return out;
        }
        out.add(title("Structures here: " + found.size()) + label(" (" + Markup.esc(where) + ")"));
        int shown = Math.min(MAX_STRUCTURES_HERE, found.size());
        for (int i = 0; i < shown; i++) {
            describe(out, found.get(i), dimensionId, config);
        }
        if (found.size() > shown) {
            out.add(label("... and " + (found.size() - shown) + " more overlapping structures"));
        }
        return out;
    }

    private static void describe(List<String> out, Found f, String dimensionId, InhabitantsConfig config) {
        StructureSnapshot s = f.snapshot();
        StructureKey key = s.key();
        out.add(id(key.structureId()));
        out.add(label("  tags: ") + (s.tagIds().isEmpty()
                ? plain("none")
                : plain(String.join(", ", new TreeSet<>(s.tagIds().stream().map(t -> "#" + t).toList())))));
        out.add(label("  box: ") + plain(Fmt.box(s.bounds()))
                + (s.pieces().isEmpty() ? "" : label(", " + Fmt.plural(s.pieces().size(), "piece"))));
        out.add(label("  start chunk: ") + plain(Fmt.chunk(key.chunkX(), key.chunkZ()))
                + label("  |  " + (s.newlyGenerated() ? "generated this session" : "loaded from disk")));
        if (f.record() == null) {
            out.add(label("  status: ") + warn("not processed yet"));
            out.add(label("  ") + eligibility(s, dimensionId, config));
            return;
        }
        recordLines(out, f.record(), "  ");
    }

    /** Status, roll and bots of a processed structure, each line starting with {@code indent}. */
    static void recordLines(List<String> out, StructureRecord r, String indent) {
        out.add(label(indent + "status: ") + status(r.status)
                + (r.source == null || r.source.isBlank() ? "" : label("  (" + Markup.esc(r.source) + ")"))
                + rollText(r));
        if (r.note != null && !r.note.isBlank()) {
            out.add(label(indent + "note: ") + plain(r.note));
        }
        if (r.status == StructureStatus.ABANDONED || r.bots == null || r.bots.isEmpty()) {
            return;
        }
        out.add(label(indent + "bots: ") + plain(spawned(r) + "/" + Math.max(r.plannedBots, r.bots.size())
                + " spawned"));
        List<String> names = new ArrayList<>();
        for (BotRecord b : r.bots) {
            names.add(indent + "  " + id(String.valueOf(b.name)) + " " + botState(b.state));
        }
        Fmt.addCapped(out, names, MAX_BOTS_LISTED, "");
    }

    /** Spawned bots of a record, tolerating a record whose bot list was lost in (de)serialisation. */
    private static int spawned(StructureRecord r) {
        return r.bots == null ? 0 : r.spawnedCount();
    }

    private static String rollText(StructureRecord r) {
        if ("ADMIN_FORCED".equals(r.source)) {
            return label("  no roll (forced)");
        }
        boolean rolled = r.roll > 0 || r.occupiedChance > 0;
        if (!rolled) {
            return r.status == StructureStatus.ABANDONED ? label("  roll details not kept") : "";
        }
        String cmp = r.status == StructureStatus.ABANDONED ? " >= " : " < ";
        return label("  roll " + Fmt.decimal(r.roll, 3) + cmp + "chance " + Fmt.percent(r.occupiedChance));
    }

    private static String eligibility(StructureSnapshot s, String dimensionId, InhabitantsConfig c) {
        StructureKey key = s.key();
        if (!c.enabled) {
            return bad("addon is disabled; nothing is rolled");
        }
        if (!RuleResolver.isEligible(c, key.structureId(), s.tagIds())) {
            return bad("excluded by the config include/exclude lists") + label(" (a forced process still works)");
        }
        String rule = ruleText(RuleResolver.resolve(c, key.structureId(), s.tagIds()));
        List<String> caveats = new ArrayList<>();
        if (!RuleResolver.isDimensionEligible(c, dimensionId)) {
            caveats.add("dimension excluded");
        }
        if (c.processing != null && c.processing.onlyNewlyGenerated && !s.newlyGenerated()) {
            caveats.add("skipped automatically: generated before the addon (processing.onlyNewlyGenerated)");
        }
        String base = caveats.isEmpty() ? good("eligible") : warn("eligible only by force")
                + label(" (" + String.join("; ", caveats) + ")");
        return base + label(" - would roll ") + plain(rule) + label("; /inhabitants process nearest rolls it now");
    }

    static String ruleText(EffectiveRule r) {
        String bots = r.minBots() == r.maxBots() ? String.valueOf(r.minBots()) : r.minBots() + "-" + r.maxBots();
        if (r.singleSource()) {
            return Fmt.percent(r.occupiedChance()) + " occupied, " + bots + " bots (" + r.occupiedChanceFrom() + ")";
        }
        return Fmt.percent(r.occupiedChance()) + " occupied (" + r.occupiedChanceFrom() + "), " + bots
                + " bots (" + r.minBotsFrom() + " / " + r.maxBotsFrom() + ")";
    }

    // ---------------------------------------------------------------- nearby

    static List<String> nearby(int radiusChunks, double senderX, double senderZ,
                               List<Map.Entry<StructureKey, StructureRecord>> entries) {
        List<String> out = new ArrayList<>();
        if (entries.isEmpty()) {
            out.add(title("Processed structures within " + radiusChunks + " chunks: ") + bad("none"));
            out.add(label("Structures are recorded when their chunks load. Try /inhabitants process nearest, or a "
                    + "larger radius."));
            return out;
        }
        out.add(title("Processed structures within " + radiusChunks + " chunks: " + Fmt.num(entries.size()))
                + label(" (nearest first)"));
        List<String> lines = new ArrayList<>();
        for (Map.Entry<StructureKey, StructureRecord> e : entries) {
            lines.add(nearbyLine(e.getKey(), e.getValue(), senderX, senderZ));
        }
        Fmt.addCapped(out, lines, MAX_NEARBY_LINES, "narrow the radius");
        return out;
    }

    private static String nearbyLine(StructureKey key, StructureRecord r, double senderX, double senderZ) {
        double cx = key.chunkX() * 16 + 8.0;
        double cz = key.chunkZ() * 16 + 8.0;
        StringBuilder sb = new StringBuilder();
        sb.append(id(key.structureId()))
                .append(label(" [" + Fmt.chunk(key.chunkX(), key.chunkZ()) + "] ~"
                        + Fmt.num(Fmt.blocks(senderX, senderZ, cx, cz)) + " blocks "))
                .append(status(r.status));
        if (r.status != StructureStatus.ABANDONED) {
            int planned = Math.max(r.plannedBots, r.bots == null ? 0 : r.bots.size());
            sb.append(label(" bots ")).append(plain(spawned(r) + "/" + planned));
        }
        if ("ADMIN_FORCED".equals(r.source)) {
            sb.append(label(" forced"));
        } else if (r.roll > 0 || r.occupiedChance > 0) {
            sb.append(label(" chance " + Fmt.percent(r.occupiedChance) + " roll " + Fmt.decimal(r.roll, 2)));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- shared tokens

    static String status(StructureStatus s) {
        if (s == null) {
            return bad("UNKNOWN");
        }
        return switch (s) {
            case ABANDONED -> label("ABANDONED");
            case OCCUPIED_PENDING -> warn("PENDING");
            case POPULATED -> good("POPULATED");
            case GAVE_UP -> bad("GAVE UP");
        };
    }

    static String botState(BotState s) {
        if (s == null) {
            return bad("(unknown)");
        }
        return switch (s) {
            case PLANNED -> label("(planned)");
            case REQUESTED -> warn("(requested)");
            case SPAWNED -> good("(spawned)");
            case FAILED -> bad("(failed)");
        };
    }
}
