package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {
    @TempDir Path root;
    static Project project(String run) {
        return new Project(1, "cosmos-gremlin-to-jena", "22e3f2cccbfcc6622cacbf1873b9c620acba085e", run,
            1, "0.1.0", Mapping.standard(), null,
            new Project.Target("http://localhost/test", "http://localhost/test/query", "http://localhost/test/update",
                "http://localhost/test/data", null, "urn:test:run:", "manual"),
            new Project.Limits(10, 1048576, 262144, 1000, 0, 10, 1, 0),
            "immutable", Map.of(), List.of(), Map.of(), List.of());
    }
    static Project approved(Project project) {
        var approval = Approval.sign(project, "target-write",
            List.of(project.graph("canonical"), project.graph("derived"), project.graph("metadata")),
            Instant.now().plusSeconds(300).toString(), "env:GRAPH_TEST_APPROVAL_KEY");
        return new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(), project.runId(),
            project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(), project.target(),
            project.limits(), project.consistency(), project.phases(), List.of(approval), project.applicationHashes(), project.artifacts());
    }
    @Test void authorizationRequiredBeforeAnyTargetMutation() throws Exception {
        var dataset = DatasetFactory.createTxnMem(); var project = project("denied");
        try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
            assertThrows(SecurityException.class, () -> sink.commit(sink.batch(0, 1,
                List.of(Synthetic.record(Synthetic.Profile.semantic(1), 0)))));
            assertTrue(sink.fetch("canonical").isEmpty());
        }
    }
    @Test void commitBeforeCheckpointCrashIsReconciledWithoutDuplicates() throws Exception {
        var dataset = DatasetFactory.createTxnMem(); var project = approved(project("recover"));
        Path records = root.resolve("records.ndjson"), state = root.resolve("state");
        var profile = new Synthetic.Profile(1, 42, 20, 40, 4);
        Synthetic.generate(profile, records);
        try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
            assertThrows(IOException.class, () -> new Engine().load(project, records, state, sink, checkpoint -> {},
                batch -> { if (batch.number() == 1) throw new IOException("Injected after commit"); }));
            var checkpoint = Json.MAPPER.readValue(Files.readAllBytes(state.resolve("checkpoint.json")), Engine.Checkpoint.class);
            assertEquals(0, checkpoint.batch()); assertEquals(10, checkpoint.records());
            var result = new Engine().load(project, records, state, sink, checkpointUpdate -> {}, batch -> {});
            assertEquals("succeeded", result.status()); assertEquals(60, result.records());
            assertTrue(result.resumedOffset() > 0);
            var before = sink.fetch("canonical");
            var repeated = new Engine().load(project, records, state, sink, update -> {}, batch -> {});
            assertEquals(60, repeated.records()); assertTrue(before.isIsomorphicWith(sink.fetch("canonical")));
            assertEquals(Files.size(records), repeated.resumedOffset());
        }
    }
    @Test void corruptCheckpointAndChangedInputFailClosed() throws Exception {
        var dataset = DatasetFactory.createTxnMem(); var project = approved(project("corrupt"));
        Path records = root.resolve("records.ndjson"), state = root.resolve("state");
        Synthetic.generate(new Synthetic.Profile(1, 1, 2, 0, 1), records);
        try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
            new Engine().load(project, records, state, sink, update -> {}, batch -> {});
            Files.writeString(state.resolve("checkpoint.json"), "{}");
            assertThrows(Exception.class, () -> new Engine().load(project, records, state, sink, update -> {}, batch -> {}));
        }
    }
    @Test void pendingCheckpointReconcilesOnlyConfirmedContentAndByteBoundsAreExact() throws Exception {
            var dataset = DatasetFactory.createTxnMem(); var project = approved(project("pending"));
            Path records = root.resolve("records.ndjson"), state = root.resolve("state");
            Synthetic.generate(new Synthetic.Profile(1, 42, 20, 40, 4), records);
            Engine engine = new Engine(); List<Engine.BatchMetrics> metrics = new ArrayList<>(); engine.measure(metrics::add);
            try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
                engine.load(project, records, state, sink, update -> {}, batch -> {});
                assertTrue(metrics.stream().allMatch(m -> m.requestBodyBytes() <= project.limits().batchBytes() && m.queueDepth() <= project.limits().queueCapacity()));
                Path checkpoint = state.resolve("checkpoint.json"), pending = state.resolve("checkpoint.json.pending");
                Files.copy(checkpoint, pending);
                assertEquals("succeeded", new Engine().load(project, records, state, sink, update -> {}, batch -> {}).status());
                assertFalse(Files.exists(pending));
                var invalid = Json.MAPPER.readValue(Files.readAllBytes(checkpoint), Engine.Checkpoint.class);
                SafeFiles.atomic(checkpoint, new Engine.Checkpoint(invalid.schemaVersion(), invalid.runId(), invalid.inputHash(),
                    invalid.sourceHash(), invalid.batch(), invalid.offset(), invalid.records() + 1, invalid.lastBatchHash(), "").signed());
                assertThrows(IOException.class, () -> new Engine().load(project, records, state, sink, update -> {}, batch -> {}));
            }
        }
        @Test void preExistingUnownedGraphsCannotBeAdoptedOrDeletedAndTargetLockIsExclusive() {
            var dataset = DatasetFactory.createTxnMem(); var project = approved(project("ownership"));
            dataset.getNamedModel(project.graph("canonical")).createResource("urn:unowned").addProperty(
                dataset.getNamedModel(project.graph("canonical")).createProperty("urn:p"), "preserved");
            try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
                assertThrows(SecurityException.class, () -> sink.commit(sink.batch(0, 1, List.of(Synthetic.record(Synthetic.Profile.semantic(1), 0)))));
                assertThrows(SecurityException.class, sink::cleanup);
            }
            assertEquals(1, dataset.getNamedModel(project.graph("canonical")).size());
            var other = DatasetFactory.createTxnMem(); var firstConnection = RDFConnection.connect(other); var secondConnection = RDFConnection.connect(other);
            try (var first = new TargetLock(project, firstConnection)) {
                assertThrows(IllegalStateException.class, () -> new TargetLock(project, secondConnection));
            } finally { firstConnection.close(); secondConnection.close(); }
        }
    @Test void competingWorkersCannotAcquireRun() throws Exception {
        try (var first = new RunLock(root)) { assertThrows(IOException.class, () -> new RunLock(root)); }
        try (var recovered = new RunLock(root)) { assertNotNull(recovered); }
    }
    @Test void partialOrConflictingTargetMarkersArePreservedWithoutAdoptionOrDeletion() {
        var project = approved(project("malformed-lock"));
        var dataset = DatasetFactory.createTxnMem();
        var model = dataset.getNamedModel(project.graph("metadata"));
        var marker = model.createResource(project.graph("metadata") + ":run-lock");
        marker.addProperty(model.createProperty("urn:foreign-marker"), "preserved");
        try (var connection = RDFConnection.connect(dataset)) {
            assertThrows(IllegalStateException.class, () -> new TargetLock(project, connection));
            assertEquals(1, model.size());
            model.removeAll();
            var lock = new TargetLock(project, connection);
            marker.addProperty(model.createProperty(RdfMapper.CANONICAL + "lockOwner"), UUID.randomUUID().toString());
            long before = model.size();
            assertThrows(IllegalStateException.class, lock::close);
            assertEquals(before, model.size());
        }
    }
    @Test void expiredAuthorizationCannotAcquireTargetOwnership() {
        var project = project("expired-lock");
        var expired = Approval.sign(project, "target-write",
            List.of(project.graph("canonical"), project.graph("derived"), project.graph("metadata")),
            Instant.now().minusSeconds(1).toString(), "env:GRAPH_TEST_APPROVAL_KEY");
        var denied = new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(), project.runId(),
            project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(), project.target(),
            project.limits(), project.consistency(), project.phases(), List.of(expired), project.applicationHashes(), project.artifacts());
        var dataset = DatasetFactory.createTxnMem();
        try (var connection = RDFConnection.connect(dataset)) {
            assertThrows(SecurityException.class, () -> new TargetLock(denied, connection));
            assertTrue(dataset.getNamedModel(project.graph("metadata")).isEmpty());
        }
    }
    @Test void verifiedDeadLocalOwnerCanBeRecoveredWithoutOldOwnerDeletingReplacement() {
        var project = approved(project("dead-lock"));
        var dataset = DatasetFactory.createTxnMem();
        try (var connection = RDFConnection.connect(dataset)) {
            var old = new TargetLock(project, connection);
            var model = dataset.getNamedModel(project.graph("metadata"));
            var marker = model.createResource(project.graph("metadata") + ":run-lock");
            marker.removeAll(model.createProperty(RdfMapper.CANONICAL + "lockPid"))
                .addLiteral(model.createProperty(RdfMapper.CANONICAL + "lockPid"), Long.MAX_VALUE);
            marker.removeAll(model.createProperty(RdfMapper.CANONICAL + "lockStart"))
                .addProperty(model.createProperty(RdfMapper.CANONICAL + "lockStart"), Instant.EPOCH.toString());
            try (var replacement = new TargetLock(project, connection)) {
                long size = model.size();
                old.close();
                assertEquals(size, model.size());
            }
            assertTrue(model.isEmpty());
        }
    }
    @Test void cancellationPersistsAcknowledgedBatches() throws Exception {
        var dataset = DatasetFactory.createTxnMem(); var project = approved(project("cancel"));
        Path records = root.resolve("records.ndjson"), state = root.resolve("state");
        Synthetic.generate(new Synthetic.Profile(1, 1, 20, 0, 1), records);
        Engine engine = new Engine();
        try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
            long start = System.nanoTime();
            var result = engine.load(project, records, state, sink, update -> engine.cancel(), batch -> {});
            assertEquals("cancelled", result.status()); assertEquals(10, result.records());
            assertTrue((System.nanoTime() - start) < 5000000000L);
            assertEquals(20, new Engine().load(project, records, state, sink, update -> {}, batch -> {}).records());
        }
    }
    @Test void editsDuringLoadingCannotChangeApprovedBatchesOrProduceSuccess() throws Exception {
            var project = approved(project("snapshot"));
            Path records = root.resolve("records.ndjson"), state = root.resolve("state");
            Synthetic.generate(new Synthetic.Profile(1, 1, 20, 0, 1), records);
            byte[] approvedBytes = Files.readAllBytes(records);
            var dataset = DatasetFactory.createTxnMem();
            try (var sink = new Sink(project, RDFConnection.connect(dataset))) {
                assertThrows(IOException.class, () -> new Engine().load(project, records, state, sink, update -> {},
                    batch -> { if (batch.number() == 0) Files.writeString(records, "unapproved replacement\n"); }));
                var checkpoint = Json.MAPPER.readValue(Files.readAllBytes(state.resolve("checkpoint.json")), Engine.Checkpoint.class);
                assertEquals(20, checkpoint.records());
                Files.write(records, approvedBytes);
                assertEquals("succeeded", Validation.validate(project, records, sink).status());
            }
            try (var files = Files.list(state)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".g2r-approved-")));
            }
    }
}
