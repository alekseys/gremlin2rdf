package io.github.gremlin2rdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceActivityTest {
    @Test void streamsBoundedValueFreeActivityWithoutConsumingTheTerminalAcknowledgement() throws Exception {
        var engine=new Engine();
        var seen=new ArrayList<SourceActivity>();engine.activity(seen::add);
        var requests=new ArrayList<SourceRequestActivity>();engine.requestActivity(requests::add);
        var activity=new SourceActivity("source-progress","source-enumeration-edge-page",12,40,20,1500);
        var request=new SourceRequestActivity("source-request-progress","source-rate-wait",1000,0);
        String reply=Json.text(activity)+"\n"+Json.text(request)+"\n"+Json.text(Map.of("status","succeeded","vertices",12))+"\n";
        var terminal=Main.sourceReply(new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)),engine);
        assertEquals(List.of(activity),seen);
        assertEquals(List.of(request),requests);
        assertEquals("succeeded",Json.MAPPER.readTree(terminal).path("status").asText());
    }
    @Test void rejectsRawSourceFieldsInvalidStagesTruncationAndOversizedFrames() {
        for(String text:List.of(
            "{\"type\":\"source-progress\",\"stage\":\"private-value\",\"vertices\":0,\"edges\":0,\"reservedRequests\":0,\"elapsedMillis\":0}\n",
            "{\"type\":\"source-progress\",\"stage\":\"source-enumeration-edge-page\",\"vertices\":0,\"edges\":0,\"reservedRequests\":0,\"elapsedMillis\":0,\"query\":\"private\"}\n",
            "{\"status\":\"succeeded\"}", "x".repeat(8193)+"\n",
            "{\"status\":\"succeeded\"}\n{\"status\":\"succeeded\"}\n")) {
            var failure=assertThrows(Diagnostics.Failure.class,()->Main.sourceReply(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),new Engine()));
            assertEquals("SOURCE_PROTOCOL_INVALID",Diagnostics.failure(failure,"source-worker-protocol").get("code"));
            assertFalse(Json.text(Diagnostics.failure(failure,"source-worker-protocol")).contains("private"));
        }
    }
}
