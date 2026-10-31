package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class PrivateBenchmark {
    private PrivateBenchmark() {}
    static Object run(String[] args, Engine engine) throws Exception {
        if (args.length != 7 || !args[6].equals("--private-fixture-confirmed"))
            throw new SecurityException("Private benchmark confirmation required");
        Path root = Path.of(args[2]).toRealPath().getParent();
        Path jar = Path.of(args[3]).toRealPath();
        if (!jar.getFileName().toString().equals("graph-testkit-0.1.0.jar"))
            throw new SecurityException("Pinned private testkit required");
        Path packageManifest = jar.resolveSibling("worker-manifest.json");
        if (Files.size(packageManifest) > 65536) throw new IOException("Package manifest exceeds bound");
        var packaged = Json.MAPPER.readTree(Files.readAllBytes(packageManifest));
        if (!packaged.path("workerVersion").asText().equals("0.1.0")
            || !SafeFiles.fileHash(jar).equals(packaged.path("testkitSha256").asText()))
            throw new SecurityException("Private testkit integrity check failed");
        Path configuration = SafeFiles.resolve(root, args[4]), output = SafeFiles.resolve(root, args[5]);
        if (Files.size(configuration) > 65536 || Files.exists(output)) throw new IOException("Bounded config and new output required");
        String configHash = SafeFiles.fileHash(configuration);
        var command = List.of(Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
            "-Xmx1g", "-jar", jar.toString(), "run", "--private-fixture-confirmed",
            configuration.toString(), output.toString(), "--parent-owned");
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        var inherited = new HashMap<>(builder.environment()); builder.environment().clear();
        for (String name : List.of("PATH", "Path", "SystemRoot", "JAVA_HOME", "HOME", "USERPROFILE", "TEMP", "TMP"))
            if (inherited.containsKey(name)) builder.environment().put(name, inherited.get(name));
        builder.environment().put("GRAPH_TEST_APPROVAL_KEY", UUID.randomUUID() + "-" + UUID.randomUUID());
        Process process = builder.start();
        var reply = new CompletableFuture<byte[]>();
        Thread.startVirtualThread(() -> {
            try (var input = process.getInputStream()) {
                byte[] bytes = input.readNBytes(8193);
                if (bytes.length > 8192) throw new IOException("Benchmark acknowledgement exceeds bound");
                reply.complete(bytes);
            } catch (IOException error) { reply.completeExceptionally(error); }
        });
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (reply.isCompletedExceptionally()) reply.get();
                if (engine.stopping()) return Map.of("status", engine.stoppedStatus(), "partialOutput", "preserved-not-acceptance",
                    "remediation", "Inspect the private owned output and restart in a new directory; partial performance runs are not acceptance evidence.");
            }
            var acknowledgement = Json.MAPPER.readTree(reply.get(2, TimeUnit.SECONDS));
            Path report = SafeFiles.resolve(output, "report.json");
            if (acknowledgement == null || !acknowledgement.isObject() || process.exitValue() != 0
                || Files.size(report) > 16777216 || !SafeFiles.fileHash(report).equals(acknowledgement.path("reportSha256").asText())
                || !configHash.equals(SafeFiles.fileHash(configuration)))
                throw new IOException("Private benchmark completion is unconfirmed");
            var result = Json.MAPPER.readTree(Files.readAllBytes(report));
            if (!result.path("status").asText().equals(acknowledgement.path("status").asText())
                || !Json.MAPPER.readTree(Files.readAllBytes(configuration)).equals(result.path("configuration")))
                throw new IOException("Private benchmark differs from selected configuration");
            return Map.of("status", result.path("status").asText(), "reportPath", args[5] + "/report.json",
                "reportSha256", acknowledgement.path("reportSha256").asText(), "result", result, "liveCompatibility", "not-tested");
        } finally {
            process.getOutputStream().close();
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Owned private benchmark could not be terminated");
                }
            }
        }
    }
}
