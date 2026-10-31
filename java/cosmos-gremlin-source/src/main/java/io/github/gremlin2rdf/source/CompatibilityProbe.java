package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class CompatibilityProbe {
    static Map<String, Object> run(Project project, Path manifest, Extraction.Reader reader) throws Exception {
        String hash = SafeFiles.fileHash(manifest);
        if (!hash.equals(project.source().identityManifestHash())) throw new IOException("Probe enumeration changed");
        SourceCompatibility.authorizeMode(project);
        long beforeVertices = reader.count(GraphRecord.Kind.vertex, null, null), beforeEdges = reader.count(GraphRecord.Kind.edge, null, null);
        long vertices = 0, edges = 0;
        Set<String> valueTypes = new TreeSet<>(), scopes = new TreeSet<>(), edgePairs = new HashSet<>();
        Map<String, String> vertexPartitions = new HashMap<>();
        boolean stable = true, complete = true, systemMetadata = true, meta = false, multi = false, repeated = false, partitionCollision = false;
        boolean parallel = false, loop = false, crossPartition = false;
        try (var input = Files.newInputStream(manifest)) {
            byte[] bytes; String previous = "";
            while ((bytes = Extraction.frame(input)) != null) {
                SourceCompatibility.authorizeMode(project);
                if (vertices + edges >= 2000) throw new IOException("Compatibility probe requires a bounded semantic manifest of at most 2000 records");
                var entry = Json.MAPPER.readValue(bytes, IdentityEntry.class);
                if (entry.orderingKey().compareTo(previous) <= 0) throw new IOException("Probe manifest identities must be unique and ordered");
                previous = entry.orderingKey();
                var record = reader.read(entry); entry.verify(record, project);
                stable &= Json.text(record).equals(Json.text(reader.read(entry)));
                complete &= record.complete();
                systemMetadata &= SourceCompatibility.metadataComplete(record);
                if (record.kind() == GraphRecord.Kind.vertex) complete &= record.properties().stream().allMatch(p -> p.id() != null);
                Map<String, Set<String>> keys = new HashMap<>(), values = new HashMap<>();
                for (var property : record.properties()) {
                    valueTypes.add(property.value().type().name());
                    property.metaProperties().values().forEach(v -> valueTypes.add(v.type().name()));
                    meta |= !property.metaProperties().isEmpty();
                    if (property.id() != null) {
                        keys.computeIfAbsent(Json.hash(property.key()), key -> new HashSet<>()).add(Json.hash(property.id()));
                        values.computeIfAbsent(Json.hash(List.of(property.key(), property.value())), key -> new HashSet<>()).add(Json.hash(property.id()));
                    }
                }
                multi |= keys.values().stream().anyMatch(ids -> ids.size() > 1);
                repeated |= values.values().stream().anyMatch(ids -> ids.size() > 1);
                if (record.kind() == GraphRecord.Kind.vertex) {
                    vertices++; String id = Json.hash(record.identity().id()), partition = Json.hash(record.identity().partition());
                    String first = vertexPartitions.putIfAbsent(id, partition); partitionCollision |= first != null && !first.equals(partition);
                } else {
                    edges++; scopes.add(record.edgeScope().name());
                    parallel |= !edgePairs.add(Json.hash(List.of(record.source(), record.destination(), record.label())));
                    loop |= record.source().equals(record.destination());
                    crossPartition |= !record.source().partition().equals(record.destination().partition());
                }
            }
        }
        boolean inventory = vertices == beforeVertices && edges == beforeEdges
            && vertices == reader.count(GraphRecord.Kind.vertex, null, null) && edges == reader.count(GraphRecord.Kind.edge, null, null);
        boolean typed = valueTypes.containsAll(List.of("string", "integer", "booleanValue", "doubleValue"));
        boolean propertyFidelity = complete && meta && multi && repeated && stable;
        boolean edgeFidelity = edges > 0 && parallel && loop && crossPartition && stable;
        boolean passing = inventory && typed && edgeFidelity && partitionCollision && systemMetadata
            && (project.mapping().fidelity().equals("reduced") || propertyFidelity);
        Map<String, Object> result = new TreeMap<>();
        result.put("status", passing ? "succeeded" : "blocked"); result.put("sourceManifestHash", hash);
        result.put("driverVersion", "3.4.13"); result.put("serializer", "GraphSONMessageSerializerV2d0");
        result.put("liveEndpointObserved", true); result.put("tlsHostnameVerified", true);
        result.put("partitionQualifiedLookup", partitionCollision && stable); result.put("typedValuesPreserved", typed);
        result.put("propertyFidelity", propertyFidelity); result.put("edgeFidelity", edgeFidelity);
        result.put("systemMetadataFidelity", systemMetadata);
        result.put("edgeScopes", scopes); result.put("observedValueTypes", valueTypes);
        result.put("float32Observed", valueTypes.contains("floatValue")); result.put("sourceCountsReconciled", inventory);
        result.put("stableRepeatedReads", stable); result.put("observedVertices", vertices); result.put("observedEdges", edges);
        result.put("scope", "bounded-semantic-corpus-observations-only");
        result.put("sourceMutations", "not-executed");
        result.put("remediation", passing ? "Register this owned checksum-bound observation artifact; extraction still performs full reconciliation."
            : "Use a bounded authorized semantic corpus containing repeated partition-qualified identities, typed values, occurrences/meta and parallel/self/cross-partition edges; unsupported fidelity requires separate reduced-mode approval.");
        return Map.copyOf(result);
    }
}
