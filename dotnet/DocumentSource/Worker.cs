using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace DocumentSource;

internal sealed record Completion(int SchemaVersion, string RunId, string InputHash, string Binding,
    string SourceManifestHash, string CanonicalPath, string CanonicalSha256, string RawSnapshotHash,
    long ObservedVertices, long ObservedEdges, string DocumentFidelity, string[] Unobservable,
    string Consistency, string RequestCharge, string IndexPath);
internal sealed record SignedCompletion(Completion Result, string Integrity);

internal sealed class Worker(Config config, Func<IReadFeed> feedFactory)
{
    public Action<string, int, int>? Activity { get; init; }
    public Action<string, long, long, long, long>? Progress { get; init; }
    private Journal Open(string suffix, string mode) => new(config, config.CacheRelative + "/" + suffix, mode)
        { Activity = Activity, Progress = Progress };

    public async Task<object> Check()
    {
        using var journal = new Journal(config, config.CacheRelative + "/check-" + Guid.NewGuid().ToString("N"),
            "check", maximumRequests: 16)
            { Activity = Activity, Progress = Progress };
        if (!journal.State.FeedComplete)
        {
            using var feed = feedFactory();
            await journal.ReadAll(feed, 1, true);
        }
        return new
        {
            status = "succeeded", sourceReadAccess = true, adapter = "cosmos-dotnet-documents",
            sdkVersion = "3.58.0", requestCharge = Json.DecimalText(journal.State.RequestCharge),
            compatibility = "not-established-by-connectivity-check"
        };
    }

    public async Task<object> Probe(string reportRelative)
    {
        var report = SafeFiles.Resolve(config.Root, reportRelative);
        if (File.Exists(report)) throw new Blocked("ARTIFACT_ALREADY_EXISTS", "report-write");
        Journal? journal = null;
        try
        {
            Authorization.RequireModes(config);
            journal = Open("snapshot", "probe");
            Completion result;
            var completed = SafeFiles.Resolve(journal.DirectoryPath, "complete.json");
            if (File.Exists(completed)) result = VerifyComplete(journal);
            else
            {
                if (!journal.State.FeedComplete)
                {
                    using var feed = feedFactory();
                    await journal.ReadAll(feed, config.BatchRecords);
                }
                result = Convert(journal);
                SafeFiles.WriteNew(completed, Json.Bytes(new SignedCompletion(result, Json.Hash(Json.Bytes(result)))));
                journal.CommitCompletion(completed);
            }
            var envelope = Envelope(result);
            journal.Guard();
            SafeFiles.WriteNew(report, Json.Bytes(envelope));
            return new { status = "succeeded", artifactSha256 = SafeFiles.HashFile(report) };
        }
        catch (Blocked blocked)
        {
            if (!File.Exists(report))
                SafeFiles.WriteNew(report, Json.Bytes(new
                {
                    schemaVersion = 1, runId = config.RunId, inputHash = config.InputHash,
                    operation = "source-compatibility", status = "blocked", sensitiveArtifact = true,
                    result = new
                    {
                        status = "blocked", adapter = "cosmos-dotnet-documents", sdkVersion = "3.58.0",
                        storageProfile = config.Profile, sourceManifestHash = config.ManifestHash,
                        code = blocked.Code, stage = blocked.Stage, sourceCountsReconciled = false,
                        gremlinSemanticCompatibility = "not-established", partialRawSnapshot = true,
                        requestCharge = journal == null ? null : Json.DecimalText(journal.State.RequestCharge),
                        spendUnknown = journal?.State.InFlight == true || blocked.Code == "SOURCE_SPEND_UNKNOWN"
                    }
                }));
            blocked.ArtifactSha256 = SafeFiles.HashFile(report);
            throw;
        }
        finally { journal?.Dispose(); }
    }
    private object Envelope(Completion result) => new
    {
        schemaVersion = 1, runId = config.RunId, inputHash = config.InputHash,
        operation = "source-compatibility", status = "succeeded", sensitiveArtifact = true,
        result = new
        {
            status = "succeeded", adapter = "cosmos-dotnet-documents", sdkVersion = "3.58.0",
            storageProfile = config.Profile, sourceManifestHash = config.ManifestHash,
            documentFidelity = result.DocumentFidelity, gremlinSemanticCompatibility = "not-established",
            sourceCountsReconciled = true, observedVertices = result.ObservedVertices, observedEdges = result.ObservedEdges,
            rawSnapshotHash = result.RawSnapshotHash, canonicalPath = result.CanonicalPath,
            canonicalSha256 = result.CanonicalSha256, unobservable = result.Unobservable,
            edgeScopes = result.ObservedEdges > 0 ? new[] { "sourceVertex" } : [],
            consistency = config.Consistency, liveFreshnessEstablished = false,
            requestCharge = result.RequestCharge
        }
    };

    private IEnumerable<IdentityEntry> Inventory()
    {
        foreach (var bytes in SafeFiles.Lines(SafeFiles.Resolve(config.Root, config.Manifest), config.MaxRecordBytes))
        {
            var entry = Json.Read<IdentityEntry>(bytes);
            entry.Validate();
            yield return entry;
        }
    }
    private static string IndexFile(string root, string key, string suffix) =>
        SafeFiles.Resolve(root, key[..2] + "/" + key + suffix);
    private void CheckEndpoint(string index, Identity identity)
    {
        var entry = new IdentityEntry(1, "vertex", identity.Partition, identity.Id, null, null);
        if (identity.Scope != config.Scope || !File.Exists(IndexFile(index, entry.Key, ".identity.json")))
            throw new Blocked("EDGE_ENDPOINT_NOT_IN_INVENTORY", "source-reconciliation");
    }
    private Completion Convert(Journal journal)
    {
        journal.Report("source-document-reconciliation");
        journal.Guard();
        var attemptRelative = config.CacheRelative + "/snapshot/conversion-" + Guid.NewGuid().ToString("N");
        var indexRelative = attemptRelative + "/index";
        var index = SafeFiles.Resolve(config.Root, indexRelative);
        long expected = 0, observed = 0, vertices = 0, edges = 0;
        foreach (var entry in Inventory())
        {
            journal.Guard();
            var path = IndexFile(index, entry.Key, ".identity.json");
            if (File.Exists(path)) throw new Blocked("DUPLICATE_MANIFEST_IDENTITY", "source-reconciliation");
            SafeFiles.WriteNew(path, Json.Bytes(entry));
            expected++;
        }
        var codec = new Codec(config);
        var reduced = false;
        foreach (var raw in journal.Documents())
        {
            var record = codec.Decode(raw);
            var entry = new IdentityEntry(1, record.Kind, record.Identity.Partition, record.Identity.Id,
                record.Source?.Id, record.EdgeScope);
            var identityFile = IndexFile(index, entry.Key, ".identity.json");
            if (!File.Exists(identityFile) || Json.Read<IdentityEntry>(File.ReadAllBytes(identityFile)) != entry)
                throw new Blocked("EXTRA_DOCUMENT_IDENTITY", "source-reconciliation");
            if (record.Kind == "edge")
            {
                CheckEndpoint(index, record.Source!);
                CheckEndpoint(index, record.Destination!);
                edges++;
            }
            else vertices++;
            var recordFile = IndexFile(index, entry.Key, ".record.json");
            if (File.Exists(recordFile)) throw new Blocked("DUPLICATE_DOCUMENT_IDENTITY", "source-reconciliation");
            var bytes = Json.Bytes(record);
            if (bytes.Length > config.MaxRecordBytes) throw new Blocked("CANONICAL_RECORD_SIZE_LIMIT", "document-decode");
            SafeFiles.WriteNew(recordFile, bytes);
            observed++;
            if (observed % config.BatchRecords == 0) journal.Report("source-document-reconciliation", vertices, edges);
            reduced |= record.Provenance.Fidelity == "reduced";
        }
        if (observed != expected) throw new Blocked("MISSING_DOCUMENT_IDENTITIES", "source-reconciliation");
        if (reduced && config.Fidelity == "strict")
            throw new Blocked("DOCUMENT_FIDELITY_UNOBSERVABLE", "source-fidelity");
        var canonicalRelative = attemptRelative + "/canonical.ndjson";
        using (var output = SafeFiles.New(SafeFiles.Resolve(config.Root, canonicalRelative)))
        {
            foreach (var entry in Inventory())
            {
                journal.Guard();
                var bytes = File.ReadAllBytes(IndexFile(index, entry.Key, ".record.json"));
                SafeFiles.AppendLine(output, bytes);
            }
            output.Flush(true);
        }
        var canonicalPath = SafeFiles.Resolve(config.Root, canonicalRelative);
        return new(1, config.RunId, config.InputHash, config.Binding, config.ManifestHash,
            canonicalRelative, SafeFiles.HashFile(canonicalPath), journal.RawSnapshotHash(), vertices, edges,
            reduced ? "reduced" : "complete", codec.Unobservable.ToArray(), config.Consistency,
            Json.DecimalText(journal.State.RequestCharge), indexRelative);
    }
    private Completion VerifyComplete(Journal journal)
    {
        if (!journal.State.FeedComplete || journal.State.InFlight)
            throw new Blocked("PROBE_CACHE_INCOMPLETE", "snapshot-verification");
        var file = SafeFiles.Resolve(journal.DirectoryPath, "complete.json");
        if (!File.Exists(file) || new FileInfo(file).Length > 65536)
            throw new Blocked("PROBE_CACHE_MISSING", "snapshot-verification");
        if (journal.State.CompletionSha256 == null || SafeFiles.HashFile(file) != journal.State.CompletionSha256)
            throw new Blocked("PROBE_CACHE_INTEGRITY_FAILURE", "snapshot-verification");
        var signed = Json.Read<SignedCompletion>(File.ReadAllBytes(file));
        var result = signed.Result;
        if (signed.Integrity != Json.Hash(Json.Bytes(result)) || result.SchemaVersion != 1
            || result.RunId != config.RunId || result.InputHash != config.InputHash || result.Binding != config.Binding
            || result.SourceManifestHash != config.ManifestHash || result.Consistency != config.Consistency
            || result.DocumentFidelity is not ("complete" or "reduced")
            || config.Fidelity == "strict" && result.DocumentFidelity != "complete"
            || !result.CanonicalPath.StartsWith(config.CacheRelative + "/snapshot/conversion-", StringComparison.Ordinal)
            || !result.IndexPath.StartsWith(config.CacheRelative + "/snapshot/conversion-", StringComparison.Ordinal)
            || SafeFiles.HashFile(SafeFiles.Resolve(config.Root, result.CanonicalPath)) != result.CanonicalSha256
            || journal.RawSnapshotHash() != result.RawSnapshotHash)
            throw new Blocked("PROBE_CACHE_INTEGRITY_FAILURE", "snapshot-verification");
        using var entries = Inventory().GetEnumerator();
        long vertices = 0, edges = 0;
        foreach (var bytes in SafeFiles.Lines(SafeFiles.Resolve(config.Root, result.CanonicalPath), config.MaxRecordBytes))
        {
            journal.Guard();
            if (!entries.MoveNext()) throw new Blocked("PROBE_CACHE_INVENTORY_MISMATCH", "snapshot-verification");
            var record = Json.Read<GraphRecord>(bytes);
            var entry = new IdentityEntry(1, record.Kind, record.Identity.Partition, record.Identity.Id,
                record.Source?.Id, record.EdgeScope);
            if (entry != entries.Current || record.Identity.Scope != config.Scope
                || record.Provenance.SourceManifestHash != config.ManifestHash
                || record.Provenance.Consistency != config.Consistency)
                throw new Blocked("PROBE_CACHE_INVENTORY_MISMATCH", "snapshot-verification");
            if (record.Kind == "vertex") vertices++; else edges++;
        }
        if (entries.MoveNext() || vertices != result.ObservedVertices || edges != result.ObservedEdges)
            throw new Blocked("PROBE_CACHE_INVENTORY_MISMATCH", "snapshot-verification");
        return result;
    }
    public object Extract(string outputRelative)
    {
        Authorization.RequireModes(config);
        var output = SafeFiles.Resolve(config.Root, outputRelative);
        var manifest = SafeFiles.Resolve(config.Root, outputRelative + ".manifest.json");
        if (File.Exists(output) || File.Exists(manifest)) throw new Blocked("ARTIFACT_ALREADY_EXISTS", "extract-write");
        var cache = SafeFiles.Resolve(config.Root, config.CacheRelative + "/snapshot/checkpoint.json");
        if (!File.Exists(cache)) throw new Blocked("PROBE_CACHE_MISSING", "snapshot-verification");
        using var journal = Open("snapshot", "probe");
        var result = VerifyComplete(journal);
        using (var source = File.OpenRead(SafeFiles.Resolve(config.Root, result.CanonicalPath)))
        using (var target = SafeFiles.New(output))
        {
            var buffer = new byte[65536];
            int count;
            while ((count = source.Read(buffer)) > 0)
            {
                journal.Guard();
                target.Write(buffer, 0, count);
            }
            target.Flush(true);
        }
        var hash = SafeFiles.HashFile(output);
        if (hash != result.CanonicalSha256) throw new Blocked("OUTPUT_HASH_MISMATCH", "extract-write");
        SafeFiles.WriteNew(manifest, Json.Bytes(new
        {
            schemaVersion = 1, status = "succeeded", runId = config.RunId, inputHash = config.InputHash,
            identityManifestHash = config.ManifestHash, bytes = new FileInfo(output).Length, sha256 = hash,
            records = result.ObservedVertices + result.ObservedEdges, observedVertices = result.ObservedVertices,
            observedEdges = result.ObservedEdges, sensitiveArtifact = true, consistency = config.Consistency,
            adapter = "cosmos-dotnet-documents", sdkVersion = "3.58.0", documentFidelity = result.DocumentFidelity,
            rawSnapshotHash = result.RawSnapshotHash, liveFreshnessEstablished = false
        }));
        return new { status = "succeeded", sourceHash = hash };
    }
}
