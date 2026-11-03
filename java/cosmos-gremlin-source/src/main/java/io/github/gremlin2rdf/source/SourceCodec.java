package io.github.gremlin2rdf.source;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gremlin2rdf.*;
import java.math.BigInteger;
import java.util.*;

final class SourceCodec {
    private final Project project;
    SourceCodec(Project project) {
        this.project = project;
        Objects.requireNonNull(project.source(), "Explicit source configuration required");
    }
    static GraphRecord.Value value(Object value) {
        if (value instanceof String s) return GraphRecord.Value.string(s);
        if (value instanceof Boolean b) return new GraphRecord.Value(GraphRecord.ValueType.booleanValue, b.toString());
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger)
            return new GraphRecord.Value(GraphRecord.ValueType.integer, value.toString());
        if (value instanceof Float f && Float.isFinite(f)) return new GraphRecord.Value(GraphRecord.ValueType.floatValue, f.toString());
        if (value instanceof Double d && Double.isFinite(d)) return new GraphRecord.Value(GraphRecord.ValueType.doubleValue, d.toString());
        throw new IllegalArgumentException("Unsupported or unobservable source value type");
    }
    static Object scalar(JsonNode typed) throws java.io.IOException {
        GraphRecord.Value value = Json.MAPPER.treeToValue(typed, GraphRecord.Value.class);
        return switch (value.type()) {
            case string -> value.lexical();
            case integer -> {
                var integer = new BigInteger(value.lexical());
                yield integer.bitLength() <= 63 ? integer.longValueExact() : integer;
            }
            case booleanValue -> Boolean.valueOf(value.lexical());
            case floatValue -> Float.valueOf(value.lexical());
            case doubleValue -> Double.valueOf(value.lexical());
        };
    }
    static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Required source object is unobservable");
        return map;
    }
    static long count(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger))
            throw new IllegalArgumentException("Source count must be an exact integer");
        long count = new BigInteger(value.toString()).longValueExact();
        if (count < 0) throw new IllegalArgumentException("Negative source count"); return count;
    }
    private GraphRecord.Identity identity(Object id, Object partition) {
        return new GraphRecord.Identity(project.source().scope(), value(partition), value(id));
    }
    GraphRecord normalize(IdentityEntry entry, Map<?, ?> row, long observedPropertyCount, String manifestHash) {
        if (!value(row.get("id")).equals(entry.id()) || !value(row.get("partition")).equals(entry.partition()))
            throw new IllegalArgumentException("Partition-qualified lookup did not preserve requested typed identity");
        if (!(row.get("label") instanceof String label)) throw new IllegalArgumentException("Required label is unobservable");
        if (!(row.get("properties") instanceof List<?> properties) || properties.size() > 10000 || observedPropertyCount != properties.size())
            throw new IllegalArgumentException("Property observation count changed or exceeds the bounded frame");
        boolean vertex = entry.kind() == GraphRecord.Kind.vertex, reduced = project.mapping().fidelity().equals("reduced");
        List<GraphRecord.Property> normalized = new ArrayList<>();
        Set<String> occurrences = new HashSet<>();
        boolean excluded = false;
        for (Object property : properties) {
            var item = object(property);
            if (!(item.get("key") instanceof String key)) throw new IllegalArgumentException("Required property key is unobservable");
            if (project.mapping().exclusions().contains(key)) { excluded = true; continue; }
            boolean complete = !vertex || item.get("id") != null && item.get("meta") instanceof Map<?, ?>;
            if (!complete && !reduced) throw new IllegalArgumentException("Required occurrence identity or meta-properties are unobservable");
            String occurrence = Json.text(List.of(key, vertex && item.get("id") != null ? value(item.get("id")) : ""));
            if ((!vertex || item.get("id") != null) && !occurrences.add(occurrence))
                throw new IllegalArgumentException("Duplicate source property occurrence identity");
            Map<String, GraphRecord.Value> meta = new TreeMap<>();
            if (vertex && item.get("meta") != null) object(item.get("meta")).forEach((name, value) -> {
                if (!(name instanceof String s)) throw new IllegalArgumentException("Unsupported meta-property key type");
                meta.put(s, value(value));
            });
            normalized.add(new GraphRecord.Property(key, value(item.get("value")), vertex && item.get("id") != null ? value(item.get("id")) : null,
                meta, complete ? GraphRecord.Fidelity.complete : GraphRecord.Fidelity.reduced));
        }
        GraphRecord.Identity source = null, destination = null;
        if (!vertex) {
            var out = object(row.get("source")); var in = object(row.get("destination"));
            source = identity(out.get("id"), out.get("partition")); destination = identity(in.get("id"), in.get("partition"));
            if (!source.id().equals(entry.ownerId())) throw new IllegalArgumentException("Edge owner differs from manifest identity");
        }
        var fidelity = !excluded && normalized.stream().allMatch(p -> p.fidelity() == GraphRecord.Fidelity.complete)
            ? GraphRecord.Fidelity.complete : GraphRecord.Fidelity.reduced;
        Map<String, GraphRecord.Value> metadata = new TreeMap<>();
        if (row.containsKey("metadata")) object(row.get("metadata")).forEach((key, value) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("Unsupported source metadata key type");
            metadata.put(name, value(value));
        });
        return new GraphRecord(entry.kind(), identity(row.get("id"), row.get("partition")), label, source, destination, entry.edgeScope(),
            normalized, metadata, new GraphRecord.Provenance("identity-manifest:" + Json.hash(entry.partition()), manifestHash, project.consistency(), fidelity));
    }
}
