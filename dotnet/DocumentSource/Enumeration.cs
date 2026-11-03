using System.Text;
using System.Text.Json;

namespace DocumentSource;

internal sealed record EnumerationPlan(int SchemaVersion, TypedValue[] Partitions, int PageSize,
    long MaximumRequests, long MaximumDurationMillis);
internal sealed record EnumerationIndex(IdentityEntry Entry, string RawSha256);

internal sealed class Enumeration(Config config, Func<IReadFeed> feedFactory)
{
    public Action<string, int, int>? Activity { get; init; }
    public Action<string, long, long, long, long>? Progress { get; init; }
    public async Task<object> Run(string planRelative, string outputRelative)
    {
        if (config.Consistency == "best-effort")
            throw new Blocked("ENUMERATION_REQUIRES_STABLE_SOURCE", "source-enumeration");
        var planPath = SafeFiles.Resolve(config.Root, planRelative);
        if (new FileInfo(planPath).Length > 1048576) throw new Blocked("INVALID_ENUMERATION_PLAN");
        var planBytes = File.ReadAllBytes(planPath);
        var plan = Json.Read<EnumerationPlan>(planBytes);
        if (plan.SchemaVersion != 1 || plan.Partitions == null || plan.Partitions.Length > 1000
            || plan.PageSize is < 1 or > 100 || plan.PageSize > config.BatchRecords
            || plan.MaximumRequests is < 1 or > 10000000 || plan.MaximumDurationMillis is < 1000 or > 86400000)
            throw new Blocked("INVALID_ENUMERATION_PLAN", "source-enumeration");
        foreach (var partition in plan.Partitions)
            new IdentityEntry(1, "vertex", partition, new("string", "plan-validation"), null, null).Validate();
        // All physical partitions are always read. Candidates do not silently restrict coverage.
        using var planDoc = JsonDocument.Parse(planBytes);
        var planHash = Json.Hash(Encoding.UTF8.GetBytes(Json.Stable(planDoc.RootElement)));
        using var journal = new Journal(config, outputRelative, "enumerate", planHash,
            plan.MaximumRequests, plan.MaximumDurationMillis) { Activity = Activity, Progress = Progress };
        var completedPath = SafeFiles.Resolve(journal.DirectoryPath, "manifest.json");
        if (File.Exists(completedPath))
        {
            if (new FileInfo(completedPath).Length > 65536) throw new Blocked("ENUMERATION_MANIFEST_INTEGRITY");
            if (journal.State.CompletionSha256 == null
                || SafeFiles.HashFile(completedPath) != journal.State.CompletionSha256)
                throw new Blocked("ENUMERATION_MANIFEST_INTEGRITY", "source-enumeration");
            using var completed = JsonDocument.Parse(File.ReadAllBytes(completedPath));
            var value = completed.RootElement;
            if (Json.Text(value, "runId") != config.RunId || Json.Text(value, "inputHash") != config.InputHash
                || Json.Text(value, "planHash") != planHash || Json.Text(value, "status") != "succeeded"
                || !journal.State.FeedComplete || journal.State.Pass != 1
                || Json.Text(value, "rawSnapshotHash") != journal.RawSnapshotHash()
                || Json.Text(value, "sha256") != SafeFiles.HashFile(SafeFiles.Resolve(journal.DirectoryPath, "identities.ndjson")))
                throw new Blocked("ENUMERATION_MANIFEST_INTEGRITY", "source-enumeration");
            return value.Clone();
        }
        if (journal.State.Pass == 0)
        {
            if (!journal.State.FeedComplete)
            {
                using var feed = feedFactory();
                await journal.ReadAll(feed, plan.PageSize);
            }
            journal.BeginVerificationPass();
        }
        if (!journal.State.FeedComplete)
        {
            using var feed = feedFactory();
            await journal.ReadAll(feed, plan.PageSize);
        }
        var attempt = SafeFiles.Resolve(journal.DirectoryPath, "inventory-" + Guid.NewGuid().ToString("N"));
        journal.Report("source-document-reconciliation");
        var index = SafeFiles.Resolve(attempt, "index");
        var codec = new Codec(config);
        var chunks = new List<byte[]>(Math.Min(config.BatchRecords, 512));
        long chunkBytes = 0;
        long vertices = 0, edges = 0, firstCount = 0, secondCount = 0;
        var segments = 0;
        foreach (var raw in journal.Documents(0))
        {
            using var parsed = Codec.Parse(raw);
            var entry = codec.Entry(parsed.RootElement);
            if (entry.Id.Type != "string" || entry.OwnerId is { Type: not "string" })
                throw new Blocked("UNSUPPORTED_PHYSICAL_ID", "source-enumeration");
            var path = EntryPath(index, entry.Key, ".json");
            if (File.Exists(path)) throw new Blocked("DUPLICATE_DOCUMENT_IDENTITY", "source-enumeration");
            SafeFiles.WriteNew(path, Json.Bytes(new EnumerationIndex(entry, Json.Hash(raw))));
            var entryBytes = Json.Bytes(entry);
            if (entryBytes.Length > config.MaxRecordBytes)
                throw new Blocked("RECORD_SIZE_LIMIT", "source-enumeration");
            if (chunks.Count > 0 && chunkBytes + entryBytes.Length + 1 > config.BatchBytes)
            {
                WriteChunk(chunks, Segment(attempt, 0, segments++));
                chunks.Clear();
                chunkBytes = 0;
            }
            chunks.Add(entryBytes);
            chunkBytes += entryBytes.Length + 1;
            if (entry.Kind == "vertex") vertices++; else edges++;
            firstCount++;
            if (firstCount % config.BatchRecords == 0) journal.Report("source-document-reconciliation", vertices, edges);
            if (chunks.Count >= Math.Min(config.BatchRecords, 512))
            {
                WriteChunk(chunks, Segment(attempt, 0, segments++));
                chunks.Clear();
                chunkBytes = 0;
            }
        }
        if (chunks.Count > 0) WriteChunk(chunks, Segment(attempt, 0, segments++));
        foreach (var raw in journal.Documents(1))
        {
            using var parsed = Codec.Parse(raw);
            var entry = codec.Entry(parsed.RootElement);
            var path = EntryPath(index, entry.Key, ".json");
            if (!File.Exists(path)) throw new Blocked("SOURCE_DOCUMENTS_CHANGED", "source-enumeration");
            var expected = Json.Read<EnumerationIndex>(File.ReadAllBytes(path));
            if (expected.Entry != entry || expected.RawSha256 != Json.Hash(raw))
                throw new Blocked("SOURCE_DOCUMENTS_CHANGED", "source-enumeration");
            var seen = EntryPath(index, entry.Key, ".verified");
            if (File.Exists(seen)) throw new Blocked("DUPLICATE_DOCUMENT_IDENTITY", "source-enumeration");
            SafeFiles.WriteNew(seen, []);
            if (entry.Kind == "edge")
            {
                var owner = new IdentityEntry(1, "vertex", entry.Partition, entry.OwnerId!, null, null);
                if (!File.Exists(EntryPath(index, owner.Key, ".json")))
                    throw new Blocked("EDGE_ENDPOINT_NOT_IN_INVENTORY", "source-enumeration");
            }
            secondCount++;
        }
        if (firstCount != secondCount) throw new Blocked("SOURCE_DOCUMENTS_CHANGED", "source-enumeration");
        var pass = 0;
        while (segments > 16)
        {
            var merged = 0;
            for (var start = 0; start < segments; start += 16)
            {
                journal.Guard();
                Merge(Enumerable.Range(start, Math.Min(16, segments - start))
                    .Select(n => Segment(attempt, pass, n)).ToArray(), Segment(attempt, pass + 1, merged++), journal);
            }
            pass++;
            segments = merged;
        }
        var output = SafeFiles.Resolve(journal.DirectoryPath, "identities.ndjson");
        if (File.Exists(output)) throw new Blocked("UNCONFIRMED_ENUMERATION_OUTPUT", "source-enumeration");
        Merge(Enumerable.Range(0, segments).Select(n => Segment(attempt, pass, n)).ToArray(), output, journal);
        journal.Guard();
        var result = new
        {
            schemaVersion = 1, status = "succeeded", runId = config.RunId, inputHash = config.InputHash,
            planHash, sha256 = SafeFiles.HashFile(output), vertices, edges, sourceCountsReconciled = true,
            reservedRequestAttempts = journal.State.Requests, sensitiveArtifact = true,
            edgeScope = "sourceVertex", identityTypes = "string-only", adapter = "cosmos-dotnet-documents",
            sdkVersion = "3.58.0", strategy = "all-partitions-document-feed",
            coverageProof = "two-complete-feeds-identical-per-identity-raw-sha256",
            rawSnapshotHash = journal.RawSnapshotHash(), consistency = config.Consistency,
            requestCharge = Json.DecimalText(journal.State.RequestCharge),
            compatibility = "enumeration observations only; run source fidelity probe before extraction"
        };
        SafeFiles.WriteNew(completedPath, Json.Bytes(result));
        journal.CommitCompletion(completedPath);
        return result;
    }

    private static string EntryPath(string index, string key, string suffix) =>
        SafeFiles.Resolve(index, key[..2] + "/" + key + suffix);
    private static string Segment(string directory, int pass, int segment) =>
        SafeFiles.Resolve(directory, $"sort-{pass}-{segment}.ndjson");
    private static string Key(byte[] line)
    {
        var entry = Json.Read<IdentityEntry>(line);
        return Json.Stable(JsonSerializer.SerializeToElement(new object[]
        {
            entry.Kind == "vertex" ? "0" : "1", entry.Partition, entry.Id,
            entry.EdgeScope ?? "", entry.EdgeScope == "sourceVertex" ? (object)entry.OwnerId! : ""
        }, Json.Options));
    }
    private static void WriteChunk(List<byte[]> entries, string path)
    {
        entries.Sort((a, b) => string.CompareOrdinal(Key(a), Key(b)));
        using var stream = SafeFiles.New(path);
        foreach (var entry in entries) SafeFiles.AppendLine(stream, entry);
        stream.Flush(true);
    }
    private static void Merge(string[] inputs, string output, Journal journal)
    {
        if (inputs.Length > 16) throw new Blocked("SORT_FAN_IN_LIMIT");
        var readers = inputs.Select(p => SafeFiles.Lines(p, 1048576).GetEnumerator()).ToArray();
        try
        {
            var active = readers.Select(reader => reader.MoveNext()).ToArray();
            var keys = readers.Select((reader, i) => active[i] ? Key(reader.Current) : "").ToArray();
            using var stream = SafeFiles.New(output);
            string? previous = null;
            while (true)
            {
                journal.Guard();
                var minimum = -1;
                for (var i = 0; i < readers.Length; i++)
                    if (active[i] && (minimum < 0 || string.CompareOrdinal(keys[i], keys[minimum]) < 0)) minimum = i;
                if (minimum < 0) break;
                if (previous != null && string.CompareOrdinal(previous, keys[minimum]) >= 0)
                    throw new Blocked("DUPLICATE_ENUMERATION_IDENTITY", "source-enumeration");
                previous = keys[minimum];
                SafeFiles.AppendLine(stream, readers[minimum].Current);
                active[minimum] = readers[minimum].MoveNext();
                if (active[minimum]) keys[minimum] = Key(readers[minimum].Current);
            }
            stream.Flush(true);
        }
        finally { foreach (var reader in readers) reader.Dispose(); }
    }
}
