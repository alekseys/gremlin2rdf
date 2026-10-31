package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceCompatibilityTest {
    @TempDir Path root;
    private Project base() {
        var source = new Project.Source("fixture", "localhost", 443, "db", "graph", "/pk", null,
            "verified-identity-manifest", "manifest.ndjson", "0".repeat(64), "compatibility.json", null, null, true);
        return new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "fixture", 1, "0.1.0", Mapping.standard(), source, null,
            Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
    }
    private Project evidence(Project base, boolean observed, String hash) throws Exception {
        var result = Map.ofEntries(Map.entry("sourceManifestHash", base.source().identityManifestHash()), Map.entry("driverVersion", "3.4.13"),
            Map.entry("serializer", "GraphSONMessageSerializerV2d0"), Map.entry("liveEndpointObserved", observed),
            Map.entry("tlsHostnameVerified", true), Map.entry("partitionQualifiedLookup", true), Map.entry("typedValuesPreserved", true),
            Map.entry("edgeFidelity", true), Map.entry("propertyFidelity", true), Map.entry("edgeScopes", List.of("sourceVertex")),
            Map.entry("status", "succeeded"), Map.entry("sourceCountsReconciled", true), Map.entry("stableRepeatedReads", true),
            Map.entry("systemMetadataFidelity", true));
        Path file = root.resolve("compatibility.json");
        Files.writeString(file, Json.text(Map.of("schemaVersion", 1, "runId", base.runId(), "inputHash", base.inputHash(),
            "operation", "source-compatibility", "status", "succeeded", "result", result)));
        return new Project(base.schemaVersion(), base.migrationKind(), base.baselineRepositorySha(), base.runId(), base.protocolVersion(),
            base.workerVersion(), base.mapping(), base.source(), base.target(), base.limits(), base.consistency(),
            Map.of("source-compatibility", new Project.Phase("succeeded", List.of(), List.of("compatibility.json"), hash == null ? base.inputHash() : hash)),
            List.of(), Map.of(), List.of(new Project.Artifact("compatibility.json", SafeFiles.fileHash(file), base.runId(), true)));
    }
    @Test void pathPresenceNeverEstablishesCompatibility() {
        assertThrows(Exception.class, () -> SourceCompatibility.read(base(), root));
    }
    @Test void verifiesOwnershipHashInputsAndRealObservationFlag() throws Exception {
        var current = evidence(base(), true, null);
        assertTrue(SourceCompatibility.supports(SourceCompatibility.read(current, root), GraphRecord.EdgeScope.sourceVertex));
        assertFalse(SourceCompatibility.supports(SourceCompatibility.read(current, root), GraphRecord.EdgeScope.sourcePartition));
        Files.writeString(root.resolve("compatibility.json"), "{}");
        assertThrows(Exception.class, () -> SourceCompatibility.read(current, root));
        var offline = evidence(base(), false, null);
        assertThrows(Exception.class, () -> SourceCompatibility.read(offline, root));
        var stale = evidence(base(), true, "1".repeat(64));
        assertThrows(Exception.class, () -> SourceCompatibility.read(stale, root));
    }
}
