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
    private final BigDecimal budget;
    private final Sleeper sleeper;
    private BigDecimal charge = BigDecimal.ZERO;
    private boolean chargeObserved = true;
    private long retries;
    private Path journal;
    private Project project;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean inFlight;
    record State(int schemaVersion, String runId, String inputHash, String charge, boolean chargeObserved, long retries, boolean inFlight, String integrity) {
        String calculatedIntegrity() { return Json.hash(List.of(schemaVersion, runId, inputHash, charge, chargeObserved, retries, inFlight)); }
        State signed() { return new State(schemaVersion, runId, inputHash, charge, chargeObserved, retries, inFlight, calculatedIntegrity()); }
    }
    SourceRequests(int maximum, int timeout, String budget, Sleeper sleeper) {
        this.maximum = maximum; this.timeout = timeout; this.budget = budget == null ? null : new BigDecimal(budget); this.sleeper = sleeper;
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
                if (state.inFlight()) { chargeObserved = false; persist(); }
            }
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    private State readState(Path path) throws IOException {
        if (Files.size(path) > 65536) throw new Diagnostics.Failure("SOURCE_REQUEST_JOURNAL","source-request-journal");
        State state;
        try { state=Json.MAPPER.readValue(Files.readAllBytes(path),State.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new Diagnostics.Failure("SOURCE_REQUEST_JOURNAL","source-request-journal"); }
        if (state==null || state.runId()==null || state.inputHash()==null || state.integrity()==null ||
            state.schemaVersion() != 1 || !state.runId().equals(project.runId()) || !state.inputHash().equals(project.inputHash())
            || !state.integrity().equals(state.calculatedIntegrity()) || new BigDecimal(state.charge()).signum() < 0 || state.retries() < 0)
            throw new Diagnostics.Failure("SOURCE_REQUEST_JOURNAL","source-request-journal");
        return state;
    }
    private void persist() throws IOException {
        if (journal != null) SafeFiles.atomic(journal, new State(1, project.runId(), project.inputHash(), charge.toPlainString(), chargeObserved, retries, inFlight, "").signed());
    }
    Object execute(Attempt request) throws Exception {
        for (int attempt = 0; ; attempt++) {
            if (budget != null && (!chargeObserved || charge.compareTo(budget) >= 0))
                throw new Diagnostics.Failure(chargeObserved ? "SOURCE_RU_BUDGET_EXHAUSTED" : "SOURCE_RU_UNOBSERVABLE","source-request");
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
                retries++; persist(); sleeper.sleep(wait);
            }
        }
    }
    private void account(Map<String, Object> attributes) throws IOException {
        inFlight = false;
        Object value = attributes.get("x-ms-request-charge");
        if (value == null) chargeObserved = false;
        else {
            var observed = new BigDecimal(value.toString());
            if (observed.signum() < 0) throw new IOException("Negative source request charge");
            charge = charge.add(observed);
        }
        persist();
        if (budget != null && (!chargeObserved || charge.compareTo(budget) > 0))
            throw new Diagnostics.Failure(chargeObserved ? "SOURCE_RU_BUDGET_EXHAUSTED" : "SOURCE_RU_UNOBSERVABLE","source-request");
    }
    long retries() { return retries; }
    String charge() { return chargeObserved ? charge.toPlainString() : "not-observed"; }
    @Override public void close() throws IOException {
        try { if (lock != null && lock.isValid()) lock.release(); }
        finally { if (lockChannel != null) lockChannel.close(); }
    }
}
