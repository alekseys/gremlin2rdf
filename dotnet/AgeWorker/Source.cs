using System.Text.Json;
using Gremlin.Net.Driver;
using Gremlin.Net.Structure.IO.GraphSON;

namespace Gremlin2Age;

public sealed record SourceEvidence(int SchemaVersion, string Host, string Database, string Container,
    string InventoryHash, string Consistency, string Status, bool PropertyIds, bool MetaProperties,
    bool TypedValues, bool PartitionQualifiedEndpoints, string ObservedAt);
public static class CosmosSource
{
    private static TypedValue Value(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.String => new("string", value.GetString()!),
        JsonValueKind.True or JsonValueKind.False => new("boolean", value.GetRawText()),
        // A raw JSON number does not prove the provider's original numeric type.
        _ => throw new Blocker("SOURCE_NUMERIC_TYPE", "Provider numeric type/lexical fidelity is not established; use reviewed typed canonical input.")
    };
    public static GraphRecord Decode(JsonElement raw, Identity requested, string consistency, string observation)
    {
        var kind = raw.GetProperty("type").GetString() ?? "";
        if (kind is not ("vertex" or "edge")) throw new Blocker("SOURCE_KIND", "Provider vertex/edge record required.");
        if (Codec.Text(Value(raw.GetProperty("id"))) != Codec.Text(requested.Id))
            throw new Blocker("SOURCE_ID", "Provider returned a different typed identity.");
        var properties = new List<Occurrence>();
        foreach (var field in raw.GetProperty("properties").EnumerateObject())
        {
            if (field.Value.ValueKind != JsonValueKind.Array)
                throw new Blocker("SOURCE_OCCURRENCES", "Provider occurrence arrays required; edge scalar properties cannot preserve occurrence identity.");
            foreach (var entry in field.Value.EnumerateArray())
            {
                if (!entry.TryGetProperty("id", out var id) || !entry.TryGetProperty("value", out var value) ||
                    !entry.TryGetProperty("properties", out var meta) || meta.ValueKind != JsonValueKind.Object)
                    throw new Blocker("SOURCE_PROPERTY_FIDELITY", "Occurrence IDs and explicit meta-property observations are required; missing fields are not assumed empty.");
                properties.Add(new(field.Name, Value(id), Value(value),
                    meta.EnumerateObject().ToDictionary(m => m.Name, m => Value(m.Value))));
            }
        }
        Identity? source = null, destination = null;
        if (kind == "edge")
        {
            if (!raw.TryGetProperty("outVPartition", out var outPartition) || !raw.TryGetProperty("inVPartition", out var inPartition))
                throw new Blocker("SOURCE_ENDPOINT_PARTITION", "Partition-qualified endpoint observations are required.");
            source = new(requested.Scope, Value(outPartition), Value(raw.GetProperty("outV")));
            destination = new(requested.Scope, Value(inPartition), Value(raw.GetProperty("inV")));
        }
        var result = new GraphRecord(1, kind, requested, raw.GetProperty("label").GetString()!, source, destination,
            properties, new("cosmos-gremlin", consistency, observation, true,
                raw.EnumerateObject().Where(p => !new[] { "id", "label", "type", "properties", "outV", "inV", "outVPartition", "inVPartition" }.Contains(p.Name))
                    .ToDictionary(p => p.Name, p => p.Value.GetRawText())));
        result.Validate(); return result;
    }
    public static async Task<object> Extract(Project project, string root, string inventory, string evidencePath,
        string output, CancellationToken token)
    {
        var source = project.Source ?? throw new Blocker("SOURCE_REQUIRED", "Cosmos source configuration required.");
        var inventoryPath = Codec.Owned(root, inventory);
        var hash = Codec.FileHash(inventoryPath, token);
        var evidence = Codec.Parse<SourceEvidence>(Codec.Bounded(Codec.Owned(root, evidencePath)));
        if (evidence.SchemaVersion != 1 || evidence.Status != "verified" || evidence.Host != source.Host ||
            evidence.Database != source.Database || evidence.Container != source.Container || evidence.InventoryHash != hash ||
            evidence.Consistency != project.Consistency || !evidence.PropertyIds || !evidence.MetaProperties ||
            !evidence.TypedValues || !evidence.PartitionQualifiedEndpoints ||
            !DateTimeOffset.TryParse(evidence.ObservedAt, out var observed) || observed < DateTimeOffset.UtcNow.AddHours(-24) ||
            observed > DateTimeOffset.UtcNow)
            throw new Blocker("SOURCE_COMPATIBILITY", "Fresh reviewed endpoint/inventory-specific fidelity evidence required; this worker cannot infer compatibility.");
        var path = Codec.Owned(root, output);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        using var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        using var writer = new StreamWriter(file);
        var server = new GremlinServer(source.Host, 443, true, "/dbs/" + source.Database + "/colls/" + source.Container,
            Authorizations.Secret(source.AuthenticationRef));
        using var client = new GremlinClient(server, new GraphSON2Reader(), new GraphSON2Writer(), "application/vnd.gremlin-v2.0+json");
        var seen = new HashSet<string>(); long count = 0;
        foreach (var line in Codec.Lines(inventoryPath, 16384, token))
        {
            token.ThrowIfCancellationRequested();
            if (line.Length > 16384) throw new Blocker("INVENTORY_BOUND", "Identity inventory record exceeds bound.");
            var entry = Codec.Parse<InventoryEntry>(line); entry.Identity.Validate(entry.Kind);
            if (!seen.Add(entry.Identity.Key(entry.Kind)) || seen.Count > 100000)
                throw new Blocker("INVENTORY_BOUND", "Duplicate identities or 100000-identity extraction bound exceeded.");
            if (entry.Identity.Scope != source.Scope || entry.Identity.Id.Type != "string" || entry.Identity.Partition.Type != "string")
                throw new Blocker("SOURCE_ID_TYPE", "Automatic source reads currently support reviewed string partition/ID inventories only.");
            var bindings = new Dictionary<string, object>
            {
                ["partition"] = entry.Identity.Partition.Lexical,
                ["id"] = entry.Identity.Id.Lexical
            };
            string query;
            if (entry.Kind == "vertex") query = "g.V([partition,id])";
            else if (entry.Kind == "edge" && entry.Identity.Owner?.Type == "string")
            {
                bindings["owner"] = entry.Identity.Owner.Lexical;
                query = "g.V([partition,owner]).outE().hasId(id)";
            }
            else throw new Blocker("EDGE_OWNER", "Owner-qualified string edge identity required.");
            var response = await client.SubmitAsync<dynamic>(query, bindings).WaitAsync(TimeSpan.FromSeconds(project.Limits.TimeoutSeconds), token);
            if (response.Count != 1) throw new Blocker("SOURCE_MISSING", "Source identity missing or ambiguous.");
            var raw = JsonSerializer.SerializeToElement((object)response.First());
            var record = Decode(raw, entry.Identity, project.Consistency, evidence.ObservedAt);
            await writer.WriteLineAsync(Codec.Text(record).AsMemory(), token); count++;
        }
        await writer.FlushAsync(token); file.Flush(true);
        writer.Dispose();
        file.Dispose();
        if (hash != Codec.FileHash(inventoryPath)) throw new Blocker("SOURCE_CHANGED", "Identity inventory changed during extraction.");
        Codec.NewFile(path + ".manifest.json", Codec.Text(new
        {
            schemaVersion = 1,
            status = "succeeded",
            records = count,
            inputHash = project.InputHash,
            sourceHash = Codec.FileHash(path),
            inventoryHash = hash
        }));
        return new { status = "succeeded", records = count, output, sha256 = Codec.FileHash(path) };
    }
}
public sealed record InventoryEntry(string Kind, Identity Identity);
