package io.github.zoyluo.minecraftai.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the classification of EVERY caller of the observation and line-of-sight predicates (docs/PERCEPTION.md, "Scope"):
 *
 * <ul>
 *   <li><b>notice</b>: {@code ObservableWorldQuery.canNoticeCreature} / {@code canNoticeCreatureWithin}: a bot NOTICES a creature
 *       (realistic perception: view cone, reaction time, hearing, a blow);</li>
 *   <li><b>object</b>: {@code canObserveEntity} / {@code canObserveEntityWithin}: OBJECTS (items, boats, containers) and the
 *       deliberate searches for animals and villagers, which keep omnidirectional observation on purpose (a bot glances around
 *       while it searches);</li>
 *   <li><b>physical</b>: {@code hasLineOfSight} / {@code hasLineOfSightOrOwnerSees}: strike legality, reachability and the plain
 *       "can it hit me" ray, which stay physical.</li>
 * </ul>
 *
 * A new caller (or a moved one) fails this test until it is classified here, so nothing that notices a creature can quietly go back
 * to being omnidirectional and no object search can quietly acquire a cone.
 */
class PerceptionCallSiteClassificationTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private record Counts(int notice, int object, int physical) {
        static Counts of(int notice, int object, int physical) {
            return new Counts(notice, object, physical);
        }
    }

    /** file (relative to the mod package) to its calls, each with the reason for its class. */
    private static Map<String, Counts> expected() {
        Map<String, Counts> m = new TreeMap<>();
        // ---- the predicates themselves
        m.put("mode/ObservableWorldQuery.java", Counts.of(2, 4, 2));            // definitions: canNoticeCreature(+Within), canObserveEntity(+Within), plain rays
        m.put("perception/CreatureSenses.java", Counts.of(0, 1, 2));           // perception off (and a passive animal, a failed scan): today's plain line-of-sight test; the projectile with perception off is exactly canObserveEntity
        // ---- creature noticing (converted)
        m.put("task/DangerWatcher.java", Counts.of(4, 0, 2));                  // death-site hostiles, fight-before-rescue, trapped fight back, last-resort shelter; physical: canReachThreat (owner-aware ray)
        m.put("task/AggroSense.java", Counts.of(1, 1, 0));                     // mob aggressors noticed; object: the OWNER (a friend) being hurt, plain sight
        m.put("task/SharedVision.java", Counts.of(1, 0, 1));                   // seenByBotOrOwner: the bot part is noticing; ownerSees is the owner's own ray
        m.put("task/CombatCore.java", Counts.of(2, 1, 5));                     // ranged threats, target acquisition (hostile: notice; non-hostile deliberate search: object); physical: strike/reach rays
        m.put("task/CombatTask.java", Counts.of(2, 0, 5));                     // pressure, shoot-from-here; physical: strike legality and lost-sight timer
        m.put("task/ShieldGuard.java", Counts.of(5, 1, 0));                    // reactive shield: noticed melee/creeper/guardian/drawing/tracked shooter; visible primed TNT is an observed object
        m.put("task/CreeperDefenseTask.java", Counts.of(1, 0, 0));
        m.put("task/EmergencyShelterTask.java", Counts.of(2, 1, 2));           // physical: melee strike from the shelter
        m.put("task/EvadeTask.java", Counts.of(3, 1, 0));                      // object: the OWNER's direction (a friend), plain sight
        m.put("task/FollowEscort.java", Counts.of(1, 0, 0));
        m.put("perception/PerceptionCollector.java", Counts.of(1, 1, 0));      // what the LLM is told it sees: creatures noticed; dropped items observed
        m.put("log/DiagnosticLogger.java", Counts.of(1, 0, 0));
        m.put("baritone/ServerPlayerContext.java", Counts.of(1, 1, 0));        // mob avoidance: noticed mobs; dropped items observed
        // ---- objects and deliberate searches (unchanged on purpose)
        m.put("action/HarvestCore.java", Counts.of(0, 2, 0));                  // dropped items
        m.put("action/MilkCowAction.java", Counts.of(0, 1, 0));                // deliberate search for a cow
        m.put("brain/ToolRegistry.java", Counts.of(1, 0, 0));                  // attack_entity: candidates are creatures the bot has NOTICED (animals and villagers stay omnidirectional inside canNoticeCreature)
        m.put("mining/assist/PoiDetector.java", Counts.of(0, 1, 0));           // landmark evidence (habitation, warden) for the assist's scoring, not a threat notice
        m.put("task/BoatSupport.java", Counts.of(0, 3, 0));                    // boat and each boarding/dismount candidate
        m.put("task/BreedTask.java", Counts.of(0, 1, 0));                      // deliberate search for animals
        m.put("task/CreateObsidianTask.java", Counts.of(0, 1, 0));             // dropped items
        m.put("task/DiscoveryTask.java", Counts.of(0, 1, 0));                  // deliberate local sheep survey
        m.put("task/HuntTask.java", Counts.of(0, 5, 1));                       // prey and its drops; physical: strike legality
        m.put("task/MiningServiceTask.java", Counts.of(0, 4, 0));              // dropped items
        m.put("task/OreDigTask.java", Counts.of(0, 1, 0));                     // dropped items
        m.put("task/RecoverDropsTask.java", Counts.of(0, 1, 0));               // dropped items
        m.put("task/TradeTask.java", Counts.of(0, 1, 0));                      // deliberate search for a villager
        // ---- strike legality / reachability (physical, unchanged)
        m.put("task/CombatRegroupTask.java", Counts.of(0, 0, 1));
        m.put("task/GuardTask.java", Counts.of(0, 0, 1));
        return m;
    }

    private static final Pattern NOTICE = Pattern.compile("canNoticeCreature(?:Within)?\\(");
    private static final Pattern OBJECT = Pattern.compile("canObserveEntity(?:Within)?\\(");
    private static final Pattern PHYSICAL = Pattern.compile("hasLineOfSight(?:OrOwnerSees)?\\(");

    static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    private static int count(Pattern pattern, String code) {
        Matcher matcher = pattern.matcher(code);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    @Test
    void everyCallerOfTheObservationPredicatesIsClassified() throws IOException {
        Map<String, Counts> actual = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java")).toList());
        }
        for (Path file : files) {
            String code = code(Files.readString(file));
            Counts counts = Counts.of(count(NOTICE, code), count(OBJECT, code), count(PHYSICAL, code));
            if (counts.notice() + counts.object() + counts.physical() > 0) {
                actual.put(MAIN.relativize(file).toString().replace('\\', '/'), counts);
            }
        }
        assertEquals(expected(), actual,
                "a call of canNoticeCreature / canObserveEntity / hasLineOfSight was added, moved or removed: classify it in "
                        + "PerceptionCallSiteClassificationTest and docs/PERCEPTION.md (creature noticing vs object vs strike legality)");
    }

    @Test
    void theCreatureSitesOfTheBriefAllUseTheNoticePredicate() throws IOException {
        // The named creature sites: DangerWatcher threat scans, AggroSense, CombatCore target acquisition, CombatTask
        // skeleton/creeper/target checks, CreeperDefenseTask, EmergencyShelterTask, EvadeTask, ProjectileThreat,
        // SharedVision (bot part), HostileBotIntent sampling, PerceptionCollector and the DiagnosticLogger lists.
        for (String file : List.of("task/DangerWatcher.java", "task/AggroSense.java", "task/CombatCore.java", "task/CombatTask.java",
                "task/CreeperDefenseTask.java", "task/EmergencyShelterTask.java", "task/EvadeTask.java",
                "task/SharedVision.java", "perception/PerceptionCollector.java", "log/DiagnosticLogger.java",
                "task/FollowEscort.java", "baritone/ServerPlayerContext.java")) {
            assertTrue(expected().get(file).notice() > 0, file + " must ask canNoticeCreature");
        }
        String projectile = code(Files.readString(MAIN.resolve("task/ProjectileThreat.java")));
        assertTrue(projectile.contains("CreatureSenses.INSTANCE.noticedProjectile(bot, projectile)")
                        && !projectile.contains("canObserveEntity"),
                "a projectile is noticed if its shot is heard or it is in sight, never by omnidirectional observation");
        String intent = code(Files.readString(MAIN.resolve("task/HostileBotIntent.java")));
        assertTrue(intent.contains("perceived(aggressor, observers)") && intent.contains("CreatureSenses.INSTANCE.noticed(bot, aggressor)")
                        && intent.contains("SharedVision.ownerSees(bot, aggressor)"),
                "the intent sampler is gated on someone noticing the aggressor (or the owner seeing it)");
    }

    @Test
    void theObjectSitesStayOmnidirectional() throws IOException {
        // Objects and deliberate searches must never be given the cone: they use canObserveEntity, not canNoticeCreature.
        for (String file : List.of("action/HarvestCore.java", "action/MilkCowAction.java", "task/BoatSupport.java", "task/BreedTask.java",
                "task/CreateObsidianTask.java", "task/DiscoveryTask.java", "task/HuntTask.java", "task/MiningServiceTask.java", "task/OreDigTask.java",
                "task/RecoverDropsTask.java", "task/TradeTask.java", "mining/assist/PoiDetector.java")) {
            assertEquals(0, expected().get(file).notice(), file + " searches for objects or deliberately for animals");
        }
    }

    @Test
    void theAttackToolOnlyConsidersCreaturesTheBotHasNoticed() throws IOException {
        String tool = code(Files.readString(MAIN.resolve("brain/ToolRegistry.java")));
        assertTrue(tool.contains("ObservableWorldQuery.canNoticeCreature(bot, entity));")
                        && !tool.contains("canObserveEntity")
                        && tool.contains("StrikeLegality.strikeRefusal(bot, entity) == null"),
                "attack_entity candidates are noticed creatures (its reply never reveals an unseen mob), through the same strike legality gate");
    }
}
