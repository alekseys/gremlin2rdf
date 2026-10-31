package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.DatasetFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FusekiIntegrationTest {
    @TempDir Path root;
    static Project remote(Project project, int port) {
        String url = "http://localhost:" + port + "/test";
        return new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(), project.runId(),
            project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(),
            new Project.Target(url, url + "/query", url + "/update", url + "/data", null, "urn:test:run:", "manual"),
            project.limits(), project.consistency(), project.phases(), List.of(), project.applicationHashes(), project.artifacts());
    }
    @Test void realHttpLoadReplayAndTypedStructuralReconstruction() throws Exception {
        var server = FusekiServer.create().loopback(true).port(0).add("/test", DatasetFactory.createTxnMem()).build().start();
        try {
            Project project = RecoveryTest.approved(remote(RecoveryTest.project("http"), server.getPort()));
            Path records = root.resolve("records.ndjson"), state = root.resolve("state");
            Synthetic.generate(new Synthetic.Profile(1, 42, 20, 40, 4), records);
            try (var sink = new Sink(project)) {
                assertTrue(sink.queryAccess());
                assertEquals(60, new Engine().load(project, records, state, sink, update -> {}, batch -> {}).records());
                var report = Validation.validate(project, records, sink);
                assertEquals("succeeded", report.status());
                assertEquals(20, report.vertices()); assertEquals(40, report.edges());
                assertEquals(142, report.propertyOccurrences()); assertEquals(40, report.metaProperties());
                assertEquals(60, new Engine().load(project, records, state, sink, update -> {}, batch -> {}).records());
                assertEquals("succeeded", Validation.validate(project, records, sink).status());
                assertEquals("succeeded", Functional.run(project, records, sink).status());
            }
        } finally { server.stop(); }
    }
    @Test void targetMutationDenialLeavesNoCheckpoint() throws Exception {
        var server = FusekiServer.create().loopback(true).port(0).add("/test", DatasetFactory.createTxnMem(), false).build().start();
        try {
            Project project = RecoveryTest.approved(remote(RecoveryTest.project("denied-http"), server.getPort()));
            Path records = root.resolve("records.ndjson"), state = root.resolve("state");
            Synthetic.generate(new Synthetic.Profile(1, 1, 2, 0, 1), records);
            try (var sink = new Sink(project)) {
                assertThrows(Exception.class, () -> new Engine().load(project, records, state, sink, update -> {}, batch -> {}));
                assertFalse(Files.exists(state.resolve("checkpoint.json")));
                assertFalse(sink.connection().queryAsk("ASK { GRAPH <" + project.graph("canonical") + "> { ?s ?p ?o } }"));
            }
        } finally { server.stop(); }
    }
}
