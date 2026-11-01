using System.Data;
using System.Text.Json;
using Npgsql;

namespace Gremlin2Age;

public sealed class AgeStore : IAsyncDisposable
{
    private readonly Project project;
    private readonly NpgsqlConnection connection;
    private string Schema => Codec.Identifier(project.Target!.LedgerSchema);
    private string Graph => Codec.Identifier(project.Target!.Graph);
    public AgeStore(Project project)
    {
        this.project = project;
        var target = project.Target ?? throw new Blocker("TARGET_REQUIRED", "Existing PostgreSQL/AGE target required.");
        var builder = new NpgsqlConnectionStringBuilder
        {
            Host = target.Host,
            Port = target.Port,
            Database = target.Database,
            Username = target.Username,
            Password = Authorizations.Secret(target.AuthenticationRef),
            SslMode = SslMode.VerifyFull,
            Timeout = project.Limits.TimeoutSeconds,
            CommandTimeout = project.Limits.TimeoutSeconds,
            Pooling = false,
            IncludeErrorDetail = false
        };
        connection = new(builder.ConnectionString);
    }
    public async Task Open(CancellationToken token)
    {
        await connection.OpenAsync(token);
        // AGE must already be installed and permitted by the operator; this does not install/enable it.
        await Sql("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public", null, token);
        var exists = await Scalar("SELECT count(*) FROM ag_catalog.ag_graph WHERE name=@graph", null, token, ("graph", Graph));
        if (Convert.ToInt64(exists) != 1) throw new Blocker("AGE_GRAPH_MISSING", "Ask the operator to create the dedicated target AGE graph.");
    }
    private async Task Sql(string sql, NpgsqlTransaction? transaction, CancellationToken token,
        params (string, object)[] parameters)
    {
        await using var command = new NpgsqlCommand(sql, connection, transaction);
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value);
        await command.ExecuteNonQueryAsync(token);
    }
    private async Task<object?> Scalar(string sql, NpgsqlTransaction? transaction, CancellationToken token,
        params (string, object)[] parameters)
    {
        await using var command = new NpgsqlCommand(sql, connection, transaction);
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value);
        return await command.ExecuteScalarAsync(token);
    }
    private async Task Lock(NpgsqlTransaction tx, CancellationToken token) =>
        await Sql("SELECT pg_advisory_xact_lock(hashtextextended(@resource,0))", tx, token, ("resource", "gremlin2age/graph/" + Graph));
    private async Task RequireGraphOwner(NpgsqlTransaction tx, CancellationToken token)
    {
        var owners = await ReadNative("MATCH (o:G2ROwner) RETURN count(o)", new { }, token, tx);
        var matches = await ReadNative("MATCH (o:G2ROwner) WHERE o.runId=$run AND o.inputHash=$hash AND o.ledgerSchema=$schema RETURN count(o)",
            new { run = project.RunId, hash = project.InputHash, schema = Schema }, token, tx);
        if (owners.Count != 1 || matches.Count != 1 || Agtype.Decode(owners[0]).Lexical != "1" || Agtype.Decode(matches[0]).Lexical != "1")
            throw new Blocker("TARGET_OWNERSHIP", "Native graph ownership does not match this run/input/ledger.");
    }
    private async Task Initialize(NpgsqlTransaction tx, string sourceHash, CancellationToken token)
    {
        Authorizations.Require(project, "load", sourceHash);
        await Sql($"CREATE SCHEMA IF NOT EXISTS {Schema}; " +
            $"CREATE TABLE IF NOT EXISTS {Schema}.owner (singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), graph text NOT NULL, run_id text NOT NULL, input_hash text NOT NULL);" +
            $"CREATE TABLE IF NOT EXISTS {Schema}.records (external_id text PRIMARY KEY, run_id text NOT NULL, kind text NOT NULL CHECK(kind IN ('vertex','edge')), canonical text NOT NULL, hash text NOT NULL);" +
            $"CREATE TABLE IF NOT EXISTS {Schema}.requests (request_hash text PRIMARY KEY, run_id text NOT NULL, operation text NOT NULL, external_id text NOT NULL);" +
            $"CREATE TABLE IF NOT EXISTS {Schema}.batches (run_id text NOT NULL, number bigint NOT NULL, input_hash text NOT NULL, source_hash text NOT NULL, batch_hash text NOT NULL, records bigint NOT NULL, PRIMARY KEY(run_id,number));", tx, token);
        await Sql($"INSERT INTO {Schema}.owner(singleton,graph,run_id,input_hash) VALUES(true,@graph,@run,@hash) ON CONFLICT DO NOTHING",
            tx, token, ("graph", Graph), ("run", project.RunId), ("hash", project.InputHash));
        var matches = await Scalar($"SELECT count(*) FROM {Schema}.owner WHERE graph=@graph AND run_id=@run AND input_hash=@hash",
            tx, token, ("graph", Graph), ("run", project.RunId), ("hash", project.InputHash));
        if (Convert.ToInt64(matches) != 1) throw new Blocker("TARGET_OWNERSHIP", "Dedicated ledger/graph is owned by another run or input.");
    }
    private async Task CypherWrite(string query, NpgsqlTransaction tx, CancellationToken token)
    {
        if (query.Contains("$g2r$", StringComparison.Ordinal)) throw new Blocker("CYPHER_DELIMITER", "Invalid query delimiter.");
        // AGE documents parameterized prepared statements for reads, not arbitrary writes.
        // Writes contain only fixed templates, validated identifiers and escaped literal values.
        await Sql($"SELECT * FROM ag_catalog.cypher('{Graph}', $g2r${query}$g2r$) AS (result ag_catalog.agtype)", tx, token);
    }
    private async Task Add(GraphRecord record, NpgsqlTransaction tx, CancellationToken token)
    {
        record.Validate();
        var canonical = Codec.Text(record); var hash = Codec.Hash(canonical);
        var existing = await Scalar($"SELECT hash FROM {Schema}.records WHERE external_id=@id", tx, token, ("id", record.ExternalId));
        if (existing != null)
        {
            if ((string)existing != hash) throw new Blocker("IDENTITY_CONFLICT", "An external identity already has different canonical content.");
            return;
        }
        var props = "{externalId:" + GremlinCompiler.Literal(record.ExternalId) + ",runId:" +
            GremlinCompiler.Literal(project.RunId) + ",sourceLabel:" + GremlinCompiler.Literal(record.Label) + "}";
        if (record.Kind == "vertex")
            await CypherWrite($"CREATE (v:G2RVertex {props}) RETURN v.externalId", tx, token);
        else
        {
            foreach (var endpoint in new[] { record.Source!, record.Destination! })
            {
                var found = await Scalar($"SELECT count(*) FROM {Schema}.records WHERE external_id=@id AND kind='vertex' AND run_id=@run",
                    tx, token, ("id", endpoint.Key("vertex")), ("run", project.RunId));
                if (Convert.ToInt64(found) != 1) throw new Blocker("ENDPOINT_NOT_LOADED", "Load referenced vertices before edges.");
            }
            await CypherWrite($"MATCH (s:G2RVertex),(d:G2RVertex) WHERE s.externalId={GremlinCompiler.Literal(record.Source!.Key("vertex"))}" +
                $" AND d.externalId={GremlinCompiler.Literal(record.Destination!.Key("vertex"))} AND s.runId={GremlinCompiler.Literal(project.RunId)}" +
                $" AND d.runId={GremlinCompiler.Literal(project.RunId)} CREATE (s)-[e:G2REdge {props}]->(d) RETURN e.externalId", tx, token);
        }
        await Sql($"INSERT INTO {Schema}.records(external_id,run_id,kind,canonical,hash) VALUES(@id,@run,@kind,@record,@hash)",
            tx, token, ("id", record.ExternalId), ("run", project.RunId), ("kind", record.Kind), ("record", canonical), ("hash", hash));
    }
    public async Task<bool> Batch(long number, string sourceHash, List<GraphRecord> records, CancellationToken token)
    {
        Authorizations.Require(project, "load", sourceHash);
        var batchHash = Codec.Hash(Codec.Text(records));
        // Each statement sees state committed before acquisition of the graph-wide writer lock.
        await using var tx = await connection.BeginTransactionAsync(IsolationLevel.ReadCommitted, token);
        await Lock(tx, token); await Initialize(tx, sourceHash, token);
        var previous = await Scalar($"SELECT batch_hash FROM {Schema}.batches WHERE run_id=@run AND number=@number",
            tx, token, ("run", project.RunId), ("number", number));
        if (previous != null)
        {
            await RequireGraphOwner(tx, token);
            if ((string)previous != batchHash || !await Marker(number, sourceHash, batchHash, token, tx))
                throw new Blocker("BATCH_CONFLICT", "Persisted batch marker differs; do not advance checkpoint.");
            await tx.RollbackAsync(token); return true;
        }
        if (number == 1)
        {
            var native = await ReadNative("MATCH (v) RETURN count(v)", new { }, token, tx);
            if (native.Count != 1 || Agtype.Decode(native[0]).Lexical != "0")
                throw new Blocker("TARGET_NOT_EMPTY", "Initial load requires an empty dedicated AGE graph.");
            await CypherWrite("CREATE (o:G2ROwner {runId:" + GremlinCompiler.Literal(project.RunId) +
                ",inputHash:" + GremlinCompiler.Literal(project.InputHash) + ",ledgerSchema:" + GremlinCompiler.Literal(Schema) + "}) RETURN o.runId", tx, token);
        }
        else await RequireGraphOwner(tx, token);
        foreach (var record in records) await Add(record, tx, token);
        await Sql($"INSERT INTO {Schema}.batches(run_id,number,input_hash,source_hash,batch_hash,records) VALUES(@run,@number,@input,@source,@batch,@records)",
            tx, token, ("run", project.RunId), ("number", number), ("input", project.InputHash),
            ("source", sourceHash), ("batch", batchHash), ("records", (long)records.Count));
        Authorizations.Require(project, "load", sourceHash);
        // If COMMIT loses its reply, caller reopens and checks the exact committed marker. No blind retry.
        await tx.CommitAsync(token); return true;
    }
    public async Task<bool> Marker(long number, string sourceHash, string batchHash, CancellationToken token, NpgsqlTransaction? tx = null)
    {
        var value = await Scalar($"SELECT count(*) FROM {Schema}.batches WHERE run_id=@run AND number=@number AND input_hash=@input AND source_hash=@source AND batch_hash=@batch",
            tx, token, ("run", project.RunId), ("number", number), ("input", project.InputHash), ("source", sourceHash), ("batch", batchHash));
        return Convert.ToInt64(value) == 1;
    }
    public async Task<List<string>> ReadNative(string cypher, object parameters, CancellationToken token, NpgsqlTransaction? tx = null)
    {
        if (cypher.Contains("$g2r$", StringComparison.Ordinal)) throw new Blocker("CYPHER_DELIMITER", "Invalid delimiter.");
        await using var command = new NpgsqlCommand(
            $"SELECT CASE WHEN octet_length(result::text)<=262144 THEN result::text ELSE NULL END FROM ag_catalog.cypher('{Graph}', $g2r${cypher}$g2r$, @parameters::ag_catalog.agtype) AS (result ag_catalog.agtype)", connection, tx);
        command.Parameters.AddWithValue("parameters", Codec.Text(parameters));
        await command.PrepareAsync(token);
        var output = new List<string>();
        long bytes = 0;
        await using var reader = await command.ExecuteReaderAsync(token);
        while (await reader.ReadAsync(token))
        {
            if (output.Count >= 10000) throw new Blocker("QUERY_BOUND", "Read result exceeds 10000 rows; narrow the query.");
            if (reader.IsDBNull(0)) throw new Blocker("QUERY_RECORD_BOUND", "Native result exceeds its byte bound.");
            var value = reader.GetString(0); bytes += System.Text.Encoding.UTF8.GetByteCount(value);
            if (bytes > 1048576) throw new Blocker("QUERY_BYTE_BOUND", "Native result exceeds its total byte bound.");
            output.Add(value);
        }
        return output;
    }
    public async Task<GraphRecord> ReadIdentity(string id, CancellationToken token)
    {
        var text = await Scalar($"SELECT CASE WHEN octet_length(canonical)<=262144 THEN canonical ELSE '' END FROM {Schema}.records WHERE external_id=@id AND run_id=@run",
            null, token, ("id", id), ("run", project.RunId));
        if (text is not string record) throw new Blocker("NOT_FOUND", "Typed external identity not found.");
        if (record.Length == 0) throw new Blocker("RECORD_BOUND", "Canonical ledger record exceeds its byte bound.");
        var parsed = Codec.Parse<GraphRecord>(record); parsed.Validate(); return parsed;
    }
    public async Task<List<object>> Query(Translation translation, CancellationToken token)
    {
        var native = await ReadNative(translation.Cypher, new { run = project.RunId }, token);
        var output = new List<object>();
        long bytes = 0;
        void Add(object value)
        {
            bytes += System.Text.Encoding.UTF8.GetByteCount(Codec.Text(value));
            if (bytes > 940000) throw new Blocker("QUERY_BYTE_BOUND", "Typed result exceeds the bounded worker response capacity.");
            output.Add(value);
        }
        foreach (var raw in native)
        {
            var value = Agtype.Decode(raw);
            if (value.Type != "string") throw new Blocker("NATIVE_ID", "AGE did not return a typed external identity.");
            var record = await ReadIdentity(value.Lexical, token);
            switch (translation.Projection)
            {
                case "id": Add(record.Identity.Id); break;
                case "label": Add(new TypedValue("string", record.Label)); break;
                case "values": foreach (var property in record.Properties.Where(p => p.Key == translation.Property)) Add(property.Value); break;
                default: Add(record); break;
            }
            if (output.Count > 10000) throw new Blocker("QUERY_BOUND", "Typed result bound exceeded.");
        }
        if (translation.Deduplicate) output = output.DistinctBy(DedupKey).ToList();
        if (translation.Limit != null) output = output.Take(translation.Limit.Value).ToList();
        if (translation.Count) return [new TypedValue("integer", output.Count.ToString(System.Globalization.CultureInfo.InvariantCulture))];
        return output;
    }
    public static string DedupKey(object value)
    {
        if (value is GraphRecord record) return record.ExternalId;
        if (value is not TypedValue typed) throw new Blocker("DEDUP_TYPE", "Unsupported projection equality.");
        var culture = System.Globalization.CultureInfo.InvariantCulture;
        return typed.Type + ":" + (typed.Type switch
        {
            "integer" => System.Numerics.BigInteger.Parse(typed.Lexical, culture).ToString(culture),
            "double" => BitConverter.DoubleToInt64Bits(double.Parse(typed.Lexical, culture)).ToString(culture),
            "float" => BitConverter.SingleToInt32Bits(float.Parse(typed.Lexical, culture)).ToString(culture),
            _ => typed.Lexical
        });
    }
    public async Task<object> ValidateFile(string path, CancellationToken token)
    {
        var sourceHash = Codec.FileHash(path, token);
        long records = 0, vertices = 0, edges = 0;
        await using var tx = await connection.BeginTransactionAsync(IsolationLevel.RepeatableRead, token);
        await Sql("SET TRANSACTION READ ONLY", tx, token);
        var owned = await Scalar($"SELECT count(*) FROM {Schema}.owner WHERE graph=@graph AND run_id=@run AND input_hash=@hash",
            tx, token, ("graph", Graph), ("run", project.RunId), ("hash", project.InputHash));
        if (Convert.ToInt64(owned) != 1) throw new Blocker("TARGET_OWNERSHIP", "Validation requires the exact owned target/run.");
        await RequireGraphOwner(tx, token);
        foreach (var line in Codec.Lines(path, 262144, token))
        {
            token.ThrowIfCancellationRequested();
            if (line.Length > 262144) throw new Blocker("RECORD_BOUND", "Record bound exceeded.");
            var record = Codec.Parse<GraphRecord>(line); record.Validate();
            var actual = await Scalar($"SELECT canonical FROM {Schema}.records WHERE external_id=@id AND run_id=@run",
                tx, token, ("id", record.ExternalId), ("run", project.RunId));
            if (actual is not string text || text != Codec.Text(record)) throw new Blocker("FIDELITY_MISMATCH", "Canonical ledger record mismatch.");
            string match = record.Kind == "vertex" ? "MATCH (v:G2RVertex)" : "MATCH ()-[v:G2REdge]->()";
            var count = await ReadNative($"{match} WHERE v.externalId=$id AND v.runId=$run AND v.sourceLabel=$label RETURN count(v)",
                new { id = record.ExternalId, run = project.RunId, label = record.Label }, token, tx);
            if (count.Count != 1 || Agtype.Decode(count[0]).Lexical != "1") throw new Blocker("NATIVE_FIDELITY", "Native graph identity multiplicity differs.");
            if (record.Kind == "edge")
            {
                var endpoints = await ReadNative("MATCH (s:G2RVertex)-[e:G2REdge]->(d:G2RVertex) WHERE e.externalId=$id AND e.runId=$run RETURN [s.externalId,d.externalId,e.sourceLabel]",
                    new { id = record.ExternalId, run = project.RunId }, token, tx);
                if (endpoints.Count != 1) throw new Blocker("EDGE_FIDELITY", "Native edge missing or duplicated.");
                var observed = Agtype.Decode(endpoints[0]).Structure;
                if (observed is not { ValueKind: JsonValueKind.Array } array || array.GetArrayLength() != 3 ||
                    array[0].GetString() != record.Source!.Key("vertex") ||
                    array[1].GetString() != record.Destination!.Key("vertex") || array[2].GetString() != record.Label)
                    throw new Blocker("EDGE_FIDELITY", "Native edge direction, endpoints or label differs.");
            }
            records++; if (record.Kind == "vertex") vertices++; else edges++;
        }
        var total = await Scalar($"SELECT count(*) FROM {Schema}.records WHERE run_id=@run", tx, token, ("run", project.RunId));
        if (Convert.ToInt64(total) != records) throw new Blocker("LEDGER_COUNT", "Ledger has missing or extra records.");
        foreach (var (cypher, expected) in new[] { ("MATCH (v) RETURN count(v)", vertices + 1),
            ("MATCH ()-[e]->() RETURN count(e)", edges) })
        {
            var count = await ReadNative(cypher, new { run = project.RunId }, token, tx);
            if (count.Count != 1 || Agtype.Decode(count[0]).Lexical != expected.ToString(System.Globalization.CultureInfo.InvariantCulture))
                throw new Blocker("NATIVE_COUNT", "Native graph has missing or extra records.");
        }
        if (sourceHash != Codec.FileHash(path)) throw new Blocker("INPUT_CHANGED", "Validation input changed; no fidelity result established.");
        await tx.CommitAsync(token);
        return new { status = "succeeded", records, vertices, edges, sourceHash, liveCompatibility = "not-established" };
    }
    public async Task Upsert(GraphRecord record, CancellationToken token)
    {
        var resourceHash = Codec.Hash(Codec.Text(record));
        Authorizations.Require(project, "crud", resourceHash); record.Validate();
        await using var tx = await connection.BeginTransactionAsync(IsolationLevel.ReadCommitted, token);
        await Lock(tx, token);
        await RequireGraphOwner(tx, token);
        var owned = await Scalar($"SELECT count(*) FROM {Schema}.owner WHERE run_id=@run AND input_hash=@hash", tx, token,
            ("run", project.RunId), ("hash", project.InputHash));
        if (Convert.ToInt64(owned) != 1) throw new Blocker("TARGET_OWNERSHIP", "CRUD requires initialized owned target.");
        var old = await Scalar($"SELECT canonical FROM {Schema}.records WHERE external_id=@id", tx, token, ("id", record.ExternalId));
        if (await RequestMarker(resourceHash, token, tx))
        {
            if (old is not string replayed || replayed != Codec.Text(record))
                throw new Blocker("CRUD_REPLAY_CONFLICT", "Previously committed request no longer matches current record.");
            await tx.RollbackAsync(token); return;
        }
        if (old is string prior)
        {
            var existing = Codec.Parse<GraphRecord>(prior);
            if (existing.Kind != record.Kind || existing.Label != record.Label ||
                Codec.Text(existing.Source) != Codec.Text(record.Source) || Codec.Text(existing.Destination) != Codec.Text(record.Destination))
                throw new Blocker("CRUD_TOPOLOGY", "Update supports properties/provenance only; topology changes require explicit delete/create.");
            await Sql($"UPDATE {Schema}.records SET canonical=@canonical,hash=@hash WHERE external_id=@id",
                tx, token, ("canonical", Codec.Text(record)), ("hash", Codec.Hash(Codec.Text(record))), ("id", record.ExternalId));
        }
        else await Add(record, tx, token);
        Authorizations.Require(project, "crud", resourceHash);
        await Sql($"INSERT INTO {Schema}.requests(request_hash,run_id,operation,external_id) VALUES(@hash,@run,'upsert',@id)",
            tx, token, ("hash", resourceHash), ("run", project.RunId), ("id", record.ExternalId));
        await CommitRequest(tx, resourceHash, token);
    }
    public async Task Delete(Identity identity, string kind, string expectedHash, CancellationToken token)
    {
        var resourceHash = Codec.Hash(Codec.Text(new DeleteRequest(identity, kind, expectedHash)));
        Authorizations.Require(project, "crud", resourceHash); identity.Validate(kind);
        if (kind is not ("vertex" or "edge")) throw new Blocker("CRUD_KIND", "Typed vertex/edge required.");
        await using var tx = await connection.BeginTransactionAsync(IsolationLevel.ReadCommitted, token);
        await Lock(tx, token);
        await RequireGraphOwner(tx, token);
        var owned = await Scalar($"SELECT count(*) FROM {Schema}.owner WHERE run_id=@run AND input_hash=@hash", tx, token,
            ("run", project.RunId), ("hash", project.InputHash));
        if (Convert.ToInt64(owned) != 1) throw new Blocker("TARGET_OWNERSHIP", "Delete requires initialized owned target.");
        var id = identity.Key(kind);
        var current = await Scalar($"SELECT hash FROM {Schema}.records WHERE external_id=@id AND run_id=@run", tx, token,
            ("id", id), ("run", project.RunId));
        if (await RequestMarker(resourceHash, token, tx))
        {
            if (current != null) throw new Blocker("CRUD_REPLAY_CONFLICT", "Deleted identity has been recreated; new authorization required.");
            await tx.RollbackAsync(token); return;
        }
        if (current is not string hash || hash != expectedHash) throw new Blocker("CRUD_EXPECTED_HASH", "Exact reviewed current record checksum required.");
        if (kind == "vertex")
        {
            var incident = await ReadNative("MATCH (v:G2RVertex)-[e:G2REdge]-() WHERE v.externalId=$id AND v.runId=$run RETURN e.externalId",
                new { id, run = project.RunId }, token, tx);
            if (incident.Count > 0) throw new Blocker("CRUD_INCIDENT_EDGES", "Delete each incident edge with its own scoped request before deleting a vertex.");
            await CypherWrite($"MATCH (v:G2RVertex) WHERE v.externalId={GremlinCompiler.Literal(id)} AND v.runId={GremlinCompiler.Literal(project.RunId)} DELETE v", tx, token);
        }
        else await CypherWrite($"MATCH ()-[e:G2REdge]->() WHERE e.externalId={GremlinCompiler.Literal(id)} AND e.runId={GremlinCompiler.Literal(project.RunId)} DELETE e", tx, token);
        await Sql($"DELETE FROM {Schema}.records WHERE external_id=@id AND run_id=@run", tx, token, ("id", id), ("run", project.RunId));
        Authorizations.Require(project, "crud", resourceHash);
        await Sql($"INSERT INTO {Schema}.requests(request_hash,run_id,operation,external_id) VALUES(@hash,@run,'delete',@id)",
            tx, token, ("hash", resourceHash), ("run", project.RunId), ("id", id));
        await CommitRequest(tx, resourceHash, token);
    }
    public async Task<bool> RequestMarker(string hash, CancellationToken token, NpgsqlTransaction? tx = null) =>
        Convert.ToInt64(await Scalar($"SELECT count(*) FROM {Schema}.requests WHERE request_hash=@hash AND run_id=@run",
            tx, token, ("hash", hash), ("run", project.RunId))) == 1;
    private async Task CommitRequest(NpgsqlTransaction tx, string hash, CancellationToken token)
    {
        try { await tx.CommitAsync(token); }
        catch (Exception error) when (error is NpgsqlException or OperationCanceledException or IOException)
        {
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(project.Limits.TimeoutSeconds));
            await using var reconcile = new AgeStore(project); await reconcile.Open(deadline.Token);
            if (!await reconcile.RequestMarker(hash, deadline.Token))
                throw new Blocker("CRUD_COMMIT_UNRESOLVED", "Request commit not confirmed; preserve exact request and reconcile before retry.");
        }
    }
    public ValueTask DisposeAsync() => connection.DisposeAsync();
}
public sealed record DeleteRequest(Identity Identity, string Kind, string ExpectedHash);
