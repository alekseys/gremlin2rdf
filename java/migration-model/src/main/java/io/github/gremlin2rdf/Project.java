package io.github.gremlin2rdf;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Project(
    int schemaVersion, String migrationKind, String baselineRepositorySha, String runId,
    int protocolVersion, String workerVersion, Mapping mapping, Source source, Target target,
    Limits limits, String consistency, Map<String, Phase> phases, List<Approval> approvals,
    Map<String, String> applicationHashes, List<Artifact> artifacts
) {
    public enum Status { notStarted, running, paused, blocked, failed, succeeded, cancelled, stale }
    public record Phase(String status, List<String> blockers, List<String> evidence, String inputHash) {
        public Phase {
            if (!List.of("not-started", "running", "paused", "blocked", "failed", "succeeded", "cancelled", "stale").contains(status)
                || inputHash != null && !inputHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid phase evidence");
            blockers = List.copyOf(blockers); evidence = List.copyOf(evidence); evidence.forEach(SafeFiles::relative);
        }
    }
    public record Source(String scope, String host, int port, String database, String container,
                         String partitionKeyPath, String authenticationRef, String extractionStrategy,
                         String identityManifest, String identityManifestHash, String compatibilityEvidence,
                         String ruBudget, String customCaRef, boolean syntheticTestContainer) {
        public Source {
            reference(authenticationRef); reference(customCaRef);
            if (scope == null || scope.isBlank() || host == null || host.isBlank() || host.contains("/")
                || port < 1 || port > 65535 || database == null || database.isBlank() || container == null || container.isBlank()
                || partitionKeyPath == null || !partitionKeyPath.matches("/[^/]{1,255}")
                || !"verified-identity-manifest".equals(extractionStrategy))
                throw new IllegalArgumentException("Invalid bounded source configuration");
            SafeFiles.relative(identityManifest);
            if (identityManifestHash == null || !identityManifestHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Verified identity manifest checksum required");
            if (compatibilityEvidence != null) SafeFiles.relative(compatibilityEvidence);
            if (ruBudget != null && new java.math.BigDecimal(ruBudget).signum() <= 0)
                throw new IllegalArgumentException("Positive source RU budget required");
        }
    }
    public record Target(String datasetUrl, String queryEndpoint, String updateEndpoint, String graphStoreEndpoint,
                         String authenticationRef, String graphNamespace, String publicationMode, String customCaRef) {
        public Target(String datasetUrl, String queryEndpoint, String updateEndpoint, String graphStoreEndpoint,
                      String authenticationRef, String graphNamespace, String publicationMode) {
            this(datasetUrl, queryEndpoint, updateEndpoint, graphStoreEndpoint, authenticationRef, graphNamespace, publicationMode, null);
        }
        public Target {
            endpoint(datasetUrl); endpoint(queryEndpoint); endpoint(updateEndpoint); endpoint(graphStoreEndpoint);
            Mapping.iri(graphNamespace);
            if (!List.of("manual", "version-pointer").contains(publicationMode))
                throw new IllegalArgumentException("Invalid publication mode");
            reference(authenticationRef);
            reference(customCaRef);
        }
    }
    public record Limits(int batchRecords, int batchBytes, int maxRecordBytes, int requestTimeoutMillis,
                         int maxRetries, int queueCapacity, int writerConcurrency, long minimumFreeDiskBytes) {
        public Limits {
            if (batchRecords < 1 || batchRecords > 1000 || batchBytes < 1024 || batchBytes > 8388608
                || maxRecordBytes < 128 || maxRecordBytes > 1048576 || maxRecordBytes > batchBytes
                || requestTimeoutMillis < 100 || requestTimeoutMillis > 300000 || maxRetries < 0 || maxRetries > 10
                || queueCapacity < 1 || queueCapacity > 1000 || writerConcurrency != 1 || minimumFreeDiskBytes < 0)
                throw new IllegalArgumentException("Invalid bounded migration limits; only one writer is supported");
        }
        public static Limits standard() { return new Limits(100, 1048576, 262144, 30000, 3, 100, 1, 104857600); }
    }
    public record Artifact(String relativePath, String sha256, String ownerRunId, boolean sensitive) {
        public Artifact {
            SafeFiles.relative(relativePath);
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}") || ownerRunId == null || !ownerRunId.matches("[a-zA-Z0-9_-]{1,80}"))
                throw new IllegalArgumentException("Artifact integrity and owner required");
        }
    }
    public Project {
        if (schemaVersion != 1 || protocolVersion != 1 || !"cosmos-gremlin-to-jena".equals(migrationKind)
            || !"0.1.0".equals(workerVersion)) throw new IllegalArgumentException("Incompatible graph project");
        if (baselineRepositorySha == null || !baselineRepositorySha.matches("[a-f0-9]{40}"))
            throw new IllegalArgumentException("Baseline SHA required");
        if (runId == null || !runId.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid run identity");
        if (!List.of("quiesced", "immutable", "best-effort").contains(consistency))
            throw new IllegalArgumentException("Consistency declaration required");
        Objects.requireNonNull(mapping); Objects.requireNonNull(limits);
        phases = Map.copyOf(phases); approvals = List.copyOf(approvals);
        applicationHashes = Map.copyOf(applicationHashes); artifacts = List.copyOf(artifacts);
        applicationHashes.forEach((file, hash) -> {
            SafeFiles.relative(file);
            if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid application fingerprint");
        });
        artifacts.forEach(a -> SafeFiles.relative(a.relativePath()));
        if (source != null) reference(source.authenticationRef());
    }
    public String inputHash() {
        return Json.hash(List.of(migrationKind, schemaVersion, baselineRepositorySha, runId, protocolVersion,
            workerVersion, mapping, source == null ? "" : source, target == null ? "" : target, limits, consistency, applicationHashes));
    }
    public String graph(String role) {
        if (target == null || !List.of("canonical", "derived", "metadata", "validation").contains(role))
            throw new IllegalArgumentException("Unknown graph role or missing target");
        return target.graphNamespace() + runId + ":" + role;
    }
    public static void reference(String value) {
        if (value != null && !value.matches("env:GRAPH_[A-Z0-9_]{1,100}"))
            throw new IllegalArgumentException("Only approved environment credential references are supported headlessly");
    }
    public static String secret(String ref) {
        reference(ref);
        if (ref == null) return null;
        String value = System.getenv(ref.substring(4));
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Credential reference is unavailable");
        return value;
    }
    private static void endpoint(String value) {
        URI uri = URI.create(value);
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null
            || !(uri.getScheme().equals("https") || uri.getScheme().equals("http")
                && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
            throw new IllegalArgumentException("HTTPS endpoint required except loopback; embedded credentials prohibited");
    }
}
