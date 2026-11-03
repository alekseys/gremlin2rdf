# PostgreSQL / Apache AGE migration

The independent .NET worker maps a complete canonical graph to native AGE vertices/edges and an owned PostgreSQL
identity/property ledger in the same transaction. It does not produce RDF, call Java, provision PostgreSQL,
install/enable AGE or perform production cutover.

## Operator prerequisites

Use an operator-installed compatible PostgreSQL/AGE instance, trusted TLS certificate and an empty dedicated
graph. The migration login needs network access, permission to `LOAD 'age'`, access to the graph through
`ag_catalog.cypher`, and permission to create its dedicated ledger schema/tables. On managed PostgreSQL,
the operator must separately configure extension allowlisting and any required server/session libraries;
availability depends on the provider/version. No Azure allowlist edits or restarts are performed by the extension.

This deliverable has **not executed native PostgreSQL/AGE acceptance** or live Cosmos extraction. Its SQL,
prepared read queries, supported Cypher mutation templates and transaction/recovery behavior need a bounded
operator-approved check on the exact server versions before live migration. In particular, multi-hop relationship
reuse/endpoint Cypher execution is not empirically established. A compiler smoke is not database compatibility.

## Workbench

Enable `cosmosDB.experimental.graphMigration.enabled`, then open **Gremlin to PostgreSQL AGE Migration**.
Save connections (or a source/target-null project for offline work). Secrets are stored by the host in SecretStorage
or supplied through referenced `AGE_*` environment variables; never enter secret values in JSON or source evidence.
Default references are `env:AGE_SOURCE_KEY`, `env:AGE_TARGET_PASSWORD` and host-owned `env:AGE_APPROVAL_KEY`.

Import your reviewed source inventory and endpoint observations, or a complete canonical input.
The fixed mapping keeps every property occurrence ID, original value type/lexical representation, meta-property
and provenance in the ledger. Native `G2RVertex`/`G2REdge` objects carry stable external IDs, run ownership and
source labels. Parallel edges and self-loops are distinct objects. Original property values are **not** flattened
into AGE maps; translated `values()` reads consult the authoritative ledger.
Canonical numeric types currently cover arbitrary integers, finite floats and doubles with their original lexical
forms. Other declared types are blockers, not coerced values. Duplicate JSON keys are rejected.
Additional returned source fields are retained as bounded raw-JSON provenance observations; this preserves their
observed representation without claiming unverified original metadata types.

Review and authorize the **exact canonical file checksum, run, project inputs, target and 15-minute expiry**.
Loading initializes the owned ledger under that approval, then atomically writes graph objects, ledger rows and
batch markers. Advisory transaction locks serialize this implementation's writers; ledger primary keys enforce
stable-ID uniqueness. Locks are graph-wide, independent of ledger schema; writer transactions use read-committed
snapshots after lock acquisition. An atomic `G2ROwner` metadata vertex binds the graph to one run/input/ledger,
including after all migrated data vertices are deleted. It is excluded from application query roots and accounted
for separately during validation. External operator writes to the dedicated graph are prohibited during migration.
Never write to AGE's internal graph tables.

Pause sends a scoped cancellation frame, with a five-second process-stop fallback. A confirmed batch is checkpointed;
an ambiguous COMMIT reconnects and checks the exact marker. Unresolved results do not advance a checkpoint.
If a checkpoint `.pending` file exists, preserve it and reconcile it against committed markers before manual recovery.
Do not delete graph/ledger/checkpoints to “fix” an uncertain result. Resume replays and verifies acknowledged markers.

Validation uses a repeatable-read snapshot to compare exact ledger records, native multiplicity, edge direction,
endpoints and source labels/counts. It is bounded by the operation deadline; partial validation is not a pass.
Load/extraction/validation are capped, not background benchmarks.

## Independent CLI

Build with the pinned SDK; package preparation uses locked restore and publishes a self-contained `win-x64` worker:

```powershell
dotnet build dotnet\AgeWorker\AgeWorker.csproj -c Release
dotnet dotnet\AgeWorker\bin\Release\net10.0\AgeWorker.dll smoke
dotnet dotnet\AgeWorker\bin\Release\net10.0\AgeWorker.dll translate "g.V().out('knows').count()"
```

For all project operations, artifacts are relative to the directory containing `project.json`:

```powershell
$worker = ".\resources\age-migration\win-x64\AgeWorker.exe"
& $worker fixture .\.cosmosdb-age-migration\project.json "" fixture.jsonl
& $worker authorize .\.cosmosdb-age-migration\project.json load fixture.jsonl
# Review the returned approval and add it to project.approvals; approval key stays outside the JSON.
& $worker load .\.cosmosdb-age-migration\project.json fixture.jsonl checkpoint.json
& $worker validate .\.cosmosdb-age-migration\project.json fixture.jsonl
```

The project is version 1, kind `cosmos-gremlin-to-age`; see bundled JSON schemas and TS/C# contracts.
Default batch limits: 50 records, 1 MiB; request timeout 30 seconds; operation deadline 300 seconds.
No runtime binary downloads occur. Windows x64 is the only packaged AGE extension-host RID.

## Source fidelity and inputs

The .NET source adapter pins **Gremlin.NET 3.4.13**, the line recommended by
[Cosmos support documentation](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/support).
The current [.NET quickstart](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/quickstart-dotnet)
does not override that compatibility table. Newtonsoft.Json is explicitly pinned to patched 13.0.4; the
old driver's vulnerable transitive version is not suppressed or shipped.

The source path submits only bounded read scripts, never bytecode or source mutations. Supply a **NDJSON**
`inventory.json` with one `{ "kind": "vertex" | "edge", "identity": ... }` per line, vertices before edges.
Identity contains `scope`, typed `partition`, typed `id`, and an owner vertex ID for edge reads:

```json
{"kind":"vertex","identity":{"scope":"cosmos:account.gremlin.cosmos.azure.com/db/graph","partition":{"type":"string","lexical":"tenant"},"id":{"type":"string","lexical":"a"},"owner":null}}
```

Automatic extraction currently requires string IDs/partitions and explicit observations for every property
occurrence ID/meta map and partition-qualified edge endpoint. Raw provider JSON numbers do not prove original
numeric types. Missing meta maps are not assumed empty; scalar edge properties and unknown endpoint partitions
block extraction. Many normal Cosmos responses may therefore require a separately reviewed canonical exporter.
**Do not fabricate evidence flags to bypass these blockers.**

The imported `source-compatibility.json` is reviewed endpoint/inventory-specific evidence, not an automatically
generated compatibility claim:

```json
{
  "schemaVersion": 1,
  "host": "account.gremlin.cosmos.azure.com",
  "database": "db",
  "container": "graph",
  "inventoryHash": "<SHA256 of the exact inventory bytes>",
  "consistency": "quiesced",
  "status": "verified",
  "propertyIds": true,
  "metaProperties": true,
  "typedValues": true,
  "partitionQualifiedEndpoints": true,
  "observedAt": "<actual UTC observation time, less than 24 hours old>"
}
```

Source extraction independently checks each returned record. A successful extraction produces a matching completion
manifest; partial source files cannot load without it. Inventory/source evidence is sensitive—keep it local.
Extraction is capped at 100,000 identities; canonical loading at one million unique records. No source inventory
enumeration or resumable extraction is claimed for this initial AGE adapter.

## Read translation and typed access

Supported literal syntax: `g.V()`/`g.E()`, `hasLabel('label')`, `out`/`in`/`outE`/`inE` with zero or one edge label,
`inV`/`outV`, `id`/`label`/`values('key')`, terminal `dedup`, `count`, or `limit(0..10000)`.
Roots with IDs use typed `GraphAccess.ReadAsync` instead; partition ambiguity is not guessed.
Dynamic input, bindings, lambdas, predicates, both-direction traversals, arbitrary mutation and unsupported step
orders are structured blockers.

Cypher read templates use AGE's documented prepared agtype parameter-map interface; mutation templates contain
only validated identifiers and bounded escaped literals, not arbitrary caller Cypher. See
[AGE prepared statements](https://age.apache.org/age-manual/master/advanced/prepared_statements.html).
Query/typed projection results are capped at 10,000 values, including count inputs; oversized counts fail rather
than returning truncated totals. Unordered subsets do not promise deterministic ordering.
The decoder handles scalar agtype plus JSON structures with outer numeric/vertex/edge annotations; nested annotated
paths are not supported and must fail, not be treated as ordinary JSON.

`GraphAccess` exposes typed read/query, property/provenance upsert (topology changes blocked) and exact-hash delete.
Each CRUD request needs its own signed canonical resource hash and commits a request marker atomically.
Replay checks current state; ambiguous CRUD commits reconcile the exact request marker.
Delete incident edges through separately authorized requests before deleting their vertex—no implicit cascade.
CLI authorization uses `authorize project.json crud-upsert record.json` or `crud-delete delete-request.json`;
a delete request contains `identity`, `kind`, and the exact current canonical `expectedHash`.

## C# application migration

Roslyn inventories literal `SubmitAsync` calls and produces a review plan. The narrow new-file rewrite accepts a
one-argument literal call on an explicitly declared `IGremlinClient` receiver, with `GraphAccess ageAccess` and
`CancellationToken cancellationToken` already declared. It rewrites only calls, not client lifecycle or result types.
This is syntax-aware, **not semantic symbol binding or full application equivalence**.
See `dotnet/AgeWorker/Fixtures/Repository.cs.txt`.

```powershell
& $worker rewrite-plan .\.cosmosdb-age-migration\project.json app.cs rewrite-plan.json
& $worker authorize-rewrite .\.cosmosdb-age-migration\project.json app.cs rewritten.cs
# Review/store the returned rewrite approval, then:
& $worker rewrite-apply .\.cosmosdb-age-migration\project.json app.cs rewritten.cs
```

Approval binds source content and exact output path; files are created exclusively, never overwritten.
Review compilation, typed results, behavior and rollback yourself before switching production.
Java/Jena application migration remains separate and unchanged.
