package io.github.gremlin2rdf.source;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceTlsTest {
    @TempDir Path root;
    @Test void privateHandshakeAcceptsTrustedHostnameAndRejectsWrongHostnameAndUntrustedCa() throws Exception {
        Path store = root.resolve("fixture.p12"), certificate = root.resolve("fixture.pem");
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        run(executable, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-keystore", store.toString(), "-storetype", "PKCS12",
            "-storepass", "fixture-only-password", "-keypass", "fixture-only-password", "-noprompt");
        run(executable, "-exportcert", "-rfc", "-alias", "fixture", "-keystore", store.toString(),
            "-storepass", "fixture-only-password", "-file", certificate.toString());
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "fixture-only-password".toCharArray()); }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "fixture-only-password".toCharArray());
        var context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, null);
        assertTrue(handshake(context, "localhost", certificate.toString()));
        assertFalse(handshake(context, "wrong.invalid", certificate.toString()));
        assertFalse(handshake(context, "localhost", null));
    }
    private static void run(String... command) throws Exception {
        var process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new java.io.IOException("Fixture certificate generation deadline exceeded"); }
        assertEquals(0, process.exitValue(), "Fixture certificate generation failed");
    }
    private static boolean handshake(SSLContext serverContext, String verifiedHost, String ca) throws Exception {
        try (var server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(10000);
            var accepted = executor.submit(() -> {
                try (var socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(10000);
                    try { socket.startHandshake(); return true; }
                    catch (SSLException e) { return false; }
                }
            });
            var group = new NioEventLoopGroup(1);
            SslHandler handler = new SourceTls(verifiedHost, server.getLocalPort(), ca).newHandler(io.netty.buffer.UnpooledByteBufAllocator.DEFAULT);
            Channel channel = null;
            try {
                channel = new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(handler).addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) { context.close(); }
                            });
                        }
                    }).connect(server.getInetAddress(), server.getLocalPort()).sync().channel();
                assertTrue(handler.handshakeFuture().await(10, TimeUnit.SECONDS), "Private TLS handshake timed out");
                boolean success = handler.handshakeFuture().isSuccess();
                if (!success) assertNotNull(handler.handshakeFuture().cause());
                if (success) assertTrue(accepted.get(10, TimeUnit.SECONDS));
                return success;
            } finally {
                if (channel != null) channel.close().sync();
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }
}
