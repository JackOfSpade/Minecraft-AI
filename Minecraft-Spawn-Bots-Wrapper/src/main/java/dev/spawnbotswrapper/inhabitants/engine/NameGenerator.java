package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.util.BotNameShape;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;

import java.util.function.Predicate;

/**
 * Generates inhabitant names: {@code <prefix>_<Word><suffix>}, plain ASCII, at most 16 characters
 * ({@code [A-Za-z0-9_]{3,16}}, the only shape every layer between the addon and vanilla accepts).
 * <p>
 * Names are a pure function of the bot's seed plus an attempt counter, so deterministic mode reproduces
 * them; on a collision the caller simply asks for the next attempt, which is again deterministic.
 * The addon has its own tiny word list because upstream's generator does blocking network I/O on the
 * server thread and can neither take a prefix nor a seed.
 * <p>
 * Two details keep the result safe and unique:
 * <ul>
 *   <li>The word is <em>chosen among the words that fit</em> the space the prefix leaves, instead of being
 *       cut off, so a long prefix still yields real words.</li>
 *   <li>The random tail starts with a digit, so it can never spell a word of its own by accident.</li>
 * </ul>
 * Uniqueness is the caller's business (only the caller knows the store and the live server); it is passed in
 * as a predicate. Comparison there must be case-insensitive because vanilla resolves player names that way.
 */
public final class NameGenerator {
    public static final int MIN_LENGTH = BotNameShape.MIN_NAME_LENGTH;
    public static final int MAX_LENGTH = BotNameShape.MAX_NAME_LENGTH;
    /** Longest prefix honoured; the same rule the config validator applies (see {@link BotNameShape}). */
    public static final int MAX_PREFIX_LENGTH = BotNameShape.MAX_PREFIX_LENGTH;

    private static final int MIN_WORD_LENGTH = 3;
    /** Candidates tried per suffix width before the suffix grows by one character (if there is room). */
    private static final int ATTEMPTS_PER_WIDENING = 64;
    private static final int MAX_ATTEMPTS = 4096;
    private static final long NAME_STREAM = 0x4E414D45L;
    private static final String DIGITS = "0123456789";
    private static final String BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz";

    private static final String[] WORDS = {
            "Ash", "Bane", "Blaze", "Bone", "Briar", "Cinder", "Cliff", "Crag", "Crow", "Dagger",
            "Dawn", "Dusk", "Dust", "Elm", "Ember", "Fang", "Fern", "Flint", "Fox", "Frost",
            "Gale", "Ghost", "Glade", "Gloom", "Grim", "Hawk", "Hollow", "Iron", "Ivy", "Jade",
            "Kestrel", "Lark", "Lynx", "Marsh", "Mist", "Moss", "Moth", "Night", "Oak", "Onyx",
            "Owl", "Pike", "Quill", "Raven", "Reed", "Rook", "Rune", "Rust", "Sable", "Shade",
            "Silt", "Slate", "Sleet", "Soot", "Spire", "Storm", "Talon", "Thorn", "Tide", "Vale",
            "Vine", "Warden", "Wisp", "Wolf", "Wren", "Yew"
    };

    private final String head;
    private final int room;
    private final int baseSuffixLength;

    /**
     * @param prefix reserved prefix (config value); anything outside {@code [A-Za-z0-9_]} is dropped, it is
     *               cut to {@link #MAX_PREFIX_LENGTH} characters and trailing underscores are trimmed; may be empty
     */
    public NameGenerator(String prefix) {
        String p = sanitize(prefix);
        this.head = p.isEmpty() ? "" : p + "_";
        this.room = MAX_LENGTH - head.length();
        this.baseSuffixLength = room >= 10 ? 4 : 3;
    }

    /** Whether {@code name} has the shape every layer accepts. */
    public static boolean isValid(String name) {
        return BotNameShape.isValidName(name);
    }

    /** Number of distinct words the generator can pick from (for tests and diagnostics). */
    public static int wordCount() {
        return WORDS.length;
    }

    /** The {@code attempt}-th candidate for a bot seed. Pure: same arguments, same name. */
    public String candidate(long botSeed, int attempt) {
        int widening = Math.min(Math.max(0, attempt) / ATTEMPTS_PER_WIDENING,
                room - MIN_WORD_LENGTH - baseSuffixLength);
        int suffixLength = baseSuffixLength + widening;
        int maxWord = room - suffixLength;

        SplitMix64 rng = new SplitMix64(StableHash.of(botSeed, NAME_STREAM, attempt));
        String word = pickWord(rng, maxWord);
        StringBuilder sb = new StringBuilder(MAX_LENGTH).append(head).append(word);
        sb.append(DIGITS.charAt(rng.nextInt(DIGITS.length())));
        for (int i = 1; i < suffixLength; i++) {
            sb.append(BASE36.charAt(rng.nextInt(BASE36.length())));
        }
        return sb.toString();
    }

    /**
     * The first candidate (in the deterministic sequence of {@code botSeed}) that {@code taken} does not
     * reject.
     *
     * @throws IllegalStateException if thousands of consecutive candidates are all taken, which only a
     *                               broken predicate can cause
     */
    public String generate(long botSeed, Predicate<String> taken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String name = candidate(botSeed, attempt);
            if (!taken.test(name)) {
                return name;
            }
        }
        throw new IllegalStateException("no free inhabitant name after " + MAX_ATTEMPTS + " candidates");
    }

    private static String pickWord(SplitMix64 rng, int maxWord) {
        int fitting = 0;
        for (String w : WORDS) {
            if (w.length() <= maxWord) {
                fitting++;
            }
        }
        if (fitting == 0) {
            // Cannot happen for prefixes up to MAX_PREFIX_LENGTH (three-letter words always fit); stay total anyway.
            String w = WORDS[rng.nextInt(WORDS.length)];
            return w.substring(0, Math.max(MIN_WORD_LENGTH, Math.min(w.length(), maxWord)));
        }
        int pick = rng.nextInt(fitting);
        for (String w : WORDS) {
            if (w.length() <= maxWord && pick-- == 0) {
                return w;
            }
        }
        throw new AssertionError("unreachable");
    }

    private static String sanitize(String prefix) {
        return BotNameShape.sanitizePrefix(prefix);
    }
}
