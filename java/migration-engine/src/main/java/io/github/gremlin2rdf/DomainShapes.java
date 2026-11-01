package io.github.gremlin2rdf;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

final class DomainShapes {
    private static final String SH = "http://www.w3.org/ns/shacl#";
    private DomainShapes() {}
    static void add(Model model, Mapping mapping) {
        var mapper = new RdfMapper(mapping);
        for (var entry : mapping.valueRules().entrySet()) {
            String predicate = mapping.properties().getOrDefault(entry.getKey(), mapper.domain("property", entry.getKey()));
            Resource shape = model.createResource("urn:gremlin2rdf:shape:value:" + Json.hash(entry.getKey()));
            shape.addProperty(RDF.type, model.createResource(SH + "NodeShape"));
            shape.addProperty(model.createProperty(SH + "targetClass"), model.createResource(RdfMapper.CANONICAL + "Property"));
            Resource unmatched = model.createResource(), matchedKey = model.createResource(), keyConstraint = model.createResource();
            unmatched.addProperty(model.createProperty(SH + "not"), matchedKey);
            matchedKey.addProperty(model.createProperty(SH + "property"), keyConstraint);
            keyConstraint.addProperty(model.createProperty(SH + "path"), model.createResource(RdfMapper.CANONICAL + "key"));
            keyConstraint.addProperty(model.createProperty(SH + "hasValue"), entry.getKey());
            Resource projected = model.createResource();
            shape.addProperty(model.createProperty(SH + "or"), model.createList(new RDFNode[] { unmatched, projected }));
            Resource constraint = model.createResource();
            projected.addProperty(model.createProperty(SH + "property"), constraint);
            constraint.addProperty(model.createProperty(SH + "path"), model.createResource(predicate));
            constraint.addLiteral(model.createProperty(SH + "minCount"), 1);
            constraint.addLiteral(model.createProperty(SH + "maxCount"), 1);
            var rule = entry.getValue();
            constraint.addProperty(model.createProperty(SH + (rule.representation().equals("resource") ? "nodeKind" : "datatype")),
                model.createResource(rule.representation().equals("resource") ? SH + "IRI" : rule.datatype()));
        }
        for (var rule : mapping.domainRules()) {
            if (!mapping.valueRules().containsKey(rule.property()))
                throw new IllegalArgumentException("Domain cardinality requires explicit property value projection");
            Resource shape = model.createResource("urn:gremlin2rdf:shape:domain:" + Json.hash(rule));
            shape.addProperty(RDF.type, model.createResource(SH + "NodeShape"));
            shape.addProperty(model.createProperty(SH + "targetClass"),
                model.createResource(mapping.labels().getOrDefault(rule.label(), mapper.domain("label", rule.label()))));
            Resource constraint = model.createResource();
            shape.addProperty(model.createProperty(SH + "property"), constraint);
            constraint.addProperty(model.createProperty(SH + "path"), model.createResource(RdfMapper.CANONICAL + "property"));
            Resource occurrence = model.createResource(), key = model.createResource();
            constraint.addProperty(model.createProperty(SH + "qualifiedValueShape"), occurrence);
            occurrence.addProperty(model.createProperty(SH + "property"), key);
            key.addProperty(model.createProperty(SH + "path"), model.createResource(RdfMapper.CANONICAL + "key"));
            key.addProperty(model.createProperty(SH + "hasValue"), rule.property());
            constraint.addLiteral(model.createProperty(SH + "qualifiedMinCount"), rule.minimumOccurrences());
            if (rule.maximumOccurrences() != null)
                constraint.addLiteral(model.createProperty(SH + "qualifiedMaxCount"), rule.maximumOccurrences());
        }
    }
}
