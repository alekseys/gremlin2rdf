package io.github.gremlin2rdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class SourceCompatibility {
    private SourceCompatibility() {}
    public static JsonNode read(Project project, Path root) throws IOException {
        if (project.source() == null || project.source().compatibilityEvidence() == null)
            throw new Diagnostics.Failure("SOURCE_COMPATIBILITY_NOT_CURRENT","source-compatibility-prerequisite");
        String reference = project.source().compatibilityEvidence();
        var phase = project.phases().get("source-compatibility");
        var artifacts = project.artifacts().stream().filter(a -> a.relativePath().equals(reference)
            && a.ownerRunId().equals(project.runId())).toList();
        if (artifacts.size()!=1)
            throw new Diagnostics.Failure("SOURCE_COMPATIBILITY_NOT_CURRENT","source-compatibility-ownership");
        if (phase == null || !phase.status().equals("succeeded") || !phase.evidence().contains(reference))
            throw new Diagnostics.Failure("SOURCE_COMPATIBILITY_NOT_CURRENT","source-compatibility-prerequisite");
        if (!project.inputHash().equals(phase.inputHash()))
            throw new Diagnostics.Failure("SOURCE_COMPATIBILITY_NOT_CURRENT","source-compatibility-input");
        Path file = SafeFiles.resolve(root, reference);
        if (!Files.isRegularFile(file))
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_INTEGRITY","source-compatibility-file");
        if (Files.size(file)>1048576)
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_INTEGRITY","source-compatibility-size");
        if (!SafeFiles.fileHash(file).equals(artifacts.getFirst().sha256()))
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_INTEGRITY","source-compatibility-artifact");
        JsonNode envelope;
        try { envelope=Json.MAPPER.readTree(Files.readAllBytes(file)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_CONTENT","source-compatibility-envelope");
        }
        if (envelope==null || !envelope.isObject())
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_CONTENT","source-compatibility-envelope");
        JsonNode result=envelope.path("result");
        if (envelope.path("schemaVersion").asInt() != 1)
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_CONTENT","source-compatibility-schema");
        if (!envelope.path("runId").asText().equals(project.runId()) || !envelope.path("inputHash").asText().equals(project.inputHash()))
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_CONTENT","source-compatibility-run-input");
        if ("cosmos-documents".equals(project.source().sourceMode())) {
            if (!envelope.path("operation").asText().equals("source-compatibility") ||
                !envelope.path("status").asText().equals("succeeded") ||
                !result.path("status").asText().equals("succeeded") ||
                !result.path("adapter").asText().equals("cosmos-dotnet-documents") ||
                !result.path("sdkVersion").asText().equals("3.58.0") ||
                !result.path("storageProfile").asText().equals(project.source().documentProfile()) ||
                !result.path("sourceManifestHash").asText().equals(project.source().identityManifestHash()) ||
                !yes(result,"sourceCountsReconciled") ||
                !result.path("gremlinSemanticCompatibility").asText().equals("not-established") ||
                !Set.of("complete","reduced").contains(result.path("documentFidelity").asText()) ||
                !result.path("unobservable").isArray() ||
                result.path("documentFidelity").asText().equals("complete") && !result.path("unobservable").isEmpty() ||
                !result.path("observedVertices").isIntegralNumber() || result.path("observedVertices").asLong(-1)<0 ||
                !result.path("observedEdges").isIntegralNumber() || result.path("observedEdges").asLong(-1)<0 ||
                !result.path("rawSnapshotHash").asText().matches("[a-f0-9]{64}") ||
                !result.path("canonicalSha256").asText().matches("[a-f0-9]{64}") ||
                project.mapping().fidelity().equals("strict") && !result.path("documentFidelity").asText().equals("complete"))
                throw new Diagnostics.Failure("SOURCE_EVIDENCE_CONTENT","source-document-fidelity");
            Path canonical=SafeFiles.resolve(root,result.path("canonicalPath").asText());
            if (!Files.isRegularFile(canonical) || !SafeFiles.fileHash(canonical).equals(result.path("canonicalSha256").asText()))
                throw new Diagnostics.Failure("SOURCE_EVIDENCE_INTEGRITY","source-document-canonical");
            return result;
        }
        if (!result.path("driverVersion").asText().equals("3.4.13") ||
            !result.path("serializer").asText().equals("GraphSONMessageSerializerV2d0"))
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_CONTENT","source-compatibility-driver-serializer");
        if (!result.path("sourceManifestHash").asText().equals(project.source().identityManifestHash()))
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_CONTENT","source-compatibility-inventory");
        if (
            !envelope.path("operation").asText().equals("source-compatibility")
            || !envelope.path("status").asText().equals("succeeded")
            || !result.path("status").asText().equals("succeeded")
            || !yes(result, "liveEndpointObserved") || !yes(result, "tlsHostnameVerified")
            || !yes(result, "partitionQualifiedLookup") || !yes(result, "typedValuesPreserved") || !yes(result, "edgeFidelity")
            || !yes(result, "sourceCountsReconciled") || !yes(result, "stableRepeatedReads")
            || !yes(result, "systemMetadataFidelity")
            || project.mapping().fidelity().equals("strict") && !yes(result, "propertyFidelity"))
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_CONTENT","source-compatibility-fidelity");
        if (!result.path("edgeScopes").isArray() || result.path("edgeScopes").isEmpty())
            throw new Diagnostics.Failure("SOURCE_EVIDENCE_EDGE_SCOPE","source-compatibility-edge-scopes");
        for (var scope : result.path("edgeScopes")) {
            try { GraphRecord.EdgeScope.valueOf(scope.asText()); }
            catch (IllegalArgumentException failure) { throw new Diagnostics.Failure("SOURCE_EVIDENCE_EDGE_SCOPE","source-compatibility-edge-scopes"); }
        }
        return result;
    }
    private static boolean yes(JsonNode result, String key) { return result.path(key).isBoolean() && result.path(key).booleanValue(); }
    public static boolean metadataComplete(GraphRecord record) {
        if (!record.metadata().keySet().containsAll(List.of("_ts", "_self", "_attachments", "_rid", "_etag"))) return false;
        var etag = record.metadata().get("_etag");
        var timestamp = record.metadata().get("_ts");
        if (etag.type() != GraphRecord.ValueType.string || etag.lexical().isEmpty() || timestamp.type() != GraphRecord.ValueType.integer) return false;
        for (String key : List.of("_self", "_attachments", "_rid"))
            if (record.metadata().get(key).type() != GraphRecord.ValueType.string) return false;
        return record.kind() == GraphRecord.Kind.vertex || record.destination().partition().equals(record.metadata().get("inVPartition"))
            && record.source().partition().equals(record.metadata().get("outVPartition"));
    }
    public static boolean supports(JsonNode result, GraphRecord.EdgeScope scope) {
        for (var observed : result.path("edgeScopes")) if (observed.isTextual() && observed.textValue().equals(scope.name())) return true;
        return false;
    }
    public static void authorizeMode(Project project) {
        if (project.mapping().fidelity().equals("reduced"))
            Approval.require(project, "reduced-fidelity", List.of(project.mapping().hash()));
        if (project.consistency().equals("best-effort"))
            Approval.require(project, "best-effort-source", List.of(project.source().scope(), project.source().identityManifestHash()));
    }
}
