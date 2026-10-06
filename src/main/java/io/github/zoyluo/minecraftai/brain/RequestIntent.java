package io.github.zoyluo.minecraftai.brain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a player's chat line asks for, as far as tool routing is concerned. A pure function of the
 * text: the verbs and the few grammar words are a small closed vocabulary, the item nouns are
 * resolved against the item registry ({@link ItemNouns}).
 *
 * <p>The line is read sentence by sentence and clause by clause. A verb counts only where it is a
 * request: not inside a question about what happened ("how many logs did you gather?"), not after
 * a negation ("don't collect any more"), and not as a noun or pronoun ("the farm", "give me mine").
 * Its object phrase decides what it asks for: a raw resource, a crafted item, or stock the bot
 * already carries ("your logs", "the logs you collected").</p>
 *
 * @param acquiresRaw       the player wants raw resources newly collected (get/gather/mine/chop ...
 *                          a resource), so stock the bot already carries must not satisfy it
 * @param quantityStated    the collection clause (or the handoff that follows it) names a number
 * @param handsOverAcquired a player is asked to receive what was collected (explicit give/hand/bring
 *                          with a player, "me" or an implicit "here" as the recipient)
 * @param handsOverPart     the handoff names a quantity other than the collected one ("gather 32 logs and
 *                          give me 16", "... give me half"), so it is not the whole collection
 * @param craftsOutcome     a crafted item is part of the outcome (craft/make/get an iron pickaxe,
 *                          or hand over a table), as opposed to only raw materials
 */
record RequestIntent(boolean acquiresRaw, boolean quantityStated, boolean handsOverAcquired,
                     boolean handsOverPart, boolean craftsOutcome) {
    static final RequestIntent NONE = new RequestIntent(false, false, false, false, false);

    private enum Verb { ACQUIRE_CORE, ACQUIRE_SOFT, HANDOFF, CRAFT }

    private enum NounPhrase { NONE, PRONOUN, GENERIC_STOCK, RAW, CRAFTED, UNKNOWN }

    // Verbs a player uses to have resources collected. "Core" ones always mean physical collection, so
    // an unrecognised object still counts; the soft ones ("get", "need") count only for a resolved raw
    // item (or an unrecognised one with a number), because "get ready" or "get in the boat" are not requests.
    private static final Map<String, Verb> VERBS = Map.ofEntries(
            Map.entry("gather", Verb.ACQUIRE_CORE), Map.entry("gathering", Verb.ACQUIRE_CORE),
            Map.entry("collect", Verb.ACQUIRE_CORE), Map.entry("collecting", Verb.ACQUIRE_CORE),
            Map.entry("harvest", Verb.ACQUIRE_CORE), Map.entry("harvesting", Verb.ACQUIRE_CORE),
            Map.entry("forage", Verb.ACQUIRE_CORE), Map.entry("foraging", Verb.ACQUIRE_CORE),
            Map.entry("farm", Verb.ACQUIRE_CORE), Map.entry("farming", Verb.ACQUIRE_CORE),
            Map.entry("chop", Verb.ACQUIRE_CORE), Map.entry("chopping", Verb.ACQUIRE_CORE),
            Map.entry("mine", Verb.ACQUIRE_CORE), Map.entry("mining", Verb.ACQUIRE_CORE),
            Map.entry("get", Verb.ACQUIRE_SOFT), Map.entry("getting", Verb.ACQUIRE_SOFT),
            Map.entry("grab", Verb.ACQUIRE_SOFT), Map.entry("grabbing", Verb.ACQUIRE_SOFT),
            Map.entry("fetch", Verb.ACQUIRE_SOFT), Map.entry("fetching", Verb.ACQUIRE_SOFT),
            Map.entry("obtain", Verb.ACQUIRE_SOFT), Map.entry("obtaining", Verb.ACQUIRE_SOFT),
            Map.entry("acquire", Verb.ACQUIRE_SOFT), Map.entry("acquiring", Verb.ACQUIRE_SOFT),
            Map.entry("need", Verb.ACQUIRE_SOFT), Map.entry("want", Verb.ACQUIRE_SOFT),
            Map.entry("require", Verb.ACQUIRE_SOFT),
            Map.entry("dig", Verb.ACQUIRE_SOFT), Map.entry("digging", Verb.ACQUIRE_SOFT),
            Map.entry("cut", Verb.ACQUIRE_SOFT), Map.entry("cutting", Verb.ACQUIRE_SOFT),
            Map.entry("fell", Verb.ACQUIRE_SOFT), Map.entry("felling", Verb.ACQUIRE_SOFT),
            Map.entry("pick", Verb.ACQUIRE_SOFT), Map.entry("picking", Verb.ACQUIRE_SOFT),
            Map.entry("stock", Verb.ACQUIRE_SOFT), Map.entry("stocking", Verb.ACQUIRE_SOFT),
            Map.entry("give", Verb.HANDOFF), Map.entry("giving", Verb.HANDOFF),
            Map.entry("hand", Verb.HANDOFF), Map.entry("handing", Verb.HANDOFF),
            Map.entry("deliver", Verb.HANDOFF), Map.entry("delivering", Verb.HANDOFF),
            Map.entry("drop", Verb.HANDOFF), Map.entry("dropping", Verb.HANDOFF),
            Map.entry("bring", Verb.HANDOFF), Map.entry("bringing", Verb.HANDOFF),
            Map.entry("send", Verb.HANDOFF), Map.entry("sending", Verb.HANDOFF),
            Map.entry("toss", Verb.HANDOFF), Map.entry("tossing", Verb.HANDOFF),
            Map.entry("throw", Verb.HANDOFF), Map.entry("throwing", Verb.HANDOFF),
            Map.entry("pass", Verb.HANDOFF), Map.entry("passing", Verb.HANDOFF),
            Map.entry("share", Verb.HANDOFF), Map.entry("sharing", Verb.HANDOFF),
            Map.entry("donate", Verb.HANDOFF), Map.entry("gift", Verb.HANDOFF),
            Map.entry("craft", Verb.CRAFT), Map.entry("crafting", Verb.CRAFT),
            Map.entry("make", Verb.CRAFT), Map.entry("making", Verb.CRAFT),
            Map.entry("build", Verb.CRAFT), Map.entry("building", Verb.CRAFT),
            Map.entry("create", Verb.CRAFT), Map.entry("creating", Verb.CRAFT),
            Map.entry("smelt", Verb.CRAFT), Map.entry("smelting", Verb.CRAFT),
            Map.entry("cook", Verb.CRAFT), Map.entry("cooking", Verb.CRAFT),
            Map.entry("forge", Verb.CRAFT), Map.entry("bake", Verb.CRAFT));
    /** Core verbs that may stand without an object ("start gathering", "keep mining"); "mine" may be a pronoun. */
    private static final Set<String> BARE_VERBS = Set.of(
            "gather", "gathering", "collect", "collecting", "harvest", "harvesting",
            "forage", "foraging", "farm", "farming", "chop", "chopping", "mining");
    // "farm"/"mine"/"drop" are also nouns or pronouns ("the farm", "that is mine"): after one of these
    // the word is not a verb. A be/have form before an -ing verb is a statement, not a request.
    private static final Set<String> NOUN_USE_BEFORE = Set.of(
            "the", "a", "an", "my", "our", "your", "ur", "their", "his", "her", "its", "this", "that",
            "these", "those", "at", "in", "on", "near", "by", "from", "of", "around", "inside", "into",
            "every", "each", "some");
    private static final Set<String> STATEMENT_BEFORE = Set.of(
            "is", "are", "was", "were", "be", "been", "am", "im", "has", "have", "had", "did", "didnt",
            "does", "doesnt", "ive", "youve", "weve", "youre");
    private static final Set<String> NEGATORS = Set.of(
            "dont", "not", "never", "no", "stop", "cancel", "skip", "without", "forget", "ignore",
            "nevermind", "nvm", "cease", "avoid", "wont", "shouldnt", "cant", "cannot", "quit", "abort", "nah");
    /** "no problem, gather 32 logs": a courtesy, not a refusal. */
    private static final Set<String> COURTESY_AFTER_NO = Set.of(
            "problem", "problems", "prob", "probs", "worries", "worry", "rush", "hurry", "biggie");
    private static final int NEGATION_REACH = 4;
    /** Verbs that make the player's wish physical whatever the noun is: nothing is mined or chopped by crafting. */
    private static final Set<String> PHYSICAL_VERBS = Set.of(
            "mine", "mining", "dig", "digging", "chop", "chopping", "harvest", "harvesting",
            "forage", "foraging", "farm", "farming");
    private static final Set<String> COMMA = Set.of(",");
    private static final Set<String> CLAUSE_WORDS = Set.of(
            "then", "and", "also", "plus", "afterwards", "afterward", "next", "after", "before", "once",
            "while", "so", "but", "or", "until", "when", "if", "because", "since");
    private static final Set<String> QUESTION_WORDS = Set.of(
            "how", "what", "whats", "which", "who", "whos", "whose", "where", "wheres", "when", "whens", "why");
    private static final Set<String> QUESTION_AUXILIARIES = Set.of(
            "did", "does", "do", "have", "has", "had", "are", "is", "was", "were", "am", "should", "shall");
    private static final Set<String> SUBJECTS = Set.of(
            "you", "u", "ya", "i", "we", "he", "she", "they", "it", "there", "this", "that");
    private static final Set<String> REQUESTS_AFTER_YOU = Set.of("mind", "able", "willing", "going", "want", "wanna");
    private static final Set<String> POLITE_OPENERS = Set.of("can", "could", "would", "will", "please", "pls", "plz");
    /** A question word may follow a greeting and the bot's name ("hey moss, how many ..."). */
    private static final int QUESTION_OPENING_WORDS = 5;
    // Words that end an object phrase.
    private static final Set<String> OBJECT_ENDS = Set.of(
            "to", "for", "from", "with", "in", "at", "on", "near", "by", "beside", "using", "as", "so", "because",
            "if", "when", "while", "after", "before", "until", "back", "here", "there", "now", "please", "pls",
            "plz", "thanks", "thx", "too", "again", "then", "and", "or", "but", "you", "youve", "youre", "u", "ya",
            "which", "who", "what", "whatever", "that", "is", "are", "was", "were", "will", "would", "can", "could",
            "should", "asap", "soon", "later", "today", "tonight", "quickly", "fast", "over", "around", "round",
            "together", "nearby", "close", "closer");
    private static final Set<String> YOU = Set.of("you", "youve", "youre", "u", "ya");
    private static final Set<String> YOURS = Set.of("your", "ur", "yours", "urs");
    /** "the logs that/what you have": a relative clause about the bot's own stock. */
    private static final Set<String> RELATIVE_WORDS = Set.of("that", "which", "what", "whatever");
    /** "in/on/from your inventory": where the bot's own stock is. */
    private static final Set<String> CARRIED_PLACES = Set.of("in", "on", "from");
    private static final Set<String> RECIPIENT_PREPOSITIONS = Set.of("to", "for", "near", "by", "beside");
    /** "stock up ON logs", "cut DOWN trees", "hand OVER logs", "mine FOR diamonds": particles that belong to the verb. */
    private static final Set<String> UP_VERBS = Set.of("pick", "picking", "stock", "stocking");
    private static final Set<String> UP_PREPOSITIONS = Set.of("on", "with");
    private static final Set<String> DIRECTION_VERBS = Set.of(
            "cut", "cutting", "chop", "chopping", "fell", "felling", "dig", "digging");
    private static final Set<String> DIRECTION_PARTICLES = Set.of("down", "out", "up");
    private static final Set<String> SEARCH_VERBS = Set.of("mine", "mining", "dig", "digging", "forage", "foraging");
    private static final Set<String> HAND_OVER_VERBS = Set.of("hand", "handing", "give", "giving");
    // Determiners and quantity words in front of the noun.
    private static final Set<String> VAGUE_QUANTITY = Set.of(
            "the", "some", "any", "more", "all", "both", "another", "extra", "additional", "few", "several",
            "couple", "many", "lots", "lot", "bunch", "ton", "tons", "plenty", "enough", "half", "full",
            "whole", "entire", "rest", "of", "other", "same", "these", "those", "this", "that", "each",
            "every", "my", "our", "his", "her", "their", "your", "ur", "yours");
    /** Quantity words that name a share rather than a determiner ("give me half"). */
    private static final Set<String> PART_WORDS = Set.of("half", "some", "few", "several", "couple");
    private static final Set<String> ARTICLES = Set.of("a", "an");
    private static final Set<String> STACK_WORDS = Set.of("stack", "stacks", "dozen", "dozens");
    private static final Set<String> NUMBER_WORDS = Set.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
            "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand");
    private static final Set<String> RECIPIENTS = Set.of("me", "us", "myself", "ourselves", "him", "her");
    private static final Set<String> PRONOUNS = Set.of("it", "them", "em", "these", "those", "this", "that");
    private static final Set<String> GENERIC_STOCK = Set.of(
            "stuff", "things", "thing", "items", "item", "everything", "anything", "inventory", "loot", "stock",
            "belongings", "junk", "supplies", "goods");
    /** After "get": movement and idiom, not resources ("get over here", "get ready"). */
    private static final Set<String> GET_IDIOMS = Set.of(
            "over", "to", "here", "there", "back", "away", "up", "down", "out", "closer", "close", "inside",
            "outside", "in", "on", "off", "ready", "going", "started", "moving", "lost", "near", "along");

    /** Classifies a chat line; {@code playerNames} are the online players a handoff may name as recipient. */
    static RequestIntent parse(String text, Set<String> playerNames) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        Set<String> names = new HashSet<>();
        if (playerNames != null) {
            playerNames.forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
        }
        Reading reading = new Reading();
        for (Sentence sentence : sentences(text)) {
            // A comma ends a question too: "how many logs do you have, gather 32 more" still asks for work.
            List<List<String>> parts = split(sentence.words(), COMMA);
            for (int part = 0; part < parts.size(); part++) {
                boolean lastPart = part == parts.size() - 1;
                if (isQuestion(parts.get(part), sentence.questionMark() && lastPart)) {
                    continue;
                }
                for (List<String> clause : split(parts.get(part), CLAUSE_WORDS)) {
                    readClause(clause, names, reading);
                }
            }
        }
        return new RequestIntent(reading.acquired, reading.quantity, reading.handsOver, reading.partial,
                reading.crafts);
    }

    static RequestIntent parse(String text) {
        return parse(text, Set.of());
    }

    /** Mutable result of reading the clauses of one chat line in order. */
    private static final class Reading {
        boolean acquired;
        boolean quantity;
        boolean handsOver;
        boolean partial;
        boolean crafts;
        /** An acquisition verb without an object ("gather and hand me 32 logs") borrows the handoff's object. */
        boolean objectPending;
        /** The number or share words of the collection clause, to compare with what the handoff names. */
        List<String> collectQuantity = List.of();
    }

    /** What follows one verb: the object phrase and who receives it. */
    private static final class Phrase {
        int end;
        final List<String> noun = new ArrayList<>();
        /** Only quantity words, no noun ("give me half", "gather some more"). */
        boolean consumedOnly;
        boolean recipientInline;
        boolean recipientAfter;
        boolean implicitRecipient;
        /** The object is stock the bot already carries ("your logs", "the logs you collected"). */
        boolean carried;
        boolean quantified;
        /** The numbers and share words in front of the noun ("16", "half", "stack"), in order. */
        final List<String> quantityWords = new ArrayList<>();
        /** "get over here", "get ready": movement, not an object. */
        boolean idiom;

        boolean recipient() {
            return recipientInline || recipientAfter || implicitRecipient;
        }
    }

    private static void readClause(List<String> clause, Set<String> names, Reading reading) {
        int i = 0;
        while (i < clause.size()) {
            String word = clause.get(i);
            Verb verb = verbAt(clause, i);
            if (verb == null || !inVerbPosition(clause, i)) {
                i++;
                continue;
            }
            boolean negated = negatedBefore(clause, i);
            Phrase phrase = readPhrase(clause, i + 1, word, names);
            i = Math.max(phrase.end, i + 1);
            if (!negated) {
                apply(verb, word, phrase, reading);
            }
        }
    }

    private static void apply(Verb verb, String word, Phrase phrase, Reading reading) {
        NounPhrase noun = nounPhrase(verb, phrase);
        switch (verb) {
            case ACQUIRE_CORE, ACQUIRE_SOFT -> {
                if (phrase.idiom || phrase.carried) {
                    return;
                }
                // An unrecognised noun counts for a core verb only: "get 5 steaks" or "get 3 levels" name
                // nothing a collection tool can fetch, so hiding give_item for them would only strand the request.
                boolean core = verb == Verb.ACQUIRE_CORE;
                boolean collects = switch (noun) {
                    case RAW -> true;
                    // The recipe that makes a natural block (sandstone, clay) is not how a player gets it, and
                    // "mine"/"dig" cannot mean crafting: those verbs keep the request physical.
                    case CRAFTED -> PHYSICAL_VERBS.contains(word);
                    case UNKNOWN -> core;
                    case NONE -> BARE_VERBS.contains(word);
                    default -> false;
                };
                if (collects) {
                    reading.acquired = true;
                    reading.quantity |= phrase.quantified;
                    reading.objectPending = noun == NounPhrase.NONE;
                    reading.collectQuantity = phrase.quantityWords;
                } else if (noun == NounPhrase.CRAFTED) {
                    reading.crafts = true;
                }
            }
            case CRAFT -> reading.crafts |= noun == NounPhrase.CRAFTED;
            case HANDOFF -> {
                // Only handing over what was just collected matters. A handoff of carried stock, of
                // something that is no item ("a status update") or to a non-player is not part of it.
                boolean ofItems = noun == NounPhrase.PRONOUN || noun == NounPhrase.RAW
                        || noun == NounPhrase.CRAFTED || noun == NounPhrase.NONE;
                if (!reading.acquired || phrase.carried || !ofItems || !phrase.recipient()) {
                    return;
                }
                reading.handsOver = true;
                reading.crafts |= noun == NounPhrase.CRAFTED;
                if (reading.objectPending) {
                    reading.quantity |= phrase.quantified;
                    reading.collectQuantity = phrase.quantityWords;
                } else if (!phrase.quantityWords.isEmpty() && !phrase.quantityWords.equals(reading.collectQuantity)) {
                    reading.partial = true;
                }
            }
        }
    }

    private static NounPhrase nounPhrase(Verb verb, Phrase phrase) {
        List<String> noun = phrase.noun;
        if (noun.isEmpty()) {
            if (!phrase.consumedOnly) {
                return NounPhrase.NONE;
            }
            // "give me those" hands something over; "gather some more" is still a bare gather.
            return verb == Verb.HANDOFF ? NounPhrase.PRONOUN : NounPhrase.NONE;
        }
        if (noun.size() == 1 && PRONOUNS.contains(noun.get(0))) {
            return NounPhrase.PRONOUN;
        }
        if (verb != Verb.ACQUIRE_CORE && noun.stream().anyMatch(GENERIC_STOCK::contains)) {
            return NounPhrase.GENERIC_STOCK;
        }
        return switch (ItemNouns.classify(noun)) {
            case RAW -> NounPhrase.RAW;
            case CRAFTED -> NounPhrase.CRAFTED;
            case UNKNOWN -> NounPhrase.UNKNOWN;
        };
    }

    private static Verb verbAt(List<String> clause, int index) {
        String word = clause.get(index);
        if (UP_VERBS.contains(word)) {
            return at(clause, index + 1).equals("up") ? VERBS.get(word) : null;
        }
        if (word.equals("like")) {
            // Only "I'd like 32 logs" / "we would like some wood".
            String before = at(clause, index - 1);
            return before.equals("id") || before.equals("wed") || before.equals("would") ? Verb.ACQUIRE_SOFT : null;
        }
        return VERBS.get(word);
    }

    private static boolean inVerbPosition(List<String> clause, int index) {
        String before = at(clause, index - 1);
        return !NOUN_USE_BEFORE.contains(before) && !STATEMENT_BEFORE.contains(before);
    }

    /**
     * Whether a negation in this clause governs the verb at {@code index}. It reaches back a few words
     * ("do not chop any trees", "no more mining") but stops at an earlier verb with an object of its own:
     * that verb is what the negation governs, so "dont get logs get stone" still asks for the stone.
     */
    private static boolean negatedBefore(List<String> clause, int index) {
        for (int i = index - 1; i >= Math.max(0, index - NEGATION_REACH); i--) {
            if (negates(clause, i)) {
                return true;
            }
            if (verbAt(clause, i) != null && inVerbPosition(clause, i) && !passesOnToNextVerb(clause, i)) {
                return false;
            }
        }
        return false;
    }

    /** A negator word, except where it is a courtesy or opens a suggestion: "no problem", "why not", "why dont you". */
    private static boolean negates(List<String> clause, int index) {
        String word = clause.get(index);
        return NEGATORS.contains(word)
                && !(word.equals("no") && COURTESY_AFTER_NO.contains(at(clause, index + 1)))
                && !at(clause, index - 1).equals("why");
    }

    /** "need to gather", "want you to mine": the earlier verb only introduces the next one, so a negation passes through it. */
    private static boolean passesOnToNextVerb(List<String> clause, int index) {
        String next = at(clause, index + 1);
        return next.equals("to") || YOU.contains(next) && at(clause, index + 2).equals("to");
    }

    /** Reads everything after a verb: particles, an inline recipient, quantity, the noun and what follows it. */
    private static Phrase readPhrase(List<String> clause, int from, String verb, Set<String> names) {
        Phrase phrase = new Phrase();
        int j = from;
        String next = at(clause, j);
        if (UP_VERBS.contains(verb)) {
            j += UP_PREPOSITIONS.contains(at(clause, j + 1)) ? 2 : 1; // verbAt required the "up"
        } else if (DIRECTION_VERBS.contains(verb) && DIRECTION_PARTICLES.contains(next)
                || SEARCH_VERBS.contains(verb) && next.equals("for")) {
            j++;
        } else if (HAND_OVER_VERBS.contains(verb) && next.equals("over")) {
            j++;
            phrase.implicitRecipient = true;
        } else if ((verb.equals("get") || verb.equals("getting")) && GET_IDIOMS.contains(next)) {
            phrase.idiom = true;
            phrase.end = j;
            return phrase;
        }
        String candidate = at(clause, j);
        boolean followedByObject = j + 1 < clause.size() && !OBJECT_ENDS.contains(clause.get(j + 1));
        if (RECIPIENTS.contains(candidate) && !(candidate.equals("her") && !followedByObject)
                || (candidate.equals("them") || names.contains(candidate)) && followedByObject) {
            phrase.recipientInline = true;
            j++;
        }
        int consumed = 0;
        while (j < clause.size()) {
            String word = clause.get(j);
            if (isNumber(word) || STACK_WORDS.contains(word)) {
                phrase.quantified = true;
                phrase.quantityWords.add(word);
            } else if (ARTICLES.contains(word)) {
                phrase.quantified = true;
            } else if (!VAGUE_QUANTITY.contains(word)) {
                break;
            } else if (PART_WORDS.contains(word)) {
                phrase.quantityWords.add(word);
            }
            phrase.carried |= YOURS.contains(word);
            consumed++;
            j++;
        }
        while (j < clause.size() && !OBJECT_ENDS.contains(clause.get(j)) && !VERBS.containsKey(clause.get(j))) {
            phrase.noun.add(clause.get(j));
            j++;
        }
        phrase.consumedOnly = phrase.noun.isEmpty() && consumed > 0;
        phrase.carried |= phrase.noun.stream().anyMatch(YOURS::contains);
        String after = at(clause, j);
        String afterNext = at(clause, j + 1);
        if (YOU.contains(after)
                || RELATIVE_WORDS.contains(after) && YOU.contains(afterNext)
                || CARRIED_PLACES.contains(after) && (YOURS.contains(afterNext) || YOU.contains(afterNext))) {
            phrase.carried = true;
        }
        phrase.recipientAfter = RECIPIENT_PREPOSITIONS.contains(after)
                && (RECIPIENTS.contains(afterNext) || names.contains(afterNext));
        phrase.implicitRecipient |= after.equals("here") || after.equals("over") || after.equals("back");
        phrase.end = j;
        return phrase;
    }

    private static String at(List<String> clause, int index) {
        return index >= 0 && index < clause.size() ? clause.get(index) : "";
    }

    private static boolean isNumber(String word) {
        if (NUMBER_WORDS.contains(word)) {
            return true;
        }
        int digits = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
            } else if (c != 'x') {
                return false;
            }
        }
        return digits > 0;
    }

    /** One sentence of the chat line; {@code questionMark} is true when it ended in "?". */
    private record Sentence(List<String> words, boolean questionMark) {
    }

    /** A question about the past or about state ("how many logs did you gather?") asks for no work. */
    private static boolean isQuestion(List<String> words, boolean questionMark) {
        for (int i = 0; i < Math.min(QUESTION_OPENING_WORDS, words.size()); i++) {
            String word = words.get(i);
            if (VERBS.containsKey(word) || POLITE_OPENERS.contains(word)) {
                return false;
            }
            if (QUESTION_WORDS.contains(word)) {
                return !suggestsWork(words, i);
            }
            if (QUESTION_AUXILIARIES.contains(word)) {
                String next = at(words, i + 1);
                // "do you mind getting me wood" is a request; "have the logs ready" is a command.
                if (YOU.contains(next) && REQUESTS_AFTER_YOU.contains(at(words, i + 2)) || asksPolitely(words, i)) {
                    return false;
                }
                return SUBJECTS.contains(next) || questionMark;
            }
        }
        return false;
    }

    /**
     * A question word that opens a suggestion or a condition, not a question: "how about gathering logs",
     * "why dont you gather some", "why not mine coal", "when you get a chance, gather 32 logs".
     */
    private static boolean suggestsWork(List<String> words, int index) {
        String word = words.get(index);
        String next = at(words, index + 1);
        return (word.equals("how") || word.equals("what")) && next.equals("about")
                || word.equals("why") && (next.equals("not") || next.equals("dont") && SUBJECTS.contains(at(words, index + 2)))
                || word.equals("when") && SUBJECTS.contains(next);
    }

    /** "is it possible to gather 32 logs", "is it possible for you to ...", "do you think you could ...". */
    private static boolean asksPolitely(List<String> words, int index) {
        String next = at(words, index + 1);
        if (words.get(index).equals("is") && next.equals("it") && at(words, index + 2).equals("possible")) {
            String after = at(words, index + 3);
            return after.equals("to") || after.equals("for") && YOU.contains(at(words, index + 4));
        }
        return YOU.contains(next) && at(words, index + 2).equals("think") && YOU.contains(at(words, index + 3))
                && POLITE_OPENERS.contains(at(words, index + 4));
    }

    /** The non-empty runs of words between separator words. */
    private static List<List<String>> split(List<String> words, Set<String> separators) {
        List<List<String>> runs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String word : words) {
            if (separators.contains(word)) {
                if (!current.isEmpty()) {
                    runs.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(word);
            }
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        return runs;
    }

    /** Lowercased words per sentence; apostrophes vanish ("don't" -> "dont") and "," stays as a word. */
    private static List<Sentence> sentences(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('’', '\'').replace("'", "").replace("&", " and ");
        List<Sentence> sentences = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : '\n';
            if (Character.isLetterOrDigit(c) || c == '_' || c == ':') {
                word.append(c);
                continue;
            }
            if (!word.isEmpty()) {
                String token = word.toString();
                while (token.endsWith(":")) {
                    token = token.substring(0, token.length() - 1);
                }
                if (!token.isEmpty()) {
                    current.add(token);
                }
                word.setLength(0);
            }
            if (c == '.' || c == '!' || c == '?' || c == ';' || c == '\n' || c == '\r') {
                if (!current.isEmpty()) {
                    sentences.add(new Sentence(joinItemNames(current), c == '?'));
                    current = new ArrayList<>();
                }
            } else if (c == ',') {
                current.add(",");
            }
        }
        return sentences;
    }

    /**
     * Rejoins item names the player spaced out: "pork chop" is the item porkchop (not the verb chop),
     * "crafting table" the item crafting_table (not the verb crafting).
     */
    private static List<String> joinItemNames(List<String> words) {
        List<String> joined = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            String merged = i + 1 < words.size() ? mergedPair(words.get(i), words.get(i + 1)) : null;
            if (merged != null) {
                joined.add(merged);
                i++;
            } else {
                joined.add(words.get(i));
            }
        }
        return joined;
    }

    private static String mergedPair(String first, String second) {
        if (first.equals(",") || second.equals(",")) {
            return null;
        }
        for (String variant : ItemNouns.singularVariants(second)) {
            if (ItemNouns.isItemSegment(first + variant)) {
                return first + variant;
            }
            if (VERBS.containsKey(first) && ItemNouns.isItemPath(first + "_" + variant)) {
                return first + "_" + variant;
            }
        }
        return null;
    }
}
