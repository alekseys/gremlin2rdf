package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.util.*;

/** Fixed read-only record snapshot, shared by live requests and adapter contract tests. */
final class SourceSnapshot {
    interface Query { Object execute(String script, Map<String,Object> bindings) throws Exception; }
    static GraphRecord read(Project project, IdentityEntry identity, String manifestHash, Query query) throws Exception {
        boolean vertex=identity.kind()==GraphRecord.Kind.vertex;
        Map<String,Object> bindings=new HashMap<>();
        bindings.put("g2rPartition",SourceCodec.scalar(Json.MAPPER.valueToTree(identity.partition())));
        bindings.put("g2rElementId",SourceCodec.scalar(Json.MAPPER.valueToTree(identity.id())));
        bindings.put("g2rPartitionProperty",project.source().partitionKeyPath().substring(1));
        if (!vertex) bindings.put("g2rOwnerId",SourceCodec.scalar(Json.MAPPER.valueToTree(identity.ownerId())));
        String selection=vertex ? "g.V([g2rPartition,g2rElementId])"
            : "g.V([g2rPartition,g2rOwnerId]).outE().hasId(g2rElementId)";
        Object initialEtag=systemValue(query,selection,bindings,"_etag");
        long count;
        try { count=SourceCodec.count(request(query,selection+".properties().count()",bindings,"source-record-property-count")); }
        catch (IllegalArgumentException | ArithmeticException failure) {
            throw new Diagnostics.Failure("SOURCE_RECORD_UNSUPPORTED","source-record-property-count");
        }
        if (count>10000) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-record-property-count");
        String projection=vertex
            ? ".project('id','label','partition','properties').by(id()).by(label()).by(values(g2rPartitionProperty))"
                + ".by(properties().project('key','value','id','meta').by(key()).by(value()).by(id()).by(valueMap()).fold())"
            : ".project('id','label','partition','source','destination','properties').by(id()).by(label())"
                + ".by(outV().values(g2rPartitionProperty))"
                + ".by(outV().project('id','partition').by(id()).by(values(g2rPartitionProperty)))"
                + ".by(inV().project('id','partition').by(id()).by(values(g2rPartitionProperty)))"
                + ".by(properties().project('key','value').by(key()).by(value()).fold())";
        if (vertex && project.mapping().fidelity().equals("reduced"))
            projection=".project('id','label','partition','properties').by(id()).by(label()).by(values(g2rPartitionProperty))"
                + ".by(properties().project('key','value').by(key()).by(value()).fold())";
        Object observed=request(query,selection+projection,bindings,"source-record-property-projection");
        if (!(observed instanceof Map<?,?> object))
            throw new Diagnostics.Failure("SOURCE_RECORD_UNSUPPORTED","source-record-property-projection");
        var row=new HashMap<Object,Object>(object);
        Map<String,Object> metadata=new TreeMap<>();
        metadata.put("_etag",initialEtag);
        for (String key:vertex ? List.of("_ts","_self","_attachments","_rid")
            : List.of("_ts","_self","_attachments","_rid","inVPartition","outVPartition"))
            metadata.put(key,systemValue(query,selection,bindings,key));
        Object finalEtag=systemValue(query,selection,bindings,"_etag");
        if (!initialEtag.equals(finalEtag))
            throw new Diagnostics.Failure("SOURCE_RECORD_CHANGED","source-record-etag-stability");
        row.put("metadata",metadata);
        GraphRecord result;
        try { result=new SourceCodec(project).normalize(identity,row,count,manifestHash); }
        catch (IllegalArgumentException failure) {
            throw new Diagnostics.Failure("SOURCE_RECORD_UNSUPPORTED","source-record-normalization");
        }
        if (!SourceCompatibility.metadataComplete(result))
            throw new Diagnostics.Failure("SOURCE_SYSTEM_METADATA_INVALID","source-record-system-metadata-validation");
        return result;
    }
    private static Object request(Query query,String script,Map<String,Object> bindings,String stage) throws Exception {
        try { return query.execute(script,bindings); }
        catch (Exception failure) { throw new SourceDiagnostics.Context(stage,failure); }
    }
    static Object systemValue(Query query,String selection,Map<String,Object> bindings,String key) throws Exception {
        String stage=switch(key) {
            case "_etag" -> "source-record-system-etag";
            case "_ts" -> "source-record-system-timestamp";
            case "_self" -> "source-record-system-self";
            case "_attachments" -> "source-record-system-attachments";
            case "_rid" -> "source-record-system-rid";
            case "inVPartition" -> "source-record-system-in-partition";
            case "outVPartition" -> "source-record-system-out-partition";
            default -> throw new IllegalArgumentException("Unsupported fixed system-property selector");
        };
        Map<String,Object> parameters=new HashMap<>(bindings);parameters.put("g2rSystemKey",key);
        Object folded=request(query,"g.withStrategies(ProjectionStrategy.build().IncludeSystemProperties(g2rSystemKey).create())"
            +selection.substring(1)+".values(g2rSystemKey).fold()",parameters,stage);
        if (!(folded instanceof List<?> values) || values.size()!=1 || values.getFirst()==null)
            throw new Diagnostics.Failure("SOURCE_SYSTEM_METADATA_UNOBSERVABLE",stage);
        Object value=values.getFirst();
        if (key.equals("_ts") ? !(value instanceof Byte || value instanceof Short || value instanceof Integer
            || value instanceof Long || value instanceof java.math.BigInteger)
            : key.equals("_etag") ? !(value instanceof String text) || text.isEmpty()
            : Set.of("_self","_attachments","_rid").contains(key) && !(value instanceof String))
            throw new Diagnostics.Failure("SOURCE_SYSTEM_METADATA_INVALID",stage);
        return value;
    }
}
