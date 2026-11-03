package io.github.gremlin2rdf.testkit;

import io.github.gremlin2rdf.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.tdb2.TDB2Factory;

public final class Benchmark {
    private Benchmark() {}
    public record Configuration(int schemaVersion, String mode, String profile, long seed, int warmups,
                                int repetitions, long soakDurationMillis, int batchRecords, int batchBytes,
                                int queueCapacity, long minimumFreeDiskBytes) {
        public Configuration {
            if (schemaVersion != 1 || !List.of("load", "soak").contains(mode) || !List.of("semantic", "ci", "load").contains(profile)
                || warmups < 1 || warmups > 10 || repetitions < 3 || repetitions > 10 || soakDurationMillis < 0
                || soakDurationMillis > 86400000 || mode.equals("soak") && soakDurationMillis < 100
                || batchRecords < 1 || batchRecords > 1000 || batchBytes < 262144 || batchBytes > 8388608
                || queueCapacity < 1 || queueCapacity > 1000 || minimumFreeDiskBytes < 0)
                throw new IllegalArgumentException("Invalid bounded benchmark configuration");
        }
    }
    public record Metrics(long records, long dataQuads, long requestBodyBytes, long elapsedMillis,
                          double recordsPerSecond, double dataQuadsPerSecond, double bodyBytesPerSecond,
                          long p50BatchNanos, long p95BatchNanos, long p99BatchNanos, long retries,
                          int maximumQueueDepth, long afterGcHeapBytes, long gcCount, long gcMillis) {}
    public record Repetition(String status, String profile, String sourceSha256, String inputHash, Metrics migration,
                             Validation.Report structural, boolean recoveryPassed, long mixedCrudOperations,
                             long soakMillis, long equivalenceFailures, long corruptionFailures) {}
    private static final class Measurement {
        long records, quads, bytes, retries, count; int depth;
        final long[] latency = new long[4096];
        void accept(Engine.BatchMetrics metric) {
            records += metric.records(); quads += metric.dataQuads(); bytes += metric.requestBodyBytes();
            retries += metric.retries(); depth = Math.max(depth, metric.queueDepth()); count++;
            int bucket = Math.max(0, Math.min(latency.length - 1, (int) (Math.log(Math.max(1, metric.elapsedNanos())) / Math.log(1.01))));
            latency[bucket]++;
        }
        long quantile(double fraction) {
            long target = (long) Math.ceil(count * fraction), cumulative = 0;
            for (int i = 0; i < latency.length; i++) if ((cumulative += latency[i]) >= target) return (long) Math.ceil(Math.pow(1.01, i + 1));
            return 0;
        }
    }
    public static void main(String[] args) {
        try {
            if (args.length == 2 && args[0].equals("serve-private")) { serve(Path.of(args[1])); return; }
            if (args.length == 4 && args[0].equals("crash-worker")) { crashWorker(args); return; }
            boolean parentOwned = args.length == 5 && args[4].equals("--parent-owned");
            if ((args.length != 4 && !parentOwned) || !args[0].equals("run") || !args[1].equals("--private-fixture-confirmed"))
                throw new SecurityException("Explicit private fixture confirmation required");
            if (parentOwned) {
                ProcessHandle.current().parent().orElseThrow(() -> new SecurityException("Owned benchmark parent required"))
                    .onExit().thenRun(() -> System.exit(2));
                Thread.startVirtualThread(() -> {
                    try { while (System.in.read() != -1) {} System.exit(2); }
                    catch (IOException error) { System.exit(2); }
                });
            }
            if (Files.size(Path.of(args[2])) > 65536) throw new IOException("Benchmark config exceeds bound");
            Configuration config = Json.MAPPER.readValue(Files.readAllBytes(Path.of(args[2])), Configuration.class);
            var result = run(config, Path.of(args[3]));
            System.out.println(Json.text(parentOwned ? Map.of("status", result.get("status"),
                "reportSha256", SafeFiles.fileHash(Path.of(args[3]).resolve("report.json"))) : result));
        } catch (Exception e) { System.out.println(Json.text(Main.diagnostic(e))); System.exit(2); }
    }
    public static Map<String, Object> run(Configuration config, Path output) throws Exception {
        if (Runtime.getRuntime().maxMemory() != 1073741824L) throw new IllegalArgumentException("Reference worker must use -Xmx1g");
        Path root = output.toAbsolutePath().normalize(); Files.createDirectory(root);
        long freeBytes = Files.getFileStore(root).getUsableSpace();
        if (freeBytes < config.minimumFreeDiskBytes()) {
            var blocked = Map.of("schemaVersion", 1, "status", "blocked", "code", "INSUFFICIENT_BENCHMARK_DISK",
                "requiredBytes", config.minimumFreeDiskBytes(), "availableBytes", freeBytes, "configuration", config,
                "remediation", "Run the committed benchmark configuration in a new private output directory on a sufficiently provisioned reference host.");
            SafeFiles.atomic(root.resolve("report.json"), blocked); return blocked;
        }
        SafeFiles.atomic(root.resolve("ownership.json"), Map.of("schemaVersion", 1, "kind", "private-testkit",
            "owner", UUID.randomUUID().toString(), "configurationHash", Json.hash(config), "noLiveTdb2Files", true));
        var semantic = Synthetic.Profile.semantic(config.seed());
        var profile = switch (config.profile()) {
            case "semantic" -> semantic; case "ci" -> Synthetic.Profile.ci(config.seed()); default -> Synthetic.Profile.load(config.seed());
        };
        Path warmRecords = root.resolve("warmup.ndjson"), records = root.resolve("records.ndjson");
        Synthetic.generate(semantic, warmRecords); Synthetic.generate(profile, records);
        List<Repetition> warmups = new ArrayList<>(), measured = new ArrayList<>();
        for (int i = 0; i < config.warmups(); i++) warmups.add(repetition(config, semantic, warmRecords, root.resolve("warmup-" + i), false, 0));
        Repetition recoveryCheck = repetition(config, semantic, warmRecords, root.resolve("recovery-check"), true, 0);
        for (int i = 0; i < config.repetitions(); i++) measured.add(repetition(config, profile, records, root.resolve("measured-" + i), false,
            config.mode().equals("soak") && i == 0 ? config.soakDurationMillis() : 0));
        long maximumHeap = measured.stream().mapToLong(r -> r.migration().afterGcHeapBytes()).max().orElseThrow();
        boolean queue = measured.stream().allMatch(r -> r.migration().maximumQueueDepth() <= config.queueCapacity());
        long warmHeap = warmups.stream().mapToLong(r -> r.migration().afterGcHeapBytes()).max().orElseThrow();
        // A fixed allowance, not a dataset-relative limit, detects retained-memory growth between profiles.
        boolean scaling = maximumHeap <= warmHeap + 134217728L;
        boolean loadAcceptance = config.profile().equals("load") && maximumHeap < 858993459L && scaling && queue;
        long soak = measured.stream().mapToLong(Repetition::soakMillis).max().orElseThrow();
        Map<String, Object> report = new TreeMap<>();
        report.put("schemaVersion", 1); report.put("status", "succeeded"); report.put("mode", config.mode()); report.put("profile", config.profile());
        report.put("configuration", config); report.put("configurationHash", Json.hash(config)); report.put("sourceSha256", SafeFiles.fileHash(records));
        report.put("vertices", profile.vertices()); report.put("edges", profile.edges()); report.put("warmups", config.warmups());
        report.put("measuredRepetitions", config.repetitions()); report.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("maximumAfterGcHeapBytes", maximumHeap); report.put("retainedMemoryScalingPassed", scaling);
        report.put("queueBoundRespected", queue); report.put("unresolvedBatches", 0); report.put("measuredDurationMillis", soak);
        report.put("mixedCrudOperations", measured.stream().mapToLong(Repetition::mixedCrudOperations).sum());
        report.put("equivalenceFailures", 0); report.put("corruptionFailures", 0);
        report.put("loadAcceptance", loadAcceptance ? "succeeded" : "blocked-profile-or-memory-gate");
        report.put("soakAcceptance", soak >= 7200000 ? "succeeded" : "blocked-duration-not-executed");
        report.put("warmupResults", warmups); report.put("repetitions", measured);
        report.put("recoveryCheck", recoveryCheck);
        report.put("latencyEstimator", "1-percent-logarithmic-upper-bound; batch confirmation and checkpoint latency, not individual HTTP latency");
        report.put("networkBytes", "not-observed; requestBodyBytes exclude HTTP/TLS overhead");
        report.put("sourceRuDiagnostics", "not-observed-local-canonical-source"); report.put("liveCosmosCompatibility", "blocked-no-live-endpoints");
        report.put("environment", Map.of("jdk", Runtime.version().toString(), "jena", "6.2.0", "os", System.getProperty("os.name"),
            "architecture", System.getProperty("os.arch"), "processors", Runtime.getRuntime().availableProcessors(),
            "workerHeap", "-Xmx1g", "targetHeap", "-Xmx2g", "target", "separate-loopback-Fuseki-TDB2-private-process",
            "writerConcurrency", 1, "targetCpuDiskLatencyRss", "not-observed-without-platform-metrics"));
        SafeFiles.atomic(root.resolve("report.json"), report); return Map.copyOf(report);
    }
    private static Repetition repetition(Configuration config, Synthetic.Profile profile, Path records, Path root,
                                         boolean recovery, long soakDuration) throws Exception {
        Files.createDirectory(root);
        SafeFiles.atomic(root.resolve("private-target.json"), Map.of("schemaVersion", 1, "kind", "private-testkit", "newDirectoryOnly", true));
        Process server = child("-Xmx2g", "serve-private", root.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var reader = new BufferedReader(new InputStreamReader(server.getInputStream()))) {
            var startupExecutor = Executors.newSingleThreadExecutor();
            String hello;
            try { hello = startupExecutor.submit(reader::readLine).get(30, TimeUnit.SECONDS); }
            finally { startupExecutor.shutdownNow(); }
            if (hello == null || hello.length() > 4096) throw new IOException("Private target did not return a bounded startup frame");
            int port = Json.MAPPER.readTree(hello).path("port").asInt();
            if (port < 1 || port > 65535) throw new IOException("Invalid private target port");
            Project project = project(config, port, "testkit-" + UUID.randomUUID());
            Path state = root.resolve("state"), projectFile = root.resolve("project.json"); SafeFiles.atomic(projectFile, project);
            Measurement measurement = new Measurement(); Engine engine = new Engine(); engine.measure(measurement::accept);
            long started = System.nanoTime();
            if (recovery) {
                Process crash = child("-Xmx1g", "crash-worker", projectFile.toString(), records.toString(), state.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                try {
                    if (!crash.waitFor(300, TimeUnit.SECONDS) || crash.exitValue() != 17) throw new IOException("After-commit worker termination was not observed");
                } finally { if (crash.isAlive()) { crash.destroy(); if (!crash.waitFor(2, TimeUnit.SECONDS)) crash.destroyForcibly(); } }
            }
            try (var sink = new Sink(project)) {
                Engine.Result result = engine.load(project, records, state, sink, checkpoint -> {}, batch -> {});
                if (!result.status().equals("succeeded") || result.records() != profile.vertices() + profile.edges())
                    throw new IOException("Benchmark load did not reconcile source records");
                long elapsed = (System.nanoTime() - started) / 1000000;
                var structural = Validation.validate(project, records, sink);
                SafeFiles.atomic(root.resolve("structural.json"), structural);
                if (!structural.status().equals("succeeded")) throw new IOException("Structural benchmark equivalence failed");
                if (profile.vertices() + profile.edges() <= 10000) {
                    var functional = Functional.run(project, records, sink);
                    SafeFiles.atomic(root.resolve("functional.json"), functional);
                    if (!functional.status().equals("succeeded")) throw new IOException("Golden benchmark query equivalence failed");
                }
                if (!new Engine().load(project, records, state, sink, checkpoint -> {}, batch -> {}).status().equals("succeeded"))
                    throw new IOException("Idempotent replay failed");
                long operations = 0, soakStarted = System.nanoTime();
                var access = new GraphAccess(project, sink); var mapper = new RdfMapper(project.mapping());
                GraphRecord baseline = Synthetic.record(profile, 0);
                String vertex = mapper.entity(baseline);
                while ((System.nanoTime() - soakStarted) / 1000000 < soakDuration) {
                    if (!access.lookup(vertex).orElseThrow().equals(baseline)) throw new IOException("Soak lookup changed canonical source fidelity");
                    var property = new GraphRecord.Property("soak-property", GraphRecord.Value.integer(0), GraphRecord.Value.string("soak-id"),
                        Map.of("meta", GraphRecord.Value.string("owned")), GraphRecord.Fidelity.complete);
                    access.insertProperty(vertex, property); access.insertProperty(vertex, property);
                    access.replaceProperties(vertex, baseline.properties()); access.removeProperty(vertex, "soak-property");
                    if (!access.lookup(vertex).orElseThrow().equals(baseline)) throw new IOException("Soak replacement/removal was not lossless");
                    GraphRecord prototype = Synthetic.record(profile, profile.vertices());
                    var edge = new GraphRecord(prototype.kind(), new GraphRecord.Identity(prototype.identity().scope(), prototype.identity().partition(),
                        GraphRecord.Value.string("soak-edge")), prototype.label(), prototype.source(), prototype.destination(), prototype.edgeScope(),
                        prototype.properties(), prototype.metadata(), prototype.provenance());
                    access.create(edge); access.create(edge); access.deleteEdge(mapper.entity(edge));
                    var isolated = new GraphRecord(baseline.kind(), new GraphRecord.Identity(baseline.identity().scope(), baseline.identity().partition(),
                        GraphRecord.Value.string("soak-isolated")), baseline.label(), null, null, null, baseline.properties(), baseline.metadata(), baseline.provenance());
                    access.create(isolated); access.deleteVertex(mapper.entity(isolated), "reject"); operations += 10;
                    if (operations % 100 == 0 && profile.vertices() + profile.edges() <= 10000 &&
                        !Functional.run(project, records, sink).status().equals("succeeded")) throw new IOException("Soak query equivalence failed");
                }
                long soakMillis = (System.nanoTime() - soakStarted) / 1000000;
                if (soakDuration > 0 && !Validation.validate(project, records, sink).status().equals("succeeded"))
                    throw new IOException("Soak corrupted canonical records or derived views");
                System.gc(); Thread.sleep(100); System.gc(); Thread.sleep(100);
                long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                long gcCount = 0, gcMillis = 0;
                for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) { gcCount += Math.max(0, collector.getCollectionCount()); gcMillis += Math.max(0, collector.getCollectionTime()); }
                long totalRecords = profile.vertices() + profile.edges();
                var metrics = new Metrics(totalRecords, measurement.quads, measurement.bytes, elapsed,
                    totalRecords * 1000.0 / Math.max(1, elapsed), measurement.quads * 1000.0 / Math.max(1, elapsed),
                    measurement.bytes * 1000.0 / Math.max(1, elapsed), measurement.quantile(.5), measurement.quantile(.95), measurement.quantile(.99),
                    measurement.retries, measurement.depth, heap, gcCount, gcMillis);
                String actualProfile = profile.vertices() == 200 ? "semantic" : profile.vertices() == 10000 ? "ci" : "load";
                var report = new Repetition("succeeded", actualProfile, SafeFiles.fileHash(records), project.inputHash(), metrics,
                    structural, recovery, operations, soakMillis, 0, 0);
                SafeFiles.atomic(root.resolve("result.json"), report); return report;
            }
        } finally {
            server.getOutputStream().close();
            if (!server.waitFor(10, TimeUnit.SECONDS)) { server.destroy(); if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly(); }
        }
    }
    private static Project project(Configuration config, int port, String run) {
        String url = "http://localhost:" + port + "/fixture";
        var unsigned = new Project(1, "cosmos-gremlin-to-jena", "22e3f2cccbfcc6622cacbf1873b9c620acba085e", run,
            1, "0.1.0", Mapping.standard(), null, new Project.Target(url, url + "/query", url + "/update", url + "/data",
                null, "urn:gremlin2rdf:testkit:", "manual"), new Project.Limits(config.batchRecords(), config.batchBytes(), 262144,
                30000, 3, config.queueCapacity(), 1, config.minimumFreeDiskBytes()), "immutable", Map.of(), List.of(), Map.of(), List.of());
        List<Approval> approvals = new ArrayList<>();
        for (String operation : List.of("target-write", "application-write", "application-delete")) {
            List<String> graphs = operation.equals("target-write") ? List.of(unsigned.graph("canonical"), unsigned.graph("derived"), unsigned.graph("metadata"))
                : List.of(unsigned.graph("canonical"), unsigned.graph("derived"));
            approvals.add(Approval.sign(unsigned, operation, graphs, Instant.now().plusSeconds(172800).toString(), "env:GRAPH_TEST_APPROVAL_KEY"));
        }
        return new Project(unsigned.schemaVersion(), unsigned.migrationKind(), unsigned.baselineRepositorySha(), unsigned.runId(), unsigned.protocolVersion(),
            unsigned.workerVersion(), unsigned.mapping(), unsigned.source(), unsigned.target(), unsigned.limits(), unsigned.consistency(),
            unsigned.phases(), approvals, unsigned.applicationHashes(), unsigned.artifacts());
    }
    private static ProcessBuilder child(String heap, String... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), heap, "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Benchmark.class.getName()));
        command.addAll(List.of(args)); return new ProcessBuilder(command);
    }
    private static void serve(Path root) throws Exception {
        var ownership = Json.MAPPER.readTree(Files.readAllBytes(root.resolve("private-target.json")));
        if (!ownership.path("kind").asText().equals("private-testkit") || !ownership.path("newDirectoryOnly").asBoolean())
            throw new SecurityException("Private target ownership manifest required");
        Path directory = SafeFiles.resolve(root, "tdb2");
        if (Files.exists(directory)) throw new SecurityException("Never open existing or live TDB2 files");
        var dataset = TDB2Factory.connectDataset(directory.toString());
        var server = FusekiServer.create().loopback(true).port(0).add("/fixture", dataset).build().start();
        try { System.out.println(Json.text(Map.of("port", server.getPort()))); System.out.flush(); System.in.read(); }
        finally { server.stop(); dataset.close(); }
    }
    private static void crashWorker(String[] args) throws Exception {
        Project project = Main.project(args[1]);
        if (!project.runId().startsWith("testkit-") || !project.target().datasetUrl().startsWith("http://localhost:")
            || project.target().authenticationRef() != null) throw new SecurityException("Crash injection is restricted to private loopback fixtures");
        try (var sink = new Sink(project)) {
            new Engine().load(project, Path.of(args[2]), Path.of(args[3]), sink, checkpoint -> {},
                batch -> { if (batch.number() == 1) Runtime.getRuntime().halt(17); });
        }
        throw new IOException("Crash injection did not reach its designated batch");
    }
}
