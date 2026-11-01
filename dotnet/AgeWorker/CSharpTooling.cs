using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

namespace Gremlin2Age;

public static class CSharpTooling
{
    public static string ResourceHash(string source, string output) => Codec.Hash(Codec.Text(new { sourceHash = Codec.Hash(source), output }));
    public static string Apply(string source)
    {
        RewritePlan(source);
        var tree = CSharpSyntaxTree.ParseText(source); var root = tree.GetRoot();
        bool Declared(string name, string type) => root.DescendantNodes().OfType<VariableDeclarationSyntax>()
            .Any(d => d.Type.ToString().Split('.').Last() == type && d.Variables.Any(v => v.Identifier.Text == name)) ||
            root.DescendantNodes().OfType<ParameterSyntax>()
                .Any(p => p.Identifier.Text == name && p.Type?.ToString().Split('.').Last() == type);
        if (!Declared("ageAccess", "GraphAccess") || !Declared("cancellationToken", "CancellationToken"))
            throw new Blocker("CSHARP_ADAPTER", "Predeclare GraphAccess ageAccess and CancellationToken cancellationToken; result signatures and lifecycle remain manual.");
        var calls = root.DescendantNodes().OfType<InvocationExpressionSyntax>()
            .Where(c => c.Expression is MemberAccessExpressionSyntax m && m.Name.Identifier.Text == "SubmitAsync").ToList();
        foreach (var call in calls)
        {
            var member = (MemberAccessExpressionSyntax)call.Expression;
            if (member.Expression is not IdentifierNameSyntax receiver || !Declared(receiver.Identifier.Text, "IGremlinClient"))
                throw new Blocker("CSHARP_CLIENT_TYPE", "Only an explicitly declared IGremlinClient receiver is eligible; review type binding manually.");
        }
        return root.ReplaceNodes(calls, (call, _) => SyntaxFactory.ParseExpression(
            "ageAccess.QueryAsync(" + call.ArgumentList.Arguments[0].ToString() + ", cancellationToken)").WithTriviaFrom(call)).ToFullString();
    }
    public static object Assess(string source)
    {
        var tree = CSharpSyntaxTree.ParseText(source);
        if (tree.GetDiagnostics().Any(d => d.Severity == DiagnosticSeverity.Error))
            throw new Blocker("CSHARP_SYNTAX", "C# syntax errors require manual remediation.");
        var findings = new List<object>();
        foreach (var call in tree.GetRoot().DescendantNodes().OfType<InvocationExpressionSyntax>())
        {
            if (call.Expression is not MemberAccessExpressionSyntax member ||
                member.Name.Identifier.Text is not ("SubmitAsync" or "SubmitWithSingleResultAsync")) continue;
            var line = tree.GetLineSpan(call.Span).StartLinePosition.Line + 1;
            if (call.ArgumentList.Arguments.FirstOrDefault()?.Expression is not LiteralExpressionSyntax literal ||
                !literal.IsKind(SyntaxKind.StringLiteralExpression))
            { findings.Add(new { line, status = "blocked", code = "DYNAMIC_QUERY" }); continue; }
            try
            {
                var translation = GremlinCompiler.Compile(literal.Token.ValueText);
                findings.Add(new
                {
                    line,
                    status = "supported-read-review-required",
                    translation.Projection,
                    queryHash = Codec.Hash(literal.Token.ValueText)
                });
            }
            catch (Blocker blocker) { findings.Add(new { line, status = "blocked", code = blocker.Code }); }
        }
        return new
        {
            status = "succeeded",
            findings,
            sourceHash = Codec.Hash(source),
            limitations = "Syntax-aware literal SubmitAsync inventory only; bindings, lambdas, result-model changes and client lifecycle remain manual."
        };
    }
    public static object RewritePlan(string source)
    {
        var tree = CSharpSyntaxTree.ParseText(source);
        if (tree.GetDiagnostics().Any(d => d.Severity == DiagnosticSeverity.Error))
            throw new Blocker("CSHARP_SYNTAX", "C# syntax errors require manual remediation.");
        var edits = new List<object>();
        foreach (var call in tree.GetRoot().DescendantNodes().OfType<InvocationExpressionSyntax>())
        {
            if (call.Expression is not MemberAccessExpressionSyntax member || member.Name.Identifier.Text != "SubmitAsync") continue;
            if (call.ArgumentList.Arguments.Count != 1 ||
                call.ArgumentList.Arguments[0].Expression is not LiteralExpressionSyntax literal ||
                !literal.IsKind(SyntaxKind.StringLiteralExpression))
                throw new Blocker("CSHARP_REWRITE_PATTERN", "Only one-argument literal SubmitAsync calls can be planned.");
            GremlinCompiler.Compile(literal.Token.ValueText);
            edits.Add(new
            {
                start = call.Span.Start,
                length = call.Span.Length,
                replacement = "ageAccess.QueryAsync(" + literal.ToFullString() + ", cancellationToken)"
            });
        }
        if (edits.Count == 0) throw new Blocker("CSHARP_NO_PATTERN", "No supported literal SubmitAsync calls found.");
        return new
        {
            status = "review-required",
            sourceHash = Codec.Hash(source),
            edits,
            instructions = "Review the new output manually: provide GraphAccess ageAccess and cancellationToken; adapt typed result handling. This plan is not application equivalence or write authorization."
        };
    }
}
