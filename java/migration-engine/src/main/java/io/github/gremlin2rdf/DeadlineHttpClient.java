package io.github.gremlin2rdf;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import javax.net.ssl.*;

final class DeadlineHttpClient extends HttpClient {
    private final HttpClient delegate;
    private final Duration deadline;
    private final String bearer;
    private final long responseLimit;
    private final ScheduledThreadPoolExecutor timer = scheduler();
    private static ScheduledThreadPoolExecutor scheduler() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "graph-http-deadline"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    static final class ResponseLimit extends IOException {
        ResponseLimit() { super("Target response exceeds bounded byte capacity"); }
    }
    DeadlineHttpClient(int timeoutMillis, String bearer, String customCaPath) {
        this(timeoutMillis, bearer, customCaPath, 33554432);
    }
    DeadlineHttpClient(int timeoutMillis, String bearer, String customCaPath, long responseLimit) {
        if (responseLimit < 1) throw new IllegalArgumentException("Positive HTTP response bound required");
        this.responseLimit = responseLimit;
        deadline = Duration.ofMillis(timeoutMillis); this.bearer = bearer;
        var builder = HttpClient.newBuilder().connectTimeout(deadline).followRedirects(Redirect.NEVER);
        if (customCaPath != null) {
            try {
                var path = java.nio.file.Path.of(customCaPath);
                if (java.nio.file.Files.size(path) > 1048576) throw new IOException("CA file exceeds bound");
                var store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); store.load(null, null);
                try (var input = java.nio.file.Files.newInputStream(path)) {
                    int index = 0;
                    for (var certificate : java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(input))
                        store.setCertificateEntry("approved-ca-" + index++, certificate);
                    if (index == 0) throw new IOException("CA certificates required");
                }
                var manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); manager.init(store);
                var context = SSLContext.getInstance("TLS"); context.init(null, manager.getTrustManagers(), null);
                builder.sslContext(context);
            } catch (IOException | java.security.GeneralSecurityException e) { throw new IllegalArgumentException("Approved custom CA could not be loaded", e); }
        }
        delegate = builder.build();
    }
    private HttpRequest request(HttpRequest request) {
        var builder = HttpRequest.newBuilder(request, (name, value) -> true).timeout(deadline);
        if (bearer != null) builder.setHeader("Authorization", "Bearer " + bearer);
        return builder.build();
    }
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        return delegate.send(request(request), bounded(handler));
    }
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return delegate.sendAsync(request(request), bounded(handler));
    }
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) { return delegate.sendAsync(request(request), bounded(handler), push); }
    private <T> HttpResponse.BodyHandler<T> bounded(HttpResponse.BodyHandler<T> handler) {
        long expires = System.nanoTime() + deadline.toNanos();
        return info -> new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<T> downstream = handler.apply(info);
            private final java.util.concurrent.atomic.AtomicBoolean complete = new java.util.concurrent.atomic.AtomicBoolean();
            private Flow.Subscription upstream;
            private ScheduledFuture<?> timeout;
            private long bytes;
            public CompletionStage<T> getBody() { return downstream.getBody(); }
            public void onSubscribe(Flow.Subscription subscription) {
                upstream = subscription;
                downstream.onSubscribe(new Flow.Subscription() {
                    public void request(long count) { subscription.request(count); }
                    public void cancel() {
                        complete.set(true); if (timeout != null) timeout.cancel(false); subscription.cancel();
                    }
                });
                timeout = timer.schedule(() -> fail(new IOException("Target response body deadline exceeded")),
                    Math.max(0, expires - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (complete.get()) timeout.cancel(false);
            }
            public synchronized void onNext(java.util.List<java.nio.ByteBuffer> items) {
                if (complete.get()) return;
                for (var item : items) {
                    if (item.remaining() > responseLimit - bytes) { fail(new ResponseLimit()); return; }
                    bytes += item.remaining();
                }
                downstream.onNext(items);
            }
            public void onError(Throwable error) { fail(error); }
            public synchronized void onComplete() {
                if (complete.compareAndSet(false, true)) {
                    if (timeout != null) timeout.cancel(false); downstream.onComplete();
                }
            }
            private synchronized void fail(Throwable error) {
                if (complete.compareAndSet(false, true)) {
                    if (timeout != null) timeout.cancel(false);
                    if (upstream != null) upstream.cancel();
                    downstream.onError(error);
                }
            }
        };
    }
    public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    public Redirect followRedirects() { return delegate.followRedirects(); }
    public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    public SSLContext sslContext() { return delegate.sslContext(); }
    public SSLParameters sslParameters() { return delegate.sslParameters(); }
    public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    public Version version() { return delegate.version(); }
    public Optional<Executor> executor() { return delegate.executor(); }
    public void close() {
        try { delegate.close(); }
        finally { timer.shutdownNow(); }
    }
}
