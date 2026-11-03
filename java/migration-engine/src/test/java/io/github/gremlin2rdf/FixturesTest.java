package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixturesTest {
    @TempDir Path root;
    private Project authorize(Project project, Path catalog) throws Exception {
        List<Approval> approvals = new ArrayList<>(RecoveryTest.approved(project).approvals());
        for (String operation : List.of("application-write", "application-delete", "fixture-test")) {
            var resources = new ArrayList<>(List.of(project.graph("canonical"), project.graph("derived")));
            if (operation.equals("fixture-test")) resources.add(SafeFiles.fileHash(catalog));
            approvals.add(Approval.sign(project, operation, resources, Instant.now().plusSeconds(300).toString(), "env:GRAPH_TEST_APPROVAL_KEY"));
        }
        return new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(), project.runId(),
            project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(), project.target(), project.limits(),
            project.consistency(), project.phases(), approvals, project.applicationHashes(), project.artifacts());
    }
    @Test void derivesIdenticalCatalogsAndExecutesActualHttpQueriesAndDeclaredCrudWithoutCorruption() throws Exception {
        var server = FusekiServer.create().loopback(true).port(0).add("/test", DatasetFactory.createTxnMem()).build().start();
        try {
            var unsigned = FusekiIntegrationTest.remote(RecoveryTest.project("catalog"), server.getPort());
            Path records = root.resolve("records.ndjson");
            Synthetic.generate(Synthetic.Profile.semantic(42), records);
            var first = Fixtures.generate(unsigned, root, "records.ndjson", "first", unsigned.graph("canonical"));
            var second = Fixtures.generate(unsigned, root, "records.ndjson", "second", unsigned.graph("canonical"));
            assertEquals(first, second);
            for (var artifact : first.artifacts())
                assertEquals(-1, Files.mismatch(root.resolve("first").resolve(artifact.path()), root.resolve("second").resolve(artifact.path())));
            var project = authorize(unsigned, root.resolve("first").resolve("catalog.json"));
            try (var sink = new Sink(project)) {
                assertEquals("succeeded", new Engine().load(project, records, root.resolve("state"), sink, update -> {}, batch -> {}).status());
                var report = Fixtures.run(project, root, "first", sink);
                assertEquals("succeeded", report.status());
                assertEquals(Functional.QUERIES.size(), report.queries().size());
                assertEquals(15, report.mutations().size());
                assertTrue(report.queries().stream().allMatch(query -> query.mismatchCount() == 0));
                assertEquals("succeeded", Validation.validate(project, records, sink).status());
            }
        } finally { server.stop(); }
    }
    @Test void missingCatalogApprovalFailsBeforeAnyTargetMutation() throws Exception {
        var project = RecoveryTest.project("catalog-denied");
        Synthetic.generate(new Synthetic.Profile(1, 42, 20, 40, 4), root.resolve("records.ndjson"));
        Fixtures.generate(project, root, "records.ndjson", "catalog", project.graph("canonical"));
        var dataset = DatasetFactory.createTxnMem();
        try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
            assertThrows(SecurityException.class, () -> Fixtures.run(project, root, "catalog", sink));
            assertTrue(dataset.getNamedModel(project.graph("canonical")).isEmpty());
            assertTrue(dataset.getNamedModel(project.graph("derived")).isEmpty());
        }
    }
    @Test void approvedCancelledFixtureStopsBeforeQueriesAndMutationsAndDoesNotClaimAcceptance() throws Exception {
        var unsigned = RecoveryTest.project("catalog-stop");
        Synthetic.generate(new Synthetic.Profile(1, 42, 20, 40, 4), root.resolve("records.ndjson"));
        Fixtures.generate(unsigned, root, "records.ndjson", "catalog", unsigned.graph("canonical"));
        var project = authorize(unsigned, root.resolve("catalog").resolve("catalog.json"));
        var dataset = DatasetFactory.createTxnMem(); var engine = new Engine(); engine.cancel();
        try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
            var stopped = Fixtures.run(project, root, "catalog", sink, engine);
            assertEquals("cancelled", stopped.status()); assertTrue(stopped.queries().isEmpty()); assertTrue(stopped.mutations().isEmpty());
            assertTrue(dataset.getNamedModel(project.graph("canonical")).isEmpty());
            assertTrue(stopped.oracle().startsWith("partial-fixture-state-preserved"));
        }
    }
    @Test void checksumChangesAndReapprovedButFalseOraclesAreNotAccepted() throws Exception {
        var unsigned = RecoveryTest.project("catalog-tampered");
        Path records = root.resolve("records.ndjson");
        Synthetic.generate(new Synthetic.Profile(1, 42, 20, 40, 4), records);
        var catalog = Fixtures.generate(unsigned, root, "records.ndjson", "catalog", unsigned.graph("canonical"));
        Path query = root.resolve("catalog").resolve("query-00.json");
        var definition = Json.MAPPER.readValue(Files.readAllBytes(query), Fixtures.QueryFixture.class);
        Files.delete(query);
        SafeFiles.atomic(query, new Fixtures.QueryFixture(definition.gremlin(), definition.translation(), List.of()));
        var dataset = DatasetFactory.createTxnMem();
        var project = authorize(unsigned, root.resolve("catalog").resolve("catalog.json"));
        try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
            new Engine().load(project, records, root.resolve("state"), sink, update -> {}, batch -> {});
            assertThrows(java.io.IOException.class, () -> Fixtures.run(project, root, "catalog", sink));
        }
        var artifacts = new ArrayList<Fixtures.Artifact>();
        for (var artifact : catalog.artifacts())
            artifacts.add(artifact.path().equals("query-00.json") ? new Fixtures.Artifact(artifact.path(), SafeFiles.fileHash(query), artifact.kind()) : artifact);
        Path manifest = root.resolve("catalog").resolve("catalog.json"); Files.delete(manifest);
        SafeFiles.atomic(manifest, new Fixtures.Catalog(catalog.schemaVersion(), catalog.runId(), catalog.inputHash(), catalog.sourcePath(),
            catalog.sourceHash(), catalog.graph(), artifacts, true));
        var reapproved = authorize(unsigned, manifest);
        try (var sink = new Sink(reapproved, RDFConnection.connect(dataset))) {
            assertThrows(java.io.IOException.class, () -> Fixtures.run(reapproved, root, "catalog", sink));
            assertEquals("succeeded", Validation.validate(reapproved, records, sink).status());
        }
    }
    @Test void typedMismatchEvidencePreservesMultiplicityAndBoundsSequenceExamples() {
        var integer = new GraphAccess.Term("literal", "1", "http://www.w3.org/2001/XMLSchema#integer", "");
        var text = new GraphAccess.Term("literal", "1", "http://www.w3.org/2001/XMLSchema#string", "");
        var row = Map.of("value", integer); var wrong = Map.of("value", text);
        var bag = new Translator("urn:fixture", Map.of()).translate("g.V().values('number')");
        var multiplicity = Functional.compare(bag, List.of(row, row), List.of(row));
        assertEquals("failed", multiplicity.status());
        assertEquals(2, multiplicity.differences().getFirst().expectedMultiplicity());
        assertEquals(1, multiplicity.differences().getFirst().actualMultiplicity());
        var sequence = new Translator("urn:fixture", Map.of()).translate("g.V().values('number').order()");
        var typed = Functional.compare(sequence, Collections.nCopies(30, row), Collections.nCopies(30, wrong));
        assertEquals(30, typed.mismatchCount()); assertEquals(20, typed.differences().size());
        assertNotEquals(typed.differences().getFirst().expected(), typed.differences().getFirst().actual());
    }
}
