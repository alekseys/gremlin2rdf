# Experimental graph workbench

Extraction requires current, owned succeeded source compatibility evidence and a verified selected inventory.
A blocked enumeration cannot be adopted: an empty bootstrap file leaves fidelity disabled, and mapping/connection
success does not permit extraction. Existing evidence and acknowledged checkpoints are preserved. Inspect the
current enumeration evidence first; collapsed stale reports are prior observations only. `SOURCE_SCRIPT_REJECTED`
with Gremlin 597/source 400 identifies an observed page-script rejection, not its exact unsupported token.
The fixed `source-keyset-page-v1` template identifier and sanitized numeric statuses survive into saved reports/UI.
Offline pinned Groovy syntax parsing does not establish Cosmos dialect or ordered paging acceptance; there is no
unordered-paging fallback. Do not share full project configuration, queries, bindings or secrets.

Version 0.39.7 routes every webview enumeration action, including **All phases and tools**, to guided Prepare instead of submitting the advanced default
`enumeration-plan.json` (which guided Prepare does not create). Select **Find partition candidates**, then
**Resume preserved read-only preparation** if offered. Otherwise explicitly review the listed ceilings and
stable-source assertion before **Start bounded read-only preparation**. The independent CLI still accepts an
existing explicit plan; missing/invalid plans block at `source-enumeration-plan` before source construction.
The webview no longer offers a duplicate raw enumeration Run or unused default plan/output fields.

## Simplified connection setup

The project page uses source/target connection cards, a numbered sequence overview and status badges with icons.
Required inputs and core migration steps are highlighted; application adaptation, reviewed-report import and
advanced tools are collapsed by default. The sequence overview is not a progress claim: phase badges reflect
persisted project state. Open **All phases and tools** to run a different phase, review its approval or open
its evidence. Identity enumeration is a discovery option when you need an inventory; an already reviewed
inventory does not require enumeration again. Local fixtures and benchmarks remain explicit manual actions.

New projects show a connection form for Gremlin host, database, graph, partition key and Fuseki dataset URL.
Source consistency is a separate explicit choice: pausing writes and immutable snapshots are operator assertions,
not environment-derived guarantees. Full project JSON, custom mapping and lower-level controls remain under the
collapsed **Advanced configuration and mapping** section.

Recognized environment keys include `GREMLIN_HOST`, `GREMLIN_ENDPOINT`, `GREMLIN_DATABASE`, `GREMLIN_CONTAINER`,
`GREMLIN_PARTITION_KEY_PATH`, `FUSEKI_DATASET_URL`, `FUSEKI_ENDPOINT`, `GRAPH_SOURCE_HOST` and
`GRAPH_TARGET_DATASET_URL`, plus supported Cosmos Gremlin/Jena aliases. Only non-secret connection keys are used.
The extension reads at most 256 KiB per known configuration file, refuses symlink paths, and never evaluates code:
`.env`, `.env.local`, `application.properties`, `src/main/resources/application.properties`,
`appsettings.json`, `appsettings.Development.json`, `local.settings.json`, `config.json`, `config/gremlin.json`,
`gremlin.json`, `.vscode/settings.json`, `gremlin.yaml`, `gremlin.yml`, `conf/remote.yaml`,
`src/main/resources/gremlin.yaml`, `application.yml`, `application.yaml`, and
`src/main/resources/application.yml`.

JSON permits comments/trailing commas but rejects duplicate keys. YAML discovery handles simple scalar settings
and a single inline driver `hosts` entry; complex collections, tags, interpolation and evaluated configuration
remain manual. Conventional Cosmos driver `/dbs/<database>/colls/<graph>` usernames supply routing names only;
password values are never surfaced. Unsupported/malformed files and conflicting recognized values produce notices,
not a guessed endpoint. Suggestions are collected in the trusted workspace and identify their origin; existing
saved connection values take precedence. Discovery makes no network request and does not establish compatibility.

Saving the form derives standard `/query`, `/update` and `/data` endpoints for the supplied Fuseki dataset URL.
Use the actual existing dataset path, for example `http://localhost:3030/ekg`; the server root alone does not
establish a dataset. If the intended dataset is absent, an operator must create or mount it separately. Custom
query/update/graph-store paths can be configured in advanced settings. Read-only checks do not use or require the
Fuseki administration API and never create datasets. A safe HTTP 404 means the requested query path was not found,
not universal proof that no datasets exist; HTTP 401/403 retain authentication/permission diagnostics.
Existing custom endpoints remain unchanged when the dataset URL is unchanged. Mapping, run graphs, protocol
versions, batching and request limits retain defaults; the active built-in Git API supplies the workspace baseline
commit when available, otherwise the legacy baseline stays available for advanced review.

New source setup creates an empty `identities.ndjson` only if no inventory exists; its SHA-256 is the actual file
checksum, not fabricated evidence. Empty bootstrap inventories allow discovery but cannot establish source fidelity.
Use **Prepare your source** to check read-only source and target connectivity independently. Missing extraction
inventory/fidelity evidence appears as a separate prerequisite rather than masking endpoint diagnostics.
**Find partition candidates** reads at most 16 MiB from each of `gremlin-partitions.json` and
`data/gremlin/gremlin_load_file.txt`, refuses symlinks and supports at most 1000 string partitions.
The JSON shape is `{"schemaVersion":1,"partitions":["tenant-a","tenant-b"]}`. Seed discovery lexes literal
`g.addV(...).property('pk','value')` chains for the configured flat partition key; it never evaluates Groovy.
Candidates remain unverified until global/per-partition live counts reconcile.

Review candidate values, request/duration/RU ceilings and the explicit stable-source declaration, then start
bounded read-only preparation. Provider charge observations must enforce the RU ceiling. Coverage/count changes
and exhausted budgets are distinct blockers, never successful partial inventories.
Successful enumeration verifies producer/run/input ownership and plan/inventory checksums, automatically selects
the inventory, creates a fresh extraction run, resets compatibility evidence and clears old approvals. Prior
project snapshots, budgets, checkpoints and artifacts remain preserved. No advanced JSON/path/hash edits are
needed for this normal path. Then run **Check read-only source fidelity**; enumeration does not establish it.
Do not run fidelity after failed enumeration: the unchanged empty bootstrap inventory has no observations.
Version 0.39.4 disables that follow-on action and reports `SOURCE_INVENTORY_REQUIRED` instead of false capability
findings. Valid supplied nonempty inventories remain usable. An empty inventory is accepted only with an owned
successful enumeration receipt proving reconciled global and partition counts; repeated live zero counts then
report `SOURCE_EMPTY_VERIFIED`, with fidelity **not established**, rather than a migration/capability pass.
This does not authorize extraction or target writes.

Enumeration failures retain fixed substeps such as `source-enumeration-global-vertex-count`,
`source-enumeration-vertex-page` and `source-enumeration-edge-page`. Evidence and UI show numeric
`gremlinStatus`, `sourceStatus` and `sourceSubstatus` only when returned and validated. Provider messages,
queries, bindings, partition/property values and remote stacks remain redacted. Status-based authentication,
throttling, serialization, argument and script rejection are distinguished; an unclassified provider status
reports `SOURCE_PROVIDER_CAUSE_UNKNOWN`, not a guessed schema/permission or paging defect. Preserve the
checkpoint and share only the safe diagnostic/substep/status evidence for investigation. No unordered
paging fallback or coverage approximation is performed.
Existing inventories are never overwritten. Input changes invalidate dependent evidence.
Stale phase diagnostics are collapsed under **Previous result — not a current connection check**. They are
historical input-bound evidence; changing the RU budget, inventory or run really does invalidate the old result.
Re-saving unchanged normalized settings or updating phase/artifact metadata does not change the input hash.
Save settings and rerun **Check read-only connections** to obtain current source/target results.
This action does not populate source data, authorize target writes, or remove existing source/target configuration.

Version 0.39.3 corrects the pinned Gremlin 3.4.13 adapter's internal single-connection pool invariants:
minimum and maximum simultaneous usage (and in-process requests) are both explicitly one.
The former constructor failure occurred before submitting source requests and is not invalid user JSON,
an inventory problem or a credential failure. Future classified constructor defects report
`INTERNAL_ADAPTER_CONFIGURATION` at `source-cluster-construction` with a complete-VSIX reinstall remedy.

## Installing or replacing the VSIX

In VS Code, select **Extensions → … → Install from VSIX**, choose the new package and reload the window.
Stop any active migration before replacing the extension. Settings and workspace migration files are retained.
For a package with the same version as your installed preview, you can force replacement from PowerShell:

```powershell
code --install-extension ".\vscode-cosmosdb-0.39.4.vsix" --force
```

If the CLI prints `DEP0169` after reporting that the extension was successfully installed, installation succeeded.
The warning was reproduced with VS Code 1.144.0 in isolated extensions/user-data directories. Its stack points to
VS Code's own gallery-request proxy helper (`getProxyAgent` in `src/vs/platform/request/node/proxy.ts`), not this
extension. That helper uses deprecated `url.parse()` even when no proxy is configured. Rebuilding this VSIX or
changing this repository's dependencies cannot repair the CLI. Do not disable warnings, TLS checks or proxy security.

The corrective fix belongs in VS Code: migrate its proxy/request URL parsing to the WHATWG `URL` API, preserving
proxy authentication and request behavior, then install a VS Code release containing that fix. No fixed release
was verified; the upstream `main` proxy helper still used `url.parse()` when checked. The successful installation
needs no rollback or reinstall solely because of this warning. Use **Help → Report Issue** in VS Code to report
the installer warning with a deprecation stack trace if it persists.

For diagnostics, set `NODE_OPTIONS=--trace-deprecation` only for the installation command and restore its previous
value afterward. This reports the actual caller instead of suppressing the warning.

## Execution budgets and meaningful progress

Set optional `limits.maximumOperationMillis` between 1,000 and 86,400,000 milliseconds to impose a per-operation
editor worker deadline. Old projects without this field retain their existing behavior. Changing the budget changes
the input hash, so review stale approvals/checkpoints before attempting resume; a deadline is not a migration pass.
The editor requests pause at the deadline, rejects subsequent completion as accepted evidence, and requests
termination after five seconds if the owned worker remains active. Actual closure, not a kill request, releases ownership.
This is extension worker control; it does not fix cancellation in the Copilot desktop app itself.

Headless load and main-CLI validation check the same deadline at record/batch boundaries. Source-child operations
check it while supervising the child; an in-flight target request still uses its configured request timeout.
Artifact hashing/preparation and commands without cooperative engine checks are not a hard headless-process deadline.
Use an external supervisor for a strict wall-clock process ceiling. Do not mistake request deadlines for an overall limit.

Checkpoint-confirmed load batches emit cumulative record counts, request-body bytes, retry/queue observations and
elapsed milliseconds over bounded `progress` protocol frames. The editor displays counts/bytes/time in its live status.
Request-body bytes exclude transport overhead; these fields do not claim target CPU/RSS or per-HTTP latency.

## Read-only discovery and operational preflight

Use **Enumerate read-only source identities** with the dedicated run-relative plan/output fields.
The bounded plan lists known typed partitions and explicit request/duration limits; see
[source enumeration](source-extraction.md). Review the generated manifest and configure a new extraction run,
then collect source fidelity evidence. Discovery does not silently update configuration or authorize any mutation.

**Check prerequisites** also reports the worker filesystem's available/required disk bytes and read visibility
of each configured run graph. An empty graph result cannot prove write authorization or distinguish an inaccessible
graph from an empty one. The reachability observation belongs to the current worker execution location.

For a separately authorized capability probe, set a dedicated graph IRI with the form
`<graphNamespace><runId>:probe:<UUID>`, then select **Authorize** and **Run** on
**Probe approved disposable target graph**. It inserts two ownership/input marker statements, verifies their
exact content, deletes only those exact statements and verifies the graph is empty. Foreign content blocks the
operation. Ambiguous outcomes preserve the graph for reconciliation; repeating the same approved probe can reconcile
its exact marker. It never drops an unreviewed graph or proves remote compound-transaction rollback semantics.

Headless equivalent: `preflight probe <project.json> <dedicated-probe-graph>`, with a current `target-probe` approval
whose sole resource is that exact graph IRI. Live execution still needs separately supplied endpoint credentials.

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
