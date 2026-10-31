package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import org.apache.jena.atlas.web.HttpException;
import org.junit.jupiter.api.Test;

class DeadlineHttpClientTest {
    @Test void exactBodyBoundSucceedsAndOversizedBodiesAreNotTransient() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] bytes = new byte[exchange.getRequestURI().getPath().equals("/exact") ? 1024 : 1025];
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try (var client = new DeadlineHttpClient(2000, null, null, 1024)) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(1024, client.send(HttpRequest.newBuilder(URI.create(base + "/exact")).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body().length);
            var failure = assertThrows(IOException.class, () -> client.send(
                HttpRequest.newBuilder(URI.create(base + "/oversized")).build(), HttpResponse.BodyHandlers.ofByteArray()));
            assertTrue(TargetHttp.responseExceeded(failure));
            var wrapped = new HttpException(-1, "bounded failure", null, failure);
            assertFalse(TargetHttp.transientFailure(wrapped));
            assertEquals("TARGET_RESPONSE_BOUND", Main.diagnostic(failure).get("code"));
        } finally { server.stop(0); }
    }
    @Test void streamingBodyCannotOutliveTheConfiguredDeadlineAfterHeaders() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try (var client = new DeadlineHttpClient(300, null, null, 1024)) {
            var response = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build(), HttpResponse.BodyHandlers.ofInputStream());
            long start = System.nanoTime();
            try (var body = response.body()) { assertThrows(IOException.class, body::readAllBytes); }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    @Test void unsafeMemorySettingsFailBeforeOpeningAnyEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new Project.Limits(1001, 1048576, 262144, 1000, 0, 10, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Project.Limits(10, 8388609, 262144, 1000, 0, 10, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Project.Limits(10, 8388608, 1048577, 1000, 0, 10, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Project.Limits(10, 1048576, 262144, 1000, 0, 1001, 1, 0));
    }
}
