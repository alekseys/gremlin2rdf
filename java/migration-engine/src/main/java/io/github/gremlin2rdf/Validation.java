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
        Engine engine = new Engine();
        engine.deadline(project.limits().maximumOperationMillis());
        return validate(project, source, sink, engine);
    }
    public static Report validate(Project project, Path source, Sink sink, Engine engine) throws IOException {
        var mapper = new RdfMapper(project.mapping());
        long vertices = 0, edges = 0, properties = 0, meta = 0, mismatch = 0, endpoints = 0, triples = 0, subjects = 0;
        Map<String, Long> vertexCounts = new TreeMap<>(), edgeCounts = new TreeMap<>();
        long inventoryBytes = 0;
        String sourceHash = SafeFiles.fileHash(source);
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Shapes shapes = shapes(project.mapping()); boolean conforms = true;
        List<GraphRecord> batch = new ArrayList<>();
        long batchBytes = 0;
        try (var input = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(source), digest);
             var reader = new RecordReader(input, project.limits().maxRecordBytes(), 0)) {
            GraphRecord record;
            while ((record = reader.next()) != null) {
                if (engine.stopping()) throw new IOException("Validation stopped; partial observations do not establish fidelity");
                String key = Json.text(List.of(record.identity().partition(), record.label()));
                if (record.kind() == GraphRecord.Kind.vertex) { vertices++; inventoryBytes = inventory(vertexCounts, key, inventoryBytes); }
                else { edges++; inventoryBytes = inventory(edgeCounts, key, inventoryBytes); }
                if (vertexCounts.size() + edgeCounts.size() > 10000) throw new IOException("Partition/label inventory exceeds bounded validation capacity");
                properties += record.properties().size();
                meta += record.properties().stream().mapToLong(p -> p.metaProperties().size()).sum();
                try (var mapped = mapper.map(record)) {
                    long bytes = serializedBytes(mapped.canonical(), project.limits().batchBytes());
                    if (!batch.isEmpty() && (batch.size() >= project.limits().batchRecords()
                        || bytes > project.limits().batchBytes() - batchBytes)) {
                        var checked = validateBatch(project, batch, sink, shapes);
                        mismatch += checked.mismatches(); endpoints += checked.endpoints();
                        subjects += checked.subjects(); triples += checked.triples(); conforms &= checked.conforms();
                        batch.clear(); batchBytes = 0;
                    }
                    batch.add(record); batchBytes += bytes;
                }
            }
        }
        if (!batch.isEmpty()) {
            if (engine.stopping()) throw new IOException("Validation stopped; preserve partial observations");
            var checked = validateBatch(project, batch, sink, shapes);
            mismatch += checked.mismatches(); endpoints += checked.endpoints();
            subjects += checked.subjects(); triples += checked.triples(); conforms &= checked.conforms();
        }
        var countQuery = new ParameterizedSparqlString("SELECT (COUNT(*) AS ?triples) (COUNT(DISTINCT ?s) AS ?subjects)"
            + " WHERE { GRAPH ?graph { ?s ?p ?o } }");
        countQuery.setIri("graph", project.graph("canonical"));
        boolean exact;
        try (var execution = sink.connection().query(countQuery.asQuery())) {
            var counts = execution.execSelect().next();
            exact = counts.getLiteral("triples").getLong() == triples && counts.getLiteral("subjects").getLong() == subjects && mismatch == 0;
        }
        if (engine.stopping()) throw new IOException("Validation deadline exhausted before final reconciliation");
        Report result = finish(project, source, sink, digest, sourceHash, vertices, edges, properties, meta,
            mismatch, endpoints, exact, conforms, vertexCounts, edgeCounts);
        if (engine.stopping()) throw new IOException("Validation deadline exhausted during final reconciliation");
        return result;
    }
        private record BatchCheck(long mismatches, long endpoints, long subjects, long triples, boolean conforms) {}
        private static long serializedBytes(Model model, long limit) throws IOException {
            class Counter extends java.io.OutputStream {
                long bytes;
                public void write(int value) throws IOException { add(1); }
                public void write(byte[] value, int offset, int length) throws IOException { add(length); }
                private void add(int count) throws IOException {
                    if (count > limit - bytes) throw new IOException("Mapped validation record exceeds configured batch-byte bound");
                    bytes += count;
                }
            }
            Counter output = new Counter();
            RDFDataMgr.write(output, model, Lang.NTRIPLES);
            return output.bytes;
        }
        private static BatchCheck validateBatch(Project project, List<GraphRecord> records, Sink sink, Shapes shapes) throws IOException {
            var mapper = new RdfMapper(project.mapping());
            Model expected = ModelFactory.createDefaultModel();
            try {
                Map<Resource, Integer> owners = new HashMap<>();
                Set<String> endpointIris = new TreeSet<>();
                for (int index = 0; index < records.size(); index++) {
                    GraphRecord record = records.get(index);
                    try (var mapped = mapper.map(record)) {
                        expected.add(mapped.canonical());
                        for (Resource subject : mapped.canonical().listSubjects().toList()) {
                            Integer previous = owners.putIfAbsent(subject, index);
                            if (previous != null) throw new IOException("Duplicate canonical identities in validation batch");
                        }
                    }
                    if (record.kind() == GraphRecord.Kind.edge) {
                        endpointIris.add(mapper.vertex(record.source())); endpointIris.add(mapper.vertex(record.destination()));
                    }
                }
                String values = owners.keySet().stream().map(r -> NodeFmtLib.strNT(r.asNode()))
                    .collect(java.util.stream.Collectors.joining(" "));
                var query = new ParameterizedSparqlString(
                    "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?graph { VALUES ?s { " + values + " } ?s ?p ?o } }");
                query.setIri("graph", project.graph("canonical"));
                try (var execution = sink.connection().query(query.asQuery())) {
                    Model actual = execution.execConstruct();
                    try {
                        Set<Integer> mismatches = new HashSet<>();
                        Model missing = expected.difference(actual), additional = actual.difference(expected);
                        try {
                            for (Model difference : List.of(missing, additional))
                                for (Resource subject : difference.listSubjects().toList()) {
                                    Integer owner = owners.get(subject);
                                    if (owner == null) {
                                        for (int i = 0; i < records.size(); i++) mismatches.add(i);
                                    } else mismatches.add(owner);
                                }
                        } finally { missing.close(); additional.close(); }
                        long unresolved = 0;
                        if (!endpointIris.isEmpty()) {
                            String endpoints = endpointIris.stream().map(iri -> NodeFmtLib.strNT(NodeFactory.createURI(iri)))
                                .collect(java.util.stream.Collectors.joining(" "));
                            var context = new ParameterizedSparqlString(
                                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?graph { VALUES ?s { " + endpoints
                                + " } ?s ?p ?o FILTER (?p IN (?type, ?label, ?id, ?partition, ?scope))"
                                + " FILTER (?p != ?type || ?o = ?vertex) } }");
                            context.setIri("graph", project.graph("canonical"));
                            context.setIri("type", org.apache.jena.vocabulary.RDF.type.getURI());
                            context.setIri("vertex", RdfMapper.CANONICAL + "Vertex");
                            context.setIri("label", RdfMapper.CANONICAL + "label");
                            context.setIri("id", RdfMapper.CANONICAL + "id");
                            context.setIri("partition", RdfMapper.CANONICAL + "partition");
                            context.setIri("scope", RdfMapper.CANONICAL + "sourceScope");
                            try (var endpointExecution = sink.connection().query(context.asQuery())) {
                                Model endpointModel = endpointExecution.execConstruct();
                                try {
                                    Resource vertex = actual.createResource(RdfMapper.CANONICAL + "Vertex");
                                    for (GraphRecord record : records) if (record.kind() == GraphRecord.Kind.edge
                                        && (!endpointModel.contains(endpointModel.createResource(mapper.vertex(record.source())),
                                            org.apache.jena.vocabulary.RDF.type, vertex)
                                            || !endpointModel.contains(endpointModel.createResource(mapper.vertex(record.destination())),
                                            org.apache.jena.vocabulary.RDF.type, vertex))) unresolved++;
                                    actual.add(endpointModel);
                                } finally { endpointModel.close(); }
                            }
                        }
                        return new BatchCheck(mismatches.size(), unresolved, owners.size(), expected.size(),
                            ShaclValidator.get().validate(shapes, actual.getGraph()).conforms());
                    } finally { actual.close(); }
                }
            } finally { expected.close(); }
        }
    private static Report finish(Project project, Path source, Sink sink, java.security.MessageDigest digest,
                                 String sourceHash, long vertices, long edges, long properties, long meta,
                                 long mismatch, long endpoints, boolean exact, boolean conforms,
                                 Map<String, Long> vertexCounts, Map<String, Long> edgeCounts) throws IOException {
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
    private static Shapes shapes(Mapping mapping) throws IOException {
        try (var input = Validation.class.getResourceAsStream("/canonical-v1.shacl.ttl")) {
            if (input == null) throw new IOException("Packaged canonical SHACL resource missing");
            var model = ModelFactory.createDefaultModel(); RDFDataMgr.read(model, input, Lang.TURTLE);
            DomainShapes.add(model, mapping);
            return Shapes.parse(model.getGraph());
        }
    }
}
