package io.github.gremlin2rdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.jena.graph.*;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdfconnection.*;
import org.apache.jena.riot.*;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.modify.request.*;
import org.apache.jena.update.*;

public final class Sink implements AutoCloseable {
    public static final class UnconfirmedBatch extends IllegalStateException {
        UnconfirmedBatch() { super("Ambiguous batch has not been confirmed; checkpoint must not advance"); }
    }
    private final RDFConnection connection;
    private final Project project;
    private final DeadlineHttpClient client;
    private TargetLock runLock;
    private boolean closed;
    public Sink(Project project) {
        this.project = project;
        if (project.target() == null) throw new IllegalArgumentException("Explicit target endpoints required");
        client = new DeadlineHttpClient(project.limits().requestTimeoutMillis(), Project.secret(project.target().authenticationRef()),
            Project.secret(project.target().customCaRef()));
        connection = RDFConnectionRemote.create().destination(project.target().datasetUrl())
            .queryEndpoint(project.target().queryEndpoint()).updateEndpoint(project.target().updateEndpoint())
            .gspEndpoint(project.target().graphStoreEndpoint()).httpClient(client).build();
    }
    public Sink(Project project, RDFConnection connection) { this.project = project; this.connection = connection; client = null; }
    public record Batch(long number, long offset, long records, String hash, List<Quad> quads) {}
    public Batch batchFromQuads(long number, long offset, List<GraphRecord> records, List<Quad> quads) {
        return new Batch(number, offset, records.size(), Json.hash(List.of(project.inputHash(), records)), List.copyOf(quads));
    }
    public Batch batch(long number, long offset, List<GraphRecord> records) {
        var mapper = new RdfMapper(project.mapping());
        List<Quad> quads = new ArrayList<>();
        for (var record : records) {
            try (var mapped = mapper.map(record)) {
                add(quads, project.graph("canonical"), mapped.canonical());
                add(quads, project.graph("derived"), mapped.derived());
            }
        }
        quads.sort(Comparator.comparing(org.apache.jena.riot.out.NodeFmtLib::str));
        String hash = Json.hash(List.of(project.inputHash(), records));
        return new Batch(number, offset, records.size(), hash, List.copyOf(quads));
    }
    private static void add(List<Quad> quads, String graph, Model model) {
        Node graphNode = NodeFactory.createURI(graph);
        model.getGraph().find().forEachRemaining(t -> quads.add(new Quad(graphNode, t)));
    }
    public int serializedBytes(Batch batch) {
        return update(batch).toString().getBytes(StandardCharsets.UTF_8).length;
    }
    private String marker(long number) { return project.graph("metadata") + ":batch:" + number; }
    public Optional<String> markerHash(long number) {
        var query = new ParameterizedSparqlString("SELECT DISTINCT ?hash WHERE { GRAPH ?graph {"
            + " ?batch ?p ?o . OPTIONAL { ?batch ?predicate ?hash } } }");
        query.setIri("graph", project.graph("metadata")); query.setIri("batch", marker(number));
        query.setIri("predicate", RdfMapper.CANONICAL + "batchHash");
        try (var execution = connection.query(query.asQuery())) {
            var results = execution.execSelect();
            if (!results.hasNext()) return Optional.empty();
            var row = results.next();
            if (!row.contains("hash") || !row.get("hash").isLiteral())
                throw new IllegalStateException("Incomplete target batch marker; explicit reconciliation required");
            String hash = row.getLiteral("hash").getString();
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid target batch hash");
            if (results.hasNext()) throw new IllegalStateException("Conflicting target batch markers");
            return Optional.of(hash);
        }
    }
    public void commit(Batch batch) {
        Approval.require(project, "target-write", List.of(project.graph("canonical"), project.graph("derived"), project.graph("metadata")));
        if (!project.mapping().fidelity().equals("strict"))
            Approval.require(project, "reduced-fidelity", List.of(project.mapping().hash()));
        if (runLock == null) {
            if (!ownsCurrentRun()) {
                var existing = new ParameterizedSparqlString("ASK {"
                    + " { GRAPH ?canonical { ?s ?p ?o } } UNION { GRAPH ?derived { ?s ?p ?o } }"
                    + " UNION { GRAPH ?validation { ?s ?p ?o } } UNION { GRAPH ?metadata { ?s ?p ?o } FILTER(?s != ?lock) } }");
                existing.setIri("canonical", project.graph("canonical")); existing.setIri("derived", project.graph("derived"));
                existing.setIri("metadata", project.graph("metadata")); existing.setIri("validation", project.graph("validation"));
                existing.setIri("lock", project.graph("metadata") + ":run-lock");
                if (connection.queryAsk(existing.asQuery())) throw new SecurityException("Refusing to adopt pre-existing unowned named graphs");
            }
            runLock = new TargetLock(project, connection);
        }
        var existing = markerHash(batch.number());
        if (existing.isPresent()) {
            if (!existing.get().equals(batch.hash())) throw new IllegalStateException("Target batch belongs to incompatible content");
            return;
        }
        connection.update(update(batch));
        reconcile(batch);
    }
    private UpdateRequest update(Batch batch) {
        List<Quad> quads = new ArrayList<>(batch.quads());
        Model metadata = ModelFactory.createDefaultModel();
        Resource marker = metadata.createResource(marker(batch.number()));
        marker.addProperty(RdfMapper.predicate(metadata, "batchHash"), batch.hash());
        marker.addProperty(RdfMapper.predicate(metadata, "inputHash"), project.inputHash());
        marker.addLiteral(RdfMapper.predicate(metadata, "recordCount"), batch.records());
        marker.addLiteral(RdfMapper.predicate(metadata, "batchNumber"), batch.number());
        marker.addLiteral(RdfMapper.predicate(metadata, "endOffset"), batch.offset());
        Resource ownership = metadata.createResource(project.graph("metadata") + ":ownership");
        ownership.addProperty(RdfMapper.predicate(metadata, "ownerRunId"), project.runId());
        ownership.addProperty(RdfMapper.predicate(metadata, "inputHash"), project.inputHash());
        for (String role : List.of("canonical", "derived", "metadata", "validation"))
            ownership.addProperty(RdfMapper.predicate(metadata, "ownsGraph"), metadata.createResource(project.graph(role)));
        add(quads, project.graph("metadata"), metadata); metadata.close();
        return new UpdateRequest(new UpdateDataInsert(new QuadDataAcc(quads)));
    }
    public void reconcile(Batch batch) {
        var observed = markerHash(batch.number());
        if (observed.isEmpty()) throw new UnconfirmedBatch();
        if (!observed.get().equals(batch.hash()))
            throw new IllegalStateException("Target batch content conflicts with approved input; no retry permitted");
    }
    public boolean confirms(Engine.Checkpoint checkpoint) {
        var query = new ParameterizedSparqlString("SELECT ?hash ?offset ?input WHERE { GRAPH ?graph {"
            + " ?batch ?hashPredicate ?hash ; ?offsetPredicate ?offset ; ?inputPredicate ?input } }");
        query.setIri("graph", project.graph("metadata")); query.setIri("batch", marker(checkpoint.batch()));
        query.setIri("hashPredicate", RdfMapper.CANONICAL + "batchHash");
        query.setIri("offsetPredicate", RdfMapper.CANONICAL + "endOffset");
        query.setIri("inputPredicate", RdfMapper.CANONICAL + "inputHash");
        try (var execution = connection.query(query.asQuery())) {
            var results = execution.execSelect();
            if (!results.hasNext()) return false;
            var row = results.next();
            if (results.hasNext() || !row.getLiteral("hash").getString().equals(checkpoint.lastBatchHash())
                || row.getLiteral("offset").getLong() != checkpoint.offset()
                || !row.getLiteral("input").getString().equals(checkpoint.inputHash())) return false;
        }
        var count = new ParameterizedSparqlString("SELECT (SUM(?records) AS ?total) WHERE { GRAPH ?graph {"
            + " ?batch ?inputPredicate ?input ; ?numberPredicate ?number ; ?countPredicate ?records } FILTER(?number <= ?maximum) }");
        count.setIri("graph", project.graph("metadata")); count.setIri("inputPredicate", RdfMapper.CANONICAL + "inputHash");
        count.setLiteral("input", checkpoint.inputHash()); count.setIri("numberPredicate", RdfMapper.CANONICAL + "batchNumber");
        count.setIri("countPredicate", RdfMapper.CANONICAL + "recordCount"); count.setLiteral("maximum", checkpoint.batch());
        try (var execution = connection.query(count.asQuery())) {
            return execution.execSelect().next().getLiteral("total").getLong() == checkpoint.records();
        }
    }
    public boolean queryAccess() { return connection.queryAsk("ASK { }"); }
    public Model fetch(String role) { return connection.fetch(project.graph(role)); }
    public boolean ownsCurrentRun() {
        var query = new ParameterizedSparqlString("ASK { GRAPH ?metadata { ?ownership ?runPredicate ?run ;"
            + " ?inputPredicate ?input ; ?ownsPredicate ?canonical, ?derived, ?metadata, ?validation } }");
        query.setIri("metadata", project.graph("metadata")); query.setIri("ownership", project.graph("metadata") + ":ownership");
        query.setIri("runPredicate", RdfMapper.CANONICAL + "ownerRunId"); query.setLiteral("run", project.runId());
        query.setIri("inputPredicate", RdfMapper.CANONICAL + "inputHash"); query.setLiteral("input", project.inputHash());
        query.setIri("ownsPredicate", RdfMapper.CANONICAL + "ownsGraph");
        query.setIri("canonical", project.graph("canonical")); query.setIri("derived", project.graph("derived"));
        query.setIri("validation", project.graph("validation")); return connection.queryAsk(query.asQuery());
    }
    public void cleanup() {
        List<String> roles = List.of("canonical", "derived", "metadata", "validation");
        Approval.require(project, "cleanup", roles.stream().map(project::graph).toList());
        if (!ownsCurrentRun()) throw new SecurityException("Verified run graph ownership manifest required for cleanup");
        if (runLock == null) runLock = new TargetLock(project, connection);
        UpdateRequest request = new UpdateRequest();
        roles.forEach(role -> request.add(new UpdateDrop(NodeFactory.createURI(project.graph(role)), false)));
        connection.update(request);
    }
    public RDFConnection connection() { return connection; }
    public void close() {
        if (closed) return;
        closed = true;
        try { if (runLock != null) runLock.close(); }
        finally { connection.close(); if (client != null) client.close(); }
    }
}
