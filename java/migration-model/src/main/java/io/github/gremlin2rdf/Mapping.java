package io.github.gremlin2rdf;

import java.net.URI;
import java.util.Map;
import java.util.Set;

public record Mapping(int schemaVersion, String baseIri, String vocabulary, Map<String, String> labels,
                      Map<String, String> properties, Map<String, String> edges, Set<String> exclusions,
                      boolean derivedRelationships, boolean provenance, String fidelity,
                      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                      Map<String, ValueRule> valueRules,
                      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                      java.util.List<DomainRule> domainRules) {
    public Mapping(int schemaVersion, String baseIri, String vocabulary, Map<String, String> labels,
                   Map<String, String> properties, Map<String, String> edges, Set<String> exclusions,
                   boolean derivedRelationships, boolean provenance, String fidelity) {
        this(schemaVersion, baseIri, vocabulary, labels, properties, edges, exclusions,
            derivedRelationships, provenance, fidelity, Map.of(), java.util.List.of());
    }
    public record ValueRule(String sourceType, String representation, String datatype) {
        public ValueRule {
            if (sourceType == null || !java.util.Arrays.stream(GraphRecord.ValueType.values())
                .anyMatch(type -> type.name().equals(sourceType))
                || !Set.of("literal", "resource").contains(representation))
                throw new IllegalArgumentException("Explicit supported value rule required");
            if (representation.equals("resource")) {
                if (!sourceType.equals("string") || datatype != null)
                    throw new IllegalArgumentException("Resource projection requires string source and no datatype");
            } else {
                iri(datatype);
                if (!Set.of("string", "boolean", "integer", "decimal", "float", "double", "date", "dateTime", "anyURI")
                    .stream().anyMatch(type -> datatype.equals("http://www.w3.org/2001/XMLSchema#" + type)))
                    throw new IllegalArgumentException("Unsupported explicit domain datatype");
            }
        }
    }
    public record DomainRule(String label, String property, int minimumOccurrences, Integer maximumOccurrences) {
        public DomainRule {
            if (label == null || label.isBlank() || property == null || property.isBlank()
                || minimumOccurrences < 0 || maximumOccurrences != null && maximumOccurrences < minimumOccurrences)
                throw new IllegalArgumentException("Invalid domain occurrence constraint");
        }
    }
    public static final String CANONICAL = "urn:gremlin2rdf:canonical:v1:";
    public Mapping {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported mapping version");
        iri(baseIri); iri(vocabulary);
        labels = Map.copyOf(labels); properties = Map.copyOf(properties); edges = Map.copyOf(edges);
        exclusions = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(exclusions));
        valueRules = valueRules == null ? Map.of() : Map.copyOf(valueRules);
        domainRules = domainRules == null ? java.util.List.of() : java.util.List.copyOf(domainRules);
        if (valueRules.size() > 1000 || domainRules.size() > 1000
            || valueRules.keySet().stream().anyMatch(String::isBlank)
            || !java.util.Collections.disjoint(exclusions, valueRules.keySet()))
            throw new IllegalArgumentException("Bounded nonexcluded domain rules required");
        for (DomainRule rule : domainRules)
            if (exclusions.contains(rule.property()) || !valueRules.containsKey(rule.property()))
                throw new IllegalArgumentException("Domain constraints require a nonexcluded value projection");
        Set<String> projectedPredicates = new java.util.HashSet<>();
        for (String key : valueRules.keySet()) {
            String predicate = properties.getOrDefault(key, vocabulary + "property:" + Json.hash(key));
            if (!projectedPredicates.add(predicate))
                throw new IllegalArgumentException("Domain value rules require distinct predicates");
        }
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
