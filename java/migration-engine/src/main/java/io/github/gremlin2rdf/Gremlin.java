package io.github.gremlin2rdf;

import java.util.*;

public final class Gremlin {
    private Gremlin() {}
    public sealed interface Argument permits Literal, Binding, Sequence, Call {}
    public record Literal(GraphRecord.Value value) implements Argument {}
    public record Binding(String name) implements Argument {}
    public record Sequence(List<Argument> values) implements Argument {}
    public record Call(String name, List<Argument> arguments) implements Argument {}
    public record Query(List<Call> steps) {}
    public static final class Unsupported extends IllegalArgumentException {
        private final String code;
        public Unsupported(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
    public static Query parse(String input) {
        if (input == null || input.length() > 65536) throw new Unsupported("QUERY_SIZE", "Query exceeds parser bound");
        return new Parser(input).parse();
    }
    private static final class Parser {
        private final String input;
        private int at, depth, tokens;
        Parser(String input) { this.input = input; }
        Query parse() {
            if (!identifier().equals("g")) fail("Only the g traversal source is supported");
            List<Call> steps = new ArrayList<>();
            while (take('.')) {
                String name = identifier(); expect('(');
                steps.add(new Call(name, arguments(')')));
                if (steps.size() > 100) fail("Traversal exceeds step bound");
            }
            whitespace();
            if (at != input.length() || steps.isEmpty()) fail("Executable Groovy or trailing input is prohibited");
            return new Query(List.copyOf(steps));
        }
        List<Argument> arguments(char end) {
            if (++depth > 12) fail("Query nesting exceeds bound");
            List<Argument> args = new ArrayList<>();
            if (!take(end)) {
                do { args.add(argument()); } while (take(','));
                expect(end);
            }
            depth--;
            return List.copyOf(args);
        }
        Argument argument() {
            whitespace();
            if (++tokens > 1000 || at == input.length()) fail("Invalid or oversized query");
            char c = input.charAt(at);
            if (c == '\'' || c == '"') return new Literal(GraphRecord.Value.string(string()));
            if (take('[')) return new Sequence(arguments(']'));
            if (c == '-' || Character.isDigit(c)) {
                int start = at++;
                while (at < input.length() && (Character.isDigit(input.charAt(at)) || input.charAt(at) == '.')) at++;
                String number = input.substring(start, at);
                return new Literal(new GraphRecord.Value(number.indexOf('.') >= 0 ? GraphRecord.ValueType.doubleValue
                    : GraphRecord.ValueType.integer, number));
            }
            String name = identifier();
            if (name.equals("true") || name.equals("false"))
                return new Literal(new GraphRecord.Value(GraphRecord.ValueType.booleanValue, name));
            if (take('.')) name += "." + identifier();
            if (take('(')) return new Call(name, arguments(')'));
            return new Binding(name);
        }
        String string() {
            char quote = input.charAt(at++);
            StringBuilder value = new StringBuilder();
            while (at < input.length()) {
                char c = input.charAt(at++);
                if (c == quote) return value.toString();
                if (c == '\\') {
                    if (at == input.length()) fail("Incomplete string escape");
                    c = input.charAt(at++);
                    value.append(switch(c) {
                        case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        case '\\', '\'', '"' -> c;
                        default -> throw new Unsupported("STRING_ESCAPE", "Unsupported string escape; use a typed binding");
                    });
                } else value.append(c);
            }
            fail("Unterminated literal"); return "";
        }
        String identifier() {
            whitespace(); int start = at;
            if (at == input.length() || !(Character.isLetter(input.charAt(at)) || input.charAt(at) == '_')) fail("Identifier required");
            at++;
            while (at < input.length() && (Character.isLetterOrDigit(input.charAt(at)) || input.charAt(at) == '_')) at++;
            return input.substring(start, at);
        }
        void whitespace() { while (at < input.length() && Character.isWhitespace(input.charAt(at))) at++; }
        boolean take(char c) { whitespace(); if (at < input.length() && input.charAt(at) == c) { at++; return true; } return false; }
        void expect(char c) { if (!take(c)) fail("Expected syntax delimiter at offset " + at); }
        void fail(String message) { throw new Unsupported("UNSUPPORTED_SYNTAX", message); }
    }
    public static GraphRecord.Value value(Argument arg, Map<String, GraphRecord.Value> bindings) {
        if (arg instanceof Literal literal) return literal.value();
        if (arg instanceof Binding binding && bindings.containsKey(binding.name())) return bindings.get(binding.name());
        throw new Unsupported("UNRESOLVED_BINDING", "Supply explicit typed bindings; dynamic assembly cannot be evaluated");
    }
    public static String text(Argument arg, Map<String, GraphRecord.Value> bindings) {
        var value = value(arg, bindings);
        if (value.type() != GraphRecord.ValueType.string) throw new Unsupported("STRING_REQUIRED", "String argument required");
        return value.lexical();
    }
}
