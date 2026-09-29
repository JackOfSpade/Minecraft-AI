import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the Baritone source tree that the mod compiles, from the pristine vendored copy:
 *
 * <pre>
 *   third_party/baritone (byte-identical upstream)
 *     -> copy src/api, src/main, src/test
 *     -> remove everything listed in tools/baritone/exclude.txt
 *     -> add tools/baritone/overlay/** (new files only; a file that shadows upstream must be excluded first)
 *     -> git apply tools/baritone/patches/NNNN-*.patch, in order, strictly
 * </pre>
 *
 * Run with a JDK 21+: {@code java tools/baritone/BaritoneSource.java <command> [options]}.
 * Commands: generate (default), resume, report, verify, export. See tools/baritone/README.md.
 * The output directory is its own git repository: one commit for the excluded upstream tree, one for the
 * overlay, then one commit per patch (subject = patch name), so patches can be edited and re-exported.
 */
public final class BaritoneSource {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path TOOLS = ROOT.resolve("tools/baritone");
    private static final String[] COPIED_ROOTS = {"src/api/java", "src/main/java", "src/test/java"};
    private static final Pattern PATCH_NAME = Pattern.compile("\\d{4}-[a-z0-9][a-z0-9-]*");
    private static final String OVERLAY_COMMIT = "overlay";
    private static final String BASE_COMMIT = "upstream";

    private static Path upstream = ROOT.resolve("third_party/baritone");
    private static Path out = ROOT.resolve("build/baritone-src");
    private static Path patchesDir = TOOLS.resolve("patches");
    private static Path excludeFile = TOOLS.resolve("exclude.txt");
    private static Path overlayDir = TOOLS.resolve("overlay");
    private static boolean threeWay;
    private static boolean skipVerify;
    private static boolean keepRepo;

    public static void main(String[] args) throws Exception {
        String command = "generate";
        List<String> rest = new ArrayList<>(List.of(args));
        if (!rest.isEmpty() && !rest.get(0).startsWith("--")) {
            command = rest.remove(0);
        }
        for (int i = 0; i < rest.size(); i++) {
            switch (rest.get(i)) {
                case "--upstream" -> upstream = Path.of(rest.get(++i)).toAbsolutePath();
                case "--out" -> out = Path.of(rest.get(++i)).toAbsolutePath();
                case "--patches" -> patchesDir = Path.of(rest.get(++i)).toAbsolutePath();
                case "--exclude" -> excludeFile = Path.of(rest.get(++i)).toAbsolutePath();
                case "--overlay" -> overlayDir = Path.of(rest.get(++i)).toAbsolutePath();
                case "--3way" -> threeWay = true;
                case "--no-verify" -> skipVerify = true;
                case "--keep-repo" -> keepRepo = true;
                default -> fail("unknown option " + rest.get(i));
            }
        }
        switch (command) {
            case "generate" -> generate(false);
            case "report" -> {
                keepRepo = true;
                generate(true);
            }
            case "resume" -> {
                keepRepo = true; // an upgrade ends with `export`, which needs the per-patch history
                resume();
            }
            case "verify" -> verify();
            case "export" -> export();
            default -> fail("unknown command " + command + " (generate | resume | report | verify | export)");
        }
    }

    // ------------------------------------------------------------------ verify

    /** Recomputes the git blob SHA-1 of every vendored file and compares it with MANIFEST.txt. */
    private static void verify() throws Exception {
        Path manifest = upstream.resolve("MANIFEST.txt");
        if (!Files.isRegularFile(manifest)) {
            System.out.println("no MANIFEST.txt in " + upstream + ", skipping byte-identity check");
            return;
        }
        Map<String, String> expected = new TreeMap<>();
        for (String line : Files.readAllLines(manifest)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\s+", 2);
            expected.put(p[1].trim(), p[0]);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            Path f = upstream.resolve(e.getKey());
            if (!Files.isRegularFile(f)) {
                problems.add("missing   " + e.getKey());
            } else if (!blobSha(Files.readAllBytes(f)).equals(e.getValue())) {
                problems.add("modified  " + e.getKey());
            }
        }
        try (Stream<Path> s = Files.walk(upstream)) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String rel = rel(upstream, f);
                if (!rel.equals("MANIFEST.txt") && !rel.equals("UPSTREAM.md") && !expected.containsKey(rel)) {
                    problems.add("unlisted  " + rel);
                }
            }
        }
        if (!problems.isEmpty()) {
            problems.forEach(System.err::println);
            fail("vendored Baritone under " + upstream + " is not byte-identical to upstream (see above). "
                    + "Never edit third_party/baritone: put changes in tools/baritone/patches instead.");
        }
        System.out.println("vendor OK: " + expected.size() + " files match the upstream blob hashes");
    }

    private static String blobSha(byte[] content) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(sha1.digest(content));
    }

    // ---------------------------------------------------------------- generate

    private static void generate(boolean report) throws Exception {
        if (!skipVerify) verify();
        deleteTree(out);
        Files.createDirectories(out);

        for (String r : COPIED_ROOTS) {
            Path src = upstream.resolve(r);
            if (!Files.isDirectory(src)) fail("upstream has no " + r + " (" + src + ")");
            copyTree(src, out.resolve(r));
        }
        applyExclusions(report);

        git("init", "-q");
        for (String c : new String[]{"core.autocrlf=false", "core.safecrlf=false", "core.filemode=false",
                "core.longpaths=true", "commit.gpgsign=false", "user.name=baritone-source",
                "user.email=baritone-source@localhost", "core.hooksPath=/dev/null", "gc.auto=0",
                "maintenance.auto=false"}) {
            String[] kv = c.split("=", 2);
            git("config", kv[0], kv[1]);
        }
        git("add", "-A");
        git("commit", "-q", "-m", BASE_COMMIT, "-m", "Pristine upstream sources minus tools/baritone/exclude.txt");

        applyOverlay();
        git("add", "-A");
        git("commit", "-q", "--allow-empty", "-m", OVERLAY_COMMIT, "-m", "tools/baritone/overlay");

        applyPatches(listPatches(), report);
    }

    /**
     * Continues an interrupted upgrade: applies the patches that have no commit yet on top of the generated tree in
     * {@code --out}, after the developer resolved a conflict and committed it with the patch name as subject.
     */
    private static void resume() throws Exception {
        if (!Files.isDirectory(out.resolve(".git"))) fail(out + " has no git history (run generate --keep-repo first)");
        if (!git("status", "--porcelain").output.isBlank()) {
            fail("uncommitted changes in " + out + ": resolve the conflict, then `git add -A && git commit -m <patch name>`");
        }
        Set<String> done = new HashSet<>();
        boolean seenOverlay = false;
        for (String l : git("log", "--reverse", "--format=%s").output.split("\\R")) {
            if (seenOverlay && !l.isBlank()) done.add(l);
            if (l.equals(OVERLAY_COMMIT)) seenOverlay = true;
        }
        List<Path> remaining = new ArrayList<>();
        for (Path p : listPatches()) {
            if (!done.remove(baseName(p))) remaining.add(p);
            else if (!remaining.isEmpty()) fail("patch " + baseName(p) + " is committed but an earlier patch is not");
        }
        applyPatches(remaining, false);
    }

    private static void applyPatches(List<Path> patches, boolean report) throws Exception {
        List<String[]> results = new ArrayList<>();
        boolean failed = false;
        for (Path patch : patches) {
            String name = baseName(patch);
            String note = offsetNote(patch);
            Result r = tryApply(patch, false);
            String how = "clean" + note;
            if (!r.ok && threeWay) {
                r = tryApply(patch, true);
                how = r.ok ? "3-way merge (context had moved)" : "CONFLICT";
            }
            if (!r.ok) {
                failed = true;
                how = threeWay ? "CONFLICT" : "FAILED";
                results.add(new String[]{name, how, firstLines(r.output, 8)});
                if (!report) {
                    System.err.println("PATCH " + how + ": " + name);
                    System.err.println(r.output);
                    if (threeWay) {
                        fail("patch " + name + " has conflicts, left as markers in " + out + " (git diff --diff-filter=U). Resolve them, then\n"
                                + "  git -C " + out + " add -A && git -C " + out + " commit -m " + name + "\n"
                                + "and run `resume --3way --out " + out + "` for the remaining patches, then `export`.");
                    }
                    fail("patch " + name + " does not apply to " + upstream
                            + " (see the failing hunk above). Rerun with --3way --keep-repo to merge it.");
                }
                git("reset", "-q", "--hard", "HEAD"); // drop conflict markers and continue
                continue;
            }
            git("add", "-A");
            if (git("diff", "--cached", "--numstat").output.isBlank()) {
                fail("patch " + name + " applied but changed nothing (was git apply run outside the generated tree?)");
            }
            String desc = description(patch);
            git("commit", "-q", "--allow-empty", "-m", name, "-m", desc.isBlank() ? name : desc);
            results.add(new String[]{name, how, ""});
        }
        List<String> leftovers = clientReferences();
        System.out.println("generated " + out + " (" + patches.size() + " patches applied)");
        if (!leftovers.isEmpty()) {
            leftovers.forEach(System.err::println);
            if (!report) {
                fail("client-side references remain after patching (above). Add or extend a patch that removes them.");
            }
        }
        if (!keepRepo && !failed && leftovers.isEmpty()) {
            writeBuildInfo();
            deleteTree(out.resolve(".git")); // plain builds want sources only; --keep-repo keeps the per-patch history for export
        }
        if (report || failed) {
            System.out.println();
            for (String[] r : results) {
                System.out.printf("%-48s %s%s%n", r[0], r[1], r[2].isEmpty() ? "" : "\n" + r[2]);
            }
            if (!leftovers.isEmpty()) System.out.println(leftovers.size() + " client-side reference(s) remain after patching");
        }
        if (failed || (report && !leftovers.isEmpty())) System.exit(1);
    }

    /** A small resource for the jar: which vendored manifest and which patch series this Baritone was built from. */
    private static void writeBuildInfo() throws Exception {
        MessageDigest series = MessageDigest.getInstance("SHA-1");
        int patchCount = 0;
        for (Path p : listPatches()) {
            series.update(baseName(p).getBytes(StandardCharsets.UTF_8));
            series.update(Files.readAllBytes(p));
            patchCount++;
        }
        Path manifest = upstream.resolve("MANIFEST.txt");
        String manifestSha = Files.isRegularFile(manifest) ? blobSha(Files.readAllBytes(manifest)) : "none";
        String text = "# generated by tools/baritone/BaritoneSource.java, do not edit\n"
                + "vendor.manifest.sha1=" + manifestSha + "\n"
                + "patches.count=" + patchCount + "\n"
                + "patches.sha1=" + HexFormat.of().formatHex(series.digest()) + "\n";
        Path file = out.resolve("resources/baritone-server-build.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static final Pattern CLIENT_REF = Pattern.compile(
            "net\\.minecraft\\.client\\b|com\\.mojang\\.blaze3d\\b|\\bMinecraft\\.getInstance\\(\\)");

    /** Early, version-independent check: the compiled tree must not mention the Minecraft client (outside comments). */
    private static List<String> clientReferences() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(out.resolve("src"))) {
            for (Path f : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                StringBuilder code = new StringBuilder();
                Matcher c = COMMENT.matcher(text);
                int last = 0;
                while (c.find()) {
                    code.append(text, last, c.start());
                    c.group().chars().filter(ch -> ch == '\n').forEach(ch -> code.append('\n')); // keep line numbers
                    last = c.end();
                }
                code.append(text.substring(last));
                String[] lines = code.toString().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (CLIENT_REF.matcher(lines[i]).find()) hits.add("  client reference: " + rel(out, f) + ":" + (i + 1) + ": " + lines[i].strip());
                }
            }
        }
        return hits;
    }

    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    private record Result(boolean ok, String output) {}

    private static Result tryApply(Path patch, boolean merge) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("apply", "--whitespace=nowarn"));
        if (merge) cmd.add("--3way");
        cmd.add(patch.toString());
        return run(out, altObjects(), "git", cmd);
    }

    /** With --3way git needs the preimage blobs of the patch; they live in the main repository's object store. */
    private static Map<String, String> altObjects() {
        if (!threeWay) return Map.of();
        try {
            Result r = run(ROOT, Map.of(), "git", List.of("rev-parse", "--path-format=absolute", "--git-common-dir"));
            if (r.ok) return Map.of("GIT_ALTERNATE_OBJECT_DIRECTORIES", r.output.trim() + "/objects");
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    /** Uses GNU patch (if present) purely to learn whether the patch applies with an offset or fuzz. */
    private static String offsetNote(Path patch) {
        try {
            Result r = run(out, Map.of(), "patch", List.of("-p1", "--dry-run", "--no-backup-if-mismatch", "-s", "-i", patch.toString()));
            // -s hides the offset chatter, so rerun verbosely
            r = run(out, Map.of(), "patch", List.of("-p1", "--dry-run", "--no-backup-if-mismatch", "-i", patch.toString()));
            StringBuilder sb = new StringBuilder();
            int offsets = 0, fuzz = 0;
            for (String l : r.output.split("\\R")) {
                if (l.contains("offset")) offsets++;
                if (l.contains("fuzz")) fuzz++;
            }
            if (offsets > 0) sb.append(" with ").append(offsets).append(" offset hunk(s)");
            if (fuzz > 0) sb.append(" with ").append(fuzz).append(" fuzzy hunk(s)");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void applyExclusions(boolean lenient) throws IOException {
        if (!Files.isRegularFile(excludeFile)) fail("missing " + excludeFile);
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(out)) {
            s.filter(Files::isRegularFile).forEach(all::add);
        }
        int removed = 0;
        for (String raw : Files.readAllLines(excludeFile)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + line);
            int hits = 0;
            for (Path f : all) {
                if (Files.exists(f) && m.matches(Path.of(rel(out, f)))) {
                    Files.delete(f);
                    hits++;
                }
            }
            if (hits == 0) {
                String msg = "exclude.txt entry matches nothing (moved or removed upstream?): " + line;
                if (!lenient) fail(msg);
                System.out.println("WARNING " + msg);
            }
            removed += hits;
        }
        pruneEmptyDirs(out);
        System.out.println("excluded " + removed + " files");
    }

    private static void applyOverlay() throws IOException {
        if (!Files.isDirectory(overlayDir)) return;
        try (Stream<Path> s = Files.walk(overlayDir)) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                Path target = out.resolve(rel(overlayDir, f));
                if (Files.exists(target)) {
                    fail("overlay file " + rel(overlayDir, f) + " collides with an upstream file: "
                            + "add it to exclude.txt first (a stub must replace an excluded file explicitly)");
                }
                Files.createDirectories(target.getParent());
                Files.copy(f, target);
            }
        }
    }

    private static List<Path> listPatches() throws IOException {
        if (!Files.isDirectory(patchesDir)) return List.of();
        try (Stream<Path> s = Files.list(patchesDir)) {
            List<Path> l = s.filter(p -> p.getFileName().toString().endsWith(".patch")).sorted().toList();
            for (Path p : l) {
                if (!PATCH_NAME.matcher(baseName(p)).matches()) {
                    fail("bad patch file name " + p.getFileName() + " (want NNNN-kebab-case.patch)");
                }
            }
            return l;
        }
    }

    // ------------------------------------------------------------------ export

    /** Rewrites tools/baritone/patches from the commits made on top of the overlay commit in the generated tree. */
    private static void export() throws Exception {
        if (!Files.isDirectory(out.resolve(".git"))) fail(out + " has no git history (run generate --keep-repo first)");
        Result st = git("status", "--porcelain");
        if (!st.output.isBlank()) fail("uncommitted changes in " + out + " - commit them first with the patch name as subject");
        Result log = git("log", "--reverse", "--format=%H%x09%s");
        List<String[]> commits = new ArrayList<>();
        boolean seenOverlay = false;
        for (String l : log.output.split("\\R")) {
            if (l.isBlank()) continue;
            String[] p = l.split("\t", 2);
            if (seenOverlay) commits.add(p);
            if (p[1].equals(OVERLAY_COMMIT)) seenOverlay = true;
        }
        if (!seenOverlay) fail("no '" + OVERLAY_COMMIT + "' commit found in " + out);
        for (String[] c : commits) {
            if (!PATCH_NAME.matcher(c[1]).matches()) fail("commit " + c[0].substring(0, 8) + " subject '" + c[1] + "' is not NNNN-kebab-name");
        }
        Files.createDirectories(patchesDir);
        try (Stream<Path> s = Files.list(patchesDir)) {
            for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".patch"))::iterator) Files.delete(p);
        }
        for (String[] c : commits) {
            String body = git("log", "-1", "--format=%b", c[0]).output.strip();
            String diff = git("diff", "--no-color", "--no-ext-diff", "--no-renames", c[0] + "^", c[0]).output;
            String text = (body.isBlank() || body.equals(c[1]) ? "" : body + "\n\n") + diff;
            Files.writeString(patchesDir.resolve(c[1] + ".patch"), text, StandardCharsets.UTF_8);
            System.out.println("wrote " + c[1] + ".patch");
        }
    }

    private static String description(Path patch) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : Files.readAllLines(patch, StandardCharsets.UTF_8)) {
            if (l.startsWith("diff --git ")) break;
            sb.append(l).append('\n');
        }
        return sb.toString().strip();
    }

    // ----------------------------------------------------------------- helpers

    private static Result git(String... args) throws Exception {
        Result r = run(out, Map.of(), "git", List.of(args));
        if (!r.ok) fail("git " + String.join(" ", args) + " failed in " + out + ":\n" + r.output);
        return r;
    }

    private static Result run(Path dir, Map<String, String> env, String exe, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        byte[] bytes;
        try (InputStream in = p.getInputStream()) {
            bytes = in.readAllBytes();
        }
        int code = p.waitFor();
        return new Result(code == 0, new String(bytes, StandardCharsets.UTF_8));
    }

    private static String firstLines(String s, int n) {
        return "    " + String.join("\n    ", s.strip().lines().limit(n).toList());
    }

    private static String baseName(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.length() - ".patch".length());
    }

    private static String rel(Path base, Path f) {
        return base.relativize(f).toString().replace('\\', '/');
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, to.resolve(from.relativize(f).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                f.toFile().setWritable(true); // git object files are read-only on Windows
                Files.delete(f);
            }
        }
    }

    private static void pruneEmptyDirs(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path d : (Iterable<Path>) s.filter(Files::isDirectory).sorted(Comparator.reverseOrder())::iterator) {
                if (!d.equals(p)) {
                    try (Stream<Path> c = Files.list(d)) {
                        if (c.findAny().isEmpty()) Files.delete(d);
                    }
                }
            }
        }
    }

    private static void fail(String message) {
        System.err.println("baritone-source: " + message);
        System.exit(2);
    }
}
