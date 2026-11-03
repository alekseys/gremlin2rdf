using System.Security.Cryptography;
using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace DocumentSource;

internal sealed class FixtureFeed(Func<string?, int, FeedPage> read) : IReadFeed
{
    public int Calls { get; private set; }
    public Task<FeedPage> Read(string? continuation, int pageSize, bool connectivity, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        return Task.FromResult(read(continuation, Calls++));
    }
    public void Dispose() { }
}

internal static class SelfTest
{
    private static readonly string InputHash = new('a', 64);
    private static int assertions;
    private static void Assert(bool condition)
    {
        assertions++;
        if (!condition) throw new Blocked("SELF_TEST_ASSERTION_FAILED", "self-test");
    }
    private static void Reject(Action action, string code)
    {
        try { action(); }
        catch (Blocked blocked) when (blocked.Code == code) { assertions++; return; }
        throw new Blocked("SELF_TEST_EXPECTED_REJECTION", "self-test");
    }
    private static async Task RejectAsync(Func<Task> action, string code)
    {
        try { await action(); }
        catch (Blocked blocked) when (blocked.Code == code) { assertions++; return; }
        throw new Blocked("SELF_TEST_EXPECTED_REJECTION", "self-test");
    }
    private static byte[] Raw(string text) => Encoding.UTF8.GetBytes(text);
    private static byte[] Vertex(string id, string pk = "a", string extra = "") => Raw(
        $$$"""{"id":"{{{id}}}","pk":"{{{pk}}}","label":"person","name":[{"id":"p1","_value":"same","_meta":{}},{"id":"p2","_value":"same","_meta":{}}],"_rid":"rid","_self":"self","_etag":"etag","_attachments":"attachments","_ts":123{{{extra}}}}""");
    private static byte[] Edge(string id, string owner, string sink, string pk = "a", string sinkPk = "b") => Raw(
        $$$"""{"id":"{{{id}}}","pk":"{{{pk}}}","label":"knows","_isEdge":true,"_vertexId":"{{{owner}}}","_vertexLabel":"person","_sink":"{{{sink}}}","_sinkLabel":"person","_sinkPartition":"{{{sinkPk}}}","weight":1.25,"_rid":"rid","_self":"self","_etag":"etag","_attachments":"attachments","_ts":123}""");
    private static Config Fixture(string root, string name, byte[][] documents, string profile = "property-bags-v1",
        string fidelity = "reduced", decimal budget = 1000, decimal? rate = null)
    {
        var directory = Path.Combine(root, name);
        Directory.CreateDirectory(directory);
        var entries = new List<IdentityEntry>();
        foreach (var raw in documents)
        {
            using var doc = JsonDocument.Parse(raw);
            var d = doc.RootElement;
            var edge = d.TryGetProperty("_isEdge", out var flag) && flag.ValueKind == JsonValueKind.True;
            var partition = d.GetProperty("pk");
            var typedPk = partition.ValueKind == JsonValueKind.String ? new TypedValue("string", partition.GetString()!)
                : new TypedValue("integer", partition.GetRawText());
            entries.Add(new(1, edge ? "edge" : "vertex", typedPk, new("string", d.GetProperty("id").GetString()!),
                edge ? new("string", d.GetProperty("_vertexId").GetString()!) : null, edge ? "sourceVertex" : null));
        }
        using (var stream = SafeFiles.New(Path.Combine(directory, "identities.ndjson")))
        {
            foreach (var entry in entries) SafeFiles.AppendLine(stream, Json.Bytes(entry));
            stream.Flush(true);
        }
        var mapping = JsonSerializer.SerializeToElement(new
        {
            schemaVersion = 1, baseIri = "urn:test:", vocabulary = "urn:vocab:", labels = new { },
            properties = new { }, edges = new { }, exclusions = Array.Empty<string>(), derivedRelationships = false,
            provenance = true, fidelity
        }, Json.Options);
        var expires = DateTimeOffset.UtcNow.AddHours(1).ToString("O");
        const string keyRef = "env:GRAPH_DOCUMENT_SELF_TEST_APPROVAL";
        const string secret = "document-self-test-key-never-a-real-account-secret";
        Environment.SetEnvironmentVariable(keyRef[4..], secret);
        var resources = new[] { Json.Hash(Encoding.UTF8.GetBytes(Json.Stable(mapping))) };
        var signature = Convert.ToHexStringLower(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret),
            Json.Bytes(new object[] { 1, "fixture", "reduced-fidelity", resources, InputHash, expires, keyRef })));
        var project = new
        {
            runId = "fixture", mapping, consistency = "immutable",
            source = new
            {
                scope = "fixture-scope", sourceMode = "cosmos-documents", documentEndpoint = "https://127.0.0.1:12345/",
                documentProfile = profile, database = "fixture-database", container = "fixture-container",
                partitionKeyPath = "/pk", authenticationRef = "env:GRAPH_DOCUMENT_SELF_TEST_CREDENTIAL",
                identityManifest = "identities.ndjson", identityManifestHash = SafeFiles.HashFile(Path.Combine(directory, "identities.ndjson")),
                ruBudget = Json.DecimalText(budget), ruPerSecond = rate.HasValue ? Json.DecimalText(rate.Value) : null,
                compatibilityEvidence = (string?)null
            },
            limits = new
            {
                batchRecords = 100, batchBytes = 1048576, maxRecordBytes = 262144, requestTimeoutMillis = 1000,
                maxRetries = 1, minimumFreeDiskBytes = 0, maximumOperationMillis = 300000
            },
            approvals = new[] { new { schemaVersion = 1, runId = "fixture", operation = "reduced-fidelity",
                resources, inputHash = InputHash, expiresAt = expires, keyRef, signature } }
        };
        SafeFiles.WriteNew(Path.Combine(directory, "project.json"), Json.Bytes(project));
        return Config.Load(Path.Combine(directory, "project.json"), InputHash);
    }
    private static FixtureFeed Feed(byte[][] documents) => new((token, _) =>
    {
        var offset = token == null ? 0 : int.Parse(token, System.Globalization.CultureInfo.InvariantCulture);
        var page = documents.Skip(offset).Take(2).ToArray();
        var next = offset + page.Length < documents.Length ? (offset + page.Length).ToString(
            System.Globalization.CultureInfo.InvariantCulture) : null;
        return new(page, next, 1.5m, 200, 0);
    });

    public static async Task<int> Run()
    {
        var root = Path.GetFullPath(Path.Combine("dotnet", "DocumentSource", ".self-test-" + Guid.NewGuid().ToString("N")));
        Directory.CreateDirectory(root);
        try
        {
            var docs = new[] { Vertex("v1"), Vertex("v2", "b"), Edge("e1", "v1", "v2"),
                Edge("e2", "v1", "v2"), Edge("self", "v1", "v1", "a", "a") };
            var config = Fixture(root, "normal", docs);
            var codec = new Codec(config);
            var vertex = codec.Decode(docs[0]);
            Assert(vertex.Properties.Count(p => p.Key == "name") == 2);
            Assert(vertex.Properties.Where(p => p.Key == "name").Select(p => p.Id).Distinct().Count() == 2);
            Assert(vertex.Properties.Single(p => p.Key == "pk").Id == null && vertex.Provenance.Fidelity == "reduced");
            var edge = codec.Decode(docs[2]);
            Assert(edge.EdgeScope == "sourceVertex" && edge.Source!.Partition.Lexical == "a"
                && edge.Destination!.Partition.Lexical == "b");
            Assert(edge.Metadata["inVPartition"] == edge.Destination!.Partition
                && edge.Metadata["sourceLabel"].Lexical == "person");
            Assert(codec.Decode(docs[4]).Source == codec.Decode(docs[4]).Destination);
            Assert(codec.Unobservable.Contains("numeric-runtime-type-unobservable"));
            var duplicate = Encoding.UTF8.GetString(docs[0]).Replace("\"p2\"", "\"p1\"", StringComparison.Ordinal);
            Reject(() => codec.Decode(Raw(duplicate)), "DUPLICATE_OCCURRENCE_ID");
            Reject(() => codec.Decode(Vertex("v", extra: ",\"bad\":null")), "UNSUPPORTED_PROPERTY_BAG");
            Reject(() => codec.Decode(Vertex("v", extra: ",\"bad\":[{\"id\":\"p\",\"_value\":1,\"other\":2}]")), "UNKNOWN_OCCURRENCE_FIELD");
            Reject(() => codec.Decode(Raw("""{"id":"v","label":"person","pk":"a","_isEdge":false}""")), "AMBIGUOUS_EDGE_FLAG");
            Reject(() => codec.Decode(Raw("""{"id":"v","id":"w","label":"person","pk":"a"}""")), "DUPLICATE_JSON_FIELD");
            Reject(() => codec.Decode(Vertex("v", extra: ",\"other\":[{\"id\":\"p1\",\"_value\":\"x\",\"_meta\":{}}]")),
                "DUPLICATE_OCCURRENCE_ID");
            Reject(() => codec.Decode(Raw("""{"id":"v","label":"person","pk":"a","x":[{"id":"p","_value":1e999,"_meta":{}}]}""")),
                "UNSUPPORTED_NUMERIC_VALUE");
            var lexical = codec.Decode(Vertex("v", extra: ",\"number\":[{\"id\":\"n\",\"_value\":1.2500e+02,\"_meta\":{}}]"));
            Assert(lexical.Properties.Single(p => p.Key == "number").Value == new TypedValue("doubleValue", "1.2500e+02"));
            var huge = codec.Decode(Vertex("v", extra: ",\"number\":[{\"id\":\"n\",\"_value\":123456789012345678901234567890,\"_meta\":{}}]"));
            Assert(huge.Properties.Single(p => p.Key == "number").Value.Type == "integer");
            var normalized = codec.Decode(Vertex("v", extra: ",\"number\":[{\"id\":\"n\",\"_value\":-0,\"_meta\":{}}]"));
            Assert(normalized.Properties.Single(p => p.Key == "number").Value == new TypedValue("integer", "0")
                && normalized.Properties.Single(p => p.Key == "number").Fidelity == "reduced");
            var numericPk = codec.Decode(Raw("""{"id":"numeric","label":"person","pk":42}"""));
            Assert(numericPk.Identity.Partition == new TypedValue("integer", "42"));
            var decimalPk = codec.Decode(Raw("""{"id":"decimal","label":"person","pk":1.2500}"""));
            Assert(decimalPk.Identity.Partition == new TypedValue("doubleValue", "1.2500")
                && decimalPk.Provenance.Fidelity == "reduced");
            Reject(() => codec.Decode(Raw("""{"id":42,"label":"person","pk":42}""")), "UNSUPPORTED_PHYSICAL_ID");
            Reject(() => codec.Decode(Raw("""{"id":"e","label":"knows","pk":"a","_isEdge":true,"_vertexId":"v1","_sink":"v2","_vertexLabel":"person","_sinkLabel":"person"}""")),
                "MISSING_DOCUMENT_IDENTITY");
            var flatRaw = Raw("""{"id":"flat","label":"person","pk":"a","name":"text","active":true,"score":1.2300}""");
            var flatConfig = Fixture(root, "flat", [flatRaw], "flat-v1");
            var flat = new Codec(flatConfig).Decode(flatRaw);
            Assert(flat.Properties.All(p => p.Id == null && p.Fidelity == "reduced"));
            Assert(flat.Properties.Single(p => p.Key == "active").Value.Type == "booleanValue");
            var autoConfig = Fixture(root, "auto", [docs[0], flatRaw], "auto-v1");
            var autoCodec = new Codec(autoConfig);
            Assert(Json.Bytes(autoCodec.Decode(docs[0]).Properties).SequenceEqual(Json.Bytes(vertex.Properties)));
            Assert(Json.Bytes(autoCodec.Decode(flatRaw).Properties).SequenceEqual(Json.Bytes(flat.Properties)));
            Assert(autoCodec.Decode(flatRaw).Properties.Single(p => p.Key == "name").Value.Lexical == "text");
            Assert(autoCodec.DetectedProfiles.SetEquals(["property-bags-v1", "flat-v1"]));
            Reject(() => autoCodec.Decode(Raw("""{"id":"v","label":"person","pk":"a","name":"x","age":[{"id":"p","_value":1}]}""")),
                "UNSUPPORTED_DOCUMENT_SHAPE");
            Reject(() => autoCodec.Decode(Raw("""{"id":"v","label":"person","pk":"a","name":{"_value":"x"}}""")),
                "UNSUPPORTED_DOCUMENT_SHAPE");
            Reject(() => autoCodec.Decode(Raw("""{"id":"v","label":"person","pk":"a","name":[{"id":"p"}]}""")),
                "MISSING_PROPERTY_VALUE");
            Assert(autoCodec.Decode(Raw("""{"id":"empty","label":"person","pk":"a"}""")).Properties.Count == 1);
            await new Worker(autoConfig, () => Feed([docs[0], flatRaw])).Probe("auto-report.json");
            using (var autoReport = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(autoConfig.Root, "auto-report.json"))))
                Assert(autoReport.RootElement.GetProperty("result").GetProperty("detectedProfiles").GetArrayLength() == 2);
            var worker = new Worker(config, () => Feed(docs));
            await worker.Probe("reports/one.json");
            await worker.Probe("reports/two.json");
            var cacheWorker = new Worker(config, () => throw new Blocked("UNEXPECTED_SOURCE_REQUEST"));
            cacheWorker.Extract("output/canonical.ndjson");
            await cacheWorker.Probe("reports/cached.json");
            var output = Path.Combine(config.Root, "output", "canonical.ndjson");
            Assert(SafeFiles.Lines(output, config.MaxRecordBytes).Count() == docs.Length);
            var outputManifest = JsonDocument.Parse(File.ReadAllBytes(output + ".manifest.json"));
            Assert(Json.Text(outputManifest.RootElement, "sha256") == SafeFiles.HashFile(output));
            outputManifest.Dispose();
            Reject(() => cacheWorker.Extract("output/canonical.ndjson"), "ARTIFACT_ALREADY_EXISTS");
            var projectPath = Path.Combine(config.Root, "project.json");
            var updated = Encoding.UTF8.GetString(File.ReadAllBytes(projectPath))
                .Replace("\"compatibilityEvidence\":null", "\"compatibilityEvidence\":\"reports/two.json\"", StringComparison.Ordinal);
            File.WriteAllText(projectPath, updated, new UTF8Encoding(false));
            var phaseUpdated = Config.Load(projectPath, InputHash);
            Assert(phaseUpdated.ProjectSha != config.ProjectSha && phaseUpdated.Binding == config.Binding);
            new Worker(phaseUpdated, () => throw new Blocked("UNEXPECTED_SOURCE_REQUEST")).Extract("output/phase-update.ndjson");
            var corruptRaw = Directory.EnumerateFiles(Path.Combine(config.Root,
                config.CacheRelative.Replace('/', Path.DirectorySeparatorChar), "snapshot", "raw"), "*.raw").First();
            File.AppendAllText(corruptRaw, " ");
            Reject(() => cacheWorker.Extract("output/corrupt.ndjson"), "RAW_SNAPSHOT_INTEGRITY_FAILURE");
            var flatWorker = new Worker(flatConfig, () => Feed([flatRaw]));
            await flatWorker.Probe("report.json");
            var prettyRaw = Raw(Encoding.UTF8.GetString(docs[0]).Replace(",\"pk\"", ",\n  \"pk\"", StringComparison.Ordinal));
            var prettyConfig = Fixture(root, "pretty", [prettyRaw]);
            await new Worker(prettyConfig, () => Feed([prettyRaw])).Probe("report.json");
            var prettySnapshot = Directory.GetFiles(Path.Combine(prettyConfig.Root,
                prettyConfig.CacheRelative.Replace('/', Path.DirectorySeparatorChar), "snapshot", "raw"), "*.raw").Single();
            Assert(File.ReadAllBytes(prettySnapshot).SequenceEqual(prettyRaw.Concat(new byte[] { 10 })));
            var checkConfig = Fixture(root, "check", [docs[0]], rate: 1000);
            var checkProject = Path.Combine(checkConfig.Root, "project.json");
            File.Delete(Path.Combine(checkConfig.Root, "identities.ndjson"));
            var checkLoaded = Config.Load(checkProject, InputHash, true);
            await new Worker(checkLoaded, () => Feed([docs[0]])).Check();
            await new Worker(checkLoaded, () => Feed([docs[0]])).Check();
            Assert(Directory.GetDirectories(Path.Combine(checkConfig.Root,
                checkConfig.CacheRelative.Replace('/', Path.DirectorySeparatorChar))).Length == 2);
            Reject(() => Config.Load(Path.Combine(flatConfig.Root, "project.json"), InputHash, true),
                "REVIEWED_RU_RATE_REQUIRED");
            var modeConfig = Fixture(root, "mode-guard", [docs[0]]);
            var modePath = Path.Combine(modeConfig.Root, "project.json");
            File.WriteAllText(modePath, Encoding.UTF8.GetString(File.ReadAllBytes(modePath))
                .Replace("\"sourceMode\":\"cosmos-documents\"", "\"sourceMode\":\"gremlin\"", StringComparison.Ordinal));
            Reject(() => Config.Load(modePath, InputHash), "INVALID_PROJECT");
            var noBudget = Fixture(root, "no-budget", [docs[0]], rate: 1000);
            var noBudgetPath = Path.Combine(noBudget.Root, "project.json");
            File.WriteAllText(noBudgetPath, Encoding.UTF8.GetString(File.ReadAllBytes(noBudgetPath))
                .Replace("\"ruBudget\":\"1000\"", "\"ruBudget\":null", StringComparison.Ordinal));
            Reject(() => Config.Load(noBudgetPath, InputHash, true), "REVIEWED_RU_BUDGET_REQUIRED");
            var strictConfig = Fixture(root, "strict", [docs[0]], fidelity: "strict");
            await RejectAsync(() => new Worker(strictConfig, () => Feed([docs[0]])).Probe("report.json"),
                "DOCUMENT_FIDELITY_UNOBSERVABLE");
            using (var blocked = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(strictConfig.Root, "report.json"))))
            {
                var result = blocked.RootElement.GetProperty("result");
                Assert(result.GetProperty("sourceCountsReconciled").GetBoolean());
                Assert(result.GetProperty("observedVertices").GetInt64() == 1);
                Assert(result.GetProperty("observedEdges").GetInt64() == 0);
                Assert(result.GetProperty("unobservable").EnumerateArray()
                    .Any(value => value.GetString() == "property-occurrence-id-unobservable"));
                Assert(!result.GetProperty("partialRawSnapshot").GetBoolean());
                Assert(blocked.RootElement.GetProperty("status").GetString() == "blocked");
            }
            Assert(!File.Exists(Path.Combine(strictConfig.Root,
                strictConfig.CacheRelative.Replace('/', Path.DirectorySeparatorChar), "snapshot", "complete.json")));
            Assert(File.Exists(Path.Combine(strictConfig.Root, strictConfig.CacheRelative.Replace('/', Path.DirectorySeparatorChar),
                "snapshot", "raw", "0000000000.raw")));
            var missing = Fixture(root, "missing", docs);
            await RejectAsync(() => new Worker(missing, () => Feed(docs[..2])).Probe("report.json"), "MISSING_DOCUMENT_IDENTITIES");
            using (var blocked = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(missing.Root, "report.json"))))
                Assert(!blocked.RootElement.GetProperty("result").GetProperty("sourceCountsReconciled").GetBoolean());
            var duplicates = Fixture(root, "duplicates", docs[..2]);
            await RejectAsync(() => new Worker(duplicates, () => Feed([docs[0], docs[0]])).Probe("report.json"),
                "DUPLICATE_DOCUMENT_IDENTITY");
            var badEndpoint = Fixture(root, "endpoint", [docs[0], docs[2]]);
            await RejectAsync(() => new Worker(badEndpoint, () => Feed([docs[0], docs[2]])).Probe("report.json"),
                "EDGE_ENDPOINT_NOT_IN_INVENTORY");
            await JournalTests(root, docs);
            await EnumerationTests(root, docs);
            assertions += await SdkFixture.Verify(config);
            await VerifyParentOwnership(root);
            Reject(() => SafeFiles.Resolve(root, "../escape"), "UNSAFE_ARTIFACT_PATH");
            Reject(() => SafeFiles.Resolve(root, "C:/escape"), "UNSAFE_ARTIFACT_PATH");
            Reject(() => SafeFiles.Resolve(root, "CON.json"), "UNSAFE_ARTIFACT_PATH");
            Environment.SetEnvironmentVariable("GRAPH_DOCUMENT_SELF_TEST_APPROVAL", null);
            Directory.Delete(root, true);
            Console.Out.WriteLine(JsonSerializer.Serialize(new
            {
                status = "succeeded", operation = "self-test", assertions, sdkVersion = "3.58.0",
                liveEndpointAccessed = false
            }, Json.Options));
            return 0;
        }

        catch
        {
            Environment.SetEnvironmentVariable("GRAPH_DOCUMENT_SELF_TEST_APPROVAL", null);
            throw;
        }
    }

    public static async Task<int> RunSdkOnly()
    {
        var root = Path.GetFullPath(Path.Combine("dotnet", "DocumentSource", ".self-test-" + Guid.NewGuid().ToString("N")));
        Directory.CreateDirectory(root);
        var config = Fixture(root, "sdk", [Vertex("sdk-fixture")]);
        var checks = await SdkFixture.Verify(config);
        Directory.Delete(root, true);
        Environment.SetEnvironmentVariable("GRAPH_DOCUMENT_SELF_TEST_APPROVAL", null);
        Console.Out.WriteLine(JsonSerializer.Serialize(new
        {
            status = "succeeded", operation = "self-test-sdk", assertions = checks,
            sdkVersion = "3.58.0", liveEndpointAccessed = false
        }, Json.Options));
        return 0;
    }

    private static async Task VerifyParentOwnership(string root)
    {
        using var child = new Process
        {
            StartInfo = new ProcessStartInfo(Environment.ProcessPath!, "self-test-sdk --parent-owned")
            {
                WorkingDirectory = root, UseShellExecute = false, RedirectStandardInput = true,
                RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true
            }
        };
        Assert(child.Start());
        child.StandardInput.Close();
        try
        {
            await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
            Assert(child.ExitCode == 2);
        }
        catch
        {
            if (!child.HasExited) child.Kill(entireProcessTree: true);
            await child.WaitForExitAsync();
            throw;
        }
    }

    private static async Task JournalTests(string root, byte[][] docs)
    {
        var config = Fixture(root, "journal", docs);
        using (var journal = new Journal(config, "resume", "probe"))
        using (var feed = new FixtureFeed((token, calls) => calls == 0 ? new([docs[0]], "next", 2m, 200, 0)
            : throw new IOException("fixture-transport-loss")))
            await RejectAsync(() => journal.ReadAll(feed, 100), "SOURCE_SPEND_UNKNOWN");
        Reject(() => { using var journal = new Journal(config, "resume", "probe"); }, "SOURCE_SPEND_UNKNOWN");
        Assert(File.Exists(Path.Combine(config.Root, "resume", "raw", "0000000000.raw")));
        var resumed = Fixture(root, "known-resume", docs);
        var calls = 0;
        using (var journal = new Journal(resumed, "journal", "probe", maximumRequests: 1))
        using (var feed = new FixtureFeed((_, _) => { calls++; return new([docs[0]], "next", 2m, 200, 0); }))
            await RejectAsync(() => journal.ReadAll(feed, 100), "SOURCE_ENUMERATION_REQUEST_LIMIT");
        using (var journal = new Journal(resumed, "journal", "probe", maximumRequests: 2))
        using (var feed = new FixtureFeed((token, _) =>
        {
            Assert(token == "next");
            calls++;
            return new([docs[1]], null, 3m, 200, 0);
        }))
        {
            await journal.ReadAll(feed, 100);
            Assert(journal.State.RequestCharge == 5 && journal.State.Pages == 2 && journal.State.FeedComplete);
        }
        Assert(calls == 2);
        using (var journal = new Journal(resumed, "journal", "probe"))
            Assert(journal.Documents().Count() == 2);
        var retryConfig = Fixture(root, "retry", docs);
        using (var journal = new Journal(retryConfig, "retry", "probe"))
        using (var feed = new FixtureFeed((_, calls) => calls == 0 ? new([], null, 0.25m, 429, 1)
            : new([docs[0]], null, 1m, 200, 0)))
        {
            await journal.ReadAll(feed, 100);
            Assert(journal.State.RequestCharge == 1.25m && journal.State.Requests == 2);
        }
        var limit = Fixture(root, "ru-limit", docs, budget: 1);
        using (var journal = new Journal(limit, "limit", "probe"))
        using (var feed = Feed(docs))
            await RejectAsync(() => journal.ReadAll(feed, 100), "RU_BUDGET_EXCEEDED");
        var pendingConfig = Fixture(root, "pending", docs);
        using (var journal = new Journal(pendingConfig, "pending", "probe")) { }
        SafeFiles.WriteNew(Path.Combine(pendingConfig.Root, "pending", "checkpoint.json.pending"), "{}"u8.ToArray());
        Reject(() => { using var journal = new Journal(pendingConfig, "pending", "probe"); }, "PENDING_CHECKPOINT");
        var pacing = Fixture(root, "pacing", docs, rate: 2);
        var paced = false;
        var timer = Stopwatch.StartNew();
        using (var journal = new Journal(pacing, "paced", "probe")
            { Activity = (stage, _, _) => paced |= stage == "source-rate-wait" })
        using (var feed = new FixtureFeed((_, calls) =>
        {
            if (calls == 1) Assert(timer.ElapsedMilliseconds >= 450);
            return new([docs[calls]], calls == 0 ? "next" : null, 1m, 200, 0);
        }))
            await journal.ReadAll(feed, 100);
        Assert(paced);
        var approvalConfig = Fixture(root, "approval", docs);
        Environment.SetEnvironmentVariable("GRAPH_DOCUMENT_SELF_TEST_APPROVAL", "different-key-with-at-least-thirty-two-bytes");
        await RejectAsync(() => new Worker(approvalConfig, () => throw new Blocked("UNEXPECTED_SOURCE_REQUEST")).Probe("report.json"),
            "SCOPED_APPROVAL_REQUIRED");
    }

    private static async Task EnumerationTests(string root, byte[][] docs)
    {
        var config = Fixture(root, "enumeration", docs);
        SafeFiles.WriteNew(Path.Combine(config.Root, "plan.json"),
            Json.Bytes(new EnumerationPlan(1, [], 100, 100, 60000)));
        var enumeration = new Enumeration(config, () => Feed(docs));
        await enumeration.Run("plan.json", "sdk-enumeration");
        await new Enumeration(config, () => throw new Blocked("UNEXPECTED_SOURCE_REQUEST"))
            .Run("plan.json", "sdk-enumeration");
        var output = Path.Combine(config.Root, "sdk-enumeration", "identities.ndjson");
        Assert(SafeFiles.Lines(output, config.MaxRecordBytes).Count() == docs.Length);
        using var manifest = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(config.Root, "sdk-enumeration", "manifest.json")));
        Assert(manifest.RootElement.GetProperty("vertices").GetInt64() == 2
            && manifest.RootElement.GetProperty("edges").GetInt64() == 3);
        Assert(manifest.RootElement.GetProperty("sourceCountsReconciled").GetBoolean());
        var changed = Fixture(root, "enumeration-changed", docs);
        SafeFiles.WriteNew(Path.Combine(changed.Root, "plan.json"), Json.Bytes(new EnumerationPlan(1, [], 100, 100, 60000)));
        var passes = 0;
        await RejectAsync(() => new Enumeration(changed, () => Feed(passes++ == 0 ? docs : docs[..^1]))
            .Run("plan.json", "sdk-enumeration"), "SOURCE_DOCUMENTS_CHANGED");
        var largeDocs = Enumerable.Range(0, 2105).Select(i => Vertex("vertex-" + i.ToString("D6",
            System.Globalization.CultureInfo.InvariantCulture))).ToArray();
        var large = Fixture(root, "external-sort", largeDocs);
        SafeFiles.WriteNew(Path.Combine(large.Root, "plan.json"), Json.Bytes(new EnumerationPlan(1, [], 100, 10000, 300000)));
        // Full-size fixture pages keep the test bounded while forcing a multi-stage, 16-way merge.
        FixtureFeed LargeFeed() => new((token, _) =>
        {
            var start = token == null ? 0 : int.Parse(token, System.Globalization.CultureInfo.InvariantCulture);
            var page = largeDocs.Skip(start).Take(100).Reverse().ToArray();
            return new(page, start + page.Length < largeDocs.Length ? (start + page.Length).ToString(
                System.Globalization.CultureInfo.InvariantCulture) : null, 1m, 200, 0);
        });
        await new Enumeration(large, LargeFeed).Run("plan.json", "sdk-enumeration");
        Assert(SafeFiles.Lines(Path.Combine(large.Root, "sdk-enumeration", "identities.ndjson"), large.MaxRecordBytes)
            .Count() == 2105);
    }
}
