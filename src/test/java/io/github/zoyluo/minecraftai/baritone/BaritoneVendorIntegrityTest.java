package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * third_party/baritone must stay byte-identical to upstream: every change we need is a patch in tools/baritone. The
 * manifest holds the upstream git blob SHA-1 of each vendored file, so an accidental edit (or a CRLF conversion by a
 * checkout) fails here and names the file. Mirrors {@code BaritoneSource verify}, which also runs before every generation.
 */
class BaritoneVendorIntegrityTest {
    private static final Path VENDOR = Path.of("third_party/baritone");

    @Test
    void everyVendoredFileMatchesItsUpstreamBlobHashAndNothingElseIsThere() throws Exception {
        Map<String, String> expected = new TreeMap<>();
        for (String line : Files.readAllLines(VENDOR.resolve("MANIFEST.txt"))) {
            if (!line.isBlank()) {
                String[] parts = line.split("\\s+", 2);
                String path = parts[1].trim();
                if (!isReadmeArtifact(path)) {
                    expected.put(path, parts[0]);
                }
            }
        }
        assertTrue(expected.size() > 300, "manifest looks truncated: " + expected.size() + " entries");

        Map<String, String> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(VENDOR)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String rel = VENDOR.relativize(file).toString().replace('\\', '/');
                if (!rel.equals("MANIFEST.txt") && !rel.equals("UPSTREAM.md")) {
                    actual.put(rel, blobSha(Files.readAllBytes(file)));
                }
            }
        }
        assertEquals(expected, actual, "third_party/baritone differs from the manifest (edit patches, not the vendor tree)");
    }

    @Test
    void upstreamNotesNameTheCommitTheManifestWasTakenFrom() throws IOException {
        String notes = Files.readString(VENDOR.resolve("UPSTREAM.md"));
        assertTrue(notes.contains("23723891da460ef15797b02fe5b385b0c5b163cc"), "UPSTREAM.md must record the upstream commit");
        assertTrue(notes.contains("v1.17.0"), "UPSTREAM.md must record the upstream tag");
        assertFalse(Files.readString(VENDOR.resolve("src/main/java/baritone/Baritone.java")).contains("\r"),
                "vendored sources must keep upstream's LF line endings");
    }

    private static String blobSha(byte[] content) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(sha1.digest(content));
    }

    /** Matches BaritoneSource.verify: README artifacts are deliberately not part of the vendor tree. */
    private static boolean isReadmeArtifact(String path) {
        String name = Path.of(path).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("readme");
    }
}
