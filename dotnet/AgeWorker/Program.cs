using System.Text;
using System.Text.Json;

namespace Gremlin2Age;

public static class Program
{
    private static readonly object OutputLock = new();
    private static long sequence;
    private static void Emit(string type, string run, string operationId, string phase, object payload)
    {
        lock (OutputLock)
        {
            var text = Codec.Text(new
            {
                protocolVersion = 1,
                sequence = ++sequence,
                type,
                runId = run,
                operationId,
                phase,
                payload
            });
            if (Encoding.UTF8.GetByteCount(text) > 1_048_576)
                text = Codec.Text(new
                {
                    protocolVersion = 1,
                    sequence,
                    type = "error",
                    runId = run,
                    operationId,
                    phase,
                    payload = new { status = "blocked", code = "OUTPUT_BOUND" }
                });
            Console.WriteLine(text);
        }
    }
    private static object Diagnostic(Exception error) => error is Blocker b
        ? new { status = "blocked", code = b.Code, remediation = b.Message }
        : new
        {
            status = error is OperationCanceledException ? "paused" : "blocked",
            code = "AGE_OPERATION_FAILED",
            remediation = "Review the owned artifacts, TLS/operator prerequisites and confirmed checkpoint. Raw database errors are not exposed."
        };
    public static async Task<int> Main(string[] args)
    {
        try
        {
            if (args is ["worker"]) return await Worker();
            if (args is ["smoke"]) { Console.WriteLine(Codec.Text(Smoke())); return 0; }
            if (args is ["translate", var query]) { Console.WriteLine(Codec.Text(GremlinCompiler.Compile(query))); return 0; }
            if (args.Length < 2) throw new Blocker("COMMAND", "Use worker, smoke, translate <query>, or <operation> <project.json> [input] [output].");
            var projectPath = Path.GetFullPath(args[1]);
            var project = Codec.Parse<Project>(Codec.Bounded(projectPath)); project.Validate();
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(project.Limits.MaximumOperationSeconds));
            var result = await Execute(args[0], project, Path.GetDirectoryName(projectPath)!, args.ElementAtOrDefault(2) ?? "",
                args.ElementAtOrDefault(3) ?? "", deadline.Token, _ => { }, args.ElementAtOrDefault(4) ?? "");
            Console.WriteLine(Codec.Text(result)); return 0;
        }
        catch (Exception error) { Console.WriteLine(Codec.Text(Diagnostic(error))); return 2; }
    }
    private static async Task<string?> Frame(Stream input, CancellationToken token)
    {
        using var bytes = new MemoryStream(); var buffer = new byte[1];
        while (await input.ReadAsync(buffer, token) == 1)
        {
            if (buffer[0] == 10) return Encoding.UTF8.GetString(bytes.ToArray()).TrimEnd('\r');
            if (bytes.Length >= 1_048_576) throw new Blocker("FRAME_BOUND", "Stdio frame exceeds 1 MiB.");
            bytes.WriteByte(buffer[0]);
        }
        if (bytes.Length != 0) throw new Blocker("FRAME_TRUNCATED", "Truncated protocol frame.");
        return null;
    }
    private static async Task<int> Worker()
    {
        using var lifetime = new CancellationTokenSource();
        CancellationTokenSource? operation = null; Task? work = null;
        string activeRun = "", activeId = "", activeHash = "";
        long incoming = 0;
        var stdin = Console.OpenStandardInput();
        try
        {
            while (await Frame(stdin, lifetime.Token) is { } line)
            {
                var request = Codec.Parse<Request>(line);
                if (request.ProtocolVersion != 1 || request.Sequence != ++incoming ||
                    string.IsNullOrEmpty(request.OperationId) || request.OperationId.Length > 80)
                    throw new Blocker("PROTOCOL", "Invalid version, sequence or operation identity.");
                if (request.Type is "cancel" or "pause")
                {
                    if (request.RunId != activeRun || request.OperationId != activeId || request.InputHash != activeHash)
                        throw new Blocker("PROTOCOL_SCOPE", "Control frame does not match active operation.");
                    operation?.Cancel(); continue;
                }
                if (request.Type != "start" || work != null)
                    throw new Blocker("PROTOCOL_STATE", "Exactly one start operation per worker is supported.");
                if (request.Project == null || request.Input.Length > 16384 || request.Output.Length > 1024)
                    throw new Blocker("PROTOCOL_PAYLOAD", "Project required; input/output exceeds allowed bounds.");
                request.Project.Validate();
                if (request.Project.RunId != request.RunId || request.Project.InputHash != request.InputHash)
                    throw new Blocker("PROTOCOL_INPUT", "Project does not match frame identity/input hash.");
                activeRun = request.RunId; activeId = request.OperationId; activeHash = request.InputHash;
                operation = new CancellationTokenSource(TimeSpan.FromSeconds(request.Project.Limits.MaximumOperationSeconds));
                var token = operation.Token;
                work = Task.Run(async () =>
                {
                    try
                    {
                        var result = await Execute(request.Operation, request.Project, Directory.GetCurrentDirectory(),
                            request.Input, request.Output, token,
                            progress => Emit("progress", request.RunId, request.OperationId, request.Operation, progress), request.ExpectedResourceHash);
                        Emit("result", request.RunId, request.OperationId, request.Operation, result);
                    }
                    catch (Exception error) { Emit("error", request.RunId, request.OperationId, request.Operation, Diagnostic(error)); }
                });
            }
            operation?.Cancel();
            if (work != null) await work;
            return 0;
        }
        finally { operation?.Dispose(); }
    }
    public static async Task<object> Execute(string operation, Project p, string root, string input, string output,
        CancellationToken token, Action<object> progress, string expectedResourceHash = "")
    {
        p.Validate();
        switch (operation)
        {
            case "translate": return GremlinCompiler.Compile(input);
            case "fixture":
                var records = Fixture();
                var path = Codec.Owned(root, output);
                Codec.NewFile(path, string.Join('\n', records.Select(Codec.Text)) + "\n");
                return new { status = "succeeded", records = records.Count, output, sha256 = Codec.FileHash(path), liveCompatibility = "unverified" };
            case "authorize":
                var resourcePath = Codec.Owned(root, output);
                var authorizedHash = input switch
                {
                    "load" => Codec.FileHash(resourcePath, token),
                    "crud-upsert" => Codec.Hash(Codec.Text(Codec.Parse<GraphRecord>(Codec.Bounded(resourcePath, 262144)))),
                    "crud-delete" => Codec.Hash(Codec.Text(Codec.Parse<DeleteRequest>(Codec.Bounded(resourcePath, 16384)))),
                    _ => throw new Blocker("APPROVAL_OPERATION", "Use load, crud-upsert or crud-delete exact input.")
                };
                if (expectedResourceHash != "" && authorizedHash != expectedResourceHash)
                    throw new Blocker("REVIEWED_INPUT_CHANGED", "Reviewed input changed before authorization; review again.");
                return input switch
                {
                    "load" => Authorizations.Issue(p, "load", authorizedHash),
                    "crud-upsert" or "crud-delete" => Authorizations.Issue(p, "crud", authorizedHash),
                    _ => throw new Blocker("APPROVAL_OPERATION", "Use load, crud-upsert or crud-delete exact input.")
                };
            case "authorize-rewrite":
                var rewriteHash = CSharpTooling.ResourceHash(Codec.Bounded(Codec.Owned(root, input)), output);
                if (expectedResourceHash != "" && rewriteHash != expectedResourceHash)
                    throw new Blocker("REVIEWED_INPUT_CHANGED", "Reviewed source/output changed before authorization.");
                return Authorizations.Issue(p, "rewrite", rewriteHash);
            case "load":
            case "resume":
                return await Migration.Load(p, root, input, output, token, progress);
            case "extract":
                return await CosmosSource.Extract(p, root, input, "source-compatibility.json", output, token);
            case "query":
                await using (var store = new AgeStore(p))
                {
                    await store.Open(token); return new { status = "succeeded", values = await store.Query(GremlinCompiler.Compile(input), token) };
                }
            case "validate":
                await using (var store = new AgeStore(p))
                {
                    await store.Open(token); return await store.ValidateFile(Codec.Owned(root, input), token);
                }
            case "assess":
                return CSharpTooling.Assess(Codec.Bounded(Codec.Owned(root, input)));
            case "rewrite-plan":
                var plan = CSharpTooling.RewritePlan(Codec.Bounded(Codec.Owned(root, input)));
                Codec.NewFile(Codec.Owned(root, output), Codec.Text(plan)); return plan;
            case "rewrite-apply":
                var original = Codec.Bounded(Codec.Owned(root, input));
                Authorizations.Require(p, "rewrite", CSharpTooling.ResourceHash(original, output));
                var rewritten = CSharpTooling.Apply(original);
                Codec.NewFile(Codec.Owned(root, output), rewritten);
                return new
                {
                    status = "review-required",
                    output,
                    sourceHash = Codec.Hash(original),
                    outputHash = Codec.Hash(rewritten),
                    applicationEquivalence = "not-established"
                };
            case "crud-upsert":
                var record = Codec.Parse<GraphRecord>(Codec.Bounded(Codec.Owned(root, input), 262144));
                await new GraphAccess(p).UpsertAsync(record, token);
                return new { status = "succeeded", externalId = record.ExternalId };
            case "crud-delete":
                var delete = Codec.Parse<DeleteRequest>(Codec.Bounded(Codec.Owned(root, input), 16384));
                await new GraphAccess(p).DeleteAsync(delete.Identity, delete.Kind, delete.ExpectedHash, token);
                return new { status = "succeeded", externalId = delete.Identity.Key(delete.Kind) };
            case "publish":
                throw new Blocker("MANUAL_CUTOVER", "Publication is manual; no production routing or deployment is performed.");
            default:
                throw new Blocker("OPERATION_UNSUPPORTED", "Operation is not implemented for AGE; no fallback or approximation.");
        }
    }
    public static List<GraphRecord> Fixture()
    {
        var partition = new TypedValue("string", "tenant");
        var a = new Identity("fixture", partition, new("string", "a"));
        var b = new Identity("fixture", partition, new("integer", "1"));
        var provenance = new Provenance("synthetic", "immutable", "deterministic-fixture-v1", true);
        var occurrence = new Occurrence("score", new("string", "p1"), new("double", "1.00"),
            new() { ["unit"] = new("string", "points") });
        var records = new List<GraphRecord>
        {
            new(1,"vertex",a,"Person",null,null,[occurrence, occurrence with { Id = new("string","p2") }],provenance),
            new(1,"vertex",b,"Person",null,null,[],provenance),
            new(1,"edge",new("fixture",partition,new("string","e1"),a.Id),"knows",a,b,[],provenance),
            new(1,"edge",new("fixture",partition,new("string","e2"),a.Id),"knows",a,b,[],provenance),
            new(1,"edge",new("fixture",partition,new("string","self"),a.Id),"knows",a,a,[],provenance)
        };
        foreach (var record in records) record.Validate();
        return records;
    }
    public static object Smoke()
    {
        int checks = 0;
        void Assert(bool value) { if (!value) throw new Blocker("SMOKE_FAILED", "Offline invariant failed."); checks++; }
        var records = Fixture();
        Assert(records.Select(r => r.ExternalId).Distinct().Count() == 5);
        Assert(records[0].Properties.Count == 2 && records[0].Properties[0].Value.Lexical == "1.00");
        Assert(Codec.Text(Codec.Parse<GraphRecord>(Codec.Text(records[0]))) == Codec.Text(records[0]));
        Assert(new Identity("fixture", new("string", "tenant"), new("string", "1")).Key("vertex") != records[1].ExternalId);
        var read = GremlinCompiler.Compile("g.V().out('knows').in('knows').count()");
        Assert(read.Cypher.Contains("WITH v1 MATCH") && read.Count);
        Assert(GremlinCompiler.Compile("g.V().values('score').dedup().count()").Deduplicate);
        Assert(Agtype.Decode("1.00::numeric").Lexical == "1.00");
        Assert(Agtype.Decode("{\"id\":1,\"label\":\"Person\",\"properties\":{}}::vertex").Type == "vertex");
        try { Codec.Parse<TypedValue>("{\"type\":\"string\",\"lexical\":\"a\",\"lexical\":\"b\"}"); Assert(false); }
        catch (Blocker error) when (error.Code == "JSON_DUPLICATE_KEY") { checks++; }
        try { GremlinCompiler.Compile("g.V().both().count()"); Assert(false); } catch (Blocker) { checks++; }
        try { GremlinCompiler.Compile("g.V().has('x',foo)"); Assert(false); } catch (Blocker) { checks++; }
        try { (records[0] with { Properties = [records[0].Properties[0], records[0].Properties[0]] }).Validate(); Assert(false); } catch (Blocker) { checks++; }
        var assessment = Codec.Text(CSharpTooling.Assess("class C { void M() { client.SubmitAsync(\"g.V().count()\"); } }"));
        Assert(assessment.Contains("supported-read-review-required"));
        Assert(Codec.Text(CSharpTooling.RewritePlan("class C { void M() { client.SubmitAsync(\"g.V().count()\"); } }")).Contains("ageAccess.QueryAsync"));
        return new { status = "succeeded", checks, liveSource = "unverified", postgresAge = "not-executed", javaDependency = false };
    }
}
public sealed record Request(int ProtocolVersion, long Sequence, string Type, string RunId,
    string OperationId, string InputHash, string Operation, Project? Project, string Input = "", string Output = "", string ExpectedResourceHash = "");
