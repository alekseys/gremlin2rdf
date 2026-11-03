using System.Text.Json;

namespace DocumentSource;

internal static class Program
{
    public static async Task<int> Main(string[] arguments)
    {
        var parentOwned = arguments.Contains("--parent-owned", StringComparer.Ordinal);
        var args = arguments.Where(value => value != "--parent-owned").ToArray();
        using var parentWatch = new CancellationTokenSource();
        if (parentOwned)
        {
            // The host owns stdin. An EOF means the host exited, even if a network operation is blocked.
            _ = Task.Run(async () =>
            {
                try
                {
                    var buffer = new char[64];
                    while (await Console.In.ReadAsync(buffer.AsMemory(), parentWatch.Token) > 0) { }
                    if (!parentWatch.IsCancellationRequested) Environment.Exit(2);
                }
                catch (OperationCanceledException) { }
                catch { if (!parentWatch.IsCancellationRequested) Environment.Exit(2); }
            });
        }
        try
        {
            if (args is ["self-test"]) return await SelfTest.Run();
            if (args is ["self-test-sdk"]) return await SelfTest.RunSdkOnly();
            if (args.Length < 3) throw new Blocked("INVALID_COMMAND", "command-validation");
            var config = Config.Load(args[1], args[2], args[0] == "check");
            var worker = new Worker(config, () => new CosmosFeed(config)) { Activity = Progress, Progress = SourceProgress };
            object acknowledgement = args switch
            {
                ["check", _, _] => await worker.Check(),
                ["probe", _, _, var report] => await worker.Probe(report),
                ["extract", _, _, var output] => worker.Extract(output),
                ["enumerate", _, _, var plan, var output] =>
                    await new Enumeration(config, () => new CosmosFeed(config))
                        { Activity = Progress, Progress = SourceProgress }.Run(plan, output),
                _ => throw new Blocked("INVALID_COMMAND", "command-validation")
            };
            Console.Out.WriteLine(JsonSerializer.Serialize(acknowledgement, Json.Options));
            return 0;
        }
        catch (Exception exception)
        {
            var blocked = exception as Blocked ?? (args is ["self-test"]
                ? new Blocked("SELF_TEST_FAILED", exception switch
                    {
                        NullReferenceException => "self-test-null-reference",
                        NotSupportedException => "self-test-not-supported",
                        ArgumentException => "self-test-argument",
                        System.Security.SecurityException => "self-test-security",
                        IOException => "self-test-io",
                        System.TypeLoadException => "self-test-type-load",
                        _ => "self-test-unclassified"
                    })
                : new Blocked(
                exception is IOException or UnauthorizedAccessException ? "LOCAL_ARTIFACT_UNAVAILABLE"
                : exception is JsonException or KeyNotFoundException or FormatException or ArgumentException
                    ? "INVALID_PROJECT_OR_ARTIFACT" : "DOCUMENT_SOURCE_BLOCKED"));
            var acknowledgement = new Dictionary<string, object?>
            {
                ["status"] = "blocked", ["code"] = blocked.Code, ["stage"] = blocked.Stage,
                ["remediation"] = "Preserve local evidence; verify source configuration, scoped approval and budgets before retrying."
            };
            if (blocked.ArtifactSha256 != null) acknowledgement["artifactSha256"] = blocked.ArtifactSha256;
            Console.Out.WriteLine(JsonSerializer.Serialize(acknowledgement, Json.Options));
            return 2;
        }
        finally { await parentWatch.CancelAsync(); }
    }

    private static void Progress(string stage, int wait, int retries) =>
        Console.Out.WriteLine(JsonSerializer.Serialize(new
        {
            type = "source-request-progress", stage, waitMillis = wait, retries
        }, Json.Options));
    private static void SourceProgress(string stage, long vertices, long edges, long reservedRequests, long elapsedMillis) =>
        Console.Out.WriteLine(JsonSerializer.Serialize(new
        {
            type = "source-progress", stage, vertices, edges, reservedRequests, elapsedMillis
        }, Json.Options));
}
