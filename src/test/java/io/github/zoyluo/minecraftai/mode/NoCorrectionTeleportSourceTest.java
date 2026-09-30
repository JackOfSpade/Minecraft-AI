package io.github.zoyluo.minecraftai.mode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 lock: no production code moves a bot to correct its position. A teleport (or any equivalent instant relocation) of an entity may
 * be called only from
 * <ul>
 *   <li>{@code AIPlayerEntity}, which merely delegates its overrides to {@code super} (and records them in {@code TeleportAudit});</li>
 *   <li>{@code AIPlayerManager}, the lifecycle (spawn, respawn after death);</li>
 *   <li>{@code MinecraftAiServerNetworking}, the panel's RECALL / TO_AI buttons, gated by the MANUAL_TELEPORT capability;</li>
 *   <li>the four operator-profile EMERGENCY rescues ({@code TeleportAudit.PRIVILEGED_METHODS}), each of which decides the
 *       EMERGENCY_TELEPORT capability (denied in strict survival) before it reads a single cell the bot may not see.</li>
 * </ul>
 * Everything else moves by walked steps (real inputs). The scan runs on comment- and string-free source.
 */
class NoCorrectionTeleportSourceTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    /** Every way the game API relocates an entity instantly; a call is {@code .name(} (a declaration has no dot before its name). */
    private static final Pattern RELOCATION = Pattern.compile(
            "\\.(teleportTo|teleportRelative|teleport|moveTo|snapTo|absMoveTo|absSnapTo|setPos|setPosRaw|randomTeleport|dismountTo|"
                    + "moveToAndFace|setPosAndOldPos)\\s*\\(");

    /** The reviewed EMERGENCY sites: file (relative to the mod package) to the methods whose bodies may relocate the bot. */
    private static final Map<String, List<String>> EMERGENCY_SITES = Map.of(
            "task/NavSafetyNet.java", List.of("escapeSuffocation", "emergencyTeleportToAir"),
            "task/DangerWatcher.java", List.of("escapeToSurface"),
            "task/GatherQuotaTask.java", List.of("trySurface"));

    /** What a rescue must not do before its capability decision: read cells the bot may not be able to see. */
    private static final Pattern UNOBSERVED_SCAN = Pattern.compile("Standab|findNearest|cachedFind|getBlockState|getFluidState");

    @Test
    void noProductionSourceRelocatesABotOutsideTheReviewedSites() throws IOException {
        Map<String, String> sources = productionSources();
        assertFalse(sources.isEmpty(), "the production source tree was not found");
        Map<String, List<Integer>> calls = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Matcher matcher = RELOCATION.matcher(entry.getValue());
            List<Integer> at = new ArrayList<>();
            while (matcher.find()) {
                at.add(matcher.start());
            }
            if (!at.isEmpty()) {
                calls.put(entry.getKey(), at);
            }
        }
        java.util.Set<String> allowed = new java.util.TreeSet<>(java.util.Set.of(
                "entity/AIPlayerEntity.java",
                "manager/AIPlayerManager.java",
                "network/MinecraftAiServerNetworking.java"));
        allowed.addAll(EMERGENCY_SITES.keySet());
        assertEquals(allowed, new java.util.TreeSet<>(calls.keySet()),
                "a new relocation of an entity needs an explicit privileged-boundary review (a correction teleport is never one)");
    }

    @Test
    void theEntityOverridesOnlyDelegateToSuper() throws IOException {
        String entity = productionSources().get("entity/AIPlayerEntity.java");
        String withoutSuper = entity.replace("super.teleportTo(", "").replace("super.teleport(", "");
        assertFalse(RELOCATION.matcher(withoutSuper).find(),
                "AIPlayerEntity relocates nothing itself: its teleport overrides audit and then call super");
    }

    @Test
    void panelTeleportsDecideTheManualCapabilityFirst() throws IOException {
        String network = productionSources().get("network/MinecraftAiServerNetworking.java");
        int decision = network.indexOf("PrivilegedCapability.MANUAL_TELEPORT");
        Matcher first = RELOCATION.matcher(network);
        assertTrue(first.find(), "the panel teleport exists");
        assertTrue(decision >= 0 && decision < first.start(),
                "the MANUAL_TELEPORT capability is decided before the panel teleports anything");
        assertTrue(network.contains("CapabilityRuntime.decide("));
    }

    @Test
    void everyEmergencyRelocationIsInsideAReviewedRescueThatDecidesTheCapabilityFirst() throws IOException {
        Map<String, String> sources = productionSources();
        for (Map.Entry<String, List<String>> site : EMERGENCY_SITES.entrySet()) {
            String source = sources.get(site.getKey());
            assertTrue(source != null, site.getKey() + " exists");
            List<int[]> bodies = new ArrayList<>();
            for (String method : site.getValue()) {
                int[] body = methodBody(source, method);
                assertTrue(body != null, site.getKey() + " has " + method);
                bodies.add(body);
                String text = source.substring(body[0], body[1]);
                Matcher decision = Pattern.compile(
                        "CapabilityRuntime\\s*\\.\\s*(decide|run)\\s*\\([^;]*?PrivilegedCapability\\.EMERGENCY_TELEPORT",
                        Pattern.DOTALL).matcher(text);
                assertTrue(decision.find(), site.getKey() + "#" + method + " decides EMERGENCY_TELEPORT");
                Matcher scan = UNOBSERVED_SCAN.matcher(text);
                assertTrue(!scan.find() || scan.start() > decision.start(),
                        site.getKey() + "#" + method + " reads cells before it decides the capability");
                Matcher relocation = RELOCATION.matcher(text);
                assertTrue(relocation.find(), site.getKey() + "#" + method + " is a rescue that relocates the bot");
                assertTrue(relocation.start() > decision.start(),
                        site.getKey() + "#" + method + " relocates before it decides the capability");
            }
            Matcher every = RELOCATION.matcher(source);
            while (every.find()) {
                int position = every.start();
                assertTrue(bodies.stream().anyMatch(body -> position >= body[0] && position < body[1]),
                        site.getKey() + " relocates a bot outside its reviewed rescue methods " + site.getValue());
            }
        }
    }

    @Test
    void theAuditListsExactlyTheReviewedRescues() throws IOException {
        String audit = Files.readString(MAIN.resolve("entity/TeleportAudit.java"));
        Matcher block = Pattern.compile("PRIVILEGED_METHODS\\s*=\\s*Set\\.of\\((.*?)\\);", Pattern.DOTALL).matcher(audit);
        assertTrue(block.find(), "TeleportAudit.PRIVILEGED_METHODS exists");
        List<String> listed = new ArrayList<>();
        Matcher entry = Pattern.compile("\"(\\w+)#(\\w+)\"").matcher(block.group(1));
        while (entry.find()) {
            listed.add(entry.group(1) + "#" + entry.group(2));
        }
        List<String> reviewed = new ArrayList<>();
        EMERGENCY_SITES.forEach((file, methods) -> methods.forEach(
                method -> reviewed.add(file.substring(file.indexOf('/') + 1, file.length() - ".java".length()) + "#" + method)));
        assertEquals(new java.util.TreeSet<>(reviewed), new java.util.TreeSet<>(listed),
                "the audit's privileged list and this source lock name the same emergency rescues");
    }

    /** Start and end offsets of the body of the (first) method called {@code name}, or null. */
    private static int[] methodBody(String source, String name) {
        Matcher declaration = Pattern.compile("\\b(?:boolean|void)\\s+" + Pattern.quote(name) + "\\s*\\(").matcher(source);
        if (!declaration.find()) {
            return null;
        }
        int open = source.indexOf('{', declaration.end());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return new int[]{declaration.start(), i + 1};
            }
        }
        return null;
    }

    private static Map<String, String> productionSources() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).sorted().toList()) {
                result.put(MAIN.relativize(path).toString().replace('\\', '/'), stripCommentsAndStrings(Files.readString(path)));
            }
        }
        return result;
    }

    /** Removes comments and the contents of string and char literals, so a name in prose or a log line never counts as a call. */
    static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                i += 2;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && source.charAt(i) != c) {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                out.append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    @Test
    void theStripperKeepsCodeAndDropsProse() {
        String stripped = stripCommentsAndStrings(
                "a.teleportTo(1); // b.teleportTo(2)\n/* c.teleportTo(3) */ d(\"e.teleportTo(4)\"); f('\\'');");
        assertEquals(1, RELOCATION.matcher(stripped).results().count(), stripped);
    }
}
