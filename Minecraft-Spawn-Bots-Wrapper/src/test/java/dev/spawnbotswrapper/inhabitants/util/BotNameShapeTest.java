package dev.spawnbotswrapper.inhabitants.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BotNameShapeTest {

    @Test
    void validNamesAreThreeToSixteenAllowedChars() {
        assertTrue(BotNameShape.isValidName("abc"));
        assertTrue(BotNameShape.isValidName("Inh_Wolf9f01"));
        assertTrue(BotNameShape.isValidName("A234567890123456")); // exactly 16
        assertFalse(BotNameShape.isValidName("ab"), "too short");
        assertFalse(BotNameShape.isValidName("A2345678901234567"), "too long (17)");
        assertFalse(BotNameShape.isValidName("bad name"), "space");
        assertFalse(BotNameShape.isValidName("bad-name"), "hyphen");
        assertFalse(BotNameShape.isValidName(null));
        assertFalse(BotNameShape.isValidName(""));
    }

    @Test
    void sanitizePrefixStripsInvalidCharsAndTruncatesToEight() {
        assertEquals("", BotNameShape.sanitizePrefix(null));
        assertEquals("abc", BotNameShape.sanitizePrefix("abc"));
        assertEquals("abc123", BotNameShape.sanitizePrefix("ab!c1#2$3"));
        assertEquals("12345678", BotNameShape.sanitizePrefix("123456789"), "truncated to 8");
    }

    @Test
    void sanitizePrefixDropsATrailingUnderscoreLeftByTruncationOrByItself() {
        // The exact case that used to diverge between the config validator and the name generator: an
        // otherwise-valid 8-character prefix ending in underscores must not keep them, because the
        // generator always appends its own separating underscore after the prefix.
        assertEquals("abcd_e", BotNameShape.sanitizePrefix("abcd_e__"));
        assertEquals("abc", BotNameShape.sanitizePrefix("abc_"));
        assertEquals("", BotNameShape.sanitizePrefix("________"));
    }
}
