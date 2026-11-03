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
        var error=assertThrows(Diagnostics.Failure.class, () -> SourceCompatibility.read(base(), root));
        assertEquals("SOURCE_COMPATIBILITY_NOT_CURRENT",error.getMessage());
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
        var error=assertThrows(Diagnostics.Failure.class, () -> SourceCompatibility.read(stale, root));
        assertEquals("source-compatibility-input",Diagnostics.failure(error,"source-operation").get("stage"));
    }
    private Project refreshHash(Project project) throws Exception {
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(project);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("artifacts").get(0)).put("sha256",SafeFiles.fileHash(root.resolve("compatibility.json")));
        return Json.MAPPER.treeToValue(tree,Project.class);
    }
    @Test void classifiesMissingCorruptAndWrongRunEvidenceWithoutChangingOwnedFiles() throws Exception {
        var current=evidence(base(),true,null);
        var file=root.resolve("compatibility.json");
        var valid=Files.readString(file);
        Files.delete(file);
        assertEquals("source-compatibility-file",Diagnostics.failure(assertThrows(Diagnostics.Failure.class,
            ()->SourceCompatibility.read(current,root)),"source-operation").get("stage"));
        Files.writeString(file,"[private-malformed-value");
        var corrupt=refreshHash(current);
        var error=assertThrows(Diagnostics.Failure.class,()->SourceCompatibility.read(corrupt,root));
        assertEquals("SOURCE_EVIDENCE_CONTENT",error.getMessage());
        assertFalse(Json.text(Diagnostics.failure(error,"source-operation")).contains("private-"));
        assertEquals("[private-malformed-value",Files.readString(file));
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(valid);
        tree.put("runId","prior-run");Files.writeString(file,Json.text(tree));
        var prior=refreshHash(current);
        assertEquals("source-compatibility-run-input",Diagnostics.failure(assertThrows(Diagnostics.Failure.class,
            ()->SourceCompatibility.read(prior,root)),"source-operation").get("stage"));
        tree.put("runId",current.runId());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("result")).put("serializer","GraphSONv3");
        Files.writeString(file,Json.text(tree));
        var incompatible=refreshHash(current);
        assertEquals("source-compatibility-driver-serializer",Diagnostics.failure(assertThrows(Diagnostics.Failure.class,
            ()->SourceCompatibility.read(incompatible,root)),"source-operation").get("stage"));
    }
    private Project documentProject(boolean reduced) throws Exception {
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(base());
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("source");
        source.put("sourceMode","cosmos-documents");
        source.put("documentEndpoint","https://fixture.documents.azure.com/");
        source.put("documentProfile","property-bags-v1");
        if (reduced) ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("mapping")).put("fidelity","reduced");
        return Json.MAPPER.treeToValue(tree,Project.class);
    }
    private Project documentEvidence(Project project,String fidelity,List<String> unobservable) throws Exception {
        Files.writeString(root.resolve("canonical.ndjson"),"private-fixture-canonical\n");
        var result=Map.ofEntries(
            Map.entry("status","succeeded"),Map.entry("adapter","cosmos-dotnet-documents"),Map.entry("sdkVersion","3.58.0"),
            Map.entry("storageProfile","property-bags-v1"),Map.entry("sourceManifestHash",project.source().identityManifestHash()),
            Map.entry("documentFidelity",fidelity),Map.entry("gremlinSemanticCompatibility","not-established"),
            Map.entry("sourceCountsReconciled",true),Map.entry("observedVertices",1),Map.entry("observedEdges",0),
            Map.entry("unobservable",unobservable),Map.entry("rawSnapshotHash","0".repeat(64)),
            Map.entry("canonicalPath","canonical.ndjson"),Map.entry("canonicalSha256",SafeFiles.fileHash(root.resolve("canonical.ndjson"))));
        Files.writeString(root.resolve("compatibility.json"),Json.text(Map.of(
            "schemaVersion",1,"runId",project.runId(),"inputHash",project.inputHash(),"operation","source-compatibility",
            "status","succeeded","result",result)));
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(project);
        tree.set("phases",Json.MAPPER.valueToTree(Map.of("source-compatibility",
            new Project.Phase("succeeded",List.of(),List.of("compatibility.json"),project.inputHash()))));
        tree.set("artifacts",Json.MAPPER.valueToTree(List.of(
            new Project.Artifact("compatibility.json",SafeFiles.fileHash(root.resolve("compatibility.json")),project.runId(),true))));
        return Json.MAPPER.treeToValue(tree,Project.class);
    }
    @Test void documentProofIsModeBoundAndCannotClaimStrictPassWithUnobservablesOrGremlinEquivalence() throws Exception {
        var strict=documentProject(false);
        assertThrows(Diagnostics.Failure.class,()->SourceMain.requireGremlinMode(strict));
        var legacy=base();
        SourceMain.requireGremlinMode(legacy);
        assertFalse(Json.text(legacy.source()).contains("sourceMode"));
        assertFalse(Json.text(legacy.source()).contains("documentEndpoint"));
        assertNotEquals(legacy.inputHash(),strict.inputHash());
        var complete=documentEvidence(strict,"complete",List.of());
        var report=SourceCompatibility.read(complete,root);
        assertEquals("not-established",report.path("gremlinSemanticCompatibility").asText());
        assertFalse(report.has("driverVersion"));
        var falseComplete=documentEvidence(strict,"complete",List.of("numeric-runtime-type"));
        assertThrows(Diagnostics.Failure.class,()->SourceCompatibility.read(falseComplete,root));
        var partialStrict=documentEvidence(strict,"reduced",List.of("property-occurrence-id"));
        assertThrows(Diagnostics.Failure.class,()->SourceCompatibility.read(partialStrict,root));
        var reduced=documentEvidence(documentProject(true),"reduced",List.of("property-occurrence-id"));
        assertEquals("reduced",SourceCompatibility.read(reduced,root).path("documentFidelity").asText());
        assertThrows(SecurityException.class,()->SourceCompatibility.authorizeMode(reduced));
        Files.writeString(root.resolve("canonical.ndjson"),"changed-private-value");
        var failure=assertThrows(Diagnostics.Failure.class,()->SourceCompatibility.read(reduced,root));
        assertEquals("source-document-canonical",Diagnostics.failure(failure,"source-operation").get("stage"));
    }
}
