package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtractionTest {
    @TempDir Path root;
    private final List<IdentityEntry> entries = List.of(
        new IdentityEntry(1, GraphRecord.Kind.vertex, GraphRecord.Value.string("p"), GraphRecord.Value.integer(1), null, null),
        new IdentityEntry(1, GraphRecord.Kind.vertex, GraphRecord.Value.string("p"), GraphRecord.Value.integer(2), null, null));
    private Project project(Path manifest, long disk) throws IOException {
        var source = new Project.Source("fixture", "localhost", 443, "database", "container", "/pk", null,
            "verified-identity-manifest", "identities.ndjson", SafeFiles.fileHash(manifest), null, null, null, true);
        return new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "fixture", 1, "0.1.0", Mapping.standard(), source, null,
            new Project.Limits(100, 1048576, 262144, 30000, 3, 100, 1, disk), "immutable", Map.of(), List.of(), Map.of(), List.of());
    }
    private Path manifest() throws IOException {
        Path manifest = root.resolve("identities.ndjson");
        Files.writeString(manifest, String.join("\n", entries.stream().map(Json::text).toList()) + "\n"); return manifest;
    }
    private Extraction.Reader reader(Project project, int failAt) {
        return new Extraction.Reader() {
            int reads;
            @Override public GraphRecord read(IdentityEntry entry) throws IOException {
                if (++reads == failAt) throw new IOException("Injected source loss");
                return new GraphRecord(entry.kind(), new GraphRecord.Identity("fixture", entry.partition(), entry.id()), "person", null, null, null,
                    List.of(), Map.of(), new GraphRecord.Provenance("p", project.source().identityManifestHash(), "immutable", GraphRecord.Fidelity.complete));
            }
            @Override public long count(GraphRecord.Kind kind, GraphRecord.Value partition, String label) { return kind == GraphRecord.Kind.vertex ? 2 : 0; }
        };
    }
    @Test void resumesConfirmedPrefixWithoutDuplicatingRecordsAndReconcilesLabels() throws Exception {
        Path manifest = manifest(), output = root.resolve("records.ndjson"); Project project = project(manifest, 0);
        assertThrows(IOException.class, () -> Extraction.run(project, manifest, output, reader(project, 2)));
        assertFalse(Files.exists(root.resolve("records.ndjson.manifest.json")));
        Files.writeString(output, "unconfirmed-tail", StandardOpenOption.APPEND);
        var result = Extraction.run(project, manifest, output, reader(project, -1));
        assertEquals(2, result.records()); assertEquals(2, Files.readAllLines(output).size());
        assertEquals(2, result.labels().values().iterator().next()); assertTrue(result.strictEquivalenceClaim());
        assertEquals(result.sha256(), SafeFiles.fileHash(output));
        assertEquals(result, Extraction.run(project, manifest, output, reader(project, 1)));
    }
    @Test void refusesChangedInputsCorruptPrefixesUnownedFilesAndInsufficientDisk() throws Exception {
        Path manifest = manifest(), output = root.resolve("records.ndjson"); Project project = project(manifest, 0);
        assertThrows(IOException.class, () -> Extraction.run(project, manifest, output, reader(project, 2)));
        assertThrows(IOException.class, () -> Extraction.run(project(manifest, 1), manifest, output, reader(project, -1)));
        Files.writeString(output, "corrupt");
        assertThrows(IOException.class, () -> Extraction.run(project, manifest, output, reader(project, -1)));
        Path unowned = root.resolve("unowned.ndjson"); Files.writeString(unowned, "preserve");
        assertThrows(IOException.class, () -> Extraction.run(project, manifest, unowned, reader(project, -1)));
        assertEquals("preserve", Files.readString(unowned));
        Path disk = root.resolve("disk.ndjson"); Project impossible = project(manifest, Long.MAX_VALUE);
        assertThrows(IOException.class, () -> Extraction.run(impossible, manifest, disk, reader(impossible, -1)));
        assertFalse(Files.exists(disk));
    }
    @Test void refusesPendingCorruptionAndCompetingWriters() throws Exception {
        Path manifest = manifest(), output = root.resolve("records.ndjson"); Project project = project(manifest, 0);
        try (var channel = java.nio.channels.FileChannel.open(root.resolve("records.ndjson.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThrows(IOException.class, () -> Extraction.run(project, manifest, output, reader(project, -1)));
        }
        assertThrows(IOException.class, () -> Extraction.run(project, manifest, output, reader(project, 2)));
        Files.writeString(root.resolve("records.ndjson.checkpoint.json.pending"), "{}");
        assertThrows(Exception.class, () -> Extraction.run(project, manifest, output, reader(project, -1)));
    }
    @Test void detectsSourceCountDriftAndTruncatedManifest() throws Exception {
        Path manifest = manifest(), output = root.resolve("records.ndjson"); Project project = project(manifest, 0);
        var drift = new Extraction.Reader() {
            @Override public GraphRecord read(IdentityEntry entry) throws Exception { return reader(project, -1).read(entry); }
            @Override public long count(GraphRecord.Kind kind, GraphRecord.Value partition, String label) { return kind == GraphRecord.Kind.vertex ? 3 : 0; }
        };
        assertThrows(IOException.class, () -> Extraction.run(project, manifest, output, drift));
        Files.writeString(manifest, Json.text(entries.getFirst()));
        Project truncated = project(manifest, 0);
        assertThrows(IOException.class, () -> Extraction.run(truncated, manifest, output, reader(truncated, -1)));
    }
}
