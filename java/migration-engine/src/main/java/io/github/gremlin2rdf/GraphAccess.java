package io.github.gremlin2rdf;

import java.util.*;
import org.apache.jena.graph.*;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.sparql.modify.request.*;
import org.apache.jena.update.*;

public final class GraphAccess implements AutoCloseable {
    public record Term(String kind, String lexical, String datatype, String language) {
        public static Term from(RDFNode node) {
            if (node.isURIResource()) return new Term("iri", node.asResource().getURI(), null, null);
            if (node.isLiteral()) {
                var literal = node.asLiteral();
                return new Term("literal", literal.getLexicalForm(), literal.getDatatypeURI(), literal.getLanguage());
            }
            throw new IllegalArgumentException("Blank-node results are not canonical graph identities");
        }
    }
    private final Project project;
    private final Sink sink;
    private final RdfMapper mapper;
    private static final long MAX_READ_BYTES = 16777216;
    public GraphAccess(Project project) { this(project, new Sink(project)); }
    public GraphAccess(Project project, Sink sink) { this.project = project; this.sink = sink; mapper = new RdfMapper(project.mapping()); }
    public List<Map<String, Term>> read(String sparql) {
        Query query = QueryFactory.create(sparql);
        if (!query.isSelectType()) throw new IllegalArgumentException("Typed read API requires SELECT");
        List<Map<String, Term>> rows = new ArrayList<>();
        long bytes = 0;
        try (var execution = sink.connection().query(query)) {
            var result = execution.execSelect();
            while (result.hasNext()) {
                if (rows.size() == 100000) throw new IllegalStateException("Read result exceeds configured bound");
                var row = result.next(); Map<String, Term> decoded = new LinkedHashMap<>();
                for (String variable : result.getResultVars()) if (row.contains(variable)) {
                    var term = Term.from(row.get(variable));
                    long size = 0;
                    for (String text : new String[] { variable, term.kind(), term.lexical(), term.datatype(), term.language() }) {
                        if (text == null) continue;
                        if (text.length() > project.limits().maxRecordBytes()) throw new IllegalStateException("Typed term exceeds configured record bound");
                        size += text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    }
                    if (size > project.limits().maxRecordBytes() || (bytes += size) > MAX_READ_BYTES)
                        throw new IllegalStateException("Typed read exceeds bounded result bytes; page an explicitly ordered query");
                    decoded.put(variable, term);
                }
                rows.add(Map.copyOf(decoded));
            }
        }
        return List.copyOf(rows);
    }
    public long count(String sparql) {
        var rows = read(sparql);
        if (rows.size() != 1 || rows.getFirst().size() != 1) throw new IllegalStateException("Expected one typed count");
        var value = rows.getFirst().values().iterator().next();
        if (!"http://www.w3.org/2001/XMLSchema#integer".equals(value.datatype()))
            throw new IllegalStateException("Count must be an exact RDF integer");
        return Long.parseLong(value.lexical());
    }
    public Optional<GraphRecord> lookup(String iri) {
        Mapping.iri(iri);
        var query = new ParameterizedSparqlString("SELECT ?record WHERE { GRAPH ?graph { ?entity ?predicate ?record } }");
        query.setIri("graph", project.graph("canonical")); query.setIri("entity", iri);
        query.setIri("predicate", RdfMapper.CANONICAL + "record");
        try (var execution = sink.connection().query(query.asQuery())) {
            var result = execution.execSelect();
            if (!result.hasNext()) return Optional.empty();
            String record = result.next().getLiteral("record").getString();
            if (record.length() > project.limits().maxRecordBytes()
                || record.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > project.limits().maxRecordBytes())
                throw new IllegalStateException("Canonical lookup exceeds configured record bound");
            if (result.hasNext()) throw new IllegalStateException("Conflicting canonical entity versions");
            try { return Optional.of(Json.MAPPER.readValue(record, GraphRecord.class)); }
            catch (java.io.IOException e) { throw new IllegalStateException("Canonical record cannot be decoded", e); }
        }
    }
    private void authorization(String operation) {
        Approval.require(project, operation, List.of(project.graph("canonical"), project.graph("derived")));
    }
    private String graph(String role) { return NodeFmtLib.strNT(NodeFactory.createURI(project.graph(role))); }
    private String triples(Model model) {
        var text = new StringBuilder();
        model.getGraph().find().forEachRemaining(t -> text.append(NodeFmtLib.str(t)).append(" .\n"));
        return text.toString();
    }
    private String derivedRefresh() {
        return "DELETE WHERE { GRAPH " + graph("derived") + " { ?s ?p ?o } }; INSERT { GRAPH " + graph("derived")
            + " { ?s ?p ?d } } WHERE { GRAPH " + graph("canonical") + " { ?e a <" + RdfMapper.CANONICAL
            + "Edge> ; <" + RdfMapper.CANONICAL + "source> ?s ; <" + RdfMapper.CANONICAL
            + "destination> ?d ; <" + RdfMapper.CANONICAL + "domainPredicate> ?p } }";
    }
    public void create(GraphRecord record) {
        authorization("application-write");
        if (record.kind() == GraphRecord.Kind.edge
            && (lookup(mapper.vertex(record.source())).isEmpty() || lookup(mapper.vertex(record.destination())).isEmpty()))
            throw new IllegalArgumentException("Edge endpoints must already exist");
        var existing = lookup(mapper.entity(record));
        if (existing.isPresent()) {
            if (!existing.get().equals(record)) throw new IllegalStateException("Identity already owns different content");
            return;
        }
        try (var mapped = mapper.map(record)) {
            String entity = NodeFmtLib.strNT(NodeFactory.createURI(mapper.entity(record)));
            String endpointGuard = record.kind() == GraphRecord.Kind.edge
                ? "GRAPH " + graph("canonical") + " { " + NodeFmtLib.strNT(NodeFactory.createURI(mapper.vertex(record.source())))
                    + " a <" + RdfMapper.CANONICAL + "Vertex> . "
                    + NodeFmtLib.strNT(NodeFactory.createURI(mapper.vertex(record.destination()))) + " a <" + RdfMapper.CANONICAL + "Vertex> } "
                : "";
            UpdateRequest request = UpdateFactory.create("INSERT { GRAPH " + graph("canonical") + " { "
                + triples(mapped.canonical()) + " } } WHERE { " + endpointGuard
                + " FILTER NOT EXISTS { GRAPH " + graph("canonical") + " { " + entity + " ?p ?o } } }");
            UpdateFactory.create(derivedRefresh()).getOperations().forEach(request::add);
            authorization("application-write");
            sink.connection().update(request);
        }
        if (!lookup(mapper.entity(record)).equals(Optional.of(record))) throw new IllegalStateException("Create could not be confirmed");
    }
    public void replaceProperties(String entity, List<GraphRecord.Property> properties) {
        authorization("application-write");
        var old = lookup(entity).orElseThrow(() -> new IllegalArgumentException("Entity not found"));
        var updated = new GraphRecord(old.kind(), old.identity(), old.label(), old.source(), old.destination(),
            old.edgeScope(), properties, old.metadata(), old.provenance());
        replace(entity, old, updated);
    }
    public void insertProperty(String entity, GraphRecord.Property property) {
        var old = lookup(entity).orElseThrow(() -> new IllegalArgumentException("Entity not found"));
        if (property.id() == null) throw new IllegalArgumentException("Application property insertion requires a caller-assigned idempotent property identity");
        var existing = old.properties().stream().filter(p -> p.key().equals(property.key()) && property.id().equals(p.id())).findFirst();
        if (existing.isPresent()) {
            if (!existing.get().equals(property)) throw new IllegalStateException("Property identity already owns different content");
            return;
        }
        List<GraphRecord.Property> properties = new ArrayList<>(old.properties()); properties.add(property);
        authorization("application-write");
        replace(entity, old, new GraphRecord(old.kind(), old.identity(), old.label(), old.source(), old.destination(),
            old.edgeScope(), properties, old.metadata(), old.provenance()));
    }
    public void removeProperty(String entity, String key) {
        var old = lookup(entity).orElseThrow(() -> new IllegalArgumentException("Entity not found"));
        authorization("application-write");
        replace(entity, old, new GraphRecord(old.kind(), old.identity(), old.label(), old.source(), old.destination(),
            old.edgeScope(), old.properties().stream().filter(p -> !p.key().equals(key)).toList(), old.metadata(), old.provenance()));
    }
    private String deletion(String entity) {
        var query = new ParameterizedSparqlString("DELETE { GRAPH ?graph { ?entity ?p ?o . ?prop ?pp ?po . ?meta ?mp ?mo } }"
            + " WHERE { GRAPH ?graph { ?entity ?p ?o . OPTIONAL { ?entity ?propertyPredicate ?prop . ?prop ?pp ?po ."
            + " OPTIONAL { ?prop ?metaPredicate ?meta . ?meta ?mp ?mo } } } }");
        query.setIri("graph", project.graph("canonical")); query.setIri("entity", entity);
        query.setIri("propertyPredicate", RdfMapper.CANONICAL + "property");
        query.setIri("metaPredicate", RdfMapper.CANONICAL + "metaProperty");
        return query.asUpdate().toString();
    }
    private void replace(String entity, GraphRecord previous, GraphRecord updated) {
        try (var mapped = mapper.map(updated)) {
            var statement = new ParameterizedSparqlString("DELETE { GRAPH ?graph { ?entity ?p ?o . ?prop ?pp ?po . ?meta ?mp ?mo } }"
                + " INSERT { GRAPH ?graph { " + triples(mapped.canonical()) + " } }"
                + " WHERE { GRAPH ?graph { ?entity ?recordPredicate ?previous ; ?p ?o ."
                + " OPTIONAL { ?entity ?propertyPredicate ?prop . ?prop ?pp ?po . OPTIONAL { ?prop ?metaPredicate ?meta . ?meta ?mp ?mo } } } }");
            statement.setIri("graph", project.graph("canonical")); statement.setIri("entity", entity);
            statement.setIri("recordPredicate", RdfMapper.CANONICAL + "record"); statement.setLiteral("previous", Json.text(previous));
            statement.setIri("propertyPredicate", RdfMapper.CANONICAL + "property");
            statement.setIri("metaPredicate", RdfMapper.CANONICAL + "metaProperty");
            UpdateRequest request = statement.asUpdate();
            UpdateFactory.create(derivedRefresh()).getOperations().forEach(request::add);
            authorization("application-write");
            sink.connection().update(request);
        }
        if (!lookup(entity).equals(Optional.of(updated))) throw new IllegalStateException("Property replacement could not be confirmed");
    }
    public void deleteEdge(String edge) {
        authorization("application-delete");
        var record = lookup(edge);
        if (record.isEmpty()) return;
        if (record.get().kind() != GraphRecord.Kind.edge) throw new IllegalArgumentException("Edge identity required");
        authorization("application-delete");
        sink.connection().update(UpdateFactory.create(deletion(edge) + ";" + derivedRefresh()));
    }
    public void deleteVertex(String vertex, String incidentPolicy) {
        authorization("application-delete");
        if (!List.of("reject", "cascade-edges").contains(incidentPolicy)) throw new IllegalArgumentException("Explicit incident-edge policy required");
        var record = lookup(vertex);
        if (record.isEmpty()) return;
        if (record.get().kind() != GraphRecord.Kind.vertex) throw new IllegalArgumentException("Vertex identity required");
        var query = new ParameterizedSparqlString("SELECT DISTINCT ?edge WHERE { GRAPH ?graph {"
            + " ?edge a ?edgeClass . { ?edge ?source ?vertex } UNION { ?edge ?destination ?vertex } } }");
        query.setIri("graph", project.graph("canonical")); query.setIri("edgeClass", RdfMapper.CANONICAL + "Edge");
        query.setIri("source", RdfMapper.CANONICAL + "source"); query.setIri("destination", RdfMapper.CANONICAL + "destination");
        query.setIri("vertex", vertex); List<String> edges = new ArrayList<>();
        try (var execution = sink.connection().query(query.asQuery())) {
            var results = execution.execSelect();
            while (results.hasNext()) {
                if (edges.size() == 10000) throw new IllegalStateException("Incident-edge mutation exceeds bounded policy");
                edges.add(results.next().getResource("edge").getURI());
            }
        }
        if (!edges.isEmpty() && incidentPolicy.equals("reject")) throw new IllegalStateException("Vertex has incident edges");
        String guard = incidentPolicy.equals("reject")
            ? " FILTER NOT EXISTS { GRAPH ?graph { ?incident a ?edgeClass . { ?incident ?source ?vertex } UNION { ?incident ?destination ?vertex } } } "
            : "";
        String entities = incidentPolicy.equals("reject") ? " VALUES ?entity { ?vertex } "
            : " { VALUES ?entity { ?vertex } } UNION { ?entity a ?edgeClass ."
                + " { ?entity ?source ?vertex } UNION { ?entity ?destination ?vertex } } ";
        var deletion = new ParameterizedSparqlString("DELETE { GRAPH ?graph { ?entity ?p ?o . ?prop ?pp ?po . ?meta ?mp ?mo } }"
            + " WHERE { { SELECT ?eligible WHERE { VALUES ?eligible { ?vertex }"
            + " GRAPH ?graph { ?eligible ?recordPredicate ?previous . } " + guard + " } LIMIT 1 } "
            + " GRAPH ?graph { " + entities
            + " ?entity ?p ?o . OPTIONAL { ?entity ?propertyPredicate ?prop . ?prop ?pp ?po ."
            + " OPTIONAL { ?prop ?metaPredicate ?meta . ?meta ?mp ?mo } } } }");
        deletion.setIri("graph", project.graph("canonical")); deletion.setIri("vertex", vertex);
        deletion.setIri("edgeClass", RdfMapper.CANONICAL + "Edge"); deletion.setIri("source", RdfMapper.CANONICAL + "source");
        deletion.setIri("destination", RdfMapper.CANONICAL + "destination"); deletion.setIri("recordPredicate", RdfMapper.CANONICAL + "record");
        deletion.setLiteral("previous", Json.text(record.get())); deletion.setIri("propertyPredicate", RdfMapper.CANONICAL + "property");
        deletion.setIri("metaPredicate", RdfMapper.CANONICAL + "metaProperty");
        UpdateRequest request = deletion.asUpdate(); UpdateFactory.create(derivedRefresh()).getOperations().forEach(request::add);
        authorization("application-delete");
        sink.connection().update(request);
        if (lookup(vertex).isPresent()) throw new IllegalStateException("Concurrent mutation prevented vertex deletion");
    }
    public void close() { sink.close(); }
}
