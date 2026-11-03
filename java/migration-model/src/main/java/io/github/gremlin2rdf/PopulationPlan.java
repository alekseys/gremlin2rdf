package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public record PopulationPlan(int schemaVersion, String runId, String inputHash, String containerIdentity,
                             String runPrefix, String recordsPath, String recordsSha256, long vertices, long edges,
                             long estimatedWrites, long maximumWrites, String ruBudget) {
    public static final String OWNER_KEY = "__gremlin2rdf_run";
    public static final class UnsupportedValue extends IllegalArgumentException {
        public UnsupportedValue(ArithmeticException cause) { super("Source population supports signed int64, not arbitrary-precision integers", cause); }
    }
    public String hash() { return Json.hash(this); }
    public static String prefix(Project project) { return "g2r-" + project.runId() + "-"; }
    public static String container(Project project) {
        var source = Objects.requireNonNull(project.source());
        return Json.text(List.of(source.host(), source.port(), source.database(), source.container(), source.scope()));
    }
    public List<String> resources() {
        return List.of(containerIdentity, runPrefix, recordsSha256, hash(), "exclusive-pre-existing-test-container");
    }
    public static PopulationPlan create(Project project, Path root, String relativeRecords, long maximumWrites) throws IOException {
        if (project.source() == null || !project.source().syntheticTestContainer() || project.source().ruBudget() == null
            || !project.consistency().equals("quiesced") || !project.mapping().fidelity().equals("strict") || maximumWrites < 1)
            throw new SecurityException("Dedicated pre-existing quiesced test container, RU budget and write limit required");
        Path records = SafeFiles.resolve(root, relativeRecords);
        long vertices = 0, edges = 0, writes = 0; boolean sawEdge = false; String previous = "";
        String before = SafeFiles.fileHash(records);
        try (var reader = new RecordReader(records, project.limits().maxRecordBytes(), 0)) {
            GraphRecord record;
            while ((record = reader.next()) != null) {
                validateRecord(project, record);
                var entry = new IdentityEntry(1, record.kind(), record.identity().partition(), record.identity().id(),
                    record.kind() == GraphRecord.Kind.edge ? record.source().id() : null, record.edgeScope());
                if (entry.orderingKey().compareTo(previous) <= 0) throw new IOException("Unique sorted population identities required");
                previous = entry.orderingKey();
                if (record.kind() == GraphRecord.Kind.vertex) {
                    if (sawEdge) throw new IOException("Population records must place vertices before edges");
                    vertices++;
                } else { edges++; sawEdge = true; }
                writes = Math.addExact(writes, 2L + record.properties().size());
                for (var property : record.properties()) writes = Math.addExact(writes, property.metaProperties().size());
                if (writes > maximumWrites) throw new IOException("Estimated source writes exceed explicitly configured limit");
            }
        }
        if (vertices == 0 || !before.equals(SafeFiles.fileHash(records))) throw new IOException("Empty or changing population fixture");
        return new PopulationPlan(1, project.runId(), project.inputHash(), container(project), prefix(project), relativeRecords,
            before, vertices, edges, writes, maximumWrites, project.source().ruBudget());
    }
    public void verify(Project project, Path root) throws IOException {
        if (!equals(create(project, root, recordsPath, maximumWrites))) throw new IOException("Changed, invalid or unowned population plan");
    }
    public static void validateRecord(Project project, GraphRecord record) {
        if (!record.identity().scope().equals(project.source().scope()) || !record.complete() || !record.metadata().isEmpty())
            throw new IllegalArgumentException("Complete source-scoped fixture required; system metadata cannot be populated");
        validateIdentity(project, record.identity());
        if (record.kind() == GraphRecord.Kind.edge) {
            validateIdentity(project, record.source()); validateIdentity(project, record.destination());
            if (record.edgeScope() != GraphRecord.EdgeScope.sourcePartition)
                throw new IllegalArgumentException("Population supports source-partition edge identities only");
        }
        var keys = new HashSet<String>(); var propertyIds = new HashSet<GraphRecord.Value>(); int owners = 0, partitions = 0;
        String partitionKey = project.source().partitionKeyPath().substring(1);
        for (var property : record.properties()) {
            if (Set.of("id", "label", "_ts", "_self", "_attachments", "_rid", "_etag", "inVPartition", "outVPartition").contains(property.key()))
                throw new IllegalArgumentException("Reserved source property or system metadata key");
            nativeValue(property.value());
            if (property.key().equals(OWNER_KEY)) {
                owners++;
                if (!property.value().equals(GraphRecord.Value.string(project.runId()))) throw new IllegalArgumentException("Wrong fixture owner");
            }
            if (record.kind() == GraphRecord.Kind.vertex) {
                if (property.id() == null || property.id().type() != GraphRecord.ValueType.string
                    || !property.id().lexical().startsWith(prefix(project)) || !propertyIds.add(property.id()))
                    throw new IllegalArgumentException("Unique explicit prefixed vertex-property IDs required");
                property.metaProperties().values().forEach(PopulationPlan::nativeValue);
                if (property.key().equals(partitionKey)) {
                    partitions++;
                    if (!property.value().equals(record.identity().partition())) throw new IllegalArgumentException("Partition property differs from identity");
                }
            } else if (!keys.add(property.key()) || property.id() != null || !property.metaProperties().isEmpty())
                throw new IllegalArgumentException("Edge property IDs, meta-properties and duplicate keys are unsupported");
        }
        if (owners != 1 || record.kind() == GraphRecord.Kind.vertex && partitions != 1)
            throw new IllegalArgumentException("Exactly one recorded owner and vertex partition property required");
    }
    private static void validateIdentity(Project project, GraphRecord.Identity identity) {
        if (identity == null || !identity.scope().equals(project.source().scope()) || identity.id().type() != GraphRecord.ValueType.string
            || !identity.id().lexical().startsWith(prefix(project))) throw new IllegalArgumentException("Prefixed source string identity required");
        nativeValue(identity.partition());
    }
    public static void nativeValue(GraphRecord.Value value) {
        if (value.type() == GraphRecord.ValueType.integer) {
            try { new java.math.BigInteger(value.lexical()).longValueExact(); }
            catch (ArithmeticException e) { throw new UnsupportedValue(e); }
        }
    }
}
