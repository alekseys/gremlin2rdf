using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Gremlin2Age;

public sealed record Step(string Name, List<JsonElement> Arguments);
public sealed record Translation(string Cypher, string Projection, string? Property, bool Count,
    bool Deduplicate, int? Limit, List<Step> Ast);

public static class GremlinCompiler
{
    public static Translation Compile(string query)
    {
        if (query.Length > 16_384 || !query.StartsWith("g.", StringComparison.Ordinal))
            throw new Blocker("GREMLIN_ROOT", "A bounded literal g.V()/g.E() read traversal is required.");
        var steps = new List<Step>();
        var cursor = 2;
        while (cursor < query.Length)
        {
            var start = cursor;
            while (cursor < query.Length && char.IsAsciiLetter(query[cursor])) cursor++;
            var name = query[start..cursor];
            if (name.Length == 0 || cursor >= query.Length || query[cursor++] != '(')
                throw new Blocker("GREMLIN_SYNTAX", "Only literal method-call traversals are supported.");
            var arguments = new List<JsonElement>();
            while (cursor < query.Length && query[cursor] != ')')
            {
                while (cursor < query.Length && char.IsWhiteSpace(query[cursor])) cursor++;
                var argumentStart = cursor;
                if (cursor < query.Length && query[cursor] is '\'' or '"')
                {
                    var quote = query[cursor++];
                    var value = new StringBuilder();
                    var closed = false;
                    while (cursor < query.Length)
                    {
                        var c = query[cursor++];
                        if (c == quote) { closed = true; break; }
                        if (c == '\\')
                        {
                            if (cursor >= query.Length || query[cursor] is not ('\\' or '\'' or '"'))
                                throw new Blocker("GREMLIN_ESCAPE", "Only quoted-string and backslash escapes are supported.");
                            c = query[cursor++];
                        }
                        value.Append(c);
                    }
                    if (!closed) throw new Blocker("GREMLIN_STRING", "Unterminated literal.");
                    arguments.Add(JsonSerializer.SerializeToElement(value.ToString()));
                }
                else
                {
                    while (cursor < query.Length && char.IsAsciiDigit(query[cursor])) cursor++;
                    if (cursor == argumentStart || !int.TryParse(query[argumentStart..cursor], out var integer))
                        throw new Blocker("GREMLIN_DYNAMIC", "Bindings, lambdas, mutation and dynamic expressions require manual migration.");
                    arguments.Add(JsonSerializer.SerializeToElement(integer));
                }
                while (cursor < query.Length && char.IsWhiteSpace(query[cursor])) cursor++;
                if (cursor < query.Length && query[cursor] == ',')
                {
                    cursor++;
                    if (cursor >= query.Length || query[cursor] == ')') throw new Blocker("GREMLIN_SYNTAX", "Trailing comma is not supported.");
                    continue;
                }
                if (cursor >= query.Length || query[cursor] != ')')
                    throw new Blocker("GREMLIN_SYNTAX", "Malformed traversal argument list.");
            }
            if (cursor >= query.Length) throw new Blocker("GREMLIN_SYNTAX", "Unterminated step.");
            cursor++;
            steps.Add(new(name, arguments));
            if (steps.Count > 32) throw new Blocker("GREMLIN_BOUND", "At most 32 read steps are supported.");
            if (cursor == query.Length) break;
            if (query[cursor++] != '.' || cursor == query.Length) throw new Blocker("GREMLIN_SYNTAX", "Unexpected trailing expression.");
        }
        if (steps.Count == 0 || steps[0].Name is not ("V" or "E") || steps[0].Arguments.Count != 0)
            throw new Blocker("GREMLIN_ID", "Root IDs require typed GraphAccess.ReadIdentity; unqualified IDs are ambiguous.");
        var kind = steps[0].Name == "V" ? "vertex" : "edge";
        var variable = kind == "vertex" ? "v0" : "e0";
        var text = new StringBuilder(kind == "vertex"
            ? "MATCH (v0:G2RVertex) WHERE v0.runId = $run"
            : "MATCH ()-[e0:G2REdge]->() WHERE e0.runId = $run");
        string projection = "record"; string? property = null;
        bool count = false, dedup = false; int? limit = null; var sequence = 0;
        foreach (var step in steps.Skip(1))
        {
            if (count || limit != null || (dedup && step.Name is not ("count" or "limit")))
                throw new Blocker("GREMLIN_ORDER", "count/limit must be final; dedup can only precede count/limit.");
            switch (step.Name)
            {
                case "hasLabel":
                    if (projection != "record") throw new Blocker("GREMLIN_TYPE", "hasLabel requires a graph element.");
                    RequireStrings(step, 1);
                    text.Append($" AND {variable}.sourceLabel = {Literal(step.Arguments[0].GetString()!)}");
                    break;
                case "out":
                case "in":
                case "outE":
                case "inE":
                    if (kind != "vertex" || projection != "record") throw new Blocker("GREMLIN_TYPE", "Traversal requires a vertex.");
                    if (step.Arguments.Count > 1) throw new Blocker("GREMLIN_LABELS", "Only one literal edge label per step is supported.");
                    if (step.Arguments.Count == 1) RequireStrings(step, 1);
                    var previous = variable;
                    sequence++;
                    var edge = $"e{sequence}"; var vertex = $"v{sequence}";
                    // Separate MATCH clauses preserve independent hop multiplicity; AGE execution still requires verification.
                    text.Append($" WITH {variable} MATCH ");
                    text.Append(step.Name.StartsWith("out", StringComparison.Ordinal)
                        ? $"({previous})-[{edge}:G2REdge]->({vertex}:G2RVertex)"
                        : $"({previous})<-[{edge}:G2REdge]-({vertex}:G2RVertex)");
                    text.Append($" WHERE {edge}.runId = $run AND {vertex}.runId = $run");
                    if (step.Arguments.Count == 1) text.Append($" AND {edge}.sourceLabel = {Literal(step.Arguments[0].GetString()!)}");
                    variable = step.Name.EndsWith('E') ? edge : vertex;
                    kind = step.Name.EndsWith('E') ? "edge" : "vertex";
                    break;
                case "inV":
                case "outV":
                    if (kind != "edge" || projection != "record" || step.Arguments.Count != 0) throw new Blocker("GREMLIN_TYPE", "Endpoint traversal requires an edge.");
                    sequence++; var endpoint = $"v{sequence}";
                    text.Append($" WITH {variable} MATCH ");
                    text.Append(step.Name == "inV" ? $"()-[{variable}]->({endpoint}:G2RVertex)" : $"({endpoint}:G2RVertex)-[{variable}]->()");
                    text.Append($" WHERE {endpoint}.runId = $run");
                    variable = endpoint; kind = "vertex";
                    break;
                case "id":
                case "label":
                    if (step.Arguments.Count != 0 || projection != "record") throw new Blocker("GREMLIN_PROJECTION", "Single projection required.");
                    projection = step.Name; break;
                case "values":
                    RequireStrings(step, 1);
                    if (projection != "record") throw new Blocker("GREMLIN_PROJECTION", "Single projection required.");
                    projection = "values"; property = step.Arguments[0].GetString(); break;
                case "dedup":
                    if (step.Arguments.Count != 0 || dedup) throw new Blocker("GREMLIN_DEDUP", "Only one dedup step supported.");
                    // Post-projection dedup is performed on typed values, not native generated AGE IDs.
                    if (steps.Last() != step && steps.Last().Name is not ("count" or "limit"))
                        throw new Blocker("GREMLIN_DEDUP", "dedup is supported only immediately before a terminal count/limit or at the end.");
                    dedup = true; break;
                case "count":
                    if (step.Arguments.Count != 0) throw new Blocker("GREMLIN_COUNT", "count takes no arguments.");
                    count = true; break;
                case "limit":
                    if (step.Arguments.Count != 1 || !step.Arguments[0].TryGetInt32(out var bound) || bound is < 0 or > 10000)
                        throw new Blocker("GREMLIN_LIMIT", "limit requires an integer from zero to 10000.");
                    limit = bound; break;
                default:
                    throw new Blocker("GREMLIN_UNSUPPORTED", "Unsupported traversal step; review the local AST and supported read subset. No approximate translation was produced.");
            }
        }
        text.Append($" RETURN {variable}.externalId");
        return new(text.ToString(), projection, property, count, dedup, limit, steps);
    }
    private static void RequireStrings(Step step, int count)
    {
        if (step.Arguments.Count != count || step.Arguments.Any(a => a.ValueKind != JsonValueKind.String))
            throw new Blocker("GREMLIN_LITERAL", "Literal string arguments required.");
    }
    public static string Literal(string text)
    {
        if (text.Length > 4096 || text.Any(char.IsControl)) throw new Blocker("CYPHER_LITERAL", "Bounded control-free string required.");
        return "'" + text.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("'", "\\'", StringComparison.Ordinal) + "'";
    }
}

public sealed record AgValue(string Type, string Lexical, JsonElement? Structure = null);
public static class Agtype
{
    public static AgValue Decode(string text)
    {
        if (text.Length > 1_048_576) throw new Blocker("AGTYPE_BOUND", "agtype response exceeds bound.");
        string? annotation = null;
        foreach (var suffix in new[] { "::vertex", "::edge", "::path", "::numeric" })
            if (text.EndsWith(suffix, StringComparison.Ordinal)) { annotation = suffix[2..]; text = text[..^suffix.Length]; break; }
        using var document = JsonDocument.Parse(text, new JsonDocumentOptions { MaxDepth = 48 });
        var value = document.RootElement.Clone();
        return value.ValueKind switch
        {
            JsonValueKind.String when annotation == null => new("string", value.GetString()!),
            JsonValueKind.Number when annotation is null or "numeric" => new(annotation == "numeric" ? "numeric" :
                Regex.IsMatch(value.GetRawText(), "^-?[0-9]+$") ? "integer" : "double", value.GetRawText()),
            JsonValueKind.True or JsonValueKind.False when annotation == null => new("boolean", value.GetRawText()),
            JsonValueKind.Null when annotation == null => new("null", "null"),
            JsonValueKind.Object or JsonValueKind.Array => new(annotation ?? "structure", value.GetRawText(), value),
            _ => throw new Blocker("AGTYPE_TYPE", "Unrecognized agtype representation.")
        };
    }
}
