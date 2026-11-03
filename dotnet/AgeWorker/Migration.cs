namespace Gremlin2Age;

public sealed record LoadCheckpoint(int SchemaVersion, string RunId, string InputHash, string SourceHash,
    long Batch, long Records, string BatchHash);
public sealed record ExtractionManifest(int SchemaVersion, string Status, long Records, string InputHash, string SourceHash, string InventoryHash);
public static class Migration
{
    public static async Task<object> Load(Project project, string root, string input, string checkpoint,
        CancellationToken token, Action<object> progress)
    {
        var path = Codec.Owned(root, input); var checkpointPath = Codec.Owned(root, checkpoint);
        using var immutableInput = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        var hash = Codec.FileHash(path, token);
        Authorizations.Require(project, "load", hash);
        if (File.Exists(checkpointPath + ".pending")) throw new Blocker("CHECKPOINT_PENDING", "Pending checkpoint requires reviewed reconciliation; preserve both artifacts.");
        LoadCheckpoint? previous = File.Exists(checkpointPath)
            ? Codec.Parse<LoadCheckpoint>(Codec.Bounded(checkpointPath)) : null;
        if (previous != null && (previous.SchemaVersion != 1 || previous.RunId != project.RunId ||
            previous.InputHash != project.InputHash || previous.SourceHash != hash || previous.Batch < 0 || previous.Records < 0))
            throw new Blocker("CHECKPOINT_INPUT", "Checkpoint does not match this run/input/source.");
        // Full streaming validation before the first mutation: vertices precede all edges, complete records only.
        var seen = new HashSet<string>(); bool edgesStarted = false, observedSource = false;
        foreach (var line in Codec.Lines(path, 262144, token))
        {
            token.ThrowIfCancellationRequested();
            if (line.Length > 262144) throw new Blocker("RECORD_BOUND", "Canonical record exceeds bound.");
            var record = Codec.Parse<GraphRecord>(line); record.Validate();
            observedSource |= record.Provenance.Source == "cosmos-gremlin";
            if (record.Provenance.Consistency != project.Consistency) throw new Blocker("CONSISTENCY", "Canonical input consistency differs.");
            if (!seen.Add(record.ExternalId) || seen.Count > 1000000) throw new Blocker("INPUT_IDENTITY", "Duplicate canonical identity or one-million record bound exceeded.");
            if (record.Kind == "edge")
            {
                edgesStarted = true;
                if (!seen.Contains(record.Source!.Key("vertex")) || !seen.Contains(record.Destination!.Key("vertex")))
                    throw new Blocker("INPUT_ENDPOINT", "Both endpoint vertices must occur before edges.");
            }
            else if (edgesStarted) throw new Blocker("INPUT_ORDER", "Canonical input must contain vertices before edges.");
        }
        if (seen.Count == 0) throw new Blocker("EMPTY_INPUT", "Reviewed nonempty input required.");
        if (observedSource)
        {
            var manifest = Codec.Parse<ExtractionManifest>(Codec.Bounded(Codec.Owned(root, input + ".manifest.json")));
            if (manifest.SchemaVersion != 1 || manifest.Status != "succeeded" || manifest.Records != seen.Count ||
                manifest.InputHash != project.InputHash || manifest.SourceHash != hash)
                throw new Blocker("EXTRACTION_INCOMPLETE", "Complete matching source extraction manifest required.");
        }
        if (hash != Codec.FileHash(path, token)) throw new Blocker("INPUT_CHANGED", "Canonical input changed before loading.");
        await using var store = new AgeStore(project); await store.Open(token);
        long batchNumber = 0, recordsConfirmed = 0; var batch = new List<GraphRecord>(); int bytes = 0;
        async Task Commit()
        {
            if (batch.Count == 0) return;
            batchNumber++;
            var batchHash = Codec.Hash(Codec.Text(batch));
            if (previous != null && batchNumber <= previous.Batch)
            {
                if (!await store.Marker(batchNumber, hash, batchHash, token))
                    throw new Blocker("CHECKPOINT_RECONCILIATION", "Previously acknowledged batch marker is absent or changed.");
                if (batchNumber == previous.Batch && (previous.BatchHash != batchHash || previous.Records != recordsConfirmed + batch.Count))
                    throw new Blocker("CHECKPOINT_RECONCILIATION", "Checkpoint metadata does not match its exact confirmed prefix.");
            }
            else
            {
                try { await store.Batch(batchNumber, hash, batch, token); }
                catch (Exception error) when (error is Npgsql.NpgsqlException or OperationCanceledException or IOException)
                {
                    using var reconcileDeadline = new CancellationTokenSource(TimeSpan.FromSeconds(project.Limits.TimeoutSeconds));
                    await using var reconcile = new AgeStore(project);
                    await reconcile.Open(reconcileDeadline.Token);
                    if (!await reconcile.Marker(batchNumber, hash, batchHash, reconcileDeadline.Token))
                        throw new Blocker("COMMIT_UNRESOLVED", "Commit was not confirmed; preserve checkpoint and retry only after exact marker reconciliation.");
                }
            }
            recordsConfirmed += batch.Count;
            if (previous == null || batchNumber > previous.Batch)
                Codec.Checkpoint(checkpointPath, Codec.Text(new LoadCheckpoint(1, project.RunId, project.InputHash,
                    hash, batchNumber, recordsConfirmed, batchHash)));
            progress(new { recordsConfirmed, batch = batchNumber, status = "confirmed" });
            batch.Clear(); bytes = 0;
        }
        foreach (var line in Codec.Lines(path, 262144, token))
        {
            token.ThrowIfCancellationRequested();
            var record = Codec.Parse<GraphRecord>(line); var length = System.Text.Encoding.UTF8.GetByteCount(line);
            if (batch.Count > 0 && (batch.Count >= project.Limits.BatchRecords || bytes + length > project.Limits.BatchBytes))
                await Commit();
            batch.Add(record); bytes += length;
        }
        await Commit();
        if (previous != null && (batchNumber < previous.Batch || recordsConfirmed < previous.Records))
            throw new Blocker("CHECKPOINT_PAST_END", "Checkpoint extends beyond canonical input.");
        if (hash != Codec.FileHash(path, token)) throw new Blocker("INPUT_CHANGED", "Input changed while loading; preserve confirmed progress.");
        return new { status = "succeeded", recordsConfirmed, batches = batchNumber, sourceHash = hash };
    }
}
