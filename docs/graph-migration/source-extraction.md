# Read-only source extraction and evidence

The isolated `cosmos-gremlin-source` JVM uses the pinned 3.4.13 driver and GraphSON V2.
It submits only fixed, parameter-bound count and projection scripts. Neither extraction
nor compatibility probing provisions a container, submits arbitrary code or mutates source records.
Credentials and optional CA file references are resolved from `env:GRAPH_*`; never put their values in project JSON.

## Typed enumeration

`schemas/graph-migration/source-identity.schema.json` defines the NDJSON identity manifest.
Each line ends with LF. Vertex entries require null `ownerId` and `edgeScope`; edges require
the typed outgoing owner ID and an explicitly observed `sourcePartition` or `sourceVertex` scope.
An example vertex entry is:

```json
{"schemaVersion":1,"kind":"vertex","partition":{"type":"string","lexical":"p"},"id":{"type":"string","lexical":"v"},"ownerId":null,"edgeScope":null}
```

Sort entries by the Java `IdentityEntry.orderingKey()`: canonical JSON of
`[kind == vertex ? "0" : "1", partition, id, edgeScope ?? "", edgeScope == sourceVertex ? ownerId : ""]`.
This orders vertices before edges and includes owner identity only when the declared edge
scope requires it. A source-partition identity cannot be reused under a different owner. Duplicate or out-of-order
identities, missing scopes, noncanonical typed lexicals and truncated frames fail closed.
Typed numeric partitions are bound without string coercion. Element/owner IDs remain restricted
to the declared supported identity types; population is more conservative and requires source string IDs.
The codec preserves scalar top-level metadata. The live adapter uses the documented
`ProjectionStrategy.IncludeSystemProperties` to read `_ts`, `_self`, `_attachments`, `_rid`, `_etag`
and edge partition metadata. The ETag is read before/after the full snapshot; changing versions
or missing/incorrect metadata block rather than fabricate a complete record. These provider
queries remain unverified without a live endpoint. Provenance shard tokens identify
logical manifest partitions, not invented physical service shards.
Each identity frame is at most 65,536 bytes. The project must contain the exact manifest SHA-256.
Nested partition-key paths are unsupported rather than silently treated as flat property names.

## Compatibility is an observation, not a filename

For an authorized bounded semantic corpus with at most 2,000 records:

```powershell
java -Xmx256m -jar java\cosmos-gremlin-source\target\cosmos-gremlin-source-0.1.0.jar probe <project.json> <identities.ndjson> <new-observation.json>
```

The probe observes repeated reads, typed values, repeated partition-qualified IDs,
property occurrences/meta-properties, and directed parallel/self/cross-partition edges.
Missing cases yield a blocked report. It does not establish compatibility for an untested
larger corpus, different endpoint or different source configuration. `float32Observed` is
separate from the native types actually observed; it must not be inferred from a double.

Register the observation path in `source.compatibilityEvidence`, its actual SHA-256/owner
in `artifacts`, and a current passing `source-compatibility` phase referencing it.
The evidence envelope must match the current run/input hash and identity manifest.
Path presence alone, offline test flags, stale hashes and tampered evidence cannot pass.
Live source compatibility remains **blocked** for this repository: no live endpoint was supplied.
Passing evidence also requires actual system metadata fidelity, not merely user-property fidelity.

The source TLS engine uses platform trust (or the explicitly referenced CA), TLS 1.2/1.3 and
HTTPS endpoint identification against the configured hostname. Offline tests perform actual
private TLS handshakes, including wrong-hostname and untrusted-CA rejection.

## Extraction and resume

```powershell
java -Xmx256m -jar java\cosmos-gremlin-source\target\cosmos-gremlin-source-0.1.0.jar extract <project.json> <identities.ndjson> <records.ndjson>
```

Invoke the same command with the same owned output to resume. Each confirmed record is fsynced
before its atomic checkpoint. Resume verifies the input hash, enumeration checksum and output
prefix, reconciles valid pending checkpoints, and truncates only the unconfirmed tail of that
owned output. Unowned outputs, corrupt pending state and changed mappings are not adopted.
Global, partition and partition/label counts reconcile before the completion manifest is published.
Inventories have both group-count and byte bounds; disk capacity is checked before each record.
Incomplete outputs have no passing manifest and are not loadable.

The run-scoped request journal also survives restarts. Every returned charge, including failed
attempts, contributes to the configured RU budget. A persisted in-flight request whose cost is
unknown invalidates confident budget enforcement; the worker blocks instead of resetting spent RU.
Budget enforcement cannot predict an unknown server charge: it blocks further requests after
the returned charge exceeds the limit. Provider retry-after is honored within the approved
deadline; oversized delays, missing budget diagnostics and authorization denial do not retry.

## Explicitly non-strict modes

Reduced mapping requires a valid `reduced-fidelity` approval scoped to the mapping hash.
Reduced source projection intentionally omits occurrence IDs/meta-properties, and configured
property exclusions are explicit; unsupported remaining values still fail rather than stringify.
Affected records carry reduced provenance. Best-effort extraction additionally requires
`best-effort-source` approval scoped to `[source.scope, source.identityManifestHash]`.
Approvals are rechecked during execution. Count drift is reported, not silently normalized.
Neither mode can produce strict cutover equivalence.

The editor dispatches the read-only compatibility probe and offers separate reduced/best-effort
approval controls. Packaged source-process and complete editor-restart acceptance still need execution.

## Separately authorized source fixtures

Generation is local and never connects to Azure:

```powershell
java -jar resources\graph-migration\worker.jar synthetic source-generate semantic 42 <project.json> <source-fixture.ndjson>
```

This bounded native-int64 profile generates 200 vertices/800 edges with run-prefixed source IDs,
explicit vertex-property IDs, the configured partition property and recorded owner markers.
It produces sorted identity entries and checksums. Unsupported arbitrary-precision source integers
are recorded in a separate negative fixture; the declared native-int64 variant is not a lossy
conversion of extracted source data. The original offline canonical profile remains unchanged.
Set the generated identity path/checksum in the project before source extraction/probing.

Derive reviewable population recipes and actual deterministic RDF from the canonical fixture:

```powershell
java -jar resources\graph-migration\cosmos-gremlin-source-0.1.0.jar recipes <project.json> population-plan.json source-recipes.ndjson
java -jar resources\graph-migration\worker.jar mapping export <project.json> <source-fixture.ndjson> <new-rdf-output-directory>
```

Recipe frames contain the full typed identity, canonical-record checksum, fixed script and data
bindings; they do not execute against Azure. RDF export emits separate canonical/derived N-Triples,
sorted within each bounded record, plus ownership/checksum manifests. Triple-frame counts are not
claimed as globally unique counts. Exports remain local input conversion, not live fidelity evidence:
full structural/SHACL validation needs complete endpoint context and is a separate gate.
Service-allocated metadata becomes part of actual extracted canonical data, not an invented fixture value.

Review a plan without executing it:

```powershell
java -jar resources\graph-migration\worker.jar synthetic plan <project.json> source-fixture.ndjson population-plan.json 100000
```

The plan records endpoint/database/container identity, run prefix, complete canonical checksum,
estimated writes, explicit write ceiling and RU budget. It rejects duplicate/out-of-order identities,
foreign endpoints, missing owner/partition fields, unsupported native values and system metadata writes.
Population requires a **pre-existing empty dedicated test container**, `syntheticTestContainer: true`,
`consistency: quiesced`, strict fixture fidelity, an RU budget and explicit authorization of the exact plan resources.
The operator must maintain exclusive access: Cosmos traversal predicates are **not** an atomic
cross-client fence or a transaction guarantee.

Sign `source-populate` and `source-cleanup` separately using the normal `authorize` command and the
plan's exact resources. Store only the resulting scoped approvals, never secret values, in the project.
The editor provides separate generate/plan/approve/run controls; its reviewed plan ceiling is 100,000
estimated writes. Larger local canonical profiles do not imply authorization to populate Azure.

```powershell
java -jar resources\graph-migration\worker.jar source-populate <project.json> resources\graph-migration\cosmos-gremlin-source-0.1.0.jar population-plan.json source-ownership.json
java -jar resources\graph-migration\worker.jar source-cleanup <project.json> resources\graph-migration\cosmos-gremlin-source-0.1.0.jar population-plan.json source-ownership.json
```

The source jar independently supports `plan <project> <relative-records> <relative-plan> <maximum-writes>`,
`populate <project> <relative-plan> <relative-journal>` and
`source-cleanup <project> <relative-plan> <relative-journal>`.
Mutations use generated fixed step shapes and parameter bindings, including user-supplied property IDs.
They are **never automatically retried**. Each creation/deletion intent is persisted before submission.
Service-allocated system metadata is not part of the writable fixture expectation; subsequent extraction
captures it into the actual source canonical records.
Resume confirms exact owned contents before accepting an uncertain creation. An uncertain deletion
may confirm absence, but an identity still present or recreated blocks rather than blindly deleting again.
Partial fidelity failures retain the journal and require explicit reconciliation.
Cleanup visits recorded attempted edges first, then recorded attempted vertices; any remaining incident
edge blocks vertex removal. The remote delete traversal also requires the owner marker, the observed
ETag and no incident edges. This uses the documented optimistic-concurrency pattern, not a claim
of cross-client atomic fencing or transaction support.
No unrestricted `g.V().drop()` or resource provisioning command exists.

Offline journal/script tests and native fixture determinism are exercised locally. **Actual Cosmos
population, property-ID/meta-property writes, quiescence, RU behavior and cleanup remain blocked/unverified**
without a live authorized dedicated container. Generation, mocks, or passing local tests do not satisfy those gates.
