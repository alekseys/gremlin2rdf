package io.github.gremlin2rdf;

import java.util.Set;

/** Fixed codes from the isolated document adapter; never forwards raw exception messages. */
final class DocumentDiagnostics {
    private DocumentDiagnostics() {}
    static final Set<String> CODES=Set.of(
        "AMBIGUOUS_EDGE_FLAG","ARTIFACT_ALREADY_EXISTS","CANONICAL_RECORD_SIZE_LIMIT","CHECKPOINT_INPUT_MISMATCH",
        "CHECKPOINT_INTEGRITY_FAILURE","CHECKPOINT_SIZE_LIMIT","CREDENTIAL_UNAVAILABLE","CUSTOM_CA_UNAVAILABLE",
        "DOCUMENT_FIDELITY_UNOBSERVABLE","DOCUMENT_SOURCE_BLOCKED","DUPLICATE_DOCUMENT_IDENTITY",
        "DUPLICATE_ENUMERATION_IDENTITY","DUPLICATE_JSON_FIELD","DUPLICATE_MANIFEST_IDENTITY","DUPLICATE_OCCURRENCE_ID",
        "EDGE_ENDPOINT_NOT_IN_INVENTORY","EMPTY_NDJSON_LINE","EMPTY_PROPERTY_KEY","ENDPOINT_SCOPE_VIOLATION",
        "ENUMERATION_MANIFEST_INTEGRITY","ENUMERATION_REQUIRES_STABLE_SOURCE","EXTRA_DOCUMENT_IDENTITY",
        "IDENTITY_MANIFEST_HASH_MISMATCH","INCOMPLETE_NDJSON","INSUFFICIENT_DISK_SPACE","INVALID_COMMAND",
        "INVALID_ENUMERATION_PLAN","INVALID_FEED_PAGE","INVALID_LIMIT","INVALID_LOCAL_ARTIFACT","INVALID_PROJECT",
        "INVALID_PROJECT_OR_ARTIFACT","INVALID_RU_LIMIT","INVALID_VERIFICATION_STATE","LINKED_ARTIFACT_PATH",
        "LOCAL_ARTIFACT_UNAVAILABLE","MISSING_DOCUMENT_IDENTITIES","MISSING_DOCUMENT_IDENTITY","MISSING_DOCUMENT_LABEL",
        "MISSING_PROPERTY_VALUE","OPERATION_DURATION_LIMIT","OUTPUT_HASH_MISMATCH","PAGE_SIZE_LIMIT","PENDING_CHECKPOINT",
        "PROBE_CACHE_INCOMPLETE","PROBE_CACHE_INTEGRITY_FAILURE","PROBE_CACHE_INVENTORY_MISMATCH","PROBE_CACHE_MISSING",
        "PROJECT_SIZE_LIMIT","RAW_SNAPSHOT_INTEGRITY_FAILURE","RECEIPT_SIZE_LIMIT","RECORD_SIZE_LIMIT",
        "REVIEWED_RU_BUDGET_REQUIRED","REVIEWED_RU_RATE_REQUIRED","RU_BUDGET_EXCEEDED","RU_BUDGET_EXHAUSTED",
        "SCOPED_APPROVAL_REQUIRED","SORT_FAN_IN_LIMIT","SOURCE_ACCESS_DENIED","SOURCE_CONTINUATION_UNAVAILABLE",
        "SOURCE_DOCUMENTS_CHANGED","SOURCE_ENUMERATION_REQUEST_LIMIT","SOURCE_REQUEST_REJECTED","SOURCE_RETRY_LIMIT",
        "SOURCE_SPEND_UNKNOWN","UNCONFIRMED_ENUMERATION_OUTPUT","UNEXPECTED_EDGE_FIELD","UNKNOWN_OCCURRENCE_FIELD",
        "UNKNOWN_STORAGE_FIELD","UNOWNED_SOURCE_ARTIFACTS","UNSAFE_ARTIFACT_PATH","UNSUPPORTED_COLLECTION_METADATA",
        "UNSUPPORTED_DOCUMENT_SHAPE","UNSUPPORTED_IDENTITY_MANIFEST","UNSUPPORTED_NUMERIC_VALUE","UNSUPPORTED_PHYSICAL_ID",
        "UNSUPPORTED_PROPERTY_BAG","UNSUPPORTED_SCALAR_VALUE");
    static String remedy(String code) {
        return switch(code) {
            case "DOCUMENT_FIDELITY_UNOBSERVABLE" -> "Required document fidelity is unobservable. Preserve raw documents and inventory. Strict mode cannot invent occurrence IDs, metadata or numeric runtime provenance. Explicitly select reduced conversion and separately authorize its current scoped approval if acceptable; Gremlin semantic equivalence remains unestablished.";
            case "SOURCE_SPEND_UNKNOWN" -> "An interrupted SDK request has unconfirmed spend. Preserve raw pages, checkpoint and journal. Resume is blocked; do not reset or relabel the journal or infer zero cost. Review a separate bounded run.";
            case "REVIEWED_RU_BUDGET_REQUIRED","REVIEWED_RU_RATE_REQUIRED","INVALID_RU_LIMIT" -> "Set explicit positive document-source total RU and separate RU/s pacing in connection settings, then review the bounded run. These settings do not provision throughput or renew preserved budgets.";
            case "SOURCE_ACCESS_DENIED","CREDENTIAL_UNAVAILABLE" -> "Verify read-only access to the configured account/database/container using the stored source credential. Do not paste secrets in configuration or chat. No Gremlin fallback was attempted.";
            default -> "Preserve raw document evidence, inventory and checkpoints. Review the fixed document-source diagnostic, explicit profile, source settings and scoped budgets before retrying. Unknown shapes and mismatched state are not skipped or silently converted; no automatic Gremlin fallback occurs.";
        };
    }
}
