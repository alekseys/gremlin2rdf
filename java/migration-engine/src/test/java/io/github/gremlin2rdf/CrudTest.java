package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.junit.jupiter.api.Test;

class CrudTest {
    @Test void canonicalCrudIsIdempotentAndKeepsParallelDerivedViewsConsistent() {
        Project unsigned = RecoveryTest.project("crud");
        List<Approval> approvals = new ArrayList<>();
        for (String operation : List.of("application-write", "application-delete"))
            approvals.add(Approval.sign(unsigned, operation, List.of(unsigned.graph("canonical"), unsigned.graph("derived")),
                Instant.now().plusSeconds(300).toString(), "env:GRAPH_TEST_APPROVAL_KEY"));
        Project project = new Project(unsigned.schemaVersion(), unsigned.migrationKind(), unsigned.baselineRepositorySha(), unsigned.runId(),
            unsigned.protocolVersion(), unsigned.workerVersion(), unsigned.mapping(), unsigned.source(), unsigned.target(),
            unsigned.limits(), unsigned.consistency(), unsigned.phases(), approvals, unsigned.applicationHashes(), unsigned.artifacts());
        var dataset = DatasetFactory.createTxnMem(); var mapper = new RdfMapper(project.mapping());
        var profile = new Synthetic.Profile(1, 42, 2, 2, 2);
        var vertex = Synthetic.record(profile, 0);
        var other = Synthetic.record(profile, 1);
        var edge = Synthetic.record(profile, 2);
        var parallel = new GraphRecord(edge.kind(),
            new GraphRecord.Identity(edge.identity().scope(), edge.identity().partition(), GraphRecord.Value.string("parallel")),
            edge.label(), edge.source(), edge.destination(), edge.edgeScope(), edge.properties(), edge.metadata(), edge.provenance());
        try (var access = new GraphAccess(project, new Sink(project, RDFConnection.connect(dataset)))) {
            access.create(vertex); access.create(other); access.create(edge); access.create(parallel); access.create(vertex);
            assertEquals(vertex, access.lookup(mapper.entity(vertex)).orElseThrow());
            var property = new GraphRecord.Property("injection", GraphRecord.Value.string("');DELETE WHERE {?s ?p ?o}"),
                GraphRecord.Value.string("application-property-1"), Map.of("meta", GraphRecord.Value.integer(0)), GraphRecord.Fidelity.complete);
            access.insertProperty(mapper.entity(vertex), property); access.insertProperty(mapper.entity(vertex), property);
            assertEquals(7, access.lookup(mapper.entity(vertex)).orElseThrow().properties().size());
            access.removeProperty(mapper.entity(vertex), "injection");
            assertEquals(6, access.lookup(mapper.entity(vertex)).orElseThrow().properties().size());
            assertThrows(IllegalStateException.class, () -> access.deleteVertex(mapper.entity(vertex), "reject"));
            access.deleteEdge(mapper.entity(edge));
            assertTrue(access.lookup(mapper.entity(parallel)).isPresent());
            assertEquals(1, dataset.getNamedModel(project.graph("derived")).size());
            access.deleteVertex(mapper.entity(vertex), "cascade-edges");
            assertTrue(access.lookup(mapper.entity(vertex)).isEmpty());
            assertTrue(access.lookup(mapper.entity(parallel)).isEmpty());
            assertTrue(access.lookup(mapper.entity(other)).isPresent());
            assertTrue(dataset.getNamedModel(project.graph("derived")).isEmpty());
        }
    }
}
