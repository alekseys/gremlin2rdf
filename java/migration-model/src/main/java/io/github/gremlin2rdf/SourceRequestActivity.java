package io.github.gremlin2rdf;

import java.util.Set;

public record SourceRequestActivity(String type,String stage,long waitMillis,long retries) {
    public SourceRequestActivity {
        if (!"source-request-progress".equals(type) || !Set.of("source-request-awaiting-response",
            "source-rate-wait","source-retry-wait","source-request-completed").contains(stage)
            || waitMillis<0 || retries<0)
            throw new IllegalArgumentException("Invalid safe source request activity");
    }
}
