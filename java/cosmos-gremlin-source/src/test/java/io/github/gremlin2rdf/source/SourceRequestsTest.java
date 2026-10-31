package io.github.gremlin2rdf.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.gremlin2rdf.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceRequestsTest {
    @TempDir Path root;
    @Test void sourceThrottlingHonorsReturnedDelayAndAccountsEveryAttempt() throws Exception {
        List<Long> waits = new ArrayList<>(); var requests = new SourceRequests(3, 1000, "10", waits::add);
        int[] attempts = {0};
        Object result = requests.execute(() -> {
            if (++attempts[0] == 1) throw new SourceRequests.Failure(true,
                Map.of("x-ms-retry-after-ms", "700", "x-ms-request-charge", "2.5"), new IOException("fixture"));
            return new SourceRequests.Response(List.of(1L), Map.of("x-ms-request-charge", "1.5"));
        });
        assertEquals(1L, result); assertEquals(List.of(700L), waits); assertEquals("4.0", requests.charge()); assertEquals(1, requests.retries());
    }
    @Test void sourceLossHasBoundedRetriesAndAuthorizationDenialNeverRetries() {
        var requests = new SourceRequests(2, 1000, null, millis -> {});
        int[] attempts = {0};
        assertThrows(IOException.class, () -> requests.execute(() -> {
            attempts[0]++; throw new SourceRequests.Failure(true, Map.of(), new IOException("fixture disconnect"));
        }));
        assertEquals(3, attempts[0]); assertEquals("not-observed", requests.charge());
        var denied = new SourceRequests(3, 1000, null, millis -> fail("Authorization denial must not retry"));
        assertThrows(IOException.class, () -> denied.execute(() -> { throw new SourceRequests.Failure(false, Map.of(), new SecurityException("fixture")); }));
    }
    @Test void missingOrExceededRuBudgetAndOutOfDeadlineRetryAfterFailClosed() {
        var missing = new SourceRequests(3, 1000, "1", millis -> fail("Unknown charge must not retry"));
        assertThrows(IOException.class, () -> missing.execute(() -> new SourceRequests.Response(List.of(1L), Map.of())));
        var exceeded = new SourceRequests(3, 1000, "1", millis -> {});
        assertThrows(IOException.class, () -> exceeded.execute(() -> new SourceRequests.Response(List.of(1L), Map.of("x-ms-request-charge", "1.01"))));
        var deadline = new SourceRequests(3, 1000, null, millis -> fail("Do not violate provider retry-after"));
        assertThrows(IOException.class, () -> deadline.execute(() -> {
            throw new SourceRequests.Failure(true, Map.of("x-ms-retry-after-ms", 1001), new IOException("fixture"));
        }));
    }
    @Test void restartCannotResetAnAlreadySpentOrUnobservableBudget() throws Exception {
        var project = new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "fixture", 1, "0.1.0", Mapping.standard(), null, null,
            Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
        try (var first = new SourceRequests(2, 1000, "3", millis -> {})) {
            first.attachJournal(project, root);
            first.execute(() -> new SourceRequests.Response(List.of(1L), Map.of("x-ms-request-charge", "2")));
        }
        try (var resumed = new SourceRequests(2, 1000, "3", millis -> {})) {
            resumed.attachJournal(project, root); assertEquals("2", resumed.charge());
            assertThrows(IOException.class, () -> resumed.execute(() -> new SourceRequests.Response(List.of(1L), Map.of("x-ms-request-charge", "2"))));
        }
        try (var exceeded = new SourceRequests(2, 1000, "3", millis -> {})) {
            exceeded.attachJournal(project, root); assertEquals("4", exceeded.charge());
            assertThrows(IOException.class, () -> exceeded.execute(() -> { fail("Spent budget must block before another request"); return null; }));
        }
    }
    @Test void interruptedUnknownCostRequestPreventsConfidentBudgetResume() throws Exception {
        var project = new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "interrupted", 1, "0.1.0", Mapping.standard(), null, null,
            Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
        try (var first = new SourceRequests(2, 1000, "10", millis -> {})) {
            first.attachJournal(project, root);
            assertThrows(IllegalStateException.class, () -> first.execute(() -> { throw new IllegalStateException("Injected process boundary"); }));
        }
        try (var resumed = new SourceRequests(2, 1000, "10", millis -> {})) {
            resumed.attachJournal(project, root); assertEquals("not-observed", resumed.charge());
            assertThrows(IOException.class, () -> resumed.execute(() -> { fail("Unknown in-flight charge must fail closed"); return null; }));
        }
    }
}
