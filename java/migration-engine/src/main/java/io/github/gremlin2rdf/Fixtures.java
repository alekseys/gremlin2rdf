package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Fixtures {
    private Fixtures() {}
    public record Artifact(String path, String sha256, String kind) {}
    public record Catalog(int schemaVersion, String runId, String inputHash, String sourcePath, String sourceHash,
                          String graph, List<Artifact> artifacts, boolean sensitiveArtifact) {}
    public record QueryFixture(String gremlin, Translator.Translation translation, List<Map<String, GraphAccess.Term>> expected) {}
    public record Expectation(String entity, GraphRecord record) {}
    public record Mutation(String operation, String entity, GraphRecord record, GraphRecord.Property property,
                           List<GraphRecord.Property> properties, String incidentPolicy, String expectedStatus,
                           List<Expectation> expected) {}
    public record MutationCase(int index, String operation, String status, int verifiedExpectations, long elapsedNanos) {}
    public record Report(int schemaVersion, String status, String inputHash, String sourceHash, String catalogHash,
                         List<Functional.Case> queries, List<MutationCase> mutations, String oracle,
                         String deployedAtomicity, boolean sensitiveArtifact) {}
    private static final long MAX_ARTIFACT_BYTES = 16777216;

    public static Catalog generate(Project project, Path root, String sourcePath, String output, String graph) throws IOException {
        Mapping.iri(graph);
        if (project.target() != null && !project.graph("canonical").equals(graph))
            throw new IllegalArgumentException("Fixture graph must match the configured canonical graph");
        Path source = SafeFiles.resolve(root, sourcePath), directory = SafeFiles.resolve(root, output);
        String sourceHash = SafeFiles.fileHash(source);
        var records = Functional.records(project, source);
        if (records.isEmpty()) throw new IllegalArgumentException("Nonempty bounded canonical fixture required");
        var mapper = new RdfMapper(project.mapping()); var identities = new HashSet<String>(); var vertices = new HashSet<String>();
        var groups = new TreeMap<String, Long>(); long properties = 0, meta = 0;
        for (var record : records) {
            if (!identities.add(mapper.entity(record))) throw new IllegalArgumentException("Duplicate canonical fixture identity");
            if (record.kind() == GraphRecord.Kind.vertex) vertices.add(mapper.entity(record));
            groups.merge(Json.text(List.of(record.kind(), record.identity().partition(), record.label())), 1L, Long::sum);
            properties += record.properties().size();
            for (var property : record.properties()) meta += property.metaProperties().size();
        }
        for (var record : records) if (record.kind() == GraphRecord.Kind.edge
            && (!vertices.contains(mapper.vertex(record.source())) || !vertices.contains(mapper.vertex(record.destination()))))
            throw new IllegalArgumentException("Fixture edge endpoint does not resolve");
        Files.createDirectory(directory);
        SafeFiles.atomic(directory.resolve("ownership.json"), Map.of("schemaVersion", 1, "runId", project.runId(),
            "inputHash", project.inputHash(), "sourceHash", sourceHash, "kind", "generated-fixture-catalog"));
        List<Artifact> artifacts = new ArrayList<>(); var reference = new Reference(records, project.mapping());
        int number = 0;
        for (String query : Functional.QUERIES) {
            var translated = new Translator(graph, Map.of()).translate(query);
            String name = String.format(Locale.ROOT, "query-%02d.json", number++);
            write(directory, name, new QueryFixture(query, translated, Functional.normalize(reference.execute(query))), "query", artifacts);
        }
        var baseline = records.stream().filter(record -> record.kind() == GraphRecord.Kind.vertex).findFirst().orElseThrow();
        var mutations = mutations(project, baseline, sourceHash);
        if (mutations.stream().filter(mutation -> mutation.operation().equals("create"))
            .anyMatch(mutation -> identities.contains(mutation.entity())))
            throw new IllegalArgumentException("Generated mutation fixture collides with canonical identity");
        write(directory, "mutations.json", mutations, "mutation", artifacts);
        write(directory, "expected-records.json", Map.of("schemaVersion", 1, "sourceHash", sourceHash, "records", records.size(),
            "vertices", vertices.size(), "edges", records.size() - vertices.size(), "partitionLabelCounts", groups,
            "propertyOccurrences", properties, "metaProperties", meta), "record-manifest", artifacts);
        if (!sourceHash.equals(SafeFiles.fileHash(source))) throw new IOException("Canonical fixture changed during derivation; partial catalog is not loadable");
        var catalog = new Catalog(1, project.runId(), project.inputHash(), sourcePath, sourceHash, graph, List.copyOf(artifacts), true);
        SafeFiles.atomic(directory.resolve("catalog.json"), catalog);
        return catalog;
    }
    public static Report run(Project project, Path root, String relativeDirectory, Sink sink) throws IOException {
        return run(project, root, relativeDirectory, sink, new Engine());
    }
    public static Report run(Project project, Path root, String relativeDirectory, Sink sink, Engine engine) throws IOException {
        Path directory = SafeFiles.resolve(root, relativeDirectory), manifest = SafeFiles.resolve(directory, "catalog.json");
        var catalog = read(manifest, Catalog.class); String catalogHash = SafeFiles.fileHash(manifest);
        if (catalog.schemaVersion() != 1 || !catalog.runId().equals(project.runId()) || !catalog.inputHash().equals(project.inputHash())
            || !catalog.graph().equals(project.graph("canonical")) || catalog.artifacts().size() != Functional.QUERIES.size() + 2)
            throw new IOException("Changed or incompatible fixture catalog");
        List<String> resources = List.of(project.graph("canonical"), project.graph("derived"), catalogHash);
        Approval.require(project, "fixture-test", resources);
        Approval.require(project, "application-write", List.of(project.graph("canonical"), project.graph("derived")));
        Approval.require(project, "application-delete", List.of(project.graph("canonical"), project.graph("derived")));
        Path source = SafeFiles.resolve(root, catalog.sourcePath());
        if (!catalog.sourceHash().equals(SafeFiles.fileHash(source))) throw new IOException("Changed canonical fixture source");
        for (var artifact : catalog.artifacts()) {
            Path selected = SafeFiles.resolve(directory, artifact.path());
            if (Files.size(selected) > MAX_ARTIFACT_BYTES || !artifact.sha256().equals(SafeFiles.fileHash(selected)))
                throw new IOException("Fixture artifact checksum or size does not match catalog");
        }
        var records = Functional.records(project, source);
        var reference = new Reference(records, project.mapping()); List<Functional.Case> queries = new ArrayList<>();
        var access = new GraphAccess(project, sink);
        for (int i = 0; i < Functional.QUERIES.size(); i++) {
            if (engine.stopping()) return stopped(project, catalog, catalogHash, queries, List.of(), engine);
            String name = String.format(Locale.ROOT, "query-%02d.json", i);
            if (catalog.artifacts().stream().noneMatch(artifact -> artifact.path().equals(name) && artifact.kind().equals("query")))
                throw new IOException("Required query fixture missing");
            var fixture = read(SafeFiles.resolve(directory, name), QueryFixture.class);
            var translated = new Translator(catalog.graph(), Map.of()).translate(Functional.QUERIES.get(i));
            if (!fixture.gremlin().equals(Functional.QUERIES.get(i)) || !fixture.translation().equals(translated)
                || !fixture.expected().equals(Functional.normalize(reference.execute(fixture.gremlin()))))
                throw new IOException("Fixture differs from independently regenerated canonical oracle");
            List<Map<String, GraphAccess.Term>> actual = new ArrayList<>();
            for (var row : access.read(translated.sparql()))
                actual.add(translated.shape().equals("projection") ? row : Map.of("value", row.values().iterator().next()));
            queries.add(Functional.compare(translated, fixture.expected(), Functional.normalize(actual)));
        }
        if (queries.stream().anyMatch(result -> !result.status().equals("succeeded")))
            return new Report(1, "failed", project.inputHash(), catalog.sourceHash(), catalogHash, List.copyOf(queries),
                List.of(), "independent-canonical-reference-v1-not-Cosmos", "not-proven-by-this-runner", true);
        var baseline = records.stream().filter(record -> record.kind() == GraphRecord.Kind.vertex).findFirst().orElseThrow();
        var expected = mutations(project, baseline, catalog.sourceHash());
        var mutations = Arrays.asList(read(SafeFiles.resolve(directory, "mutations.json"), Mutation[].class));
        if (!mutations.equals(expected)) throw new IOException("Mutation catalog differs from regenerated declared fixture policy");
        for (var mutation : mutations) if (mutation.operation().equals("create") && access.lookup(mutation.entity()).isPresent())
            throw new SecurityException("Mutation fixture would adopt a pre-existing identity");
        if (engine.stopping()) return stopped(project, catalog, catalogHash, queries, List.of(), engine);
        if (!Validation.validate(project, source, sink).status().equals("succeeded"))
            throw new IOException("Full canonical/derived fixture must validate before mutation acceptance");
        List<MutationCase> cases = new ArrayList<>();
        for (int i = 0; i < mutations.size(); i++) {
            if (engine.stopping()) return stopped(project, catalog, catalogHash, queries, cases, engine);
            long started = System.nanoTime();
            Approval.require(project, "fixture-test", resources);
            var mutation = mutations.get(i);
            try {
            if (mutation.expectedStatus().equals("blocked")) {
                boolean blocked = false;
                try { access.deleteVertex(mutation.entity(), mutation.incidentPolicy()); }
                catch (IllegalStateException incident) { blocked = true; }
                if (!blocked) throw new IOException("Declared incident-edge rejection did not occur");
            } else switch (mutation.operation()) {
                case "create" -> access.create(mutation.record());
                case "lookup" -> access.lookup(mutation.entity());
                case "insertProperty" -> access.insertProperty(mutation.entity(), mutation.property());
                case "replaceProperties" -> access.replaceProperties(mutation.entity(), mutation.properties());
                case "removeProperty" -> access.removeProperty(mutation.entity(), mutation.property().key());
                case "deleteEdge" -> access.deleteEdge(mutation.entity());
                case "deleteVertex" -> access.deleteVertex(mutation.entity(), mutation.incidentPolicy());
                default -> throw new IllegalArgumentException("Unsupported declared fixture mutation");
            }
            } catch (RuntimeException failure) {
                failure.addSuppressed(new IllegalStateException("Declared fixture step " + i + " (" + mutation.operation() + ") failed"));
                throw failure;
            }
            for (var expectation : mutation.expected())
                if (!access.lookup(expectation.entity()).equals(Optional.ofNullable(expectation.record())))
                    throw new IOException("Typed mutation fixture postcondition differs from expected canonical record");
            cases.add(new MutationCase(i, mutation.operation(), "succeeded", mutation.expected().size(), System.nanoTime() - started));
        }
        if (!Validation.validate(project, source, sink).status().equals("succeeded"))
            throw new IOException("Mutation fixture left canonical/derived corruption or unexpected resources");
        if (!catalog.sourceHash().equals(SafeFiles.fileHash(source)) || !catalogHash.equals(SafeFiles.fileHash(manifest)))
            throw new IOException("Fixture inputs changed during execution; no acceptance report");
        for (var artifact : catalog.artifacts())
            if (!artifact.sha256().equals(SafeFiles.fileHash(SafeFiles.resolve(directory, artifact.path()))))
                throw new IOException("Fixture evidence changed during execution");
        return new Report(1, "succeeded", project.inputHash(), catalog.sourceHash(), catalogHash, List.copyOf(queries),
            List.copyOf(cases), "independent-canonical-reference-v1-not-Cosmos", "not-proven-by-this-runner", true);
    }
    private static Report stopped(Project project, Catalog catalog, String hash, List<Functional.Case> queries,
                                  List<MutationCase> mutations, Engine engine) {
        return new Report(1, engine.stoppedStatus(), project.inputHash(), catalog.sourceHash(), hash, List.copyOf(queries),
            List.copyOf(mutations), "partial-fixture-state-preserved-reconcile-before-rerun",
            "not-proven-by-this-runner", true);
    }
    private static <T> T read(Path path, Class<T> type) throws IOException {
        if (Files.size(path) > MAX_ARTIFACT_BYTES) throw new IOException("Fixture artifact exceeds bounded bytes");
        return Json.MAPPER.readValue(Files.readAllBytes(path), type);
    }
    private static void write(Path directory, String name, Object value, String kind, List<Artifact> artifacts) throws IOException {
        if (Json.text(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ARTIFACT_BYTES)
            throw new IOException("Generated semantic fixture artifact exceeds bounded bytes");
        SafeFiles.atomic(directory.resolve(name), value);
        artifacts.add(new Artifact(name, SafeFiles.fileHash(directory.resolve(name)), kind));
    }
    private static GraphRecord vertex(GraphRecord baseline, String id) {
        return new GraphRecord(GraphRecord.Kind.vertex, new GraphRecord.Identity(baseline.identity().scope(),
            baseline.identity().partition(), GraphRecord.Value.string(id)), baseline.label(), null, null, null,
            baseline.properties(), baseline.metadata(), baseline.provenance());
    }
    private static GraphRecord properties(GraphRecord record, List<GraphRecord.Property> properties) {
        return new GraphRecord(record.kind(), record.identity(), record.label(), record.source(), record.destination(),
            record.edgeScope(), properties, record.metadata(), record.provenance());
    }
    private static List<Mutation> mutations(Project project, GraphRecord baseline, String sourceHash) {
        String prefix = "g2r-mutation-" + sourceHash.substring(0, 32);
        var a = vertex(baseline, prefix + "-a"); var b = vertex(baseline, prefix + "-b");
        var edge = new GraphRecord(GraphRecord.Kind.edge, new GraphRecord.Identity(a.identity().scope(), a.identity().partition(),
            GraphRecord.Value.string(prefix + "-edge")), "knows", a.identity(), b.identity(), GraphRecord.EdgeScope.sourcePartition,
            List.of(), Map.of(), a.provenance());
        var mapper = new RdfMapper(project.mapping()); String ai = mapper.entity(a), bi = mapper.entity(b), ei = mapper.entity(edge);
        var p = new GraphRecord.Property("fixture-multi", GraphRecord.Value.string("equal"), GraphRecord.Value.string(prefix + "-p1"),
            Map.of("meta", GraphRecord.Value.string("retained")), GraphRecord.Fidelity.complete);
        var q = new GraphRecord.Property(p.key(), p.value(), GraphRecord.Value.string(prefix + "-p2"), p.metaProperties(), p.fidelity());
        var one = new ArrayList<>(a.properties()); one.add(p);
        var two = new ArrayList<>(one); two.add(q);
        var a1 = properties(a, one); var a2 = properties(a, two);
        return List.of(
            new Mutation("create", ai, a, null, null, null, "succeeded", List.of(new Expectation(ai, a))),
            new Mutation("lookup", ai, null, null, null, null, "succeeded", List.of(new Expectation(ai, a))),
            new Mutation("create", bi, b, null, null, null, "succeeded", List.of(new Expectation(bi, b))),
            new Mutation("create", ei, edge, null, null, null, "succeeded", List.of(new Expectation(ei, edge))),
            new Mutation("insertProperty", ai, null, p, null, null, "succeeded", List.of(new Expectation(ai, a1))),
            new Mutation("insertProperty", ai, null, p, null, null, "succeeded", List.of(new Expectation(ai, a1))),
            new Mutation("insertProperty", ai, null, q, null, null, "succeeded", List.of(new Expectation(ai, a2))),
            new Mutation("replaceProperties", ai, null, null, a.properties(), null, "succeeded", List.of(new Expectation(ai, a))),
            new Mutation("removeProperty", ai, null, new GraphRecord.Property(p.key(), p.value(), p.id(), Map.of(), p.fidelity()),
                null, null, "succeeded", List.of(new Expectation(ai, a))),
            new Mutation("deleteVertex", ai, null, null, null, "reject", "blocked", List.of(new Expectation(ai, a), new Expectation(ei, edge))),
            new Mutation("deleteEdge", ei, null, null, null, null, "succeeded", List.of(new Expectation(ei, null))),
            new Mutation("deleteEdge", ei, null, null, null, null, "succeeded", List.of(new Expectation(ei, null))),
            new Mutation("create", ei, edge, null, null, null, "succeeded", List.of(new Expectation(ei, edge))),
            new Mutation("deleteVertex", bi, null, null, null, "cascade-edges", "succeeded", List.of(new Expectation(bi, null),
                new Expectation(ei, null), new Expectation(ai, a))),
            new Mutation("deleteVertex", ai, null, null, null, "reject", "succeeded", List.of(new Expectation(ai, null))));
    }
}
