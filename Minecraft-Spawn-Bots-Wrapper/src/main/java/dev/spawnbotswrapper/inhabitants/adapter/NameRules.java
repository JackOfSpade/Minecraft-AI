package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.util.BotNameShape;

import java.util.Locale;
import java.util.Set;

/**
 * The pure rules deciding whether a bot name may be used. Kept free of Minecraft types so they are
 * testable: the adapter feeds in what it observed (an online player with that name? PvP BOT's list?).
 * <p>
 * Two different name universes meet here. Vanilla compares player names case-insensitively, PvP BOT keys
 * its bot list case-sensitively, so "Foo" and "foo" would be two PvP BOT bots driving one entity. Everything
 * in the addon therefore compares through {@link #key(String)}.
 */
final class NameRules {

    private NameRules() {
    }

    enum Check {
        FREE,
        INVALID_FORMAT,
        ONLINE_PLAYER,
        LISTED,
        /** PvP BOT's list could not be read, so "free" cannot be claimed. */
        LISTING_UNKNOWN
    }

    /** The portable subset every layer (Brigadier word, vanilla profile name, HeroBot) accepts. */
    static boolean isValid(String name) {
        return BotNameShape.isValidName(name);
    }

    /** Case-insensitive comparison key. Names are ASCII by {@link #isValid}, so ROOT lower-casing is exact. */
    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /**
     * @param onlinePlayerWithName whether a player entity with this name (any case) is online right now
     * @param listedKeys           {@link #key} of every name PvP BOT lists, or null when that list is unreadable
     */
    static Check check(String name, boolean onlinePlayerWithName, Set<String> listedKeys) {
        if (!isValid(name)) {
            return Check.INVALID_FORMAT;
        }
        if (onlinePlayerWithName) {
            return Check.ONLINE_PLAYER;
        }
        if (listedKeys == null) {
            return Check.LISTING_UNKNOWN;
        }
        return listedKeys.contains(key(name)) ? Check.LISTED : Check.FREE;
    }

    /** Human-readable refusal for a non-{@link Check#FREE} result. */
    static String refusal(Check check, String name) {
        return switch (check) {
            case FREE -> "name '" + name + "' is free";
            case INVALID_FORMAT -> "invalid bot name '" + name + "': must be " + BotNameShape.MIN_NAME_LENGTH + "-"
                    + BotNameShape.MAX_NAME_LENGTH + " characters of A-Z a-z 0-9 _";
            case ONLINE_PLAYER -> "the name '" + name + "' is already used by an online player";
            case LISTED -> "the name '" + name + "' is already listed by PvP BOT";
            case LISTING_UNKNOWN -> "PvP BOT's bot list could not be read, so the name '" + name
                    + "' cannot be confirmed free";
        };
    }
}
