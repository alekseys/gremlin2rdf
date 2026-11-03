using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace DocumentSource;

internal sealed class Blocked(string code, string stage = "document-source") : Exception
{
    public string Code { get; } = code;
    public string Stage { get; } = stage;
    public string? ArtifactSha256 { get; set; }
}

internal static class Json
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        MaxDepth = 64
    };
    public static byte[] Bytes<T>(T value) => JsonSerializer.SerializeToUtf8Bytes(value, Options);
    public static T Read<T>(byte[] value) => JsonSerializer.Deserialize<T>(value, Options)
        ?? throw new Blocked("INVALID_LOCAL_ARTIFACT");
    public static string Hash(byte[] value) => Convert.ToHexStringLower(SHA256.HashData(value));
    public static string Text(JsonElement value, string name) =>
        value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.String
            ? item.GetString()! : throw new Blocked("INVALID_PROJECT", "project-validation");
    public static string? Optional(JsonElement value, string name) =>
        !value.TryGetProperty(name, out var item) || item.ValueKind == JsonValueKind.Null
            ? null : Text(value, name);
    public static string Stable(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
            return "{" + string.Join(",", element.EnumerateObject().OrderBy(p => p.Name, StringComparer.Ordinal)
                .Select(p => JsonSerializer.Serialize(p.Name, Options) + ":" + Stable(p.Value))) + "}";
        if (element.ValueKind == JsonValueKind.Array)
            return "[" + string.Join(",", element.EnumerateArray().Select(Stable)) + "]";
        return element.ValueKind == JsonValueKind.String
            ? JsonSerializer.Serialize(element.GetString(), Options) : element.GetRawText();
    }
    public static decimal Decimal(string value) =>
        decimal.TryParse(value, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var parsed) && parsed > 0
            ? parsed : throw new Blocked("INVALID_RU_LIMIT", "project-validation");
    public static string DecimalText(decimal value) => value.ToString(CultureInfo.InvariantCulture);
}

internal sealed record TypedValue(string Type, string Lexical);
internal sealed record Identity(string Scope, TypedValue Partition, TypedValue Id);
internal sealed record IdentityEntry(int SchemaVersion, string Kind, TypedValue Partition, TypedValue Id,
    TypedValue? OwnerId, string? EdgeScope)
{
    [JsonIgnore]
    public string Key => Json.Hash(Json.Bytes(this));
    public void Validate()
    {
        static bool Valid(TypedValue v) => v.Type switch
        {
            "string" => true,
            "booleanValue" => v.Lexical is "true" or "false",
            "integer" => System.Numerics.BigInteger.TryParse(v.Lexical, NumberStyles.AllowLeadingSign,
                CultureInfo.InvariantCulture, out var n) && n.ToString(CultureInfo.InvariantCulture) == v.Lexical,
            _ => false
        };
        var validPartition = Valid(Partition) || Partition.Type == "doubleValue"
            && Regex.IsMatch(Partition.Lexical, "^-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
            && double.TryParse(Partition.Lexical, NumberStyles.Float, CultureInfo.InvariantCulture, out var number)
            && double.IsFinite(number);
        if (SchemaVersion != 1 || !validPartition || !Valid(Id)
            || Kind is not ("vertex" or "edge")
            || Kind == "vertex" && (OwnerId != null || EdgeScope != null)
            || Kind == "edge" && (OwnerId == null || !Valid(OwnerId) || EdgeScope != "sourceVertex"))
            throw new Blocked("UNSUPPORTED_IDENTITY_MANIFEST", "manifest-validation");
    }
}
internal sealed record GraphProperty(string Key, TypedValue Value, TypedValue? Id,
    SortedDictionary<string, TypedValue> MetaProperties, string Fidelity);
internal sealed record Provenance(string Shard, string SourceManifestHash, string Consistency, string Fidelity);
internal sealed record GraphRecord(string Kind, Identity Identity, string Label, Identity? Source,
    Identity? Destination, string? EdgeScope, List<GraphProperty> Properties,
    SortedDictionary<string, TypedValue> Metadata, Provenance Provenance);

internal sealed class Config
{
    public required string Root { get; init; }
    public required string RunId { get; init; }
    public required string InputHash { get; init; }
    public required string ProjectSha { get; init; }
    public required string Binding { get; init; }
    public required string Scope { get; init; }
    public required Uri Endpoint { get; init; }
    public required string Database { get; init; }
    public required string Container { get; init; }
    public required string PartitionKey { get; init; }
    public required string AuthenticationRef { get; init; }
    public string? CustomCaRef { get; init; }
    public required string Profile { get; init; }
    public required string Manifest { get; init; }
    public required string ManifestHash { get; init; }
    public required string Consistency { get; init; }
    public required string Fidelity { get; init; }
    public required JsonElement Project { get; init; }
    public required decimal RuBudget { get; init; }
    public decimal? RuPerSecond { get; init; }
    public required int BatchRecords { get; init; }
    public required int BatchBytes { get; init; }
    public required int MaxRecordBytes { get; init; }
    public required int RequestTimeoutMillis { get; init; }
    public required int MaxRetries { get; init; }
    public required long MinimumFreeDiskBytes { get; init; }
    public required long MaximumOperationMillis { get; init; }
    public string CacheRelative => $"artifacts/document-source-{RunId}-{InputHash}";

    public static Config Load(string path, string inputHash, bool connectivityCheck = false)
    {
        var full = Path.GetFullPath(path);
        if (new FileInfo(full).Length > 1048576) throw new Blocked("PROJECT_SIZE_LIMIT", "project-validation");
        var bytes = File.ReadAllBytes(full);
        using var doc = JsonDocument.Parse(bytes);
        var p = doc.RootElement;
        var s = p.GetProperty("source");
        var limits = p.GetProperty("limits");
        var run = Json.Text(p, "runId");
        var endpoint = new Uri(Json.Text(s, "documentEndpoint"), UriKind.Absolute);
        var pk = Json.Text(s, "partitionKeyPath");
        var profile = Json.Text(s, "documentProfile");
        var consistency = Json.Text(p, "consistency");
        var fidelity = Json.Text(p.GetProperty("mapping"), "fidelity");
        var manifestHash = connectivityCheck ? Json.Optional(s, "identityManifestHash") ?? new string('0', 64)
            : Json.Text(s, "identityManifestHash");
        var auth = Json.Text(s, "authenticationRef");
        var ca = Json.Optional(s, "customCaRef");
        if (!Regex.IsMatch(inputHash, "^[a-f0-9]{64}$")
            || !Regex.IsMatch(manifestHash, "^[a-f0-9]{64}$")
            || !Regex.IsMatch(run, "^[a-zA-Z0-9_-]{1,80}$")
            || endpoint.UserInfo.Length != 0 || endpoint.Query.Length != 0 || endpoint.Fragment.Length != 0
            || endpoint.AbsolutePath != "/" || endpoint.Host.Length == 0
            || endpoint.Scheme != "https"
            || !Regex.IsMatch(pk, "^/[^/~]{1,255}$")
            || new[] { "id", "label", "_rid", "_self", "_etag", "_attachments", "_ts", "_isEdge",
                "_vertexId", "_vertexLabel", "_sink", "_sinkLabel", "_sinkPartition" }.Contains(pk[1..],
                StringComparer.Ordinal)
            || Json.Optional(s, "sourceMode") != "cosmos-documents"
            || profile is not ("auto-v1" or "property-bags-v1" or "flat-v1")
            || consistency is not ("immutable" or "quiesced" or "best-effort")
            || fidelity is not ("strict" or "reduced")
            || !Regex.IsMatch(auth, "^env:GRAPH_[A-Z0-9_]{1,100}$")
            || ca != null && !Regex.IsMatch(ca, "^env:GRAPH_[A-Z0-9_]{1,100}$"))
            throw new Blocked("INVALID_PROJECT", "project-validation");
        int Limit(string key, int min, int max)
        {
            var n = limits.GetProperty(key).GetInt32();
            return n >= min && n <= max ? n : throw new Blocked("INVALID_LIMIT", "project-validation");
        }
        var maxRecord = Limit("maxRecordBytes", 128, 1048576);
        var batchBytes = Limit("batchBytes", 1024, 8388608);
        var disk = limits.GetProperty("minimumFreeDiskBytes").GetInt64();
        var duration = limits.TryGetProperty("maximumOperationMillis", out var d) && d.ValueKind != JsonValueKind.Null
            ? d.GetInt64() : 3600000;
        if (maxRecord > batchBytes || disk < 0 || duration is < 1000 or > 86400000)
            throw new Blocked("INVALID_LIMIT", "project-validation");
        var stableSource = JsonSerializer.SerializeToElement(s.EnumerateObject()
            .Where(item => item.Name != "compatibilityEvidence").ToDictionary(item => item.Name, item => item.Value), Json.Options);
        var bindingText = Json.Stable(stableSource) + "|" + Json.Stable(limits) + "|" + consistency + "|" + fidelity;
        var result = new Config
        {
            Root = Path.GetDirectoryName(full)!, RunId = run, InputHash = inputHash,
            ProjectSha = Json.Hash(bytes), Binding = Json.Hash(Encoding.UTF8.GetBytes(bindingText)),
            Scope = Json.Text(s, "scope"), Database = Json.Text(s, "database"), Container = Json.Text(s, "container"),
            Endpoint = endpoint, PartitionKey = pk[1..], Profile = profile, AuthenticationRef = auth, CustomCaRef = ca,
            Manifest = connectivityCheck ? Json.Optional(s, "identityManifest") ?? "unselected-identities.ndjson"
                : Json.Text(s, "identityManifest"), ManifestHash = manifestHash, Consistency = consistency,
            Fidelity = fidelity, Project = p.Clone(),
            RuBudget = Json.Optional(s, "ruBudget") is { } budget ? Json.Decimal(budget)
                : throw new Blocked("REVIEWED_RU_BUDGET_REQUIRED", "project-validation"),
            RuPerSecond = Json.Optional(s, "ruPerSecond") is { } rate ? Json.Decimal(rate) : null,
            BatchRecords = Limit("batchRecords", 1, 1000), BatchBytes = batchBytes, MaxRecordBytes = maxRecord,
            RequestTimeoutMillis = Limit("requestTimeoutMillis", 100, 300000),
            MaxRetries = Limit("maxRetries", 0, 10), MinimumFreeDiskBytes = disk, MaximumOperationMillis = duration
        };
        if (result.Scope.Length == 0 || result.Database.Length == 0 || result.Container.Length == 0)
            throw new Blocked("INVALID_PROJECT");
        if (connectivityCheck && result.RuPerSecond == null)
            throw new Blocked("REVIEWED_RU_RATE_REQUIRED", "project-validation");
        if (result.RuPerSecond is < 1 or > 1000000000)
            throw new Blocked("INVALID_RU_LIMIT", "project-validation");
        if (new[] { result.Database, result.Container }.Any(name => name.IndexOfAny(['/', '\\', '?', '#']) >= 0
            || name.Any(char.IsControl) || name.EndsWith(' ')))
            throw new Blocked("INVALID_PROJECT", "project-validation");
        if (!connectivityCheck)
        {
            var manifestPath = SafeFiles.Resolve(result.Root, result.Manifest);
            if (SafeFiles.HashFile(manifestPath) != manifestHash)
                throw new Blocked("IDENTITY_MANIFEST_HASH_MISMATCH", "manifest-validation");
        }
        return result;
    }
}
