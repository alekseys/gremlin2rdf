package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    @Test void truncatedOrOversizedInputStillUsesProtocolFrames() throws Exception {
        for (String input : List.of("truncated", "x".repeat(65537))) {
            var output = new ByteArrayOutputStream();
            Protocol.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(output),
                (operation, payload, engine) -> Map.of());
            var frames = output.toString(StandardCharsets.UTF_8).split("\n");
            assertEquals(2, frames.length);
            assertEquals("FRAME_BOUND_OR_TRUNCATION", Json.MAPPER.readValue(frames[1], Protocol.Message.class).payload().path("code").asText());
        }
    }
    @Test void helloAndVersionMismatchAreStructuredAndMonotonic() throws Exception {
        var output = new ByteArrayOutputStream();
        String message = Json.text(new Protocol.Message(2, "status", "run", "op", 0, "2026-10-31T00:00:00Z",
            "control", Json.MAPPER.valueToTree(Map.of()))) + "\n";
        Protocol.serve(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)), new PrintStream(output),
            (operation, payload, engine) -> Map.of());
        String[] frames = output.toString(StandardCharsets.UTF_8).split("\n");
        var hello = Json.MAPPER.readValue(frames[0], Protocol.Message.class);
        var error = Json.MAPPER.readValue(frames[1], Protocol.Message.class);
        assertEquals("hello", hello.type()); assertEquals("error", error.type());
        assertTrue(error.sequence() > hello.sequence());
    }
    @Test void secretAndSourceValuesCannotAppearInRoutineErrors() {
        var secret = "private-token-injection-'\"";
        String diagnostic = Json.text(Main.diagnostic(new IOException("Unable to read " + secret)));
        assertFalse(diagnostic.contains(secret));
        assertEquals("ARTIFACT_OR_STATE_ERROR", Main.diagnostic(new IOException(secret)).get("code"));
    }
    @Test void importedArtifactPathsCannotEscapeRunOwnership() {
        for (String value : List.of("../x", "/tmp/x", "C:\\private", "x/../../y", "x\\y"))
            assertThrows(IllegalArgumentException.class, () -> SafeFiles.relative(value));
    }
    @Test void controlCannotCancelAnotherOperationInTheSameRun() throws Exception {
        var input = new PipedInputStream(); var writer = new PipedOutputStream(input);
        var output = new ByteArrayOutputStream();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var current = new java.util.concurrent.atomic.AtomicReference<Engine>();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var server = executor.submit(() -> {
                Protocol.serve(input, new PrintStream(output), (operation, payload, engine) -> {
                    current.set(engine); started.countDown();
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Fixture timeout");
                    return Map.of("status", engine.stoppedStatus());
                });
                return null;
            });
            try {
                writer.write((Json.text(new Protocol.Message(1, "start", "run", "owned", 0, "2026-10-31T00:00:00Z", "control",
                    Json.MAPPER.valueToTree(Map.of("operation", "test")))) + "\n").getBytes(StandardCharsets.UTF_8));
                writer.flush(); assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                writer.write((Json.text(new Protocol.Message(1, "cancel", "run", "foreign", 1, "2026-10-31T00:00:00Z", "control",
                    Json.MAPPER.valueToTree(Map.of()))) + "\n").getBytes(StandardCharsets.UTF_8)); writer.flush();
                long deadline = System.nanoTime() + 5000000000L;
                while (!output.toString(StandardCharsets.UTF_8).contains("OPERATION_MISMATCH") && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("OPERATION_MISMATCH"));
                assertFalse(current.get().stopping());
                writer.write((Json.text(new Protocol.Message(1, "cancel", "run", "owned", 2, "2026-10-31T00:00:00Z", "control",
                    Json.MAPPER.valueToTree(Map.of()))) + "\n").getBytes(StandardCharsets.UTF_8)); writer.flush();
                deadline = System.nanoTime() + 5000000000L;
                while (!current.get().stopping() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(current.get().stopping());
            } finally { release.countDown(); writer.close(); }
            server.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
