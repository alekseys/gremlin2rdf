package io.github.gremlin2rdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class SourceCompatibility {
    private SourceCompatibility() {}
    public static JsonNode read(Project project, Path root) throws IOException {
        if (project.source() == null || project.source().compatibilityEvidence() == null)
            throw new IOException("Live source compatibility evidence required");
        String reference = project.source().compatibilityEvidence();
        var phase = project.phases().get("source-compatibility");
        var artifacts = project.artifacts().stream().filter(a -> a.relativePath().equals(reference)
            && a.ownerRunId().equals(project.runId())).toList();
        if (artifacts.size() != 1 || phase == null || !phase.status().equals("succeeded")
            || !project.inputHash().equals(phase.inputHash()) || !phase.evidence().contains(reference))
            throw new IOException("Current owned source compatibility evidence required");
        Path file = SafeFiles.resolve(root, reference);
        if (!Files.isRegularFile(file) || Files.size(file) > 1048576 || !SafeFiles.fileHash(file).equals(artifacts.getFirst().sha256()))
            throw new IOException("Source compatibility evidence checksum or bounds failed");
        JsonNode envelope = Json.MAPPER.readTree(Files.readAllBytes(file)), result = envelope.path("result");
        if (envelope.path("schemaVersion").asInt() != 1 || !envelope.path("runId").asText().equals(project.runId())
            || !envelope.path("inputHash").asText().equals(project.inputHash())
            || !envelope.path("operation").asText().equals("source-compatibility")
            || !envelope.path("status").asText().equals("succeeded")
            || !result.path("status").asText().equals("succeeded")
            || !result.path("sourceManifestHash").asText().equals(project.source().identityManifestHash())
            || !result.path("driverVersion").asText().equals("3.4.13")
            || !result.path("serializer").asText().equals("GraphSONMessageSerializerV2d0")
            || !yes(result, "liveEndpointObserved") || !yes(result, "tlsHostnameVerified")
            || !yes(result, "partitionQualifiedLookup") || !yes(result, "typedValuesPreserved") || !yes(result, "edgeFidelity")
            || !yes(result, "sourceCountsReconciled") || !yes(result, "stableRepeatedReads")
            || !yes(result, "systemMetadataFidelity")
            || project.mapping().fidelity().equals("strict") && !yes(result, "propertyFidelity"))
            throw new IOException("Source compatibility observations do not establish requested fidelity");
        if (!result.path("edgeScopes").isArray() || result.path("edgeScopes").isEmpty())
            throw new IOException("Observed edge identity scopes required");
        for (var scope : result.path("edgeScopes")) GraphRecord.EdgeScope.valueOf(scope.asText());
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
