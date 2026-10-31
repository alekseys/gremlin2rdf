package io.github.gremlin2rdf;

import java.math.BigInteger;
import java.util.*;

/** Independent, bounded test oracle over canonical source records; not Cosmos compatibility evidence. */
public final class Reference {
    private final List<GraphRecord> records;
    private final RdfMapper mapper;
    private record Traverser(Object value, Map<String, Object> aliases) {}
    public Reference(List<GraphRecord> records, Mapping mapping) {
        if (records.size() > 10000) throw new IllegalArgumentException("Reference oracle is bounded to semantic fixtures");
        this.records = List.copyOf(records); mapper = new RdfMapper(mapping);
    }
    public List<Map<String, GraphAccess.Term>> execute(String text) {
        var steps = Gremlin.parse(text).steps();
        List<Traverser> traversers = records.stream().filter(r -> r.kind().name().equals(steps.getFirst().name().equals("V") ? "vertex" : "edge"))
            .filter(r -> matchesIdentity(r, steps.getFirst().arguments())).map(r -> new Traverser(r, Map.of())).toList();
        for (int at = 1; at < steps.size(); at++) {
            var step = steps.get(at); List<Traverser> next = new ArrayList<>();
            switch (step.name()) {
                case "hasLabel" -> {
                    String label = string(step.arguments().getFirst());
                    traversers = traversers.stream().filter(t -> ((GraphRecord) t.value()).label().equals(label)).toList();
                }
                case "hasId" -> traversers = traversers.stream().filter(t -> matchesIdentity((GraphRecord) t.value(), step.arguments())).toList();
                case "has" -> {
                    String key = string(step.arguments().getFirst());
                    traversers = traversers.stream().filter(t -> ((GraphRecord) t.value()).properties().stream()
                        .anyMatch(p -> p.key().equals(key) && (step.arguments().size() == 1 || predicate(p.value(), step.arguments().getLast())))).toList();
                }
                case "out", "in", "both", "outE", "inE", "bothE" -> {
                    for (var t : traversers) {
                        var vertex = (GraphRecord) t.value();
                        for (var edge : records) {
                            if (edge.kind() != GraphRecord.Kind.edge || !step.arguments().isEmpty()
                                && step.arguments().stream().noneMatch(a -> string(a).equals(edge.label()))) continue;
                            if (!step.name().startsWith("in") && edge.source().equals(vertex.identity()))
                                next.add(new Traverser(step.name().endsWith("E") ? edge : vertex(edge.destination()), t.aliases()));
                            if (!step.name().startsWith("out") && edge.destination().equals(vertex.identity()))
                                next.add(new Traverser(step.name().endsWith("E") ? edge : vertex(edge.source()), t.aliases()));
                        }
                    }
                    traversers = List.copyOf(next);
                }
                case "outV", "inV", "bothV" -> {
                    for (var t : traversers) {
                        var edge = (GraphRecord) t.value();
                        if (!step.name().startsWith("in")) next.add(new Traverser(vertex(edge.source()), t.aliases()));
                        if (!step.name().startsWith("out")) next.add(new Traverser(vertex(edge.destination()), t.aliases()));
                    }
                    traversers = List.copyOf(next);
                }
                case "values" -> {
                    String key = string(step.arguments().getFirst());
                    for (var t : traversers) for (var p : ((GraphRecord) t.value()).properties())
                        if (p.key().equals(key)) next.add(new Traverser(p.value(), t.aliases()));
                    traversers = List.copyOf(next);
                }
                case "id", "label" -> traversers = traversers.stream().map(t -> new Traverser(step.name().equals("id")
                    ? ((GraphRecord) t.value()).identity().id() : GraphRecord.Value.string(((GraphRecord) t.value()).label()), t.aliases())).toList();
                case "as" -> {
                    String alias = string(step.arguments().getFirst());
                    traversers = traversers.stream().map(t -> { var aliases = new HashMap<>(t.aliases()); aliases.put(alias, t.value());
                        return new Traverser(t.value(), Map.copyOf(aliases)); }).toList();
                }
                case "select" -> {
                    for (var t : traversers) {
                        if (step.arguments().size() == 1) next.add(new Traverser(t.aliases().get(string(step.arguments().getFirst())), t.aliases()));
                        else {
                            Map<String, Object> projection = new LinkedHashMap<>();
                            step.arguments().forEach(a -> projection.put(string(a), t.aliases().get(string(a))));
                            next.add(new Traverser(Map.copyOf(projection), t.aliases()));
                        }
                    }
                    traversers = List.copyOf(next);
                }
                case "project" -> {
                    List<String> keys = step.arguments().stream().map(Reference::string).toList();
                    List<String> selectors = new ArrayList<>();
                    for (int i = 0; i < keys.size(); i++) selectors.add(((Gremlin.Call) steps.get(++at).arguments().getFirst()).name());
                    for (var t : traversers) {
                        var record = (GraphRecord) t.value(); Map<String, Object> projection = new LinkedHashMap<>();
                        for (int i = 0; i < keys.size(); i++) projection.put(keys.get(i),
                            selectors.get(i).equals("id") ? record.identity().id() : GraphRecord.Value.string(record.label()));
                        next.add(new Traverser(Map.copyOf(projection), t.aliases()));
                    }
                    traversers = List.copyOf(next);
                }
                case "dedup" -> {
                    Set<Object> seen = new HashSet<>();
                    traversers = traversers.stream().filter(t -> seen.add(t.value())).toList();
                }
                case "count" -> traversers = List.of(new Traverser(GraphRecord.Value.integer(traversers.size()), Map.of()));
                case "order" -> traversers = traversers.stream().sorted((a, b) -> compare((GraphRecord.Value) a.value(), (GraphRecord.Value) b.value())).toList();
                case "by" -> {
                    if (((Gremlin.Binding) step.arguments().getFirst()).name().endsWith("desc")) {
                        var reversed = new ArrayList<>(traversers); Collections.reverse(reversed); traversers = List.copyOf(reversed);
                    }
                }
                case "limit", "range" -> {
                    int low = step.name().equals("limit") ? 0 : Integer.parseInt(Gremlin.value(step.arguments().getFirst(), Map.of()).lexical());
                    int high = Integer.parseInt(Gremlin.value(step.arguments().getLast(), Map.of()).lexical());
                    traversers = traversers.subList(Math.min(low, traversers.size()), Math.min(high, traversers.size()));
                }
                default -> throw new Gremlin.Unsupported("ORACLE_UNSUPPORTED", "Reference does not support requested step");
            }
        }
        List<Map<String, GraphAccess.Term>> output = new ArrayList<>();
        for (var t : traversers) {
            if (t.value() instanceof Map<?, ?> projection) {
                Map<String, GraphAccess.Term> row = new TreeMap<>();
                projection.forEach((k, v) -> row.put((String) k, term(v))); output.add(Map.copyOf(row));
            } else output.add(Map.of("value", term(t.value())));
        }
        return List.copyOf(output);
    }
    private GraphRecord vertex(GraphRecord.Identity id) {
        return records.stream().filter(r -> r.kind() == GraphRecord.Kind.vertex && r.identity().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unresolved reference endpoint"));
    }
    private static String string(Gremlin.Argument argument) { return Gremlin.text(argument, Map.of()); }
    private static boolean matchesIdentity(GraphRecord record, List<Gremlin.Argument> args) {
        if (args.isEmpty()) return true;
        var identity = (Gremlin.Sequence) args.getFirst();
        return record.identity().partition().equals(Gremlin.value(identity.values().get(0), Map.of()))
            && record.identity().id().equals(Gremlin.value(identity.values().get(1), Map.of()));
    }
    private static int compare(GraphRecord.Value a, GraphRecord.Value b) {
        if (a.type() != b.type()) throw new Gremlin.Unsupported("ORACLE_NUMERIC_TYPE", "Cross-type comparison is not proven");
        return a.type() == GraphRecord.ValueType.integer ? new BigInteger(a.lexical()).compareTo(new BigInteger(b.lexical()))
            : a.lexical().compareTo(b.lexical());
    }
    private static boolean predicate(GraphRecord.Value actual, Gremlin.Argument arg) {
        String comparison = "eq";
        if (arg instanceof Gremlin.Call call) {
            comparison = call.name().replace("P.", ""); arg = call.arguments().getFirst();
        }
        var expected = Gremlin.value(arg, Map.of());
        if (comparison.equals("eq")) return actual.equals(expected);
        int order = compare(actual, expected);
        return switch (comparison) { case "neq" -> order != 0; case "gt" -> order > 0; case "gte" -> order >= 0;
            case "lt" -> order < 0; case "lte" -> order <= 0; default -> throw new IllegalArgumentException("Unknown reference predicate"); };
    }
    private GraphAccess.Term term(Object value) {
        if (value instanceof GraphRecord record) return new GraphAccess.Term("iri", mapper.entity(record), null, null);
        var typed = (GraphRecord.Value) value;
        String type = switch (typed.type()) { case string -> "string"; case booleanValue -> "boolean";
            case integer -> "integer"; case floatValue -> "float"; case doubleValue -> "double"; };
        return new GraphAccess.Term("literal", typed.lexical(), "http://www.w3.org/2001/XMLSchema#" + type, "");
    }
}
