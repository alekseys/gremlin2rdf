package io.github.gremlin2rdf;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public final class Synthetic {
    private Synthetic() {}
    public record Profile(int schemaVersion, long seed, int vertices, int edges, int partitions) {
        public Profile {
            if (schemaVersion != 1 || vertices < 1 || edges < 0 || partitions < 1 || partitions > vertices)
                throw new IllegalArgumentException("Invalid generator profile");
        }
        public static Profile semantic(long seed) { return new Profile(1, seed, 200, 800, 4); }
        public static Profile ci(long seed) { return new Profile(1, seed, 10000, 50000, 8); }
        public static Profile load(long seed) { return new Profile(1, seed, 100000, 1000000, 16); }
    }
    public record Manifest(int schemaVersion, Profile profile, long records, long bytes, String sha256, String consistency) {}
    public static GraphRecord.Identity identity(Profile profile, int index) {
        return new GraphRecord.Identity("synthetic-v1:" + profile.seed(),
            GraphRecord.Value.string("partition-" + (index % profile.partitions())),
            GraphRecord.Value.string("vertex-" + (index / profile.partitions())));
    }
    public static GraphRecord record(Profile profile, long index) {
        var provenance = new GraphRecord.Provenance("synthetic", Json.hash(profile), "immutable", GraphRecord.Fidelity.complete);
        if (index < profile.vertices()) {
            String[] text = {"", "Unicode: \u2603 \u4e2d", "'\"\\\n", "'); DROP GRAPH <urn:production>; #"};
            var properties = new ArrayList<GraphRecord.Property>();
            properties.add(property("name", GraphRecord.Value.string(text[(int) index % text.length]), Map.of()));
            properties.add(property("number", new GraphRecord.Value(GraphRecord.ValueType.integer,
                index % 17 == 0 ? "9223372036854775808" : Long.toString(index)), Map.of()));
            properties.add(property("active", new GraphRecord.Value(GraphRecord.ValueType.booleanValue,
                index % 2 == 0 ? "false" : "true"), Map.of()));
            var duplicate = property("tag", GraphRecord.Value.string("duplicate"), Map.of("origin", GraphRecord.Value.string("fixture")));
            properties.add(duplicate); properties.add(duplicate);
            if (index == 0) properties.add(property("floatingBoundary",
                new GraphRecord.Value(GraphRecord.ValueType.floatValue, Float.toString(Float.MAX_VALUE)), Map.of()));
            if (index == 1) properties.add(property("floatingBoundary",
                new GraphRecord.Value(GraphRecord.ValueType.doubleValue, Double.toString(Double.MIN_NORMAL)), Map.of()));
            for (int occurrence = 0; occurrence < properties.size(); occurrence++) {
                var property = properties.get(occurrence);
                properties.set(occurrence, new GraphRecord.Property(property.key(), property.value(),
                    GraphRecord.Value.string("occurrence-" + occurrence), property.metaProperties(), property.fidelity()));
            }
            return new GraphRecord(GraphRecord.Kind.vertex, identity(profile, (int) index),
                index % 11 == 0 ? "Edge" : index % 2 == 0 ? "person" : "device", null, null, null,
                properties, Map.of(), provenance);
        }
        long e = index - profile.vertices();
        if (e >= profile.edges()) throw new IllegalArgumentException("Fixture index outside profile");
        // Pairs intentionally share endpoints; every eleventh edge is a self-loop and hubs are deterministic.
        int connected = Math.max(1, profile.vertices() - 1);
        int from = (int) ((e / 2) % connected);
        int to = e % 11 == 0 ? from : e % 7 == 0 ? 0 : (from + 1) % connected;
        var source = identity(profile, from);
        return new GraphRecord(GraphRecord.Kind.edge,
            new GraphRecord.Identity(source.scope(), source.partition(), GraphRecord.Value.string("edge-" + e)),
            e % 3 == 0 ? "knows" : "connects", source, identity(profile, to), GraphRecord.EdgeScope.sourcePartition,
            List.of(property("weight", new GraphRecord.Value(GraphRecord.ValueType.doubleValue, "0.0"), Map.of())),
            Map.of(), provenance);
    }
    private static GraphRecord.Property property(String key, GraphRecord.Value value, Map<String, GraphRecord.Value> meta) {
        return new GraphRecord.Property(key, value, null, meta, GraphRecord.Fidelity.complete);
    }
    public static Manifest generate(Profile profile, Path output) throws IOException {
        if (Files.exists(output)) throw new IOException("Refusing to replace existing fixture");
        var digest = digest();
        long bytes = 0, count = (long) profile.vertices() + profile.edges();
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            for (long i = 0; i < count; i++) {
                String line = Json.text(record(profile, i)) + "\n";
                byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
                digest.update(encoded); bytes += encoded.length;
                writer.write(line);
            }
        }
        var manifest = new Manifest(1, profile, count, bytes, HexFormat.of().formatHex(digest.digest()), "immutable");
        Files.writeString(output.resolveSibling(output.getFileName() + ".manifest.json"), Json.text(manifest),
            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        return manifest;
    }
    public static Map<String, Object> sourceFixture(Project project, long seed, Path output) throws IOException {
        if (project.source() == null || Files.exists(output)) throw new IOException("Configured source and new fixture output required");
        var profile = Profile.semantic(seed);
        var ordered = new TreeMap<String, GraphRecord>();
        var negatives = new ArrayList<Map<String, Object>>();
        for (long i = 0; i < (long) profile.vertices() + profile.edges(); i++) {
            var original = record(profile, i);
            var identity = sourceIdentity(project, original.identity());
            var properties = new ArrayList<GraphRecord.Property>();
            int ordinal = 0;
            for (var property : original.properties()) {
                GraphRecord.Value value = property.value();
                if (value.type() == GraphRecord.ValueType.integer && new java.math.BigInteger(value.lexical()).bitLength() > 63) {
                    negatives.add(Map.of("fixtureIndex", i, "key", property.key(), "unsupportedValue", value,
                        "expectedDiagnostic", "SOURCE_POPULATION_INT64_RANGE"));
                    // This is a separately declared native-int64 generator profile, not a conversion of a source value.
                    value = GraphRecord.Value.integer(i);
                }
                properties.add(new GraphRecord.Property(property.key(), value,
                    original.kind() == GraphRecord.Kind.vertex ? GraphRecord.Value.string(identity.id().lexical() + "-property-" + ordinal++) : null,
                    property.metaProperties(), GraphRecord.Fidelity.complete));
            }
            if (original.kind() == GraphRecord.Kind.vertex)
                properties.add(new GraphRecord.Property(project.source().partitionKeyPath().substring(1), identity.partition(),
                    GraphRecord.Value.string(identity.id().lexical() + "-partition"), Map.of(), GraphRecord.Fidelity.complete));
            properties.add(new GraphRecord.Property(PopulationPlan.OWNER_KEY, GraphRecord.Value.string(project.runId()),
                original.kind() == GraphRecord.Kind.vertex ? GraphRecord.Value.string(identity.id().lexical() + "-owner") : null,
                Map.of(), GraphRecord.Fidelity.complete));
            var generated = new GraphRecord(original.kind(), identity, original.label(),
                original.source() == null ? null : sourceIdentity(project, original.source()),
                original.destination() == null ? null : sourceIdentity(project, original.destination()), original.edgeScope(),
                properties, original.metadata(), new GraphRecord.Provenance("synthetic-native-int64-v1", Json.hash(List.of(profile, project.source().scope(), project.runId())),
                    project.consistency(), GraphRecord.Fidelity.complete));
            PopulationPlan.validateRecord(project, generated);
            var entry = new IdentityEntry(1, generated.kind(), identity.partition(), identity.id(),
                generated.source() == null ? null : generated.source().id(), generated.edgeScope());
            if (ordered.put(entry.orderingKey(), generated) != null) throw new IOException("Duplicate generated identity");
        }
        Path identities = output.resolveSibling(output.getFileName() + ".identities.ndjson");
        Path negative = output.resolveSibling(output.getFileName() + ".negative.json");
        Path manifest = output.resolveSibling(output.getFileName() + ".manifest.json");
        for (var path : List.of(identities, negative, manifest))
            if (Files.exists(path)) throw new IOException("Refusing to replace existing source fixture artifact");
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
             var entries = Files.newBufferedWriter(identities, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            for (var generated : ordered.values()) {
                writer.write(Json.text(generated) + "\n");
                entries.write(Json.text(new IdentityEntry(1, generated.kind(), generated.identity().partition(), generated.identity().id(),
                    generated.source() == null ? null : generated.source().id(), generated.edgeScope())) + "\n");
            }
        }
        SafeFiles.atomic(negative, negatives);
        var result = Map.<String, Object>of("schemaVersion", 1, "generatorVariant", "cosmos-native-int64-v1", "profile", profile,
            "runPrefix", PopulationPlan.prefix(project), "sourceScope", project.source().scope(), "sha256", SafeFiles.fileHash(output),
            "identityManifestHash", SafeFiles.fileHash(identities), "negativeFixtureHash", SafeFiles.fileHash(negative),
            "records", ordered.size(), "sourceWritesExecuted", false);
        SafeFiles.atomic(manifest, result);
        return result;
    }
    private static GraphRecord.Identity sourceIdentity(Project project, GraphRecord.Identity identity) {
        return new GraphRecord.Identity(project.source().scope(), identity.partition(),
            GraphRecord.Value.string(PopulationPlan.prefix(project) + identity.id().lexical()));
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
