package dev.spawnbotswrapper.inhabitants.adapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The isolation rule of the whole addon: ONE package may know PvP BOT and HeroBot exist. Everything else
 * talks to {@link PvpBotOperations}. This test scans the real source tree so a reference that sneaks in
 * anywhere else fails the build.
 * <p>
 * What "know" means, precisely. Outside {@code adapter/} the rule is about CODE, not prose, because other
 * packages legitimately DESCRIBE the upstream mods to operators (setting descriptions, command help, the
 * {@code heroBotVersion} accessor of the frozen status record):
 * <ul>
 *   <li>the text {@code stepan1411} (PvP BOT's package/author) must not appear anywhere, comments included;</li>
 *   <li>the text {@code hero.bane} (HeroBot's package) must not appear anywhere, comments included;</li>
 *   <li>no string literal may be a mod id ({@code pvp_bot}, {@code herobot}) or contain an upstream package
 *       prefix, and no identifier may contain "herobot" (except the frozen {@code heroBotVersion}).</li>
 * </ul>
 * Inside {@code adapter/} the rule is about what may be touched: the classes whose static initialisers do
 * blocking I/O or bind to world state (name generator, faction registry, movement helper) are never
 * referenced, classes are only ever loaded WITHOUT initialisation, and the mutators of PvP BOT that this
 * addon must never call are not even named in the call layer.
 */
class ArchitectureTest {

    private static final String ADAPTER_DIR = "/dev/spawnbotswrapper/inhabitants/adapter/";
    private static final Set<String> ALLOWED_HEROBOT_IDENTIFIERS = Set.of("heroBotVersion");
    private static final Set<String> FORBIDDEN_EVERYWHERE = Set.of("stepan1411", "hero.bane");
    private static final Set<String> MOD_ID_LITERALS = Set.of("pvp_bot", "herobot");
    private static final List<String> NEVER_REFERENCED_UPSTREAM_CLASSES =
            List.of("BotFaction", "BotNameGenerator", "HerobotMovement");
    /** Members probed for presence but never invoked; the call layer must not even mention them. */
    private static final List<String> NEVER_CALLED = List.of("removeAllBots", "saveBots", "updateBotData",
            "reloadBots", "switchWorld", "cleanupDeadBots");
    /** Reflection call-layer files: everything that may invoke an upstream member. */
    private static final List<String> CALL_LAYER = List.of("UpstreamCalls.java", "PvpBotAdapter.java",
            "PatrolManager.java", "CapabilityReader.java");
    private static final Set<String> ALLOWED_SETTER_LITERALS = Set.of("setLoop", "setAttack", "setWalkType");

    // ================================================================ the real tree

    @Test
    void onlyTheAdapterPackageReferencesPvpBotOrHeroBot() throws IOException {
        Path root = sourceRoot();
        List<String> violations = new ArrayList<>();
        int files = 0;
        boolean sawAdapter = false;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = "/" + root.relativize(p).toString().replace('\\', '/');
                boolean inside = rel.contains(ADAPTER_DIR);
                sawAdapter |= inside;
                files++;
                if (!inside) {
                    violations.addAll(outsideAdapterViolations(rel, Files.readString(p)));
                }
            }
        }
        assertTrue(sawAdapter, "the adapter package was not found under " + root);
        assertTrue(files > 10, "suspiciously few sources scanned (" + files + ") under " + root);
        assertTrue(violations.isEmpty(), "code outside the adapter package references PvP BOT / HeroBot:\n  "
                + String.join("\n  ", violations));
    }

    @Test
    void theAdapterNeverReferencesTheClassesWithDangerousStaticInitialisers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path p : adapterSources()) {
            Lexed lexed = Lexed.of(Files.readString(p));
            violations.addAll(referencedNames(p.getFileName().toString(), lexed, NEVER_REFERENCED_UPSTREAM_CLASSES));
        }
        assertTrue(violations.isEmpty(), String.join("\n  ", violations));
    }

    @Test
    void theAdapterOnlyEverLoadsClassesWithoutInitialisingThem() throws IOException {
        int occurrences = 0;
        for (Path p : adapterSources()) {
            String code = Lexed.of(Files.readString(p)).code();
            Matcher m = Pattern.compile("Class\\s*\\.\\s*forName\\s*\\(").matcher(code);
            while (m.find()) {
                occurrences++;
                int end = code.indexOf(')', m.end());
                String args = code.substring(m.end(), end < 0 ? code.length() : end);
                assertTrue(args.matches("(?s).*,\\s*false\\s*,.*"),
                        p.getFileName() + ": Class.forName must pass initialize=false, found (" + args + ")");
            }
        }
        assertEquals(1, occurrences, "the only class loading is ClassLocator.forLoader");
    }

    @Test
    void theCallLayerNeverNamesTheMutatorsItMustNotCall() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path p : adapterSources()) {
            if (!CALL_LAYER.contains(p.getFileName().toString())) {
                continue;
            }
            Lexed lexed = Lexed.of(Files.readString(p));
            violations.addAll(referencedNames(p.getFileName().toString(), lexed, NEVER_CALLED));
        }
        assertTrue(violations.isEmpty(), String.join("\n  ", violations));
    }

    @Test
    void noSettingsWriterIsEverNamedByTheAdapter() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path p : adapterSources()) {
            Lexed lexed = Lexed.of(Files.readString(p));
            for (String s : lexed.strings()) {
                boolean setter = s.matches("set[A-Z]\\w*");
                boolean lifecycle = s.equals("load") || s.equals("save") || s.equals("reload");
                if ((setter && !ALLOWED_SETTER_LITERALS.contains(s)) || lifecycle) {
                    violations.add(p.getFileName() + ": names the upstream member \"" + s + "\"");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n  ", violations));
    }

    @Test
    void thereIsNoCompileTimeDependencyOnPvpBotOrHeroBot() throws IOException {
        Path build = sourceRoot().getParent().getParent().getParent().resolve("build.gradle");
        assertTrue(Files.isRegularFile(build), "build.gradle not found next to src/: " + build);
        String text = Files.readString(build);
        int open = text.indexOf("dependencies {");
        assertTrue(open >= 0, "no dependencies block in build.gradle");
        int depth = 0;
        int end = -1;
        for (int i = text.indexOf('{', open); i < text.length(); i++) {
            char c = text.charAt(i);
            depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            if (depth == 0) {
                end = i;
                break;
            }
        }
        String block = text.substring(open, end < 0 ? text.length() : end).toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("pvp", "stepan1411", "herobot", "hero.bane", "jitpack")) {
            assertFalse(block.contains(forbidden), "build.gradle dependencies must not mention '" + forbidden + "'");
        }
    }

    @Test
    void mainResourcesOtherThanTheModDescriptorDoNotTargetUpstream() throws IOException {
        Path resources = sourceRoot().getParent().resolve("resources");
        if (!Files.isDirectory(resources)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(resources)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String name = p.getFileName().toString();
                if (name.equals("fabric.mod.json") || !(name.endsWith(".json") || name.endsWith(".properties"))) {
                    continue;
                }
                String text = Files.readString(p).toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_EVERYWHERE) {
                    assertFalse(text.contains(forbidden), name + " must not reference upstream: " + forbidden);
                }
            }
        }
    }

    // ================================================================ the rules themselves

    /** Violations of the "outside the adapter" rule in one source file. */
    static List<String> outsideAdapterViolations(String file, String source) {
        List<String> out = new ArrayList<>();
        String lower = source.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_EVERYWHERE) {
            if (lower.contains(forbidden)) {
                out.add(file + ": contains '" + forbidden + "'");
            }
        }
        Lexed lexed = Lexed.of(source);
        for (String literal : lexed.strings()) {
            String l = literal.trim().toLowerCase(Locale.ROOT);
            if (MOD_ID_LITERALS.contains(l)) {
                out.add(file + ": string literal is an upstream mod id: \"" + literal + "\"");
            }
            if (l.contains("pvp_bot.")) {
                out.add(file + ": string literal looks like an upstream class name: \"" + literal + "\"");
            }
        }
        Matcher m = Pattern.compile("[A-Za-z0-9_$]+").matcher(lexed.code());
        while (m.find()) {
            String id = m.group();
            if (id.toLowerCase(Locale.ROOT).contains("herobot") && !ALLOWED_HEROBOT_IDENTIFIERS.contains(id)) {
                out.add(file + ": identifier '" + id + "' names HeroBot");
            }
        }
        return out;
    }

    /** Whole-word occurrences of {@code names} in code and string literals (never in comments). */
    static List<String> referencedNames(String file, Lexed lexed, List<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            if (word.matcher(lexed.code()).find()) {
                out.add(file + ": code references '" + name + "'");
            }
            for (String s : lexed.strings()) {
                if (word.matcher(s).find()) {
                    out.add(file + ": string literal references '" + name + "'");
                }
            }
        }
        return out;
    }

    @Test
    void theRuleCatchesAPvpBotImport() {
        assertFalse(outsideAdapterViolations("Evil.java",
                "import org.stepan1411.pvp_bot.bot.BotManager;\nclass Evil {}").isEmpty());
    }

    @Test
    void theRuleCatchesAHeroBotPackageEvenInAComment() {
        assertFalse(outsideAdapterViolations("Evil.java", "// see hero.bane.herobot.bot.BotPlayer\nclass Evil {}").isEmpty());
    }

    @Test
    void theRuleCatchesAModIdLookup() {
        assertFalse(outsideAdapterViolations("Evil.java",
                "class Evil { boolean b = FabricLoader.getInstance().isModLoaded(\"pvp_bot\"); }").isEmpty());
        assertFalse(outsideAdapterViolations("Evil.java",
                "class Evil { Object c = loader.getModContainer(\"HeroBot\"); }").isEmpty());
    }

    @Test
    void theRuleCatchesAClassNameString() {
        assertFalse(outsideAdapterViolations("Evil.java",
                "class Evil { Class<?> c = Class.forName(\"x.pvp_bot.bot.Y\"); }").isEmpty());
    }

    @Test
    void theRuleCatchesAHeroBotIdentifier() {
        assertFalse(outsideAdapterViolations("Evil.java", "class HeroBotAccess {}").isEmpty());
        assertFalse(outsideAdapterViolations("Evil.java", "class Evil { int heroBotPing; }").isEmpty());
    }

    @Test
    void theRuleAllowsProseAboutTheUpstreamModsAndTheFrozenAccessor() {
        String source = String.join("\n",
                "/** Describes PvP BOT and HeroBot to the operator. */",
                "class Fine {",
                "    // HeroBot 2.x differs from 1.x",
                "    String label = \"HeroBot: \";",
                "    String v(PvpBotOperations.Status s) { return s.heroBotVersion(); }",
                "}");
        assertEquals(List.of(), outsideAdapterViolations("Fine.java", source));
    }

    // ================================================================ the mini lexer

    /** A Java source split into code (comments and literal contents removed) and its string literals. */
    record Lexed(String code, List<String> strings) {

        static Lexed of(String src) {
            StringBuilder code = new StringBuilder(src.length());
            List<String> strings = new ArrayList<>();
            int i = 0;
            int n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                char next = i + 1 < n ? src.charAt(i + 1) : '\0';
                if (c == '/' && next == '/') {
                    while (i < n && src.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && next == '*') {
                    int end = src.indexOf("*/", i + 2);
                    i = end < 0 ? n : end + 2;
                    code.append(' ');
                } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                    int end = src.indexOf("\"\"\"", i + 3);
                    while (end > 0 && src.charAt(end - 1) == '\\') {
                        end = src.indexOf("\"\"\"", end + 1);
                    }
                    end = end < 0 ? n : end;
                    strings.add(src.substring(i + 3, end));
                    code.append("\"\"");
                    i = Math.min(n, end + 3);
                } else if (c == '"') {
                    int j = i + 1;
                    StringBuilder lit = new StringBuilder();
                    while (j < n && src.charAt(j) != '"' && src.charAt(j) != '\n') {
                        if (src.charAt(j) == '\\' && j + 1 < n) {
                            lit.append(src.charAt(j)).append(src.charAt(j + 1));
                            j += 2;
                        } else {
                            lit.append(src.charAt(j++));
                        }
                    }
                    strings.add(lit.toString());
                    code.append("\"\"");
                    i = j + 1;
                } else if (c == '\'') {
                    int j = i + 1;
                    while (j < n && src.charAt(j) != '\'' && src.charAt(j) != '\n') {
                        j += src.charAt(j) == '\\' ? 2 : 1;
                    }
                    code.append("''");
                    i = j + 1;
                } else {
                    code.append(c);
                    i++;
                }
            }
            return new Lexed(code.toString(), List.copyOf(strings));
        }
    }

    @Test
    void lexerSeparatesCommentsStringsAndCode() {
        Lexed l = Lexed.of("int x = 1; // herobot in a line comment\n/* herobot\n in a block */ String s = \"a // not a comment\";");
        assertFalse(l.code().toLowerCase(Locale.ROOT).contains("herobot"));
        assertFalse(l.code().contains("not a comment"));
        assertEquals(List.of("a // not a comment"), l.strings());
        assertTrue(l.code().contains("int x = 1;"));
        assertTrue(l.code().contains("String s ="));
    }

    @Test
    void lexerHandlesEscapesCharLiteralsAndTextBlocks() {
        Lexed l = Lexed.of("char q = '\"'; String e = \"say \\\"hi\\\" // still string\"; String t = \"\"\"\n  herobot // in a text block\n  \"\"\"; int after = 2;");
        assertTrue(l.code().contains("int after = 2;"), l.code());
        assertEquals(2, l.strings().size(), l.strings().toString());
        assertTrue(l.strings().get(0).contains("still string"));
        assertTrue(l.strings().get(1).contains("herobot"));
        assertFalse(l.code().toLowerCase(Locale.ROOT).contains("herobot"));
    }

    @Test
    void lexerSurvivesUnterminatedInput() {
        assertTrue(Lexed.of("String s = \"unterminated").strings().size() >= 1);
        Lexed.of("/* never closed");
        Lexed.of("char c = '");
        assertEquals("", Lexed.of("").code());
    }

    // ================================================================ helpers

    private static Path sourceRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        fail("src/main/java not found from " + dir);
        return null;
    }

    private static List<Path> adapterSources() throws IOException {
        Path root = sourceRoot();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = "/" + root.relativize(p).toString().replace('\\', '/');
                if (rel.contains(ADAPTER_DIR)) {
                    out.add(p);
                }
            }
        }
        assertFalse(out.isEmpty(), "no adapter sources found under " + root);
        return out;
    }
}
