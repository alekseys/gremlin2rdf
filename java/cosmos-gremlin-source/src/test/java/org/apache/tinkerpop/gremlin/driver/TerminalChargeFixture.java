package org.apache.tinkerpop.gremlin.driver;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.concurrent.*;
import org.apache.tinkerpop.gremlin.driver.message.*;
import org.apache.tinkerpop.gremlin.driver.ser.GraphSONMessageSerializerV2d0;

/** Offline fixture using the pinned driver's real serializer, response handler, queue and ResultSet. */
public final class TerminalChargeFixture {
    public static Map<String,Object> terminalAttributes(Map<String,Object> terminal) throws Exception {
        return terminalAttributes(Map.of("x-ms-request-charge",12.5,"x-ms-total-request-charge",12.5),terminal);
    }
    public static Map<String,Object> terminalAttributes(Map<String,Object> partialAttributes,Map<String,Object> terminal) throws Exception {
        var id=UUID.randomUUID();
        var completed=new CompletableFuture<Void>();
        var queue=new ResultQueue(new LinkedBlockingQueue<>(),completed);
        var pending=new ConcurrentHashMap<UUID,ResultQueue>();pending.put(id,queue);
        var channel=new EmbeddedChannel(new Handler.GremlinResponseHandler(pending));
        var executor=Executors.newSingleThreadExecutor();
        try {
            var serializer=new GraphSONMessageSerializerV2d0();
            var partial=ResponseMessage.build(id).code(ResponseStatusCode.PARTIAL_CONTENT)
                .statusAttributes(partialAttributes)
                .result(List.of(List.of())).create();
            channel.writeInbound(serializer.deserializeResponse(serializer.serializeResponseAsString(partial)));
            var last=ResponseMessage.build(id).code(ResponseStatusCode.NO_CONTENT).statusAttributes(terminal).create();
            channel.writeInbound(serializer.deserializeResponse(serializer.serializeResponseAsString(last)));
            var results=new ResultSet(queue,executor,completed,RequestMessage.build("eval").create(),null);
            if(results.all().get(2,TimeUnit.SECONDS).size()!=1)throw new AssertionError("Expected one folded empty page");
            return results.statusAttributes().get(2,TimeUnit.SECONDS);
        } finally { channel.finishAndReleaseAll();executor.shutdownNow(); }
    }
}
