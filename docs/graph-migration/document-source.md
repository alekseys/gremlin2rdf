# Cosmos .NET document-feed source

The connection form defaults to `sourceMode: "cosmos-documents"` for new configurations. The source process uses
`Microsoft.Azure.Cosmos` 3.58.0, not Gremlin.NET, against the explicitly supplied HTTPS **.NET SDK account URI**,
database and graph container. It uses the same `authenticationRef` / SecretStorage source key as the legacy adapter.
Never put an account key in an endpoint, project, report or chat.

Microsoft Learn documents SQL SDK/bulk access for graph accounts at
[Gremlin bulk executor](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/bulk-executor-dotnet), including
`https://<account-name>.documents.azure.com:443/`. The
[partitioning documentation](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/partitioning) identifies vertices
and edges as JSON documents and describes source-partition edge placement.
The [SDK stream iterator reference](https://learn.microsoft.com/en-us/dotnet/api/microsoft.azure.cosmos.container.getitemquerystreamiterator?view=azure-dotnet)
documents response streams and continuation tokens. These references do **not** make every historical raw graph-storage
layout a stable universal contract or establish an account's current read permissions.

## Explicit configuration and profiles

Set the following additional source fields through the connection form or strict project configuration:

```json
{
  "sourceMode": "cosmos-documents",
  "documentEndpoint": "https://my-account.documents.azure.com:443/",
  "documentProfile": "property-bags-v1"
}
```

`property-bags-v1` recognizes vertex properties represented by occurrence objects containing `id`, `_value`, and optional
`_meta`. `flat-v1` recognizes FlatSchema scalar vertex properties. Physical edges must identify their source vertex,
destination vertex, labels and destination partition; the edge partition is the source partition. Endpoints must resolve
against the observed vertex inventory. Unknown or ambiguous shapes, duplicate physical identities, missing endpoints and
coverage mismatches stop with diagnostics; they are not silently skipped.

These are explicit decoder profiles, not account-layout autodetection or universal Microsoft storage guarantees.
The raw document snapshot is retained with actual observed fields/types and JSON numeric lexicals. Absent occurrence
IDs or meta-property observations and missing system metadata stay unobservable. JSON numeric tokens alone cannot prove
Gremlin float32 versus double runtime provenance. The adapter does not invent IDs, metadata or runtime type evidence.

Document fidelity and Gremlin semantic compatibility are separate. Strict conversion stops when required fidelity is
unobservable. Reduced conversion requires both selecting reduced mapping fidelity and the existing scoped
`reduced-fidelity` approval; best-effort consistency independently requires its scoped approval. A document-fidelity
result never certifies Gremlin semantics, application equivalence, production readiness or a speed/cost improvement.

For explicitly approved document conversion, target validation checks the exact owned canonical bytes against RDF,
endpoint resolution, derived triples and SHACL. Its scope is `observed-document-canonical-only`, with
`strictInputFidelity: false`: success means the target matches the observed conversion, not that unobservable source
semantics were recovered. The validation command requires current document evidence, its canonical checksum and scoped
mode approvals before querying the target. Readiness still blocks Gremlin semantic compatibility and strict equivalence.
Legacy reduced-fidelity Gremlin validation retains its existing blocked strict-input behavior.

## Mode-bound operations and recovery

Feed preparation does not require Gremlin queries, Gremlin keyset paging or partition candidate scripts. Review page,
request, duration, disk, total RU and RU/s limits before starting. Source operations are read-only: no resources,
documents or source indexes are created, updated or deleted. Target writes retain their existing separate approvals.

Raw pages, continuation/checkpoint state, request-charge accounting and canonical outputs are run/input-bound. Keep old
and pending artifacts after interruption. Unknown in-flight charges block blind replay; changing a pacing rate does not
replenish a total RU allowance. Completion requires reconciled feed coverage, not just receipt of a final page.

Existing projects with no `sourceMode` remain legacy Gremlin; absent fields are not injected into their hashes.
Selecting `.NET` explicitly creates a new run and archives the prior project, preserving the selected inventory
(including previously enumerated inventories), journals and continuations. The document source must verify that
inventory against its own observations. Neither adapter resumes the other's continuations.

To try Gremlin after an SDK error, select `Gremlin traversal (explicit secondary mode)` and save/review the new run.
There is **no automatic fallback** and no automatic source comparison. Any future live benchmark comparison must be
explicitly opted into, bounded and approved; selecting this mode is not authorization to run one.

Packaging the source requires the pinned .NET 10.0.403 SDK and `npm run document:prepare`; the verified runtime package
currently targets Windows x64, matching the independently packaged AGE worker. All published runtime files are
checksummed before source execution. Offline fixtures establish decoder/worker behavior only; real account read access,
the selected account storage profile and live service behavior remain unverified without separately authorized evidence.
