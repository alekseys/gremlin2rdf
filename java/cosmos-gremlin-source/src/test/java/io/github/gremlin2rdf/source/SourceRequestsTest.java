package io.github.gremlin2rdf.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import io.github.gremlin2rdf.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceRequestsTest {
    @TempDir Path root;
    @Test @EnabledOnOs(OS.WINDOWS)
    void realJournalRenameDenialDoesNotRepeatSourceRequestOrLoseAcknowledgedCharge() throws Exception {
        var project=new Project(1,"cosmos-gremlin-to-jena","0".repeat(40),"storage",1,"0.1.0",Mapping.standard(),null,null,
            Project.Limits.standard(),"immutable",Map.of(),List.of(),Map.of(),List.of());
        var journal=root.resolve("source-requests-storage.json");
        var pending=root.resolve("source-requests-storage.json.pending");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var held=new java.util.concurrent.atomic.AtomicReference<java.nio.channels.FileChannel>();
        try(var requests=new SourceRequests(3,1000,"1000",millis->{fail("No provider retry");})) {
            requests.attachJournal(project,root);
            try {
                var failure=assertThrows(Diagnostics.Failure.class,()->requests.execute(()->{
                    calls.incrementAndGet();
                    held.set(java.nio.channels.FileChannel.open(journal,StandardOpenOption.READ,
                        com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE));
                    return new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","3"));
                }));
                var report=SourceDiagnostics.failure(new SourceDiagnostics.Context("source-enumeration-edge-page",failure),"source-operation");
                assertEquals("ARTIFACT_ATOMIC_REPLACE_ACCESS",report.get("code"));
                assertEquals("source-request-journal-write",report.get("stage"));
                assertEquals("atomic-replace",report.get("artifactAction"));assertEquals(5,report.get("artifactAttempts"));
                assertEquals(report,Diagnostics.failure(Diagnostics.acknowledged(Json.MAPPER.valueToTree(report),"execution"),"execution"));
                assertEquals(1,calls.get());assertEquals(0,requests.retries());
                assertTrue(Json.MAPPER.readTree(Files.readAllBytes(journal)).path("inFlight").asBoolean());
                assertFalse(Json.MAPPER.readTree(Files.readAllBytes(pending)).path("inFlight").asBoolean());
                assertEquals("3",Json.MAPPER.readTree(Files.readAllBytes(pending)).path("charge").asText());
            } finally {if(held.get()!=null) held.get().close();}
        }
        try(var resumed=new SourceRequests(3,1000,"1000",millis->{fail("No provider retry");})) {
            resumed.attachJournal(project,root);
            assertEquals("3",resumed.charge());
            assertEquals(1,calls.get());assertFalse(Files.exists(pending));
        }
    }
    static final class Clock implements java.util.function.LongSupplier, SourceRequests.Sleeper {
        long nanos;
        final List<Long> waits=new ArrayList<>();
        public long getAsLong(){return nanos;}
        public void sleep(long millis){waits.add(millis);nanos+=millis*1000000;}
    }
    @Test void actualPinnedTerminalTotalSurvivesFoldedEmptyEdgePageAndPartialFrames() throws Exception {
        var attributes=org.apache.tinkerpop.gremlin.driver.TerminalChargeFixture.terminalAttributes(
            Map.of("x-ms-total-request-charge",17.25,"x-ms-request-charge",0.0));
        var requests=new SourceRequests(0,1000,"1000",millis->{});
        assertEquals(List.of(),requests.execute(()->new SourceRequests.Response(List.of(List.of()),attributes)));
        assertEquals("17.25",requests.charge());
        var totalOnly=org.apache.tinkerpop.gremlin.driver.TerminalChargeFixture.terminalAttributes(
            Map.of("x-ms-request-charge",0.0,"x-ms-total-request-charge",0.0),Map.of("x-ms-total-request-charge",0.0));
        requests.execute(()->new SourceRequests.Response(List.of(List.of()),totalOnly));
        assertEquals("17.25",requests.charge());
    }
    @Test void finiteTypedAndScientificTotalsAreAcceptedWithoutTreatingInvalidTotalsAsZero() throws Exception {
        for(Object value:List.of(0.0,1e-9,1e8,new java.math.BigDecimal("0E-9"),"2.5E+2","0.00000000001",17L)){
            var requests=new SourceRequests(0,1000,"1000000000",millis->{});
            requests.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge",value)));
            assertEquals(0,new java.math.BigDecimal(requests.charge()).compareTo(new java.math.BigDecimal(value.toString())));
        }
        for(Object value:List.of(Double.NaN,Double.POSITIVE_INFINITY,-1,"1E+999","1E-999",true,"private-value",Map.of())){
            var requests=new SourceRequests(0,1000,"1000",millis->{});
            var failure=assertThrows(Diagnostics.Failure.class,()->requests.execute(()->new SourceRequests.Response(List.of(1L),
                Map.of("x-ms-total-request-charge",value,"x-ms-request-charge","0"))));
            var report=Diagnostics.failure(failure,"source-request");
            assertEquals("invalid-total",report.get("chargeObservation"));assertEquals("not-observed",requests.charge());
            assertFalse(Json.text(report).contains("private"));
        }
    }
    @Test void partialOnlyTerminalAndAbsentChargeHaveDistinctSafeReasonsAndCannotRetry() throws Exception {
        for(var fixture:Map.of("partial-charge-only",Map.<String,Object>of("x-ms-request-charge",0.0),
            "missing-total",Map.<String,Object>of()).entrySet()){
            var attributes=org.apache.tinkerpop.gremlin.driver.TerminalChargeFixture.terminalAttributes(fixture.getValue());
            var requests=new SourceRequests(2,1000,"1000",millis->{fail("No retry of unobservable spend");});
            var failure=assertThrows(Diagnostics.Failure.class,()->requests.execute(()->new SourceRequests.Response(List.of(List.of()),attributes)));
            var diagnostic=SourceDiagnostics.failure(new SourceDiagnostics.Context("source-enumeration-edge-page",failure),"source-operation");
            assertEquals(fixture.getKey(),diagnostic.get("chargeObservation"));
            assertEquals(diagnostic,Diagnostics.failure(Diagnostics.acknowledged(Json.MAPPER.valueToTree(diagnostic),"execution"),"execution"));
            assertThrows(Diagnostics.Failure.class,()->requests.execute(()->{fail("No subsequent request");return null;}));
        }
    }
    @Test void ratePacesObservedSpendInsteadOfBecomingACumulativeAllowance() throws Exception {
        var clock=new Clock();
        var requests=new SourceRequests(0,1000,"10000","400",clock,clock);
        var activity=new ArrayList<SourceRequestActivity>();requests.activity(activity::add);
        for(int i=0;i<4;i++) requests.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","400")));
        assertEquals(List.of(1000L,1000L,1000L),clock.waits);
        assertEquals("1600",requests.charge());
        assertTrue(activity.stream().anyMatch(a->a.stage().equals("source-rate-wait") && a.waitMillis()==1000));
        assertEquals(4,activity.stream().filter(a->a.stage().equals("source-request-awaiting-response")).count());
    }
    @Test void elapsedTimeAndProviderBackoffPayPacingDebtWithoutDoubleWaiting() throws Exception {
        var clock=new Clock();
        var requests=new SourceRequests(1,1000,"10000","400",clock,clock);
        requests.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","400")));
        clock.nanos+=250000000;
        requests.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","0")));
        assertEquals(List.of(750L),clock.waits);
        int[] attempts={0}; clock.waits.clear();
        requests.execute(()-> {
            if(++attempts[0]==1)throw new SourceRequests.Failure(true,Map.of("x-ms-total-request-charge","200","x-ms-retry-after-ms","700"),new IOException("fixture"));
            return new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","50"));
        });
        assertEquals(List.of(700L),clock.waits); assertEquals(1,requests.retries());assertEquals("650",requests.charge());
    }
    @Test void rateCannotReplenishTotalAllowanceAndReportsSafeAmountsThroughParent() throws Exception {
        var clock=new Clock();var requests=new SourceRequests(0,1000,"1000","400",clock,clock);
        var error=assertThrows(Diagnostics.Failure.class,()->requests.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","1001.5"))));
        var diagnostic=SourceDiagnostics.failure(new SourceDiagnostics.Context("source-enumeration-vertex-page",error),"source-operation");
        var parent=Diagnostics.failure(Diagnostics.acknowledged(Json.MAPPER.valueToTree(diagnostic),"execution"),"execution");
        assertEquals(diagnostic,parent);assertEquals("SOURCE_RU_BUDGET_EXHAUSTED",parent.get("code"));
        assertEquals("1000",parent.get("ruBudget"));assertEquals("1001.5",parent.get("ruConsumed"));
        assertEquals("0",parent.get("ruRemaining"));assertEquals("400",parent.get("ruPerSecond"));
        clock.nanos+=3600000000000L;
        assertThrows(Diagnostics.Failure.class,()->requests.execute(()->{fail("Waiting does not renew total spend");return null;}));
    }
    @Test void restartPreservesPacingDebtAndTotalSpendWithoutRealSleeps() throws Exception {
        var project = new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "paced", 1, "0.1.0", Mapping.standard(), null, null,
            Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
        var clock=new Clock();
        try(var first=new SourceRequests(0,1000,"10000","400",clock,clock)){
            first.attachJournal(project,root);
            first.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","400")));
        }
        try(var resumed=new SourceRequests(0,1000,"10000","400",clock,clock)){
            resumed.attachJournal(project,root);assertEquals("400",resumed.charge());
            resumed.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","0")));
        }
        assertEquals(List.of(1000L),clock.waits);
    }
    @Test void pacingNeverSubmitsWhenDelayExceedsRemainingDurationOrSleepIsInterrupted() throws Exception {
        var clock=new Clock();var requests=new SourceRequests(0,1000,"10000","400",clock,clock);
        requests.remainingDuration(900);
        requests.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","400")));
        var failure=assertThrows(Diagnostics.Failure.class,()->requests.execute(()->{fail("Deadline prevents next request");return null;}));
        assertEquals("SOURCE_RU_RATE_DEADLINE",Diagnostics.failure(failure,"source-request").get("code"));
        assertTrue(clock.waits.isEmpty());
        var interrupted=new SourceRequests(0,1000,"10000","400",millis->{throw new InterruptedException();},clock);
        interrupted.execute(()->new SourceRequests.Response(List.of(1L),Map.of("x-ms-total-request-charge","400")));
        assertThrows(InterruptedException.class,()->interrupted.execute(()->{fail("Cancellation prevents next request");return null;}));
    }
    @Test void rateAloneStillFailsClosedOnMissingOrMalformedCharge() {
        for(var attributes:List.<Map<String,Object>>of(Map.of(),Map.of("x-ms-total-request-charge","private-provider-value"))){
            var clock=new Clock();var requests=new SourceRequests(1,1000,null,"400",clock,clock);
            var failure=assertThrows(Diagnostics.Failure.class,()->requests.execute(()->new SourceRequests.Response(List.of(1L),attributes)));
            assertEquals("SOURCE_RU_UNOBSERVABLE",Diagnostics.failure(failure,"source-request").get("code"));
            assertFalse(Json.text(Diagnostics.failure(failure,"source-request")).contains("private"));
            assertThrows(Diagnostics.Failure.class,()->requests.execute(()->{fail("Unknown spend cannot be retried");return null;}));
        }
    }
    @Test void legacyJournalIsPreservedButCannotClaimCompleteRequestAccounting() throws Exception {
        var source=new Project.Source("fixture","localhost",443,"db","graph","/pk",null,
            "verified-identity-manifest","identities.ndjson","0".repeat(64),null,"1000",null,false);
        assertFalse(Json.MAPPER.valueToTree(source).has("ruPerSecond"));
        var paced=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(source);
        paced.put("ruPerSecond","400");
        assertEquals("400",Json.MAPPER.treeToValue(paced,Project.Source.class).ruPerSecond());
        var project = new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "legacy", 1, "0.1.0", Mapping.standard(), null, null,
            Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
        String integrity=Json.hash(List.of(1,project.runId(),project.inputHash(),"2",true,0,false));
        var journal=root.resolve("source-requests-legacy.json");
        Files.writeString(journal,Json.text(Map.of("schemaVersion",1,"runId",project.runId(),"inputHash",project.inputHash(),
            "charge","2","chargeObserved",true,"retries",0,"inFlight",false,"integrity",integrity)));
        var bytes=Files.readAllBytes(journal);
        try(var legacy=new SourceRequests(0,1000,"10000",millis->{})){
            var failure=assertThrows(Diagnostics.Failure.class,()->legacy.attachJournal(project,root));
            assertEquals("SOURCE_RU_ACCOUNTING_REVIEW",Diagnostics.failure(failure,"source-request").get("code"));
        }
        assertArrayEquals(bytes,Files.readAllBytes(journal));
        var clock=new Clock();
        try(var altered=new SourceRequests(0,1000,"10000","400",clock,clock)){
            assertThrows(Diagnostics.Failure.class,()->altered.attachJournal(project,root));
        }
        assertArrayEquals(bytes,Files.readAllBytes(journal));
    }
    @Test void sourceThrottlingHonorsReturnedDelayAndAccountsEveryAttempt() throws Exception {
        List<Long> waits = new ArrayList<>(); var requests = new SourceRequests(3, 1000, "10", waits::add);
        int[] attempts = {0};
        Object result = requests.execute(() -> {
            if (++attempts[0] == 1) throw new SourceRequests.Failure(true,
                Map.of("x-ms-retry-after-ms", "700", "x-ms-total-request-charge", "2.5"), new IOException("fixture"));
            return new SourceRequests.Response(List.of(1L), Map.of("x-ms-total-request-charge", "1.5"));
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
        assertThrows(IOException.class, () -> exceeded.execute(() -> new SourceRequests.Response(List.of(1L), Map.of("x-ms-total-request-charge", "1.01"))));
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
            first.execute(() -> new SourceRequests.Response(List.of(1L), Map.of("x-ms-total-request-charge", "2")));
        }
        try (var resumed = new SourceRequests(2, 1000, "3", millis -> {})) {
            resumed.attachJournal(project, root); assertEquals("2", resumed.charge());
            assertThrows(IOException.class, () -> resumed.execute(() -> new SourceRequests.Response(List.of(1L), Map.of("x-ms-total-request-charge", "2"))));
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
            var failure=assertThrows(Diagnostics.Failure.class, () -> resumed.execute(() -> { fail("Unknown in-flight charge must fail closed"); return null; }));
            assertEquals("unconfirmed-in-flight",Diagnostics.failure(failure,"source-request").get("chargeObservation"));
        }
        try (var resumedAgain = new SourceRequests(2,1000,"10",millis->{})) {
            resumedAgain.attachJournal(project,root);
            var failure=assertThrows(Diagnostics.Failure.class,()->resumedAgain.execute(()->{fail("Restart cannot make cost observable");return null;}));
            assertEquals("previous-unobservable",Diagnostics.failure(failure,"source-request").get("chargeObservation"));
        }
    }
    @Test void completedUnknownTerminalChargeCannotBecomeKnownAcrossRestart() throws Exception {
        var project = new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "unknown", 1, "0.1.0", Mapping.standard(), null, null,
            Project.Limits.standard(), "immutable", Map.of(), List.of(), Map.of(), List.of());
        try(var first=new SourceRequests(0,1000,"10",millis->{})){
            first.attachJournal(project,root);
            assertThrows(Diagnostics.Failure.class,()->first.execute(()->new SourceRequests.Response(List.of(List.of()),Map.of())));
        }
        try(var resumed=new SourceRequests(0,1000,"10",millis->{})){
            resumed.attachJournal(project,root);
            var failure=assertThrows(Diagnostics.Failure.class,()->resumed.execute(()->{fail("Preserve unknown cost");return null;}));
            assertEquals("previous-unobservable",Diagnostics.failure(failure,"source-request").get("chargeObservation"));
        }
    }
}
