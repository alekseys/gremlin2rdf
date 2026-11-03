using System.Globalization;
using System.Numerics;
using System.Text.Json;

namespace DocumentSource;

internal sealed class Codec(Config config)
{
    private bool normalizedInteger;
    public SortedSet<string> Unobservable { get; } = new(StringComparer.Ordinal);
    public SortedSet<string> DetectedProfiles { get; } = new(StringComparer.Ordinal);
    private static readonly HashSet<string> SystemFields = ["_rid", "_self", "_etag", "_attachments", "_ts"];
    private static readonly HashSet<string> EdgeFields =
        ["_isEdge", "_vertexId", "_vertexLabel", "_sink", "_sinkLabel", "_sinkPartition"];

    public static void UniqueObject(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Object) throw new Blocked("UNSUPPORTED_DOCUMENT_SHAPE", "document-decode");
        var names = new HashSet<string>(StringComparer.Ordinal);
        foreach (var p in value.EnumerateObject())
            if (!names.Add(p.Name)) throw new Blocked("DUPLICATE_JSON_FIELD", "document-decode");
    }
    public static JsonDocument Parse(byte[] raw)
    {
        try { return JsonDocument.Parse(raw); }
        catch (JsonException) { throw new Blocked("UNSUPPORTED_DOCUMENT_SHAPE", "document-decode"); }
    }
    public TypedValue Scalar(JsonElement value, bool identity = false)
    {
        switch (value.ValueKind)
        {
            case JsonValueKind.String: return new("string", value.GetString()!);
            case JsonValueKind.True: return new("booleanValue", "true");
            case JsonValueKind.False: return new("booleanValue", "false");
            case JsonValueKind.Number:
                var raw = value.GetRawText();
                if (!raw.Contains('.') && !raw.Contains('e') && !raw.Contains('E'))
                {
                    var canonical = BigInteger.Parse(raw, CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture);
                    if (canonical != raw)
                    {
                        normalizedInteger = true;
                        Unobservable.Add("integer-lexical-normalized");
                    }
                    return new("integer", canonical);
                }
                if (identity || !double.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out var number)
                    || !double.IsFinite(number))
                    throw new Blocked("UNSUPPORTED_NUMERIC_VALUE", "document-decode");
                Unobservable.Add("numeric-runtime-type-unobservable");
                return new("doubleValue", raw);
            default: throw new Blocked("UNSUPPORTED_SCALAR_VALUE", "document-decode");
        }
    }
    private TypedValue Required(JsonElement doc, string key, bool stableId = true) =>
        doc.TryGetProperty(key, out var value) ? Scalar(value, stableId)
            : throw new Blocked("MISSING_DOCUMENT_IDENTITY", "document-decode");
    private static string Label(JsonElement doc, string key) =>
        doc.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String && value.GetString()!.Length > 0
            ? value.GetString()! : throw new Blocked("MISSING_DOCUMENT_LABEL", "document-decode");
    private string Profile(JsonElement doc, bool edge)
    {
        if (config.Profile != "auto-v1" || edge) return config.Profile;
        string? detected = null;
        foreach (var field in doc.EnumerateObject())
        {
            if (field.Name is "id" or "label" || field.Name == config.PartitionKey
                || SystemFields.Contains(field.Name) || EdgeFields.Contains(field.Name)) continue;
            var candidate = field.Value.ValueKind switch
            {
                JsonValueKind.Array => "property-bags-v1",
                JsonValueKind.String or JsonValueKind.Number or JsonValueKind.True or JsonValueKind.False => "flat-v1",
                _ => throw new Blocked("UNSUPPORTED_DOCUMENT_SHAPE", "document-decode")
            };
            if (detected != null && detected != candidate)
                throw new Blocked("UNSUPPORTED_DOCUMENT_SHAPE", "document-decode");
            detected = candidate;
        }
        if (detected != null) DetectedProfiles.Add(detected);
        // A vertex with no user properties has the same decoding under either supported layout.
        return detected ?? "property-bags-v1";
    }
    public IdentityEntry Entry(JsonElement doc)
    {
        UniqueObject(doc);
        var edge = false;
        if (doc.TryGetProperty("_isEdge", out var flag))
        {
            if (flag.ValueKind != JsonValueKind.True) throw new Blocked("AMBIGUOUS_EDGE_FLAG", "document-decode");
            edge = true;
        }
        if (!doc.TryGetProperty("id", out var physicalId) || physicalId.ValueKind != JsonValueKind.String)
            throw new Blocked("UNSUPPORTED_PHYSICAL_ID", "document-decode");
        var entry = new IdentityEntry(1, edge ? "edge" : "vertex", Required(doc, config.PartitionKey, false),
            Scalar(physicalId, true), edge ? Required(doc, "_vertexId") : null, edge ? "sourceVertex" : null);
        entry.Validate();
        return entry;
    }
    public GraphRecord Decode(byte[] raw)
    {
        normalizedInteger = false;
        using var parsed = Parse(raw);
        var doc = parsed.RootElement;
        var entry = Entry(doc);
        var edge = entry.Kind == "edge";
        var profile = Profile(doc, edge);
        var label = Label(doc, "label");
        var metadata = new SortedDictionary<string, TypedValue>(StringComparer.Ordinal);
        var properties = new List<GraphProperty>();
        var occurrenceIds = new HashSet<TypedValue>();
        var reduced = false;
        foreach (var system in SystemFields)
        {
            if (doc.TryGetProperty(system, out var value))
            {
                var typed = Scalar(value);
                metadata.Add(system, typed);
                if (typed.Type == "doubleValue") reduced = true;
            }
            else { Unobservable.Add("system-metadata-unobservable"); reduced = true; }
        }
        Identity? source = null, destination = null;
        if (edge)
        {
            source = new(config.Scope, entry.Partition, entry.OwnerId!);
            destination = new(config.Scope, Required(doc, "_sinkPartition", false), Required(doc, "_sink"));
            reduced |= destination.Partition.Type == "doubleValue";
            metadata.Add("sourceLabel", new("string", Label(doc, "_vertexLabel")));
            metadata.Add("destinationLabel", new("string", Label(doc, "_sinkLabel")));
            metadata.Add("outVPartition", entry.Partition);
            metadata.Add("inVPartition", destination.Partition);
        }
        if (metadata.TryGetValue("_etag", out var etag) && (etag.Type != "string" || etag.Lexical.Length == 0)
            || metadata.TryGetValue("_ts", out var timestamp) && timestamp.Type != "integer"
            || new[] { "_rid", "_self", "_attachments" }.Any(key =>
                metadata.TryGetValue(key, out var value) && value.Type != "string"))
        {
            Unobservable.Add("system-metadata-shape-unobservable");
            reduced = true;
        }
        foreach (var field in doc.EnumerateObject())
        {
            if (field.Name is "id" or "label" || SystemFields.Contains(field.Name)) continue;
            if (EdgeFields.Contains(field.Name))
            {
                if (!edge) throw new Blocked("UNEXPECTED_EDGE_FIELD", "document-decode");
                continue;
            }
            if (field.Name.StartsWith('_') && field.Name != config.PartitionKey)
                throw new Blocked("UNKNOWN_STORAGE_FIELD", "document-decode");
            if (field.Name.Length == 0) throw new Blocked("EMPTY_PROPERTY_KEY", "document-decode");
            if (edge || profile == "flat-v1" || field.Name == config.PartitionKey)
            {
                var value = Scalar(field.Value);
                var fidelity = !edge || value.Type == "doubleValue" || field.Value.GetRawText() == "-0"
                    ? "reduced" : "complete";
                if (!edge) Unobservable.Add("property-occurrence-id-unobservable");
                properties.Add(new(field.Name, value, null, new(StringComparer.Ordinal), fidelity));
                if (fidelity == "reduced") reduced = true;
                continue;
            }
            if (field.Value.ValueKind != JsonValueKind.Array || field.Value.GetArrayLength() == 0)
                throw new Blocked("UNSUPPORTED_PROPERTY_BAG", "document-decode");
            foreach (var occurrence in field.Value.EnumerateArray())
            {
                UniqueObject(occurrence);
                if (occurrence.EnumerateObject().Any(p => p.Name is not ("id" or "_value" or "_meta")))
                    throw new Blocked("UNKNOWN_OCCURRENCE_FIELD", "document-decode");
                var id = Required(occurrence, "id");
                if (!occurrenceIds.Add(id)) throw new Blocked("DUPLICATE_OCCURRENCE_ID", "document-decode");
                if (!occurrence.TryGetProperty("_value", out var rawValue))
                    throw new Blocked("MISSING_PROPERTY_VALUE", "document-decode");
                var value = Scalar(rawValue);
                var meta = new SortedDictionary<string, TypedValue>(StringComparer.Ordinal);
                var propertyReduced = value.Type == "doubleValue" || rawValue.GetRawText() == "-0"
                    || occurrence.GetProperty("id").GetRawText() == "-0";
                if (occurrence.TryGetProperty("_meta", out var rawMeta))
                {
                    UniqueObject(rawMeta);
                    foreach (var item in rawMeta.EnumerateObject())
                    {
                        var typed = Scalar(item.Value);
                        meta.Add(item.Name, typed);
                        if (typed.Type == "doubleValue" || item.Value.GetRawText() == "-0") propertyReduced = true;
                    }
                    if (meta.Count > 0)
                    {
                        Unobservable.Add("meta-property-occurrence-id-unobservable");
                        propertyReduced = true;
                    }
                }
                else
                {
                    Unobservable.Add("absent-meta-storage-convention-not-runtime-proof");
                    propertyReduced = true;
                }
                properties.Add(new(field.Name, value, id, meta, propertyReduced ? "reduced" : "complete"));
                reduced |= propertyReduced;
            }
        }
        properties.Sort((a, b) => string.CompareOrdinal(Json.Stable(JsonSerializer.SerializeToElement(a, Json.Options)),
            Json.Stable(JsonSerializer.SerializeToElement(b, Json.Options))));
        reduced |= normalizedInteger;
        return new(entry.Kind, new(config.Scope, entry.Partition, entry.Id), label, source, destination,
            entry.EdgeScope, properties, metadata, new("document-feed:" + config.Binding[..24],
                config.ManifestHash, config.Consistency, reduced ? "reduced" : "complete"));
    }
}
