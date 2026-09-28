package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.util.BotNameShape;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;

import java.util.function.Predicate;

/**
 * Generates inhabitant names: {@code <prefix><Word><Word>[<digits>]}, plain ASCII, at most 16 characters
 * ({@code [A-Za-z0-9_]{3,16}}, the only shape every layer between the addon and vanilla accepts).
 * <p>
 * The shape is two distinct words from the addon's own list run together (e.g. {@code DuskRaven},
 * {@code IronFang7}), the way a person actually names an account, rather than a word glued to a block
 * of hex-looking noise -- a fixed prefix in front of every single inhabitant, plus a suffix that reads
 * as a hash, is the opposite of that (a real gamertag essentially never looks like {@code Inh_Fang8rt2}).
 * A short digit tail (0-2 by default, more only once collisions force it) is common on real accounts too,
 * so it is rolled in rather than always or never appended. The reserved prefix (see
 * {@code spawning.namePrefix}) defaults to empty for exactly this reason; an operator who needs one for
 * online-mode Mojang-account safety can still set one, and it is kept exactly as visible as before.
 * <p>
 * Names are a pure function of the bot's seed plus an attempt counter, so deterministic mode reproduces
 * them; on a collision the caller simply asks for the next attempt, which is again deterministic.
 * The addon has its own tiny word list because upstream's generator does blocking network I/O on the
 * server thread and can neither take a prefix nor a seed.
 * <p>
 * Uniqueness is the caller's business (only the caller knows the store and the live server); it is passed in
 * as a predicate. Comparison there must be case-insensitive because vanilla resolves player names that way.
 */
public final class NameGenerator {
    public static final int MAX_LENGTH = BotNameShape.MAX_NAME_LENGTH;
    /** Longest prefix honoured; the same rule the config validator applies (see {@link BotNameShape}). */
    public static final int MAX_PREFIX_LENGTH = BotNameShape.MAX_PREFIX_LENGTH;

    private static final int MIN_WORD_LENGTH = 3;
    /** Candidates tried before the forced digit-tail floor grows by one (if there is room). */
    private static final int ATTEMPTS_PER_WIDENING = 64;
    private static final int MAX_ATTEMPTS = 4096;
    /**
     * Baseline digit-tail length is rolled uniformly in {@code [0, BASELINE_MAX_DIGITS)}; widening only
     * ever raises the floor above whatever this rolls. Sized so the word-pair-plus-digits space at the
     * default (empty) prefix comfortably exceeds the old single-word design's space (word x digit x
     * base36^3, about 30 million): with 66 words, 66*65 ordered pairs times sum(10^0..10^4) candidate
     * digit widths is about 48 million, so two structures independently landing on the same name by pure
     * chance stays as vanishingly unlikely as it was before -- collision handling still exists (see
     * ATTEMPTS_PER_WIDENING) but is not something a realistic bot count should ever actually reach.
     */
    private static final int BASELINE_MAX_DIGITS = 6;
    private static final long NAME_STREAM = 0x4E414D45L;
    private static final String DIGITS = "0123456789";

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

    /**
     * @param prefix reserved prefix (config value); anything outside {@code [A-Za-z0-9_]} is dropped, it is
     *               cut to {@link #MAX_PREFIX_LENGTH} characters and trailing underscores are trimmed; may be empty
     */
    public NameGenerator(String prefix) {
        String p = sanitize(prefix);
        this.head = p.isEmpty() ? "" : p + "_";
        this.room = MAX_LENGTH - head.length();
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
        SplitMix64 rng = new SplitMix64(StableHash.of(botSeed, NAME_STREAM, attempt));
        // The digit tail may grow enough to force one-word mode (see below) -- widening's ceiling is
        // "leave room for at least one real word", not "leave room for two", so a tight operator-
        // configured prefix still keeps widening's entire point: attempts never stop finding new
        // combinations just because the two-word combo space for that prefix is small.
        int wideningCeiling = Math.max(0, room - MIN_WORD_LENGTH);
        int widening = Math.min(Math.max(0, attempt) / ATTEMPTS_PER_WIDENING, wideningCeiling);
        int digitCount = Math.min(Math.max(widening, rng.nextInt(BASELINE_MAX_DIGITS)), wideningCeiling);

        int wordBudget = room - digitCount;
        String combo;
        if (wordBudget >= 2 * MIN_WORD_LENGTH) {
            combo = pickWordPair(rng, wordBudget);
        } else if (wordBudget >= MIN_WORD_LENGTH) {
            combo = pickWord(rng, wordBudget, null);
        } else {
            // Pathological: room for less than one real word. NameGenerator never refuses to produce
            // a candidate outright, so degrade to a truncated word plus whatever still fits.
            String w = WORDS[rng.nextInt(WORDS.length)];
            combo = w.substring(0, Math.max(1, Math.min(w.length(), Math.max(1, room))));
        }
        // combo may be shorter than wordBudget (a short word was picked); the digits already rolled
        // stay as rolled rather than expanding to fill the gap, so widening's forced floor is the only
        // thing that reliably grows the tail -- an unfilled gap here is just a slightly shorter name.

        StringBuilder sb = new StringBuilder(MAX_LENGTH).append(head).append(combo);
        for (int i = 0; i < digitCount; i++) {
            sb.append(DIGITS.charAt(rng.nextInt(DIGITS.length())));
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

    /** Two distinct words run together, each fitting inside {@code budget} combined. */
    private static String pickWordPair(SplitMix64 rng, int budget) {
        String first = pickWord(rng, budget - MIN_WORD_LENGTH, null);
        String second = pickWord(rng, budget - first.length(), first);
        return first + second;
    }

    /**
     * A random word of at most {@code maxWord} characters, distinct from {@code exclude} (ignored when
     * {@code exclude} is {@code null}). Falls back to a truncated word if nothing fits or nothing distinct
     * fits -- this never throws and never returns an oversized word.
     */
    private static String pickWord(SplitMix64 rng, int maxWord, String exclude) {
        int fitting = 0;
        for (String w : WORDS) {
            if (w.length() <= maxWord && !w.equals(exclude)) {
                fitting++;
            }
        }
        if (fitting == 0) {
            // Cannot happen for prefixes up to MAX_PREFIX_LENGTH (three-letter words always fit, and the
            // list has several of them, so excluding one still leaves another); stay total anyway.
            String w = WORDS[rng.nextInt(WORDS.length)];
            return w.substring(0, Math.max(MIN_WORD_LENGTH, Math.min(w.length(), Math.max(1, maxWord))));
        }
        int pick = rng.nextInt(fitting);
        for (String w : WORDS) {
            if (w.length() <= maxWord && !w.equals(exclude) && pick-- == 0) {
                return w;
            }
        }
        throw new AssertionError("unreachable");
    }

    private static String sanitize(String prefix) {
        return BotNameShape.sanitizePrefix(prefix);
    }
}
