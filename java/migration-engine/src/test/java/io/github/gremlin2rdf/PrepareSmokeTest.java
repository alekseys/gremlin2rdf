package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.atlas.web.HttpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrepareSmokeTest {
    @TempDir Path root;
    private Project source(Project p) {
        return new Project(p.schemaVersion(),p.migrationKind(),p.baselineRepositorySha(),p.runId(),p.protocolVersion(),
            p.workerVersion(),p.mapping(),new Project.Source("fixture","fixture.invalid",443,"db","graph","/pk",
                "env:GRAPH_PREPARE_ABSENT_TEST_KEY","verified-identity-manifest","missing.ndjson","0".repeat(64),
                "missing-compatibility.json","100",null,false),p.target(),p.limits(),p.consistency(),p.phases(),
                p.approvals(),p.applicationHashes(),p.artifacts());
    }
    @Test void independentEndpointFailureDoesNotHideSourceOrInventoryChecks() {
        var calls=new AtomicInteger();
        var result=Preflight.run(source(RecoveryTest.project("prepare")),root,new Preflight.Connections(){
            public boolean target(Project p){throw new HttpException(401,"private-token-and-query");}
            public Map<String,Object> source(Project p){calls.incrementAndGet();return Map.of("status","succeeded");}
        });
        assertEquals(1,calls.get());assertEquals("blocked",result.get("status"));
        var text=Json.text(result);
        assertTrue(text.contains("ENDPOINT_AUTHENTICATION"));assertTrue(text.contains("SOURCE_FIDELITY_EVIDENCE_REQUIRED"));
        assertTrue(text.contains("SOURCE_INVENTORY_REQUIRED"));assertFalse(text.contains("private-token-and-query"));
    }
    @Test void connectivitySuccessDoesNotPretendInventoryOrFidelityReadiness() {
        var result=Preflight.run(source(RecoveryTest.project("prepare")),root,new Preflight.Connections(){
            public boolean target(Project p){return true;}
            public Map<String,Object> source(Project p){return Map.of("status","succeeded");}
        });
        assertEquals("succeeded",result.get("status"));
        assertEquals(2,((List<?>)result.get("extractionPrerequisites")).size());
    }
    @Test void target404AndInternalAdapterDefectRetainSafeSubstepsThroughArtifactEnvelope() throws Exception {
        var result=Preflight.run(source(RecoveryTest.project("prepare")),root,new Preflight.Connections(){
            public boolean target(Project p){throw new HttpException(404,"private-url-query-token");}
            public Map<String,Object> source(Project p) throws Exception {
                var report=Diagnostics.report("INTERNAL_ADAPTER_CONFIGURATION","source-cluster-construction");
                throw Diagnostics.acknowledged(Json.MAPPER.valueToTree(report),"source-connectivity");
            }
        });
        var checks=(Map<?,?>)result.get("checks");
        var target=(Map<?,?>)checks.get("target");
        assertEquals(404,target.get("httpStatus"));
        assertEquals("target-query",target.get("stage"));
        assertEquals("TARGET_ENDPOINT_NOT_FOUND",target.get("code"));
        assertEquals("INTERNAL_ADAPTER_CONFIGURATION",((Map<?,?>)checks.get("source")).get("code"));
        var artifact=root.resolve("result.json");
        SafeFiles.atomic(artifact,Map.of("schemaVersion",1,"operation","preflight","result",result));
        var text=Files.readString(artifact);
        assertTrue(text.contains("\"httpStatus\":404"));assertFalse(text.contains("private-url-query-token"));
        assertFalse(text.contains("CONFIGURATION_INVALID"));
        for (int status:List.of(401,403,500)) {
            var report=Preflight.endpoint(new HttpException(status,"private"),"target-query");
            assertEquals(status,report.get("httpStatus"));
            assertEquals(status==401 ? "ENDPOINT_AUTHENTICATION" : status==403 ? "ENDPOINT_AUTHORIZATION"
                : "TARGET_PROVIDER_FAILURE",report.get("code"));
        }
    }
    @Test void stableClassificationsNeverEchoRawPathsQueriesSecretsOrUnknownCause() {
        for(var failure:List.of(new Project.CredentialUnavailable(),new NoSuchFileException("private-source-value"),
            new IllegalArgumentException("private-source-value"),new RuntimeException(new ConnectException("private-source-value")),
            new javax.net.ssl.SSLException("private-source-value"),new IllegalStateException("private-source-value"))){
            var report=Main.diagnostic(failure);assertFalse(Json.text(report).contains("private-source-value"));
            assertTrue(report.containsKey("stage"));assertTrue(report.containsKey("remediation"));
        }
        assertEquals("OPERATION_CAUSE_UNKNOWN",Main.diagnostic(new IllegalStateException("private")).get("code"));
        assertEquals("ENDPOINT_CONNECTIVITY",Main.diagnostic(new RuntimeException(new ConnectException())).get("code"));
    }
}
