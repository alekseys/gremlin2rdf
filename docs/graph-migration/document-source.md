# Cosmos .NET document-feed source

The connection form defaults to `sourceMode: "cosmos-documents"` for new configurations. The source process uses
`Microsoft.Azure.Cosmos` 3.58.0, not Gremlin.NET, against the explicitly supplied HTTPS **.NET SDK account URI**,
database and graph container. It uses the same `authenticationRef` / SecretStorage source key as the legacy adapter.
Never put an account key in an endpoint, project, report or chat.

The connection form accepts `my-account.documents.azure.com` as well as a full account URI. On leaving the field
or saving, an unambiguous bare hostname becomes `https://my-account.documents.azure.com:443/`. An explicitly supplied
port is preserved. Full URLs still undergo the same HTTPS/no-credentials/no-query/no-fragment/no-resource-path checks;
normalization never changes a Gremlin host into a document endpoint or falls back to another source mode.
Each connection and preparation field shows accepted examples, formats or numeric ranges above its control.

Microsoft Learn documents SQL SDK/bulk access for graph accounts at
[Gremlin bulk executor](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/bulk-executor-dotnet), including
`https://<account-name>.documents.azure.com:443/`. The
[partitioning documentation](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/partitioning) identifies vertices
and edges as JSON documents and describes source-partition edge placement.
The [SDK stream iterator reference](https://learn.microsoft.com/en-us/dotnet/api/microsoft.azure.cosmos.container.getitemquerystreamiterator?view=azure-dotnet)
documents response streams and continuation tokens. These references do **not** make every historical raw graph-storage
layout a stable universal contract or establish an account's current read permissions.

## Configuration and automatic JSON layout detection

Set the following additional source fields through the connection form or strict project configuration:

```json
{
  "sourceMode": "cosmos-documents",
  "documentEndpoint": "https://my-account.documents.azure.com:443/",
  "documentProfile": "auto-v1"
}
```

`property-bags-v1` recognizes vertex properties represented by occurrence objects containing `id`, `_value`, and optional
`_meta`. `flat-v1` recognizes FlatSchema scalar vertex properties. Physical edges must identify their source vertex,
destination vertex, labels and destination partition; the edge partition is the source partition. Endpoints must resolve
against the observed vertex inventory. Unknown or ambiguous shapes, duplicate physical identities, missing endpoints and
coverage mismatches stop with diagnostics; they are not silently skipped.

New configurations default to `auto-v1`: every vertex's user properties are checked during the bounded approved read.
Arrays must pass the property-record decoder, while direct scalar fields pass the FlatSchema decoder. Mixing those
representations inside one vertex, malformed arrays, nulls, complex values and unknown storage fields stop conversion.
Vertices with no user properties do not need a layout choice; edges retain their existing required endpoint checks.
Successful source evidence lists `detectedProfiles`. Explicit profiles remain available as overrides and saved profiles
are never silently changed. This detects supported JSON structures, not account metadata or universal storage guarantees.
The raw document snapshot is retained with actual observed fields/types and JSON numeric lexicals. Absent occurrence
IDs or meta-property observations and missing system metadata stay unobservable. JSON numeric tokens alone cannot prove
Gremlin float32 versus double runtime provenance. The adapter does not invent IDs, metadata or runtime type evidence.

Document fidelity and Gremlin semantic compatibility are separate. Strict conversion stops when required fidelity is
unobservable. Reduced conversion requires both selecting reduced mapping fidelity and the existing scoped
`reduced-fidelity` approval; best-effort consistency independently requires its scoped approval. A document-fidelity
result never certifies Gremlin semantics, application equivalence, production readiness or a speed/cost improvement.

In the form, **When source details cannot be verified** is the fidelity policy: stop when required details are missing,
or migrate only observed values after a separate approval. A JSON number alone cannot reveal the original Gremlin
numeric type; a direct `name: "Alice"` field cannot reveal its property-occurrence ID. Automatic detection can identify
what is present, but cannot reconstruct missing information or choose the user's tolerance for that limitation.

`DOCUMENT_FIDELITY_UNOBSERVABLE` in Step 4 means strict policy blocked conversion, not that the account URI is invalid
or more RU is needed. The blocked evidence now lists the observed `unobservable` reason codes and supported layouts,
and reports reconciled counts only after all identities and endpoints match the inventory. It remains blocked and
does not create an approved canonical completion. This is separate from connectivity and enumeration success.
**Review source-detail policy** opens and focuses the setting without changing or approving it. To accept the
limitation, explicitly choose **Migrate observed values only (approval required)** and **Save connection settings**,
not restart. The existing inventory is preserved; changed evidence needs rechecking and the separate scoped approval.
Keeping strict mode continues to block when required details are unobservable. Neither option fabricates source data.

For explicitly approved document conversion, target validation checks the exact owned canonical bytes against RDF,
endpoint resolution, derived triples and SHACL. Its scope is `observed-document-canonical-only`, with
`strictInputFidelity: false`: success means the target matches the observed conversion, not that unobservable source
semantics were recovered. The validation command requires current document evidence, its canonical checksum and scoped
mode approvals before querying the target. Readiness still blocks Gremlin semantic compatibility and strict equivalence.
Legacy reduced-fidelity Gremlin validation retains its existing blocked strict-input behavior.

## Mode-bound operations and recovery

Use **Save settings and restart migration** to start again from the values currently shown in the form, even when the
old run is blocked or unfinished. It archives the previous project, creates a new run ID and empty inventory, clears
phase evidence and approvals for the new run, and starts read-only connection checks at Step 1. It does not delete old
inventories, journals, checkpoints or target graphs, nor resume any old continuation. New target writes still require
their own approvals. **Save connection settings** instead updates the current settings without automatically running
an operation. Unsaved edits disable continuation, and ordinary progress/reload events do not reset the form.
The current required step has a theme-aware colored background/border and an accessible current-step marker.
As it finishes, its section collapses and the next required step opens, receives focus and scrolls into view.
Completed sections can still be reopened to inspect evidence; ordinary updates do not repeatedly steal focus.

A restart is acknowledged next to its button with the new run ID and moves focus to Step 1. A rejected save instead
shows a focused inline diagnostic, retains the edited settings and leaves the previous run displayed; it does not claim
to have restarted. Existing best-effort consistency remains selected rather than becoming a blank required field, and
still requires its separate scoped approval before applicable extraction operations. An explicit fresh restart does not
need to open or hash the old identity inventory; a missing old inventory therefore does not prevent making a new one.
Existing `project.pending` or `project.lock` files still block replacement with a specific preservation/recovery message.
They are never deleted or adopted automatically. A saved new run whose connection check could not start is distinguished
from a restart that could not be saved.

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
