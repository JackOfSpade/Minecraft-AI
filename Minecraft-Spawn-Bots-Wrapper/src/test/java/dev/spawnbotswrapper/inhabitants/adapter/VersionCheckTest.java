package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.VersionCheck.HeroBotGeneration;
import dev.spawnbotswrapper.inhabitants.adapter.VersionCheck.Relation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCheckTest {

    @Test
    void theTestedVersionNeedsNoWarning() {
        assertEquals(Relation.TESTED, VersionCheck.relation("0.0.15"));
        assertTrue(VersionCheck.pvpBotWarning("0.0.15").isEmpty());
    }

    @Test
    void buildMetadataDoesNotMakeAVersionUntested() {
        assertEquals(Relation.TESTED, VersionCheck.relation("0.0.15+build.7"));
        assertEquals(Relation.TESTED, VersionCheck.relation("v0.0.15"));
        assertEquals(Relation.TESTED, VersionCheck.relation("0.0.15.0"), "trailing zero components are equal");
    }

    @Test
    void aNewerVersionIsUntestedAndSaysSo() {
        assertEquals(Relation.NEWER, VersionCheck.relation("0.0.16"));
        assertEquals(Relation.NEWER, VersionCheck.relation("0.1.0"));
        assertEquals(Relation.NEWER, VersionCheck.relation("1.0.0"));
        assertEquals(Relation.NEWER, VersionCheck.relation("0.0.100"), "numeric, not lexicographic");
        String warning = VersionCheck.pvpBotWarning("0.0.16").orElseThrow();
        assertTrue(warning.startsWith("untested version 0.0.16, tested: 0.0.15"), warning);
    }

    @Test
    void anOlderVersionIsUntestedToo() {
        assertEquals(Relation.OLDER, VersionCheck.relation("0.0.14"));
        assertEquals(Relation.OLDER, VersionCheck.relation("0.0.9"), "numeric, not lexicographic");
        assertTrue(VersionCheck.pvpBotWarning("0.0.14").orElseThrow().contains("older"));
    }

    @Test
    void aPreReleaseSortsBeforeTheReleaseButIsStillJustUntested() {
        assertEquals(Relation.PRE_RELEASE_OF_TESTED, VersionCheck.relation("0.0.15-pre-release-1"));
        assertTrue(VersionCheck.pvpBotWarning("0.0.15-pre-release-1").isPresent());
        assertEquals(Relation.NEWER, VersionCheck.relation("0.0.16-pre-release-1"));
    }

    @Test
    void garbageIsReportedAsUnparseableNotAsACrash() {
        assertEquals(Relation.UNPARSEABLE, VersionCheck.relation("nightly-abc"));
        assertEquals(Relation.UNPARSEABLE, VersionCheck.relation("latest"));
        assertTrue(VersionCheck.pvpBotWarning("latest").orElseThrow().contains("could not be parsed"));
    }

    @Test
    void absentVersionsGiveNoWarning() {
        assertEquals(Relation.ABSENT, VersionCheck.relation(null));
        assertEquals(Relation.ABSENT, VersionCheck.relation("  "));
        assertTrue(VersionCheck.pvpBotWarning(null).isEmpty());
    }

    @Test
    void absurdlyLongNumbersDoNotOverflowIntoTheTestedVersion() {
        assertEquals(Relation.NEWER, VersionCheck.relation("0.0.99999999999999999999999"));
    }

    // ---------------------------------------------------------------- HeroBot

    @Test
    void heroBotOneDotXIsTheLegacyGeneration() {
        assertEquals(HeroBotGeneration.LEGACY, VersionCheck.heroBotGeneration("1.21.11-1.4.3+v260315"));
        assertEquals(HeroBotGeneration.LEGACY, VersionCheck.heroBotGeneration("1.8.6"));
        assertTrue(VersionCheck.heroBotWarning("1.21.11-1.4.3+v260315").isEmpty());
    }

    @Test
    void heroBotTwoDotXIsTheModernGenerationAndWarns() {
        assertEquals(HeroBotGeneration.MODERN, VersionCheck.heroBotGeneration("2.6.5"));
        assertEquals(HeroBotGeneration.MODERN, VersionCheck.heroBotGeneration("1.21.11-2.7.2"));
        String w = VersionCheck.heroBotWarning("2.7.2").orElseThrow();
        assertTrue(w.contains("2.7.2") && w.contains("2.x"), w);
    }

    @Test
    void heroBotVersionsItCannotReadAreUnknownAndSilent() {
        assertEquals(HeroBotGeneration.UNKNOWN, VersionCheck.heroBotGeneration(null));
        assertEquals(HeroBotGeneration.UNKNOWN, VersionCheck.heroBotGeneration(""));
        assertEquals(HeroBotGeneration.UNKNOWN, VersionCheck.heroBotGeneration("dev"));
        assertEquals(HeroBotGeneration.UNKNOWN, VersionCheck.heroBotGeneration("0.9.0"));
        assertTrue(VersionCheck.heroBotWarning("dev").isEmpty());
        assertTrue(VersionCheck.heroBotWarning(null).isEmpty());
    }
}
