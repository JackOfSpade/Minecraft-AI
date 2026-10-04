package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * Pure text rendering of the design 6.1/6.8/6.9 POI notice templates. {@code BlockPos}/{@code String}/
 * primitives in, {@code String} out: no {@code ServerLevel}, no player, no chat send and no task or
 * intent access. Sending the rendered text lives entirely in {@code coordination.PoiCoordinator}, which
 * is unconstrained by the {@code assist} package's source-contract test; this class must stay that way so
 * it never trips it.
 */
public final class PoiNotice {
    /** Design 6.8: every rendered notice is at most this many characters. */
    public static final int MAX_LENGTH = 240;

    private static final String[] COMPASS_POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private PoiNotice() {
    }

    /**
     * Design 6.8 standard stop template: {@code Stopped: possible <label> at <x> <y> <z> (~<n> blocks
     * <dir>, y=<y>).} followed by {@code " <note>."} when {@code note} is non-blank (omitted entirely
     * when {@code note} is null or blank — no dangling ". ."), followed by the fixed resume instruction
     * {@code Say "continue" to keep mining or "cancel" to redirect me.}
     *
     * <p>{@code note} is nullable/blank. {@code PoiCoordinator} passes {@code "(auto-detected)"} for a
     * fallback {@code S >= 0.75} stop, and {@code null} for a deterministic structure-certain stop.</p>
     *
     * <p>Truncates to {@link #MAX_LENGTH}, always ending with the resume instruction: the note is
     * shortened first (and dropped once there is no room left for even one character of it); if the
     * note-free skeleton itself still exceeds {@link #MAX_LENGTH} (an abnormally long {@code label}),
     * the label is shortened instead.</p>
     */
    public static String renderStandard(String label, BlockPos site, BlockPos botPos, String note) {
        requireCommon(label, site, botPos);

        String beforeLabel = "Stopped: possible ";
        String afterLabel = " at " + coords(site) + " (~" + horizontalDistance(botPos, site) + " blocks "
                + compassDirection(botPos, site) + ", y=" + site.getY() + ").";
        String tail = " Say \"continue\" to keep mining or \"cancel\" to redirect me.";

        String withNote = beforeLabel + label + afterLabel + noteClause(note) + tail;
        if (withNote.length() <= MAX_LENGTH) {
            return withNote;
        }

        String skeleton = beforeLabel + label + afterLabel + tail;
        if (skeleton.length() <= MAX_LENGTH) {
            int budget = MAX_LENGTH - skeleton.length();
            return beforeLabel + label + afterLabel + fittedNoteClause(note, budget) + tail;
        }

        // The note-free skeleton alone is already too long: drop the note and shrink the label instead.
        return capLabel(beforeLabel, label, afterLabel + tail);
    }

    /**
     * Design 6.1 DigDown-descend variant: {@code Stopped descent: possible <label> at <x> <y> <z> (~<n>
     * blocks <dir>). Saying "continue" makes me climb back to the surface; say "cancel" to stay here.}
     * Used for every stop while the active task is a descending {@code DigDownTask}. No note slot, no {@code y=}.
     */
    public static String renderDigDownDescend(String label, BlockPos site, BlockPos botPos) {
        requireCommon(label, site, botPos);

        String beforeLabel = "Stopped descent: possible ";
        String afterLabelAndTail = " at " + coords(site) + " (~" + horizontalDistance(botPos, site) + " blocks "
                + compassDirection(botPos, site) + "). Saying \"continue\" makes me climb back to the surface; "
                + "say \"cancel\" to stay here.";

        String full = beforeLabel + label + afterLabelAndTail;
        return full.length() <= MAX_LENGTH ? full : capLabel(beforeLabel, label, afterLabelAndTail);
    }

    /**
     * Design 6.1's per-task-class notice variant, pure text selection: when {@code descending} (the task's
     * DigDown DESCEND phase at whatever moment the caller captured it — see {@code PoiCoordinator.stopNow}'s
     * and {@code PoiCoordinator.tick}'s own comments on why that moment matters), every stop (mandatory,
     * certain, or fallback) uses the climb-out wording instead of the standard/mandatory template, regardless
     * of source.
     */
    public static String renderStop(boolean descending, String label, BlockPos anchor,
                                     BlockPos botPos, String autoDetectedNote, String restartPrefix) {
        String base = descending
                ? renderDigDownDescend(label, anchor, botPos)
                : renderStandard(label, anchor, botPos, autoDetectedNote);
        return restartPrefix == null ? base : restartPrefix + base;
    }

    /**
     * Design 6.6's "Late check" line, for a verdict (real, cached, or fallback) that arrives after the
     * player already acted during the hold: short enough that its own truncation is unneeded.
     */
    public static String renderLateCheck(String label, BlockPos anchor) {
        return "Late check: that looked like " + label + " at " + anchor.getX() + " " + anchor.getY() + " "
                + anchor.getZ() + "; I kept mining, say pause if you want to look";
    }

    /** Comma-separated coordinate triple for a structured log field (e.g. {@code BotLog.task}'s {@code pos}). */
    public static String anchorStr(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * Design 6.7/6.9 notify-and-continue ("FYI") line: {@code FYI: possible <label> at <x> <y> <z>
     * (auto-detected, not confirmed). I'm continuing; say "stop" if you want to look.}
     */
    public static String renderFyi(String label, BlockPos site, BlockPos botPos) {
        requireCommon(label, site, botPos);

        String beforeLabel = "FYI: possible ";
        String afterLabelAndTail = " at " + coords(site)
                + " (auto-detected, not confirmed). I'm continuing; say \"stop\" if you want to look.";

        String full = beforeLabel + label + afterLabelAndTail;
        return full.length() <= MAX_LENGTH ? full : capLabel(beforeLabel, label, afterLabelAndTail);
    }

    /**
     * 8-point compass ({@code "N"}, {@code "NE"}, {@code "E"}, {@code "SE"}, {@code "S"}, {@code "SW"},
     * {@code "W"}, {@code "NW"}) from {@code botPos} to {@code site}, on Minecraft's axes (north is
     * {@code -z}, east is {@code +x}). Sectors are centred on their compass point and 45 degrees wide, so
     * an angle exactly on a boundary (for example 22.5 degrees, the N/NE split) resolves to the sector it
     * enters. {@code site} directly above/below {@code botPos} (no horizontal offset at all) has no
     * defined bearing and resolves to {@code "N"}.
     */
    static String compassDirection(BlockPos botPos, BlockPos site) {
        Objects.requireNonNull(botPos, "botPos");
        Objects.requireNonNull(site, "site");
        double dx = site.getX() - botPos.getX();
        double dz = site.getZ() - botPos.getZ();
        if (dx == 0.0D && dz == 0.0D) {
            return COMPASS_POINTS[0];
        }
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        if (degrees < 0.0D) {
            degrees += 360.0D;
        }
        int index = ((int) Math.floor((degrees + 22.5D) / 45.0D)) % COMPASS_POINTS.length;
        return COMPASS_POINTS[index];
    }

    private static void requireCommon(String label, BlockPos site, BlockPos botPos) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(botPos, "botPos");
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Horizontal (x/z only) distance from {@code from} to {@code to}, rounded to the nearest block. */
    private static long horizontalDistance(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.round(Math.hypot(dx, dz));
    }

    /** {@code " <note>."}, or {@code ""} when {@code note} is null or blank. */
    private static String noteClause(String note) {
        return note == null || note.isBlank() ? "" : " " + note.trim() + ".";
    }

    /**
     * Same as {@link #noteClause}, but the rendered clause (leading space and trailing period included)
     * is shortened to fit within {@code budget} characters, and dropped entirely once {@code budget}
     * cannot fit even one character of note text.
     */
    private static String fittedNoteClause(String note, int budget) {
        if (note == null || note.isBlank() || budget < 3) {
            return "";
        }
        String trimmed = note.trim();
        int maxChars = budget - 2; // reserve the leading space and trailing period
        if (trimmed.length() > maxChars) {
            trimmed = trimmed.substring(0, maxChars);
        }
        return trimmed.isEmpty() ? "" : " " + trimmed + ".";
    }

    /**
     * Rebuilds {@code beforeLabel + label + afterTail} with {@code label} shortened just enough that the
     * whole string fits within {@link #MAX_LENGTH} (empty when even {@code beforeLabel + afterTail} alone
     * does not leave room for it).
     */
    private static String capLabel(String beforeLabel, String label, String afterTail) {
        int allowed = MAX_LENGTH - beforeLabel.length() - afterTail.length();
        String capped = allowed <= 0 ? "" : label.substring(0, Math.min(label.length(), allowed));
        return beforeLabel + capped + afterTail;
    }
}
