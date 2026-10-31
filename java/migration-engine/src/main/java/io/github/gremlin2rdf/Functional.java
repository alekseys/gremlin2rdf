package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class Functional {
    private Functional() {}
    public static final List<String> QUERIES = List.of(
        "g.V()", "g.E()", "g.V().count()", "g.E().count()", "g.V().out()", "g.V().in()", "g.V().both()",
        "g.V().outE('knows').inV()", "g.V().inE().outV()", "g.V().bothE().bothV()",
        "g.V().hasLabel('person')", "g.V().has('active',false)", "g.V().has('number',P.gte(10))",
        "g.V().has('missing')", "g.V().values('tag')", "g.V().values('tag').dedup()",
        "g.V().id()", "g.E().label()", "g.V().as('a').out().select('a')",
        "g.V().as('a').out().as('b').select('a','b')",
        "g.V().project('identifier','type').by(id()).by(label())",
        "g.V().values('number').order().by(desc).range(2,7)",
        "g.V().values('tag').dedup().order().limit(1)",
        "g.V(['partition-0','vertex-0']).outE().inV().count()", "g.E().values('weight')", "g.V().values('floatingBoundary')"
    );
    public record Difference(long position, Map<String, GraphAccess.Term> expected, Map<String, GraphAccess.Term> actual,
                             long expectedMultiplicity, long actualMultiplicity) {}
    public record Case(String fingerprint, String status, String comparison, long expectedRows, long actualRows,
                       long mismatchCount, List<Difference> differences) {}
    public record Report(int schemaVersion, String status, String oracle, String liveCosmosCompatibility,
                         String inputHash, String sourceHash, List<Case> cases, boolean sensitiveArtifact,
                         List<String> normalizations) {}
    public static Report run(Project project, Path source, Sink sink) throws IOException {
        String sourceHash = SafeFiles.fileHash(source);
        List<GraphRecord> records = records(project, source);
        var reference = new Reference(records, project.mapping());
        var access = new GraphAccess(project, sink); List<Case> cases = new ArrayList<>();
        for (String query : QUERIES) {
            var translated = new Translator(project.graph("canonical"), Map.of()).translate(query);
            var expected = normalize(reference.execute(query));
            var raw = access.read(translated.sparql()); List<Map<String, GraphAccess.Term>> actual = new ArrayList<>();
            for (var row : raw) actual.add(translated.shape().equals("projection") ? row : Map.of("value", row.values().iterator().next()));
            cases.add(compare(translated, expected, normalize(actual)));
        }
        if (!sourceHash.equals(SafeFiles.fileHash(source))) throw new IOException("Canonical oracle input changed during comparison");
        return new Report(1, cases.stream().allMatch(c -> c.status().equals("succeeded")) ? "succeeded" : "failed",
            "independent-canonical-reference-v1", "blocked-no-live-credentials", project.inputHash(), sourceHash, List.copyOf(cases), true,
            List.of("Only xsd:float/xsd:double lexical variants normalize to the same finite IEEE value; datatype, signed zero and other RDF terms remain distinct"));
    }
    static List<GraphRecord> records(Project project, Path source) throws IOException {
        if (java.nio.file.Files.size(source) > 16777216) throw new IOException("Semantic oracle input exceeds bounded bytes");
        List<GraphRecord> records = new ArrayList<>();
        try (var reader = new RecordReader(source, project.limits().maxRecordBytes(), 0)) {
            GraphRecord record;
            while ((record = reader.next()) != null) {
                if (records.size() == 10000 || reader.offset() > 16777216)
                    throw new IOException("Semantic reference oracle bound exceeded; use isolated live/oracle tooling");
                records.add(record);
            }
        }
        return List.copyOf(records);
    }
    static Case compare(Translator.Translation translated, List<Map<String, GraphAccess.Term>> expected,
                        List<Map<String, GraphAccess.Term>> actual) {
        List<Difference> differences = new ArrayList<>(); long count = 0;
        if (translated.comparison().equals("sequence")) {
            for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
                var left = i < expected.size() ? expected.get(i) : null;
                var right = i < actual.size() ? actual.get(i) : null;
                if (!Objects.equals(left, right)) {
                    count++;
                    if (differences.size() < 20) differences.add(new Difference(i, left, right, left == null ? 0 : 1, right == null ? 0 : 1));
                }
            }
        } else {
            if (!Set.of("set", "multiset").contains(translated.comparison())) throw new IllegalArgumentException("Unsupported comparison policy");
            var left = bag(expected); var right = bag(actual);
            var rows = new HashSet<>(left.keySet()); rows.addAll(right.keySet());
            for (var row : rows.stream().sorted(Comparator.comparing(value -> Json.text(new TreeMap<>(value)))).toList()) {
                long a = left.getOrDefault(row, 0L), b = right.getOrDefault(row, 0L);
                if (a != b) {
                    count++;
                    if (differences.size() < 20) differences.add(new Difference(-1, a == 0 ? null : row, b == 0 ? null : row, a, b));
                }
            }
        }
        return new Case(translated.fingerprint(), count == 0 ? "succeeded" : "failed", translated.comparison(),
            expected.size(), actual.size(), count, List.copyOf(differences));
    }
    static List<Map<String, GraphAccess.Term>> normalize(List<Map<String, GraphAccess.Term>> rows) {
        List<Map<String, GraphAccess.Term>> normalized = new ArrayList<>();
        for (var row : rows) {
            Map<String, GraphAccess.Term> result = new TreeMap<>();
            for (var entry : row.entrySet()) {
                var term = entry.getValue(); String lexical = term.lexical();
                if (term.kind().equals("literal") && term.datatype().equals("http://www.w3.org/2001/XMLSchema#float"))
                    lexical = Float.toString(Float.parseFloat(lexical));
                else if (term.kind().equals("literal") && term.datatype().equals("http://www.w3.org/2001/XMLSchema#double"))
                    lexical = Double.toString(Double.parseDouble(lexical));
                result.put(entry.getKey(), new GraphAccess.Term(term.kind(), lexical, term.datatype(), term.language()));
            }
            normalized.add(Map.copyOf(result));
        }
        return normalized;
    }
    private static Map<Map<String, GraphAccess.Term>, Long> bag(List<Map<String, GraphAccess.Term>> rows) {
        Map<Map<String, GraphAccess.Term>, Long> counts = new HashMap<>();
        rows.forEach(row -> counts.merge(row, 1L, Long::sum)); return counts;
    }
}
