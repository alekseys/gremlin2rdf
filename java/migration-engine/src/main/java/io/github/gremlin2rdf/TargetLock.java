package io.github.gremlin2rdf;

import java.time.Instant;
import java.util.*;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;

final class TargetLock implements AutoCloseable {
    private final RDFConnection connection;
    private final Project project;
    private final String owner = UUID.randomUUID().toString();
    private final long pid = ProcessHandle.current().pid();
    private final String started = ProcessHandle.current().info().startInstant().orElseThrow().toString();
    private final String host;
    TargetLock(Project project, RDFConnection connection) {
        this.project = project; this.connection = connection;
        try { host = Json.hash(java.net.InetAddress.getLocalHost().getHostName()); }
        catch (java.net.UnknownHostException e) { throw new IllegalStateException("Worker host ownership cannot be established", e); }
        var initial = parameters("INSERT { GRAPH ?graph { ?lock ?ownerPredicate ?owner ; ?hostPredicate ?host ;"
            + " ?pidPredicate ?pid ; ?startPredicate ?start ; ?inputPredicate ?input } }"
            + " WHERE { FILTER NOT EXISTS { GRAPH ?graph { ?lock ?p ?o } } }");
        var existing = acquire(initial, null);
        if (existing.get("owner").equals(owner)) return;
        if (!existing.get("host").equals(host) || !existing.get("input").equals(project.inputHash()))
            throw new IllegalStateException("Run owned by another host or input version; explicit verified recovery required");
        long previousPid = Long.parseLong(existing.get("pid"));
        Optional<ProcessHandle> previous = ProcessHandle.of(previousPid);
        if (previous.isPresent() && previous.get().isAlive()
            && previous.get().info().startInstant().map(i -> i.toString().equals(existing.get("start"))).orElse(true))
            throw new IllegalStateException("Another live worker owns this run");
        var recovery = parameters("DELETE { GRAPH ?graph { ?lock ?p ?o } }"
            + " INSERT { GRAPH ?graph { ?lock ?ownerPredicate ?owner ; ?hostPredicate ?host ;"
            + " ?pidPredicate ?pid ; ?startPredicate ?start ; ?inputPredicate ?input } }"
            + " WHERE { GRAPH ?graph { ?lock ?ownerPredicate ?previousOwner ; ?hostPredicate ?host ;"
            + " ?pidPredicate ?observedPid ; ?startPredicate ?previousStart ; ?inputPredicate ?input ; ?p ?o ."
            + " FILTER (?observedPid = ?previousPid)"
            + " FILTER NOT EXISTS { ?lock ?ownerPredicate ?otherOwner . FILTER (?otherOwner != ?previousOwner) } } }");
        recovery.setLiteral("previousOwner", existing.get("owner"));
        recovery.setLiteral("previousPid", previousPid);
        recovery.setLiteral("previousStart", existing.get("start"));
        if (!acquire(recovery, existing.get("owner")).get("owner").equals(owner))
            throw new IllegalStateException("Target run lock acquisition lost race");
    }
    private Map<String, String> acquire(ParameterizedSparqlString update, String previousOwner) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt <= project.limits().maxRetries(); attempt++) {
            authorize();
            try { connection.update(update.asUpdate()); }
            catch (HttpException | QueryExceptionHTTP e) { if (!TargetHttp.transientFailure(e)) throw e; failure = e; }
            try {
                var observed = read();
                if (observed.isPresent()) {
                    var current = observed.get();
                    if (current.get("owner").equals(owner) || previousOwner == null) return current;
                    if (!current.get("owner").equals(previousOwner)) throw new IllegalStateException("Conditional target lock recovery lost ownership race");
                }
            } catch (HttpException | QueryExceptionHTTP e) { if (!TargetHttp.transientFailure(e)) throw e; failure = e; }
            if (attempt < project.limits().maxRetries()) backoff(attempt);
        }
        throw new IllegalStateException("Target run lock could not be confirmed; ambiguous ownership preserved", failure);
    }
    private void authorize() {
        Approval.require(project, "target-write", List.of(project.graph("canonical"), project.graph("derived"), project.graph("metadata")));
    }
    private static void backoff(int attempt) {
        try { Thread.sleep(Math.min(2000, 100L << Math.min(attempt, 5))); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Target ownership reconciliation interrupted", e); }
    }
    private ParameterizedSparqlString parameters(String text) {
        var query = new ParameterizedSparqlString(text);
        query.setIri("graph", project.graph("metadata"));
        query.setIri("lock", project.graph("metadata") + ":run-lock");
        query.setIri("ownerPredicate", RdfMapper.CANONICAL + "lockOwner");
        query.setIri("hostPredicate", RdfMapper.CANONICAL + "lockHost");
        query.setIri("pidPredicate", RdfMapper.CANONICAL + "lockPid");
        query.setIri("startPredicate", RdfMapper.CANONICAL + "lockStart");
        query.setIri("inputPredicate", RdfMapper.CANONICAL + "lockInputHash");
        if (!text.startsWith("SELECT")) {
            query.setLiteral("owner", owner); query.setLiteral("host", host); query.setLiteral("pid", pid);
            query.setLiteral("start", started); query.setLiteral("input", project.inputHash());
        }
        return query;
    }
    private Optional<Map<String, String>> read() {
        var query = parameters("SELECT DISTINCT ?owner ?host ?pid ?start ?input WHERE { GRAPH ?graph {"
            + " ?lock ?p ?o . OPTIONAL { ?lock ?ownerPredicate ?owner } OPTIONAL { ?lock ?hostPredicate ?host }"
            + " OPTIONAL { ?lock ?pidPredicate ?pid } OPTIONAL { ?lock ?startPredicate ?start }"
            + " OPTIONAL { ?lock ?inputPredicate ?input } } }");
        try (var execution = connection.query(query.asQuery())) {
            var results = execution.execSelect();
            if (!results.hasNext()) return Optional.empty();
            var row = results.next(); var values = new HashMap<String, String>();
            for (String key : List.of("owner", "host", "pid", "start", "input")) {
                if (!row.contains(key) || !row.get(key).isLiteral())
                    throw new IllegalStateException("Incomplete or invalid target ownership marker; explicit recovery required");
                values.put(key, row.getLiteral(key).getLexicalForm());
            }
            if (results.hasNext()) throw new IllegalStateException("Conflicting target run ownership");
            try {
                UUID.fromString(values.get("owner"));
                Instant.parse(values.get("start"));
                if (Long.parseLong(values.get("pid")) <= 0
                    || !values.get("host").matches("[0-9a-f]{64}") || !values.get("input").matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid ownership identity");
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                throw new IllegalStateException("Malformed target ownership identity; explicit recovery required", e);
            }
            return Optional.of(Map.copyOf(values));
        }
    }
    public void close() {
        var release = parameters("DELETE { GRAPH ?graph { ?lock ?p ?o } }"
            + " WHERE { GRAPH ?graph { ?lock ?ownerPredicate ?owner ; ?hostPredicate ?host ; ?pidPredicate ?pid ;"
            + " ?startPredicate ?start ; ?inputPredicate ?input ; ?p ?o ."
            + " FILTER NOT EXISTS { ?lock ?ownerPredicate ?otherOwner . FILTER (?otherOwner != ?owner) } } }");
        RuntimeException failure = null;
        for (int attempt = 0; attempt <= project.limits().maxRetries(); attempt++) {
            try {
                var current = read();
                if (current.isEmpty() || !current.get().get("owner").equals(owner)) return;
            } catch (HttpException | QueryExceptionHTTP e) {
                if (!TargetHttp.transientFailure(e)) throw e;
                failure = e;
                if (attempt < project.limits().maxRetries()) backoff(attempt);
                continue;
            }
            authorize();
            try { connection.update(release.asUpdate()); }
            catch (HttpException | QueryExceptionHTTP e) { if (!TargetHttp.transientFailure(e)) throw e; failure = e; }
            try {
                var remaining = read();
                if (remaining.isEmpty() || !remaining.get().get("owner").equals(owner)) return;
            } catch (HttpException | QueryExceptionHTTP e) { if (!TargetHttp.transientFailure(e)) throw e; failure = e; }
            if (attempt < project.limits().maxRetries()) backoff(attempt);
        }
        throw new IllegalStateException("Target lock release could not be confirmed; ownership state retained for reconciliation", failure);
    }
}
