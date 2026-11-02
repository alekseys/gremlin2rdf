package io.github.gremlin2rdf;

import java.util.Set;

/** Fixed-vocabulary, value-free source activity; never contains identities, queries or provider messages. */
public record SourceActivity(String type, String stage, long vertices, long edges, long reservedRequests,
                             long elapsedMillis) {
    public SourceActivity {
        if (!"source-progress".equals(type) || !Set.of("source-enumeration-vertex-page",
            "source-enumeration-edge-page", "source-enumeration-global-vertex-count",
            "source-enumeration-global-edge-count", "source-enumeration-partition-vertex-count",
            "source-enumeration-partition-edge-count", "source-enumeration-checkpoint",
            "source-enumeration-reconciliation", "source-enumeration-merge").contains(stage)
            || vertices < 0 || edges < 0 || reservedRequests < 0 || elapsedMillis < 0)
            throw new IllegalArgumentException("Invalid safe source activity");
    }
}
