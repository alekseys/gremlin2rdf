package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

final class Population {
    record Observation(boolean exists, boolean owned, boolean matches, long incidentEdges, String etag) {}
    interface Transport {
        boolean empty() throws Exception;
        Observation inspect(GraphRecord expected, boolean compareContent) throws Exception;
        void create(GraphRecord record) throws Exception;
        void delete(GraphRecord record, String etag) throws Exception;
    }
    record State(int schemaVersion, String runId, String inputHash, String planHash, String mode,
                 long confirmed, boolean intent, long edgesDeleted, long verticesDeleted, String deleting, String integrity) {
        State(int schemaVersion, String runId, String inputHash, String planHash, String mode,
              long confirmed, boolean intent, long edgesDeleted, long verticesDeleted, String integrity) {
            this(schemaVersion, runId, inputHash, planHash, mode, confirmed, intent, edgesDeleted, verticesDeleted, "", integrity);
        }
        State signed() { return new State(schemaVersion, runId, inputHash, planHash, mode, confirmed, intent, edgesDeleted, verticesDeleted, deleting, calculated()); }
        State deletion(String value) {
            return new State(schemaVersion, runId, inputHash, planHash, mode, confirmed, intent, edgesDeleted, verticesDeleted, value, "").signed();
        }
        String calculated() { return Json.hash(List.of(schemaVersion, runId, inputHash, planHash, mode, confirmed, intent, edgesDeleted, verticesDeleted, deleting)); }
    }
    static Map<String, Object> run(Project project, Path root, PopulationPlan plan, String relativeJournal,
                                   boolean cleanup, Transport transport) throws Exception {
        plan.verify(project, root);
        String operation = cleanup ? "source-cleanup" : "source-populate";
        Approval.require(project, operation, plan.resources());
        Path journal = SafeFiles.resolve(root, relativeJournal), records = SafeFiles.resolve(root, plan.recordsPath());
        Path lockPath = SafeFiles.resolve(root, relativeJournal + ".lock");
        if (journal.equals(records)) throw new IOException("Ownership journal cannot replace canonical fixture");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("Competing source writer", e); }
            if (lock == null) throw new IOException("Competing source writer");
            try (lock; var approved = new ApprovedRecords(records, root, plan.recordsSha256(), project.limits().minimumFreeDiskBytes())) {
                Path pending = SafeFiles.resolve(root, relativeJournal + ".pending");
                if (Files.exists(pending)) {
                    State candidate = read(pending, project, plan);
                    if (Files.exists(journal)) {
                        State old = read(journal, project, plan);
                        if (candidate.confirmed() < old.confirmed() || candidate.edgesDeleted() < old.edgesDeleted()
                            || candidate.verticesDeleted() < old.verticesDeleted())
                            throw new IOException("Non-monotonic pending ownership state");
                    }
                    Files.move(pending, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                State state;
                if (Files.exists(journal)) state = read(journal, project, plan);
                else {
                    if (cleanup) throw new SecurityException("Cannot clean up without the recorded ownership journal");
                    if (!transport.empty()) throw new SecurityException("Initial population requires a pre-existing empty dedicated container");
                    state = new State(1, project.runId(), project.inputHash(), plan.hash(), "populate", 0, false, 0, 0, "").signed();
                    SafeFiles.atomic(journal, state);
                }
                if (!cleanup) {
                    if (!state.mode().equals("populate")) throw new IOException("Cannot repopulate a cleaned or cleaning run");
                    long index = 0;
                    try (var reader = approved.reader(project.limits().maxRecordBytes(), 0)) {
                        GraphRecord record;
                        while ((record = reader.next()) != null) {
                            Approval.require(project, operation, plan.resources()); disk(root, project);
                            var observed = transport.inspect(record, true);
                            if (index < state.confirmed()) {
                                if (!observed.exists() || !observed.owned() || !observed.matches()) throw new IOException("Confirmed owned record changed");
                            } else {
                                if (observed.exists() && (!state.intent() || !observed.owned() || !observed.matches()))
                                    throw new SecurityException("Existing or partially written identity cannot be silently adopted");
                                if (!observed.exists()) {
                                    state = new State(1, state.runId(), state.inputHash(), state.planHash(), "populate", index, true, 0, 0, "").signed();
                                    SafeFiles.atomic(journal, state);
                                    Approval.require(project, operation, plan.resources());
                                    transport.create(record);
                                    observed = transport.inspect(record, true);
                                    if (!observed.exists() || !observed.owned() || !observed.matches()) throw new IOException("Source mutation did not preserve complete fixture fidelity");
                                }
                                state = new State(1, state.runId(), state.inputHash(), state.planHash(), "populate", index + 1, false, 0, 0, "").signed();
                                SafeFiles.atomic(journal, state);
                            }
                            index++;
                        }
                    }
                    if (state.confirmed() != plan.vertices() + plan.edges()) throw new IOException("Population count did not reconcile");
                } else {
                    long attempted = state.confirmed() + (state.intent() ? 1 : 0);
                    state = new State(1, state.runId(), state.inputHash(), state.planHash(), "cleanup", state.confirmed(), state.intent(),
                        state.edgesDeleted(), state.verticesDeleted(), "").deletion(state.deleting());
                    SafeFiles.atomic(journal, state);
                    for (var kind : List.of(GraphRecord.Kind.edge, GraphRecord.Kind.vertex)) {
                        long index = 0, visited = 0;
                        try (var reader = approved.reader(project.limits().maxRecordBytes(), 0)) {
                            GraphRecord record;
                            while ((record = reader.next()) != null) {
                                if (index++ >= attempted || record.kind() != kind) continue;
                                Approval.require(project, operation, plan.resources()); disk(root, project);
                                var observed = transport.inspect(record, false);
                                long done = kind == GraphRecord.Kind.edge ? state.edgesDeleted() : state.verticesDeleted();
                                if (visited < done && observed.exists()) throw new IOException("Deleted source identity was recreated; cleanup will not adopt it");
                                if (visited >= done) {
                                    String deleting = kind.name() + ":" + (index - 1);
                                    if (!state.deleting().isEmpty() && !state.deleting().equals(deleting))
                                        throw new IOException("Pending deletion must reconcile before another source mutation");
                                    if (state.deleting().equals(deleting) && observed.exists())
                                        throw new IOException("Uncertain deletion still exists or was recreated; explicit reconciliation required, not blind retry");
                                    if (observed.exists()) {
                                        if (!observed.owned()) throw new SecurityException("Unowned source identity cannot be deleted");
                                        if (kind == GraphRecord.Kind.vertex && observed.incidentEdges() != 0)
                                            throw new SecurityException("Vertex deletion would cascade to unrecorded or foreign incident edges");
                                        if (observed.etag() == null || observed.etag().isEmpty()) throw new IOException("Observed source version required before deletion");
                                        state = state.deletion(deleting);
                                        SafeFiles.atomic(journal, state);
                                        Approval.require(project, operation, plan.resources());
                                        transport.delete(record, observed.etag());
                                        if (transport.inspect(record, false).exists()) throw new IOException("Source deletion was denied or did not reconcile");
                                    }
                                    state = new State(1, state.runId(), state.inputHash(), state.planHash(), "cleanup", state.confirmed(), state.intent(),
                                        kind == GraphRecord.Kind.edge ? visited + 1 : state.edgesDeleted(),
                                        kind == GraphRecord.Kind.vertex ? visited + 1 : state.verticesDeleted(), "").signed();
                                    SafeFiles.atomic(journal, state);
                                }
                                visited++;
                            }
                        }
                    }
                }
                if (!plan.recordsSha256().equals(SafeFiles.fileHash(records)))
                    throw new IOException("Selected canonical fixture changed during operation; approved snapshot writes remain recorded");
                return Map.of("status", "succeeded", "operation", operation, "planHash", plan.hash(),
                    "confirmedRecords", state.confirmed(), "edgesDeleted", state.edgesDeleted(), "verticesDeleted", state.verticesDeleted(),
                    "ownershipJournalSha256", SafeFiles.fileHash(journal));
            }
        }
    }
    private static void disk(Path root, Project project) throws IOException {
        if (Files.getFileStore(root).getUsableSpace() < project.limits().minimumFreeDiskBytes())
            throw new IOException("Insufficient disk for source ownership evidence");
    }
    private static State read(Path path, Project project, PopulationPlan plan) throws IOException {
        if (Files.size(path) > 65536) throw new IOException("Ownership state exceeds bound");
        var state = Json.MAPPER.readValue(Files.readAllBytes(path), State.class);
        if (state.schemaVersion() != 1 || !state.runId().equals(project.runId()) || !state.inputHash().equals(project.inputHash())
            || !state.planHash().equals(plan.hash()) || !Set.of("populate", "cleanup").contains(state.mode())
            || state.confirmed() < 0 || state.confirmed() > plan.vertices() + plan.edges()
            || state.intent() && state.confirmed() == plan.vertices() + plan.edges()
            || state.edgesDeleted() < 0 || state.edgesDeleted() > plan.edges() || state.verticesDeleted() < 0 || state.verticesDeleted() > plan.vertices()
            || state.deleting() == null || !state.deleting().isEmpty() && !state.deleting().matches("(?:edge|vertex):[0-9]+")
            || state.mode().equals("populate") && !state.deleting().isEmpty()
            || !state.integrity().equals(state.calculated())) throw new IOException("Invalid, tampered or changed-input ownership state");
        return state;
    }
}
