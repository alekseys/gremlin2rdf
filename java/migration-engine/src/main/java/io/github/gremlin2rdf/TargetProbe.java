package io.github.gremlin2rdf;

import java.io.IOException;
import java.util.*;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;

final class TargetProbe {
    private TargetProbe() {}
    static Map<String, Object> run(Project project, String graph, Sink sink) throws IOException {
        Mapping.iri(graph);
        String prefix = project.target().graphNamespace() + project.runId() + ":probe:";
        if (!graph.startsWith(prefix) || !graph.substring(prefix.length()).matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Dedicated run-owned UUID probe graph required");
        Approval.require(project, "target-probe", List.of(graph));
        Model expected = ModelFactory.createDefaultModel();
        try {
            Resource marker = expected.createResource(graph + ":owner");
            marker.addProperty(expected.createProperty(RdfMapper.CANONICAL + "runId"), project.runId());
            marker.addProperty(expected.createProperty(RdfMapper.CANONICAL + "inputHash"), project.inputHash());
            var read = new ParameterizedSparqlString("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?graph { ?s ?p ?o } }");
            read.setIri("graph", graph);
            try (var query = sink.connection().query(read.asQuery())) {
                Model actual = query.execConstruct();
                try {
                    if (!actual.isEmpty() && !actual.isIsomorphicWith(expected))
                        throw new IOException("Probe graph contains unowned or changed content");
                } finally { actual.close(); }
            }
            var buffer = new java.io.ByteArrayOutputStream();
            RDFDataMgr.write(buffer, expected, Lang.NTRIPLES);
            String triples = buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
            var insert = new ParameterizedSparqlString(
                "INSERT { GRAPH ?graph { " + triples + " } } WHERE { FILTER NOT EXISTS { GRAPH ?graph { ?s ?p ?o } } }");
            insert.setIri("graph", graph);
            Approval.require(project, "target-probe", List.of(graph));
            sink.connection().update(insert.asUpdate());
            try (var query = sink.connection().query(read.asQuery())) {
                Model actual = query.execConstruct();
                try {
                    if (!expected.isIsomorphicWith(actual)) throw new IOException("Target probe write was not confirmed");
                } finally { actual.close(); }
            }
            // Delete only the two exact owned statements, never a graph-wide DROP.
            var delete = new ParameterizedSparqlString("DELETE DATA { GRAPH ?graph { " + triples + " } }");
            delete.setIri("graph", graph);
            Approval.require(project, "target-probe", List.of(graph));
            sink.connection().update(delete.asUpdate());
            try (var query = sink.connection().query(read.asQuery())) {
                Model actual = query.execConstruct();
                try {
                    if (!actual.isEmpty()) throw new IOException("Probe cleanup was not confirmed; preserve graph for reconciliation");
                } finally { actual.close(); }
            }
            return Map.of("status", "succeeded", "graph", graph, "runId", project.runId(),
                "inputHash", project.inputHash(), "writeConfirmed", true, "deleteConfirmed", true,
                "transactionGuarantee", "not-established-by-capability-probe");
        } finally { expected.close(); }
    }
}
