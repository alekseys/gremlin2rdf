package example;

import static org.junit.jupiter.api.Assertions.*;
import io.github.gremlin2rdf.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.DatasetFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphRepositoryTest {
    @TempDir Path root;
    @Test void generatedJenaApplicationExecutesOverRealRemoteCanonicalData() throws Exception {
        var server = FusekiServer.create().loopback(true).port(0).add("/fixture", DatasetFactory.createTxnMem()).build().start();
        try {
            String url = "http://localhost:" + server.getPort() + "/fixture";
            var unsigned = new Project(1, "cosmos-gremlin-to-jena", "22e3f2cccbfcc6622cacbf1873b9c620acba085e",
                "fixture", 1, "0.1.0", new Mapping(1, "urn:fixture:", "urn:domain:", Map.of(), Map.of(),
                Map.of(), Set.of(), true, true, "strict"), null,
                new Project.Target(url, url + "/query", url + "/update", url + "/data", null, "urn:example:", "manual"),
                Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
            var approval = Approval.sign(unsigned, "target-write",
                List.of(unsigned.graph("canonical"), unsigned.graph("derived"), unsigned.graph("metadata")),
                Instant.now().plusSeconds(300).toString(), "env:GRAPH_TEST_APPROVAL_KEY");
            var project = new Project(unsigned.schemaVersion(), unsigned.migrationKind(), unsigned.baselineRepositorySha(), unsigned.runId(),
                unsigned.protocolVersion(), unsigned.workerVersion(), unsigned.mapping(), unsigned.source(), unsigned.target(), unsigned.limits(),
                unsigned.consistency(), unsigned.phases(), List.of(approval), unsigned.applicationHashes(), unsigned.artifacts());
            Path records = root.resolve("records.ndjson");
            Synthetic.generate(new Synthetic.Profile(1, 42, 20, 40, 4), records);
            try (var sink = new Sink(project)) {
                new Engine().load(project, records, root.resolve("state"), sink, checkpoint -> {}, batch -> {});
                var repository = new GraphRepository(new GraphAccess(project, sink));
                assertEquals(20, repository.vertices()); assertEquals(40, repository.edges());
                assertEquals("preserved", repository.unrelated());
            }
        } finally { server.stop(); }
    }
}
