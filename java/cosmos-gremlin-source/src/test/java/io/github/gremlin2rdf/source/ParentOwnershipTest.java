package io.github.gremlin2rdf.source;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ParentOwnershipTest {
    public static void main(String[] args) throws Exception {
        SourceMain.monitorParent();
        System.out.println("ready"); System.out.flush();
        new CountDownLatch(1).await();
    }
    @Test void actualChildHaltsWhenItsOwningPipeCloses() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var child = new ProcessBuilder(java.toString(), "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            ParentOwnershipTest.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var reader = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            var ready = new CompletableFuture<String>();
            Thread.startVirtualThread(() -> {
                try { ready.complete(reader.readLine()); }
                catch (Exception e) { ready.completeExceptionally(e); }
            });
            assertEquals("ready", ready.get(10, TimeUnit.SECONDS));
            assertFalse(child.waitFor(100, TimeUnit.MILLISECONDS));
            child.getOutputStream().close();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS)); assertEquals(5, child.exitValue());
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS)); }
        }
    }
}
