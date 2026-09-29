package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the rules around the Baritone capability switches ({@code nav.baritone.*}; the behavior is proven by
 * {@code BaritoneCapabilityGameTests}): the switches come from the config at every plan and never from a hand-edited settings
 * file, the only item use without a block is the bucket of a water-bucket fall inside that movement, chunk caching stays off, and
 * mob avoidance can only see what the bot observes.
 */
class BaritoneCapabilityContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void theSwitchesAreDerivedFromTheConfigAtEveryPlanAndNotFixed() throws IOException {
        String settings = read("baritone/BaritoneSettings.java");
        int fixed = settings.indexOf("public static void applyFixed()");
        int limits = settings.indexOf("public static void applyNavLimits()");
        assertTrue(fixed >= 0 && limits > fixed);
        String fixedPart = settings.substring(fixed, limits);
        String limitsPart = settings.substring(limits);
        for (String setting : new String[] {"allowParkour", "allowParkourPlace", "allowParkourAscend", "allowWaterBucketFall", "allowVines", "avoidance"}) {
            assertFalse(fixedPart.contains("settings." + setting + ".value"), setting + " must not be fixed: it follows nav.baritone");
            assertTrue(limitsPart.contains("settings." + setting + ".value ="), setting + " follows the config at every plan");
        }
        assertTrue(fixedPart.contains("settings.chunkCaching.value = false;"), "the world cache stays off (it would remember terrain the bot never saw)");
        assertTrue(fixedPart.contains("settings.mobSpawnerAvoidanceCoefficient.value = 1.0D;"), "spawner avoidance needs the world cache");
        assertTrue(limitsPart.contains("caps.waterBucketFallEnabled() ? Math.max(maxSafeFall, caps.maxBucketFall()) : maxSafeFall"),
                "without the switch the bucket limit is the safe fall");
    }

    @Test
    void theOnlyItemUseWithoutABlockIsTheBucketOfAFallMovement() throws IOException {
        String policy = read("baritone/BaritoneBreakPlacePolicy.java");
        assertTrue(policy.contains("USE_ITEM_ALLOWLIST = Set.of();"), "the general allow-list stays empty");
        assertTrue(policy.contains("BaritoneWaterFall.refusalOf(bot, item)"), "checkUseItem asks the water-fall rule");
        String fall = read("baritone/BaritoneWaterFall.java");
        int refusal = fall.indexOf("static String refusalOf(");
        assertTrue(refusal >= 0);
        String rule = fall.substring(refusal, fall.indexOf("/** Called after the bot used the water bucket", refusal));
        assertTrue(rule.contains("isFallBucket(item)") && rule.contains("waterBucketFallEnabled()") && rule.contains("runningFall(bot)")
                && rule.contains("waterEvaporates(bot, dest)") && rule.contains("target_outside_landing_column"),
                "only the two buckets, with the switch on, inside a fall movement, where water does not evaporate, aimed at the landing column");
        assertTrue(fall.contains("baritone_water_left"), "a pickup that fails is logged, never silent");
    }

    @Test
    void mobAvoidanceReadsOnlyTheObservedEntities() throws IOException {
        String context = read("baritone/ServerPlayerContext.java");
        assertTrue(context.contains("ObservableWorldQuery.canObserveEntity(self, mob)"), "a mob enters the list only when the bot observes it");
        assertTrue(context.contains("mob instanceof Enemy"), "hostile mobs only");
        String driver = read("baritone/BaritoneDriver.java");
        assertTrue(driver.contains("BaritoneWaterFall.recover(bot, entry)"), "left-behind water is taken back by the driver");
    }
}
