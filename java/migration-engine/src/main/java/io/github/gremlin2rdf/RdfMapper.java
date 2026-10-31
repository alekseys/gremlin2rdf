package io.github.gremlin2rdf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.sparql.expr.NodeValue;

public final class RdfMapper {
    public static final String CANONICAL = "urn:gremlin2rdf:canonical:v1:";
    private final Mapping mapping;
    public record Mapped(Model canonical, Model derived) implements AutoCloseable {
        public void close() { canonical.close(); derived.close(); }
    }
    public RdfMapper(Mapping mapping) { this.mapping = mapping; }
    public String vertex(GraphRecord.Identity identity) {
        return mapping.baseIri() + "v1:vertex:" + Json.hash(List.of("vertex-v1", identity));
    }
    public String entity(GraphRecord record) {
        return record.kind() == GraphRecord.Kind.vertex ? vertex(record.identity())
            : mapping.baseIri() + "v1:edge:" + Json.hash(List.of("edge-v1", record.edgeScope(), record.identity(),
                record.edgeScope() == GraphRecord.EdgeScope.sourceVertex ? record.source() : record.identity().partition()));
    }
    public String domain(String category, String label) {
        return mapping.vocabulary() + category + ":" + Json.hash(label);
    }
    public static org.apache.jena.rdf.model.Property predicate(Model model, String name) {
        return model.createProperty(CANONICAL + name);
    }
    public static Literal literal(Model model, GraphRecord.Value value) {
        var datatype = switch (value.type()) {
            case string -> XSDDatatype.XSDstring;
            case booleanValue -> XSDDatatype.XSDboolean;
            case integer -> XSDDatatype.XSDinteger;
            case floatValue -> XSDDatatype.XSDfloat;
            case doubleValue -> XSDDatatype.XSDdouble;
        };
        return switch (value.type()) {
            case floatValue -> model.asRDFNode(NodeValue.makeFloat(Float.parseFloat(value.lexical())).asNode()).asLiteral();
            case doubleValue -> model.asRDFNode(NodeValue.makeDouble(Double.parseDouble(value.lexical())).asNode()).asLiteral();
            default -> model.createTypedLiteral(value.lexical(), datatype);
        };
    }
    private static void typed(Model model, Resource resource, String field, GraphRecord.Value value) {
        resource.addProperty(predicate(model, field), literal(model, value));
        resource.addProperty(predicate(model, field + "Type"), value.type().name());
        resource.addProperty(predicate(model, field + "Lexical"), value.lexical());
    }
    public Mapped map(GraphRecord record) {
        if (mapping.fidelity().equals("strict") && (!record.complete() || !mapping.exclusions().isEmpty()))
            throw new IllegalArgumentException("Strict mapping cannot discard required fidelity");
        Model model = ModelFactory.createDefaultModel(), derived = ModelFactory.createDefaultModel();
        Resource entity = model.createResource(entity(record));
        entity.addProperty(RDF.type, model.createResource(CANONICAL + (record.kind() == GraphRecord.Kind.vertex ? "Vertex" : "Edge")));
        entity.addProperty(predicate(model, "label"), record.label());
        entity.addProperty(predicate(model, "sourceScope"), record.identity().scope());
        typed(model, entity, "id", record.identity().id());
        typed(model, entity, "partition", record.identity().partition());
        entity.addProperty(predicate(model, "mappingHash"), mapping.hash());
        entity.addProperty(predicate(model, "fidelity"), record.complete() ? "complete" : "reduced");
        if (mapping.provenance()) entity.addProperty(predicate(model, "provenance"), Json.text(record.provenance()));
        entity.addProperty(predicate(model, "metadata"), Json.text(record.metadata()));
        // Keeping the normalized record permits typed reconstruction without guessing source lexical forms.
        entity.addProperty(predicate(model, "record"), Json.text(record));
        if (record.kind() == GraphRecord.Kind.vertex) {
            entity.addProperty(RDF.type, model.createResource(mapping.labels().getOrDefault(record.label(), domain("label", record.label()))));
        } else {
            entity.addProperty(predicate(model, "source"), model.createResource(vertex(record.source())));
            entity.addProperty(predicate(model, "destination"), model.createResource(vertex(record.destination())));
            entity.addProperty(predicate(model, "edgeScope"), record.edgeScope().name());
            entity.addProperty(predicate(model, "domainPredicate"),
                model.createResource(mapping.edges().getOrDefault(record.label(), domain("edge", record.label()))));
            if (mapping.derivedRelationships())
                derived.add(derived.createResource(vertex(record.source())),
                    derived.createProperty(mapping.edges().getOrDefault(record.label(), domain("edge", record.label()))),
                    derived.createResource(vertex(record.destination())));
        }
        var occurrences = new ArrayList<>(record.properties());
        occurrences.sort(Comparator.comparing(Json::text));
        Map<String, Integer> duplicates = new HashMap<>();
        for (var property : occurrences) {
            if (mapping.exclusions().contains(property.key())) continue;
            String identity = property.id() == null ? Json.hash(property) : Json.hash(List.of(property.key(), property.id()));
            int occurrence = duplicates.merge(identity, 1, Integer::sum) - 1;
            if (property.id() != null && occurrence != 0) throw new IllegalArgumentException("Duplicate observed property ID");
            Resource p = model.createResource(mapping.baseIri() + "v1:property:" + Json.hash(List.of(entity.getURI(), identity, occurrence)));
            entity.addProperty(predicate(model, "property"), p);
            p.addProperty(RDF.type, model.createResource(CANONICAL + "Property"));
            p.addProperty(predicate(model, "owner"), entity);
            p.addProperty(predicate(model, "key"), property.key());
            p.addLiteral(predicate(model, "value"), literal(model, property.value()));
            p.addProperty(predicate(model, "valueType"), property.value().type().name());
            p.addProperty(predicate(model, "valueLexical"), property.value().lexical());
            p.addProperty(predicate(model, "metaProperties"), Json.text(property.metaProperties()));
            if (property.id() != null) typed(model, p, "id", property.id());
            p.addProperty(predicate(model, "domainPredicate"),
                model.createResource(mapping.properties().getOrDefault(property.key(), domain("property", property.key()))));
            int metaIndex = 0;
            for (var meta : new java.util.TreeMap<>(property.metaProperties()).entrySet()) {
                Resource m = model.createResource(p.getURI() + ":meta:" + metaIndex++);
                p.addProperty(predicate(model, "metaProperty"), m);
                m.addProperty(RDF.type, model.createResource(CANONICAL + "MetaProperty"));
                m.addProperty(predicate(model, "owner"), p);
                m.addProperty(predicate(model, "key"), meta.getKey());
                m.addLiteral(predicate(model, "value"), literal(model, meta.getValue()));
            }
        }
        return new Mapped(model, derived);
    }
}
