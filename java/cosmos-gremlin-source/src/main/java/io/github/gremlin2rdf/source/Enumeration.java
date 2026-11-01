package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read-only string-ID keyset enumeration; provider ordering and coverage are checked, not assumed. */
final class Enumeration {
    record Plan(int schemaVersion, List<GraphRecord.Value> partitions, int pageSize,
                long maximumRequests, long maximumDurationMillis) {
        Plan {
            partitions = List.copyOf(partitions);
            if (schemaVersion != 1 || partitions.isEmpty() || partitions.size() > 1000
                || new HashSet<>(partitions).size() != partitions.size() || pageSize < 1 || pageSize > 100
                || maximumRequests < 1 || maximumRequests > 10000000
                || maximumDurationMillis < 1000 || maximumDurationMillis > 86400000)
                throw new IllegalArgumentException("Bounded explicit enumeration plan required");
        }
    }
    interface Reader {
        default void remainingDuration(long millis) {}
        List<String> vertices(GraphRecord.Value partition, String after, int limit) throws Exception;
        List<String> edges(GraphRecord.Value partition, String owner, String after, int limit) throws Exception;
        long count(GraphRecord.Kind kind, GraphRecord.Value partition) throws Exception;
    }
    record State(int schemaVersion, String runId, String inputHash, String planHash, int partition,
                 String vertexCursor, List<String> pendingVertices, String owner, String edgeCursor,
                 long vertices, long edges, int segments, long requests, long elapsedMillis,
                 long initialVertices, long initialEdges, String integrity) {
        String calculated() {
            return Json.hash(List.of(schemaVersion, runId, inputHash, planHash, partition, vertexCursor,
                pendingVertices, owner, edgeCursor, vertices, edges, segments, requests, elapsedMillis,
                initialVertices, initialEdges));
        }
        State signed() {
            return new State(schemaVersion, runId, inputHash, planHash, partition, vertexCursor,
                pendingVertices, owner, edgeCursor, vertices, edges, segments, requests, elapsedMillis,
                initialVertices, initialEdges, calculated());
        }
    }
    private final Project project;
    private final Plan plan;
    private final Path directory;
    private final Reader reader;
    private State state;
    private long started, elapsedBase;
    private Enumeration(Project project, Plan plan, Path directory, Reader reader) {
        this.project = project; this.plan = plan; this.directory = directory; this.reader = reader;
    }
    static Map<String, Object> run(Project project, Plan plan, Path directory, Reader reader) throws Exception {
        if (project.source() == null || project.consistency().equals("best-effort"))
            throw new IllegalArgumentException("Enumeration requires a declared quiesced or immutable source");
        SafeFiles.resolve(directory.toAbsolutePath().getParent(), directory.getFileName().toString());
        if (!Files.exists(directory)) Files.createDirectory(directory);
        try (var channel = java.nio.channels.FileChannel.open(SafeFiles.resolve(directory, "enumeration.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock;
            try { lock = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException e) { throw new IOException("Enumeration already owned", e); }
            if (lock == null) throw new IOException("Enumeration already owned");
            try (lock) { return new Enumeration(project, plan, directory, reader).run(); }
        }
    }
    private Map<String, Object> run() throws Exception {
        Path checkpoint = SafeFiles.resolve(directory, "checkpoint.json");
        if (Files.exists(SafeFiles.resolve(directory, "checkpoint.json.pending")))
            throw new IOException("Pending enumeration checkpoint requires reviewed reconciliation");
        if (Files.exists(checkpoint)) {
            if (Files.size(checkpoint) > 1048576) throw new IOException("Enumeration checkpoint exceeds bound");
            state = Json.MAPPER.readValue(Files.readAllBytes(checkpoint), State.class);
            if (state.schemaVersion() != 1 || !state.runId().equals(project.runId())
                || !state.inputHash().equals(project.inputHash()) || !state.planHash().equals(Json.hash(plan))
                || !state.integrity().equals(state.calculated()) || state.partition() < 0
                || state.partition() > plan.partitions().size() || state.segments() < 0 || state.requests() < 0
                || state.elapsedMillis() < 0 || state.vertices() < 0 || state.edges() < 0
                || state.pendingVertices().size() > plan.pageSize() || state.segments() > plan.maximumRequests()
                || state.initialVertices() < -1 || state.initialEdges() < -1)
                throw new IOException("Corrupt or incompatible enumeration checkpoint");
        } else {
            try (var files = Files.list(directory)) {
                if (files.anyMatch(p -> !p.getFileName().toString().equals("enumeration.lock")))
                    throw new IOException("Cannot adopt uncheckpointed enumeration artifacts");
            }
            state = new State(1, project.runId(), project.inputHash(), Json.hash(plan), 0, "",
                List.of(), "", "", 0, 0, 0, 0, 0, -1, -1, "").signed();
            save();
        }
        elapsedBase = state.elapsedMillis(); started = System.nanoTime();
        if (state.initialVertices() < 0) {
            long vertices = count(GraphRecord.Kind.vertex, null), edges = count(GraphRecord.Kind.edge, null);
            update(state.partition(), state.vertexCursor(), state.pendingVertices(), state.owner(), state.edgeCursor(),
                state.vertices(), state.edges(), state.segments(), vertices, edges);
        }
        while (state.partition() < plan.partitions().size()) {
            GraphRecord.Value partition = plan.partitions().get(state.partition());
            if (!state.owner().isEmpty()) {
                request();
                List<String> ids;
                try { ids=reader.edges(partition,state.owner(),state.edgeCursor(),plan.pageSize()); }
                catch (Exception failure) { throw new SourceDiagnostics.Context("source-enumeration-edge-page",failure); }
                ordered(ids, state.edgeCursor());
                if (ids.isEmpty()) {
                    update(state.partition(), state.vertexCursor(), state.pendingVertices(), "", "",
                        state.vertices(), state.edges(), state.segments(), state.initialVertices(), state.initialEdges());
                } else {
                    List<IdentityEntry> entries = ids.stream().map(id -> new IdentityEntry(1, GraphRecord.Kind.edge,
                        partition, string(id), string(state.owner()), GraphRecord.EdgeScope.sourceVertex)).toList();
                    segment(entries);
                    update(state.partition(), state.vertexCursor(), state.pendingVertices(), state.owner(),
                        ids.getLast(), state.vertices(), state.edges() + ids.size(), state.segments() + 1,
                        state.initialVertices(), state.initialEdges());
                }
            } else if (!state.pendingVertices().isEmpty()) {
                String owner = state.pendingVertices().getFirst();
                update(state.partition(), state.vertexCursor(), state.pendingVertices().subList(1, state.pendingVertices().size()),
                    owner, "", state.vertices(), state.edges(), state.segments(),
                    state.initialVertices(), state.initialEdges());
            } else {
                request();
                List<String> ids;
                try { ids=reader.vertices(partition,state.vertexCursor(),plan.pageSize()); }
                catch (Exception failure) { throw new SourceDiagnostics.Context("source-enumeration-vertex-page",failure); }
                ordered(ids, state.vertexCursor());
                if (!ids.isEmpty()) segment(ids.stream().map(id -> new IdentityEntry(1, GraphRecord.Kind.vertex,
                    partition, string(id), null, null)).toList());
                update(ids.isEmpty() ? state.partition() + 1 : state.partition(), ids.isEmpty() ? "" : ids.getLast(),
                    ids, "", "", state.vertices() + ids.size(), state.edges(), state.segments() + (ids.isEmpty() ? 0 : 1),
                    state.initialVertices(), state.initialEdges());
            }
        }
        long vertices = count(GraphRecord.Kind.vertex, null), edges = count(GraphRecord.Kind.edge, null);
        if (vertices != state.initialVertices() || edges != state.initialEdges())
            throw new Diagnostics.Failure("SOURCE_COUNTS_CHANGED","source-enumeration");
        if (vertices != state.vertices() || edges != state.edges())
            throw new Diagnostics.Failure("SOURCE_PARTITION_COVERAGE","source-enumeration");
        Map<GraphRecord.Value, long[]> inventory = new HashMap<>();
        for (GraphRecord.Value partition : plan.partitions()) inventory.put(partition, new long[2]);
        for (int i = 0; i < state.segments(); i++) {
            budget();
            for (IdentityEntry entry : readSegment(i)) {
                long[] counts = inventory.get(entry.partition());
                if (counts == null) throw new IOException("Enumeration page contains an unconfigured partition");
                counts[entry.kind() == GraphRecord.Kind.vertex ? 0 : 1]++;
            }
        }
        // Verify each partition independently, not just a matching global sum.
        for (GraphRecord.Value partition : plan.partitions()) {
            if (count(GraphRecord.Kind.vertex, partition) != inventory.get(partition)[0]
                || count(GraphRecord.Kind.edge, partition) != inventory.get(partition)[1])
                throw new Diagnostics.Failure("SOURCE_COUNTS_CHANGED","source-enumeration");
        }
        budget();
        Path output = SafeFiles.resolve(directory, "identities.ndjson");
        if (Files.exists(output)) {
            Path completed = SafeFiles.resolve(directory, "manifest.json");
            if (!Files.exists(completed) || Files.size(completed) > 1048576)
                throw new IOException("Unconfirmed enumeration output must be preserved for reconciliation");
            var previous = Json.MAPPER.readTree(Files.readAllBytes(completed));
            if (!previous.path("inputHash").asText().equals(project.inputHash())
                || !previous.path("planHash").asText().equals(Json.hash(plan))
                || !previous.path("sha256").asText().equals(SafeFiles.fileHash(output)))
                throw new IOException("Completed enumeration output changed");
            return Json.MAPPER.convertValue(previous, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        }
        int pass = 0, count = state.segments();
        while (count > 16) {
            int merged = 0;
            for (int start = 0; start < count; start += 16) {
                budget();
                List<Path> group = new ArrayList<>();
                for (int i = start; i < Math.min(start + 16, count); i++)
                    group.add(SafeFiles.resolve(directory, pass == 0 ? "segment-" + i + ".ndjson"
                        : "merge-" + (pass - 1) + "-" + i + ".ndjson"));
                Path path = SafeFiles.resolve(directory, "merge-" + pass + "-" + merged++ + ".ndjson");
                merge(group, path, false);
            }
            count = merged; pass++;
        }
        List<Path> group = new ArrayList<>();
        for (int i = 0; i < count; i++)
            group.add(SafeFiles.resolve(directory, pass == 0 ? "segment-" + i + ".ndjson"
                : "merge-" + (pass - 1) + "-" + i + ".ndjson"));
        merge(group, output, true);
        Map<String, Object> result = new TreeMap<>();
        result.put("schemaVersion", 1); result.put("status", "succeeded"); result.put("runId", project.runId());
        result.put("inputHash", project.inputHash()); result.put("planHash", Json.hash(plan));
        result.put("sha256", SafeFiles.fileHash(output)); result.put("vertices", vertices); result.put("edges", edges);
        result.put("sourceCountsReconciled",true);
        result.put("reservedRequestAttempts", state.requests()); result.put("sensitiveArtifact", true);
        result.put("edgeScope", "sourceVertex"); result.put("identityTypes", "string-only");
        result.put("compatibility", "enumeration observations only; run source fidelity probe before extraction");
        SafeFiles.atomic(SafeFiles.resolve(directory, "manifest.json"), result);
        return result;
    }
    private static GraphRecord.Value string(String value) {
        return new GraphRecord.Value(GraphRecord.ValueType.string, value);
    }
    private void ordered(List<String> values, String after) throws IOException {
        if (values.size() > plan.pageSize()) throw new IOException("Provider page exceeds enumeration bound");
        String previous = after;
        for (String value : values) {
            if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 4096
                || value.compareTo(previous) <= 0)
                throw new Diagnostics.Failure("SOURCE_REQUEST_REJECTED","source-enumeration-page-validation");
            previous = value;
        }
    }
    private void budget() throws IOException {
        reader.remainingDuration(Math.max(0,plan.maximumDurationMillis()-elapsed()));
        if (elapsedBase + (System.nanoTime() - started) / 1000000 >= plan.maximumDurationMillis()
            || Files.getFileStore(directory).getUsableSpace() < project.limits().minimumFreeDiskBytes())
            throw new Diagnostics.Failure("SOURCE_ENUMERATION_DURATION_LIMIT","source-enumeration");
    }
    private void request() throws IOException {
        budget();
        long reserved = project.limits().maxRetries() + 1L;
        if (reserved > plan.maximumRequests() - state.requests()) throw new Diagnostics.Failure("SOURCE_ENUMERATION_REQUEST_LIMIT","source-enumeration");
        state = new State(1, state.runId(), state.inputHash(), state.planHash(), state.partition(), state.vertexCursor(),
            state.pendingVertices(), state.owner(), state.edgeCursor(), state.vertices(), state.edges(), state.segments(),
            state.requests() + reserved, elapsed(), state.initialVertices(), state.initialEdges(), "").signed();
        save();
    }
    private long count(GraphRecord.Kind kind, GraphRecord.Value partition) throws Exception {
        request();
        try { return reader.count(kind,partition); }
        catch (Exception failure) { throw new SourceDiagnostics.Context(
            "source-enumeration-"+(partition==null ? "global-" : "partition-")+kind.name()+"-count",failure); }
    }
    private long elapsed() { return elapsedBase + (System.nanoTime() - started) / 1000000; }
    private void update(int partition, String cursor, List<String> pending, String owner, String edgeCursor,
                        long vertices, long edges, int segments, long initialVertices, long initialEdges) throws IOException {
        state = new State(1, project.runId(), project.inputHash(), Json.hash(plan), partition, cursor,
            List.copyOf(pending), owner, edgeCursor, vertices, edges, segments, state.requests(), elapsed(),
            initialVertices, initialEdges, "").signed(); save();
    }
    private void save() throws IOException { SafeFiles.atomic(SafeFiles.resolve(directory, "checkpoint.json"), state); }
    private void segment(List<IdentityEntry> entries) throws IOException {
        var sorted = entries.stream().sorted(Comparator.comparing(IdentityEntry::orderingKey)).toList();
        StringBuilder text = new StringBuilder();
        for (IdentityEntry entry : sorted) text.append(Json.text(entry)).append('\n');
        Path path = SafeFiles.resolve(directory, "segment-" + state.segments() + ".ndjson");
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1048576) throw new IOException("Enumeration identity page exceeds byte bound");
        if (Files.exists(path)) {
            if (Files.size(path) > 1048576) throw new IOException("Unconfirmed enumeration page exceeds bound");
            if (!Arrays.equals(Files.readAllBytes(path), bytes)) throw new IOException("Unconfirmed page differs on replay");
        } else {
            try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        }
        SafeFiles.atomic(SafeFiles.resolve(directory, "segment-" + state.segments() + ".json"),
            Map.of("sha256", SafeFiles.fileHash(path), "records", entries.size()));
    }
    private List<IdentityEntry> readSegment(int index) throws IOException {
        Path path = SafeFiles.resolve(directory, "segment-" + index + ".ndjson");
        Path manifest = SafeFiles.resolve(directory, "segment-" + index + ".json");
        if (Files.size(path) > 1048576 || Files.size(manifest) > 65536)
            throw new IOException("Enumeration page exceeds artifact bound");
        var metadata = Json.MAPPER.readTree(Files.readAllBytes(manifest));
        if (!metadata.path("sha256").asText().equals(SafeFiles.fileHash(path)))
            throw new IOException("Confirmed enumeration page changed");
        List<IdentityEntry> entries = new ArrayList<>();
        try (var input = Files.newInputStream(path)) {
            byte[] bytes;
            while ((bytes = Extraction.frame(input)) != null)
                entries.add(Json.MAPPER.readValue(bytes, IdentityEntry.class));
        }
        if (entries.size() > plan.pageSize() || entries.size() != metadata.path("records").asInt(-1))
            throw new IOException("Enumeration page inventory differs");
        return entries;
    }
    private void merge(List<Path> inputs, Path output, boolean finalOutput) throws IOException {
        if (Files.exists(output)) throw new IOException("Unconfirmed merge output requires reviewed reconciliation");
        List<InputStream> streams = new ArrayList<>();
        record Head(int stream, IdentityEntry entry) {}
        var queue = new PriorityQueue<Head>(Comparator.comparing(h -> h.entry().orderingKey()));
        try (var out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
            for (Path path : inputs) {
                InputStream input = Files.newInputStream(path); streams.add(input);
                byte[] frame = Extraction.frame(input);
                if (frame != null) queue.add(new Head(streams.size() - 1, Json.MAPPER.readValue(frame, IdentityEntry.class)));
            }
            String previous = ""; long records = 0;
            while (!queue.isEmpty()) {
                budget(); Head head = queue.remove(); String key = head.entry().orderingKey();
                if (key.compareTo(previous) <= 0) throw new IOException("Duplicate or unordered source identities");
                previous = key; out.write((Json.text(head.entry()) + "\n").getBytes(StandardCharsets.UTF_8)); records++;
                byte[] frame = Extraction.frame(streams.get(head.stream()));
                if (frame != null) queue.add(new Head(head.stream(), Json.MAPPER.readValue(frame, IdentityEntry.class)));
            }
            if (finalOutput && records != state.vertices() + state.edges())
                throw new IOException("Merged identity inventory differs");
        } finally {
            IOException failure = null;
            for (var input : streams) try { input.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            if (failure != null) throw failure;
        }
    }
}
