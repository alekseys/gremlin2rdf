package io.github.gremlin2rdf;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GraphRecord(
    Kind kind, Identity identity, String label, Identity source, Identity destination,
    EdgeScope edgeScope, List<Property> properties, Map<String, Value> metadata, Provenance provenance
) {
    public enum Kind { vertex, edge }
    public enum EdgeScope { sourcePartition, sourceVertex }
    public enum Fidelity { complete, reduced }
    public enum ValueType { string, booleanValue, integer, floatValue, doubleValue }
    public record Value(ValueType type, String lexical) {
        public Value {
            Objects.requireNonNull(type); Objects.requireNonNull(lexical);
            switch (type) {
                case booleanValue -> {
                    if (!lexical.equals("true") && !lexical.equals("false")) throw new IllegalArgumentException("Invalid boolean");
                }
                case integer -> {
                    if (!new BigInteger(lexical).toString().equals(lexical)) throw new IllegalArgumentException("Noncanonical integer");
                }
                case floatValue -> {
                    if (!numericLexical(lexical) || !Float.isFinite(Float.parseFloat(lexical))) throw new IllegalArgumentException("Invalid or nonfinite float");
                }
                case doubleValue -> {
                    if (!numericLexical(lexical) || !Double.isFinite(Double.parseDouble(lexical))) throw new IllegalArgumentException("Invalid or nonfinite double");
                }
                case string -> {}
            }
        }
        public static Value string(String value) { return new Value(ValueType.string, value); }
        public static Value integer(long value) { return new Value(ValueType.integer, Long.toString(value)); }
        private static boolean numericLexical(String value) { return value.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"); }
    }
    public record Identity(String scope, Value partition, Value id) {
        public Identity {
            if (scope == null || scope.isBlank()) throw new IllegalArgumentException("Source scope required");
            Objects.requireNonNull(partition); Objects.requireNonNull(id);
        }
    }
    public record Property(String key, Value value, Value id, Map<String, Value> metaProperties, Fidelity fidelity) {
        public Property {
            if (key == null || key.isEmpty()) throw new IllegalArgumentException("Property key required");
            Objects.requireNonNull(value); Objects.requireNonNull(fidelity);
            metaProperties = Map.copyOf(Objects.requireNonNull(metaProperties));
        }
    }
    public record Provenance(String shard, String sourceManifestHash, String consistency, Fidelity fidelity) {
        public Provenance {
            Objects.requireNonNull(shard); Objects.requireNonNull(sourceManifestHash); Objects.requireNonNull(fidelity);
            if (!List.of("quiesced", "immutable", "best-effort").contains(consistency))
                throw new IllegalArgumentException("Explicit source consistency required");
        }
    }
    public GraphRecord {
        Objects.requireNonNull(kind); Objects.requireNonNull(identity); Objects.requireNonNull(provenance);
        if (label == null || label.isEmpty()) throw new IllegalArgumentException("Label required");
        properties = Objects.requireNonNull(properties).stream()
            .sorted(java.util.Comparator.comparing(Json::text)).toList();
        metadata = Map.copyOf(Objects.requireNonNull(metadata));
        if (kind == Kind.edge) {
            Objects.requireNonNull(source, "Missing source identity");
            Objects.requireNonNull(destination, "Missing destination identity");
            Objects.requireNonNull(edgeScope, "Verified edge identity scope required");
            if (!identity.scope().equals(source.scope()) || !identity.scope().equals(destination.scope()))
                throw new IllegalArgumentException("Endpoint scope mismatch");
            if (!identity.partition().equals(source.partition())) throw new IllegalArgumentException("Edge partition mismatch");
        } else if (source != null || destination != null || edgeScope != null)
            throw new IllegalArgumentException("Vertex cannot have edge endpoints");
    }

    public boolean complete() {
        return provenance.fidelity() == Fidelity.complete
            && properties.stream().allMatch(p -> p.fidelity() == Fidelity.complete && (kind == Kind.edge || p.id() != null));
    }
}
