package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceSnapshotTest {
    private final GraphRecord.Value partition=GraphRecord.Value.string("private-partition"),
        id=GraphRecord.Value.string("private-id");
    private final IdentityEntry vertex=new IdentityEntry(1,GraphRecord.Kind.vertex,partition,id,null,null);
    private Project project() {
        var source=new Project.Source("fixture","localhost",443,"db","g","/pk",null,"verified-identity-manifest",
            "identities.ndjson","0".repeat(64),null,null,null,true);
        return new Project(1,"cosmos-gremlin-to-jena","0".repeat(40),"fixture",1,"0.1.0",Mapping.standard(),
            source,null,Project.Limits.standard(),"immutable",Map.of(),List.of(),Map.of(),List.of());
    }
    private Object query(String script,Map<String,Object> bindings) {
        assertFalse(script.contains("private-"));
        if (bindings.containsKey("g2rSystemKey")) return List.of(switch(bindings.get("g2rSystemKey").toString()) {
            case "_ts" -> 123L;
            case "inVPartition","outVPartition" -> partition.lexical();
            default -> "private-system-value";
        });
        if (script.endsWith(".properties().count()")) return 1L;
        var row=new HashMap<String,Object>(Map.of("id",id.lexical(),"partition",partition.lexical(),"label","private-label",
            "properties",List.of(Map.of("key","name","value","private-value","id","private-occurrence","meta",Map.of()))));
        if (bindings.containsKey("g2rOwnerId")) {
            row.put("source",Map.of("id",id.lexical(),"partition",partition.lexical()));
            row.put("destination",Map.of("id",id.lexical(),"partition",partition.lexical()));
            row.put("properties",List.of(Map.of("key","name","value","private-value")));
        }
        return row;
    }
    private Map<String,Object> diagnostic(Exception failure) {
        var report=SourceDiagnostics.failure(new SourceDiagnostics.Context("source-record-read",failure),"source-operation");
        assertEquals(report,Diagnostics.failure(Diagnostics.acknowledged(Json.MAPPER.valueToTree(report),"execution"),"execution"));
        assertFalse(Json.text(report).contains("private-"));
        return report;
    }
    @Test void actualSnapshotPreservesStrictPropertiesAndMetadataForVerticesAndEdges() throws Exception {
        var record=SourceSnapshot.read(project(),vertex,"0".repeat(64),this::query);
        assertTrue(record.complete());assertTrue(SourceCompatibility.metadataComplete(record));
        assertEquals("private-value",record.properties().getFirst().value().lexical());
        var edge=new IdentityEntry(1,GraphRecord.Kind.edge,partition,id,id,GraphRecord.EdgeScope.sourceVertex);
        assertTrue(SourceCompatibility.metadataComplete(SourceSnapshot.read(project(),edge,"0".repeat(64),this::query)));
    }
    @Test void everyMissingSystemPropertyHasItsOwnSafeStageInsteadOfArtifactBlame() {
        for (var entry:Map.of("_etag","etag","_ts","timestamp","_self","self","_attachments","attachments",
            "_rid","rid","inVPartition","in-partition","outVPartition","out-partition").entrySet()) {
            var identity=entry.getKey().endsWith("Partition")
                ? new IdentityEntry(1,GraphRecord.Kind.edge,partition,id,id,GraphRecord.EdgeScope.sourceVertex) : vertex;
            for (Object response:List.of(List.of(),List.of("private-a","private-b"),"private-not-a-fold")) {
                var failure=assertThrows(Exception.class,()->SourceSnapshot.read(project(),identity,"0".repeat(64),(script,bindings)->
                    entry.getKey().equals(bindings.get("g2rSystemKey")) ? response : query(script,bindings)));
                var report=diagnostic(failure);
                assertEquals("SOURCE_SYSTEM_METADATA_UNOBSERVABLE",report.get("code"));
                assertEquals("source-record-system-"+entry.getValue(),report.get("stage"));
            }
        }
    }
    @Test void typedSystemMetadataFailuresAndChangedEtagsAreNotArtifactErrors() {
        for (String key:List.of("_ts","_etag","_self","_attachments","_rid")) {
            var failure=assertThrows(Exception.class,()->SourceSnapshot.read(project(),vertex,"0".repeat(64),(script,bindings)->
                key.equals(bindings.get("g2rSystemKey")) ? List.of(key.equals("_ts") ? "private-not-integer" : 42L) : query(script,bindings)));
            assertEquals("SOURCE_SYSTEM_METADATA_INVALID",diagnostic(failure).get("code"));
        }
        int[] reads={0};
        var changed=assertThrows(Exception.class,()->SourceSnapshot.read(project(),vertex,"0".repeat(64),(script,bindings)->
            "_etag".equals(bindings.get("g2rSystemKey")) ? List.of("private-etag-"+reads[0]++) : query(script,bindings)));
        assertEquals("SOURCE_RECORD_CHANGED",diagnostic(changed).get("code"));
    }
    @Test void invalidProjectionAndUnclassifiedReadRemainSourceFailures() {
        var invalid=assertThrows(Exception.class,()->SourceSnapshot.read(project(),vertex,"0".repeat(64),(script,bindings)->
            script.contains(".project('id'") ? List.of("private-value") : query(script,bindings)));
        assertEquals("SOURCE_RECORD_UNSUPPORTED",diagnostic(invalid).get("code"));
        var failure=assertThrows(Exception.class,()->SourceSnapshot.read(project(),vertex,"0".repeat(64),(script,bindings)->{
            throw new java.io.IOException("private-provider-details");
        }));
        var report=diagnostic(failure);
        assertEquals("SOURCE_RECORD_READ_FAILED",report.get("code"));
        assertEquals("source-record-system-etag",report.get("stage"));
    }
    @Test void topLevelCardinalityIsNotConfusedWithAMissingFoldOrRetried() {
        var requests=new SourceRequests(2,1000,"1000",millis->{throw new AssertionError("No cardinality retries");});
        for (List<?> values:List.of(List.of(),List.of("private-a","private-b"))) {
            var failure=assertThrows(Exception.class,()->SourceSnapshot.read(project(),vertex,"0".repeat(64),(script,bindings)->
                requests.execute(()->new SourceRequests.Response(values,Map.of("x-ms-total-request-charge",1)))));
            var report=diagnostic(failure);
            assertEquals("SOURCE_RESPONSE_CARDINALITY",report.get("code"));
            assertEquals("source-record-system-etag",report.get("stage"));
        }
        assertEquals("2",requests.charge());assertEquals(0,requests.retries());
    }
}
