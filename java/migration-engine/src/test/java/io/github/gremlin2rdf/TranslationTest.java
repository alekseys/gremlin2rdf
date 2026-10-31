package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.apache.jena.query.*;
import org.junit.jupiter.api.Test;

class TranslationTest {
    @Test void traversalsPreserveParallelEdgesAndPropertyMultiplicity() {
        var dataset = DatasetFactory.createTxnMem(); var model = dataset.getNamedModel("urn:fixture");
        var profile = Synthetic.Profile.semantic(42); var mapper = new RdfMapper(Mapping.standard());
        for (int i = 0; i < 1000; i++) {
            try (var mapped = mapper.map(Synthetic.record(profile, i))) { model.add(mapped.canonical()); }
        }
        Map<String, Long> queries = new LinkedHashMap<>();
        queries.put("g.V().count()", 200L);
        queries.put("g.E().count()", 800L);
        queries.put("g.V().values('tag').count()", 400L);
        queries.put("g.V().values('tag').dedup().count()", 1L);
        queries.put("g.V().out().count()", 800L);
        queries.put("g.V().both().count()", 1600L);
        queries.put("g.E().bothV().count()", 1600L);
        queries.put("g.V().has('active',false).count()", 100L);
        queries.put("g.V().has('tag','duplicate').count()", 200L);
        queries.put("g.V().has('missing').count()", 0L);
        queries.put("g.V().has('number',P.gt(199)).count()", 12L);
        queries.put("g.V(['partition-0','vertex-0']).outE().inV().count()", 6L);
        for (var entry : queries.entrySet()) {
            var translation = new Translator("urn:fixture", Map.of()).translate(entry.getKey());
            try (var execution = QueryExecutionFactory.create(translation.sparql(), dataset)) {
                assertEquals(entry.getValue().longValue(), execution.execSelect().next().getLiteral("count").getLong(), entry.getKey());
            }
        }
        dataset.close();
    }
    @Test void scalarOrderingAndRangesHaveSequenceShape() {
        var translation = new Translator("urn:fixture", Map.of()).translate("g.V().values('number').order().by(desc).range(2,7)");
        var query = QueryFactory.create(translation.sparql());
        assertEquals(2, query.getOffset()); assertEquals(5, query.getLimit()); assertEquals("sequence", translation.comparison());
    }
    @Test void executableAndAmbiguousConstructsAreRejected() {
        for (String query : List.of("g.V();System.exit(1)", "g.V('same-id')", "g.V().repeat(out())",
            "g.V().dedup().out()", "g.V().limit(1)", "g.V().path()", "g.V().filter{it.get()}", "g.V().project('x')")) {
            assertThrows(Gremlin.Unsupported.class, () -> new Translator("urn:fixture", Map.of()).translate(query), query);
        }
    }
    @Test void bindingsCannotBecomeSparqlOrGroovyInstructions() {
        String payload = "'); DELETE WHERE {?s ?p ?o}; # \u2603";
        var translation = new Translator("urn:fixture", Map.of("input", GraphRecord.Value.string(payload)))
            .translate("g.V().has('name',input)");
        assertNotNull(QueryFactory.create(translation.sparql()));
        assertThrows(Gremlin.Unsupported.class, () -> Gremlin.parse("g.V().has('x',input + 'evil')"));
    }
}
