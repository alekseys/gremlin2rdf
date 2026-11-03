package io.github.gremlin2rdf.source;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import org.junit.jupiter.api.Test;

class SourceCompressionTest {
    @Test void negotiatedNonDefaultWebSocketCompressionHasItsPinnedRuntimeDependency() {
        var encoder = ZlibCodecFactory.newZlibEncoder(ZlibWrapper.NONE, 6, 12, 8);
        assertEquals("JZlibEncoder", encoder.getClass().getSimpleName());
        var channel = new EmbeddedChannel(encoder);
        try {
            assertTrue(channel.writeOutbound(Unpooled.copiedBuffer("private-fixture", StandardCharsets.UTF_8)));
            ByteBuf compressed = channel.readOutbound();
            try { assertTrue(compressed.isReadable()); }
            finally { compressed.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }
}
