package io.github.gremlin2rdf.source;

import java.io.IOException;
import java.math.*;
import java.util.*;
import io.github.gremlin2rdf.*;
import java.nio.file.*;
import java.nio.channels.*;

final class SourceRequests implements AutoCloseable {
    record Response(List<?> values, Map<String, Object> attributes) {}
    static final class Failure extends IOException {
        final boolean retryable;
        final Map<String, Object> attributes;
        Failure(boolean retryable, Map<String, Object> attributes, Throwable cause) {
            super("Sanitized source request failure", cause); this.retryable = retryable; this.attributes = attributes;
        }
    }
    interface Attempt { Response run() throws Exception; }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private final int maximum, timeout;
    private final BigDecimal budget, rate;
    private final Sleeper sleeper;
    private final java.util.function.LongSupplier clock;
    private long pacingDelayMillis, pacingStarted, deadlineStarted;
    private long remainingDurationMillis = Long.MAX_VALUE;
    private BigDecimal charge = BigDecimal.ZERO;
    private boolean chargeObserved = true;
    private String chargeObservation;
    private long retries;
    private Path journal;
    private Project project;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean inFlight;
    record State(int schemaVersion, String runId, String inputHash, String charge, boolean chargeObserved, long retries, boolean inFlight, String integrity,
                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                 Long pacingDelayMillis) {
        String calculatedIntegrity() {
            var fields = new ArrayList<Object>(List.of(schemaVersion, runId, inputHash, charge, chargeObserved, retries, inFlight));
            if (pacingDelayMillis != null) fields.add(pacingDelayMillis);
            return Json.hash(fields);
        }
        State signed() { return new State(schemaVersion, runId, inputHash, charge, chargeObserved, retries, inFlight, calculatedIntegrity(), pacingDelayMillis); }
    }
    SourceRequests(int maximum, int timeout, String budget, Sleeper sleeper) {
        this(maximum,timeout,budget,null,sleeper,System::nanoTime);
    }
    SourceRequests(int maximum, int timeout, String budget, String rate, Sleeper sleeper, java.util.function.LongSupplier clock) {
        this.maximum = maximum; this.timeout = timeout; this.budget = budget == null ? null : new BigDecimal(budget);
        this.rate = rate == null ? null : new BigDecimal(rate); this.sleeper = sleeper; this.clock = clock;
        if (this.rate != null && (this.rate.compareTo(BigDecimal.ONE)<0 || this.rate.compareTo(new BigDecimal("1000000000"))>0))
            throw new IllegalArgumentException("Invalid bounded source pacing rate");
    }
    void remainingDuration(long millis) {
        long remaining = remainingDurationMillis == Long.MAX_VALUE ? Long.MAX_VALUE
            : Math.max(0,remainingDurationMillis-(clock.getAsLong()-deadlineStarted)/1000000);
        remainingDurationMillis = Math.min(remaining,Math.max(0,millis)); deadlineStarted=clock.getAsLong();
    }
    void attachJournal(Project project, Path root) throws IOException {
        this.project = project;
        journal = SafeFiles.resolve(root, "source-requests-" + project.runId() + ".json");
        Path lockPath = SafeFiles.resolve(root, journal.getFileName() + ".lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            try { lock = lockChannel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("Competing source operation", e); }
            if (lock == null) throw new IOException("Competing source operation");
            var pending = SafeFiles.resolve(root, journal.getFileName() + ".pending");
            if (Files.exists(pending)) {
                var candidate = readState(pending);
                if (Files.exists(journal)) {
                    var old = readState(journal);
                    if (new BigDecimal(candidate.charge()).compareTo(new BigDecimal(old.charge())) < 0
                        || !old.chargeObserved() && candidate.chargeObserved() || candidate.retries() < old.retries())
                        throw new Diagnostics.Failure("SOURCE_REQUEST_JOURNAL","source-request-pending-journal");
                }
                Files.move(pending, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.exists(journal)) {
                var state = readState(journal); charge = new BigDecimal(state.charge()); chargeObserved = state.chargeObserved(); retries = state.retries();
                pacingDelayMillis = state.pacingDelayMillis() == null ? 0 : state.pacingDelayMillis();
                pacingStarted=clock.getAsLong();
                if (!chargeObserved) chargeObservation="previous-unobservable";
                if (state.inFlight()) { chargeObserved = false; chargeObservation="unconfirmed-in-flight"; persist(); }
            }
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    private State readState(Path path) throws IOException {
        if (Files.size(path) > 65536) throw new Diagnostics.Failure("SOURCE_REQUEST_JOURNAL","source-request-journal");
        State state;
        try { state=Json.MAPPER.readValue(Files.readAllBytes(path),State.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new Diagnostics.Failure("SOURCE_REQUEST_JOURNAL","source-request-journal"); }
        if (state==null || state.runId()==null || state.inputHash()==null || state.integrity()==null ||
            !Set.of(1,2).contains(state.schemaVersion()) || !state.runId().equals(project.runId()) || !state.inputHash().equals(project.inputHash())
            || !state.integrity().equals(state.calculatedIntegrity()) || new BigDecimal(state.charge()).signum() < 0 || state.retries() < 0
            || state.pacingDelayMillis()!=null && state.pacingDelayMillis()<0
            || (rate == null) != (state.pacingDelayMillis()==null))
            throw new Diagnostics.Failure("SOURCE_REQUEST_JOURNAL","source-request-journal");
        if (state.schemaVersion()==1)
            throw new Diagnostics.Failure("SOURCE_RU_ACCOUNTING_REVIEW","source-request-journal");
        return state;
    }
    private void persist() throws IOException {
        if (journal != null) SafeFiles.atomic(journal, new State(2, project.runId(), project.inputHash(), charge.toPlainString(), chargeObserved, retries, inFlight, "",
            rate == null ? null : pacingDelayMillis).signed());
    }
    Object execute(Attempt request) throws Exception {
        for (int attempt = 0; ; attempt++) {
            if ((budget != null || rate != null) && !chargeObserved) throw budgetFailure("SOURCE_RU_UNOBSERVABLE");
            if (budget != null && charge.compareTo(budget) >= 0) throw budgetFailure("SOURCE_RU_BUDGET_EXHAUSTED");
            waitWithinDeadline(0);
            pace();
            try {
                inFlight = true; persist();
                Response response = request.run(); account(response.attributes());
                if (response.values().size() != 1) throw new IOException("Unexpected bounded source result shape");
                return response.values().getFirst();
            } catch (Failure failure) {
                try { account(failure.attributes); }
                catch (Diagnostics.Failure budgetFailure) { budgetFailure.initCause(failure);throw budgetFailure; }
                if (!failure.retryable || attempt >= maximum) throw failure;
                long wait = Math.min(2000, 100L << attempt);
                Object after = failure.attributes.get("x-ms-retry-after-ms");
                if (after != null) {
                    long requested = new BigDecimal(after.toString()).longValueExact();
                    if (requested < 0 || requested > timeout) throw new IOException("Provider retry-after exceeds the approved deadline");
                    wait = Math.max(wait, requested);
                }
                retries++; persist(); waitWithinDeadline(wait);
            }
        }
    }
    private void account(Map<String, Object> attributes) throws IOException {
        inFlight = false;
        // ResultSet exposes only terminal attributes; a chunk charge cannot establish a traversal's total.
        Object value = attributes.get("x-ms-total-request-charge");
        if (value == null) {
            chargeObserved = false;
            chargeObservation = attributes.containsKey("x-ms-request-charge") ? "partial-charge-only" : "missing-total";
        }
        else {
            BigDecimal observed;
            try {
                observed = requestCharge(value);
            } catch (NumberFormatException failure) {
                chargeObserved=false; chargeObservation="invalid-total"; persist(); throw budgetFailure("SOURCE_RU_UNOBSERVABLE");
            }
            charge = charge.add(observed);
            if (rate != null) {
                pacingDelayMillis=observed.multiply(new BigDecimal("1000")).divide(rate,0,RoundingMode.CEILING).longValueExact();
                pacingStarted=clock.getAsLong();
            }
        }
        persist();
        if ((budget != null || rate != null) && !chargeObserved) throw budgetFailure("SOURCE_RU_UNOBSERVABLE");
        if (budget != null && charge.compareTo(budget) > 0) throw budgetFailure("SOURCE_RU_BUDGET_EXHAUSTED");
    }
    static BigDecimal requestCharge(Object value) {
        if (!(value instanceof String || value instanceof Number)) throw new NumberFormatException();
        String text=value.toString();
        if (text.length()>64 || !text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?"))
            throw new NumberFormatException();
        var amount=new BigDecimal(text);
        if (amount.signum()<0 || amount.precision()>30 || amount.scale() < -15 || amount.scale()>20
            || amount.compareTo(new BigDecimal("1000000000000000"))>0)
            throw new NumberFormatException();
        return amount.signum()==0 ? BigDecimal.ZERO : amount;
    }
    private Diagnostics.Failure budgetFailure(String code) {
        var report=new TreeMap<String,Object>(Diagnostics.report(code,"source-request"));
        if (budget != null) report.put("ruBudget",budget.toPlainString());
        if (chargeObserved) {
            report.put("ruConsumed",charge.toPlainString());
            if (budget != null) report.put("ruRemaining",budget.subtract(charge).max(BigDecimal.ZERO).toPlainString());
        }
        if (rate != null) report.put("ruPerSecond",rate.toPlainString());
        if (chargeObservation != null) report.put("chargeObservation",chargeObservation);
        return Diagnostics.acknowledged(Json.MAPPER.valueToTree(report),"source-request");
    }
    private void pace() throws Exception {
        if (rate == null) return;
        long elapsed=Math.max(0,(clock.getAsLong()-pacingStarted)/1000000);
        waitWithinDeadline(Math.max(0,pacingDelayMillis-elapsed));
        pacingDelayMillis=0; persist();
    }
    private void waitWithinDeadline(long millis) throws Exception {
        long remaining=remainingDurationMillis == Long.MAX_VALUE ? Long.MAX_VALUE
            : Math.max(0,remainingDurationMillis-(clock.getAsLong()-deadlineStarted)/1000000);
        if (millis>=remaining) throw budgetFailure("SOURCE_RU_RATE_DEADLINE");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Source pacing interrupted");
        if (millis>0) sleeper.sleep(millis);
        if (remainingDurationMillis!=Long.MAX_VALUE && (clock.getAsLong()-deadlineStarted)/1000000>=remainingDurationMillis)
            throw budgetFailure("SOURCE_RU_RATE_DEADLINE");
    }
    long retries() { return retries; }
    String charge() { return chargeObserved ? charge.toPlainString() : "not-observed"; }
    @Override public void close() throws IOException {
        try { if (lock != null && lock.isValid()) lock.release(); }
        finally { if (lockChannel != null) lockChannel.close(); }
    }
}
