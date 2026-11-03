package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.riot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingTest {
    @TempDir Path root;
    @Test void semanticFixturePassesShaclAndPreservesOccurrences() throws Exception {
        var mapper = new RdfMapper(Mapping.standard());
        Model graph = ModelFactory.createDefaultModel();
        var profile = Synthetic.Profile.semantic(42);
        Set<String> identities = new HashSet<>();
        for (long i = 0; i < 1000; i++) {
            var record = Synthetic.record(profile, i);
            assertTrue(record.complete());
            if (record.kind() == GraphRecord.Kind.vertex) assertTrue(record.properties().stream().allMatch(p -> p.id() != null));
            assertTrue(identities.add(mapper.entity(record)));
            try (var mapped = mapper.map(record)) { graph.add(mapped.canonical()); }
        }
        var shapes = RDFParser.source(getClass().getResourceAsStream("/canonical-v1.shacl.ttl")).lang(Lang.TTL).toGraph();
        assertTrue(ShaclValidator.get().validate(shapes, graph.getGraph()).conforms());
        assertEquals(1802, graph.listResourcesWithProperty(org.apache.jena.vocabulary.RDF.type,
            graph.createResource(RdfMapper.CANONICAL + "Property")).toList().size());
        assertEquals(400, graph.listResourcesWithProperty(org.apache.jena.vocabulary.RDF.type,
            graph.createResource(RdfMapper.CANONICAL + "MetaProperty")).toList().size());
        assertFalse(graph.listSubjects().toList().stream().anyMatch(Resource::isAnon));
        graph.close();
    }
    @Test void typedAndPartitionQualifiedIdentityCannotCollide() {
        var mapper = new RdfMapper(Mapping.standard());
        var one = new GraphRecord.Identity("scope", GraphRecord.Value.string("1"), GraphRecord.Value.string("a:b"));
        var two = new GraphRecord.Identity("scope", GraphRecord.Value.integer(1), GraphRecord.Value.string("a:b"));
        assertNotEquals(mapper.vertex(one), mapper.vertex(two));
        assertNotEquals(mapper.vertex(one), mapper.vertex(new GraphRecord.Identity("scope",
            GraphRecord.Value.string("2"), one.id())));
    }
    @Test void reorderedEqualOccurrencesRemainDeterministic() {
        var record = Synthetic.record(Synthetic.Profile.semantic(2), 0);
        var properties = new ArrayList<>(record.properties()); Collections.reverse(properties);
        var reordered = new GraphRecord(record.kind(), record.identity(), record.label(), null, null, null,
            properties, record.metadata(), record.provenance());
        assertEquals(Json.text(record), Json.text(reordered));
        var mapper = new RdfMapper(Mapping.standard());
        try (var a = mapper.map(record); var b = mapper.map(reordered)) {
            assertTrue(a.canonical().isIsomorphicWith(b.canonical()));
            assertEquals(6, a.canonical().listObjectsOfProperty(RdfMapper.predicate(a.canonical(), "property")).toList().size());
        }
    }
    @Test void generatorIsByteDeterministic() throws Exception {
        var a = Synthetic.generate(Synthetic.Profile.semantic(55), root.resolve("a.ndjson"));
        var b = Synthetic.generate(Synthetic.Profile.semantic(55), root.resolve("b.ndjson"));
        assertEquals(a, b);
        assertEquals(-1, Files.mismatch(root.resolve("a.ndjson"), root.resolve("b.ndjson")));
    }
    @Test void sourceProfileIsDeterministicAndSeparatesUnsupportedValuesIntoNegativeFixtures() throws Exception {
        var source = new Project.Source("fixture", "localhost", 443, "db", "test", "/pk", null,
            "verified-identity-manifest", "identities.ndjson", "0".repeat(64), null, "10000", null, true);
        var project = new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "fixture", 1, "0.1.0",
            Mapping.standard(), source, null, Project.Limits.standard(), "quiesced", Map.of(), List.of(), Map.of(), List.of());
        var a = Synthetic.sourceFixture(project, 55, root.resolve("a.ndjson"));
        var b = Synthetic.sourceFixture(project, 55, root.resolve("b.ndjson"));
        assertEquals(a, b); assertEquals(-1, Files.mismatch(root.resolve("a.ndjson"), root.resolve("b.ndjson")));
        assertEquals(1000, a.get("records"));
        assertFalse((boolean) a.get("sourceWritesExecuted"));
        assertTrue(Files.readString(root.resolve("a.ndjson.negative.json")).contains("9223372036854775808"));
        var plan = PopulationPlan.create(project, root, "a.ndjson", 100000);
        assertEquals(200, plan.vertices()); assertEquals(800, plan.edges());
        assertThrows(java.io.IOException.class, () -> PopulationPlan.create(project, root, "a.ndjson", 1));
    }
    @Test void unknownAndInexactValuesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new GraphRecord.Value(GraphRecord.ValueType.integer, "01"));
        assertThrows(IllegalArgumentException.class, () -> new GraphRecord.Value(GraphRecord.ValueType.doubleValue, "NaN"));
        assertThrows(IllegalArgumentException.class, () -> new GraphRecord.Value(GraphRecord.ValueType.booleanValue, "0"));
        assertThrows(IllegalArgumentException.class, () -> new Mapping(1, "urn:x:", "urn:y:",
            Map.of("Edge", RdfMapper.CANONICAL + "Edge"), Map.of(), Map.of(), Set.of(), true, true, "strict"));
    }
}
