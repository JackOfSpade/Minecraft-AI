package dev.spawnbotswrapper.inhabitants.adapter;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version bookkeeping for the two upstream mods. NOTHING here gates availability: PvP BOT has no API
 * contract and its maintainer deletes public members without notice, so the structural probes (are the
 * members there?) decide compatibility. The version only produces an honest warning when the installed
 * build is not the one this adapter was verified against.
 */
final class VersionCheck {

    /** The PvP BOT release this adapter was verified against. */
    static final String TESTED_PVP_BOT = "0.0.15";

    private static final Pattern VERSION = Pattern.compile("^v?(\\d+(?:\\.\\d+)*)([-+].*)?$");

    private VersionCheck() {
    }

    enum Relation {
        /** Same release (build metadata after {@code +} is ignored, as in semantic versioning). */
        TESTED,
        NEWER,
        OLDER,
        /** Same numbers but a pre-release suffix ({@code 0.0.15-pre-release-1}), which sorts BEFORE the release. */
        PRE_RELEASE_OF_TESTED,
        UNPARSEABLE,
        ABSENT
    }

    static Relation relation(String friendlyVersion) {
        if (friendlyVersion == null || friendlyVersion.isBlank()) {
            return Relation.ABSENT;
        }
        Matcher m = VERSION.matcher(friendlyVersion.trim());
        if (!m.matches()) {
            return Relation.UNPARSEABLE;
        }
        int cmp = compareNumbers(m.group(1), TESTED_PVP_BOT);
        if (cmp > 0) {
            return Relation.NEWER;
        }
        if (cmp < 0) {
            return Relation.OLDER;
        }
        String suffix = m.group(2);
        return suffix != null && suffix.startsWith("-") ? Relation.PRE_RELEASE_OF_TESTED : Relation.TESTED;
    }

    /** The warning for a PvP BOT build that is not the tested one, or empty when there is nothing to say. */
    static Optional<String> pvpBotWarning(String friendlyVersion) {
        return switch (relation(friendlyVersion)) {
            case TESTED, ABSENT -> Optional.empty();
            case NEWER -> Optional.of("untested version " + friendlyVersion + ", tested: " + TESTED_PVP_BOT
                    + " (every required member was found, so it is used; report problems with this version)");
            case OLDER -> Optional.of("untested version " + friendlyVersion + ", tested: " + TESTED_PVP_BOT
                    + " (older than the tested release; members may be missing, see the API check)");
            case PRE_RELEASE_OF_TESTED -> Optional.of("untested version " + friendlyVersion + ", tested: "
                    + TESTED_PVP_BOT + " (a pre-release of the tested version)");
            case UNPARSEABLE -> Optional.of("untested version " + friendlyVersion + ", tested: " + TESTED_PVP_BOT
                    + " (the version string could not be parsed)");
        };
    }

    private static int compareNumbers(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long x = i < pa.length ? parse(pa[i]) : 0L;
            long y = i < pb.length ? parse(pb[i]) : 0L;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static long parse(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- HeroBot

    /**
     * HeroBot's two code generations. They differ in class names (which PvP BOT 0.0.15 partly keys off) but
     * share the command surface PvP BOT actually spawns through, so this is reported, never enforced.
     */
    enum HeroBotGeneration {
        /** 1.x line, the one PvP BOT 0.0.15 was built against. */
        LEGACY,
        /** 2.x line and later. */
        MODERN,
        UNKNOWN
    }

    private static final Pattern MC_PREFIXED = Pattern.compile("^v?\\d+\\.\\d+(?:\\.\\d+)?-(\\d+)\\..*$");
    private static final Pattern PLAIN = Pattern.compile("^v?(\\d+)\\..*$");

    /**
     * Reads the generation out of the metadata string. HeroBot's 1.x builds are named
     * {@code <mc version>-<mod version>} (for example 1.21.11-1.4.3+v260315) while 2.x uses the bare
     * mod version, so a Minecraft-prefixed string is read after the dash. Anything else is UNKNOWN.
     */
    static HeroBotGeneration heroBotGeneration(String friendlyVersion) {
        if (friendlyVersion == null || friendlyVersion.isBlank()) {
            return HeroBotGeneration.UNKNOWN;
        }
        String v = friendlyVersion.trim();
        Matcher m = MC_PREFIXED.matcher(v);
        String major = null;
        if (m.matches()) {
            major = m.group(1);
        } else {
            Matcher p = PLAIN.matcher(v);
            if (p.matches()) {
                major = p.group(1);
            }
        }
        if (major == null) {
            return HeroBotGeneration.UNKNOWN;
        }
        long n = parse(major);
        if (n == 1L) {
            return HeroBotGeneration.LEGACY;
        }
        return n >= 2L && n != Long.MAX_VALUE ? HeroBotGeneration.MODERN : HeroBotGeneration.UNKNOWN;
    }

    static Optional<String> heroBotWarning(String friendlyVersion) {
        if (heroBotGeneration(friendlyVersion) != HeroBotGeneration.MODERN) {
            return Optional.empty();
        }
        return Optional.of("HeroBot " + friendlyVersion + " is a 2.x generation; PvP BOT " + TESTED_PVP_BOT
                + " was built against HeroBot 1.x and some of its optional hooks are keyed to 1.x class names. "
                + "Spawning goes through HeroBot's commands, which both generations share, but verify in a "
                + "test world that inhabitants spawn, move and fight");
    }
}
