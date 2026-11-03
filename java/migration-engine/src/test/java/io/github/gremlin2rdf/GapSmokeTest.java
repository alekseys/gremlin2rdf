package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdf.model.*;
import org.apache.jena.shacl.ShaclValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GapSmokeTest {
    @TempDir Path root;
    private Project with(Project p, Mapping mapping, Project.Limits limits, List<Approval> approvals) {
        return new Project(p.schemaVersion(), p.migrationKind(), p.baselineRepositorySha(), p.runId(),
            p.protocolVersion(), p.workerVersion(), mapping, p.source(), p.target(), limits, p.consistency(),
            p.phases(), approvals, p.applicationHashes(), p.artifacts());
    }
    @Test void batchReconciliationUsesBatchRequestsAndAttributesCorruption() throws Exception {
        Project project = RecoveryTest.approved(RecoveryTest.project("batch-smoke"));
        Path records = root.resolve("records.ndjson");
        Synthetic.generate(new Synthetic.Profile(1, 42, 12, 12, 2), records);
        var dataset = DatasetFactory.createTxnMem();
        try (var connection = RDFConnection.connect(dataset); var loading = new Sink(project, connection)) {
            new Engine().load(project, records, root.resolve("state"), loading, checkpoint -> {}, batch -> {});
            AtomicInteger constructs = new AtomicInteger();
            var observed = (RDFConnection) Proxy.newProxyInstance(RDFConnection.class.getClassLoader(),
                new Class<?>[] { RDFConnection.class }, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("query") && args[0].toString().stripLeading().startsWith("CONSTRUCT"))
                        constructs.incrementAndGet();
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
            try (var reading = new Sink(project, observed)) {
                var report = Validation.validate(project, records, reading);
                assertEquals("succeeded", report.status()); assertEquals(24, report.vertices() + report.edges());
                assertTrue(constructs.get() <= 6, "At most two CONSTRUCT requests per ten-record batch");
                var vertex = Synthetic.record(new Synthetic.Profile(1, 42, 12, 12, 2), 0);
                String entity = new RdfMapper(project.mapping()).entity(vertex);
                connection.update("DELETE WHERE { GRAPH <" + project.graph("canonical") + "> { <" + entity
                    + "> <" + RdfMapper.CANONICAL + "label> ?label } }");
                var corrupted = Validation.validate(project, records, reading);
                assertEquals("blocked", corrupted.status()); assertEquals(1, corrupted.mismatchedRecords());
            }
        }
    }
    @Test void preflightAndScopedDisposableProbeUseOnlyLocalDataset() throws Exception {
        var server = FusekiServer.create().loopback(true).port(0).add("/test", DatasetFactory.createTxnMem()).build().start();
        try {
            Project project = FusekiIntegrationTest.remote(RecoveryTest.project("probe-smoke"), server.getPort());
            Path file = root.resolve("project.json"); Files.writeString(file, Json.text(project));
            var preflight = Json.MAPPER.valueToTree(Main.execute(new String[] {"preflight", file.toString()}, new Engine()));
            assertTrue(preflight.toString().contains("not-established-by-read-only-query"));
            String graph = project.target().graphNamespace() + project.runId() + ":probe:" + UUID.randomUUID();
            try (var sink = new Sink(project)) {
                assertThrows(SecurityException.class, () -> TargetProbe.run(project, graph, sink));
            }
            var approval = Approval.sign(project, "target-probe", List.of(graph),
                Instant.now().plusSeconds(300).toString(), "env:GRAPH_TEST_APPROVAL_KEY");
            Project approved = with(project, project.mapping(), project.limits(), List.of(approval));
            try (var sink = new Sink(approved)) {
                assertEquals("succeeded", TargetProbe.run(approved, graph, sink).get("status"));
                assertFalse(sink.connection().queryAsk("ASK { GRAPH <" + graph + "> { ?s ?p ?o } }"));
                sink.connection().update("INSERT DATA { GRAPH <" + graph + "> { <urn:foreign> <urn:p> 'keep' } }");
                assertThrows(IOException.class, () -> TargetProbe.run(approved, graph, sink));
                assertTrue(sink.connection().queryAsk("ASK { GRAPH <" + graph + "> { <urn:foreign> <urn:p> 'keep' } }"));
            }
        } finally { server.stop(); }
    }
    @Test void domainShapesCountEqualValuedOccurrencesAndRejectMissingProjection() throws Exception {
        Mapping standard = Mapping.standard();
        Mapping mapping = new Mapping(1, standard.baseIri(), standard.vocabulary(), Map.of(), Map.of(), Map.of(),
            Set.of(), true, true, "strict", Map.of("tag", new Mapping.ValueRule("string", "literal",
                "http://www.w3.org/2001/XMLSchema#string")),
            List.of(new Mapping.DomainRule("person", "tag", 2, 2)));
        var record = new GraphRecord(GraphRecord.Kind.vertex,
            new GraphRecord.Identity("fixture", GraphRecord.Value.string("p"), GraphRecord.Value.string("v")),
            "person", null, null, null,
            List.of(new GraphRecord.Property("tag", GraphRecord.Value.string("same"), GraphRecord.Value.string("a"), Map.of(),
                    GraphRecord.Fidelity.complete),
                new GraphRecord.Property("tag", GraphRecord.Value.string("same"), GraphRecord.Value.string("b"), Map.of(),
                    GraphRecord.Fidelity.complete)),
            Map.of(), new GraphRecord.Provenance("fixture", "0".repeat(64), "immutable", GraphRecord.Fidelity.complete));
        Model shapes = ModelFactory.createDefaultModel(); DomainShapes.add(shapes, mapping);
        try (var mapped = new RdfMapper(mapping).map(record)) {
            assertTrue(ShaclValidator.get().validate(shapes.getGraph(), mapped.canonical().getGraph()).conforms());
            var projected = mapped.canonical().createProperty(new RdfMapper(mapping).domain("property", "tag"));
            assertEquals(2, mapped.canonical().listStatements(null, projected, (RDFNode) null).toList().size());
            mapped.canonical().removeAll(null, projected, null);
            assertFalse(ShaclValidator.get().validate(shapes.getGraph(), mapped.canonical().getGraph()).conforms());
        } finally { shapes.close(); }
        Path file = root.resolve("mapping.json"); Files.writeString(file, Json.text(mapping));
        Path output = root.resolve("shapes.ttl");
        assertEquals("succeeded", Json.MAPPER.valueToTree(Main.execute(
            new String[] {"mapping", "shapes", file.toString(), output.toString()}, new Engine())).path("status").asText());
        assertTrue(Files.readString(output).contains("qualifiedMinCount"));
        assertFalse(Json.text(standard).contains("valueRules"));
    }
    @Test void assessmentReportsBindingsResultsLifecycleAndRetryWithoutQueryValues() throws Exception {
        Files.writeString(root.resolve("App.java"), """
            class App {
                Client client;
                void migrate() {
                    try { client.submit("g.V()", bindings).all().get(); }
                    catch (RuntimeException failure) { client.close(); }
                }
            }
            """);
        var report = Assessment.assess(root);
        assertEquals("succeeded", report.status());
        assertTrue(report.findings().stream().anyMatch(f -> f.diagnostic().startsWith("Bindings are present")));
        assertTrue(report.findings().stream().anyMatch(f -> f.diagnostic().startsWith("Result/order/multiplicity")));
        assertTrue(report.findings().stream().anyMatch(f -> f.diagnostic().contains("mutation idempotency")));
        assertTrue(report.findings().stream().anyMatch(f -> f.diagnostic().contains("close/disposal")));
        assertFalse(Json.text(report).contains("g.V()"));
    }
    @Test void operationDeadlinePreservesOnlyConfirmedCheckpointAndProgress() throws Exception {
        Project base = RecoveryTest.project("deadline-smoke");
        var limits = base.limits();
        Project project = RecoveryTest.approved(with(base, base.mapping(),
            new Project.Limits(1, limits.batchBytes(), limits.maxRecordBytes(), limits.requestTimeoutMillis(),
                0, 1, 1, 0, 1000L), List.of()));
        Path records = root.resolve("records.ndjson");
        Synthetic.generate(new Synthetic.Profile(1, 42, 3, 0, 1), records);
        var engine = new Engine(); List<Engine.BatchMetrics> metrics = new ArrayList<>(); engine.measure(metrics::add);
        try (var sink = new Sink(project, RDFConnection.connect(DatasetFactory.createTxnMem()))) {
            var result = engine.load(project, records, root.resolve("state"), sink, checkpoint -> {},
                batch -> engine.deadline(0L));
            assertEquals("paused", result.status()); assertTrue(engine.deadlineExceeded());
            assertEquals(1, result.records()); assertEquals(1, metrics.size()); assertEquals(1, metrics.getFirst().records());
            assertTrue(metrics.getFirst().requestBodyBytes() > 0);
            assertEquals(3, new Engine().load(project, records, root.resolve("state"), sink,
                checkpoint -> {}, batch -> {}).records());
        }
    }
}
