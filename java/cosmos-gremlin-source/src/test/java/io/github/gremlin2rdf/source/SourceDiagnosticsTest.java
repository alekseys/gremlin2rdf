package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import org.apache.tinkerpop.gremlin.driver.exception.ResponseException;
import org.apache.tinkerpop.gremlin.driver.message.ResponseStatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceDiagnosticsTest {
    @TempDir Path root;
    @Test void actualPinnedResponseRetainsSubstepAndNumericStatusesAcrossAcknowledgement() throws Exception {
        var response=new ResponseException(ResponseStatusCode.SERVER_ERROR_SCRIPT_EVALUATION,"private-query-secret",
            List.of("private-class"),"private-stack",Map.of("x-ms-status-code","400","x-ms-substatus-code","1004",
                "private-binding","private-partition-value"));
        var diagnostic=SourceDiagnostics.failure(new SourceDiagnostics.Context("source-enumeration-vertex-page",
            new SourceRequests.Failure(false,Map.of(),response)),"source-operation");
        assertEquals("SOURCE_SCRIPT_REJECTED",diagnostic.get("code"));
        assertEquals("source-keyset-page-v1",diagnostic.get("templateId"));
        assertEquals(597,diagnostic.get("gremlinStatus"));assertEquals(400,diagnostic.get("sourceStatus"));
        assertEquals(1004,diagnostic.get("sourceSubstatus"));
        var acknowledged=Diagnostics.failure(Diagnostics.acknowledged(Json.MAPPER.valueToTree(diagnostic),"source-operation"),"execution");
        assertEquals(diagnostic,acknowledged);assertFalse(Json.text(acknowledged).contains("private-"));
    }
    @Test void enumerationFailureKeepsGlobalCountContextAndCreatesNoCompletedInventory() throws Exception {
        var manifest=root.resolve("identities.ndjson");java.nio.file.Files.writeString(manifest,"");
        var source=new Project.Source("fixture","localhost",443,"db","g","/pk",null,"verified-identity-manifest",
            "identities.ndjson",SafeFiles.fileHash(manifest),null,null,null,true);
        var p=new Project(1,"cosmos-gremlin-to-jena","0".repeat(40),"fixture",1,"0.1.0",Mapping.standard(),
            source,null,Project.Limits.standard(),"immutable",Map.of(),List.of(),Map.of(),List.of());
        var plan=new Enumeration.Plan(1,List.of(GraphRecord.Value.string("fixture")),100,20,10000);
        var failure=assertThrows(Exception.class,()->Enumeration.run(p,plan,root.resolve("out"),new Enumeration.Reader(){
            public List<String> vertices(GraphRecord.Value partition,String after,int limit){throw new AssertionError();}
            public List<String> edges(GraphRecord.Value partition,String owner,String after,int limit){throw new AssertionError();}
            public long count(GraphRecord.Kind kind,GraphRecord.Value partition) throws Exception {
                throw new ResponseException(ResponseStatusCode.SERVER_ERROR,"private-provider-message");
            }
        }));
        var diagnostic=SourceDiagnostics.failure(failure,"source-operation");
        assertEquals("source-enumeration-global-vertex-count",diagnostic.get("stage"));
        assertEquals("SOURCE_PROVIDER_CAUSE_UNKNOWN",diagnostic.get("code"));
        assertFalse(java.nio.file.Files.exists(root.resolve("out/manifest.json")));
        assertTrue(java.nio.file.Files.exists(root.resolve("out/checkpoint.json")));
        assertFalse(Json.text(diagnostic).contains("private-provider-message"));
    }
    @Test void observedAuthThrottleAndMalformedStatusesAreNotGuessedFromMessages() {
        for (var entry:Map.of(ResponseStatusCode.UNAUTHORIZED,"ENDPOINT_AUTHENTICATION",
            ResponseStatusCode.FORBIDDEN,"ENDPOINT_AUTHORIZATION",
            ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS,"SOURCE_REQUEST_REJECTED").entrySet()) {
            var response=new ResponseException(entry.getKey(),"private",List.of(),"private",
                Map.of("x-ms-status-code","private","x-ms-substatus-code","-1"));
            var report=SourceDiagnostics.failure(response,"source-enumeration-edge-page");
            assertEquals(entry.getValue(),report.get("code"));assertFalse(report.containsKey("sourceStatus"));
            assertFalse(report.containsKey("sourceSubstatus"));
        }
        var throttle=new ResponseException(ResponseStatusCode.SERVER_ERROR,"private",List.of(),"private",Map.of("x-ms-status-code",429));
        assertEquals("ENDPOINT_THROTTLED",SourceDiagnostics.failure(throttle,"source-request").get("code"));
    }
    @Test void missingChargeStillBlocksRetriesWhilePreservingReturnedProviderStatus() {
        var requests=new SourceRequests(2,1000,"1000",millis->{throw new AssertionError("No retries without charge");});
        var response=new ResponseException(ResponseStatusCode.SERVER_ERROR_SCRIPT_EVALUATION,"private");
        var failure=assertThrows(Exception.class,()->requests.execute(()->{
            throw new SourceRequests.Failure(false,Map.of(),response);
        }));
        var report=SourceDiagnostics.failure(new SourceDiagnostics.Context("source-enumeration-edge-page",failure),"source-operation");
        assertEquals("SOURCE_RU_UNOBSERVABLE",report.get("code"));assertEquals(597,report.get("gremlinStatus"));
        assertEquals("source-enumeration-edge-page",report.get("stage"));assertEquals(0,requests.retries());
    }
}
