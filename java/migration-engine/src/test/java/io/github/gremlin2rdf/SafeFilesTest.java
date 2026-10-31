package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeFilesTest {
    @TempDir Path root;
    @Test void resolvedDirectoryLinksCannotEscapeOwnedArtifacts() throws Exception {
        Path owned = Files.createDirectory(root.resolve("owned")), outside = Files.createDirectory(root.resolve("outside"));
        Path link = owned.resolve("external");
        Files.writeString(outside.resolve("evidence.json"), "{}");
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd.exe", "/d", "/c",
                "mklink /J \"" + link + "\" \"" + outside + "\"").redirectErrorStream(true).start();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } else Files.createSymbolicLink(link, outside);
        assertThrows(SecurityException.class, () -> SafeFiles.resolve(owned, "external/evidence.json"));
        assertThrows(SecurityException.class, () -> SafeFiles.resolve(owned, "external/new.json"));
    }
}
