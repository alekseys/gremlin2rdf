using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Gremlin2Age;

public sealed class Blocker(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

public static class Codec
{
    public static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        MaxDepth = 48
    };
    public static string Text<T>(T value) => JsonSerializer.Serialize(value, Json);
    public static T Parse<T>(string value)
    {
        using var document = JsonDocument.Parse(value, new JsonDocumentOptions { MaxDepth = 48 });
        static void Unique(JsonElement element)
        {
            if (element.ValueKind == JsonValueKind.Object)
            {
                var names = new HashSet<string>(StringComparer.Ordinal);
                foreach (var property in element.EnumerateObject())
                {
                    if (!names.Add(property.Name)) throw new Blocker("JSON_DUPLICATE_KEY", "Duplicate JSON keys are not lossless canonical input.");
                    Unique(property.Value);
                }
            }
            else if (element.ValueKind == JsonValueKind.Array) foreach (var child in element.EnumerateArray()) Unique(child);
        }
        Unique(document.RootElement);
        return document.RootElement.Deserialize<T>(Json) ?? throw new Blocker("INVALID_JSON", "A typed JSON object is required.");
    }
    public static string Hash(string text) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
    public static string FileHash(string path, CancellationToken token = default)
    {
        using var stream = File.OpenRead(path);
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var bytes = new byte[65536]; int count;
        while ((count = stream.Read(bytes)) != 0) { token.ThrowIfCancellationRequested(); hash.AppendData(bytes, 0, count); }
        return Convert.ToHexStringLower(hash.GetHashAndReset());
    }
    public static string Owned(string root, string relative)
    {
        if (!Directory.Exists(root) || File.GetAttributes(root).HasFlag(FileAttributes.ReparsePoint))
            throw new Blocker("UNOWNED_ROOT", "Existing non-linked run directory required.");
        if (Path.IsPathRooted(relative) || relative.Split('/', '\\').Any(x => x is ".." or "." or ""))
            throw new Blocker("UNOWNED_PATH", "Use a nonempty run-relative artifact path.");
        var full = Path.GetFullPath(Path.Combine(root, relative));
        var current = root;
        foreach (var part in relative.Split('/', '\\'))
        {
            current = Path.Combine(current, part);
            if ((File.Exists(current) || Directory.Exists(current)) &&
                File.GetAttributes(current).HasFlag(FileAttributes.ReparsePoint))
                throw new Blocker("SYMLINK_PATH", "Linked artifact paths are not supported.");
        }
        return full;
    }
    public static string Bounded(string path, long maximum = 1_048_576)
    {
        using var file = File.OpenRead(path); using var output = new MemoryStream();
        var bytes = new byte[65536]; int count;
        while ((count = file.Read(bytes)) != 0)
        {
            if (output.Length + count > maximum) throw new Blocker("FILE_BOUND", "Artifact exceeds its byte limit.");
            output.Write(bytes, 0, count);
        }
        return new UTF8Encoding(false, true).GetString(output.ToArray()).TrimStart('\uFEFF');
    }
    public static IEnumerable<string> Lines(string path, int maximum, CancellationToken token)
    {
        using var file = File.OpenRead(path); using var line = new MemoryStream();
        var buffer = new byte[16384]; int count;
        while ((count = file.Read(buffer)) != 0)
        {
            token.ThrowIfCancellationRequested();
            for (var i = 0; i < count; i++)
            {
                if (buffer[i] == 10)
                {
                    yield return new UTF8Encoding(false, true).GetString(line.ToArray()).TrimEnd('\r');
                    line.SetLength(0); line.Position = 0;
                }
                else
                {
                    if (line.Length >= maximum) throw new Blocker("RECORD_BOUND", "Input line exceeds its byte bound.");
                    line.WriteByte(buffer[i]);
                }
            }
        }
        if (line.Length > 0) yield return new UTF8Encoding(false, true).GetString(line.ToArray()).TrimEnd('\r');
    }
    public static void NewFile(string path, string content)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        using var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        file.Write(Encoding.UTF8.GetBytes(content));
        file.Flush(true);
    }
    public static void Checkpoint(string path, string content)
    {
        var pending = path + ".pending";
        NewFile(pending, content);
        File.Move(pending, path, true);
    }
    public static string Identifier(string value)
    {
        if (!Regex.IsMatch(value, "^[a-z][a-z0-9_]{0,47}$", RegexOptions.CultureInvariant))
            throw new Blocker("IDENTIFIER", "Use a lowercase SQL/AGE identifier of at most 48 characters.");
        return value;
    }
}

public sealed record TypedValue(string Type, string Lexical)
{
    public void Validate()
    {
        if (Lexical.Length > 262_144 || !new[] { "string", "integer", "double", "float", "boolean" }.Contains(Type))
            throw new Blocker("VALUE_TYPE", "Unsupported typed canonical value.");
        var valid = Type switch
        {
            "integer" => Regex.IsMatch(Lexical, @"^-?(0|[1-9][0-9]*)$") && System.Numerics.BigInteger.TryParse(Lexical, out _),
            "boolean" => Lexical is "true" or "false",
            "double" => double.TryParse(Lexical, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var value) && double.IsFinite(value),
            "float" => float.TryParse(Lexical, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var value) && float.IsFinite(value),
            _ => true
        };
        if (!valid) throw new Blocker("VALUE_LEXICAL", "The lexical value is not valid for its declared type.");
    }
}
public sealed record Identity(string Scope, TypedValue Partition, TypedValue Id, TypedValue? Owner = null)
{
    public string Key(string kind) => Codec.Hash(Codec.Text(new { kind, Scope, Partition, Id, Owner }));
    public void Validate(string kind)
    {
        if (kind is not ("vertex" or "edge")) throw new Blocker("IDENTITY_KIND", "Typed vertex/edge identity required.");
        if (string.IsNullOrWhiteSpace(Scope) || Scope.Length > 1024) throw new Blocker("IDENTITY_SCOPE", "Source scope required.");
        Partition.Validate(); Id.Validate(); Owner?.Validate();
        if (kind == "vertex" && Owner != null) throw new Blocker("IDENTITY_OWNER", "Vertex identities cannot have an edge owner.");
    }
}
public sealed record Occurrence(string Key, TypedValue Id, TypedValue Value, Dictionary<string, TypedValue> Meta);
public sealed record Provenance(string Source, string Consistency, string Observation, bool Complete,
    Dictionary<string, string>? Metadata = null);
public sealed record GraphRecord(int SchemaVersion, string Kind, Identity Identity, string Label,
    Identity? Source, Identity? Destination, List<Occurrence> Properties, Provenance Provenance)
{
    [JsonIgnore] public string ExternalId => Identity.Key(Kind);
    public void Validate()
    {
        if (SchemaVersion != 1 || Kind is not ("vertex" or "edge") || Label.Length > 4096 || string.IsNullOrWhiteSpace(Label) ||
            Label.Any(char.IsControl) || Label.Contains("$g2r$", StringComparison.Ordinal))
            throw new Blocker("CANONICAL_RECORD", "Versioned vertex/edge record with label required.");
        Identity.Validate(Kind);
        if ((Kind == "edge") != (Source != null && Destination != null) ||
            (Kind == "vertex" && (Source != null || Destination != null)))
            throw new Blocker("EDGE_ENDPOINT", "Edges require both typed endpoints; vertices cannot have endpoints.");
        Source?.Validate("vertex"); Destination?.Validate("vertex");
        if (Kind == "edge" && (Identity.Owner == null || Codec.Text(Identity.Owner) != Codec.Text(Source!.Id) ||
            Codec.Text(Identity.Partition) != Codec.Text(Source.Partition)))
            throw new Blocker("EDGE_OWNER", "Edge identity must retain its typed outgoing owner and partition.");
        if (Source?.Scope != null && (Source.Scope != Identity.Scope || Destination!.Scope != Identity.Scope))
            throw new Blocker("EDGE_SCOPE", "Endpoint scopes differ from the edge scope.");
        if (!Provenance.Complete || Provenance.Consistency is not ("quiesced" or "immutable") ||
            string.IsNullOrWhiteSpace(Provenance.Observation))
            throw new Blocker("SOURCE_FIDELITY", "Complete source observations and stable source consistency required.");
        if (Provenance.Metadata is { } metadata && (metadata.Count > 1024 ||
            metadata.Any(p => string.IsNullOrWhiteSpace(p.Key) || p.Key.Length > 4096 || p.Value.Length > 262144)))
            throw new Blocker("PROVENANCE_BOUND", "Supplemental source metadata exceeds its canonical bound.");
        if (Properties.Count > 4096) throw new Blocker("PROPERTY_BOUND", "Property occurrence bound exceeded.");
        var ids = new HashSet<string>();
        foreach (var property in Properties)
        {
            if (string.IsNullOrWhiteSpace(property.Key) || property.Key.Length > 4096 ||
                !ids.Add(Codec.Text(new { property.Key, property.Id })))
                throw new Blocker("PROPERTY_ID", "Each property occurrence needs a distinct typed identity.");
            property.Id.Validate(); property.Value.Validate();
            if (property.Meta.Count > 1024) throw new Blocker("META_BOUND", "Meta-property bound exceeded.");
            foreach (var meta in property.Meta) { if (string.IsNullOrWhiteSpace(meta.Key)) throw new Blocker("META_KEY", "Meta key required."); meta.Value.Validate(); }
        }
        if (Encoding.UTF8.GetByteCount(Codec.Text(this)) > 262_144) throw new Blocker("RECORD_BOUND", "Canonical record exceeds 256 KiB.");
    }
}
public sealed record SourceConfig(string Host, string Database, string Container, string PartitionKeyPath,
    string AuthenticationRef, string Scope);
public sealed record TargetConfig(string Host, int Port, string Database, string Username, string AuthenticationRef,
    string Graph, string LedgerSchema, string SslMode);
public sealed record Limits(int BatchRecords = 50, int BatchBytes = 1_048_576, int TimeoutSeconds = 30,
    int MaximumOperationSeconds = 300);
public sealed record Approval(string Operation, string InputHash, string RunId, string Target,
    string ResourceHash, string ExpiresAt, string Signature);
public sealed record Project(int SchemaVersion, string MigrationKind, string RunId, SourceConfig? Source,
    TargetConfig? Target, string Consistency, Limits Limits, List<Approval> Approvals)
{
    [JsonIgnore] public string InputHash => Codec.Hash(Codec.Text(new { SchemaVersion, MigrationKind, RunId, Source, Target, Consistency, Limits }));
    [JsonIgnore] public string TargetScope => Target == null ? "" : Codec.Text(Target);
    public void Validate()
    {
        if (SchemaVersion != 1 || MigrationKind != "cosmos-gremlin-to-age" ||
            !Regex.IsMatch(RunId, "^[a-zA-Z0-9-]{1,80}$") || Consistency is not ("immutable" or "quiesced"))
            throw new Blocker("AGE_PROJECT", "Separate versioned AGE project and stable source consistency required.");
        if (Limits.BatchRecords is < 1 or > 1000 || Limits.BatchBytes is < 262_144 or > 16_777_216 ||
            Limits.TimeoutSeconds is < 1 or > 120 || Limits.MaximumOperationSeconds is < 1 or > 3600)
            throw new Blocker("LIMITS", "Bounded AGE execution limits required.");
        if (Target != null)
        {
            Codec.Identifier(Target.Graph); Codec.Identifier(Target.LedgerSchema);
            if (Target.LedgerSchema == Target.Graph || new[] { "public", "ag_catalog", "information_schema" }.Contains(Target.LedgerSchema) ||
                Target.LedgerSchema.StartsWith("pg_", StringComparison.Ordinal))
                throw new Blocker("LEDGER_SCHEMA", "Use a dedicated non-system ledger schema distinct from the AGE graph schema.");
            if (Target.Port is < 1 or > 65535 || Target.SslMode != "VerifyFull")
                throw new Blocker("TARGET_TLS", "PostgreSQL VerifyFull TLS with valid server certificate is required.");
        }
        if (Source != null && (!Regex.IsMatch(Source.Host, "^[a-zA-Z0-9.-]+$") ||
            !Source.PartitionKeyPath.StartsWith('/') || Source.PartitionKeyPath.Length < 2))
            throw new Blocker("SOURCE_CONFIG", "Gremlin hostname and partition key path required.");
        if (Source != null && (Source.Database.Length is < 1 or > 255 || Source.Container.Length is < 1 or > 255 ||
            Source.Scope.Length is < 1 or > 1024 || !Regex.IsMatch(Source.AuthenticationRef, "^env:AGE_[A-Z0-9_]+$")))
            throw new Blocker("SOURCE_CONFIG", "Bounded database/container/scope and secret reference required.");
        if (Target != null && (Target.Host.Length is < 1 or > 255 || Target.Database.Length is < 1 or > 255 ||
            Target.Username.Length is < 1 or > 255 || !Regex.IsMatch(Target.AuthenticationRef, "^env:AGE_[A-Z0-9_]+$")))
            throw new Blocker("TARGET_CONFIG", "Bounded target connection and secret reference required.");
    }
}

public static class Authorizations
{
    private static byte[] SigningKey()
    {
        var key = Encoding.UTF8.GetBytes(Secret("env:AGE_APPROVAL_KEY"));
        if (key.Length < 32) throw new Blocker("APPROVAL_KEY", "Approval key must contain at least 32 private random bytes.");
        return key;
    }
    public static string Secret(string reference)
    {
        if (!Regex.IsMatch(reference, "^env:AGE_[A-Z0-9_]+$"))
            throw new Blocker("SECRET_REFERENCE", "Use approved env:AGE_* credential references.");
        return Environment.GetEnvironmentVariable(reference[4..])
            ?? throw new Blocker("SECRET_MISSING", "Configure the referenced credential outside artifacts.");
    }
    private static string Content(string operation, Project p, string resourceHash, string expires) =>
        Codec.Text(new { operation, inputHash = p.InputHash, runId = p.RunId, target = p.TargetScope, resourceHash, expiresAt = expires });
    public static Approval Issue(Project p, string operation, string resourceHash)
    {
        if (operation is not ("load" or "crud" or "rewrite")) throw new Blocker("APPROVAL_OPERATION", "Unsupported authorization scope.");
        if (operation is "load" or "crud" && p.Target == null) throw new Blocker("TARGET_REQUIRED", "Target-scoped approval requires a configured target.");
        var expires = DateTimeOffset.UtcNow.AddMinutes(15).ToString("O");
        if (!Regex.IsMatch(resourceHash, "^[a-f0-9]{64}$")) throw new Blocker("APPROVAL_RESOURCE", "Exact input/resource SHA256 required.");
        return new(operation, p.InputHash, p.RunId, p.TargetScope, resourceHash, expires,
            Convert.ToHexStringLower(HMACSHA256.HashData(SigningKey(),
                Encoding.UTF8.GetBytes(Content(operation, p, resourceHash, expires)))));
    }
    public static void Require(Project p, string operation, string resourceHash)
    {
        foreach (var approval in p.Approvals)
        {
            if (approval.Operation != operation || approval.InputHash != p.InputHash || approval.RunId != p.RunId ||
                approval.Target != p.TargetScope || approval.ResourceHash != resourceHash || !DateTimeOffset.TryParse(approval.ExpiresAt, out var expiry) ||
                expiry <= DateTimeOffset.UtcNow || expiry > DateTimeOffset.UtcNow.AddMinutes(16)) continue;
            var signature = HMACSHA256.HashData(SigningKey(),
                Encoding.UTF8.GetBytes(Content(operation, p, resourceHash, approval.ExpiresAt)));
            try { if (CryptographicOperations.FixedTimeEquals(signature, Convert.FromHexString(approval.Signature))) return; }
            catch (FormatException) { }
        }
        throw new Blocker("APPROVAL_REQUIRED", "Current run/input/target-scoped approval is required.");
    }
}
