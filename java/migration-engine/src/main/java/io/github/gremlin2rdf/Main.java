package io.github.gremlin2rdf;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        int exit = 0;
        try {
            if (args.length == 1 && args[0].equals("worker")) {
                Protocol.serve(System.in, System.out, Main::worker); return;
            }
            Object result = execute(args, new Engine());
            System.out.println(Json.text(result));
            if (Set.of("blocked", "failed", "cancelled").contains(Json.MAPPER.valueToTree(result).path("status").asText())) exit = 2;
        } catch (Exception e) { System.out.println(Json.text(diagnostic(e))); exit = e instanceof SecurityException ? 3 : e instanceof Gremlin.Unsupported ? 4 : 2; }
        System.exit(exit);
    }
    public static Map<String, Object> diagnostic(Exception e) {
        if (e instanceof Diagnostics.Failure || e instanceof Project.CredentialUnavailable)
            return Diagnostics.failure(e,"execution");
        if (!(e instanceof Gremlin.Unsupported || e instanceof PopulationPlan.UnsupportedValue ||
            e instanceof Validation.InputChanged || TargetHttp.responseExceeded(e) || TargetHttp.authorizationDenied(e)))
            return Diagnostics.failure(e,"execution");
        // Never echo exception messages: library errors may contain endpoint credentials, queries or records.
        return Map.of("status", "blocked", "code", e instanceof Gremlin.Unsupported unsupported ? unsupported.code()
            : e instanceof PopulationPlan.UnsupportedValue ? "SOURCE_POPULATION_INT64_RANGE"
            : e instanceof Validation.InputChanged ? "CANONICAL_INPUT_CHANGED"
            : TargetHttp.responseExceeded(e) ? "TARGET_RESPONSE_BOUND"
            : TargetHttp.authorizationDenied(e) ? "TARGET_ENDPOINT_AUTHORIZATION"
            : e instanceof SecurityException ? "AUTHORIZATION_REQUIRED" : e instanceof java.io.IOException ? "ARTIFACT_OR_STATE_ERROR"
            : "OPERATION_CAUSE_UNKNOWN", "stage","execution", "remediation", e instanceof Gremlin.Unsupported ? "Review the unsupported construct in the local assessment and use the documented supported Gremlin subset."
            : e instanceof SecurityException ? "Supply a valid scoped authorization bound to the current inputs."
            : TargetHttp.authorizationDenied(e) ? "Renew endpoint credentials through approved secret references and grant least-privilege read/update access for the exact run graphs. Verify ambiguous batch markers before resuming; do not retry denied operations."
            : e instanceof Validation.InputChanged ? "Select an unchanged canonical input or re-extract it, then regenerate validation against the current checksum."
            : TargetHttp.responseExceeded(e) ? "Use bounded query results and inspect the exact owned run graphs for unexpected content. Reconcile ambiguous writes before resuming; do not retry oversized responses."
            : "Inspect sanitized diagnostics; verify configuration, checkpoint integrity and endpoint capabilities.");
    }
    private static Object worker(String command, JsonNode payload, Engine engine) throws Exception {
        var allowed = Set.of("preflight", "source-check", "source-enumerate", "source-compatibility", "source-populate", "source-cleanup", "source-recipes", "assess", "mapping", "synthetic", "extract", "load", "resume", "validate",
            "translate", "rewrite", "test", "publish", "cleanup", "report");
        if (!allowed.contains(command)) throw new Gremlin.Unsupported("WORKER_OPERATION", "Unsupported worker operation");
        JsonNode args = payload.path("arguments");
        if (!args.isArray() || args.size() > 10) throw new IllegalArgumentException("Bounded argument array required");
        List<String> arguments = new ArrayList<>(List.of(command));
        for (var arg : args) {
            if (!arg.isTextual() || arg.textValue().length() > 65536) throw new IllegalArgumentException("Invalid worker argument");
            arguments.add(arg.textValue());
        }
        var project = project(Path.of("project.json").toString());
        if (!project.runId().equals(payload.path("runId").asText())
            || !project.inputHash().equals(payload.path("inputHash").asText()))
            throw new SecurityException("Worker inputs differ from the persisted project");
        JsonNode result;
        try { result = Json.MAPPER.valueToTree(execute(arguments.toArray(String[]::new), engine)); }
        catch (Exception e) { result = Json.MAPPER.valueToTree(diagnostic(e)); }
        if (engine.deadlineExceeded() && !engine.stoppedStatus().equals("cancelled")
            && result instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
            object.put("status", "paused");
        if (Set.of("paused","cancelled").contains(result.path("status").asText())
            && !result.path("diagnostics").isArray() && result instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
            object.set("diagnostics",Json.MAPPER.valueToTree(List.of(Diagnostics.report(
                result.path("status").asText().equals("cancelled") ? "OPERATOR_CANCELLED" :
                    engine.deadlineExceeded() ? "OPERATION_DEADLINE" : "OPERATOR_PAUSED","worker-control"))));
        String status = result.path("status").asText("succeeded");
        if (result.path("blockers").isArray() && !result.path("blockers").isEmpty()) status = "blocked";
        if (result.path("readiness").asText().equals("blocked")) status = "blocked";
        String relative = "artifacts/" + command + "-" + UUID.randomUUID() + ".json";
        boolean sourceObservation = command.equals("source-compatibility") && result.has("sourceManifestHash")
            && Set.of("succeeded", "blocked").contains(status);
        if (sourceObservation)
            relative = Objects.requireNonNull(project.source().compatibilityEvidence());
        Path artifact = SafeFiles.resolve(Path.of("."), relative);
        Files.createDirectories(artifact.getParent());
        var produced = status.equals("succeeded") || command.equals("test") && result.has("reportSha256")
            ? producedArtifacts(arguments.toArray(String[]::new), result, project) : List.of();
        if (sourceObservation) {
            if (Files.size(artifact) > 1048576) throw new IOException("Source observation exceeds bound");
            var envelope = Json.MAPPER.readTree(Files.readAllBytes(artifact));
            if (!envelope.path("runId").asText().equals(project.runId()) || !envelope.path("inputHash").asText().equals(project.inputHash())
                || !envelope.path("operation").asText().equals(command) || !envelope.path("status").asText().equals(status))
                throw new IOException("Source observation differs from current worker inputs");
        } else SafeFiles.atomic(artifact, Map.of("schemaVersion", 1, "runId", project.runId(), "inputHash", project.inputHash(),
            "operation", command, "status", status, "sensitiveArtifact", true, "result", result));
        return Map.of("status", status, "inputHash", project.inputHash(), "artifact", Map.of("relativePath", relative,
            "sha256", SafeFiles.fileHash(artifact), "ownerRunId", project.runId(), "sensitive", true), "artifacts", produced,
            "diagnostics", result.path("diagnostics").isArray() ? result.path("diagnostics")
                : result.has("code") ? List.of(result) : List.of());
    }
    static byte[] sourceReply(java.io.InputStream input, Engine engine) throws IOException {
        byte[] result=null;
        var frame=new java.io.ByteArrayOutputStream();
        int next;
        while ((next=input.read())!=-1) {
            if (next!='\n') {
                if (frame.size()>=8192) throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
                frame.write(next);continue;
            }
            if (frame.size()==0) throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
            byte[] bytes=frame.toByteArray();frame.reset();
            try {
                var value=Json.MAPPER.readTree(bytes);
                if (result!=null || value==null || !value.isObject())
                    throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
                if (value.path("type").asText().equals("source-progress"))
                    engine.activity(Json.MAPPER.treeToValue(value,SourceActivity.class));
                else if (value.path("type").asText().equals("source-request-progress"))
                    engine.requestActivity(Json.MAPPER.treeToValue(value,SourceRequestActivity.class));
                else if (value.has("type")) throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
                else result=bytes;
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
                throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
            }
        }
        if (frame.size()!=0 || result==null)
            throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
        return result;
    }
    private static List<Map<String, Object>> producedArtifacts(String[] args, JsonNode result, Project project) throws IOException {
        List<Path> paths = new ArrayList<>();
        Map<Path, String> expected = new HashMap<>();
        Set<Path> manifests = new HashSet<>();
        if (args[0].equals("synthetic")) {
            if (args[1].equals("generate") || args[1].equals("source-generate")) {
                var output = Path.of(args[args[1].equals("generate") ? 4 : 5]);
                paths.add(output); paths.add(output.resolveSibling(output.getFileName() + ".manifest.json"));
                expected.put(output, result.path("sha256").asText());
                manifests.add(output.resolveSibling(output.getFileName() + ".manifest.json"));
                if (args[1].equals("source-generate")) {
                    paths.add(output.resolveSibling(output.getFileName() + ".identities.ndjson"));
                    paths.add(output.resolveSibling(output.getFileName() + ".negative.json"));
                    expected.put(output.resolveSibling(output.getFileName() + ".identities.ndjson"), result.path("identityManifestHash").asText());
                    expected.put(output.resolveSibling(output.getFileName() + ".negative.json"), result.path("negativeFixtureHash").asText());
                }
            } else if (args[1].equals("plan")) {
                Path planPath = Path.of(args[4]); paths.add(planPath);
                if (Files.size(planPath) > 65536 || !Json.MAPPER.readValue(Files.readAllBytes(planPath), PopulationPlan.class)
                    .hash().equals(result.path("planHash").asText())) throw new IOException("Population plan differs from producer acknowledgement");
            }
            else if (args[1].equals("derive")) {
                var directory = Path.of(args[4]); paths.add(directory.resolve("catalog.json"));
                manifests.add(directory.resolve("catalog.json"));
                paths.add(directory.resolve("ownership.json"));
                for (var artifact : result.path("artifacts")) {
                    Path selected = SafeFiles.resolve(directory, artifact.path("path").asText());
                    paths.add(selected); expected.put(selected, artifact.path("sha256").asText());
                }
            }
        } else if (args[0].equals("mapping") && args[1].equals("export")) {
            var directory = Path.of(args[4]);
            for (String name : List.of("canonical.nt", "derived.nt", "manifest.json", "ownership.json")) paths.add(directory.resolve(name));
            expected.put(directory.resolve("canonical.nt"), result.path("canonical").path("sha256").asText());
            expected.put(directory.resolve("derived.nt"), result.path("derived").path("sha256").asText());
            manifests.add(directory.resolve("manifest.json"));
        } else if (args[0].equals("source-enumerate")) {
            Path directory = Path.of(args[4]);
            Path identities = directory.resolve("identities.ndjson");
            paths.add(identities); paths.add(directory.resolve("manifest.json"));
            expected.put(identities, result.path("sha256").asText());
            manifests.add(directory.resolve("manifest.json"));
        } else if (Set.of("source-populate", "source-cleanup", "source-recipes").contains(args[0])) {
            paths.add(Path.of(args[4]));
            expected.put(Path.of(args[4]), result.path(args[0].equals("source-recipes") ? "sha256" : "ownershipJournalSha256").asText());
        }
        else if (args[0].equals("extract")) {
            paths.add(Path.of(args[4])); paths.add(Path.of(args[4] + ".manifest.json"));
            expected.put(Path.of(args[4]), result.path("sha256").asText());
            manifests.add(Path.of(args[4] + ".manifest.json"));
        } else if (args[0].equals("rewrite") && args[1].equals("apply")) {
            Path output = Path.of(args[3]).resolve(args[5]); paths.add(output);
            expected.put(output, result.path("outputSha256").asText());
        } else if (args[0].equals("test") && args[1].equals("benchmark")) {
            Path report = Path.of(result.path("reportPath").asText()); paths.add(report);
            expected.put(report, result.path("reportSha256").asText());
        } else if (args[0].equals("publish") && result.has("applicationConfiguration")) {
            Path configuration = Path.of(result.path("applicationConfiguration").asText()); paths.add(configuration);
            expected.put(configuration, result.path("sha256").asText());
        }
        Path root = Path.of(".").toRealPath(); List<Map<String, Object>> artifacts = new ArrayList<>();
        for (var path : paths) {
            Path selected = path.toAbsolutePath().normalize();
            if (!selected.startsWith(root)) throw new IOException("Worker output is outside its run directory");
            String relative = root.relativize(selected).toString().replace('\\', '/');
            selected = SafeFiles.resolve(root, relative);
            if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Declared worker output is not a regular artifact");
            String checksum = SafeFiles.fileHash(selected);
            if (expected.containsKey(path) && !checksum.equals(expected.get(path))) throw new IOException("Declared output differs from producer checksum");
            if (manifests.contains(path)) {
                if (Files.size(selected) > 16777216 || !Json.MAPPER.readTree(Files.readAllBytes(selected))
                    .equals(Json.MAPPER.readTree(Json.text(result))))
                    throw new IOException("Produced manifest differs from the completed operation");
            }
            artifacts.add(Map.of("relativePath", relative, "sha256", checksum,
                "ownerRunId", project.runId(), "sensitive", true));
        }
        return List.copyOf(artifacts);
    }
    public static Object execute(String[] args, Engine engine) throws Exception {
        if (args.length == 0) throw new Gremlin.Unsupported("COMMAND_REQUIRED", "A documented CLI operation is required");
        if (args.length > 1 && Set.of("source-enumerate", "source-compatibility", "extract", "load", "migrate",
            "resume", "validate", "cleanup", "publish", "report").contains(args[0]))
            engine.deadline(project(args[1]).limits().maximumOperationMillis());
        return switch (args[0]) {
            case "mapping" -> {
                if (args.length == 4 && args[1].equals("shapes")) {
                    var mapping = Json.MAPPER.readValue(Files.readAllBytes(Path.of(args[2])), Mapping.class);
                    Path output = Path.of(args[3]);
                    if (Files.exists(output)) throw new IOException("New SHACL output required");
                    var model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                    try (var resource = Main.class.getResourceAsStream("/canonical-v1.shacl.ttl")) {
                        if (resource == null) throw new IOException("Canonical shapes resource missing");
                        org.apache.jena.riot.RDFDataMgr.read(model, resource, org.apache.jena.riot.Lang.TURTLE);
                        DomainShapes.add(model, mapping);
                        try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                            org.apache.jena.riot.RDFDataMgr.write(stream, model, org.apache.jena.riot.Lang.TURTLE);
                        }
                    } finally { model.close(); }
                    yield Map.of("status", "succeeded", "mappingHash", mapping.hash(), "outputSha256", SafeFiles.fileHash(output));
                }
                if (args.length == 5 && args[1].equals("export"))
                    yield RdfExport.run(project(args[2]), Path.of(args[3]), Path.of(args[4]));
                require(args, 3);
                if (!args[1].equals("validate")) throw new Gremlin.Unsupported("MAPPING_OPERATION", "Only mapping validate is supported");
                var mapping = Json.MAPPER.readValue(Files.readAllBytes(Path.of(args[2])), Mapping.class);
                var shapes = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                try { DomainShapes.add(shapes, mapping); } finally { shapes.close(); }
                yield Map.of("status", "succeeded", "mappingHash", mapping.hash());
            }
            case "synthetic" -> {
                if (args.length == 6 && args[1].equals("derive")) {
                    var project = project(args[2]); Path root = Path.of(args[2]).toRealPath().getParent();
                    yield Fixtures.generate(project, root, args[3], args[4], args[5]);
                }
                if (args.length == 6 && args[1].equals("source-generate")) {
                    if (!args[2].equals("semantic")) throw new Gremlin.Unsupported("SOURCE_PROFILE", "Source generation supports the bounded semantic profile; generate larger profiles locally");
                    yield Synthetic.sourceFixture(project(args[4]), Long.parseLong(args[3]), Path.of(args[5]));
                }
                if (args.length == 6 && args[1].equals("plan")) {
                    var project = project(args[2]); Path root = Path.of(args[2]).toRealPath().getParent();
                    var plan = PopulationPlan.create(project, root, args[3], Long.parseLong(args[5]));
                    Path output = SafeFiles.resolve(root, args[4]);
                    if (Files.exists(output)) throw new IOException("New immutable population plan required");
                    SafeFiles.atomic(output, plan);
                    yield Map.of("status", "succeeded", "planHash", plan.hash(), "estimatedWrites", plan.estimatedWrites(), "resources", plan.resources());
                }
                require(args, 5);
                if (!args[1].equals("generate")) throw new Gremlin.Unsupported("SOURCE_POPULATION", "Source population requires an authorized pre-existing fixture container");
                long seed = Long.parseLong(args[3]);
                var profile = switch (args[2]) {
                    case "semantic" -> Synthetic.Profile.semantic(seed);
                    case "ci" -> Synthetic.Profile.ci(seed);
                    case "load" -> Synthetic.Profile.load(seed);
                    default -> throw new IllegalArgumentException("Unknown synthetic profile");
                };
                yield Synthetic.generate(profile, Path.of(args[4]));
            }
            case "translate" -> {
                require(args, 3);
                yield new Translator(args[1], Map.of()).translate(args[2]);
            }
            case "assess" -> {
                require(args, 2); yield Assessment.assess(Path.of(args[1]));
            }
            case "rewrite" -> {
                if (args.length > 1 && args[1].equals("plan")) {
                    require(args, 5); yield Rewrite.plan(Path.of(args[2]), args[3], args[4]);
                }
                require(args, 6);
                if (!args[1].equals("apply")) throw new Gremlin.Unsupported("REWRITE_OPERATION", "rewrite plan or rewrite apply required");
                var plan = Json.MAPPER.readValue(Files.readAllBytes(Path.of(args[4])), Rewrite.Plan.class);
                Rewrite.apply(project(args[2]), Path.of(args[3]), plan, args[5]);
                yield Map.of("status", "succeeded", "output", args[5], "applicationBuildEvidence", "not-executed",
                    "outputSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(plan.output().getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    "readiness", "blocked-until-build-and-equivalence-pass");
            }
            case "test" -> {
                if (args.length > 1 && args[1].equals("benchmark")) yield PrivateBenchmark.run(args, engine);
                require(args, 4);
                if (args[1].equals("fixtures")) {
                    var project = project(args[2]); Path root = Path.of(args[2]).toRealPath().getParent();
                    try (var sink = new Sink(project)) { yield Fixtures.run(project, root, args[3], sink, engine); }
                }
                if (!args[1].equals("functional")) throw new Gremlin.Unsupported("TEST_OPERATION", "Use the isolated testkit for load and soak execution");
                var project = project(args[2]);
                try (var sink = new Sink(project)) { yield Functional.run(project, Path.of(args[3]), sink); }
            }
            case "load", "migrate", "resume" -> {
                require(args, 4);
                var project = project(args[1]);
                try (var sink = new Sink(project)) {
                    yield engine.load(project, Path.of(args[2]), Path.of(args[3]), sink, checkpoint -> {}, batch -> {});
                }
            }
            case "validate" -> {
                require(args, 3); var project = project(args[1]);
                if(project.source()!=null && "cosmos-documents".equals(project.source().sourceMode())){
                    SourceCompatibility.authorizeMode(project);
                    var observations=SourceCompatibility.read(project,Path.of(args[1]).toRealPath().getParent());
                    if(!SafeFiles.fileHash(Path.of(args[2])).equals(observations.path("canonicalSha256").asText()))
                        throw new Diagnostics.Failure("SOURCE_EVIDENCE_INTEGRITY","source-document-validation-input");
                }
                try (var sink = new Sink(project)) { yield Validation.validate(project, Path.of(args[2]), sink, engine); }
            }
            case "source-check", "source-enumerate", "extract", "source-compatibility", "source-populate", "source-cleanup", "source-recipes" -> {
                require(args, 5); var project = project(args[1]);
                Path sourceJar = Path.of(args[2]).toRealPath(), output = Path.of(args[4]);
                boolean probe = args[0].equals("source-compatibility");
                boolean mutation = Set.of("source-populate", "source-cleanup").contains(args[0]);
                boolean recipes = args[0].equals("source-recipes");
                boolean enumerate = args[0].equals("source-enumerate");
                boolean check = args[0].equals("source-check");
                boolean documents=project.source()!=null && "cosmos-documents".equals(project.source().sourceMode());
                if (documents && (mutation || recipes))
                    throw new Diagnostics.Failure("SOURCE_DOCUMENT_READ_ONLY","source-document-operation");
                if (documents && !check && !enumerate) SourceCompatibility.authorizeMode(project);
                if (documents && !check && !enumerate && !probe)
                    SourceCompatibility.read(project,Path.of(args[1]).toRealPath().getParent());
                PopulationPlan populationPlan = null;
                if (mutation || recipes) {
                    Path root = Path.of(args[1]).toRealPath().getParent();
                    Path selected = SafeFiles.resolve(root, args[3]);
                    if (Files.size(selected) > 65536) throw new IOException("Population plan exceeds bound");
                    populationPlan = Json.MAPPER.readValue(Files.readAllBytes(selected), PopulationPlan.class);
                    populationPlan.verify(project, root);
                    if (mutation) Approval.require(project, args[0], populationPlan.resources());
                    output = SafeFiles.resolve(root, args[4]);
                }
                if (probe) {
                    if (project.source() == null || project.source().compatibilityEvidence() == null)
                        throw new IllegalArgumentException("Explicit relative source observation artifact required");
                    var selected = SafeFiles.resolve(Path.of(args[1]).toRealPath().getParent(), project.source().compatibilityEvidence());
                    if (!selected.equals(output.toAbsolutePath().normalize()) || Files.exists(output))
                        throw new IOException("New owned observation artifact required");
                    Files.createDirectories(selected.getParent());
                }
                if (!sourceJar.getFileName().toString().equals(documents ? "DocumentSource.exe" : "cosmos-gremlin-source-0.1.0.jar"))
                    throw new IllegalArgumentException("Pinned source adapter required");
                if (documents) verifyDocumentAdapter(sourceJar);
                var command=new ArrayList<String>();
                if (!documents) command.addAll(List.of(Path.of(System.getProperty("java.home"),"bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),"-Xmx256m","-jar"));
                command.addAll(List.of(sourceJar.toString(),check ? "check" : enumerate ? "enumerate" : probe ? "probe" : recipes ? "recipes"
                        : mutation ? args[0].equals("source-populate") ? "populate" : "source-cleanup" : "extract",args[1]));
                if (documents) {
                    command.add(project.inputHash());
                    var root=Path.of(args[1]).toRealPath().getParent();
                    if (enumerate) command.add(args[3]);
                    if (!check) command.add(root.relativize(output.isAbsolute() ? output.normalize() : root.resolve(output).normalize()).toString().replace('\\','/'));
                } else command.addAll(List.of(args[3],args[4]));
                command.add("--parent-owned");
                var process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                var reply = new java.util.concurrent.CompletableFuture<byte[]>();
                Thread.startVirtualThread(() -> {
                    try (var input = process.getInputStream()) {
                        reply.complete(sourceReply(input,engine));
                    } catch (IOException | RuntimeException e) { process.destroy();reply.completeExceptionally(e); }
                });
                while (!process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (engine.stopping()) {
                        process.destroy();
                        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Source child could not be terminated");
                        }
                        var diagnostic=Diagnostics.report(engine.stoppedStatus().equals("cancelled") ? "OPERATOR_CANCELLED" :
                            engine.deadlineExceeded() ? "OPERATION_DEADLINE" : "OPERATOR_PAUSED","worker-control");
                        yield Map.of("status", engine.stoppedStatus(), "partialOutput", "preserved-not-loadable",
                            "diagnostics",List.of(diagnostic));
                    }
                }
                JsonNode acknowledgement;
                try { acknowledgement = Json.MAPPER.readTree(reply.get(2, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (java.util.concurrent.ExecutionException failure) {
                    if (failure.getCause() instanceof Diagnostics.Failure diagnostic) throw diagnostic;
                    throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
                }
                catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
                }
                if (acknowledgement == null || !acknowledgement.isObject())
                    throw new Diagnostics.Failure("SOURCE_PROTOCOL_INVALID","source-worker-protocol");
                if (process.exitValue()!=0 && acknowledgement.has("code") && !(probe && Files.exists(output)))
                    throw Diagnostics.acknowledged(acknowledgement,
                        check ? "source-connectivity" : enumerate ? "source-enumeration" : "source-operation");
                if (check) {
                    if (process.exitValue()!=0 || !acknowledgement.path("sourceReadAccess").asBoolean() ||
                        !(documents
                            ? acknowledgement.path("adapter").asText().equals("cosmos-dotnet-documents") &&
                              acknowledgement.path("sdkVersion").asText().equals("3.58.0")
                            : acknowledgement.path("driverVersion").asText().equals("3.4.13")))
                        throw new Diagnostics.Failure("ENDPOINT_PROVIDER_FAILURE","source-connectivity");
                    yield acknowledgement;
                }
                if (enumerate) {
                    Path directory = SafeFiles.resolve(Path.of(args[1]).toRealPath().getParent(), args[4]);
                    Path manifest = SafeFiles.resolve(directory, "manifest.json");
                    if (process.exitValue() != 0 || Files.size(manifest) > 1048576
                        || !acknowledgement.path("status").asText().equals("succeeded")
                        || !acknowledgement.path("inputHash").asText().equals(project.inputHash())
                        || !acknowledgement.path("runId").asText().equals(project.runId())
                        || !acknowledgement.equals(Json.MAPPER.readTree(Files.readAllBytes(manifest)))
                        || !acknowledgement.path("sha256").asText().equals(SafeFiles.fileHash(SafeFiles.resolve(directory, "identities.ndjson"))))
                        throw new IOException("Unconfirmed or changed enumeration manifest");
                    yield acknowledgement;
                }
                if (mutation || recipes) {
                    if (process.exitValue() != 0 || !acknowledgement.path("status").asText().equals("succeeded")
                        || !acknowledgement.path("planHash").asText().equals(populationPlan.hash())
                        || !acknowledgement.path(recipes ? "sha256" : "ownershipJournalSha256").asText().equals(SafeFiles.fileHash(output)))
                        throw new IOException("Unconfirmed or changed source ownership acknowledgement");
                    yield acknowledgement;
                }
                if (probe && Files.exists(output) && Files.size(output) <= 1048576) {
                    var envelope = Json.MAPPER.readTree(Files.readAllBytes(output));
                    var result = envelope.path("result");
                    String status = envelope.path("status").asText();
                    if (!envelope.path("runId").asText().equals(project.runId()) || !envelope.path("inputHash").asText().equals(project.inputHash())
                        || !envelope.path("operation").asText().equals("source-compatibility")
                        || !Set.of("succeeded", "blocked").contains(status) || !result.path("status").asText().equals(status)
                        || !status.equals(acknowledgement.path("status").asText())
                        || !SafeFiles.fileHash(output).equals(acknowledgement.path("artifactSha256").asText())
                        || status.equals("succeeded") && process.exitValue() != 0)
                        throw new IOException("Invalid source observation envelope or process status");
                    if (documents && status.equals("blocked")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode)result).set("diagnostics",
                            Json.MAPPER.valueToTree(List.of(Diagnostics.report(result.path("code").asText(),
                                result.path("stage").asText()))));
                    }
                    yield result;
                }
                if (process.waitFor() != 0 || probe || !acknowledgement.path("status").asText().equals("succeeded")
                    || !SafeFiles.fileHash(output).equals(acknowledgement.path("sourceHash").asText()))
                    throw new java.io.IOException("Source operation failed or changed; incomplete output must not be loaded");
                Path extractionManifest = output.resolveSibling(output.getFileName() + ".manifest.json");
                if (Files.size(extractionManifest) > 1048576) throw new IOException("Extraction manifest exceeds bound");
                var extracted = Json.MAPPER.readTree(Files.readAllBytes(extractionManifest));
                if (extracted.path("schemaVersion").asInt() != 1 || !extracted.path("status").asText().equals("succeeded")
                    || !extracted.path("runId").asText().equals(project.runId())
                    || !extracted.path("inputHash").asText().equals(project.inputHash())
                    || !extracted.path("identityManifestHash").asText().equals(project.source().identityManifestHash())
                    || extracted.path("bytes").asLong(-1) != Files.size(output)
                    || !extracted.path("sha256").asText().equals(acknowledgement.path("sourceHash").asText()))
                    throw new IOException("Extraction manifest differs from the acknowledged current inputs");
                yield extracted;
            }
            case "preflight" -> {
                if (args.length == 4 && args[1].equals("probe")) {
                    var project = project(args[2]);
                    try (var sink = new Sink(project)) { yield TargetProbe.run(project, args[3], sink); }
                }
                if (args.length!=2 && args.length!=3) throw new IllegalArgumentException("Preflight project and optional packaged source adapter required");
                var project=project(args[1]);Path root=Path.of(args[1]).toRealPath().getParent();
                engine.deadline(project.limits().maximumOperationMillis()==null ? 60000 : project.limits().maximumOperationMillis());
                yield Preflight.run(project,root,new Preflight.Connections() {
                    public boolean target(Project p) throws Exception { try(var sink=new Sink(p)){return sink.queryAccess();} }
                    public Map<String,Object> source(Project p) throws Exception {
                        Project.secret(p.source().authenticationRef());
                        if (args.length!=3) throw new Diagnostics.Failure("SOURCE_ADAPTER_REQUIRED","source-connectivity");
                        var result=Json.MAPPER.valueToTree(execute(new String[]{"source-check",args[1],args[2],"",""},engine));
                        return Json.MAPPER.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                    }
                });
            }
            case "authorize" -> {
                require(args, 7); var project = project(args[1]);
                if (!args[2].equals("--operator-confirmed")) throw new SecurityException("Explicit operator confirmation required");
                yield Approval.sign(project, args[3], List.of(Json.MAPPER.readValue(args[4], String[].class)), args[5], args[6]);
            }
            case "cleanup" -> {
                require(args, 2); var project = project(args[1]);
                try (var sink = new Sink(project)) { sink.cleanup(); }
                yield Map.of("status", "succeeded", "runId", project.runId());
            }
            case "report" -> {
                require(args, 3); var project = project(args[1]);
                yield Readiness.build(project, Path.of(args[1]).toRealPath().getParent());
            }
            case "publish" -> {
                require(args, 2); var project = project(args[1]);
                try (var sink = new Sink(project)) { yield Readiness.publish(project, Path.of(args[1]).toRealPath().getParent(), sink); }
            }
            default -> throw new Gremlin.Unsupported("COMMAND_NOT_IMPLEMENTED", "Requested operation has no verified implementation");
        };
    }
    static void verifyDocumentAdapter(Path executable) throws IOException {
        Path runtime=executable.getParent();
        if (runtime==null || !runtime.getFileName().toString().equals("win-x64"))
            throw new Diagnostics.Failure("SOURCE_ADAPTER_REQUIRED","source-document-integrity");
        Path manifest=SafeFiles.resolve(runtime.getParent(),"worker-manifest.json");
        if (Files.size(manifest)>65536)
            throw new Diagnostics.Failure("SOURCE_ADAPTER_REQUIRED","source-document-integrity");
        JsonNode packaged=Json.MAPPER.readTree(Files.readAllBytes(manifest));
        if (packaged==null || packaged.path("schemaVersion").asInt()!=1 ||
            !packaged.path("rid").asText().equals("win-x64") ||
            !packaged.path("executable").asText().equals("DocumentSource.exe") ||
            !packaged.path("sdkVersion").asText().equals("3.58.0") ||
            !packaged.path("files").isObject() || !packaged.path("files").has("DocumentSource.exe"))
            throw new Diagnostics.Failure("SOURCE_ADAPTER_REQUIRED","source-document-integrity");
        var files=packaged.path("files").fields();
        while(files.hasNext()) {
            var entry=files.next();
            if (!entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9._-]*") ||
                !entry.getValue().isTextual() || !entry.getValue().asText().matches("[a-f0-9]{64}") ||
                !SafeFiles.fileHash(SafeFiles.resolve(runtime,entry.getKey())).equals(entry.getValue().asText()))
                throw new Diagnostics.Failure("SOURCE_ADAPTER_REQUIRED","source-document-integrity");
        }
    }
    public static Project project(String file) throws Exception {
        if (Files.size(Path.of(file)) > 1048576) throw new IllegalArgumentException("Project exceeds configured size bound");
        return Json.MAPPER.readValue(Files.readAllBytes(Path.of(file)), Project.class);
    }
    private static void require(String[] args, int length) {
        if (args.length != length) throw new IllegalArgumentException("Incorrect operation arguments");
    }
}
