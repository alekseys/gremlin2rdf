# Experimental graph workbench

The graph tab owns `.cosmosdb-graph-migration/project.json`; relational migration state and fields are not reused.
Trust the workspace before executing anything. Credentials are stored in VS Code SecretStorage and only applicable
references are passed to each operation. Packaged JAR hashes are checked with streaming reads before execution.

Generate the canonical semantic fixture, derive its query/mutation catalog, and review the produced artifacts.
Catalog execution separately authorizes its exact checksum and application write/delete graph scopes.
The catalog runner executes translated queries and typed CRUD; it does not populate Cosmos DB.
Cancelled fixture runs preserve partial state and are not equivalence passes: reconcile their owned mutation identities
before another run. The application runner does not automatically delete partial fixtures.

For Java migration, select a workspace-relative source, run **Review Java rewrite plan**, inspect its evidence, and choose
a new `artifacts/rewritten/*.java` output. Authorize that exact source/plan hash and output before applying. The original
source is never overwritten. Unsupported AST patterns stay blocked; building a rewrite does not establish equivalence.

Private reference load and two-hour mixed soak actions require a separate local confirmation. They use the committed
reference configurations, a 1 GiB worker, and a separate 2 GiB loopback Fuseki process with newly created private TDB2
directories. They receive no live credentials. Load needs 50 GiB free disk. A resource or duration blocker is evidence,
not a passing gate. Existing/live TDB2 files are never opened. Parent loss or cancellation stops owned child processes;
partial benchmark directories are retained for inspection, not adopted as acceptance.

Pause/cancel also works during preparation and before the worker handshake. An unresponsive owned worker is requested
to terminate after five seconds; a termination request is not a verified successful exit. New work remains blocked while
that process is owned. On restart, running phases without an active owner become paused, not succeeded.
Completion verifies each actual produced artifact against its worker checksum; changed artifacts invalidate dependent
successful evidence. Invalid acknowledgements cannot advance the phase.

The reviewed-evidence import action accepts only a run-relative, bounded result envelope naming the current run, input
hash and selected application/CRUD/performance/dependency phase. It rejects credential fields, changed checksums and
stale inputs, and requires separate local operator review. Import records a producer report, not database authorization:
the readiness evaluator independently checks measurable thresholds and current linked artifacts. Reference benchmark
envelopes must match their concrete owned report checksum before load/soak gates can pass. A raw JSON result without
run/input ownership is not a passing gate.

Passing dependency evidence additionally links current owned `sbomPath`, `licenseReportPath`,
`javaVulnerabilityReportPath` and `extensionVulnerabilityReportPath` artifacts, with corresponding SHA-256 fields.
Import verifies each linked file before the shared review and checks its checksum again before registering ownership.
A summary without linked files never proves their execution. Readiness verifies CycloneDX/license content,
the Java scan's SBOM binding and empty findings, and the npm
scan's zero total before accepting the dependency gate. Any advisory, missing license, changed file or unexecuted scan
keeps release blocked. Native result status and envelope operation must also match the gate; numeric coercions and
unowned nested benchmark reports cannot turn incomplete evidence into passing acceptance.

Project save crash artifacts (`project.lock`, `project.pending`) are deliberately not automatically deleted or adopted.
If either remains, stop all editors/workers for this workspace, preserve both files and the current `project.json`, inspect
the pending document's run and inputs, then restore a reviewed project document before removing only the inspected
stale save lock/pending paths. Do not perform this repair while another editor can write. This manual state-file repair
is distinct from target checkpoint reconciliation and never authorizes a database mutation.

Version-pointer publication remains explicitly unsupported without atomicity/cache-coherence proof.
Manual cutover and cleanup continue to require current evidence and exact run graph authorization.
