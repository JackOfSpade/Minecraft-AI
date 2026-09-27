package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class NameGeneratorTest {

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Two capitalised words (greedy lowercase runs cannot cross an uppercase letter) then digits. */
    private static final Pattern TWO_WORDS_THEN_DIGITS = Pattern.compile("^([A-Z][a-z]*)([A-Z][a-z]*)([0-9]*)$");
    private static final List<String> PREFIXES = List.of("Inh", "", "A", "Abcdefgh", "Bot_", "x_y", "9", "Long_Prefix_That_Is_Cut");

    @Test
    void namesAlwaysHaveTheSafeShapeForAnyPrefix() {
        SplitMix64 seeds = new SplitMix64(1);
        for (String prefix : PREFIXES) {
            NameGenerator gen = new NameGenerator(prefix);
            for (int i = 0; i < 3000; i++) {
                String name = gen.candidate(seeds.nextLong(), i % 200);
                assertTrue(SHAPE.matcher(name).matches(), "bad name '" + name + "' for prefix '" + prefix + "'");
                assertTrue(NameGenerator.isValid(name));
            }
        }
    }

    @Test
    void hostilePrefixesAreSanitisedNotTrusted() {
        for (String prefix : new String[]{null, "", "____", "a b!c", "äöü", "  ", "-.+", "12345678901234567890"}) {
            NameGenerator gen = new NameGenerator(prefix);
            for (int i = 0; i < 200; i++) {
                String name = gen.candidate(i * 7919L, i);
                assertTrue(SHAPE.matcher(name).matches(), "bad name '" + name + "' for prefix '" + prefix + "'");
            }
        }
    }

    @Test
    void theEightCharacterPrefixLeavesRoomForAtLeastOneRealWordAndSometimesTwo() {
        NameGenerator gen = new NameGenerator("Abcdefgh");
        boolean sawTwoWords = false;
        for (int attempt = 0; attempt < 200; attempt++) {
            String name = gen.candidate(5, attempt);
            assertTrue(name.startsWith("Abcdefgh_"), name);
            assertTrue(name.length() <= 16, name);
            // "Abcdefgh_" (9 chars) + at least one >= 3-letter word => at least 12 characters, always.
            assertTrue(name.length() >= 9 + 3, name);
            if (TWO_WORDS_THEN_DIGITS.matcher(name.substring(9)).matches()) {
                sawTwoWords = true;
            }
        }
        // A high digit roll degrades this tight a budget to one word sometimes (by design: widening
        // must be able to shrink the word part to grow the digit tail), but not on every attempt.
        assertTrue(sawTwoWords, "never saw two-word mode across 200 attempts");
    }

    @Test
    void longPrefixesAreCutToEightCharacters() {
        NameGenerator gen = new NameGenerator("Abcdefghijkl");
        assertTrue(gen.candidate(5, 0).startsWith("Abcdefgh_"));
    }

    @Test
    void aConfiguredPrefixProducesTwoWordsAndAnOptionalDigitTail() {
        NameGenerator gen = new NameGenerator("Inh");
        for (int attempt = 0; attempt < 200; attempt++) {
            String name = gen.candidate(123, attempt);
            assertTrue(name.startsWith("Inh_"), name);
            String tail = name.substring(4);
            Matcher m = TWO_WORDS_THEN_DIGITS.matcher(tail);
            assertTrue(m.matches(), tail);
            assertNotEquals(m.group(1), m.group(2), "the two words must be distinct: " + tail);
        }
    }

    @Test
    void emptyPrefixGivesNoLeadingUnderscore() {
        NameGenerator gen = new NameGenerator("");
        for (int i = 0; i < 500; i++) {
            String name = gen.candidate(i, 0);
            assertFalse(name.startsWith("_"), name);
            assertTrue(Character.isUpperCase(name.charAt(0)), name);
        }
    }

    @Test
    void theDigitTailIsAlwaysAtTheEndSoItCanNeverSplitAWordInTwo() {
        NameGenerator gen = new NameGenerator("");
        for (int i = 0; i < 2000; i++) {
            String name = gen.candidate(i * 31L, 0);
            Matcher m = TWO_WORDS_THEN_DIGITS.matcher(name);
            assertTrue(m.matches(), name);
            // Once the digit run starts, nothing but digits follows -- proven by the anchored regex
            // itself; this test exists to pin that the tail is a *suffix*, not interleaved.
            String digits = m.group(3);
            assertTrue(name.endsWith(digits));
        }
    }

    @Test
    void bothWordsInAPairAreAlwaysDistinct() {
        NameGenerator gen = new NameGenerator("");
        SplitMix64 seeds = new SplitMix64(2024);
        for (int i = 0; i < 5000; i++) {
            String name = gen.candidate(seeds.nextLong(), 0);
            Matcher m = TWO_WORDS_THEN_DIGITS.matcher(name);
            assertTrue(m.matches(), name);
            assertNotEquals(m.group(1), m.group(2), name);
        }
    }

    @Test
    void sameSeedAndAttemptAlwaysGiveTheSameName() {
        NameGenerator a = new NameGenerator("Inh");
        NameGenerator b = new NameGenerator("Inh");
        for (int i = 0; i < 500; i++) {
            long seed = new SplitMix64(i).nextLong();
            for (int attempt = 0; attempt < 5; attempt++) {
                assertEquals(a.candidate(seed, attempt), b.candidate(seed, attempt));
            }
        }
    }

    @Test
    void differentAttemptsGiveDifferentCandidates() {
        NameGenerator gen = new NameGenerator("Inh");
        Set<String> seen = new HashSet<>();
        for (int attempt = 0; attempt < 200; attempt++) {
            seen.add(gen.candidate(777, attempt));
        }
        assertTrue(seen.size() >= 195, "attempts barely differ: " + seen.size());
    }

    @Test
    void generateReturnsTheFirstFreeCandidateOfTheDeterministicSequence() {
        NameGenerator gen = new NameGenerator("Inh");
        long seed = 99;
        Set<String> taken = new HashSet<>();
        taken.add(gen.candidate(seed, 0).toLowerCase(Locale.ROOT));
        taken.add(gen.candidate(seed, 1).toLowerCase(Locale.ROOT));
        String name = gen.generate(seed, n -> taken.contains(n.toLowerCase(Locale.ROOT)));
        assertEquals(gen.candidate(seed, 2), name);
        // and again: same answer
        assertEquals(name, gen.generate(seed, n -> taken.contains(n.toLowerCase(Locale.ROOT))));
    }

    @Test
    void uniquenessAcrossManyBotsIsCaseInsensitive() {
        NameGenerator gen = new NameGenerator("Inh");
        Set<String> used = new HashSet<>();
        SplitMix64 seeds = new SplitMix64(7);
        for (int i = 0; i < 20_000; i++) {
            String name = gen.generate(seeds.nextLong(), n -> used.contains(n.toLowerCase(Locale.ROOT)));
            assertTrue(used.add(name.toLowerCase(Locale.ROOT)), "duplicate " + name);
            assertTrue(NameGenerator.isValid(name));
        }
        assertEquals(20_000, used.size());
    }

    @Test
    void collisionsStillResolveWithTheShortestPrefixSpace() {
        // an 8-character prefix leaves the smallest name space; a lot of bots must still get unique names
        NameGenerator gen = new NameGenerator("Abcdefgh");
        Set<String> used = new HashSet<>();
        SplitMix64 seeds = new SplitMix64(8);
        for (int i = 0; i < 5_000; i++) {
            String name = gen.generate(seeds.nextLong(), n -> used.contains(n.toLowerCase(Locale.ROOT)));
            assertTrue(used.add(name.toLowerCase(Locale.ROOT)), "duplicate " + name);
            assertTrue(SHAPE.matcher(name).matches(), name);
        }
    }

    @Test
    void stubbornCollisionsWidenTheDigitTailInsteadOfGivingUp() {
        NameGenerator gen = new NameGenerator("Inh");
        long seed = 4242;
        // reject the first 200 candidates outright
        Set<String> rejected = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            rejected.add(gen.candidate(seed, i));
        }
        String name = gen.generate(seed, rejected::contains);
        assertFalse(rejected.contains(name));
        assertTrue(SHAPE.matcher(name).matches());
    }

    @Test
    void aPredicateThatRejectsEverythingIsReportedNotLoopedForever() {
        NameGenerator gen = new NameGenerator("Inh");
        assertThrows(IllegalStateException.class, () -> gen.generate(1, n -> true));
    }

    @Test
    void wordListIsBigDistinctAsciiAndHasShortWordsForLongPrefixes() {
        assertTrue(NameGenerator.wordCount() >= 60);
        // every generated name carries two words from the list; collect them over many seeds
        Set<String> words = new HashSet<>();
        NameGenerator gen = new NameGenerator("");
        for (int i = 0; i < 20_000; i++) {
            Matcher m = TWO_WORDS_THEN_DIGITS.matcher(gen.candidate(i, 0));
            assertTrue(m.matches());
            words.add(m.group(1));
            words.add(m.group(2));
        }
        assertTrue(words.size() >= 55, "only " + words.size() + " distinct words");
        long shortWords = words.stream().filter(w -> w.length() <= 4).count();
        assertTrue(shortWords >= 8, "need short words for long prefixes, have " + shortWords);
        for (String w : words) {
            assertTrue(Pattern.matches("[A-Z][a-z]{2,6}", w), "unexpected word shape: " + w);
        }
    }

    @Test
    void isValidRejectsWhatVanillaOrUpstreamWouldNotTake() {
        assertTrue(NameGenerator.isValid("Inh_Wolf7k2a"));
        assertTrue(NameGenerator.isValid("abc"));
        assertTrue(NameGenerator.isValid("A234567890123456"));
        assertFalse(NameGenerator.isValid(null));
        assertFalse(NameGenerator.isValid(""));
        assertFalse(NameGenerator.isValid("ab"));
        assertFalse(NameGenerator.isValid("A2345678901234567"));
        assertFalse(NameGenerator.isValid("has space"));
        assertFalse(NameGenerator.isValid("dash-ed"));
        assertFalse(NameGenerator.isValid("äbc"));
    }
}
