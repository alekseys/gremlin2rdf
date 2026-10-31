package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.riot.*;
import org.apache.jena.shacl.*;

public final class Validation {
    private Validation() {}
    static final class InputChanged extends IOException {
        InputChanged() { super("Canonical input changed during structural validation"); }
    }
    static long inventory(Map<String, Long> counts, String key, long bytes) throws IOException {
        if (!counts.containsKey(key)) {
            bytes += key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > 4194304) throw new IOException("Partition/label inventory exceeds bounded byte capacity");
        }
        counts.merge(key, 1L, Long::sum);
        return bytes;
    }
    public record Report(int schemaVersion, String runId, String inputHash, String sourceHash, String status,
                         long vertices, long edges, long propertyOccurrences, long metaProperties,
                         long mismatchedRecords, long unresolvedEndpoints, boolean graphContentExact,
                         boolean derivedConsistent, String consistency, boolean sensitiveArtifact,
                         Map<String, Long> verticesByPartitionAndLabel, Map<String, Long> edgesByPartitionAndLabel,
                         boolean shaclConforms) {}
    public static Report validate(Project project, Path source, Sink sink) throws IOException {
        var mapper = new RdfMapper(project.mapping());
        long vertices = 0, edges = 0, properties = 0, meta = 0, mismatch = 0, endpoints = 0, triples = 0, subjects = 0;
        Map<String, Long> vertexCounts = new TreeMap<>(), edgeCounts = new TreeMap<>();
        long inventoryBytes = 0;
        String sourceHash = SafeFiles.fileHash(source);
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Shapes shapes = shapes(); boolean conforms = true;
        try (var input = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(source), digest);
             var reader = new RecordReader(input, project.limits().maxRecordBytes(), 0)) {
            GraphRecord record;
            while ((record = reader.next()) != null) {
                String key = Json.text(List.of(record.identity().partition(), record.label()));
                if (record.kind() == GraphRecord.Kind.vertex) { vertices++; inventoryBytes = inventory(vertexCounts, key, inventoryBytes); }
                else { edges++; inventoryBytes = inventory(edgeCounts, key, inventoryBytes); }
                if (vertexCounts.size() + edgeCounts.size() > 10000) throw new IOException("Partition/label inventory exceeds bounded validation capacity");
                properties += record.properties().size();
                meta += record.properties().stream().mapToLong(p -> p.metaProperties().size()).sum();
                try (var mapped = mapper.map(record)) {
                    var expected = mapped.canonical();
                    List<Resource> owned = expected.listSubjects().toList();
                    subjects += owned.size(); triples += expected.size();
                    String values = owned.stream().map(r -> NodeFmtLib.strNT(r.asNode())).collect(java.util.stream.Collectors.joining(" "));
                    var query = new ParameterizedSparqlString("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?graph { VALUES ?s { " + values + " } ?s ?p ?o } }");
                    query.setIri("graph", project.graph("canonical"));
                    try (var execution = sink.connection().query(query.asQuery())) {
                        var actual = execution.execConstruct();
                        if (!expected.isIsomorphicWith(actual)) mismatch++;
                        if (record.kind() == GraphRecord.Kind.edge) {
                            var context = new ParameterizedSparqlString("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?graph {"
                                + " VALUES ?s { ?source ?destination } ?s ?p ?o FILTER(?p != ?property) } }");
                            context.setIri("graph", project.graph("canonical")); context.setIri("source", mapper.vertex(record.source()));
                            context.setIri("destination", mapper.vertex(record.destination())); context.setIri("property", RdfMapper.CANONICAL + "property");
                            try (var endpointExecution = sink.connection().query(context.asQuery())) {
                                var endpointsModel = endpointExecution.execConstruct(); actual.add(endpointsModel); endpointsModel.close();
                            }
                        }
                        conforms &= ShaclValidator.get().validate(shapes, actual.getGraph()).conforms();
                        actual.close();
                    }
                    if (record.kind() == GraphRecord.Kind.edge) {
                        var endpointQuery = new ParameterizedSparqlString("ASK { GRAPH ?graph { ?source a ?vertex . ?destination a ?vertex } }");
                        endpointQuery.setIri("graph", project.graph("canonical"));
                        endpointQuery.setIri("source", mapper.vertex(record.source()));
                        endpointQuery.setIri("destination", mapper.vertex(record.destination()));
                        endpointQuery.setIri("vertex", RdfMapper.CANONICAL + "Vertex");
                        if (!sink.connection().queryAsk(endpointQuery.asQuery())) endpoints++;
                    }
                }
            }
        }
        var countQuery = new ParameterizedSparqlString("SELECT (COUNT(*) AS ?triples) (COUNT(DISTINCT ?s) AS ?subjects)"
            + " WHERE { GRAPH ?graph { ?s ?p ?o } }");
        countQuery.setIri("graph", project.graph("canonical"));
        boolean exact;
        try (var execution = sink.connection().query(countQuery.asQuery())) {
            var counts = execution.execSelect().next();
            exact = counts.getLiteral("triples").getLong() == triples && counts.getLiteral("subjects").getLong() == subjects && mismatch == 0;
        }
        var derived = new ParameterizedSparqlString("ASK {"
            + " { GRAPH ?derived { ?s ?p ?d } FILTER NOT EXISTS { GRAPH ?canonical { ?e a ?edge ; ?source ?s ; ?destination ?d ; ?predicate ?p } } }"
            + " UNION { GRAPH ?canonical { ?e a ?edge ; ?source ?s ; ?destination ?d ; ?predicate ?p }"
            + " FILTER NOT EXISTS { GRAPH ?derived { ?s ?p ?d } } } }");
        derived.setIri("derived", project.graph("derived")); derived.setIri("canonical", project.graph("canonical"));
        derived.setIri("edge", RdfMapper.CANONICAL + "Edge"); derived.setIri("source", RdfMapper.CANONICAL + "source");
        derived.setIri("destination", RdfMapper.CANONICAL + "destination"); derived.setIri("predicate", RdfMapper.CANONICAL + "domainPredicate");
        var derivedEmpty = new ParameterizedSparqlString("ASK { GRAPH ?derived { ?s ?p ?o } }");
        derivedEmpty.setIri("derived", project.graph("derived"));
        boolean derivedConsistent = project.mapping().derivedRelationships() ? !sink.connection().queryAsk(derived.asQuery())
            : !sink.connection().queryAsk(derivedEmpty.asQuery());
        boolean strict = project.mapping().fidelity().equals("strict") && !project.consistency().equals("best-effort");
        if (!sourceHash.equals(java.util.HexFormat.of().formatHex(digest.digest()))
            || !sourceHash.equals(SafeFiles.fileHash(source))) throw new InputChanged();
        return new Report(1, project.runId(), project.inputHash(), sourceHash,
            exact && endpoints == 0 && derivedConsistent && strict && conforms ? "succeeded" : "blocked",
            vertices, edges, properties, meta, mismatch, endpoints, exact, derivedConsistent, project.consistency(), true,
            Map.copyOf(vertexCounts), Map.copyOf(edgeCounts), conforms);
    }
    private static Shapes shapes() throws IOException {
        try (var input = Validation.class.getResourceAsStream("/canonical-v1.shacl.ttl")) {
            if (input == null) throw new IOException("Packaged canonical SHACL resource missing");
            var model = ModelFactory.createDefaultModel(); RDFDataMgr.read(model, input, Lang.TURTLE);
            return Shapes.parse(model.getGraph());
        }
    }
}
