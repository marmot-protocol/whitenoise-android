import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises host-dependent failure modes in the actual backport archive and Git command helpers. */
public class ComposeUiBackportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** Archive bytes and extra fields must not depend on timezone or input-map insertion order. */
    @Test
    public void zipBytesRemainIdenticalAcrossTimezones() throws Exception {
        TimeZone original = TimeZone.getDefault();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("z.txt", "last".getBytes(UTF_8));
        entries.put("a.txt", "first".getBytes(UTF_8));
        byte[] expected = null;
        try {
            for (String zone : new String[] {"UTC", "America/New_York", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                byte[] actual = ComposeUiBackport.writeZip(entries);
                if (expected == null) expected = actual;
                assertArrayEquals(zone, expected, actual);
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(actual))) {
                    var first = zip.getNextEntry();
                    assertEquals("a.txt", first.getName());
                    assertEquals(LocalDateTime.of(1980, 2, 1, 0, 0), first.getTimeLocal());
                    assertEquals(0, first.getExtra() == null ? 0 : first.getExtra().length);
                    assertArrayEquals(entries.get("a.txt"), zip.readAllBytes());
                }
            }
            Map<String, byte[]> reversed = new LinkedHashMap<>();
            reversed.put("a.txt", entries.get("a.txt"));
            reversed.put("z.txt", entries.get("z.txt"));
            assertArrayEquals(expected, ComposeUiBackport.writeZip(reversed));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /** Global autocrlf/eol preferences cannot change the patched file's pinned LF bytes. */
    @Test
    public void gitApplyPreservesLfUnderGlobalCrlfSettings() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path source = root.resolve("example.txt");
        Files.writeString(source, "before\n", UTF_8);
        Path patch = root.resolve("change.patch");
        Files.writeString(patch, """
                diff --git a/example.txt b/example.txt
                --- a/example.txt
                +++ b/example.txt
                @@ -1 +1 @@
                -before
                +after
                """, UTF_8);
        Path config = root.resolve("global.gitconfig");
        Files.writeString(config, "[core]\n\tautocrlf = true\n\teol = crlf\n", UTF_8);
        for (boolean checkOnly : new boolean[] {true, false}) {
            ProcessBuilder builder = new ProcessBuilder(ComposeUiBackport.patchCommand(patch, checkOnly));
            builder.directory(root.toFile()).redirectErrorStream(true);
            builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
            builder.environment().put("GIT_CONFIG_GLOBAL", config.toString());
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CEILING_DIRECTORIES", root.getParent().toString());
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), UTF_8);
            assertEquals(output, 0, process.waitFor());
            assertArrayEquals((checkOnly ? "before\n" : "after\n").getBytes(UTF_8), Files.readAllBytes(source));
        }
    }
}
