package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompatibilityProbeTest {
    @TempDir Path root;
    private final GraphRecord.Value p = GraphRecord.Value.string("p"), q = GraphRecord.Value.string("q"), id = GraphRecord.Value.string("same-id");
    private List<IdentityEntry> entries() {
        return List.of(new IdentityEntry(1, GraphRecord.Kind.vertex, p, id, null, null), new IdentityEntry(1, GraphRecord.Kind.vertex, q, id, null, null),
            new IdentityEntry(1, GraphRecord.Kind.edge, p, GraphRecord.Value.string("e0"), id, GraphRecord.EdgeScope.sourcePartition),
            new IdentityEntry(1, GraphRecord.Kind.edge, p, GraphRecord.Value.string("e1"), id, GraphRecord.EdgeScope.sourcePartition),
            new IdentityEntry(1, GraphRecord.Kind.edge, p, GraphRecord.Value.string("e2"), id, GraphRecord.EdgeScope.sourcePartition));
    }
    private Project project(Path manifest) throws Exception {
        var source = new Project.Source("fixture", "localhost", 443, "db", "graph", "/pk", null, "verified-identity-manifest",
            "identities.ndjson", SafeFiles.fileHash(manifest), null, null, null, true);
        return new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "fixture", 1, "0.1.0", Mapping.standard(), source, null,
            Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
    }
    private Extraction.Reader reader(Project project, boolean unstable) {
        return new Extraction.Reader() {
            int reads;
            @Override public GraphRecord read(IdentityEntry entry) {
                boolean vertex = entry.kind() == GraphRecord.Kind.vertex;
                var properties = vertex ? List.of(
                    new GraphRecord.Property("tag", GraphRecord.Value.string("same"), GraphRecord.Value.string("a"), Map.of("verified", new GraphRecord.Value(GraphRecord.ValueType.booleanValue, "true")), GraphRecord.Fidelity.complete),
                    new GraphRecord.Property("tag", GraphRecord.Value.string("same"), GraphRecord.Value.string("b"), Map.of(), GraphRecord.Fidelity.complete),
                    new GraphRecord.Property("age", GraphRecord.Value.integer(1), GraphRecord.Value.string("c"), Map.of(), GraphRecord.Fidelity.complete),
                    new GraphRecord.Property("ratio", new GraphRecord.Value(GraphRecord.ValueType.doubleValue, "-0.0"), GraphRecord.Value.string("d"), Map.of(), GraphRecord.Fidelity.complete)) : List.<GraphRecord.Property>of();
                var source = new GraphRecord.Identity("fixture", p, id);
                var destination = entry.id().lexical().equals("e2") ? source : new GraphRecord.Identity("fixture", q, id);
                var metadata = new HashMap<String, GraphRecord.Value>(Map.of("_ts", GraphRecord.Value.integer(123), "_etag", GraphRecord.Value.string("opaque"),
                    "_self", GraphRecord.Value.string("self"), "_attachments", GraphRecord.Value.string("attachments"), "_rid", GraphRecord.Value.string("rid")));
                if (!vertex) { metadata.put("inVPartition", destination.partition()); metadata.put("outVPartition", source.partition()); }
                return new GraphRecord(entry.kind(), new GraphRecord.Identity("fixture", entry.partition(), entry.id()),
                    unstable && ++reads % 2 == 0 ? "changed" : vertex ? "person" : "knows", vertex ? null : source, vertex ? null : destination, entry.edgeScope(),
                    properties, metadata, new GraphRecord.Provenance("fixture", project.source().identityManifestHash(), "immutable", GraphRecord.Fidelity.complete));
            }
            @Override public long count(GraphRecord.Kind kind, GraphRecord.Value partition, String label) { return kind == GraphRecord.Kind.vertex ? 2 : 3; }
        };
    }
    @Test void evaluatesRealObservationRequirementsAgainstPureFixturesWithoutCallingCosmos() throws Exception {
        Path manifest = root.resolve("identities.ndjson");
        Files.writeString(manifest, String.join("\n", entries().stream().map(Json::text).toList()) + "\n");
        var project = project(manifest);
        var result = CompatibilityProbe.run(project, manifest, reader(project, false));
        assertEquals("succeeded", result.get("status")); assertEquals(true, result.get("propertyFidelity"));
        assertEquals(true, result.get("edgeFidelity")); assertEquals(true, result.get("partitionQualifiedLookup"));
        assertEquals(false, result.get("float32Observed"));
        assertEquals("blocked", CompatibilityProbe.run(project, manifest, reader(project, true)).get("status"));
    }
    @Test void bootstrapEmptyInventoryBlocksBeforeAnyReaderRequests() throws Exception {
        Path manifest=root.resolve("identities.ndjson");Files.writeString(manifest,"");
        var p=project(manifest);
        var failure=assertThrows(Diagnostics.Failure.class,()->CompatibilityProbe.run(p,manifest,new Extraction.Reader(){
            public GraphRecord read(IdentityEntry entry){throw new AssertionError("No bootstrap reads");}
            public long count(GraphRecord.Kind kind,GraphRecord.Value partition,String label){throw new AssertionError("No bootstrap counts");}
        }));
        assertEquals("SOURCE_INVENTORY_REQUIRED",Diagnostics.failure(failure,"execution").get("code"));
    }
    @Test void ownedReconciledEmptyInventoryReportsNotEstablishedRatherThanFalseCapabilities() throws Exception {
        Path manifest=root.resolve("identities.ndjson");Files.writeString(manifest,"");Files.createDirectory(root.resolve("artifacts"));
        var p=project(manifest);
        Path receipt=root.resolve("artifacts/adopted-inventory-fixture.json");
        SafeFiles.atomic(receipt,Map.of("runId",p.runId(),"inputHash",p.inputHash(),"identityManifest","identities.ndjson",
            "sha256",SafeFiles.fileHash(manifest),"coverage","live-global-and-partition-counts-reconciled","vertices",0,"edges",0));
        p=new Project(p.schemaVersion(),p.migrationKind(),p.baselineRepositorySha(),p.runId(),p.protocolVersion(),p.workerVersion(),
            p.mapping(),p.source(),p.target(),p.limits(),p.consistency(),p.phases(),p.approvals(),p.applicationHashes(),
            List.of(new Project.Artifact("artifacts/adopted-inventory-fixture.json",SafeFiles.fileHash(receipt),p.runId(),true)));
        var report=CompatibilityProbe.run(p,manifest,new Extraction.Reader(){
            public GraphRecord read(IdentityEntry entry){throw new AssertionError();}
            public long count(GraphRecord.Kind kind,GraphRecord.Value partition,String label){return 0;}
        });
        assertEquals("SOURCE_EMPTY_VERIFIED",report.get("code"));assertEquals("not-established",report.get("fidelity"));
        assertEquals(true,report.get("emptySourceVerified"));assertFalse(report.containsKey("typedValuesPreserved"));
        assertFalse(report.containsKey("propertyFidelity"));assertEquals("blocked",report.get("status"));
    }
}
