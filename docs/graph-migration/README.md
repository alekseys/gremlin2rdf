# Gremlin to RDF migration

This experimental fork implements a distinct graph migration kind, `cosmos-gremlin-to-jena`.
It does not relabel relational project fields or replace the existing migration workflow.

New source configurations default to the explicit [.NET Cosmos document-feed profile](document-source.md);
mode-less existing projects retain Gremlin until the operator selects a different mode. The configuration form now has
**Save settings and restart migration**, which preserves the old run and starts Step 1 with the displayed settings.
New document configurations detect supported JSON property layouts automatically; missing source details still require
an explicit stop-or-observed-values decision and separate approval. Version 0.39.18's private
installed-VSIX document and retained-Gremlin migrations passed through target validation and golden queries.
The [sanitized receipt](evidence/installed-vsix-0.39.18.json) separates observed document conversion from strict
equivalence and records the unchanged AccountOverview focus failure in the latest broad unit run.
The [0.39.19 receipt](evidence/installed-vsix-0.39.19.json) verifies the installed legacy-run restart, automatic JSON
layout detection and document migration without asking the user to test individual steps.
This is an experimental offline-verified preview, not live-account or production-release certification.

## Prerequisites and entry points

Use Java 21 and the pinned Maven Wrapper under `java/`. Build/package with `npm run graph:prepare`.
Machine-readable schemas are under `schemas/graph-migration/`; generate them with `npm run graph:schemas`.
Enable `cosmosDB.experimental.graphMigration.enabled` and run command `cosmosDB.graphMigration.open` in a trusted
workspace. The tab creates `.cosmosdb-graph-migration/project.json`. See [workbench behavior](workbench.md).

The standalone worker is `resources/graph-migration/worker.jar`; invoke it with `java -Xmx1g -jar <worker.jar> ...`.
No editor is required for the deterministic local generator, RDF export, translator, catalog runner or Java rewrite.
Prepare a strict project document before project-dependent commands; environment references must be `env:GRAPH_*`.

```
synthetic generate semantic 42 records.ndjson
mapping validate mapping.json
mapping export project.json records.ndjson new-rdf-directory
synthetic derive project.json records.ndjson new-catalog-directory urn:explicit:canonical-graph
translate urn:explicit:canonical-graph "g.V().count()"
rewrite plan workspace-root relative/Repository.java urn:explicit:canonical-graph
authorize project.json --operator-confirmed operation resources-json expiry env:GRAPH_APPROVAL_KEY
load project.json records.ndjson owned-state-directory
resume project.json records.ndjson owned-state-directory
validate project.json records.ndjson
test functional project.json records.ndjson
test fixtures project.json relative-catalog-directory
rewrite apply project.json workspace-root reviewed-plan.json new/Repository.java
report project.json owned-artifact-directory
```

These are argument arrays, not templates for shell-concatenated untrusted input. The approval resources, expiry and
operation must match the documented exact scopes. Existing outputs, unsupported semantics and incompatible state fail
closed; do not pass arbitrary application code as a source script. Read [source extraction](source-extraction.md) before
using live adapters or the separately approved native source fixture operations.

## Architecture and trust

`migration-model` shares strict typed records, projects, signed approvals and bounded file contracts.
`migration-engine` owns Jena mapping/SHACL, restricted parsing/translation, loading, reconciliation, Java rewrites and
typed application access. `cosmos-gremlin-source` runs the pinned 3.4.13 driver in a separate JVM, never on Jena's runtime
dependency line. `examples-before` and `examples-after` exercise supported application transformations.
`graph-testkit` creates only private loopback Fuseki/TDB2 processes for actual offline execution and recovery evidence.

New connection forms default to the read-only **Cosmos .NET SDK document feed**; see
[document-source profiles](document-source.md). Existing projects without an explicit source mode retain their legacy
Gremlin adapter and hashes. Selecting another mode archives the prior run, keeps its inventory and accounting, and
requires fresh mode-bound evidence and approvals. An SDK failure never invokes Gremlin automatically. Comparative live
benchmarks are not automatic or authorized by selecting a mode.

The editor exchanges bounded, versioned NDJSON frames with an owned worker. Large results stay in owned artifacts.
Checksummed concrete outputs and current input hashes are verified before evidence registration or later approval.
Secrets remain in SecretStorage or applicable approved environment references, never protocol/project/report values.
Source data, Java comments, query text and imported JSON are data, not agent instructions or executable hooks.

Canonical records retain typed source/partition/ID identities, occurrence IDs, equal-valued multiplicity, metadata and
endpoint scope. Canonical and derived graphs are separate. Derived relationships are supplementary; never use their
triple count as the authoritative count of parallel logical edges. See [mapping](mapping.md).

Extraction is read-only and manifest-qualified. Source population/cleanup is a different, explicitly approved
dedicated-fixture operation. Target batches pair data and marker in one server operation, not a pretend distributed
HTTP transaction. An approved anchored source snapshot prevents changed input from altering later writes.
Acknowledgement loss is reconciled before checkpoint advancement. See [recovery](recovery.md).

## Acceptance and operational boundaries

[Application migration](application-migration.md) documents the supported AST patterns, exact rewrite scopes and
behavioral fixtures. Arbitrary driver submissions, lambdas/Groovy, general loops/side effects and unrecognized traversal
semantics remain explicit blockers. Do not turn a blocked pattern into an approximate query or unchecked source rewrite.

[Performance](performance.md) distinguishes mapping, paired RDF loading, typed application operations, load/recovery,
mixed soak and unobserved live extraction. `milestones.json` and `evidence/` record executed gates and blockers.
Release SBOM/license/public-advisory reports are under `release/`; a generated audit with findings is **blocked**, not a
release approval. Historical package fingerprints are retained rather than falsely relabeled current.

Use [cutover guidance](cutover.md) only after current structural, query/CRUD, application, compatibility, performance and
dependency evidence passes. Missing live evidence or authorization cannot be supplied by a mock or an offline fixture.
