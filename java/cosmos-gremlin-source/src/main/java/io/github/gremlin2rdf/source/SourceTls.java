package io.github.gremlin2rdf.source;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.*;
import javax.net.ssl.*;
import java.io.File;
import java.util.List;

final class SourceTls extends SslContext {
    private final SslContext delegate;
    private final String host;
    private final int port;
    SourceTls(String host, int port, String caFile) throws SSLException {
        var builder = SslContextBuilder.forClient().sslProvider(SslProvider.JDK);
        if (caFile != null) builder.trustManager(new File(caFile));
        delegate = builder.build(); this.host = host; this.port = port;
    }
    @Override public boolean isClient() { return true; }
    @Override public List<String> cipherSuites() { return delegate.cipherSuites(); }
    @Override public ApplicationProtocolNegotiator applicationProtocolNegotiator() { return delegate.applicationProtocolNegotiator(); }
    @Override public SSLSessionContext sessionContext() { return delegate.sessionContext(); }
    @Override public SSLEngine newEngine(ByteBufAllocator allocator) {
        var engine = delegate.newEngine(allocator, host, port);
        var parameters = engine.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
        engine.setSSLParameters(parameters); return engine;
    }
    @Override public SSLEngine newEngine(ByteBufAllocator allocator, String peerHost, int peerPort) { return newEngine(allocator); }
}
