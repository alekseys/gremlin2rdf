package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.io.IOException;

final class PopulationScripts {
    record Script(String text, Map<String, Object> bindings) {}
    static Map<String, Object> bindings(Project project, GraphRecord record) throws Exception {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("g2rId", record.identity().id().lexical());
        bindings.put("g2rPartition", SourceCodec.scalar(Json.MAPPER.valueToTree(record.identity().partition())));
        bindings.put("g2rOwnerKey", PopulationPlan.OWNER_KEY); bindings.put("g2rRun", project.runId());
        if (record.kind() == GraphRecord.Kind.edge) bindings.put("g2rOwnerId", record.source().id().lexical());
        return bindings;
    }
    static String selection(GraphRecord record) {
        return record.kind() == GraphRecord.Kind.vertex ? "g.V([g2rPartition,g2rId])"
            : "g.V([g2rPartition,g2rOwnerId]).outE().hasId(g2rId)";
    }
    static Script create(Project project, GraphRecord record) throws Exception {
        PopulationPlan.validateRecord(project, record);
        var bindings = bindings(project, record);
        bindings.put("g2rLabel", record.label());
        StringBuilder script = new StringBuilder();
        if (record.kind() == GraphRecord.Kind.vertex) script.append("g.addV(g2rLabel).property(T.id,g2rId)");
        else {
            bindings.put("g2rDestinationId", record.destination().id().lexical());
            bindings.put("g2rDestinationPartition", SourceCodec.scalar(Json.MAPPER.valueToTree(record.destination().partition())));
            script.append("g.V([g2rPartition,g2rOwnerId]).has(g2rOwnerKey,g2rRun).as('g2rFrom')")
                .append(".V([g2rDestinationPartition,g2rDestinationId]).has(g2rOwnerKey,g2rRun)")
                .append(".addE(g2rLabel).from('g2rFrom').property(T.id,g2rId)");
        }
        var properties = new ArrayList<>(record.properties());
        String partitionKey = project.source().partitionKeyPath().substring(1);
        properties.sort(Comparator.comparingInt(property -> property.key().equals(partitionKey) ? 0 : property.key().equals(PopulationPlan.OWNER_KEY) ? 1 : 2));
        int i = 0;
        for (var property : properties) {
            String stem = "g2rProperty" + i++;
            bindings.put(stem + "Key", property.key()); bindings.put(stem + "Value", SourceCodec.scalar(Json.MAPPER.valueToTree(property.value())));
            script.append(".property(");
            if (record.kind() == GraphRecord.Kind.vertex) script.append("VertexProperty.Cardinality.list,");
            script.append(stem).append("Key,").append(stem).append("Value");
            if (record.kind() == GraphRecord.Kind.vertex) {
                bindings.put(stem + "Id", property.id().lexical());
                script.append(",T.id,").append(stem).append("Id");
                int meta = 0;
                for (var entry : new TreeMap<>(property.metaProperties()).entrySet()) {
                    String key = stem + "Meta" + meta++;
                    bindings.put(key + "Key", entry.getKey()); bindings.put(key + "Value", SourceCodec.scalar(Json.MAPPER.valueToTree(entry.getValue())));
                    script.append(",").append(key).append("Key,").append(key).append("Value");
                }
            }
            script.append(")");
        }
        return new Script(script.append(".count()").toString(), Map.copyOf(bindings));
    }
    static Script delete(Project project, GraphRecord record, String etag) throws Exception {
        if (etag == null || etag.isEmpty()) throw new IllegalArgumentException("Observed source ETag required for deletion");
        String script = "g.withStrategies(ProjectionStrategy.build().IncludeSystemProperties('_etag').create())"
            + selection(record).substring(1) + ".has(g2rOwnerKey,g2rRun).has('_etag',g2rEtag)";
        if (record.kind() == GraphRecord.Kind.vertex) script += ".not(bothE())";
        var bindings = bindings(project, record); bindings.put("g2rEtag", etag);
        return new Script(script + ".drop().count()", bindings);
    }
    static Map<String, Object> export(Project project, Path root, PopulationPlan plan, String relativeOutput) throws Exception {
        plan.verify(project, root);
        Path output = SafeFiles.resolve(root, relativeOutput);
        if (Files.exists(output)) throw new IOException("New source recipe artifact required");
        long records = 0;
        try (var reader = new RecordReader(SafeFiles.resolve(root, plan.recordsPath()), project.limits().maxRecordBytes(), 0);
             var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            GraphRecord record;
            while ((record = reader.next()) != null) {
                var recipe = create(project, record);
                writer.write(Json.text(Map.of("schemaVersion", 1, "identity", record.identity(), "kind", record.kind(),
                    "canonicalRecordHash", Json.hash(record), "script", recipe.text(), "bindings", recipe.bindings())) + "\n");
                records++;
            }
        }
        try (var channel = FileChannel.open(output, StandardOpenOption.WRITE)) { channel.force(true); }
        if (records != plan.vertices() + plan.edges()) throw new IOException("Recipe inventory differs from canonical plan");
        plan.verify(project, root);
        return Map.of("status", "succeeded", "records", records, "sha256", SafeFiles.fileHash(output), "planHash", plan.hash(),
            "sourceMutationsExecuted", false, "sensitiveArtifact", true);
    }
}
