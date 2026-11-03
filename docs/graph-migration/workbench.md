# Experimental graph workbench

Extraction requires current, owned succeeded source compatibility evidence and a verified selected inventory.
A blocked enumeration cannot be adopted: an empty bootstrap file leaves fidelity disabled, and mapping/connection
success does not permit extraction. Existing evidence and acknowledged checkpoints are preserved. Inspect the
current enumeration evidence first; collapsed stale reports are prior observations only. `SOURCE_SCRIPT_REJECTED`
with Gremlin 597/source 400 identifies an observed page-script rejection, not its exact unsupported token.
Fixed template identifiers and sanitized numeric statuses survive into saved reports/UI.
Offline pinned Groovy syntax parsing does not establish Cosmos dialect or ordered paging acceptance; there is no
unordered-paging fallback. Do not share full project configuration, queries, bindings or secrets.

Version 0.39.8 uses `source-keyset-page-v2`: project string IDs first, then natural ascending scalar
`.id().order().limit(g2rPageSize).fold()`. Partition/owner selection, exclusive ID cursor, page ceiling,
strict ordering validation and count reconciliation remain unchanged. This avoids the prior
`.order().by(id(),asc)` comparator/modulator combination: the
[Cosmos support reference](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/support) links TinkerPop
3.3.2 semantics, whose [Order enum](https://github.com/apache/tinkerpop/blob/3.3.2/gremlin-core/src/main/java/org/apache/tinkerpop/gremlin/process/traversal/Order.java)
has `incr`/`decr`, not `asc`. Pinned-driver offline scalar-order/cursor tests preserve string-ID ordering;
they do not prove the exact live rejection token or successful Cosmos acceptance. Prior v1 reports still render.

Only the next unfinished required step is expanded with execution enabled. Completed steps and their evidence
stay expanded, with their Run/authorization controls disabled; future steps start collapsed and disabled.
Current failed steps remain retryable. Hash changes return to the earliest invalidated step; old evidence
stays available as historical, never forcibly current. Configuration remains editable under its collapsed
section; saving changed inputs still invalidates dependent evidence and approvals.

The active enumeration **Run** opens inline guided Prepare and discovers local candidates, instead of submitting
the advanced default `enumeration-plan.json` (which guided Prepare does not create). Review candidates, then
**Resume preserved read-only preparation** if offered. Otherwise explicitly review the listed ceilings and
stable-source assertion before **Start bounded read-only preparation**. The independent CLI still accepts an
existing explicit plan; missing/invalid plans block at `source-enumeration-plan` before source construction.
There is no duplicate required-step Run in **All phases and tools**; that collapsed section retains only
explicit optional tools. Local discovery alone never starts a worker, renews budgets or authorizes writes.

## Local artifact access failures

Version 0.39.14 distinguishes checkpoint, segment, manifest and request-journal storage failures from source
query failures. Safe diagnostics carry a fixed `artifactAction` and bounded `artifactAttempts` when available;
no local paths, file contents or provider messages are exposed. A generic earlier `ARTIFACT_ACCESS` report
does not identify the failed artifact or prove incorrect permissions.

Actual Windows offline tests reproduce `AccessDeniedException` when a handle denies delete-sharing:
read/write access can still work while atomic replacement is denied. The worker retries only the rename
of an already closed, fsynced pending file, at most five attempts with 25/50/100/200 ms waits. It does not
replay source requests, rewrite pending bytes, delete destinations or fall back to non-atomic writes.
Other errors and non-Windows access denials are not retried. This reproduces a possible mechanism, not the
confirmed cause of a particular live failure.

Persistent failure retains the old and pending files. Request-journal recovery still requires its existing
ownership, checksum and monotonic-accounting checks; it never replays the charged request. An unacknowledged
enumeration checkpoint instead reports `SOURCE_ENUMERATION_PENDING_CHECKPOINT` and guided Prepare does not
offer Resume. Do not delete, rename or blindly adopt either file. Preserve the old run; explicitly review a
new bounded preparation if verified recovery is unavailable. Installing an update does not clear pending
state, reset journals, raise budgets or certify any inventory. Do not disable security software.

## Review settings after a stopped enumeration

Version 0.39.15 keeps stopped Step 2 expanded with an explicit **Review run settings** button. Reviewing
request, duration and RU limits does not require its Run button to be enabled or local partition discovery to
succeed. Active operations and untrusted workspaces still disable changes. If an earlier required step must
be rerun, settings can be reviewed but Start/Resume stay disabled until the sequential prerequisites pass.

A current `SOURCE_ENUMERATION_REQUEST_LIMIT` automatically reveals the review form and clears the request
ceiling entry and stable-source confirmation, requiring deliberate review rather than silently reusing the
exhausted allowance. The preserved duration and RU settings remain visible. Maximum reserved attempts count
retry capacity as well as identity reads and all global/partition count reconciliation; observed identity
counts alone do not certify coverage.

Enter the intended request ceiling, review the other limits and confirm the stable source before choosing
**Start bounded read-only preparation**. This creates a separately reviewed run, preserves the old project,
checkpoint, evidence and request journal, and invalidates prior approvals. Editing fields alone never changes
the old run or submits source requests. Resume cannot renew an exhausted allowance. The reviewed limits remain
bounded; no completion duration or request count is guaranteed.

Version 0.39.16 makes repeated **Review run settings** focus the request ceiling without resetting edited
fields. If a newly created run invalidated connection evidence, the review form explains the prerequisite and
offers **Check read-only connections** in place; Start/Resume cannot bypass the check. While the host is asking
for approval, the panel explicitly identifies the pending VS Code confirmation dialog outside the webview.
Dismissal returns the controls without creating a run. The extension does not change the user's dialog style.

Preserved phase evidence from an earlier run can be opened read-only after inventory adoption only when the
phase still references its recorded artifact and the confined non-symlink file matches its SHA-256. This does
not make old evidence current or grant write permission. Readiness verifies the current succeeded extract
phase's owned canonical file, including `records-{runId}.ndjson`; legacy CLI runs without an extract phase
retain `records.ndjson` support. Changed, missing, unowned or stale extraction inputs cannot establish a pass.
Named safe readiness diagnostics identify missing/stale gates instead of a generic warning.

## Installed-VSIX end-to-end test

Version 0.39.17 records the **current inventory adoption receipt** as Step 2 success, without changing the
original enumeration report's run or input hash. Inventory adoption intentionally creates a new run, so
prior connection checks are still stale: the next action is **Check read-only connections**, not another
enumeration. The top-level continuation panel names that step and **Go to next unfinished step** scrolls
and focuses its enabled Run control. Configured connection settings no longer automatically expand over
the workflow merely because a connection result became stale.

On opening an earlier saved adopted project, the host revalidates the current owned receipt's SHA-256,
run/input binding, selected identity bytes and reconciled producer manifest before restoring the adoption
phase. This repair submits no source requests, creates no new run, resets no budget and grants no approval.
Changed inputs cannot reuse an old receipt; corrupted/missing artifacts explicitly block continuation.
Old enumeration evidence remains available with its original hash, after the current adoption receipt.
Do not delete the inventory, reset the project or repeat enumeration solely because the original report
belonged to the preparation run. The installed-VSIX test also reproduces the older persisted stale state,
reloads it, asserts zero extra source requests, and verifies that the connection Run is enabled/focused
before completing the remaining private migration.

Terminal worker messages no longer publish completed controls before the owned process has exited.
This prevents the next enabled Run from racing a still-owned Java worker. A subsequent shutdown failure
remains a failed/paused operation rather than certifying an earlier success message.

On Windows, `npm run graph:test:vsix` installs the current built VSIX into a fresh, isolated VS Code profile
and runs the actual workbench, tRPC host, pinned Java source worker and Jena sink. It requires the normal
repository Node/Playwright dependencies, Java 21+, installed VS Code at its default path, the installed Azure
Resource Groups extension and Python packages pinned in `test/graph-vsix/requirements.txt`. Set
`GRAPH_E2E_VSIX` to select another built package. This is an explicit desktop integration test, not a default
unit test or a connection to your saved workspace/profile/accounts.

The harness generates an explicitly trusted temporary workspace, a private verified localhost TLS
GraphSON fixture and a real transactional loopback Fuseki dataset. It copies extension code only, not user
accounts or settings. A custom dialog style in this isolated test profile makes the real scoped host
confirmations automatable; it does not disable trust, skip approvals or modify the user's profile.
It verifies worker JAR bytes against the installed package. The fixture rejects mutations and unknown
read templates; it is **not a Cosmos dialect/service simulator or evidence of Azure compatibility**.

The flow executes read-only preflight, a deliberately exhausted 16-attempt preparation, repeated review,
confirmation cancellation, a separately reviewed 200-attempt retry, retained evidence opening, reconciled
enumeration/adoption, assessment, strict fidelity, mapping, extraction, explicitly approved private load,
structural/SHACL validation, golden queries and readiness reporting. The three-vertex/three-edge source
contains a partition-qualified ID collision, typed/repeated property occurrences with metadata, parallel
edges, a self-loop and cross-partition endpoints. These fixture ceilings are not recommendations for live graphs.

The compressed WebSocket case exercises the pinned Netty 4.1.72.Final optional
[jzlib dependency](https://github.com/netty/netty/blob/netty-4.1.72.Final/codec/pom.xml), packaged at the
[upstream managed version 1.1.3](https://github.com/netty/netty/blob/netty-4.1.72.Final/pom.xml).
No Gremlin/Netty version override or TLS weakening is involved.

The harness requires passed core migration gates and **blocked** production readiness for unexecuted
application inventory/build, CRUD failure injection, large load, two-hour soak and dependency-audit proof.
It never manufactures those reports, authorizes publication or labels the local transfer production-ready.
Its temporary root contains `verification.json`, screenshots, persisted reports, journals and checkpoints;
fixture helpers and the isolated Code instance are stopped on exit. Test roots contain private fixture
certificate/key files; do not publish them as production evidence or credentials.

## Simplified connection setup

The project page uses source/target connection cards, a numbered sequence overview and status badges with icons.
Required inputs and core migration steps are highlighted; application adaptation, reviewed-report import and
advanced tools are collapsed by default. The sequence overview is not a progress claim: phase badges reflect
persisted project state and current input ownership. Open a required step to review its evidence, or
**All phases and tools** for optional operations. Identity enumeration is a discovery option when you need an inventory; an already reviewed
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

New forms select **Cosmos .NET SDK document feed (default)**. Supply the explicit account `.NET SDK URI`, not an inferred
conversion of the Gremlin endpoint, and choose the document storage profile. `GRAPH_DOCUMENT_ENDPOINT` and
`COSMOS_DOCUMENT_ENDPOINT` are recognized non-secret suggestions. Saved legacy projects remain Gremlin until explicitly
switched. A mode change archives the old project and preserves its complete identity inventory and journals; it creates
a new run without old approvals or compatibility. Document preparation scans the SDK feed without Gremlin queries or
partition candidates. Errors stop, never switch modes or launch a comparison benchmark.
See [document fidelity and mode-bound recovery](document-source.md).

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
Run **Check read-only connections** first to check source and target independently. Missing extraction
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
needed for this normal path. Follow the next enabled Run to **Probe read-only source compatibility**;
enumeration does not establish source fidelity.
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
code --install-extension ".\vscode-cosmosdb-0.39.17.vsix" --force
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

## Source RU rate and total-run allowance

Version 0.39.9 separates two different controls in the enumeration review:

| Control                               | Meaning                                                                                                 |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| **Migration source pacing (RU/s)**    | A migration-side average pacing rate, independent of provisioned database throughput.                   |
| **Total source RU allowance per run** | Cumulative observed spend for that run, retained across retries/restarts. Waiting never replenishes it. |

For example, a database provisioned at 5000 RU/s can serve a migration paced at 400 or 1000 RU/s.
Neither value determines the total allowance: 1000 RU/s over 120 seconds corresponds to 120000 RU
of rate-times-duration capacity, not a 1000-RU total. This arithmetic is planning guidance, not a
coverage estimate, promised completion time or automatic authorization for that spend.

Headless projects can set optional `source.ruPerSecond` (decimal string, 1–1000000000 RU/s).
`source.ruBudget` retains its existing **total-run RU** meaning; it is never reinterpreted as a rate.
The guided form requires review of both fields for new runs. Missing `ruPerSecond` keeps legacy pacing
behavior and does not change the project input hash. Re-saving unchanged connections preserves both settings.
An explicit rate change changes the input hash; it cannot renew or adopt an old journal under new inputs.

The source adapter is serial. Each successful or failed request's observed charge determines the minimum
delay before the next request, allowing already elapsed time and provider retry-after waits to satisfy that
delay. Charge is known only after a response: a single request can exceed one second of the selected rate,
and a total allowance can be crossed by that final observed charge. There is no claim of a hard instantaneous
server-side RU/s cap or pre-reservation of unknown request costs. Pacing never modifies source queries or
silently skips work. Missing/malformed charge observations block execution when either control is configured.
Counts, fidelity reads and extraction use the same controls, not just enumeration page reads.

Outstanding pacing debt and cumulative spend are saved with the owned request journal. Restart retains the
full last unconfirmed delay conservatively; it never grants fresh throughput credit or resets total spend.
Pacing/retry waits must fit the remaining operation/enumeration duration and are interruptible; otherwise
`SOURCE_RU_RATE_DEADLINE` blocks before another request. A resumed run keeps the originally reviewed
settings; editing the new-run fields does not alter Resume.

`SOURCE_RU_BUDGET_EXHAUSTED` means **total allowance exhausted**, not provider throughput exhausted.
New safe diagnostics show `ruBudget`, `ruConsumed`, `ruRemaining` and, when configured, `ruPerSecond`.
The code/stage/amounts survive source acknowledgement, persisted evidence and UI; no resource names or
request contents are included. Provider throttling remains a separate `ENDPOINT_THROTTLED` observation.

For an already exhausted run, preserve its journal and checkpoints. Install the new VSIX, reload and follow
the next unfinished Run into guided Prepare. Review a new **total allowance**, a separate **RU/s rate**,
request/duration bounds and stable-source assertion before **Start bounded read-only preparation**.
The old run is archived, not reset; approvals/evidence remain input-bound. Resume is not offered for a
current known exhausted/unobservable RU result and never grants more spend. No automatic budget increase,
database provisioning or live requests occur by installing or reviewing these controls.

### Complete request-charge accounting

Version 0.39.10 corrects two adapter defects independently of RU settings:

- Cosmos documents `x-ms-request-charge` as the **individual response chunk's** cost, and
  `x-ms-total-request-charge` as cumulative cost for the traversal. The pinned Java 3.4.13
  `ResultSet.statusAttributes()` exposes the **terminal response's** attributes, not all intermediate chunks.
  The worker now uses the terminal total exactly once, never a partial chunk as a complete cost.
- The previous decimal-only text check rejected finite numeric charges rendered in scientific notation.
  Bounded nonnegative finite numbers and numeric strings now parse as exact decimal amounts, including explicit
  zero; NaN, infinity, negatives, oversized values and malformed inputs remain rejected.

Offline fixtures exercise the actual pinned GraphSON serializer, response handler, queue and ResultSet with
partial frames followed by a terminal response, including folded empty edge pages and total-only metadata.
These reproduce adapter handling, not the exact metadata returned by a user's live endpoint.
See the [official Cosmos response headers](https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/headers)
and [pinned Java handler](https://github.com/apache/tinkerpop/blob/3.4.13/gremlin-driver/src/main/java/org/apache/tinkerpop/gremlin/driver/Handler.java).

If a complete charge is still unknown, evidence includes a fixed `chargeObservation`: `missing-total`,
`partial-charge-only`, `invalid-total`, `unconfirmed-in-flight`, or `previous-unobservable`.
Numeric provider statuses remain visible when actually observed. No arbitrary metadata keys/values, scripts,
partition values or provider messages are exposed. A zero charge is accepted only when explicitly returned
as a valid total; an empty page or absent header never implies zero. Increasing RU/s or total RU cannot
repair unknown accounting.

New request journals use accounting schema 2. Schema-1 journals from earlier builds may contain partial-only
totals and cannot safely be upgraded by relabeling or resuming; `SOURCE_RU_ACCOUNTING_REVIEW` blocks without
changing them. Preserve these files/checkpoints and review a new bounded preparation run. Current schema-2
journals retain restart/debt/integrity checks; an unknown/in-flight observation never becomes known merely
by restarting. No budget or authorization gate is bypassed.

## Execution budgets and meaningful progress

Version 0.39.11 displays activity **inside the step being executed**, including enumeration count/page
substeps, observed vertex/edge counts, reserved request attempts, saved local checkpoints, reconciliation,
merge, request/response activity, RU pacing and retry waits. Load retains target-confirmed batch counters.
Every worker operation emits a ten-second elapsed-time heartbeat; this proves worker-process activity,
not source response, target confirmation, percentage complete or ETA. A visible current line accompanies
a keyboard-focusable activity log. Version 0.39.12 shows the first update, at most one meaningful summary
every ten seconds and the final two actual observations when the worker stops. Ordinary request/response
chatter does not replace periodic count/stage summaries. History is bounded to 20 lines while retaining
the first update and recent samples/tail; terminal status and diagnostics are never delayed by sampling.
No raw stderr, queries, identities, partition
values, paths, provider messages or credentials are forwarded. Frequent counters are not live-announced;
phase/status changes remain accessible without moving focus.

The prior editor deadline handler marked the bridge failed immediately and saved only a bare `paused`
status. That discarded the worker's real acknowledgement/evidence during the stop grace period and
replaced the reason with a generic warning. The bridge now keeps accepting bounded, owned progress and
the stop report for five seconds. A late success is never accepted as success after a stop request.
If the worker cannot report before termination/disconnection, a separate checksummed **host stop report**
retains the observed deadline/termination reason and recent safe activity. It is not a worker completion
or verified checkpoint. Paused steps expose these reports through **Evidence** even after panel reload.

Deadline, operator Pause/Cancel and forced termination are distinct diagnostics. Existing paused phases
with no report receive a host recovery snapshot stating **the previous stop reason is unknown**, without
guessing a timeout or repairing/resetting user artifacts. Snapshot registration does not change inputs,
budgets, source inventory, approvals or checkpoint bytes. Request journals still fail closed on unknown
in-flight spend; a pause does not guarantee safe resume.

Install the current VSIX and reopen the saved project to inspect the existing paused state. The earlier
live pause cause cannot be reconstructed if it was never saved. For another attempt, use guided Prepare
and review its duration/request/total-RU/RU-per-second limits. Do not delete journals or increase budgets
automatically. If an actual overall deadline is observed, review that duration separately from RU controls.

An observed deadline is **not an operator Pause**. Version 0.39.12 carries the host's fixed deadline cause
into the worker, so a deadline-driven child stop produces `OPERATION_DEADLINE`, not `OPERATOR_PAUSED`.
The host acknowledgement shows one canonical `OPERATION_DEADLINE_PARTIAL_STATE_PRESERVED` diagnostic.
An earlier saved report containing both codes is retained unchanged; its explicit deadline observation
still identifies an expired time allowance, not an exhausted RU/s throughput limit.

Guided Prepare previously capped its duration field at 600 seconds. It now accepts an explicitly reviewed
**10-86400 seconds** for a **new bounded preparation**, consistent with the existing worker contract.
This covers identity reading **and global/partition count reconciliation**, not just reading the final edge.
The host clock begins at process launch; source elapsed time starts later, so a host deadline near a
597-second source observation is consistent with the former 600-second ceiling. Observed identities are
not certified complete until reconciliation finishes. Reserved attempts include retry reservations and
are not the actual request count.

After an observed deadline, reopen the next unfinished **Run**, preserve the old checkpoint/journal, and
explicitly review a longer duration (for example 1800 seconds), request ceiling, total RU allowance, pacing
and stable-source assertion before **Start bounded read-only preparation**. This creates a fresh owned run
and archives the previous project; it does not extend the expired run or guarantee completion within that
example duration. Known expired-duration results do not offer Resume even if the last checkpoint elapsed
time is slightly below the source plan ceiling. Other verified resumable runs remain eligible. No limits,
checkpoint bytes, journal accounting or approvals are automatically increased, reset or relabeled.

Version 0.39.13 corrects a remaining form-state defect: the review field previously initialized to **120
seconds on every remount**, even if a different overall limit was saved. Nonexpired preparations now show
the saved overall duration. For a current known deadline, the new-run duration is deliberately blank and
Start remains disabled until a duration is entered alongside the other required reviews. The previous
configured limit is shown; this also applies when the stop arrives while the form remains open. This does
not authorize a larger duration automatically or prevent an operator from explicitly reviewing a smaller
bounded run.

The host now captures `operationLimitMillis` and `elapsedMillis` when its actual deadline timer fires.
These bounded numeric observations appear in its diagnostic, persisted stop report and phase blocker, so
no one has to infer the effective limit from a sampled source log. Old worker-only reports lacking these
numbers remain unchanged; a last source line near 108 seconds alone does not prove a 120-second limit.
Worker evidence and extension-host stop evidence are separate checksummed artifacts. Evidence buttons
describe their producer and open their own selected path. Their content must not be assumed identical:
the host report also retains sampled activity and the effective host deadline when observed. Existing
reports are preserved without being rewritten or deduplicated.

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
