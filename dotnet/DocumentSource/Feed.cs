using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.Azure.Cosmos;

namespace DocumentSource;

internal sealed record FeedPage(byte[][] Documents, string? Continuation, decimal Charge, int Status, int RetryMillis);
internal interface IReadFeed : IDisposable
{
    Task<FeedPage> Read(string? continuation, int pageSize, bool connectivity, CancellationToken token);
}

internal sealed class EndpointHandler(Uri endpoint) : DelegatingHandler
{
    public Action? BeforeRequest { get; set; }
    public Func<HttpRequestMessage, bool>? AllowedRead { get; set; }
    public Func<HttpRequestMessage, HttpResponseMessage, CancellationToken, Task>? AfterResponse { get; set; }
    public Action? TransportFailed { get; set; }
    private readonly SemaphoreSlim serial = new(1, 1);
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var uri = request.RequestUri;
        if (uri == null || uri.Scheme != endpoint.Scheme || uri.Host != endpoint.Host || uri.Port != endpoint.Port
            || request.Method != HttpMethod.Get || AllowedRead?.Invoke(request) != true)
            throw new Blocked("ENDPOINT_SCOPE_VIOLATION", "source-request");
        await serial.WaitAsync(cancellationToken);
        try
        {
            BeforeRequest?.Invoke();
            var response = await base.SendAsync(request, cancellationToken);
            try
            {
                if (AfterResponse != null) await AfterResponse(request, response, cancellationToken);
                return response;
            }
            catch { response.Dispose(); throw; }
        }
        catch { TransportFailed?.Invoke(); throw; }
        finally { serial.Release(); }
    }
}

internal sealed class CosmosFeed : IReadFeed
{
    private CosmosClient? client;
    private Container? container;
    private readonly string credential;
    private readonly Config config;
    private readonly HttpClient http;
    private readonly EndpointHandler endpointHandler;
    private decimal transportCharge;
    private bool poisoned;
    private bool active;
    private string? ridCollection;
    private int transportErrors;
    public Action? BeforeRequest { private get; set; }
    public Action<decimal>? ChargeObserved { get; set; }
    private readonly X509Certificate2Collection roots = [];

    public CosmosFeed(Config c, HttpMessageHandler? fixtureTransport = null)
    {
        config = c;
        var secret = Environment.GetEnvironmentVariable(c.AuthenticationRef[4..]);
        if (string.IsNullOrWhiteSpace(secret)) throw new Blocked("CREDENTIAL_UNAVAILABLE", "source-authentication");
        credential = secret;
        var handler = new HttpClientHandler { AllowAutoRedirect = false };
        if (c.CustomCaRef != null)
        {
            var file = Environment.GetEnvironmentVariable(c.CustomCaRef[4..]);
            if (string.IsNullOrWhiteSpace(file)) throw new Blocked("CUSTOM_CA_UNAVAILABLE", "source-tls");
            roots.ImportFromPemFile(file);
            if (roots.Count == 0) throw new Blocked("CUSTOM_CA_UNAVAILABLE", "source-tls");
            handler.ServerCertificateCustomValidationCallback = (_, certificate, peerChain, errors) =>
            {
                if (certificate == null || (errors & (SslPolicyErrors.RemoteCertificateNameMismatch
                    | SslPolicyErrors.RemoteCertificateNotAvailable)) != 0) return false;
                using var chain = new X509Chain();
                chain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
                chain.ChainPolicy.CustomTrustStore.AddRange(roots);
                if (peerChain != null)
                    foreach (var item in peerChain.ChainElements.Cast<X509ChainElement>().Skip(1))
                        chain.ChainPolicy.ExtraStore.Add(item.Certificate);
                chain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
                chain.ChainPolicy.DisableCertificateDownloads = true;
                chain.ChainPolicy.ApplicationPolicy.Add(new System.Security.Cryptography.Oid("1.3.6.1.5.5.7.3.1"));
                return chain.Build(certificate);
            };
        }
        endpointHandler = new EndpointHandler(c.Endpoint) { InnerHandler = fixtureTransport ?? handler };
        var collectionPath = "/dbs/" + c.Database + "/colls/" + c.Container;
        bool IsCollection(string path) => path == collectionPath || ridCollection != null && path == ridCollection;
        bool IsChild(string path, string suffix) => path == collectionPath + suffix
            || ridCollection != null && path == ridCollection + suffix;
        endpointHandler.AllowedRead = request =>
        {
            var path = Uri.UnescapeDataString(request.RequestUri!.AbsolutePath).TrimEnd('/');
            return path.Length == 0 || IsCollection(path) || IsChild(path, "/pkranges") || IsChild(path, "/docs");
        };
        endpointHandler.TransportFailed = () => poisoned = true;
        endpointHandler.BeforeRequest = () =>
        {
            if (!active || poisoned) throw new Blocked("SOURCE_SPEND_UNKNOWN", "source-request");
            if (transportErrors > c.MaxRetries) throw new Blocked("SOURCE_RETRY_LIMIT", "source-request");
            BeforeRequest?.Invoke();
        };
        endpointHandler.AfterResponse = async (request, response, token) =>
        {
            var path = Uri.UnescapeDataString(request.RequestUri!.AbsolutePath).TrimEnd('/');
            if (response.Headers.TryGetValues("x-ms-request-charge", out var values))
            {
                if (!decimal.TryParse(values.Single(), System.Globalization.NumberStyles.AllowDecimalPoint,
                    System.Globalization.CultureInfo.InvariantCulture, out var charge) || charge < 0)
                    throw new Blocked("SOURCE_SPEND_UNKNOWN", "source-request");
                transportCharge += charge;
                ChargeObserved?.Invoke(transportCharge);
            }
            else if (IsChild(path, "/docs"))
                throw new Blocked("SOURCE_SPEND_UNKNOWN", "source-request");
            // Only account, selected collection and its routing metadata may omit the charge header.
            if ((int)response.StatusCode >= 400) transportErrors++;
            var bytes = await SafeFiles.BoundedRead(await response.Content.ReadAsStreamAsync(token),
                IsChild(path, "/docs") ? c.BatchBytes : 1048576, token);
            if (IsCollection(path) && response.IsSuccessStatusCode)
            {
                using var metadata = JsonDocument.Parse(bytes);
                var encoded = Json.Text(metadata.RootElement, "_rid");
                var rid = Convert.FromBase64String(encoded);
                if (rid.Length != 8) throw new Blocked("UNSUPPORTED_COLLECTION_METADATA", "source-response");
                ridCollection = "/dbs/" + Convert.ToBase64String(rid[..4]) + "/colls/" + encoded;
            }
            var replacement = new ByteArrayContent(bytes);
            foreach (var header in response.Content.Headers)
                replacement.Headers.TryAddWithoutValidation(header.Key, header.Value);
            response.Content.Dispose();
            response.Content = replacement;
        };
        http = new HttpClient(endpointHandler)
        { Timeout = TimeSpan.FromMilliseconds(c.RequestTimeoutMillis) };
    }

    private void Initialize()
    {
        if (client != null) return;
        var c = config;
        // Initialization can itself issue account metadata requests, so it must follow the journal's durable intent.
        client = new CosmosClient(c.Endpoint.AbsoluteUri, credential, new CosmosClientOptions
        {
            ConnectionMode = ConnectionMode.Gateway,
            LimitToEndpoint = true,
            MaxRetryAttemptsOnRateLimitedRequests = 0,
            MaxRetryWaitTimeOnRateLimitedRequests = TimeSpan.Zero,
            RequestTimeout = TimeSpan.FromMilliseconds(c.RequestTimeoutMillis),
            HttpClientFactory = () => http,
            EnableContentResponseOnWrite = false,
            CosmosClientTelemetryOptions = new CosmosClientTelemetryOptions
            {
                DisableDistributedTracing = true, DisableSendingMetricsToService = true
            }
        });
        container = client.GetContainer(c.Database, c.Container);
    }

    public async Task<FeedPage> Read(string? continuation, int pageSize, bool connectivity, CancellationToken token)
    {
        transportCharge = 0;
        transportErrors = 0;
        active = true;
        try
        {
            Initialize();
            // A fresh iterator per persisted continuation prevents speculative iteration beyond the reserved request.
            using var iterator = container!.GetItemQueryStreamIterator(
                queryDefinition: null,
                continuationToken: continuation, requestOptions: new QueryRequestOptions
                {
                    MaxItemCount = connectivity ? 1 : pageSize,
                    MaxConcurrency = 0,
                    MaxBufferedItemCount = 0,
                    ResponseContinuationTokenLimitInKb = 8
                });
            using var response = await iterator.ReadNextAsync(token);
            var chargeHeader = response.Headers["x-ms-request-charge"];
            if (chargeHeader == null || !decimal.TryParse(chargeHeader,
                System.Globalization.NumberStyles.AllowDecimalPoint, System.Globalization.CultureInfo.InvariantCulture,
                out var charge) || charge < 0)
                throw new Blocked("SOURCE_SPEND_UNKNOWN", "source-request");
            if (poisoned || charge > transportCharge) throw new Blocked("SOURCE_SPEND_UNKNOWN", "source-response");
            charge = transportCharge;
            var retryText = response.Headers["x-ms-retry-after-ms"];
            var retryMillis = int.TryParse(retryText, out var retry) ? Math.Clamp(retry, 0, 300000) : 0;
            if (!response.IsSuccessStatusCode)
                return new FeedPage([], continuation, charge, (int)response.StatusCode, retryMillis);
            if (response.Content == null) throw new Blocked("SOURCE_SPEND_UNKNOWN", "source-response");
            var bytes = await SafeFiles.BoundedRead(response.Content, config.BatchBytes, token);
            using var doc = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = int.MaxValue });
            var array = doc.RootElement.GetProperty("Documents");
            if (array.ValueKind != JsonValueKind.Array || array.GetArrayLength() > pageSize)
                throw new Blocked("INVALID_FEED_PAGE", "source-response");
            var documents = array.EnumerateArray().Select(d => System.Text.Encoding.UTF8.GetBytes(d.GetRawText())).ToArray();
            var next = iterator.HasMoreResults ? response.ContinuationToken : null;
            if (iterator.HasMoreResults && next == null)
                throw new Blocked("SOURCE_CONTINUATION_UNAVAILABLE", "source-response");
            return new FeedPage(documents, next, charge, (int)response.StatusCode, 0);
        }
        finally { active = false; }
    }
    public void Dispose()
    {
        client?.Dispose();
        http.Dispose();
        foreach (var root in roots) root.Dispose();
    }
}
