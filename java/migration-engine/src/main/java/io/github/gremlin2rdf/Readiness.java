package io.github.gremlin2rdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Readiness {
    private Readiness() {}
    public record Gate(String status, List<String> evidence, List<String> blockers) {}
    public record Report(int schemaVersion, String status, String readiness, String runId, String inputHash,
                         Map<String, Gate> gates, Map<String, String> applicationGraphs, List<String> cutover,
                         List<String> rollback, boolean sensitiveArtifact) {}
    public static Report build(Project project, Path root) throws IOException {
        Map<String, Gate> gates = new TreeMap<>();
        gates.put("structural", gate(project, root, "validate", result ->
            result.path("mismatchedRecords").asLong(-1) == 0 && result.path("unresolvedEndpoints").asLong(-1) == 0
                && result.path("graphContentExact").asBoolean() && result.path("derivedConsistent").asBoolean()
                && result.path("shaclConforms").asBoolean()));
        gates.put("queries", gate(project, root, "functional", result ->
            result.path("cases").isArray() && result.path("cases").size() >= Functional.QUERIES.size()
                && allSucceeded(result.path("cases")) && result.path("sourceHash").asText().matches("[a-f0-9]{64}")));
        gates.put("sourceCompatibility", gate(project, root, "source-compatibility", result ->
            result.path("liveEndpointObserved").asBoolean() && result.path("driverVersion").asText().equals("3.4.13")
                && result.path("serializer").asText().equals("GraphSONMessageSerializerV2d0")
                && result.path("partitionQualifiedLookup").asBoolean() && result.path("propertyFidelity").asBoolean()
                && result.path("edgeFidelity").asBoolean()));
        try { SourceCompatibility.read(project, root); }
        catch (IOException | IllegalArgumentException e) {
            gates.put("sourceCompatibility", new Gate("blocked", List.of(), List.of("Current owned source compatibility observations are not verified")));
        }
        gates.put("crud", gate(project, root, "crud", result ->
            result.path("cases").isArray() && result.path("cases").size() >= 10 && allSucceeded(result.path("cases"))
                && result.path("atomicityExecutionFailureTested").asBoolean()));
        gates.put("application", gate(project, root, "application-build", result ->
            result.path("beforeExitCode").asLong(-1) == 0 && result.path("afterExitCode").asLong(-1) == 0
                && result.path("equivalentCases").isIntegralNumber() && result.path("equivalentCases").asLong() > 0
                && result.path("remainingBlockers").isArray() && result.path("remainingBlockers").isEmpty()));
        gates.put("load", gate(project, root, "test-load", result ->
            result.path("profile").asText().equals("load") && result.path("measuredRepetitions").asInt() >= 3
                && result.path("warmups").asInt() >= 1 && result.path("vertices").asLong() == 100000
                && result.path("edges").asLong() == 1000000 && result.path("unresolvedBatches").asLong(-1) == 0
                && result.path("queueBoundRespected").asBoolean() && result.path("retainedMemoryScalingPassed").asBoolean()
                && result.path("maximumHeapBytes").asLong() == 1073741824
                && result.path("maximumAfterGcHeapBytes").isIntegralNumber()
                && result.path("maximumAfterGcHeapBytes").asLong(-1) >= 0
                && result.path("maximumAfterGcHeapBytes").asLong(Long.MAX_VALUE) <= 858993459));
        gates.put("soak", gate(project, root, "test-soak", result ->
            result.path("measuredDurationMillis").asLong() >= 7200000 && result.path("equivalenceFailures").asLong(-1) == 0
                && result.path("corruptionFailures").asLong(-1) == 0 && result.path("mixedCrudOperations").asLong() > 0));
        gates.put("dependencies", gate(project, root, "dependency-audit", result ->
            result.path("sbomSha256").asText().matches("[a-f0-9]{64}") && result.path("licenseReportSha256").asText().matches("[a-f0-9]{64}")
                && result.path("unresolvedReleaseBlockers").asLong(-1) == 0 && result.path("vulnerabilityScanExecuted").asBoolean()
                && dependencyProof(project, root, result)));
        for (var application : project.applicationHashes().entrySet()) {
            Path file = SafeFiles.resolve(root.getParent(), application.getKey());
            if (!Files.exists(file) || !SafeFiles.fileHash(file).equals(application.getValue()))
                gates.put("applicationInputs", new Gate("stale", List.of(), List.of("Application files changed after assessment")));
        }
        if (project.applicationHashes().isEmpty()) gates.put("applicationInputs",
            new Gate("blocked", List.of(), List.of("No approved application inventory is recorded")));
        if (project.consistency().equals("best-effort") || !project.mapping().fidelity().equals("strict"))
            gates.put("strictEquivalence", new Gate("blocked", List.of(), List.of("Best-effort or reduced-fidelity runs cannot claim strict cutover equivalence")));
        boolean ready = gates.values().stream().allMatch(g -> g.status().equals("succeeded"));
        Map<String, String> graphs = new TreeMap<>();
        if (project.target() != null) for (String role : List.of("canonical", "derived", "metadata", "validation")) graphs.put(role, project.graph(role));
        return new Report(1, ready ? "succeeded" : "blocked", ready ? "ready-for-operator-review" : "blocked", project.runId(),
            project.inputHash(), Map.copyOf(gates), Map.copyOf(graphs),
            List.of("Confirm immutable/quiesced source and all current evidence", "Review exact immutable graph identifiers",
                "Authorize publication separately with the readiness artifact hash", "Deploy the reviewed application configuration manually",
                "Do not restart applications or mutate credentials automatically"),
            List.of("Before target-only writes, restore the previous reviewed application configuration",
                "After target-only writes, require reconciliation; reverse synchronization is not implemented",
                "Cleanup requires separate approval and verified ownership for each named graph"), true);
    }
    private static boolean allSucceeded(JsonNode cases) {
        for (var item : cases) if (!item.path("status").asText().equals("succeeded")) return false;
        return !cases.isEmpty();
    }
    private static byte[] ownedBytes(Project project, Path root, String reference, String checksum) throws IOException {
        if (!checksum.matches("[a-f0-9]{64}") || project.artifacts().stream().noneMatch(a ->
            a.relativePath().equals(reference) && a.ownerRunId().equals(project.runId()) && a.sha256().equals(checksum))) return null;
        Path file = SafeFiles.resolve(root, reference);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(16777217); }
        if (bytes.length > 16777216) return null;
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(checksum)
                ? bytes : null;
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean dependencyProof(Project project, Path root, JsonNode result) {
        try {
            byte[] sbom = ownedBytes(project, root, result.path("sbomPath").asText(), result.path("sbomSha256").asText());
            byte[] licenses = ownedBytes(project, root, result.path("licenseReportPath").asText(), result.path("licenseReportSha256").asText());
            byte[] javaScan = ownedBytes(project, root, result.path("javaVulnerabilityReportPath").asText(),
                result.path("javaVulnerabilityReportSha256").asText());
            byte[] extensionScan = ownedBytes(project, root, result.path("extensionVulnerabilityReportPath").asText(),
                result.path("extensionVulnerabilityReportSha256").asText());
            if (sbom == null || licenses == null || javaScan == null || extensionScan == null
                || new String(licenses, java.nio.charset.StandardCharsets.UTF_8).isBlank()) return false;
            var inventory = Json.MAPPER.readTree(sbom);
            if (!inventory.path("bomFormat").asText().equals("CycloneDX") || !inventory.path("components").isArray()
                || inventory.path("components").isEmpty()) return false;
            for (var component : inventory.path("components"))
                if (component.path("purl").asText().startsWith("pkg:maven/")
                    && !component.path("group").asText().equals("io.github.alekseys.gremlin2rdf")
                    && (!component.path("licenses").isArray() || component.path("licenses").isEmpty())) return false;
            var javaReport = Json.MAPPER.readTree(javaScan);
            var npmTotal = Json.MAPPER.readTree(extensionScan).path("metadata").path("vulnerabilities").path("total");
            return javaReport.path("status").asText().equals("succeeded")
                && javaReport.path("sbomSha256").asText().equals(result.path("sbomSha256").asText())
                && javaReport.path("findings").isArray() && javaReport.path("findings").isEmpty()
                && npmTotal.isIntegralNumber() && npmTotal.canConvertToLong() && npmTotal.longValue() == 0;
        } catch (IOException | IllegalArgumentException | SecurityException e) { return false; }
    }
    private static Gate gate(Project project, Path root, String name, java.util.function.Predicate<JsonNode> predicate) throws IOException {
        var phase = project.phases().get(name);
        String phaseName = name;
        if (phase == null && name.equals("test-load")) { phaseName = "benchmark-load"; phase = project.phases().get(phaseName); }
        if (phase == null && name.equals("test-soak")) { phaseName = "benchmark-soak"; phase = project.phases().get(phaseName); }
        if (phase == null || !phase.status().equals("succeeded") || !project.inputHash().equals(phase.inputHash()) || phase.evidence().isEmpty())
            return new Gate("blocked", List.of(), List.of("Current passing " + name + " evidence is required"));
        List<String> accepted = new ArrayList<>();
        for (String reference : phase.evidence()) {
            var owned = project.artifacts().stream().filter(a -> a.relativePath().equals(reference) && a.ownerRunId().equals(project.runId())).findFirst();
            if (owned.isEmpty()) return new Gate("blocked", List.of(reference), List.of("Evidence ownership is not recorded"));
            byte[] bytes = ownedBytes(project, root, reference, owned.get().sha256());
            if (bytes == null)
                return new Gate("failed", List.of(reference), List.of("Evidence checksum or size verification failed"));
            var envelope = Json.MAPPER.readTree(bytes);
            if (!envelope.has("runId") || !envelope.has("inputHash") || !envelope.has("result")) continue;
            JsonNode measured = envelope.path("result");
            if (measured.has("reportPath") || measured.has("reportSha256")) {
                String relative = measured.path("reportPath").asText();
                String reportChecksum = measured.path("reportSha256").asText();
                byte[] reportBytes = ownedBytes(project, root, relative, reportChecksum);
                if (reportBytes == null || !Json.MAPPER.readTree(reportBytes).equals(Json.MAPPER.readTree(Json.text(measured.path("result")))))
                    return new Gate("failed", List.of(reference), List.of("Private performance report acknowledgement is not verified"));
                measured = measured.path("result");
            }
            String operation = envelope.path("operation").asText();
            boolean nativeTest = Set.of("functional", "test-load", "test-soak").contains(name) && operation.equals("test");
            if (!envelope.path("schemaVersion").isIntegralNumber() || !envelope.path("schemaVersion").canConvertToInt()
                || envelope.path("schemaVersion").intValue() != 1 || !envelope.path("runId").asText().equals(project.runId())
                || !envelope.path("inputHash").asText().equals(project.inputHash()) || !envelope.path("status").asText().equals("succeeded")
                || !(operation.equals(phaseName) || nativeTest) || !measured.path("status").asText().equals("succeeded")
                || !strictMeasuredTypes(measured)
                || !predicate.test(measured))
                return new Gate("blocked", List.of(reference), List.of("Evidence does not establish the required measurable gate"));
            if ((name.equals("validate") || name.equals("functional")) &&
                (!Files.isRegularFile(root.resolve("records.ndjson"), LinkOption.NOFOLLOW_LINKS)
                    || !SafeFiles.fileHash(root.resolve("records.ndjson")).equals(envelope.path("result").path("sourceHash").asText())))
                return new Gate("stale", List.of(reference), List.of("Canonical input changed or is not available for evidence verification"));
            accepted.add(reference);
        }
        return accepted.isEmpty() ? new Gate("blocked", List.of(), List.of("No current owned result envelope establishes this gate"))
            : new Gate("succeeded", List.copyOf(accepted), List.of());
    }
    private static boolean strictMeasuredTypes(JsonNode result) {
        if (!result.isObject()) return false;
        for (String name : List.of("mismatchedRecords", "unresolvedEndpoints", "beforeExitCode", "afterExitCode", "equivalentCases",
            "measuredRepetitions", "warmups", "vertices", "edges", "unresolvedBatches", "maximumHeapBytes",
            "maximumAfterGcHeapBytes", "measuredDurationMillis", "equivalenceFailures", "corruptionFailures",
            "mixedCrudOperations", "unresolvedReleaseBlockers"))
            if (result.has(name) && (!result.path(name).isIntegralNumber() || !result.path(name).canConvertToLong()
                || result.path(name).longValue() < 0)) return false;
        for (String name : List.of("graphContentExact", "derivedConsistent", "shaclConforms", "liveEndpointObserved",
            "partitionQualifiedLookup", "propertyFidelity", "edgeFidelity", "atomicityExecutionFailureTested",
            "queueBoundRespected", "retainedMemoryScalingPassed", "vulnerabilityScanExecuted"))
            if (result.has(name) && !result.path(name).isBoolean()) return false;
        return true;
    }
    public static Map<String, Object> publish(Project project, Path root, Sink sink) throws IOException {
        if (!build(project, root).status().equals("succeeded")) throw new SecurityException("Current readiness gates must pass before publication");
        var phase = project.phases().get("report");
        if (phase == null || !phase.status().equals("succeeded") || !phase.inputHash().equals(project.inputHash()) || phase.evidence().size() != 1)
            throw new SecurityException("Exactly one reviewed current readiness artifact required");
        var artifact = project.artifacts().stream().filter(a -> a.relativePath().equals(phase.evidence().getFirst()) && a.ownerRunId().equals(project.runId()))
            .findFirst().orElseThrow(() -> new SecurityException("Readiness artifact ownership missing"));
        if (!SafeFiles.fileHash(SafeFiles.resolve(root, artifact.relativePath())).equals(artifact.sha256()))
            throw new SecurityException("Readiness artifact changed");
        Approval.require(project, "publish", List.of(project.graph("canonical"), project.graph("derived"), project.graph("metadata"), artifact.sha256()));
        if (!project.target().publicationMode().equals("manual"))
            throw new Gremlin.Unsupported("VERSION_POINTER_PUBLICATION", "Use immutable manual publication; version-pointer atomicity and caching must be independently implemented and proven");
        if (!sink.ownsCurrentRun()) throw new SecurityException("Target ownership must be verified before publication");
        String relative = "artifacts/published-" + project.runId() + "-" + artifact.sha256().substring(0, 16) + ".json";
        Path output = SafeFiles.resolve(root, relative);
        var configuration = Map.of("schemaVersion", 1, "runId", project.runId(), "inputHash", project.inputHash(), "readinessSha256", artifact.sha256(),
            "canonicalGraph", project.graph("canonical"), "derivedGraph", project.graph("derived"), "target", project.target(),
            "automaticApplicationRestart", false, "targetOnlyWritesRequireReconciliationForRollback", true);
        if (Files.exists(output)) {
            if (!Json.MAPPER.readTree(Files.readAllBytes(output)).equals(Json.MAPPER.valueToTree(configuration)))
                throw new SecurityException("Existing publication configuration differs; no overwrite permitted");
        } else { Files.createDirectories(output.getParent()); SafeFiles.atomic(output, configuration); }
        return Map.of("status", "succeeded", "applicationConfiguration", relative, "sha256", SafeFiles.fileHash(output),
            "cutover", "manual-not-executed");
    }
}
