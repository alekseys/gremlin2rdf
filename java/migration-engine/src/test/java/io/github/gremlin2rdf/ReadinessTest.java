package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadinessTest {
    @TempDir Path root;
    @Test void structuralEvidenceUsesTheOwnedRunSpecificExtractionInsteadOfALiteralDefaultFile() throws Exception {
        var source=root.resolve("records-readiness.ndjson");
        Files.writeString(source,"verified-fixture-bytes");
        var result=new HashMap<String,Object>();
        result.put("status","succeeded");result.put("sourceHash",SafeFiles.fileHash(source));
        result.put("mismatchedRecords",0);result.put("unresolvedEndpoints",0);
        result.put("graphContentExact",true);result.put("derivedConsistent",true);result.put("shaclConforms",true);
        var original=attach(evidence("validate",result),source);
        var phases=new HashMap<>(original.phases());
        phases.put("extract",new Project.Phase("succeeded",List.of(),List.of("records-readiness.ndjson"),original.inputHash()));
        var project=new Project(original.schemaVersion(),original.migrationKind(),original.baselineRepositorySha(),original.runId(),
            original.protocolVersion(),original.workerVersion(),original.mapping(),original.source(),original.target(),original.limits(),
            original.consistency(),phases,original.approvals(),original.applicationHashes(),original.artifacts());
        var readiness=Readiness.build(project,root);
        assertEquals("succeeded",readiness.gates().get("structural").status());
        assertEquals("blocked",readiness.status());
        assertFalse(readiness.diagnostics().isEmpty());
        for (var phase : List.of(
            new Project.Phase("stale", List.of(), List.of("records-readiness.ndjson"), project.inputHash()),
            new Project.Phase("succeeded", List.of(), List.of("records-readiness.ndjson"), "0".repeat(64)),
            new Project.Phase("succeeded", List.of(), List.of(), project.inputHash()))) {
            var changed = new HashMap<>(project.phases());
            changed.put("extract", phase);
            var rejected = new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(),
                project.runId(), project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(),
                project.target(), project.limits(), project.consistency(), changed, project.approvals(),
                project.applicationHashes(), project.artifacts());
            assertEquals("stale", Readiness.build(rejected, root).gates().get("structural").status());
        }
        var unowned = project.artifacts().stream().map(artifact -> artifact.relativePath().endsWith(".ndjson") ?
            new Project.Artifact(artifact.relativePath(), artifact.sha256(), "previous-run", true) : artifact).toList();
        var rejected = new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(),
            project.runId(), project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(),
            project.target(), project.limits(), project.consistency(), project.phases(), project.approvals(),
            project.applicationHashes(), unowned);
        assertEquals("stale", Readiness.build(rejected, root).gates().get("structural").status());
        Files.writeString(source,"changed");
        assertEquals("stale",Readiness.build(project,root).gates().get("structural").status());
        Files.writeString(root.resolve("records.ndjson"),"verified-fixture-bytes");
        assertEquals("stale",Readiness.build(project,root).gates().get("structural").status());
        Files.delete(source);
        assertEquals("stale",Readiness.build(project,root).gates().get("structural").status());
    }
    @Test void legacyCliCanonicalFileStillEstablishesCurrentStructuralEvidence() throws Exception {
        var source=root.resolve("records.ndjson");
        Files.writeString(source,"legacy-bytes");
        var project=evidence("validate",Map.of("status","succeeded","sourceHash",SafeFiles.fileHash(source),
            "mismatchedRecords",0,"unresolvedEndpoints",0,"graphContentExact",true,"derivedConsistent",true,"shaclConforms",true));
        assertEquals("succeeded",Readiness.build(project,root).gates().get("structural").status());
    }
    private Project evidence(String phase, Map<String, Object> result) throws Exception {
        var project = RecoveryTest.project("readiness");
        Path file = root.resolve("evidence.json");
        Files.writeString(file, Json.text(Map.of("schemaVersion", 1, "runId", project.runId(), "inputHash", project.inputHash(),
            "operation", phase.startsWith("benchmark-") ? "test" : phase, "status", "succeeded", "result", result)));
        return new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(), project.runId(),
            project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(), project.target(), project.limits(),
            project.consistency(), Map.of(phase, new Project.Phase("succeeded", List.of(), List.of("evidence.json"), project.inputHash())),
            project.approvals(), project.applicationHashes(), List.of(new Project.Artifact("evidence.json", SafeFiles.fileHash(file), project.runId(), true)));
    }
    private Map<String, Object> load(long heap) {
        var fields = new HashMap<String, Object>();
        fields.put("status", "succeeded");
        fields.put("profile", "load"); fields.put("measuredRepetitions", 3); fields.put("warmups", 1);
        fields.put("vertices", 100000); fields.put("edges", 1000000); fields.put("unresolvedBatches", 0);
        fields.put("queueBoundRespected", true); fields.put("retainedMemoryScalingPassed", true);
        fields.put("maximumHeapBytes", 1073741824L); fields.put("maximumAfterGcHeapBytes", heap);
        return fields;
    }
    @Test void exactMemoryThresholdsAndMalformedMeasurementsCannotEstablishLoadAcceptance() throws Exception {
        assertEquals("succeeded", Readiness.build(evidence("test-load", load(858993459)), root).gates().get("load").status());
        assertEquals("blocked", Readiness.build(evidence("test-load", load(858993460)), root).gates().get("load").status());
        assertEquals("blocked", Readiness.build(evidence("test-load", load(-1)), root).gates().get("load").status());
        var malformed = load(1); malformed.put("vertices", "100000");
        assertEquals("blocked", Readiness.build(evidence("test-load", malformed), root).gates().get("load").status());
        malformed = load(1); malformed.put("queueBoundRespected", "true");
        assertEquals("blocked", Readiness.build(evidence("test-load", malformed), root).gates().get("load").status());
    }
    @Test void missingApplicationBlockersChecksumChangesAndRawReportsDoNotBecomePasses() throws Exception {
        assertEquals("blocked", Readiness.build(evidence("application-build",
            Map.of("beforeExitCode", 0, "afterExitCode", 0, "equivalentCases", 10)), root).gates().get("application").status());
        var project = evidence("test-load", load(1)); Files.writeString(root.resolve("evidence.json"), "{}");
        assertEquals("failed", Readiness.build(project, root).gates().get("load").status());
        project = evidence("test-load", load(1));
        Files.writeString(root.resolve("evidence.json"), Json.text(load(1)));
        var raw = new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(), project.runId(),
            project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(), project.target(), project.limits(),
            project.consistency(), project.phases(), project.approvals(), project.applicationHashes(),
            List.of(new Project.Artifact("evidence.json", SafeFiles.fileHash(root.resolve("evidence.json")), project.runId(), true)));
        assertEquals("blocked", Readiness.build(raw, root).gates().get("load").status());
    }
    private Project attach(Project project, Path file) throws Exception {
        String relative = root.relativize(file).toString().replace('\\', '/');
        var artifacts = new ArrayList<>(project.artifacts());
        artifacts.removeIf(artifact -> artifact.relativePath().equals(relative));
        artifacts.add(new Project.Artifact(relative, SafeFiles.fileHash(file), project.runId(), true));
        return new Project(project.schemaVersion(), project.migrationKind(), project.baselineRepositorySha(), project.runId(),
            project.protocolVersion(), project.workerVersion(), project.mapping(), project.source(), project.target(), project.limits(),
            project.consistency(), project.phases(), project.approvals(), project.applicationHashes(), artifacts);
    }
    @Test void nestedPrivateReportsRequireExactCurrentOwnedBytesAndNativeOperation() throws Exception {
        Path report = root.resolve("private-report.json");
        Files.writeString(report, Json.text(load(1)));
        var project = evidence("benchmark-load", Map.of("status", "succeeded", "reportPath", "private-report.json",
            "reportSha256", SafeFiles.fileHash(report), "result", load(1)));
        assertEquals("failed", Readiness.build(project, root).gates().get("load").status());
        project = attach(project, report);
        assertEquals("succeeded", Readiness.build(project, root).gates().get("load").status());
        Files.writeString(report, "{}");
        assertEquals("failed", Readiness.build(project, root).gates().get("load").status());
        Files.delete(report);
        assertEquals("failed", Readiness.build(project, root).gates().get("load").status());
        var rejected = load(1); rejected.put("status", "blocked");
        assertEquals("blocked", Readiness.build(evidence("test-load", rejected), root).gates().get("load").status());
        project = evidence("test-load", load(1));
        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        envelope.put("operation", "dependency-audit");
        Files.writeString(root.resolve("evidence.json"), Json.text(envelope));
        assertEquals("blocked", Readiness.build(attach(project, root.resolve("evidence.json")), root).gates().get("load").status());
    }
    @Test void overflowingExitCodesCannotBeCoercedIntoSuccessfulBuilds() throws Exception {
        assertEquals("blocked", Readiness.build(evidence("application-build",
            Map.of("status", "succeeded", "beforeExitCode", 4294967296L, "afterExitCode", 0, "equivalentCases", 1,
                "remainingBlockers", List.of())), root).gates().get("application").status());
    }
    @Test void dependencyDigestsAloneAreNotExecutedEvidence() throws Exception {
        Path sbom = root.resolve("sbom.json"), licenses = root.resolve("licenses.txt"),
            javaScan = root.resolve("java-scan.json"), npmScan = root.resolve("npm-scan.json");
        Files.writeString(sbom, Json.text(Map.of("bomFormat", "CycloneDX", "components", List.of(Map.of(
            "purl", "pkg:maven/example/library@1", "group", "example", "licenses", List.of(Map.of("license", Map.of("id", "MIT"))))))));
        Files.writeString(licenses, "Example library: MIT");
        Files.writeString(javaScan, Json.text(Map.of("status", "succeeded", "sbomSha256", SafeFiles.fileHash(sbom), "findings", List.of())));
        Files.writeString(npmScan, Json.text(Map.of("metadata", Map.of("vulnerabilities", Map.of("total", 0)))));
        var result = new HashMap<String, Object>();
        result.put("status", "succeeded"); result.put("sbomSha256", SafeFiles.fileHash(sbom));
        result.put("licenseReportSha256", SafeFiles.fileHash(licenses));
        result.put("unresolvedReleaseBlockers", 0); result.put("vulnerabilityScanExecuted", true);
        assertEquals("blocked", Readiness.build(evidence("dependency-audit", result), root).gates().get("dependencies").status());
        result.put("sbomPath", "sbom.json"); result.put("licenseReportPath", "licenses.txt");
        result.put("javaVulnerabilityReportPath", "java-scan.json"); result.put("javaVulnerabilityReportSha256", SafeFiles.fileHash(javaScan));
        result.put("extensionVulnerabilityReportPath", "npm-scan.json"); result.put("extensionVulnerabilityReportSha256", SafeFiles.fileHash(npmScan));
        var project = evidence("dependency-audit", result);
        for (Path file : List.of(sbom, licenses, javaScan, npmScan)) project = attach(project, file);
        assertEquals("succeeded", Readiness.build(project, root).gates().get("dependencies").status());
        Files.writeString(licenses, "");
        assertEquals("blocked", Readiness.build(project, root).gates().get("dependencies").status());
    }
}
