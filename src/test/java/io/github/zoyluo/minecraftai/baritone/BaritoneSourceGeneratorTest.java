package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Each rule either applies or fails loudly at an exact spot": drives tools/baritone/BaritoneSource.java (compiled once
 * here) on a tiny synthetic upstream and checks the success path and every way the pipeline is supposed to refuse.
 * Needs {@code git} on the PATH, like the build itself.
 */
class BaritoneSourceGeneratorTest {
    private static final Path TOOL = Path.of("tools/baritone/BaritoneSource.java").toAbsolutePath();
    private static Path toolClasses;
    private static Path root;

    private Path upstream;
    private Path out;
    private Path patches;
    private Path exclude;
    private Path overlay;

    @BeforeAll
    static void compileTheTool() throws IOException {
        root = Files.createTempDirectory("baritone-generator-test");
        toolClasses = Files.createDirectories(root.resolve("tool"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK");
        assertEquals(0, compiler.run(null, null, null, "-d", toolClasses.toString(), TOOL.toString()),
                "tools/baritone/BaritoneSource.java does not compile");
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (root != null) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @BeforeEach
    void freshUpstream() throws Exception {
        Path dir = Files.createTempDirectory(root, "case");
        upstream = dir.resolve("upstream");
        out = dir.resolve("out");
        patches = dir.resolve("patches");
        exclude = dir.resolve("exclude.txt");
        overlay = dir.resolve("overlay");
        write(upstream.resolve("src/api/java/p/A.java"), "package p;\npublic interface A {}\n");
        write(upstream.resolve("src/main/java/p/B.java"), "package p;\npublic class B { int x = 1; }\n// end\n");
        write(upstream.resolve("src/main/java/p/Gui.java"), "package p;\npublic class Gui {}\n");
        write(upstream.resolve("src/test/java/p/T.java"), "package p;\npublic class T {}\n");
        Files.createDirectories(patches);
        Files.createDirectories(overlay);
        Files.writeString(exclude, "# nothing excluded yet\n");
        writeManifest();
    }

    @Test
    void appliesTheSeriesInOrderExcludesAndOverlaysAndLeavesNoGitDirectory() throws Exception {
        Files.writeString(exclude, "src/main/java/p/Gui.java\n");
        write(overlay.resolve("src/main/java/p/Gui.java"), "package p;\npublic class Gui { /* stub */ }\n");
        write(overlay.resolve("src/api/java/p/New.java"), "package p;\npublic class New {}\n");
        Files.writeString(patches.resolve("0001-x-two.patch"), "Bumps x.\n\n" + patchXTo(1, 2));
        Files.writeString(patches.resolve("0002-x-three.patch"), patchXTo(2, 3));

        Run run = generate();
        assertEquals(0, run.exit, run.output);
        assertTrue(Files.readString(out.resolve("src/main/java/p/B.java")).contains("int x = 3;"), "both patches must apply, in order");
        assertTrue(Files.readString(out.resolve("src/main/java/p/Gui.java")).contains("stub"), "the overlay stub replaces the excluded file");
        assertTrue(Files.exists(out.resolve("src/api/java/p/New.java")));
        assertTrue(Files.exists(out.resolve("src/test/java/p/T.java")), "upstream tests are copied for the baritoneTest source set");
        assertFalse(Files.exists(out.resolve(".git")), "a plain build leaves sources only");
    }

    @Test
    void aPatchThatDoesNotApplyFailsAndNamesThePatch() throws Exception {
        Files.writeString(patches.resolve("0001-fine.patch"), patchXTo(1, 2));
        Files.writeString(patches.resolve("0002-stale-context.patch"), patchXTo(7, 8)); // expects x = 7, upstream has 2
        Run run = generate();
        assertEquals(2, run.exit, run.output);
        assertTrue(run.output.contains("0002-stale-context"), run.output);
        assertFalse(run.output.contains("0001-fine has"), run.output);
    }

    @Test
    void anExclusionThatMatchesNothingFails() throws Exception {
        Files.writeString(exclude, "src/main/java/p/Moved.java\n");
        Run run = generate();
        assertEquals(2, run.exit, run.output);
        assertTrue(run.output.contains("src/main/java/p/Moved.java"), run.output);
    }

    @Test
    void anOverlayFileThatShadowsUpstreamWithoutBeingExcludedFails() throws Exception {
        write(overlay.resolve("src/main/java/p/Gui.java"), "package p;\npublic class Gui {}\n");
        Run run = generate();
        assertEquals(2, run.exit, run.output);
        assertTrue(run.output.contains("collides"), run.output);
    }

    @Test
    void aClientReferenceThatSurvivesThePatchesFails() throws Exception {
        write(upstream.resolve("src/main/java/p/Client.java"),
                "package p;\nimport net.minecraft.client.Minecraft;\n/* net.minecraft.client.Comment is fine */\npublic class Client {}\n");
        writeManifest();
        Run run = generate();
        assertEquals(2, run.exit, run.output);
        assertTrue(run.output.contains("Client.java:2"), run.output);
        assertFalse(run.output.contains("Client.java:3"), "mentions inside comments are not references: " + run.output);
    }

    @Test
    void aVendorTreeThatWasEditedAfterTheManifestIsRefused() throws Exception {
        write(upstream.resolve("src/main/java/p/B.java"), "package p;\npublic class B { int x = 99; }\n// end\n");
        Run run = generate();
        assertEquals(2, run.exit, run.output);
        assertTrue(run.output.contains("modified") && run.output.contains("B.java"), run.output);
    }

    @Test
    void exportRewritesThePatchesFromTheCommitsAndTheNewSeriesReplaysToTheSameTree() throws Exception {
        Files.writeString(patches.resolve("0001-x-two.patch"), patchXTo(1, 2));
        Run kept = run("generate", "--keep-repo");
        assertEquals(0, kept.exit, kept.output);
        Path b = out.resolve("src/main/java/p/B.java");
        Files.writeString(b, Files.readString(b).replace("int x = 2;", "int x = 2; int y = 5;"));
        assertEquals(0, git(out, "add", "-A").exit);
        assertEquals(0, git(out, "commit", "-q", "-m", "0002-add-y", "-m", "Adds y.").exit);

        Run export = run("export");
        assertEquals(0, export.exit, export.output);
        assertTrue(Files.readString(patches.resolve("0002-add-y.patch")).startsWith("Adds y."), "the description leads the patch");

        String expectedTree = Files.readString(b);
        Run replay = generate();
        assertEquals(0, replay.exit, replay.output);
        assertEquals(expectedTree, Files.readString(b));
    }

    @Test
    void generateRefusesToDiscardUncommittedAuthoringWork() throws Exception {
        Files.writeString(patches.resolve("0001-x-two.patch"), patchXTo(1, 2));
        assertEquals(0, run("generate", "--keep-repo").exit);
        Path b = out.resolve("src/main/java/p/B.java");
        Files.writeString(b, Files.readString(b) + "// my edit\n");

        Run refused = generate();
        assertEquals(2, refused.exit, refused.output);
        assertTrue(refused.output.contains("uncommitted work"), refused.output);
        assertTrue(Files.readString(b).contains("// my edit"), "the edit must still be there");

        Run forced = run("generate", "--force");
        assertEquals(0, forced.exit, forced.output);
        assertFalse(Files.readString(b).contains("// my edit"));
    }

    @Test
    void resumeAppliesThePatchesThatAreStillMissingAfterAFixedConflict() throws Exception {
        Files.writeString(patches.resolve("0001-x-two.patch"), patchXTo(1, 2));
        Files.writeString(patches.resolve("0002-x-three.patch"), patchXTo(9, 3)); // wrong expectation: fails
        Run stopped = run("generate", "--keep-repo");
        assertEquals(2, stopped.exit, stopped.output);
        assertTrue(Files.readString(out.resolve("src/main/java/p/B.java")).contains("int x = 2;"), "0001 stays applied");

        Files.writeString(patches.resolve("0002-x-three.patch"), patchXTo(2, 3)); // the developer fixed the patch
        Run resumed = run("resume");
        assertEquals(0, resumed.exit, resumed.output);
        assertTrue(Files.readString(out.resolve("src/main/java/p/B.java")).contains("int x = 3;"), resumed.output);
        assertTrue(Files.exists(out.resolve(".git")), "resume keeps the history for export");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static String patchXTo(int from, int to) {
        return "diff --git a/src/main/java/p/B.java b/src/main/java/p/B.java\n"
                + "--- a/src/main/java/p/B.java\n"
                + "+++ b/src/main/java/p/B.java\n"
                + "@@ -1,3 +1,3 @@\n"
                + " package p;\n"
                + "-public class B { int x = " + from + "; }\n"
                + "+public class B { int x = " + to + "; }\n"
                + " // end\n";
    }

    private record Run(int exit, String output) {}

    private Run generate() throws Exception {
        return run("generate");
    }

    private Run run(String... command) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", toolClasses.toString(), "BaritoneSource"));
        cmd.addAll(List.of(command));
        cmd.addAll(List.of("--upstream", upstream.toString(), "--out", out.toString(), "--patches", patches.toString(),
                "--exclude", exclude.toString(), "--overlay", overlay.toString()));
        return exec(cmd, root);
    }

    private static Run git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        return exec(cmd, dir);
    }

    private static Run exec(List<String> cmd, Path dir) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timed out: " + cmd);
        return new Run(p.exitValue(), output);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeManifest() throws Exception {
        TreeMap<String, String> entries = new TreeMap<>();
        try (Stream<Path> files = Files.walk(upstream)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String rel = upstream.relativize(f).toString().replace('\\', '/');
                if (!rel.equals("MANIFEST.txt")) {
                    byte[] bytes = Files.readAllBytes(f);
                    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                    sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
                    entries.put(rel, HexFormat.of().formatHex(sha1.digest(bytes)));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        entries.forEach((path, sha) -> sb.append(sha).append("  ").append(path).append('\n'));
        Files.writeString(upstream.resolve("MANIFEST.txt"), sb.toString());
    }
}
