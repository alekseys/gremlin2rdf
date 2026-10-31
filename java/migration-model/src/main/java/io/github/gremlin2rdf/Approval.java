package io.github.gremlin2rdf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public record Approval(int schemaVersion, String runId, String operation, List<String> resources,
                       String inputHash, String expiresAt, String keyRef, String signature) {
    public Approval {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported authorization version");
        resources = resources.stream().sorted().toList();
        Project.reference(keyRef);
        if (keyRef == null) throw new IllegalArgumentException("Authorization verification key required");
    }
    private String payload() { return Json.text(List.of(schemaVersion, runId, operation, resources, inputHash, expiresAt, keyRef)); }
    private byte[] mac() {
        try {
            byte[] key = Project.secret(keyRef).getBytes(StandardCharsets.UTF_8);
            if (key.length < 32) throw new IllegalArgumentException("Authorization key must be at least 32 bytes");
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public void verify(Project project, String requestedOperation, List<String> requestedResources) {
        if (!runId.equals(project.runId()) || !operation.equals(requestedOperation)
            || !resources.equals(requestedResources.stream().sorted().toList()) || !inputHash.equals(project.inputHash())
            || !Instant.parse(expiresAt).isAfter(Instant.now()) || signature == null
            || !MessageDigest.isEqual(mac(), java.util.HexFormat.of().parseHex(signature)))
            throw new SecurityException("Missing, expired, changed-input or incorrectly scoped authorization");
    }
    public static void require(Project project, String operation, List<String> resources) {
        for (Approval approval : project.approvals()) {
            if (approval.operation().equals(operation) && approval.runId().equals(project.runId())) {
                approval.verify(project, operation, resources); return;
            }
        }
        throw new SecurityException("Explicit scoped operator authorization required");
    }
    public static Approval sign(Project project, String operation, List<String> resources, String expires, String keyRef) {
        Approval unsigned = new Approval(1, project.runId(), operation, resources, project.inputHash(), expires, keyRef, null);
        return new Approval(1, project.runId(), operation, unsigned.resources(), project.inputHash(), expires, keyRef,
            java.util.HexFormat.of().formatHex(unsigned.mac()));
    }
}
