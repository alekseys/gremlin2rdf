package io.github.gremlin2rdf;

import java.util.*;
import org.apache.jena.graph.*;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.*;
import org.apache.jena.sparql.expr.aggregate.*;
import org.apache.jena.sparql.syntax.*;
import org.apache.jena.vocabulary.RDF;

public final class Translator {
    public record Translation(int schemaVersion, String fingerprint, String sparql, String shape,
                              String comparison, List<String> resultVariables) {}
    private final Map<String, GraphRecord.Value> bindings;
    private final String graph;
    private int next;
    private ElementGroup group = new ElementGroup();
    private Var current;
    private String kind;
    private boolean distinct, ordered;
    private boolean orderBySet;
    private final Map<String, Var> aliases = new LinkedHashMap<>();
    private final Map<String, String> aliasKinds = new HashMap<>();
    private final Query query = QueryFactory.make();
    private final List<String> columns = new ArrayList<>();

    public Translator(String graph, Map<String, GraphRecord.Value> bindings) {
        Mapping.iri(graph); this.graph = graph; this.bindings = Map.copyOf(bindings);
    }
    private Var variable() { return Var.alloc("v" + next++); }
    private Node iri(String name) { return NodeFactory.createURI(RdfMapper.CANONICAL + name); }
    private Node literal(GraphRecord.Value value) {
        var model = ModelFactory.createDefaultModel();
        try { return RdfMapper.literal(model, value).asNode(); }
        finally { model.close(); }
    }
    private void triple(ElementGroup target, Node s, Node p, Node o) {
        var block = new ElementTriplesBlock(); block.addTriple(Triple.create(s, p, o)); target.addElement(block);
    }
    private void triple(Node s, String p, Node o) { triple(group, s, iri(p), o); }
    private void zero(Gremlin.Call step) { if (!step.arguments().isEmpty()) unsupported("Step does not accept arguments"); }
    private void entity() { if (!List.of("vertex", "edge").contains(kind)) unsupported("Step requires graph entities"); }
    private void unsupported(String message) { throw new Gremlin.Unsupported("UNPROVEN_COMBINATION", message); }
    private void identity(List<Gremlin.Argument> args) {
        if (args.isEmpty()) return;
        if (args.size() != 1 || !(args.getFirst() instanceof Gremlin.Sequence identity) || identity.values().size() != 2)
            throw new Gremlin.Unsupported("PARTITION_IDENTITY", "Use a typed [partition,id] selection; bare IDs may be ambiguous");
        triple(current, "partition", literal(Gremlin.value(identity.values().get(0), bindings)));
        triple(current, "id", literal(Gremlin.value(identity.values().get(1), bindings)));
    }
    private void propertyFilter(String key, Gremlin.Argument predicate) {
        var inner = new ElementGroup(); Var occurrence = variable(), value = variable();
        triple(inner, current, iri("property"), occurrence);
        triple(inner, occurrence, iri("key"), literal(GraphRecord.Value.string(key)));
        triple(inner, occurrence, iri("value"), value);
        if (predicate != null) {
            String comparison = "eq"; Gremlin.Argument operand = predicate;
            if (predicate instanceof Gremlin.Call call) {
                if (call.arguments().size() != 1) unsupported("Predicate needs exactly one typed operand");
                comparison = call.name().startsWith("P.") ? call.name().substring(2) : call.name();
                operand = call.arguments().getFirst();
            }
            var expected = Gremlin.value(operand, bindings);
            if (!comparison.equals("eq") && expected.type() != GraphRecord.ValueType.integer)
                throw new Gremlin.Unsupported("NUMERIC_SEMANTICS", "Ordered predicates currently require exact integer operands");
            Expr left = new ExprVar(value), right = NodeValue.makeNode(literal(expected));
            Expr expression = switch (comparison) {
                case "eq" -> new E_Equals(left, right); case "neq" -> new E_NotEquals(left, right);
                case "gt" -> new E_GreaterThan(left, right); case "gte" -> new E_GreaterThanOrEqual(left, right);
                case "lt" -> new E_LessThan(left, right); case "lte" -> new E_LessThanOrEqual(left, right);
                default -> throw new Gremlin.Unsupported("CUSTOM_PREDICATE", "Custom predicates are unsupported");
            };
            inner.addElement(new ElementFilter(expression));
        }
        group.addElement(new ElementFilter(new E_Exists(inner)));
    }
    private void navigation(Gremlin.Call step) {
        String name = step.name(); boolean entering = name.endsWith("V");
        if (entering ? !"edge".equals(kind) : !"vertex".equals(kind)) unsupported("Invalid navigation entity kind");
        if (entering) zero(step);
        List<String> labels = step.arguments().stream().map(a -> Gremlin.text(a, bindings)).toList();
        boolean outgoing = name.startsWith("out"), incoming = name.startsWith("in");
        Var result = variable(), edge = entering ? current : name.endsWith("E") ? result : variable();
        ElementUnion union = new ElementUnion();
        for (boolean direction : outgoing ? List.of(true) : incoming ? List.of(false) : List.of(true, false)) {
            ElementGroup branch = new ElementGroup();
            if (entering) triple(branch, edge, iri(direction ? "source" : "destination"), result);
            else {
                triple(branch, edge, iri(direction ? "source" : "destination"), current);
                if (!name.endsWith("E")) triple(branch, edge, iri(direction ? "destination" : "source"), result);
                if (!labels.isEmpty()) {
                    Var label = variable(); triple(branch, edge, iri("label"), label);
                    Expr expression = null;
                    for (String requested : labels) {
                        Expr match = new E_Equals(new ExprVar(label), NodeValue.makeString(requested));
                        expression = expression == null ? match : new E_LogicalOr(expression, match);
                    }
                    branch.addElement(new ElementFilter(expression));
                }
            }
            union.addElement(branch);
        }
        group.addElement(union); current = result; kind = name.endsWith("E") ? "edge" : "vertex";
    }
    public Translation translate(String source) {
        var parsed = Gremlin.parse(source); var steps = parsed.steps();
        var start = steps.getFirst();
        if (!List.of("V", "E").contains(start.name())) unsupported("Traversal must start with V or E");
        current = variable(); kind = start.name().equals("V") ? "vertex" : "edge";
        triple(group, current, RDF.type.asNode(), iri(kind.equals("vertex") ? "Vertex" : "Edge")); identity(start.arguments());
        query.setQuerySelectType();
        String shape = kind;
        for (int index = 1; index < steps.size(); index++) {
            var step = steps.get(index); boolean terminal = index == steps.size() - 1;
            if (distinct && !List.of("count", "order", "by", "limit", "range").contains(step.name()))
                unsupported("Intermediate dedup followed by traversal/projection is not proven");
            switch (step.name()) {
                case "hasLabel" -> {
                    entity();
                    if (step.arguments().size() != 1) unsupported("hasLabel requires one label");
                    triple(current, "label", literal(GraphRecord.Value.string(Gremlin.text(step.arguments().getFirst(), bindings))));
                }
                case "hasId" -> {
                    entity();
                    if (step.arguments().isEmpty()) unsupported("Empty hasId is not a resolvable identity selection");
                    identity(step.arguments());
                }
                case "has" -> {
                    entity();
                    if (step.arguments().isEmpty() || step.arguments().size() > 2) unsupported("Only has(key) and has(key,predicate) are supported");
                    propertyFilter(Gremlin.text(step.arguments().getFirst(), bindings), step.arguments().size() == 2 ? step.arguments().getLast() : null);
                }
                case "out", "in", "both", "outE", "inE", "bothE", "outV", "inV", "bothV" -> navigation(step);
                case "values" -> {
                    entity();
                    if (step.arguments().size() != 1) unsupported("values currently requires one property key");
                    Var property = variable(), result = variable();
                    triple(current, "property", property);
                    triple(property, "key", literal(GraphRecord.Value.string(Gremlin.text(step.arguments().getFirst(), bindings))));
                    triple(property, "value", result); current = result; kind = "value"; shape = "value";
                }
                case "id", "label" -> {
                    entity(); zero(step); Var result = variable();
                    triple(current, step.name(), result); current = result; kind = "value"; shape = "value";
                }
                case "as" -> {
                    if (step.arguments().size() != 1) unsupported("as requires one alias");
                    String alias = Gremlin.text(step.arguments().getFirst(), bindings);
                    if (aliases.putIfAbsent(alias, current) != null) unsupported("Alias rebinding is not supported");
                    aliasKinds.put(alias, kind);
                }
                case "select" -> {
                    if (step.arguments().isEmpty()) unsupported("select requires an established alias");
                    if (step.arguments().size() > 1) {
                        if (!terminal) unsupported("Multi-alias select must be terminal");
                        for (var arg : step.arguments()) {
                            String selected = Gremlin.text(arg, bindings);
                            if (!selected.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !aliases.containsKey(selected))
                                unsupported("Projection aliases must be safe, unique and established");
                            if (columns.contains(selected)) unsupported("Duplicate projection alias");
                            query.addResultVar(selected, new ExprVar(aliases.get(selected))); columns.add(selected);
                        }
                        shape = "projection"; break;
                    }
                    String alias = Gremlin.text(step.arguments().getFirst(), bindings);
                    if (!aliases.containsKey(alias)) unsupported("Alias is not in traversal scope");
                    current = aliases.get(alias); kind = aliasKinds.get(alias); shape = kind;
                }
                case "project" -> {
                    entity();
                    if (step.arguments().isEmpty() || step.arguments().size() > 8
                        || index + step.arguments().size() != steps.size() - 1)
                        unsupported("project requires one terminal by(id())/by(label()) per key");
                    for (var arg : step.arguments()) {
                        String key = Gremlin.text(arg, bindings);
                        if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || key.matches("v[0-9]+") || columns.contains(key))
                            unsupported("Projection keys must be safe and unique");
                        var by = steps.get(++index);
                        if (!by.name().equals("by") || by.arguments().size() != 1
                            || !(by.arguments().getFirst() instanceof Gremlin.Call call)
                            || !List.of("id", "label").contains(call.name()) || !call.arguments().isEmpty())
                            unsupported("Only canonical scalar id/label projections are proven");
                        var projected = (Gremlin.Call) by.arguments().getFirst();
                        Var result = variable(); triple(current, projected.name(), result);
                        query.addResultVar(key, new ExprVar(result)); columns.add(key);
                    }
                    shape = "projection";
                }
                case "dedup" -> { zero(step); distinct = true; }
                case "count" -> {
                    zero(step); if (!terminal) unsupported("count must be terminal");
                    query.addResultVar("count", query.allocAggregate(distinct ? new AggCountVarDistinct(new ExprVar(current)) : new AggCount()));
                    columns.add("count"); shape = "count";
                }
                case "order" -> {
                    zero(step);
                    if (!kind.equals("value")) unsupported("Entity ordering requires proven unique cardinality and tie policy; project values first");
                    ordered = true; query.addOrderBy(current, Query.ORDER_ASCENDING);
                }
                case "by" -> {
                    if (!ordered || orderBySet || step.arguments().size() != 1 || !(step.arguments().getFirst() instanceof Gremlin.Binding direction)
                        || !List.of("asc", "desc", "Order.asc", "Order.desc").contains(direction.name()))
                        unsupported("Only scalar order().by(asc/desc) is supported");
                    query.getOrderBy().clear();
                    var direction = (Gremlin.Binding) step.arguments().getFirst();
                    query.addOrderBy(current, direction.name().endsWith("desc") ? Query.ORDER_DESCENDING : Query.ORDER_ASCENDING);
                    orderBySet = true;
                }
                case "limit", "range" -> {
                    if (!terminal || !ordered) unsupported("limit/range require explicit established scalar ordering and must be terminal");
                    int size = step.name().equals("limit") ? 1 : 2;
                    if (step.arguments().size() != size) unsupported("Incorrect bounded range arguments");
                    long low = size == 1 ? 0 : integer(step.arguments().getFirst()), high = integer(step.arguments().getLast());
                    if (low < 0 || high < low || high > 1000000) unsupported("Range exceeds established bound");
                    query.setOffset(low); query.setLimit(high - low);
                }
                default -> throw new Gremlin.Unsupported("UNSUPPORTED_STEP", "Unsupported Gremlin step: " + step.name());
            }
        }
        if (columns.isEmpty()) { query.addResultVar(current); columns.add(current.getVarName()); shape = kind; }
        if (!shape.equals("count")) query.setDistinct(distinct);
        query.setQueryPattern(new ElementNamedGraph(NodeFactory.createURI(graph), group));
        QueryFactory.create(query.toString());
        return new Translation(1, Json.hash(source), query.toString(), shape,
            ordered ? "sequence" : distinct ? "set" : "multiset", List.copyOf(columns));
    }
    private long integer(Gremlin.Argument arg) {
        var value = Gremlin.value(arg, bindings);
        if (value.type() != GraphRecord.ValueType.integer) unsupported("Integer range required");
        try { return Long.parseLong(value.lexical()); }
        catch (NumberFormatException e) { throw new Gremlin.Unsupported("RANGE_OVERFLOW", "Range overflows supported integer bound"); }
    }
}
