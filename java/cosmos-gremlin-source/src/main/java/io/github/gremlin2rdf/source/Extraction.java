package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class Extraction {
    interface Reader {
        GraphRecord read(IdentityEntry entry) throws Exception;
        long count(GraphRecord.Kind kind, GraphRecord.Value partition, String label) throws Exception;
    }
    record Counts(long vertices, long edges) {}
    record Checkpoint(int schemaVersion, String runId, String inputHash, String manifestHash, long manifestOffset,
                      long outputBytes, long records, String outputPrefixHash, String previousKey,
                      Map<String, Counts> initialCounts, String integrity) {
        String calculatedIntegrity() {
            return Json.hash(List.of(schemaVersion, runId, inputHash, manifestHash, manifestOffset, outputBytes, records,
                outputPrefixHash, previousKey, initialCounts));
        }
        Checkpoint signed() {
            return new Checkpoint(schemaVersion, runId, inputHash, manifestHash, manifestOffset, outputBytes, records,
                outputPrefixHash, previousKey, initialCounts, calculatedIntegrity());
        }
    }
    record Result(int schemaVersion, String status, long records, long bytes, String sha256, String identityManifestHash, String consistency,
                  String runId, String inputHash, boolean strictEquivalenceClaim, Map<String, Counts> observedCounts,
                  Map<String, Long> labels, List<String> drift) {}
    static Result run(Project project, Path manifest, Path output, Reader reader) throws Exception {
        SourceCompatibility.authorizeMode(project);
        String manifestHash = SafeFiles.fileHash(manifest);
        if (!manifestHash.equals(project.source().identityManifestHash())) throw new Diagnostics.Failure("SOURCE_EVIDENCE_INTEGRITY","source-extraction-inventory");
        Path directory = output.toAbsolutePath().getParent();
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(output)) throw new Diagnostics.Failure("SOURCE_EXTRACTION_OUTPUT_UNOWNED","source-extraction-output-directory");
        Path lockPath = output.resolveSibling(output.getFileName() + ".lock");
        Path checkpointPath = output.resolveSibling(output.getFileName() + ".checkpoint.json");
        Path completed = output.resolveSibling(output.getFileName() + ".manifest.json");
        for (var path : List.of(output, lockPath, checkpointPath, completed, checkpointPath.resolveSibling(checkpointPath.getFileName() + ".pending")))
            SafeFiles.resolve(directory, path.getFileName().toString());
        try (var lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = lockChannel.tryLock(); } catch (OverlappingFileLockException e) { throw new Diagnostics.Failure("SOURCE_EXTRACTION_BUSY","source-extraction-lock"); }
            if (lock == null) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BUSY","source-extraction-lock");
            try (lock) {
                if (Files.exists(completed)) {
                    if (Files.size(completed) > 1048576) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-extraction-completion");
                    Result result;
                    try { result=Json.MAPPER.readValue(Files.readAllBytes(completed),Result.class); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new Diagnostics.Failure("SOURCE_EXTRACTION_CHECKPOINT","source-extraction-completion"); }
                    if (result==null || result.status()==null || result.inputHash()==null || result.runId()==null ||
                        result.identityManifestHash()==null || result.sha256()==null || result.schemaVersion() != 1 || !result.status().equals("succeeded") || !result.inputHash().equals(project.inputHash())
                        || !result.runId().equals(project.runId()) || !result.identityManifestHash().equals(manifestHash)
                        || result.bytes() != Files.size(output) || !result.sha256().equals(SafeFiles.fileHash(output)))
                        throw new Diagnostics.Failure("SOURCE_EXTRACTION_CHECKPOINT","source-extraction-completion");
                    return result;
                }
                Map<String, GraphRecord.Value> partitions = new TreeMap<>();
                Map<String, Counts> inventory = new TreeMap<>();
                inventory.put("*", new Counts(0, 0));
                long partitionBytes = 0;
                try (var input = Files.newInputStream(manifest)) {
                    byte[] bytes; String previous = "";
                    while ((bytes = frame(input)) != null) {
                        var entry = identity(bytes);
                        String key = entry.orderingKey(), partition = Json.text(entry.partition());
                        if (key.compareTo(previous) <= 0) throw new Diagnostics.Failure("SOURCE_EXTRACTION_RECORD","source-extraction-inventory");
                        previous = key;
                        if (partitions.putIfAbsent(partition, entry.partition()) == null) partitionBytes += partition.getBytes(StandardCharsets.UTF_8).length;
                        if (partitionBytes > 262144 || partitions.size() > 10000) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-extraction-inventory");
                        increment(inventory, partition, entry.kind()); increment(inventory, "*", entry.kind());
                    }
                }
                Checkpoint checkpoint;
                var pendingPath = checkpointPath.resolveSibling(checkpointPath.getFileName() + ".pending");
                if (Files.exists(pendingPath)) {
                    checkpoint = readCheckpoint(pendingPath, project, manifestHash);
                    if (!prefix(output, checkpoint.outputBytes()).equals(checkpoint.outputPrefixHash()))
                        throw new Diagnostics.Failure("SOURCE_EXTRACTION_PREFIX","source-extraction-pending-checkpoint");
                    Files.move(pendingPath, checkpointPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(checkpointPath)) checkpoint = readCheckpoint(checkpointPath, project, manifestHash);
                else {
                    if (Files.exists(output)) throw new Diagnostics.Failure("SOURCE_EXTRACTION_OUTPUT_UNOWNED","source-extraction-output");
                    checkDisk(directory, project);
                    Map<String, Counts> initial = observe(reader, partitions);
                    if (!project.consistency().equals("best-effort") && !initial.equals(inventory))
                        throw new Diagnostics.Failure("SOURCE_EXTRACTION_COUNTS","source-extraction-initial-counts");
                    Files.createFile(output);
                    checkpoint = new Checkpoint(1, project.runId(), project.inputHash(), manifestHash, 0, 0, 0,
                        emptyHash(), "", initial, "").signed();
                    save(checkpointPath, checkpoint);
                }
                var outputDigest = prefixDigest(output, checkpoint.outputBytes());
                if (checkpoint.manifestOffset() < 0 || checkpoint.manifestOffset() > Files.size(manifest)
                    || !snapshot(outputDigest).equals(checkpoint.outputPrefixHash()))
                    throw new Diagnostics.Failure("SOURCE_EXTRACTION_PREFIX","source-extraction-checkpoint-prefix");
                if (!project.consistency().equals("best-effort") && !checkpoint.initialCounts().equals(observe(reader, partitions)))
                    throw new Diagnostics.Failure("SOURCE_EXTRACTION_COUNTS","source-extraction-resume-counts");
                try (var input = Files.newInputStream(manifest);
                     var channel = FileChannel.open(output, StandardOpenOption.WRITE)) {
                    input.skipNBytes(checkpoint.manifestOffset());
                    channel.truncate(checkpoint.outputBytes()); channel.position(checkpoint.outputBytes());
                    byte[] bytes;
                    while ((bytes = frame(input)) != null) {
                        SourceCompatibility.authorizeMode(project);
                        checkDisk(directory, project);
                        var entry = identity(bytes);
                        if (entry.orderingKey().compareTo(checkpoint.previousKey()) <= 0) throw new Diagnostics.Failure("SOURCE_EXTRACTION_RECORD","source-extraction-resume-order");
                        GraphRecord record;
                        try { record=reader.read(entry); }
                        catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
                            throw new Diagnostics.Failure("SOURCE_EXTRACTION_RECORD","source-extraction-record-validation");
                        }
                        catch (Exception failure) { throw new SourceDiagnostics.Context("source-extraction-record-request",failure); }
                        try { entry.verify(record,project); }
                        catch (IllegalArgumentException failure) { throw new Diagnostics.Failure("SOURCE_EXTRACTION_RECORD","source-extraction-record-identity"); }
                        if (project.mapping().fidelity().equals("strict") && !record.complete())
                            throw new Diagnostics.Failure("SOURCE_EXTRACTION_RECORD","source-extraction-record-validation");
                        byte[] line = (Json.text(record) + "\n").getBytes(StandardCharsets.UTF_8);
                        if (line.length - 1 > project.limits().maxRecordBytes()) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-extraction-record-frame");
                        var buffer = ByteBuffer.wrap(line); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
                        outputDigest.update(line);
                        checkpoint = new Checkpoint(1, project.runId(), project.inputHash(), manifestHash,
                            checkpoint.manifestOffset() + bytes.length + 1, channel.position(), checkpoint.records() + 1,
                            snapshot(outputDigest), entry.orderingKey(), checkpoint.initialCounts(), "").signed();
                        save(checkpointPath, checkpoint);
                    }
                }
                if (checkpoint.records() != inventory.get("*").vertices() + inventory.get("*").edges())
                    throw new Diagnostics.Failure("SOURCE_EXTRACTION_COUNTS","source-extraction-record-reconciliation");
                Map<String, Counts> after = observe(reader, partitions);
                List<String> drift = new ArrayList<>();
                if (!after.equals(checkpoint.initialCounts())) drift.add("Source global or partition counts changed");
                if (!after.equals(inventory)) drift.add("Observed source counts differ from identity enumeration");
                Map<String, Long> labels = labels(output, project.limits().maxRecordBytes());
                for (var entry : labels.entrySet()) {
                    var key = Json.MAPPER.readTree(entry.getKey());
                    var kind = GraphRecord.Kind.valueOf(key.get(0).textValue());
                    var partition = Json.MAPPER.treeToValue(key.get(1), GraphRecord.Value.class);
                    if (count(reader,kind,partition,key.get(2).textValue()) != entry.getValue()) drift.add("Partition/label source counts differ from extraction");
                }
                if (!project.consistency().equals("best-effort") && !drift.isEmpty()) throw new Diagnostics.Failure("SOURCE_EXTRACTION_COUNTS","source-extraction-final-reconciliation");
                var result = new Result(1, "succeeded", checkpoint.records(), checkpoint.outputBytes(), checkpoint.outputPrefixHash(), manifestHash,
                    project.consistency(), project.runId(), project.inputHash(), project.mapping().fidelity().equals("strict")
                    && !project.consistency().equals("best-effort"), Map.copyOf(after), Map.copyOf(labels), drift.stream().distinct().toList());
                if (Json.text(result).getBytes(StandardCharsets.UTF_8).length > 1048576) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-extraction-report");
                SafeFiles.atomic(completed, result); return result;
            }
        }
    }
    private static Map<String, Long> labels(Path output, int maximum) throws IOException {
        Map<String, Long> labels = new TreeMap<>(); long bytes = 0;
        try (var input = Files.newInputStream(output)) {
            byte[] frame;
            while ((frame = frame(input, maximum)) != null) {
                var record = Json.MAPPER.readValue(frame, GraphRecord.class);
                String key = Json.text(List.of(record.kind().name(), record.identity().partition(), record.label()));
                if (!labels.containsKey(key)) bytes += key.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > 262144 || labels.size() >= 10000 && !labels.containsKey(key)) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-extraction-label-inventory");
                labels.merge(key, 1L, Math::addExact);
            }
        }
        return labels;
    }
    private static void increment(Map<String, Counts> counts, String key, GraphRecord.Kind kind) {
        Counts previous = counts.getOrDefault(key, new Counts(0, 0));
        counts.put(key, new Counts(previous.vertices() + (kind == GraphRecord.Kind.vertex ? 1 : 0),
            previous.edges() + (kind == GraphRecord.Kind.edge ? 1 : 0)));
    }
    private static Map<String, Counts> observe(Reader reader, Map<String, GraphRecord.Value> partitions) throws Exception {
        Map<String, Counts> counts = new TreeMap<>();
        counts.put("*", new Counts(count(reader,GraphRecord.Kind.vertex, null, null), count(reader,GraphRecord.Kind.edge, null, null)));
        for (var entry : partitions.entrySet()) counts.put(entry.getKey(),
            new Counts(count(reader,GraphRecord.Kind.vertex, entry.getValue(), null), count(reader,GraphRecord.Kind.edge, entry.getValue(), null)));
        if (counts.values().stream().anyMatch(c -> c.vertices() < 0 || c.edges() < 0)) throw new Diagnostics.Failure("SOURCE_EXTRACTION_COUNTS","source-extraction-count-validation");
        return counts;
    }
    private static long count(Reader reader,GraphRecord.Kind kind,GraphRecord.Value partition,String label) throws Exception {
        try { return reader.count(kind,partition,label); }
        catch (Exception failure) {
            throw new SourceDiagnostics.Context(label!=null ? "source-extraction-label-count" :
                partition!=null ? "source-extraction-partition-count" : "source-extraction-global-count",failure);
        }
    }
    private static IdentityEntry identity(byte[] bytes) throws IOException {
        try { return Json.MAPPER.readValue(bytes,IdentityEntry.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new Diagnostics.Failure("SOURCE_EXTRACTION_RECORD","source-extraction-inventory"); }
    }
    private static Checkpoint readCheckpoint(Path path, Project project, String manifestHash) throws IOException {
        if (Files.size(path) > 1048576) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-extraction-checkpoint");
        Checkpoint checkpoint;
        try { checkpoint=Json.MAPPER.readValue(Files.readAllBytes(path),Checkpoint.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new Diagnostics.Failure("SOURCE_EXTRACTION_CHECKPOINT","source-extraction-checkpoint"); }
        if (checkpoint==null || checkpoint.runId()==null || checkpoint.inputHash()==null || checkpoint.manifestHash()==null ||
            checkpoint.integrity()==null || checkpoint.initialCounts()==null || checkpoint.previousKey()==null ||
            checkpoint.outputPrefixHash()==null || checkpoint.schemaVersion() != 1 || !checkpoint.runId().equals(project.runId())
            || !checkpoint.inputHash().equals(project.inputHash()) || !checkpoint.manifestHash().equals(manifestHash)
            || !checkpoint.integrity().equals(checkpoint.calculatedIntegrity()) || checkpoint.outputBytes() < 0 || checkpoint.records() < 0)
            throw new Diagnostics.Failure("SOURCE_EXTRACTION_CHECKPOINT","source-extraction-checkpoint");
        return checkpoint;
    }
    private static void save(Path path, Checkpoint checkpoint) throws IOException {
        if (Json.text(checkpoint).getBytes(StandardCharsets.UTF_8).length > 1048576) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-extraction-checkpoint");
        SafeFiles.atomic(path, checkpoint);
    }
    private static void checkDisk(Path directory, Project project) throws IOException {
        long free = Files.getFileStore(directory).getUsableSpace();
        if (free < project.limits().minimumFreeDiskBytes() || free - project.limits().minimumFreeDiskBytes() < project.limits().maxRecordBytes())
            throw new Diagnostics.Failure("SOURCE_EXTRACTION_DISK","source-extraction-disk");
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String emptyHash() { return HexFormat.of().formatHex(digest().digest()); }
    private static String prefix(Path path, long bytes) throws IOException {
        return snapshot(prefixDigest(path, bytes));
    }
    private static String snapshot(MessageDigest digest) throws IOException {
        try { return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest()); }
        catch (CloneNotSupportedException e) { throw new IOException("Pinned SHA-256 provider must support checkpoint snapshots", e); }
    }
    private static MessageDigest prefixDigest(Path path, long bytes) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) < bytes) throw new Diagnostics.Failure("SOURCE_EXTRACTION_PREFIX","source-extraction-checkpoint-prefix");
        var hash = digest(); byte[] buffer = new byte[65536];
        try (var input = Files.newInputStream(path)) {
            while (bytes > 0) {
                int read = input.read(buffer, 0, (int) Math.min(bytes, buffer.length));
                if (read < 0) throw new Diagnostics.Failure("SOURCE_EXTRACTION_PREFIX","source-extraction-checkpoint-prefix"); hash.update(buffer, 0, read); bytes -= read;
            }
        }
        return hash;
    }
    static byte[] frame(InputStream input) throws IOException {
        return frame(input, 65536);
    }
    private static byte[] frame(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return buffer.toByteArray();
            if (buffer.size() == maximum) throw new Diagnostics.Failure("SOURCE_EXTRACTION_BOUND","source-extraction-frame");
            buffer.write(value);
        }
        if (buffer.size() > 0) throw new Diagnostics.Failure("SOURCE_EXTRACTION_RECORD","source-extraction-frame"); return null;
    }
}
