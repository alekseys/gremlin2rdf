# Applications, typed query evidence and fixtures

The Java parser inventories dependency/serializer configuration, submission sites, traversal construction and mutations
without evaluating application code or Groovy. Findings contain file/symbol/line ranges, fingerprints, confidence and
specific manual actions. Other languages receive discovery and assessment, not a claim of automated rewriting.

The supplied Java fixtures demonstrate exact terminal `g.V().count().next()` and `g.E().count().next()` rewrites with
declared `GraphTraversalSource` fields/parameters and constructor assignment. JavaParser preserves unrelated code/comments
and replaces the traversal-source type with `GraphAccess`. Initialization, dynamic assembly, asynchronous driver results,
shadowed types/names, escaping traversal references and unrewritten calls are blockers. This declared pattern set does not
promise general Java application rewriting. The isolated before/after Maven modules compile and execute the same vertex/edge
counts and retained unrelated method; the before oracle is not Cosmos compatibility evidence.

Review `rewrite plan <workspace-root> <relative-Java-source> <canonical-graph>`, authorize the exact source path/hash,
plan hash and separate output path, then use `rewrite apply <project> <workspace-root> <plan-file> <new-output-path>`.
No existing source/output is overwritten. Changed source or a blocked plan fails before writing. Build and run the application
after applying; rewrite success itself is not application-build, equivalence or cutover readiness.

`GraphAccess` provides exact typed SELECT/count decoding, identity-preserving lookup/create, property insertion/replacement/
removal, edge deletion and vertex deletion with explicit `reject` or `cascade-edges`. Caller-assigned property IDs make
repeated insertions idempotent without collapsing distinct equal-valued occurrences. Every actual update rechecks its scoped
application approval. Canonical property/meta-property resources and derived relationships change in one compound server
operation. Optimistic old-record guards detect conflicting replacement, and vertex eligibility is evaluated once inside the
atomic server operation, not once per property/meta-property join. Multiple preliminary lookup calls are not a transaction.
The private Fuseki rollback test establishes only its backend; deployed transaction behavior remains an acceptance gate.

Typed reads are limited to 100,000 rows and 16 MiB of decoded term data, with the configured record bound per term.
Use supported explicitly ordered pagination instead of silently truncating. The independent semantic oracle accepts at most
10,000 canonical records/16 MiB; large load profiles use separate bounded validation, not this in-memory oracle.
Equivalence reports preserve RDF datatypes, language tags, occurrence multiplicities and sequence positions. Only finite
float/double lexical variants normalize to the same IEEE value; signed zero and different datatypes remain distinct.
Reports include mismatch counts and at most 20 typed differing examples; sorting examples never changes sequence comparison.

## Canonical-derived catalogs

Generate first, without credentials or target writes:

```text
synthetic derive <project.json> <relative-canonical.ndjson> <new-relative-output-directory> <explicit-canonical-graph>
```

For a configured target, the explicit graph must match that project's canonical graph. Without a target it is an explicitly
selected offline fixture graph, not provisioned infrastructure. Each artifact is bounded to 16 MiB. `catalog.json` records
current input/source hashes and checksum-owned query, mutation and expected-record manifests. It is marked sensitive because
expected values and source identities are preserved. A malformed/changing source leaves partial owned artifacts, not a
success manifest.

The catalog derives all supported golden queries from canonical records and the independent reference interpreter.
Its 15-step mutation program exercises vertex/edge creation and lookup, duplicate equal-valued occurrence IDs/meta-properties,
idempotent replay, replacement/removal, incident-edge rejection, edge deletion and explicit edge-only cascade.
Mutation identities are deterministic and cannot collide with canonical fixture identities.

After loading the original canonical fixture into a private/explicitly approved target, run:

```text
test fixtures <project.json> <relative-catalog-directory>
```

This requires three current approval scopes: `fixture-test` for canonical graph, derived graph and exact `catalog.json`
SHA-256; `application-write` and `application-delete` for the two graphs. Use the independent CLI `authorize` operation only
after operator review. Never provide keys/secrets in arguments or chat; resolve them through approved secret references.

The runner verifies every checksum and regenerates the oracle/declared mutation policy from the current canonical source.
Even a reapproved checksum-valid false oracle is rejected. Existing fixture mutation identities are not adopted. Query
mismatches stop before mutations. Successful mutations verify typed postconditions, full original canonical/derived
equivalence and absence of unexpected resources at the end; a timeout or unknown write outcome is not acceptance.
Mutation timings cover the operation and postcondition calls, not individual HTTP latency. No automatic live cleanup,
publication, application restart or source write occurs.
