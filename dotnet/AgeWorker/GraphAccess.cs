namespace Gremlin2Age;

/// <summary>Typed access to an owned AGE projection and its lossless canonical ledger.</summary>
public sealed class GraphAccess(Project project)
{
    public async Task<List<object>> QueryAsync(string literalGremlin, CancellationToken cancellationToken)
    {
        project.Validate();
        await using var store = new AgeStore(project); await store.Open(cancellationToken);
        return await store.Query(GremlinCompiler.Compile(literalGremlin), cancellationToken);
    }
    public async Task<GraphRecord> ReadAsync(Identity identity, string kind, CancellationToken cancellationToken)
    {
        project.Validate();
        identity.Validate(kind);
        await using var store = new AgeStore(project); await store.Open(cancellationToken);
        return await store.ReadIdentity(identity.Key(kind), cancellationToken);
    }
    public async Task UpsertAsync(GraphRecord record, CancellationToken cancellationToken)
    {
        project.Validate(); record.Validate(); Authorizations.Require(project, "crud", Codec.Hash(Codec.Text(record)));
        await using var store = new AgeStore(project); await store.Open(cancellationToken);
        await store.Upsert(record, cancellationToken);
    }
    public async Task DeleteAsync(Identity identity, string kind, string expectedHash, CancellationToken cancellationToken)
    {
        project.Validate(); identity.Validate(kind);
        Authorizations.Require(project, "crud", Codec.Hash(Codec.Text(new DeleteRequest(identity, kind, expectedHash))));
        await using var store = new AgeStore(project); await store.Open(cancellationToken);
        await store.Delete(identity, kind, expectedHash, cancellationToken);
    }
}
