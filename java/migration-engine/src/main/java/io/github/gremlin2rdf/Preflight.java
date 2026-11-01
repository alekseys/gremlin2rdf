package io.github.gremlin2rdf;

import java.nio.file.*;
import java.util.*;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;

/** Endpoint reads are independent of extraction inventory/fidelity prerequisites. */
public final class Preflight {
    private Preflight() {}
    public interface Connections {
        boolean target(Project project) throws Exception;
        Map<String,Object> source(Project project) throws Exception;
    }
    public static Map<String,Object> run(Project project,Path root,Connections connections) {
        Map<String,Object> checks=new TreeMap<>(); List<Map<String,Object>> diagnostics=new ArrayList<>();
        try {
            if (project.target()==null) throw new Diagnostics.Failure("TARGET_CONFIGURATION_REQUIRED","target-connectivity");
            if (!connections.target(project)) throw new Diagnostics.Failure("ENDPOINT_PROVIDER_FAILURE","target-connectivity");
            checks.put("target",Map.of("status","succeeded","queryAccess",true,"writeAccess","not-executed-without-authorization"));
        } catch (Exception failure) { var report=endpoint(failure,"target-connectivity");checks.put("target",report);diagnostics.add(report); }
        try {
            if (project.source()==null) throw new Diagnostics.Failure("SOURCE_CONFIGURATION_REQUIRED","source-connectivity");
            var observation=connections.source(project);
            if (!"succeeded".equals(observation.get("status"))) throw new Diagnostics.Failure("ENDPOINT_TIMEOUT","source-connectivity");
            checks.put("source",observation);
        } catch (Exception failure) { var report=endpoint(failure,"source-connectivity");checks.put("source",report);diagnostics.add(report); }
        long free=0;
        try {
            free=Files.getFileStore(root).getUsableSpace();
            if (free<project.limits().minimumFreeDiskBytes()) throw new Diagnostics.Failure("PREPARE_DISK_SPACE","local-artifact-disk");
        } catch (Exception failure) { var report=Diagnostics.failure(failure,"local-artifact-disk");diagnostics.add(report);checks.put("disk",report); }
        if (Runtime.version().feature()<21) diagnostics.add(Diagnostics.report("PREPARE_JAVA_VERSION","java-runtime"));
        List<Map<String,Object>> extraction=new ArrayList<>();
        if (project.source()!=null) {
            try {
                Path inventory=SafeFiles.resolve(root,project.source().identityManifest());
                if (!Files.isRegularFile(inventory) || Files.size(inventory)==0)
                    extraction.add(Diagnostics.report("SOURCE_INVENTORY_REQUIRED","source-inventory"));
            } catch (Exception failure) { extraction.add(Diagnostics.failure(failure,"source-inventory")); }
            try { SourceCompatibility.read(project,root); }
            catch (Exception failure) { extraction.add(Diagnostics.report("SOURCE_FIDELITY_EVIDENCE_REQUIRED","source-fidelity")); }
        }
        return Map.of("status",diagnostics.isEmpty() ? "succeeded" : "blocked","checks",checks,"diagnostics",diagnostics,
            "blockers",diagnostics.stream().map(d->d.get("code")).toList(),"extractionPrerequisites",extraction,
            "java",Runtime.version().feature(),"availableArtifactDiskBytes",free,
            "requiredArtifactDiskBytes",project.limits().minimumFreeDiskBytes(),
            "emptyGraphAuthorization","not-established-by-read-only-query","connectivityLocation","current-worker-process");
    }
    public static Map<String,Object> endpoint(Exception failure,String stage) {
        Throwable current=failure;
        for (int depth=0;current!=null && depth<16;depth++,current=current.getCause()) {
            int status=current instanceof HttpException h ? h.getStatusCode()
                : current instanceof QueryExceptionHTTP q ? q.getStatusCode() : 0;
            if (status!=0) return Diagnostics.report(status==401 ? "ENDPOINT_AUTHENTICATION" : status==403 ? "ENDPOINT_AUTHORIZATION"
                : status==429 ? "ENDPOINT_THROTTLED" : status==408 || status==504 ? "ENDPOINT_TIMEOUT"
                : status==-1 ? "ENDPOINT_CONNECTIVITY" : "ENDPOINT_PROVIDER_FAILURE",stage);
        }
        return Diagnostics.failure(failure,stage);
    }
}
