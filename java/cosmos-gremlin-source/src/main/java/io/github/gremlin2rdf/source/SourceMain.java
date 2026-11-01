package io.github.gremlin2rdf.source;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.tinkerpop.gremlin.driver.*;
import org.apache.tinkerpop.gremlin.driver.ser.GraphSONMessageSerializerV2d0;

public final class SourceMain implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private final JsonNode project, config;
    private final Cluster cluster;
    private final Client client;
    private final int timeout;
    private final SourceCodec codec;
    private final io.github.gremlin2rdf.Project typedProject;
    private final SourceRequests requests;
    private final boolean connectivityOnly;
    private SourceMain(JsonNode project) throws IOException { this(project,false); }
    private SourceMain(JsonNode project,boolean connectivityOnly) throws IOException {
        this.connectivityOnly=connectivityOnly;
        this.project = project; config = project.path("source");
        try { typedProject = io.github.gremlin2rdf.Json.MAPPER.treeToValue(project, io.github.gremlin2rdf.Project.class); codec = new SourceCodec(typedProject); }
        catch (IOException e) { throw new IllegalArgumentException("Strict source project required", e); }
        if (!config.path("extractionStrategy").asText().equals("verified-identity-manifest"))
            throw new IllegalArgumentException("Safe enumeration requires a verified identity manifest");
        String reference = config.path("authenticationRef").asText();
        if (!reference.matches("env:GRAPH_[A-Z0-9_]{1,100}")) throw new SecurityException("Credential reference required");
        String key = System.getenv(reference.substring(4));
        if (key == null || key.isBlank()) throw new io.github.gremlin2rdf.Project.CredentialUnavailable();
        timeout = project.path("limits").path("requestTimeoutMillis").asInt();
        if (timeout < 100 || timeout > 300000) throw new IllegalArgumentException("Invalid source deadline");
        requests = new SourceRequests(typedProject.limits().maxRetries(), timeout, typedProject.source().ruBudget(),
            millis -> Thread.sleep(millis + java.util.concurrent.ThreadLocalRandom.current().nextLong(50)));
        cluster = createCluster(clusterBuilder(project,key));
        client = cluster.connect();
    }
    static Cluster.Builder clusterBuilder(JsonNode project,String key) throws javax.net.ssl.SSLException {
        var config = project.path("source");
        var builder = Cluster.build(config.path("host").asText()).port(config.path("port").asInt())
            .enableSsl(true).serializer(new GraphSONMessageSerializerV2d0())
            .credentials("/dbs/" + config.path("database").asText() + "/colls/" + config.path("container").asText(), key)
            .maxContentLength(Math.min(1048576, project.path("limits").path("maxRecordBytes").asInt() * 2))
            .minInProcessPerConnection(1).maxInProcessPerConnection(1)
            .minSimultaneousUsagePerConnection(1).maxSimultaneousUsagePerConnection(1)
            .minConnectionPoolSize(1).maxConnectionPoolSize(1);
        builder.sslContext(new SourceTls(config.path("host").asText(), config.path("port").asInt(),
            config.path("customCaRef").isNull() ? null : io.github.gremlin2rdf.Project.secret(config.path("customCaRef").asText())));
        return builder;
    }
    static Cluster createCluster(Cluster.Builder builder) throws io.github.gremlin2rdf.Diagnostics.Failure {
        try { return builder.create(); }
        catch (IllegalArgumentException failure) {
            throw new io.github.gremlin2rdf.Diagnostics.Failure("INTERNAL_ADAPTER_CONFIGURATION","source-cluster-construction");
        }
    }
    private Object query(String script, Map<String, Object> bindings) throws Exception {
        return query(script, bindings, false);
    }
    private Object query(String script, Map<String, Object> bindings, boolean mutation) throws Exception {
        if (connectivityOnly && mutation) throw new SecurityException("Connectivity checks prohibit source mutations");
        if (!connectivityOnly) io.github.gremlin2rdf.SourceCompatibility.authorizeMode(typedProject);
        return requests.execute(() -> {
            try {
                var resultSet = client.submit(script, bindings);
                var result = resultSet.all().get(timeout, TimeUnit.MILLISECONDS);
                var attributes = resultSet.statusAttributes().get(timeout, TimeUnit.MILLISECONDS);
                return new SourceRequests.Response(result.stream().map(Result::getObject).toList(), attributes);
            } catch (ExecutionException | TimeoutException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                boolean transientFailure = cause instanceof IOException || cause instanceof TimeoutException
                    || cause instanceof org.apache.tinkerpop.gremlin.driver.exception.ConnectionException;
                Map<String, Object> attributes = Map.of();
                if (cause instanceof org.apache.tinkerpop.gremlin.driver.exception.ResponseException response) {
                    attributes = response.getStatusAttributes().orElse(Map.of());
                    transientFailure = "429".equals(String.valueOf(attributes.get("x-ms-status-code")));
                }
                throw new SourceRequests.Failure(transientFailure && !mutation, attributes, e);
            }
        });
    }
    private io.github.gremlin2rdf.GraphRecord extract(JsonNode entry, String manifestHash) throws Exception {
        var identity = io.github.gremlin2rdf.Json.MAPPER.treeToValue(entry, io.github.gremlin2rdf.IdentityEntry.class);
        boolean vertex = entry.path("kind").asText().equals("vertex");
        if (!vertex && !entry.path("kind").asText().equals("edge")) throw new IllegalArgumentException("Unknown manifest record");
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("g2rPartition", SourceCodec.scalar(entry.path("partition"))); bindings.put("g2rElementId", SourceCodec.scalar(entry.path("id")));
        bindings.put("g2rPartitionProperty", config.path("partitionKeyPath").asText().substring(1));
        if (!vertex) bindings.put("g2rOwnerId", SourceCodec.scalar(entry.path("ownerId")));
        String selection = vertex ? "g.V([g2rPartition,g2rElementId])" : "g.V([g2rPartition,g2rOwnerId]).outE().hasId(g2rElementId)";
        Object initialEtag = systemValue(selection, bindings, "_etag");
        long count = SourceCodec.count(query(selection + ".properties().count()", bindings));
        if (count > 10000)
            throw new IllegalArgumentException("Source property collection cannot fit bounded frame");
        String projection = vertex
            ? ".project('id','label','partition','properties').by(id()).by(label()).by(values(g2rPartitionProperty))"
                + ".by(properties().project('key','value','id','meta').by(key()).by(value()).by(id()).by(valueMap()).fold())"
            : ".project('id','label','partition','source','destination','properties').by(id()).by(label())"
                + ".by(outV().values(g2rPartitionProperty))"
                + ".by(outV().project('id','partition').by(id()).by(values(g2rPartitionProperty)))"
                + ".by(inV().project('id','partition').by(id()).by(values(g2rPartitionProperty)))"
                + ".by(properties().project('key','value').by(key()).by(value()).fold())";
        if (vertex && typedProject.mapping().fidelity().equals("reduced"))
            projection = ".project('id','label','partition','properties').by(id()).by(label()).by(values(g2rPartitionProperty))"
                + ".by(properties().project('key','value').by(key()).by(value()).fold())";
        var row = new HashMap<Object, Object>(SourceCodec.object(query(selection + projection, bindings)));
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("_etag", initialEtag);
        for (String key : vertex ? List.of("_ts", "_self", "_attachments", "_rid")
            : List.of("_ts", "_self", "_attachments", "_rid", "inVPartition", "outVPartition"))
            metadata.put(key, systemValue(selection, bindings, key));
        if (!SourceCodec.value(initialEtag).equals(SourceCodec.value(systemValue(selection, bindings, "_etag"))))
            throw new IOException("Source changed during record snapshot; mixed versions cannot be accepted");
        row.put("metadata", metadata);
        var result = codec.normalize(identity, row, count, manifestHash);
        if (!io.github.gremlin2rdf.SourceCompatibility.metadataComplete(result)) throw new IOException("Required observable source system metadata is missing or has incorrect types");
        return result;
    }
    private Object systemValue(String selection, Map<String, Object> bindings, String key) throws Exception {
        Map<String, Object> parameters = new HashMap<>(bindings); parameters.put("g2rSystemKey", key);
        Object folded = query("g.withStrategies(ProjectionStrategy.build().IncludeSystemProperties(g2rSystemKey).create())"
            + selection.substring(1) + ".values(g2rSystemKey).fold()", parameters);
        if (!(folded instanceof List<?> values) || values.size() != 1 || values.getFirst() == null)
            throw new IOException("Required system metadata did not return exactly one observable value");
        return values.getFirst();
    }
    private long count(io.github.gremlin2rdf.GraphRecord.Kind kind, io.github.gremlin2rdf.GraphRecord.Value partition, String label) throws Exception {
        Map<String, Object> bindings = new HashMap<>();
        String selection;
        if (partition == null) selection = kind == io.github.gremlin2rdf.GraphRecord.Kind.vertex ? "g.V()" : "g.E()";
        else {
            bindings.put("g2rPartitionProperty", typedProject.source().partitionKeyPath().substring(1));
            bindings.put("g2rPartition", SourceCodec.scalar(io.github.gremlin2rdf.Json.MAPPER.valueToTree(partition)));
            selection = "g.V().has(g2rPartitionProperty,g2rPartition)" + (kind == io.github.gremlin2rdf.GraphRecord.Kind.edge ? ".outE()" : "");
        }
        if (label != null) { bindings.put("g2rObservedLabel", label); selection += ".hasLabel(g2rObservedLabel)"; }
        return SourceCodec.count(query(selection + ".count()", bindings));
    }
    private List<String> enumeratePage(io.github.gremlin2rdf.GraphRecord.Value partition, String owner,
                                       String after, int limit) throws Exception {
        var request=enumerationPage(typedProject.source().partitionKeyPath().substring(1),
            SourceCodec.scalar(io.github.gremlin2rdf.Json.MAPPER.valueToTree(partition)),owner,after,limit);
        Object result=query(request.text(),request.bindings());
        if (!(result instanceof List<?> values) || values.size()>limit)
            throw new io.github.gremlin2rdf.Diagnostics.Failure("SOURCE_REQUEST_REJECTED","source-enumeration-page-validation");
        List<String> ids=new ArrayList<>();
        for (Object value:values) {
            if (!(value instanceof String id))
                throw new io.github.gremlin2rdf.Diagnostics.Failure("SOURCE_REQUEST_REJECTED","source-enumeration-page-validation");
            ids.add(id);
        }
        return List.copyOf(ids);
    }
    static PopulationScripts.Script enumerationPage(String partitionProperty,Object partition,String owner,String after,int limit) {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("g2rPartitionProperty",partitionProperty);
        bindings.put("g2rPartition",partition);
        bindings.put("g2rPageSize", limit);
        String selection;
        if (owner == null) selection = "g.V().has(g2rPartitionProperty,g2rPartition)";
        else {
            bindings.put("g2rOwner", owner);
            selection = "g.V([g2rPartition,g2rOwner]).outE()";
        }
        if (!after.isEmpty()) {
            bindings.put("g2rAfter", after);
            selection += ".hasId(gt(g2rAfter))";
        }
        return new PopulationScripts.Script(selection+".id().order().limit(g2rPageSize).fold()",Map.copyOf(bindings));
    }
    static Enumeration.Plan readEnumerationPlan(Path root,String reference) throws IOException {
        Path planPath=io.github.gremlin2rdf.SafeFiles.resolve(root,reference);
        if (!Files.isRegularFile(planPath))
            throw new io.github.gremlin2rdf.Diagnostics.Failure("SOURCE_ENUMERATION_PLAN_MISSING","source-enumeration-plan");
        if (Files.size(planPath)>1048576)
            throw new io.github.gremlin2rdf.Diagnostics.Failure("SOURCE_ENUMERATION_PLAN_INVALID","source-enumeration-plan");
        try {
            var plan=io.github.gremlin2rdf.Json.MAPPER.readValue(Files.readAllBytes(planPath),Enumeration.Plan.class);
            if (plan==null) throw new io.github.gremlin2rdf.Diagnostics.Failure("SOURCE_ENUMERATION_PLAN_INVALID","source-enumeration-plan");
            return plan;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new io.github.gremlin2rdf.Diagnostics.Failure("SOURCE_ENUMERATION_PLAN_INVALID","source-enumeration-plan");
        }
    }
    public static void main(String[] args) {
        try {
            if (args.length == 5 && args[0].equals("plan")) {
                Path configuration = Path.of(args[1]).toRealPath();
                if (Files.size(configuration) > 1048576) throw new IllegalArgumentException("Bounded project required");
                var parsed = io.github.gremlin2rdf.Json.MAPPER.readValue(Files.readAllBytes(configuration), io.github.gremlin2rdf.Project.class);
                var plan = io.github.gremlin2rdf.PopulationPlan.create(parsed, configuration.getParent(), args[2], Long.parseLong(args[4]));
                var output = io.github.gremlin2rdf.SafeFiles.resolve(configuration.getParent(), args[3]);
                if (Files.exists(output)) throw new IOException("New immutable population plan required");
                io.github.gremlin2rdf.SafeFiles.atomic(output, plan);
                System.out.println(io.github.gremlin2rdf.Json.text(Map.of("status", "succeeded", "planHash", plan.hash(), "estimatedWrites", plan.estimatedWrites())));
                return;
            }
            if ((args.length != 4 && args.length != 5) || !Set.of("check","enumerate", "extract", "probe", "populate", "source-cleanup", "recipes").contains(args[0])
                || args.length == 5 && !args[4].equals("--parent-owned"))
                throw new IllegalArgumentException("extract or probe project manifest output required");
            if (args.length == 5) monitorParent();
            Path configuration = Path.of(args[1]), manifest = Path.of(args[2]), output = Path.of(args[3]);
            if (Files.size(configuration) > 1048576) throw new IllegalArgumentException("Invalid config bounds");
            var parsed = io.github.gremlin2rdf.Json.MAPPER.readValue(Files.readAllBytes(configuration), io.github.gremlin2rdf.Project.class);
            Path root = configuration.toRealPath().getParent();
            if (args[0].equals("enumerate")) {
                var plan = readEnumerationPlan(root,args[2]);
                Path directory = io.github.gremlin2rdf.SafeFiles.resolve(root, args[3]);
                try (var source = new SourceMain(io.github.gremlin2rdf.Json.MAPPER.valueToTree(parsed))) {
                    source.requests.attachJournal(parsed, root);
                    var result = Enumeration.run(parsed, plan, directory, new Enumeration.Reader() {
                        public List<String> vertices(io.github.gremlin2rdf.GraphRecord.Value partition, String after, int limit) throws Exception {
                            return source.enumeratePage(partition, null, after, limit);
                        }
                        public List<String> edges(io.github.gremlin2rdf.GraphRecord.Value partition, String owner, String after, int limit) throws Exception {
                            return source.enumeratePage(partition, owner, after, limit);
                        }
                        public long count(io.github.gremlin2rdf.GraphRecord.Kind kind, io.github.gremlin2rdf.GraphRecord.Value partition) throws Exception {
                            return source.count(kind, partition, null);
                        }
                    });
                    System.out.println(io.github.gremlin2rdf.Json.text(result));
                }
                return;
            }
            if (args[0].equals("check")) {
                if (parsed.source() == null) throw new io.github.gremlin2rdf.Diagnostics.Failure("SOURCE_CONFIGURATION_REQUIRED","source-connectivity");
                Map<String,Object> result;
                try (var source = new SourceMain(io.github.gremlin2rdf.Json.MAPPER.valueToTree(parsed),true)) {
                    SourceCodec.count(source.query("g.V().limit(1).count()",Map.of()));
                    result = Map.of("schemaVersion",1,"status","succeeded","sourceReadAccess",true,
                        "driverVersion","3.4.13","requestCharge",source.requests.charge(),
                        "compatibility","not-established-by-connectivity-check");
                }
                System.out.println(io.github.gremlin2rdf.Json.text(result));return;
            }
            if (args[0].equals("recipes")) {
                Path selected = io.github.gremlin2rdf.SafeFiles.resolve(root, args[2]);
                if (Files.size(selected) > 65536) throw new IOException("Bounded source plan required");
                var plan = io.github.gremlin2rdf.Json.MAPPER.readValue(Files.readAllBytes(selected), io.github.gremlin2rdf.PopulationPlan.class);
                System.out.println(io.github.gremlin2rdf.Json.text(PopulationScripts.export(parsed, root, plan, args[3])));
                return;
            }
            if (Set.of("populate", "source-cleanup").contains(args[0])) {
                Path planPath = io.github.gremlin2rdf.SafeFiles.resolve(root, args[2]);
                if (Files.size(planPath) > 65536) throw new IOException("Bounded population plan required");
                var plan = io.github.gremlin2rdf.Json.MAPPER.readValue(Files.readAllBytes(planPath), io.github.gremlin2rdf.PopulationPlan.class);
                plan.verify(parsed, root);
                io.github.gremlin2rdf.Approval.require(parsed, args[0].equals("populate") ? "source-populate" : "source-cleanup", plan.resources());
                try (var source = new SourceMain(io.github.gremlin2rdf.Json.MAPPER.valueToTree(parsed))) {
                    source.requests.attachJournal(parsed, root);
                    var result = Population.run(parsed, root, plan, args[3], args[0].equals("source-cleanup"), source.populationTransport());
                    System.out.println(io.github.gremlin2rdf.Json.text(result));
                }
                return;
            }
            if (parsed.source() == null || !manifest.toAbsolutePath().normalize().equals(io.github.gremlin2rdf.SafeFiles.resolve(root, parsed.source().identityManifest())))
                throw new IllegalArgumentException("Manifest must match its safe project-relative path");
            JsonNode project = io.github.gremlin2rdf.Json.MAPPER.valueToTree(parsed);
            String expected = project.path("source").path("identityManifestHash").asText();
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(manifest)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expected)) throw new IllegalArgumentException("Identity enumeration manifest changed");
            boolean probe = args[0].equals("probe");
            if (probe && Files.exists(output)) throw new IllegalArgumentException("New immutable observation artifact required");
            if (probe) CompatibilityProbe.requireInventory(parsed,manifest);
            var compatibility = probe ? null : io.github.gremlin2rdf.SourceCompatibility.read(parsed, configuration.toRealPath().getParent());
            io.github.gremlin2rdf.SourceCompatibility.authorizeMode(parsed);
            try (SourceMain source = new SourceMain(project)) {
                source.requests.attachJournal(parsed, configuration.toRealPath().getParent());
                var reader = new Extraction.Reader() {
                    @Override public io.github.gremlin2rdf.GraphRecord read(io.github.gremlin2rdf.IdentityEntry entry) throws Exception {
                        if (!probe && entry.kind() == io.github.gremlin2rdf.GraphRecord.Kind.edge
                            && !io.github.gremlin2rdf.SourceCompatibility.supports(compatibility, entry.edgeScope()))
                            throw new IllegalArgumentException("Edge scope was not observed in compatibility evidence");
                        try { return source.extract(io.github.gremlin2rdf.Json.MAPPER.valueToTree(entry),actual); }
                        catch (Exception failure) { throw new SourceDiagnostics.Context("source-record-read",failure); }
                    }
                    @Override public long count(io.github.gremlin2rdf.GraphRecord.Kind kind, io.github.gremlin2rdf.GraphRecord.Value partition, String label) throws Exception {
                        try { return source.count(kind,partition,label); }
                        catch (Exception failure) { throw new SourceDiagnostics.Context("source-fidelity-count",failure); }
                    }
                };
                if (probe) {
                    var observation = CompatibilityProbe.run(parsed, manifest, reader);
                    io.github.gremlin2rdf.SafeFiles.atomic(output, Map.of("schemaVersion", 1, "runId", parsed.runId(), "inputHash", parsed.inputHash(),
                        "operation", "source-compatibility", "status", observation.get("status"), "sensitiveArtifact", true, "result", observation));
                    System.out.println(io.github.gremlin2rdf.Json.text(Map.of("status", observation.get("status"),
                        "artifactSha256", io.github.gremlin2rdf.SafeFiles.fileHash(output))));
                    if (!observation.get("status").equals("succeeded")) { System.exit(2); return; }
                    return;
                }
                var result = Extraction.run(parsed, manifest, output, reader);
                System.out.println(io.github.gremlin2rdf.Json.text(Map.of("status", result.status(), "records", result.records(),
                    "sourceHash", result.sha256(), "sourceRetries", source.requests.retries(), "requestCharge", source.requests.charge())));
            }
        } catch (Exception e) {
            Map<String,Object> diagnostic = SourceDiagnostics.failure(e,
                args.length > 0 && args[0].equals("check") ? "source-connectivity" : "source-operation");
            // Only fixed sanitized diagnostics are returned on stdout; raw driver stderr stays private.
            System.out.println(io.github.gremlin2rdf.Json.text(diagnostic));
            System.exit(2);
        }
    }
    private Population.Transport populationTransport() {
        return new Population.Transport() {
            @Override public boolean empty() throws Exception {
                return count(io.github.gremlin2rdf.GraphRecord.Kind.vertex, null, null) == 0
                    && count(io.github.gremlin2rdf.GraphRecord.Kind.edge, null, null) == 0;
            }
            @Override public Population.Observation inspect(io.github.gremlin2rdf.GraphRecord expected, boolean compareContent) throws Exception {
                var bindings = PopulationScripts.bindings(typedProject, expected);
                String selection = PopulationScripts.selection(expected);
                long found = SourceCodec.count(query(selection + ".count()", bindings));
                if (found > 1) throw new IOException("Ambiguous source identity");
                if (found == 0) return new Population.Observation(false, false, false, 0, null);
                boolean owned = SourceCodec.count(query(selection + ".has(g2rOwnerKey,g2rRun).count()", bindings)) == 1;
                long incident = expected.kind() == io.github.gremlin2rdf.GraphRecord.Kind.vertex
                    ? SourceCodec.count(query(selection + ".bothE().count()", bindings)) : 0;
                boolean matches = false;
                if (owned && compareContent) {
                    var entry = new io.github.gremlin2rdf.IdentityEntry(1, expected.kind(), expected.identity().partition(), expected.identity().id(),
                        expected.source() == null ? null : expected.source().id(), expected.edgeScope());
                    var actual = extract(io.github.gremlin2rdf.Json.MAPPER.valueToTree(entry), typedProject.source().identityManifestHash());
                    var expectedJson = io.github.gremlin2rdf.Json.MAPPER.valueToTree(expected);
                    var actualJson = io.github.gremlin2rdf.Json.MAPPER.valueToTree(actual);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) expectedJson).remove("provenance");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) actualJson).remove("provenance");
                    // System attributes are allocated by the service; only written fixture content is compared here.
                    ((com.fasterxml.jackson.databind.node.ObjectNode) expectedJson).remove("metadata");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) actualJson).remove("metadata");
                    matches = expectedJson.equals(actualJson);
                }
                Object etag = systemValue(selection, bindings, "_etag");
                if (!(etag instanceof String version) || version.isEmpty()) throw new IOException("Observed source ETag required");
                return new Population.Observation(true, owned, matches, incident, version);
            }
            @Override public void create(io.github.gremlin2rdf.GraphRecord record) throws Exception {
                var script = PopulationScripts.create(typedProject, record);
                if (SourceCodec.count(query(script.text(), script.bindings(), true)) != 1) throw new IOException("Source creation did not affect exactly one owned entity");
            }
            @Override public void delete(io.github.gremlin2rdf.GraphRecord record, String etag) throws Exception {
                var script = PopulationScripts.delete(typedProject, record, etag);
                if (SourceCodec.count(query(script.text(), script.bindings(), true)) != 0) throw new IOException("Unexpected source deletion acknowledgement");
            }
        };
    }
    static void monitorParent() {
        Thread.startVirtualThread(() -> {
            try { while (System.in.read() != -1) {} }
            catch (IOException e) { Runtime.getRuntime().halt(5); }
            Runtime.getRuntime().halt(5);
        });
    }
    @Override public void close() throws IOException {
        try { client.close(); } finally { try { cluster.close(); } finally { requests.close(); } }
    }
}
