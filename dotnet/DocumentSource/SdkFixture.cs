using System.Net;
using System.Text;
using System.Text.Json;

namespace DocumentSource;

internal sealed class SdkFixture : HttpMessageHandler
{
    public List<string> Requests { get; } = [];
    public int DocumentRequests { get; private set; }
    private int rangeRequests;
    public bool SawContinuation { get; private set; }
    public bool ReturnThrottle { get; init; }
    public bool MultipleRanges { get; init; }
    public bool OmitDocumentCharge { get; init; }
    public bool RangeOneObserved { get; private set; }
    public string? UnexpectedPath { get; private set; }
    public Action<string>? Observe { get; init; }
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (request.Method != HttpMethod.Get) throw new Blocked("SDK_FIXTURE_MUTATION_ATTEMPT", "self-test-sdk");
        var path = request.RequestUri!.AbsolutePath;
        Observe?.Invoke(path == "/" ? "account" : path.EndsWith("/docs", StringComparison.Ordinal) ? "documents"
            : path.EndsWith("/pkranges", StringComparison.Ordinal) ? "ranges"
            : path.EndsWith("/colls/fixture-container", StringComparison.Ordinal) ? "container" : "unexpected");
        Requests.Add(request.Method.Method + " " + path);
        const string collectionRid = "IXYFAOHEBPM=";
        string body;
        var status = HttpStatusCode.OK;
        decimal? charge = null;
        string? continuation = null;
        if (path == "/")
        {
            body = """{"id":"fixture","_rid":"fixture","enableMultipleWriteLocations":false,"writableLocations":[{"name":"fixture","databaseAccountEndpoint":"http://127.0.0.1:12345/"}],"readableLocations":[{"name":"fixture","databaseAccountEndpoint":"http://127.0.0.1:12345/"}],"userConsistencyPolicy":{"defaultConsistencyLevel":"Session","maxIntervalInSeconds":5,"maxStalenessPrefix":100}}""";
        }
        else if (path.EndsWith("/colls/fixture-container", StringComparison.Ordinal))
        {
            body = """{"id":"fixture-container","_rid":"IXYFAOHEBPM=","_self":"dbs/IXYFAA==/colls/IXYFAOHEBPM=/","_etag":"fixture","partitionKey":{"paths":["/pk"],"kind":"Hash","version":2},"indexingPolicy":{"automatic":true,"indexingMode":"consistent","includedPaths":[{"path":"/*"}],"excludedPaths":[]}}""";
        }
        else if (path.EndsWith("/pkranges", StringComparison.Ordinal))
        {
            if (rangeRequests++ == 0)
                body = MultipleRanges
                    ? """{"_rid":"IXYFAOHEBPM=","PartitionKeyRanges":[{"id":"0","minInclusive":"","maxExclusive":"80","_rid":"IXYFAOHEBPMCAAAAAAAAUA==","throughputFraction":0.5,"status":"online","parents":[]},{"id":"1","minInclusive":"80","maxExclusive":"FF","_rid":"IXYFAOHEBPMDAAAAAAAAUA==","throughputFraction":0.5,"status":"online","parents":[]}],"_count":2}"""
                    : """{"_rid":"IXYFAOHEBPM=","PartitionKeyRanges":[{"id":"0","minInclusive":"","maxExclusive":"FF","_rid":"IXYFAOHEBPMCAAAAAAAAUA==","_self":"dbs/IXYFAA==/colls/IXYFAOHEBPM=/pkranges/IXYFAOHEBPMCAAAAAAAAUA==/","throughputFraction":1,"status":"online","parents":[]}],"_count":1}""";
            else { body = ""; status = HttpStatusCode.NotModified; }
        }
        else if (path.EndsWith("/docs", StringComparison.Ordinal))
        {
            DocumentRequests++;
            Observe?.Invoke(request.Headers.Contains("x-ms-continuation") ? "continuation-header-present"
                : request.Headers.Contains("If-None-Match") ? "if-none-match-present" : "no-continuation-header");
            SawContinuation |= request.Headers.Contains("x-ms-continuation")
                && request.Headers.GetValues("x-ms-continuation").Single().Contains("fixture-next", StringComparison.Ordinal);
            if (request.Headers.Contains("x-ms-continuation"))
            {
                var observed = request.Headers.GetValues("x-ms-continuation").Single();
                Observe?.Invoke(observed.Contains("fixture-ranges", StringComparison.Ordinal) ? "continuation-is-etag"
                    : observed.Contains("fixture-next", StringComparison.Ordinal) ? "continuation-is-next" : "continuation-is-other");
            }
            if (ReturnThrottle)
            {
                status = (HttpStatusCode)429;
                body = """{"code":"TooManyRequests","message":"fixture"}""";
                charge = 0.75m;
            }
            else
            {
                var rangeOne = request.Headers.TryGetValues("x-ms-documentdb-partitionkeyrangeid", out var ranges)
                    && ranges.Single().Split(',').Last() == "1";
                RangeOneObserved |= rangeOne;
                var id = rangeOne ? "sdk-third" : SawContinuation ? "sdk-second" : "sdk-first";
                body = JsonSerializer.Serialize(new
                {
                    _rid = collectionRid, Documents = new[] { new { id, pk = "fixture", label = "person" } }, _count = 1
                });
                charge = rangeOne ? 3.75m : SawContinuation ? 2.5m : 1.25m;
                continuation = rangeOne || SawContinuation ? null : "fixture-next";
            }
        }
        else
        {
            UnexpectedPath = path;
            throw new Blocked("SDK_FIXTURE_UNEXPECTED_ROUTE", "self-test-sdk");
        }
        var response = new HttpResponseMessage(status)
        {
            RequestMessage = request, Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        response.Headers.TryAddWithoutValidation("x-ms-activity-id", "00000000-0000-0000-0000-000000000001");
        response.Headers.TryAddWithoutValidation("x-ms-gatewayversion", "version=2.14.0");
        response.Headers.TryAddWithoutValidation("x-ms-serviceversion", "version=2.14.0");
        response.Headers.TryAddWithoutValidation("x-ms-schemaversion", "1.20");
        response.Headers.TryAddWithoutValidation("x-ms-session-token", "0:-1#1");
        response.Headers.TryAddWithoutValidation("x-ms-item-count", "1");
        response.Headers.TryAddWithoutValidation("etag", "\"fixture-ranges\"");
        if (charge.HasValue && !OmitDocumentCharge)
            response.Headers.TryAddWithoutValidation("x-ms-request-charge", Json.DecimalText(charge.Value));
        if (continuation != null) response.Headers.TryAddWithoutValidation("x-ms-continuation", continuation);
        if (ReturnThrottle && path.EndsWith("/docs", StringComparison.Ordinal))
            response.Headers.TryAddWithoutValidation("x-ms-retry-after-ms", "37");
        return Task.FromResult(response);
    }

    public static async Task<int> Verify(Config config)
    {
        Environment.SetEnvironmentVariable("GRAPH_DOCUMENT_SELF_TEST_CREDENTIAL",
            Convert.ToBase64String(Encoding.UTF8.GetBytes("only-a-fixture-key-never-a-live-account-key")));
        try
        {
            var log = Path.Combine(config.Root, "sdk-fixture-requests.ndjson");
            var transport = new SdkFixture { Observe = route => File.AppendAllText(log, route + "\n") };
            using (var feed = new CosmosFeed(config, transport))
            {
                var attempts = 0;
                feed.BeforeRequest = () => attempts++;
                using var timeout = new CancellationTokenSource(10000);
                var first = await feed.Read(null, 1, false, timeout.Token).WaitAsync(TimeSpan.FromSeconds(15));
                if (first.Status != 200 || first.Charge != 1.25m || first.Documents.Length != 1 || first.Continuation == null)
                    throw new Blocked("SDK_FIXTURE_FIRST_PAGE_FAILED", "self-test-sdk");
                var second = await feed.Read(first.Continuation, 1, false, timeout.Token).WaitAsync(TimeSpan.FromSeconds(15));
                if (!transport.SawContinuation) throw new Blocked("SDK_FIXTURE_TOKEN_FORWARDING_FAILED", "self-test-sdk");
                if (second.Status != 200 || second.Documents.Length != 1) throw new Blocked("SDK_FIXTURE_SECOND_PAGE_FAILED", "self-test-sdk");
                if (second.Charge != 2.5m) throw new Blocked("SDK_FIXTURE_SECOND_CHARGE_FAILED", "self-test-sdk");
                if (second.Continuation != null) throw new Blocked("SDK_FIXTURE_END_OF_FEED_FAILED", "self-test-sdk");
                if (transport.DocumentRequests != 2 || attempts != transport.Requests.Count)
                    throw new Blocked("SDK_FIXTURE_REQUEST_ACCOUNTING_FAILED", "self-test-sdk");
            }
            var throttle = new SdkFixture { ReturnThrottle = true, Observe = route => File.AppendAllText(log, route + "\n") };
            using (var feed = new CosmosFeed(config, throttle))
            {
                using var timeout = new CancellationTokenSource(10000);
                var page = await feed.Read(null, 1, false, timeout.Token).WaitAsync(TimeSpan.FromSeconds(15));
                if (page.Status != 429 || page.Charge != 0.75m || page.RetryMillis != 37 || throttle.DocumentRequests != 1)
                    throw new Blocked("SDK_FIXTURE_RETRY_ACCOUNTING_FAILED", "self-test-sdk");
            }
            var multi = new SdkFixture { MultipleRanges = true };
            using (var feed = new CosmosFeed(config, multi))
            {
                string? continuation = null;
                var ids = new HashSet<string>(StringComparer.Ordinal);
                decimal totalCharge = 0;
                var pages = 0;
                do
                {
                    if (++pages > 10) throw new Blocked("SDK_FIXTURE_MULTIRANGE_CONTINUATION_FAILED", "self-test-sdk");
                    using var timeout = new CancellationTokenSource(10000);
                    var page = await feed.Read(continuation, 1, false, timeout.Token).WaitAsync(TimeSpan.FromSeconds(15));
                    foreach (var raw in page.Documents)
                    {
                        using var document = JsonDocument.Parse(raw);
                        if (!ids.Add(Json.Text(document.RootElement, "id")))
                            throw new Blocked("SDK_FIXTURE_MULTIRANGE_DUPLICATE", "self-test-sdk");
                    }
                    totalCharge += page.Charge;
                    continuation = page.Continuation;
                } while (continuation != null);
                if (!multi.RangeOneObserved || ids.Count != 3 || totalCharge != 7.5m)
                    throw new Blocked("SDK_FIXTURE_MULTIRANGE_COVERAGE_FAILED", "self-test-sdk");
            }
            var durableTransport = new SdkFixture { MultipleRanges = true };
            var durablePath = "sdk-journal-" + Guid.NewGuid().ToString("N");
            using (var journal = new Journal(config, durablePath, "sdk-fixture", maximumRequests: 16))
            using (var feed = new CosmosFeed(config, durableTransport))
            {
                await journal.ReadAll(feed, 1).WaitAsync(TimeSpan.FromSeconds(15));
                if (journal.State.RequestCharge != 7.5m || journal.State.Requests != durableTransport.Requests.Count
                    || journal.Documents().Count() != 3)
                    throw new Blocked("SDK_FIXTURE_DURABLE_ACCOUNTING_FAILED", "self-test-sdk");
            }
            using (var restored = new Journal(config, durablePath, "sdk-fixture", maximumRequests: 16))
                if (!restored.State.FeedComplete || restored.State.RequestCharge != 7.5m)
                    throw new Blocked("SDK_FIXTURE_DURABLE_RESUME_FAILED", "self-test-sdk");
            var missingPath = "sdk-unknown-" + Guid.NewGuid().ToString("N");
            var unknownRejected = false;
            using (var journal = new Journal(config, missingPath, "sdk-fixture", maximumRequests: 16))
            using (var feed = new CosmosFeed(config, new SdkFixture { OmitDocumentCharge = true }))
            {
                try { await journal.ReadAll(feed, 1).WaitAsync(TimeSpan.FromSeconds(15)); }
                catch (Blocked blocked) when (blocked.Code == "SOURCE_SPEND_UNKNOWN") { unknownRejected = true; }
                if (!unknownRejected || !journal.State.InFlight)
                    throw new Blocked("SDK_FIXTURE_UNKNOWN_SPEND_FAILED", "self-test-sdk");
            }
            try
            {
                using var restored = new Journal(config, missingPath, "sdk-fixture", maximumRequests: 16);
                throw new Blocked("SDK_FIXTURE_UNKNOWN_RESTART_FAILED", "self-test-sdk");
            }
            catch (Blocked blocked) when (blocked.Code == "SOURCE_SPEND_UNKNOWN") { }
            return 7;
        }
        catch (Microsoft.Azure.Cosmos.CosmosException failure)
        {
            throw new Blocked("SDK_FIXTURE_COSMOS_FAILURE", failure.InnerException switch
            {
                Blocked => "self-test-sdk-route",
                ArgumentException => "self-test-sdk-argument",
                _ => "self-test-sdk-response"
            });
        }
        catch (TypeInitializationException)
        {
            throw new Blocked("SDK_FIXTURE_TYPE_INITIALIZATION_FAILURE", "self-test-sdk");
        }
        catch (InvalidOperationException)
        {
            throw new Blocked("SDK_FIXTURE_INVALID_OPERATION", "self-test-sdk");
        }
        finally { Environment.SetEnvironmentVariable("GRAPH_DOCUMENT_SELF_TEST_CREDENTIAL", null); }
    }
}
