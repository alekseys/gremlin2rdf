package io.github.gremlin2rdf;

import java.util.*;

/** Fixed, source-value-free diagnostic vocabulary shared by isolated workers. */
public final class Diagnostics {
    private Diagnostics() {}
    public static final class Failure extends java.io.IOException {
        private final String code;
        private final String stage;
        private final Integer httpStatus;
        private Map<String,Object> sourceStatus = Map.of();
        public Failure(String code, String stage) { this(code,stage,null); }
        public Failure(String code,String stage,Integer httpStatus) {
            super(code); this.code=code; this.stage=stage; this.httpStatus=httpStatus;
        }
    }
    private static final Map<String,String> REMEDIES = Map.ofEntries(
        Map.entry("CREDENTIAL_REFERENCE_UNAVAILABLE", "Use Store credentials securely for the configured reference, then retry. Do not paste credentials into configuration or artifacts."),
        Map.entry("SOURCE_CONFIGURATION_REQUIRED", "Save the Gremlin hostname, database, container and partition key in the connection form."),
        Map.entry("INTERNAL_ADAPTER_CONFIGURATION", "The packaged source adapter has an internal driver configuration defect. Install the corrected complete VSIX and reload VS Code; editing inventory or credentials cannot fix cluster construction."),
        Map.entry("TARGET_ENDPOINT_NOT_FOUND", "The configured target query endpoint returned HTTP 404. Verify the existing Fuseki dataset and its query path; a running server root does not imply a dataset exists. Have the operator create or mount the intended dataset separately if absent, save its actual endpoint paths and rerun read-only checks."),
        Map.entry("TARGET_PROVIDER_FAILURE", "The target rejected the bounded read-only query. Verify its SPARQL query path, service health and permissions; raw endpoint messages are redacted."),
        Map.entry("SOURCE_ADAPTER_REQUIRED", "Use the pinned packaged source adapter; rebuild with npm run graph:prepare if it is missing."),
        Map.entry("SOURCE_PROTOCOL_INVALID", "The isolated source worker did not return a valid bounded acknowledgement. Reinstall the complete VSIX or rebuild the pinned workers; missing inventory is not inferred from this failure."),
        Map.entry("TARGET_CONFIGURATION_REQUIRED", "Save the existing Fuseki dataset URL in the connection form."),
        Map.entry("SOURCE_INVENTORY_REQUIRED", "Use guided Prepare to discover candidate partitions and run read-only identity enumeration. Empty bootstrap files are not extraction inventories."),
        Map.entry("SOURCE_EMPTY_VERIFIED", "Read-only counts and owned enumeration confirm an empty source. Fidelity is not established without records; no migration load or capability pass is implied."),
        Map.entry("SOURCE_FIDELITY_NOT_ESTABLISHED", "The bounded observations do not establish all required fidelity semantics. Review the specific observation fields; missing examples are not proof the source lacks those capabilities. Do not extract or silently reduce fidelity."),
        Map.entry("SOURCE_FIDELITY_RECORD_LIMIT", "The current fidelity probe supports at most 2000 reviewed semantic identities. Preserve the complete extraction inventory; do not claim full coverage from a smaller sample or run an exhaustive unbounded probe. A separate bounded compatibility corpus needs explicit review."),
        Map.entry("SOURCE_REQUEST_REJECTED", "The source rejected a bounded request or its arguments. Preserve the checkpoint and share only this safe diagnostic code, substep and numeric statuses for adapter investigation; do not change paging semantics or fabricate an inventory."),
        Map.entry("SOURCE_SCRIPT_REJECTED", "The source returned a script-evaluation error for this adapter substep. Share the safe substep and numeric statuses to investigate supported request syntax; raw scripts/provider messages are redacted. No unsupported paging cause is inferred."),
        Map.entry("SOURCE_SERIALIZATION_REJECTED", "The source returned a serialization error. Verify the complete pinned GraphSON v2 adapter package; share only safe substep/status evidence. Do not coerce or drop source values."),
        Map.entry("SOURCE_PROVIDER_CAUSE_UNKNOWN", "The source returned an unclassified provider failure. Preserve the checkpoint and share only this substep, Gremlin status, source status and substatus if present. The rejected request's cause remains unknown; inventory or permission defects are not inferred."),
        Map.entry("SOURCE_FIDELITY_EVIDENCE_REQUIRED", "Complete reconciled identity enumeration, then run the read-only source fidelity check before extracting."),
        Map.entry("SOURCE_PARTITION_COVERAGE", "The candidate partitions do not cover live source counts. Add missing live partition values in guided Prepare and re-enumerate; seed candidates alone never prove coverage."),
        Map.entry("SOURCE_COUNTS_CHANGED", "Source counts changed during enumeration. Confirm paused writes or an immutable source and repeat the bounded read-only run; preserve its checkpoint."),
        Map.entry("SOURCE_ENUMERATION_REQUEST_LIMIT", "The reviewed enumeration request ceiling was reached. Preserve the checkpoint and review a new request budget before resuming."),
        Map.entry("SOURCE_ENUMERATION_DURATION_LIMIT", "The reviewed enumeration time ceiling was reached. Preserve the checkpoint and review the next bounded run; this is not complete coverage."),
        Map.entry("SOURCE_RU_BUDGET_EXHAUSTED", "The source RU budget is exhausted. Preserve request journals and review an additional budget before retrying."),
        Map.entry("SOURCE_RU_UNOBSERVABLE", "The provider did not expose reliable request charge. A configured RU ceiling cannot be enforced; do not treat the operation as successful."),
        Map.entry("ENDPOINT_AUTHENTICATION", "Renew the configured credential using SecretStorage and verify access to the configured database/dataset. Do not retry uncertain writes."),
        Map.entry("ENDPOINT_AUTHORIZATION", "Grant least-privilege access to the configured source or exact target resources. Connection credentials do not authorize migration writes."),
        Map.entry("ENDPOINT_THROTTLED", "The endpoint throttled the bounded request. Review RU/request budgets and retry policy; preserve acknowledged progress."),
        Map.entry("ENDPOINT_TIMEOUT", "The bounded endpoint request timed out. Check network/firewall/service health from the extension host and review the request deadline."),
        Map.entry("ENDPOINT_DNS", "The configured hostname could not be resolved from the extension host. Correct the connection hostname or DNS/network configuration."),
        Map.entry("ENDPOINT_CONNECTIVITY", "The endpoint could not be reached. Check the configured host/port, running service, firewall and extension-host network access."),
        Map.entry("ENDPOINT_TLS", "TLS validation failed. Correct hostname/certificate/approved CA configuration; do not disable certificate verification."),
        Map.entry("ENDPOINT_PROVIDER_FAILURE", "The provider rejected the bounded request. Verify supported driver/serializer/query capabilities and endpoint permissions; raw provider messages are redacted."),
        Map.entry("ARTIFACT_MISSING", "The required local artifact is missing. Use guided Prepare to create the inventory/plan, or restore the current owned checkpoint/evidence."),
        Map.entry("ARTIFACT_ACCESS", "The extension host cannot access the local artifact. Check file permissions and non-symlink run-relative paths."),
        Map.entry("ARTIFACT_OR_STATE_ERROR", "Preserve the owned artifacts and checkpoints. Verify their checksum, format, path and run ownership before retrying."),
        Map.entry("CONFIGURATION_INVALID", "Review the connection form and strict project schema. Unsupported or invalid values require correction; no connection success is inferred."),
        Map.entry("AUTHORIZATION_REQUIRED", "Provide current explicit operation/input/resource-scoped approval. Read-only preparation never enables source writes."),
        Map.entry("PREPARE_DISK_SPACE", "Free sufficient space on the local artifact filesystem before enumeration or extraction."),
        Map.entry("PREPARE_JAVA_VERSION", "Run the extension host with Java 21 or newer."),
        Map.entry("OPERATION_CAUSE_UNKNOWN", "The cause is not classified. Review the failed substep and safe diagnostic code; do not assume a missing inventory caused an endpoint failure.")
    );
    public static Map<String,Object> report(String code,String stage) {
        return Map.of("status","blocked","code", REMEDIES.containsKey(code) ? code : "OPERATION_CAUSE_UNKNOWN",
            "stage",stage,"remediation",REMEDIES.getOrDefault(code,REMEDIES.get("OPERATION_CAUSE_UNKNOWN")));
    }
    public static Map<String,Object> report(String code,String stage,Integer httpStatus) {
        var result=new TreeMap<>(report(code,stage));
        if (httpStatus!=null && httpStatus>=100 && httpStatus<=599) result.put("httpStatus",httpStatus);
        return result;
    }
    public static Map<String,Object> failure(Throwable error,String stage) {
        Throwable current = error;
        for (int depth=0; current!=null && depth<16; depth++,current=current.getCause()) {
            if (current instanceof Failure f) {
                var report=new TreeMap<>(report(f.code,f.stage,f.httpStatus));report.putAll(f.sourceStatus);return report;
            }
            if (current instanceof Project.CredentialUnavailable) return report("CREDENTIAL_REFERENCE_UNAVAILABLE",stage);
            if (current instanceof com.fasterxml.jackson.core.JsonProcessingException) return report("CONFIGURATION_INVALID",stage);
            if (current instanceof java.nio.file.NoSuchFileException || current instanceof java.io.FileNotFoundException)
                return report("ARTIFACT_MISSING",stage);
            if (current instanceof java.nio.file.AccessDeniedException) return report("ARTIFACT_ACCESS",stage);
            if (current instanceof javax.net.ssl.SSLException || current instanceof java.security.cert.CertificateException)
                return report("ENDPOINT_TLS",stage);
            if (current instanceof java.net.UnknownHostException) return report("ENDPOINT_DNS",stage);
            if (current instanceof java.net.SocketTimeoutException || current instanceof java.net.http.HttpTimeoutException ||
                current instanceof java.util.concurrent.TimeoutException) return report("ENDPOINT_TIMEOUT",stage);
            if (current instanceof java.net.ConnectException || current instanceof java.net.SocketException)
                return report("ENDPOINT_CONNECTIVITY",stage);
        }
        if (error instanceof SecurityException) return report("AUTHORIZATION_REQUIRED",stage);
        if (error instanceof java.io.IOException) return report("ARTIFACT_OR_STATE_ERROR",stage);
        if (error instanceof IllegalArgumentException) return report("CONFIGURATION_INVALID",stage);
        return report("OPERATION_CAUSE_UNKNOWN",stage);
    }
    public static Failure acknowledged(String code,String stage) {
        return new Failure(REMEDIES.containsKey(code) ? code : "OPERATION_CAUSE_UNKNOWN",stage);
    }
    public static Failure acknowledged(com.fasterxml.jackson.databind.JsonNode diagnostic,String fallbackStage) {
        String stage=diagnostic.path("stage").asText();
        if (!stage.matches("[a-z][a-z0-9-]{0,79}")) stage=fallbackStage;
        String code=diagnostic.path("code").asText();
        var status=diagnostic.path("httpStatus");
        var failure=new Failure(REMEDIES.containsKey(code) ? code : "OPERATION_CAUSE_UNKNOWN",stage,
            status.isIntegralNumber() && status.canConvertToInt() ? status.intValue() : null);
        var fields=new TreeMap<String,Object>();
        for (String name:List.of("gremlinStatus","sourceStatus","sourceSubstatus")) {
            var value=diagnostic.path(name);
            int max=name.equals("gremlinStatus") ? 999 : name.equals("sourceStatus") ? 599 : Integer.MAX_VALUE;
            int min=name.equals("sourceSubstatus") ? 0 : 100;
            if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>=min && value.intValue()<=max)
                fields.put(name,value.intValue());
        }
        failure.sourceStatus=Map.copyOf(fields);return failure;
    }
}
