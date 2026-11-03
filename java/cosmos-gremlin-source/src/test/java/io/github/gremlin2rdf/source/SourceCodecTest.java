package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceCodecTest {
    private static final String HASH = "0".repeat(64);
    private static Project project(String fidelity) {
        var mapping = new Mapping(1, "urn:test:", "urn:domain:", Map.of(), Map.of(), Map.of(), Set.of(), true, true, fidelity);
        var source = new Project.Source("fixture", "localhost", 443, "database", "container", "/pk", null,
            "verified-identity-manifest", "identities.ndjson", HASH, null, null, null, true);
        return new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "fixture", 1, "0.1.0", mapping, source, null,
            Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
    }
    private static IdentityEntry vertex() { return new IdentityEntry(1, GraphRecord.Kind.vertex, GraphRecord.Value.string("p"), GraphRecord.Value.integer(1), null, null); }
    private static Map<String, Object> row(List<?> properties) { return Map.of("id", 1L, "partition", "p", "label", "person", "properties", properties); }
    private static Map<String, Object> property(String id) { return Map.of("key", "tag", "id", id, "value", "same", "meta", Map.of("verified", true)); }
    @Test void preservesTypedIdentitiesMultiplicityMetaAndCanonicalOrder() {
        var codec = new SourceCodec(project("strict"));
        var a = codec.normalize(vertex(), row(List.of(property("b"), property("a"))), 2, HASH);
        var b = codec.normalize(vertex(), row(List.of(property("a"), property("b"))), 2, HASH);
        assertEquals(Json.text(a), Json.text(b)); assertTrue(a.complete()); assertEquals(2, a.properties().size());
        assertEquals(GraphRecord.ValueType.integer, a.identity().id().type());
        assertEquals(GraphRecord.ValueType.booleanValue, a.properties().getFirst().metaProperties().get("verified").type());
    }
    @Test void rejectsMissingFidelityDuplicateIdentityAndChangedCounts() {
        var codec = new SourceCodec(project("strict"));
        assertThrows(IllegalArgumentException.class, () -> codec.normalize(vertex(), row(List.of(Map.of("key", "tag", "value", "x"))), 1, HASH));
        assertThrows(IllegalArgumentException.class, () -> codec.normalize(vertex(), row(List.of(property("a"), property("a"))), 2, HASH));
        assertThrows(IllegalArgumentException.class, () -> codec.normalize(vertex(), row(List.of(property("a"))), 2, HASH));
        var wrong = new HashMap<>(row(List.of())); wrong.put("id", "1");
        assertThrows(IllegalArgumentException.class, () -> codec.normalize(vertex(), wrong, 0, HASH));
    }
    @Test void reducedObservationsRemainExplicitlyNonComplete() {
        var record = new SourceCodec(project("reduced")).normalize(vertex(), row(List.of(Map.of("key", "tag", "value", "x"))), 1, HASH);
        assertFalse(record.complete()); assertNull(record.properties().getFirst().id());
        assertEquals(GraphRecord.Fidelity.reduced, record.provenance().fidelity());
        assertThrows(SecurityException.class, () -> SourceCompatibility.authorizeMode(project("reduced")));
    }
    @Test void rejectsUnsupportedNullAndNonfiniteWithoutCoercion() throws Exception {
        for (Object value : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, List.of("x"), Map.of("x", "y")))
            assertThrows(IllegalArgumentException.class, () -> SourceCodec.value(value));
        assertThrows(Exception.class, () -> SourceCodec.scalar(Json.MAPPER.readTree("{\"type\":\"booleanValue\",\"lexical\":\"perhaps\"}")));
        assertThrows(IllegalArgumentException.class, () -> SourceCodec.count(1.0));
        assertThrows(IllegalArgumentException.class, () -> SourceCodec.count(-1));
        assertEquals("9223372036854775808", SourceCodec.value(new java.math.BigInteger("9223372036854775808")).lexical());
        assertEquals("-0.0", SourceCodec.value(-0.0d).lexical());
        assertThrows(IllegalArgumentException.class, () -> new GraphRecord.Value(GraphRecord.ValueType.doubleValue, "0x1.0p0"));
        assertThrows(IllegalArgumentException.class, () -> new GraphRecord.Value(GraphRecord.ValueType.floatValue, " 1.0 "));
    }
    @Test void requiresVerifiedEdgeScopeAndOwner() {
        assertThrows(IllegalArgumentException.class, () -> new IdentityEntry(1, GraphRecord.Kind.edge, GraphRecord.Value.string("p"), GraphRecord.Value.string("e"), null, null));
        var entry = new IdentityEntry(1, GraphRecord.Kind.edge, GraphRecord.Value.string("p"), GraphRecord.Value.string("e"), GraphRecord.Value.integer(1), GraphRecord.EdgeScope.sourceVertex);
        var edge = new HashMap<String, Object>(Map.of("id", "e", "partition", "p", "label", "knows", "properties", List.of(),
            "source", Map.of("id", 1L, "partition", "p"), "destination", Map.of("id", "1", "partition", "other")));
        var record = new SourceCodec(project("strict")).normalize(entry, edge, 0, HASH);
        assertEquals(GraphRecord.EdgeScope.sourceVertex, record.edgeScope()); assertNotEquals(record.source(), record.destination());
        edge.put("source", Map.of("id", 2L, "partition", "p"));
        assertThrows(IllegalArgumentException.class, () -> new SourceCodec(project("strict")).normalize(entry, edge, 0, HASH));
    }
    @Test void preservesObservableMetadataAndNumericPartitionTypesWithoutInventingPhysicalShards() throws Exception {
        var partition = new GraphRecord.Value(GraphRecord.ValueType.doubleValue, "1.5");
        var entry = new IdentityEntry(1, GraphRecord.Kind.vertex, partition, GraphRecord.Value.string("vertex"), null, null);
        var row = new HashMap<String, Object>(Map.of("id", "vertex", "partition", 1.5d, "label", "person", "properties", List.of(),
            "metadata", Map.of("_etag", "opaque", "_ts", 123L)));
        var record = new SourceCodec(project("strict")).normalize(entry, row, 0, HASH);
        assertEquals(partition, record.identity().partition()); assertEquals("opaque", record.metadata().get("_etag").lexical());
        assertTrue(record.provenance().shard().startsWith("identity-manifest:"));
        assertEquals(1.5d, SourceCodec.scalar(Json.MAPPER.valueToTree(partition)));
        row.put("metadata", Map.of("unsupported", List.of("x")));
        assertThrows(IllegalArgumentException.class, () -> new SourceCodec(project("strict")).normalize(entry, row, 0, HASH));
    }
}
