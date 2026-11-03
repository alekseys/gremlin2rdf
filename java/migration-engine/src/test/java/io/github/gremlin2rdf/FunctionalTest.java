package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FunctionalTest {
    @TempDir Path root;
    @Test void everySupportedGoldenQueryMatchesIndependentSourceOracle() throws Exception {
        var project = RecoveryTest.project("semantic-oracle");
        var profile = Synthetic.Profile.semantic(42); Path source = root.resolve("semantic.ndjson");
        Synthetic.generate(profile, source);
        var dataset = DatasetFactory.createTxnMem(); var model = dataset.getNamedModel(project.graph("canonical"));
        var mapper = new RdfMapper(project.mapping());
        for (int i = 0; i < 1000; i++) try (var mapped = mapper.map(Synthetic.record(profile, i))) { model.add(mapped.canonical()); }
        try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
            var report = Functional.run(project, source, sink);
            assertEquals(26, report.cases().size());
            for (var query : report.cases()) assertEquals("succeeded", query.status(), query.fingerprint());
            assertEquals("succeeded", report.status());
            assertEquals("blocked-no-live-credentials", report.liveCosmosCompatibility());
        }
    }
}
