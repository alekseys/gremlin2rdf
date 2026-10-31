package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.DatasetFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpFailureTest {
    @TempDir Path root;
    @ParameterizedTest
    @ValueSource(strings = {"before-503", "after-503", "after-disconnect", "before-timeout", "after-timeout",
        "lock-before-503", "lock-after-503", "lock-read-503", "release-before-503", "release-after-503", "release-read-503", "reconcile-403"})
    void ambiguousHttpBatchNeverLosesOrDuplicatesAcknowledgedRecords(String fault) throws Exception {
        var dataset = DatasetFactory.createTxnMem();
        var server = FusekiServer.create().loopback(true).port(0).add("/test", dataset).build().start();
        var executor = Executors.newCachedThreadPool();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean injected = new AtomicBoolean();
        AtomicBoolean committedFault = new AtomicBoolean();
        AtomicInteger batchAttempts = new AtomicInteger();
        AtomicBoolean released = new AtomicBoolean();
        try (var http = HttpClient.newHttpClient()) {
            proxy.setExecutor(executor);
            proxy.createContext("/", exchange -> {
                try (exchange) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    String text = new String(body, StandardCharsets.UTF_8) + " " + exchange.getRequestURI().getRawQuery();
                    boolean update = exchange.getRequestURI().getPath().endsWith("/update");
                    if (update && text.contains("batchHash")) batchAttempts.incrementAndGet();
                    if (!update && injected.get() && fault.equals("reconcile-403") && text.contains("batchHash")) {
                        exchange.sendResponseHeaders(403, -1); return;
                    }
                    boolean candidate = fault.equals("lock-read-503") ? !update && text.contains("lockOwner")
                        : fault.equals("release-read-503") ? released.get() && !update && text.contains("lockOwner")
                        : update && (fault.startsWith("lock-") ? text.contains("lockOwner") && text.contains("INSERT")
                        : fault.startsWith("release-") ? text.contains("lockOwner") && text.contains("DELETE") && !text.contains("INSERT")
                        : text.contains("batchHash"));
                    boolean fail = candidate && injected.compareAndSet(false, true);
                    if (fail && (fault.endsWith("before-503") || fault.endsWith("read-503") || fault.equals("reconcile-403"))) { exchange.sendResponseHeaders(503, -1); return; }
                    if (fail && fault.equals("before-timeout")) {
                        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return;
                    }
                    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + exchange.getRequestURI()))
                        .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
                    for (String header : List.of("Content-Type", "Accept"))
                        if (exchange.getRequestHeaders().getFirst(header) != null) request.header(header, exchange.getRequestHeaders().getFirst(header));
                    var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (update && text.contains("lockOwner") && text.contains("DELETE") && !text.contains("INSERT")
                        && response.statusCode() >= 200 && response.statusCode() < 300) released.set(true);
                    if (fail) committedFault.set(response.statusCode() >= 200 && response.statusCode() < 300);
                    if (fail && fault.endsWith("after-503")) { exchange.sendResponseHeaders(503, -1); return; }
                    if (fail && fault.equals("after-disconnect")) return;
                    if (fail && fault.equals("after-timeout")) {
                        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return;
                    }
                    response.headers().firstValue("Content-Type").ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
                    exchange.sendResponseHeaders(response.statusCode(), response.statusCode() == 204 ? -1 : response.body().length);
                    if (response.statusCode() != 204) exchange.getResponseBody().write(response.body());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            proxy.start();
            Project base = FusekiIntegrationTest.remote(RecoveryTest.project("fault-" + fault), proxy.getAddress().getPort());
            Project project = RecoveryTest.approved(new Project(base.schemaVersion(), base.migrationKind(), base.baselineRepositorySha(), base.runId(),
                base.protocolVersion(), base.workerVersion(), base.mapping(), base.source(), base.target(),
                new Project.Limits(10, 1048576, 262144, 2000, 3, 10, 1, 0), base.consistency(), base.phases(),
                base.approvals(), base.applicationHashes(), base.artifacts()));
            Path records = root.resolve("records.ndjson"), state = root.resolve("state");
            Synthetic.generate(new Synthetic.Profile(1, 42, 20, 40, 4), records);
            try (var sink = new Sink(project)) {
                if (fault.equals("reconcile-403")) {
                    var denied = assertThrows(org.apache.jena.sparql.engine.http.QueryExceptionHTTP.class,
                        () -> new Engine().load(project, records, state, sink, checkpoint -> {}, batch -> {}));
                    assertEquals(403, denied.getStatusCode());
                    assertEquals(1, batchAttempts.get());
                    assertFalse(Files.exists(state.resolve("checkpoint.json")));
                    var empty = new org.apache.jena.query.ParameterizedSparqlString("ASK { GRAPH ?graph { ?s ?p ?o } }");
                    empty.setIri("graph", project.graph("canonical"));
                    assertFalse(sink.connection().queryAsk(empty.asQuery()));
                } else {
                    assertEquals("succeeded", new Engine().load(project, records, state, sink, checkpoint -> {}, batch -> {}).status());
                    if (!fault.startsWith("release-")) assertTrue(injected.get());
                    assertEquals("succeeded", Validation.validate(project, records, sink).status());
                    assertEquals(60, new Engine().load(project, records, state, sink, checkpoint -> {}, batch -> {}).records());
                    assertEquals("succeeded", Functional.run(project, records, sink).status());
                }
            }
            assertTrue(injected.get());
            if (fault.contains("after")) assertTrue(committedFault.get(), "Fault must follow a successful real update, not a query");
        } finally { proxy.stop(0); executor.shutdownNow(); server.stop(); }
    }
    @Test void remoteCompoundUpdateRollsBackAnExecutionFailureNotJustASyntaxError() {
        var server = FusekiServer.create().loopback(true).port(0).add("/test", DatasetFactory.createTxnMem()).build().start();
        try {
            var project = FusekiIntegrationTest.remote(RecoveryTest.project("rollback"), server.getPort());
            try (var sink = new Sink(project)) {
                String graph = project.graph("canonical");
                assertThrows(RuntimeException.class, () -> sink.connection().update(
                    "INSERT DATA { GRAPH <" + graph + "> { <urn:fixture:first> <urn:fixture:p> 1 } };"
                        + " LOAD <http://localhost:" + server.getPort() + "/missing-fixture.ttl> INTO GRAPH <" + graph + ">"));
                assertFalse(sink.connection().queryAsk("ASK { GRAPH <" + graph + "> { ?s ?p ?o } }"));
            }
        } finally { server.stop(); }
    }
}
