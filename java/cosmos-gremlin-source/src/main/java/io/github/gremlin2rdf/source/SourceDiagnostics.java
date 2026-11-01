package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.Diagnostics;
import java.io.IOException;
import java.util.*;
import org.apache.tinkerpop.gremlin.driver.exception.ResponseException;

final class SourceDiagnostics {
    static final class Context extends IOException {
        final String stage;
        Context(String stage,Exception cause) { super("Sanitized source substep failure",cause);this.stage=stage; }
    }
    static Map<String,Object> failure(Exception error,String fallback) {
        String stage=fallback;ResponseException response=null;Map<String,Object> classified=null;
        int depth=0;
        for (Throwable cause=error;cause!=null && depth++<16;cause=cause.getCause()) {
            if (cause instanceof Context context && stage.equals(fallback)) stage=context.stage;
            if (cause instanceof Diagnostics.Failure && classified==null) classified=Diagnostics.failure(cause,stage);
            if (cause instanceof ResponseException r) { response=r;break; }
        }
        if (response==null) {
            var report=new TreeMap<>(classified==null ? Diagnostics.failure(error,stage) : classified);
            if (!stage.equals(fallback)) report.put("stage",stage);
            return report;
        }
        int gremlin=response.getResponseStatusCode().getValue();
        var attributes=response.getStatusAttributes().orElse(Map.of());
        Integer source=number(attributes.get("x-ms-status-code"),100,599);
        Integer substatus=number(attributes.get("x-ms-substatus-code"),0,Integer.MAX_VALUE);
        int status=source==null ? gremlin : source;
        String code=status==401 || gremlin==401 || gremlin==407 ? "ENDPOINT_AUTHENTICATION"
            : status==403 || gremlin==403 ? "ENDPOINT_AUTHORIZATION"
            : status==429 ? "ENDPOINT_THROTTLED"
            : status==408 || status==504 || gremlin==598 ? "ENDPOINT_TIMEOUT"
            : gremlin==497 || gremlin==599 ? "SOURCE_SERIALIZATION_REJECTED"
            : gremlin==498 || gremlin==499 ? "SOURCE_REQUEST_REJECTED"
            : gremlin==597 ? "SOURCE_SCRIPT_REJECTED" : "SOURCE_PROVIDER_CAUSE_UNKNOWN";
        var report=new TreeMap<>(classified==null ? Diagnostics.report(code,stage) : classified);
        if (!stage.equals(fallback)) report.put("stage",stage);
        report.put("gremlinStatus",gremlin);
        if (source!=null) report.put("sourceStatus",source);
        if (substatus!=null) report.put("sourceSubstatus",substatus);
        return report;
    }
    private static Integer number(Object value,int min,int max) {
        if (!(value instanceof Number || value instanceof String)) return null;
        String text=value.toString();
        if (!text.matches("[0-9]{1,10}")) return null;
        try { long n=Long.parseLong(text);return n>=min && n<=max ? (int)n : null; }
        catch (NumberFormatException ignored) { return null; }
    }
}
