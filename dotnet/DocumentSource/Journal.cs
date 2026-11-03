using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace DocumentSource;

internal sealed record Checkpoint(int SchemaVersion, string RunId, string InputHash, string Binding,
    string InitialProjectBytesSha256, string Mode, string? PlanHash, int Pages, long Requests,
    string? Continuation, bool InFlight, bool FeedComplete, decimal RequestCharge, long ElapsedMillis,
    int Retries, string ReceiptChain, int Pass, long RetryNotBeforeUtcMillis, string? CompletionSha256);
internal sealed record SignedCheckpoint(string RunId, string InputHash, long Requests, long ElapsedMillis,
    bool InFlight, Checkpoint State, string Integrity);
internal sealed record PageReceipt(int Index, string RawSha256, long RawBytes, int Documents,
    decimal RequestCharge, int Status, string? Continuation, string PreviousReceiptHash, int Pass,
    int[] DocumentLengths, string[] DocumentHashes);

internal sealed class Journal : IDisposable
{
    private readonly Config config;
    private readonly FileStream ownership;
    private readonly string checkpointPath;
    private readonly Stopwatch clock = Stopwatch.StartNew();
    private readonly long elapsedBase;
    private readonly long maximumMillis;
    private readonly long maximumRequests;
    private readonly FileStream? manifestOwnership;
    private long lastProgressMillis;
    private string progressStage = "source-document-checkpoint";
    private long observedVertices, observedEdges;
    public string DirectoryPath { get; }
    public Checkpoint State { get; private set; }
    public Action<string, int, int>? Activity { get; init; }
    public Action<string, long, long, long, long>? Progress { get; init; }

    public Journal(Config c, string relative, string mode, string? planHash = null,
        long maximumRequests = long.MaxValue, long? maximumMillis = null)
    {
        config = c;
        this.maximumMillis = Math.Min(c.MaximumOperationMillis, maximumMillis ?? c.MaximumOperationMillis);
        this.maximumRequests = maximumRequests;
        DirectoryPath = SafeFiles.Resolve(c.Root, relative);
        Directory.CreateDirectory(DirectoryPath);
        ownership = new FileStream(SafeFiles.Resolve(DirectoryPath, "worker.lock"), FileMode.OpenOrCreate,
            FileAccess.ReadWrite, FileShare.None);
        try
        {
            if (mode != "check")
            {
                var manifestPath = SafeFiles.Resolve(c.Root, c.Manifest);
                manifestOwnership = new FileStream(manifestPath, FileMode.Open, FileAccess.Read, FileShare.Read);
                if (SafeFiles.HashFile(manifestPath) != c.ManifestHash)
                    throw new Blocked("IDENTITY_MANIFEST_HASH_MISMATCH", "manifest-validation");
            }
            checkpointPath = SafeFiles.Resolve(DirectoryPath, "checkpoint.json");
            if (File.Exists(checkpointPath + ".pending")) throw new Blocked("PENDING_CHECKPOINT", "checkpoint-read");
            if (File.Exists(checkpointPath))
            {
                if (new FileInfo(checkpointPath).Length > 65536) throw new Blocked("CHECKPOINT_SIZE_LIMIT");
                var signed = Json.Read<SignedCheckpoint>(File.ReadAllBytes(checkpointPath));
                State = signed.State;
                if (signed.Integrity != Json.Hash(Json.Bytes(State)) || State.SchemaVersion != 1
                    || signed.RunId != State.RunId || signed.InputHash != State.InputHash
                    || signed.Requests != State.Requests || signed.ElapsedMillis != State.ElapsedMillis
                    || signed.InFlight != State.InFlight
                    || State.RunId != c.RunId || State.InputHash != c.InputHash || State.Binding != c.Binding
                    || State.Mode != mode || State.PlanHash != planHash || State.Pages < 0
                    || State.Requests < State.Pages || State.RequestCharge < 0 || State.ElapsedMillis < 0
                    || State.Retries < 0 || State.Retries > c.MaxRetries + 1
                    || State.InitialProjectBytesSha256.Length != 64)
                    throw new Blocked("CHECKPOINT_INPUT_MISMATCH", "checkpoint-read");
                if (State.InFlight) throw new Blocked("SOURCE_SPEND_UNKNOWN", "checkpoint-read");
            }
            else
            {
                if (Directory.EnumerateFileSystemEntries(DirectoryPath)
                    .Any(p => Path.GetFileName(p) != "worker.lock"))
                    throw new Blocked("UNOWNED_SOURCE_ARTIFACTS", "checkpoint-read");
                State = new(1, c.RunId, c.InputHash, c.Binding, c.ProjectSha, mode, planHash, 0, 0,
                    null, false, false, 0, 0, 0, "", 0, 0, null);
                Save();
            }
            elapsedBase = State.ElapsedMillis;
            Verify();
        }
        catch { manifestOwnership?.Dispose(); ownership.Dispose(); throw; }
    }

    public void Guard()
    {
        if (State.RequestCharge > config.RuBudget)
            throw new Blocked("RU_BUDGET_EXCEEDED", "source-budget");
        if (elapsedBase + clock.ElapsedMilliseconds >= maximumMillis)
            throw new Blocked("OPERATION_DURATION_LIMIT", "operation-budget");
        SafeFiles.Space(config);
        if (clock.ElapsedMilliseconds - lastProgressMillis >= 1000)
            Report(progressStage, observedVertices, observedEdges);
    }
    public void Report(string stage, long vertices = 0, long edges = 0)
    {
        progressStage = stage;
        observedVertices = vertices;
        observedEdges = edges;
        lastProgressMillis = clock.ElapsedMilliseconds;
        Progress?.Invoke(stage, vertices, edges, State.Requests, elapsedBase + clock.ElapsedMilliseconds);
    }
    private void Save()
    {
        State = State with { ElapsedMillis = elapsedBase + clock.ElapsedMilliseconds };
        SafeFiles.Atomic(checkpointPath, Json.Bytes(new SignedCheckpoint(State.RunId, State.InputHash,
            State.Requests, State.ElapsedMillis, State.InFlight, State, Json.Hash(Json.Bytes(State)))));
    }
    public IEnumerable<(PageReceipt Receipt, string Path)> Pages()
    {
        for (var i = 0; i < State.Pages; i++)
        {
            Guard();
            var receiptFile = SafeFiles.Resolve(DirectoryPath, $"raw/{i:D10}.receipt.json");
            if (new FileInfo(receiptFile).Length > 262144) throw new Blocked("RECEIPT_SIZE_LIMIT");
            var receipt = Json.Read<PageReceipt>(File.ReadAllBytes(receiptFile));
            yield return (receipt, SafeFiles.Resolve(DirectoryPath, $"raw/{i:D10}.raw"));
        }
    }
    public void Verify()
    {
        var chain = "";
        decimal charge = 0;
        var i = 0;
        string? token = null;
        foreach (var (receipt, path) in Pages())
        {
            if (receipt.Index != i++ || receipt.PreviousReceiptHash != chain
                || receipt.RequestCharge < 0 || receipt.Documents < 0 || receipt.Documents > config.BatchRecords
                || receipt.Pass < 0 || receipt.Pass > State.Pass
                || receipt.RawBytes < 0 || receipt.RawBytes > config.BatchBytes + config.BatchRecords
                || new FileInfo(path).Length != receipt.RawBytes || SafeFiles.HashFile(path) != receipt.RawSha256)
                throw new Blocked("RAW_SNAPSHOT_INTEGRITY_FAILURE", "checkpoint-read");
            var lines = PageDocuments(receipt, path).LongCount();
            if (lines != receipt.Documents) throw new Blocked("RAW_SNAPSHOT_INTEGRITY_FAILURE", "checkpoint-read");
            charge += receipt.RequestCharge;
            chain = Json.Hash(Json.Bytes(receipt));
            token = receipt.Pass == State.Pass ? receipt.Continuation : null;
        }
        if (charge != State.RequestCharge || chain != State.ReceiptChain || token != State.Continuation
            || State.Requests < State.Pages || State.FeedComplete && State.Continuation != null)
            throw new Blocked("CHECKPOINT_INTEGRITY_FAILURE", "checkpoint-read");
    }
    private async Task Wait(string stage, int millis, CancellationToken token)
    {
        if (millis <= 0) return;
        Guard();
        if (millis >= maximumMillis - elapsedBase - clock.ElapsedMilliseconds)
            throw new Blocked("OPERATION_DURATION_LIMIT", "operation-budget");
        Activity?.Invoke(stage, millis, State.Retries);
        await Task.Delay(millis, token);
    }
    public async Task ReadAll(IReadFeed feed, int pageSize, bool check = false)
    {
        while (!State.FeedComplete)
        {
            Guard();
            if (State.RequestCharge >= config.RuBudget) throw new Blocked("RU_BUDGET_EXHAUSTED", "source-budget");
            if (State.Requests >= maximumRequests) throw new Blocked("SOURCE_ENUMERATION_REQUEST_LIMIT", "source-budget");
            if (State.Retries > config.MaxRetries) throw new Blocked("SOURCE_RETRY_LIMIT", "source-request");
            if (State.Pages > 0 && State.Retries > 0)
            {
                var previous = Json.Read<PageReceipt>(File.ReadAllBytes(
                    SafeFiles.Resolve(DirectoryPath, $"raw/{State.Pages - 1:D10}.receipt.json")));
                if (previous.Status is not (408 or 429 or 449 or 503))
                    throw new Blocked(previous.Status is 401 or 403 ? "SOURCE_ACCESS_DENIED" : "SOURCE_REQUEST_REJECTED", "source-request");
            }
            var retryDue = State.RetryNotBeforeUtcMillis - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (retryDue > 0) await Wait("source-retry-wait", (int)Math.Min(retryDue, 300000), CancellationToken.None);
            if (config.RuPerSecond is { } rate)
            {
                var due = State.RequestCharge / rate * 1000m - elapsedBase - clock.ElapsedMilliseconds;
                while (due > 0)
                {
                    await Wait("source-rate-wait", (int)Math.Min(due, 300000m), CancellationToken.None);
                    due = State.RequestCharge / rate * 1000m - elapsedBase - clock.ElapsedMilliseconds;
                }
            }
            State = State with
            {
                InFlight = true, Requests = State.Requests + 1,
                ElapsedMillis = elapsedBase + clock.ElapsedMilliseconds
            };
            Save();
            if (feed is CosmosFeed cosmos)
            {
                var first = true;
                decimal pendingCharge = 0;
                cosmos.ChargeObserved = charge => pendingCharge = charge;
                cosmos.BeforeRequest = () =>
                {
                    Guard();
                    if (State.RequestCharge + pendingCharge >= config.RuBudget)
                        throw new Blocked("RU_BUDGET_EXHAUSTED", "source-budget");
                    if (config.RuPerSecond is { } transportRate)
                    {
                        var wait = (State.RequestCharge + pendingCharge) / transportRate * 1000m
                            - elapsedBase - clock.ElapsedMilliseconds;
                        while (wait > 0)
                        {
                            Wait("source-rate-wait", (int)Math.Min(wait, 300000m),
                                CancellationToken.None).GetAwaiter().GetResult();
                            wait = (State.RequestCharge + pendingCharge) / transportRate * 1000m
                                - elapsedBase - clock.ElapsedMilliseconds;
                        }
                    }
                    if (first) { first = false; return; }
                    if (State.Requests >= maximumRequests)
                        throw new Blocked("SOURCE_ENUMERATION_REQUEST_LIMIT", "source-budget");
                    State = State with { Requests = State.Requests + 1 };
                    Save();
                };
            }
            Activity?.Invoke("source-request-awaiting-response", 0, State.Retries);
            using var timeout = new CancellationTokenSource((int)Math.Min(config.RequestTimeoutMillis,
                maximumMillis - elapsedBase - clock.ElapsedMilliseconds));
            FeedPage page;
            try { page = await feed.Read(State.Continuation, pageSize, check, timeout.Token); }
            catch { throw new Blocked("SOURCE_SPEND_UNKNOWN", "source-request"); }
            if (page.Charge < 0 || page.Continuation?.Length > 16384)
                throw new Blocked("SOURCE_SPEND_UNKNOWN", "source-response");
            Guard();
            var success = page.Status is >= 200 and <= 299;
            if (!success && page.Documents.Length != 0) throw new Blocked("INVALID_FEED_PAGE", "source-response");
            var index = State.Pages;
            var rawPath = SafeFiles.Resolve(DirectoryPath, $"raw/{index:D10}.raw");
            using (var stream = SafeFiles.New(rawPath))
            {
                long total = 0;
                foreach (var document in page.Documents)
                {
                    // Raw evidence is durable before decoding, including unsupported document shapes.
                    total += document.Length + 1;
                    if (total > config.BatchBytes + pageSize) throw new Blocked("PAGE_SIZE_LIMIT", "source-page");
                    SafeFiles.AppendLine(stream, document);
                }
                stream.Flush(true);
            }
            var continuation = success ? check ? null : page.Continuation : State.Continuation;
            var receipt = new PageReceipt(index, SafeFiles.HashFile(rawPath), new FileInfo(rawPath).Length,
                page.Documents.Length, page.Charge, page.Status, continuation, State.ReceiptChain, State.Pass,
                page.Documents.Select(raw => raw.Length).ToArray(), page.Documents.Select(Json.Hash).ToArray());
            SafeFiles.WriteNew(SafeFiles.Resolve(DirectoryPath, $"raw/{index:D10}.receipt.json"), Json.Bytes(receipt));
            State = State with
            {
                Pages = index + 1, Continuation = continuation, InFlight = false,
                FeedComplete = success && (check || continuation == null),
                RequestCharge = State.RequestCharge + page.Charge,
                ElapsedMillis = elapsedBase + clock.ElapsedMilliseconds,
                Retries = success ? 0 : State.Retries + 1,
                ReceiptChain = Json.Hash(Json.Bytes(receipt)),
                RetryNotBeforeUtcMillis = success ? 0 : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                    + Math.Clamp(page.RetryMillis > 0 ? page.RetryMillis : 1000, 1, 300000)
            };
            Save();
            Activity?.Invoke("source-request-completed", 0, State.Retries);
            Report("source-document-page", observedVertices, observedEdges);
            if (State.RequestCharge > config.RuBudget) throw new Blocked("RU_BUDGET_EXCEEDED", "source-budget");
            if (!success)
            {
                if (page.Status is not (408 or 429 or 449 or 503))
                    throw new Blocked(page.Status is 401 or 403 ? "SOURCE_ACCESS_DENIED" : "SOURCE_REQUEST_REJECTED", "source-request");
                if (State.Retries > config.MaxRetries) throw new Blocked("SOURCE_RETRY_LIMIT", "source-request");
            }
        }
        if (State.RequestCharge > config.RuBudget) throw new Blocked("RU_BUDGET_EXCEEDED", "source-budget");
    }
    public void BeginVerificationPass()
    {
        if (!State.FeedComplete || State.Pass != 0) throw new Blocked("INVALID_VERIFICATION_STATE");
        State = State with { Pass = 1, Continuation = null, FeedComplete = false };
        Save();
    }
    public void CommitCompletion(string path)
    {
        Guard();
        if (!State.FeedComplete || State.InFlight) throw new Blocked("INVALID_VERIFICATION_STATE");
        State = State with { CompletionSha256 = SafeFiles.HashFile(path) };
        Save();
    }
    public IEnumerable<byte[]> Documents(int pass = 0)
    {
        foreach (var (receipt, path) in Pages())
        {
            if (receipt.Status is < 200 or > 299 || receipt.Pass != pass) continue;
            foreach (var line in PageDocuments(receipt, path))
            {
                Guard();
                yield return line;
            }
        }
    }
    private IEnumerable<byte[]> PageDocuments(PageReceipt receipt, string path)
    {
        if (receipt.DocumentLengths.Length != receipt.Documents || receipt.DocumentHashes.Length != receipt.Documents)
            throw new Blocked("RAW_SNAPSHOT_INTEGRITY_FAILURE", "snapshot-verification");
        using var stream = File.OpenRead(path);
        for (var i = 0; i < receipt.Documents; i++)
        {
            var length = receipt.DocumentLengths[i];
            if (length < 1 || length > config.MaxRecordBytes)
                throw new Blocked("RECORD_SIZE_LIMIT", "snapshot-verification");
            var bytes = new byte[length];
            stream.ReadExactly(bytes);
            if (stream.ReadByte() != 10 || Json.Hash(bytes) != receipt.DocumentHashes[i])
                throw new Blocked("RAW_SNAPSHOT_INTEGRITY_FAILURE", "snapshot-verification");
            yield return bytes;
        }
        if (stream.ReadByte() != -1) throw new Blocked("RAW_SNAPSHOT_INTEGRITY_FAILURE", "snapshot-verification");
    }
    public string RawSnapshotHash()
    {
        using var sha = System.Security.Cryptography.IncrementalHash.CreateHash(System.Security.Cryptography.HashAlgorithmName.SHA256);
        foreach (var raw in Documents()) { sha.AppendData(raw); sha.AppendData("\n"u8); }
        return Convert.ToHexStringLower(sha.GetHashAndReset());
    }
    public void Dispose()
    {
        try
        {
            if (!State.InFlight && !File.Exists(checkpointPath + ".pending")) Save();
        }
        finally { manifestOwnership?.Dispose(); ownership.Dispose(); }
    }
}
