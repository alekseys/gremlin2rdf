package io.github.gremlin2rdf;

import java.net.URI;
import java.util.Map;
import java.util.Set;

public record Mapping(int schemaVersion, String baseIri, String vocabulary, Map<String, String> labels,
                      Map<String, String> properties, Map<String, String> edges, Set<String> exclusions,
                      boolean derivedRelationships, boolean provenance, String fidelity) {
    public static final String CANONICAL = "urn:gremlin2rdf:canonical:v1:";
    public Mapping {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported mapping version");
        iri(baseIri); iri(vocabulary);
        labels = Map.copyOf(labels); properties = Map.copyOf(properties); edges = Map.copyOf(edges);
        exclusions = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(exclusions));
        labels.values().forEach(Mapping::iri);
        properties.values().forEach(Mapping::iri);
        edges.values().forEach(Mapping::iri);
        if (!Set.of("strict", "reduced").contains(fidelity)) throw new IllegalArgumentException("Unknown fidelity");
        if (labels.values().stream().anyMatch(x -> x.startsWith(CANONICAL))
            || properties.values().stream().anyMatch(x -> x.startsWith(CANONICAL))
            || edges.values().stream().anyMatch(x -> x.startsWith(CANONICAL)))
            throw new IllegalArgumentException("Reserved canonical vocabulary collision");
        if (fidelity.equals("strict") && !exclusions.isEmpty()) throw new IllegalArgumentException("Strict mapping cannot exclude properties");
    }
    public static void iri(String value) {
        if (value == null || !URI.create(value).isAbsolute() || value.chars().anyMatch(c -> c <= 32 || c == '<' || c == '>'))
            throw new IllegalArgumentException("Absolute IRI required");
    }
    public static Mapping standard() {
        return new Mapping(1, "urn:gremlin2rdf:entity:", "urn:gremlin2rdf:domain:",
            Map.of(), Map.of(), Map.of(), Set.of(), true, true, "strict");
    }
    public String hash() { return Json.hash(this); }
}
