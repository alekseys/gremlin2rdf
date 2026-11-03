package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Engine {
    public record Checkpoint(int schemaVersion, String runId, String inputHash, String sourceHash,
                             long batch, long offset, long records, String lastBatchHash, String integrity) {
        public String calculatedIntegrity() { return Json.hash(List.of(schemaVersion, runId, inputHash, sourceHash, batch, offset, records, lastBatchHash)); }
        public Checkpoint signed() {
            return new Checkpoint(schemaVersion, runId, inputHash, sourceHash, batch, offset, records, lastBatchHash, calculatedIntegrity());
        }
    }
    public record Result(String status, long records, long batches, long resumedOffset, long elapsedMillis, String inputHash) {}
    public interface FailureHook { void afterCommit(Sink.Batch batch) throws IOException; }
    public record BatchMetrics(long records, long dataQuads, long requestBodyBytes, int queueDepth, int retries, long elapsedNanos) {}
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private Consumer<Checkpoint> observer = checkpoint -> {};
    private Consumer<BatchMetrics> metrics = metric -> {};
    private Consumer<SourceActivity> activity = value -> {};
    private Consumer<SourceRequestActivity> requestActivity = value -> {};
    private volatile long deadlineNanos = Long.MAX_VALUE;
    private volatile boolean deadlineExceeded;
    public void deadline(Long millis) {
        deadlineNanos = millis == null ? Long.MAX_VALUE : System.nanoTime() + millis * 1000000L;
    }
    public boolean deadlineExceeded() { stopping(); return deadlineExceeded; }
    public void observe(Consumer<Checkpoint> observer) { this.observer = observer; }
    public void measure(Consumer<BatchMetrics> metrics) { this.metrics = metrics; }
    public void activity(Consumer<SourceActivity> activity) { this.activity = activity; }
    public void activity(SourceActivity value) { activity.accept(value); }
    public void requestActivity(Consumer<SourceRequestActivity> observer) { requestActivity=observer; }
    public void requestActivity(SourceRequestActivity value) { requestActivity.accept(value); }
    public void cancel() { cancelled.set(true); }
    public void pause() { paused.set(true); }
    public void pauseForDeadline() { deadlineExceeded=true;paused.set(true); }
    public boolean stopping() {
        if (System.nanoTime() >= deadlineNanos) { deadlineExceeded = true; paused.set(true); }
        return cancelled.get() || paused.get();
    }
    public String stoppedStatus() { return cancelled.get() ? "cancelled" : "paused"; }
    public Result load(Project project, Path source, Path artifacts, Sink sink,
                       Consumer<Checkpoint> progress, FailureHook hook) throws IOException {
        long started = System.nanoTime();
        if (deadlineNanos == Long.MAX_VALUE) deadline(project.limits().maximumOperationMillis());
        if (project.source() != null) SourceCompatibility.authorizeMode(project);
        Files.createDirectories(artifacts);
        if (Files.getFileStore(artifacts).getUsableSpace() < project.limits().minimumFreeDiskBytes())
            throw new IOException("Insufficient artifact disk capacity");
        String sourceHash = SafeFiles.fileHash(source);
        Path manifestPath = source.resolveSibling(source.getFileName() + ".manifest.json");
        if (!Files.exists(manifestPath) || Files.size(manifestPath) > 1048576)
            throw new IOException("Complete extraction manifest required before loading");
        var manifest = Json.MAPPER.readTree(Files.readAllBytes(manifestPath));
        if (!sourceHash.equals(manifest.path("sha256").asText()) || manifest.path("bytes").asLong(-1) != Files.size(source)
            || !project.consistency().equals(manifest.path("consistency").asText())
            || manifest.path("records").asLong(-1) < 0
            || project.source() != null && (!manifest.path("status").asText().equals("succeeded")
                || !manifest.path("identityManifestHash").asText().equals(project.source().identityManifestHash())))
            throw new IOException("Incomplete, changed or incompatible extraction manifest");
        var checkpointPath = SafeFiles.resolve(artifacts, "checkpoint.json");
        try (var lock = new RunLock(artifacts);
             var approved = new ApprovedRecords(source, artifacts, sourceHash, project.limits().minimumFreeDiskBytes())) {
            var pendingPath = SafeFiles.resolve(artifacts, "checkpoint.json.pending");
            if (Files.exists(pendingPath)) {
                if (Files.size(pendingPath) > 1048576) throw new IOException("Pending checkpoint exceeds bound");
                var candidate = Json.MAPPER.readValue(Files.readAllBytes(pendingPath), Checkpoint.class);
                if (candidate.schemaVersion() != 1 || !candidate.runId().equals(project.runId())
                    || !candidate.inputHash().equals(project.inputHash()) || !candidate.sourceHash().equals(sourceHash)
                    || !candidate.integrity().equals(candidate.calculatedIntegrity()) || !sink.confirms(candidate))
                    throw new IOException("Pending checkpoint cannot be reconciled; preserve and repair verified state");
                Files.move(pendingPath, checkpointPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            Checkpoint checkpoint = Files.exists(checkpointPath)
                ? Json.MAPPER.readValue(Files.readAllBytes(checkpointPath), Checkpoint.class)
                : new Checkpoint(1, project.runId(), project.inputHash(), sourceHash, -1, 0, 0, "", "").signed();
            if (checkpoint.schemaVersion() != 1 || !checkpoint.runId().equals(project.runId())
                || !checkpoint.inputHash().equals(project.inputHash()) || !checkpoint.sourceHash().equals(sourceHash)
                || !checkpoint.integrity().equals(checkpoint.calculatedIntegrity()) || checkpoint.offset() < 0
                || checkpoint.offset() > Files.size(source))
                throw new IOException("Corrupt or incompatible checkpoint; restore verified backup or create a new run");
            if (checkpoint.batch() >= 0 && !sink.confirms(checkpoint))
                throw new IOException("Acknowledged checkpoint is not present at target; repair before resume");
            long resumed = checkpoint.offset();
            try (var reader = approved.reader(project.limits().maxRecordBytes(), checkpoint.offset())) {
                List<GraphRecord> pending = new ArrayList<>();
                List<org.apache.jena.sparql.core.Quad> pendingQuads = new ArrayList<>();
                long pendingBytes = 0;
                long pendingOffset = checkpoint.offset();
                GraphRecord record;
                while (!stopping() && (record = reader.next()) != null) {
                    if (!record.provenance().consistency().equals(project.consistency()))
                        throw new IOException("Canonical source consistency differs from project");
                    var single = sink.batch(0, reader.offset(), List.of(record));
                    // Single-record framing overestimates shared batch metadata; exact framing is checked before every commit.
                    long singleBytes = sink.serializedBytes(single) + 64L;
                    if (!pending.isEmpty() && pendingBytes + singleBytes > project.limits().batchBytes()) {
                        checkpoint = commit(project, sink, sink.batchFromQuads(checkpoint.batch() + 1, pendingOffset, pending, pendingQuads),
                            checkpoint, checkpointPath, progress, hook);
                        pending.clear(); pendingQuads.clear(); pendingBytes = 0;
                    }
                    pending.add(record); pendingQuads.addAll(single.quads()); pendingBytes += singleBytes;
                    pendingOffset = reader.offset();
                    if (pending.size() == Math.min(project.limits().batchRecords(), project.limits().queueCapacity())) {
                        checkpoint = commit(project, sink, sink.batchFromQuads(checkpoint.batch() + 1, pendingOffset, pending, pendingQuads),
                            checkpoint, checkpointPath, progress, hook);
                        pending.clear();
                        pendingQuads.clear();
                        pendingBytes = 0;
                    }
                }
                if (!pending.isEmpty() && !stopping())
                    checkpoint = commit(project, sink, sink.batchFromQuads(checkpoint.batch() + 1, pendingOffset, pending, pendingQuads),
                        checkpoint, checkpointPath, progress, hook);
            }
            if (!stopping() && checkpoint.records() != manifest.path("records").asLong())
                throw new IOException("Loaded records do not reconcile extraction manifest count");
            if (!sourceHash.equals(SafeFiles.fileHash(source)))
                throw new IOException("Selected canonical input changed during load; approved snapshot batches remain checkpointed");
            return new Result(cancelled.get() ? "cancelled" : paused.get() ? "paused" : "succeeded",
                checkpoint.records(), checkpoint.batch() + 1, resumed, (System.nanoTime() - started) / 1000000, project.inputHash());
        }
    }
    private Checkpoint commit(Project project, Sink sink, Sink.Batch batch, Checkpoint previous, Path path,
                              Consumer<Checkpoint> progress, FailureHook hook) throws IOException {
        RuntimeException failure = null;
        long started = System.nanoTime();
        int retries = 0;
        int requestBytes = sink.serializedBytes(batch);
        if (requestBytes > project.limits().batchBytes()) throw new IOException("Serialized request exceeds approved batch byte bound");
        if (stopping()) return previous;
        for (int attempt = 0; attempt <= project.limits().maxRetries(); attempt++) {
            retries = attempt;
            try { sink.commit(batch); failure = null; break; }
            catch (org.apache.jena.atlas.web.HttpException | org.apache.jena.sparql.engine.http.QueryExceptionHTTP | Sink.UnconfirmedBatch e) {
                if (!(e instanceof Sink.UnconfirmedBatch) && !TargetHttp.transientFailure(e)) throw e;
                failure = e;
                try { sink.reconcile(batch); failure = null; break; }
                catch (org.apache.jena.atlas.web.HttpException | org.apache.jena.sparql.engine.http.QueryExceptionHTTP | Sink.UnconfirmedBatch unresolved) {
                    if (!(unresolved instanceof Sink.UnconfirmedBatch) && !TargetHttp.transientFailure(unresolved)) throw unresolved;
                    if (attempt == project.limits().maxRetries() || stopping()) break;
                    try { Thread.sleep(Math.min(2000, 100L << Math.min(attempt, 5))); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Retry interrupted", interrupted); }
                }
            }
        }
        if (failure != null) throw new IOException("Unresolved target batch; checkpoint unchanged", failure);
        hook.afterCommit(batch);
        Checkpoint next = new Checkpoint(1, project.runId(), project.inputHash(), previous.sourceHash(),
            batch.number(), batch.offset(), previous.records() + batch.records(), batch.hash(), "").signed();
        SafeFiles.atomic(path, next);
        progress.accept(next);
        observer.accept(next);
        metrics.accept(new BatchMetrics(batch.records(), batch.quads().size(), requestBytes, Math.toIntExact(batch.records()),
            retries, System.nanoTime() - started));
        return next;
    }
}
