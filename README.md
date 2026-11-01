# gremlin2rdf: Cosmos DB for Gremlin to Apache Jena / Fuseki

Move an existing **Azure Cosmos DB for Gremlin graph and application** toward an RDF database served by
**Apache Jena Fuseki**, using a VS Code migration workbench and an independently usable Java CLI.

This fork of [microsoft/vscode-cosmosdb](https://github.com/microsoft/vscode-cosmosdb) focuses on graph migration:
review your application, configure the source and target, map the graph to RDF, extract and load data, then compare
the results before changing your application's database connection. It is an **experimental fork**, not a
Microsoft Marketplace release or a one-click conversion of arbitrary Gremlin applications.

## What the migration preserves

The default mapping retains typed vertex and edge identities, partition-qualified IDs, edge direction, parallel
edges, self-loops, property occurrence IDs, repeated equal-valued properties, meta-properties and observed source
metadata. Unsupported or unobservable source semantics are reported rather than silently approximated.

The target keeps two complementary views:

| RDF view        | Purpose                                                                                                                                |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Canonical graph | Identity-preserving records, edges and property occurrences; the authoritative migration representation.                               |
| Derived graph   | Supplementary RDF relationships for convenient querying. A single relationship triple does not replace distinct parallel source edges. |

Optional domain mappings let you choose vocabulary IRIs, literal datatypes, resource-valued properties and
occurrence constraints. Generated SHACL shapes help check the resulting RDF.

## Before you begin

You need:

- **VS Code 1.125 or newer** and this fork's VSIX, not the standard Azure Cosmos DB Marketplace extension.
- **Java 21 or newer** available on the machine running the extension.
- Your Gremlin application's source folder, opened as a trusted VS Code workspace.
- An existing Cosmos DB for Gremlin database/graph and an existing Fuseki dataset with reachable endpoints.
- Approved credential access, and a plan to pause source writes or use an immutable source snapshot while extracting.

You can explore local fixtures and RDF conversion before supplying live database credentials. The tool does not
provision Cosmos DB resources or a Fuseki server, and it never opens a deployed server's live TDB2 files.

## Install and open the workbench

1. In VS Code, open **Extensions**, select **Install from VSIX** from the view's menu, and choose
   `vscode-cosmosdb-0.39.1.vsix` from your local build. Reload VS Code after installation.
2. Enable this setting:

   ```json
   {
     "cosmosDB.experimental.graphMigration.enabled": true
   }
   ```

3. Open your application's workspace, then run **Open Gremlin to RDF Migration (Experimental)** from the
   Command Palette.
4. Select **Create graph migration project**.

The workbench saves its project in `.cosmosdb-graph-migration/project.json`. Keep this folder with the workspace
to retain configuration, phase status, checkpoints and reviewable artifacts. It is separate from relational
migration state.

If an older build with the same version is already installed, stop active migrations and force replacement:

```powershell
code --install-extension ".\vscode-cosmosdb-0.39.1.vsix" --force
```

The VS Code CLI may print `DEP0169` from its own deprecated URL parser even when installation succeeds.
This is not an extension installation failure; see [installation guidance](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/workbench.md#installing-or-replacing-the-vsix).

## Configure your source and target

**Configuration and mapping** starts with a short connection form, not the full project JSON:

| Field                     | What to enter                                                                                                                                      |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Gremlin endpoint          | Your account's Gremlin hostname, such as `my-account.gremlin.cosmos.azure.com`. A credential-free HTTPS/WSS endpoint on port 443 is also accepted. |
| Cosmos DB database        | The existing database name.                                                                                                                        |
| Gremlin graph (container) | The existing graph/container name.                                                                                                                 |
| Partition key path        | Your graph's partition key, such as `/tenantId`. `/pk` is a suggested default, not a detected property of your database.                           |
| Fuseki dataset URL        | The dataset URL, such as `http://localhost:3030/my-dataset`, not just the server root. Use HTTPS for non-loopback endpoints.                       |
| Source consistency        | Confirm that you will pause source writes during extraction, or that the source is an immutable snapshot. This is never inferred automatically.    |

Recognized, unambiguous connection values are suggested from the extension host's environment and supported
workspace configuration files. Suggestions identify their source; conflicting or unsafe values require manual
review. No database connection is made during discovery.

For example, these **non-secret** entries in a workspace `.env` can prefill the form:

```dotenv
GREMLIN_HOST=my-account.gremlin.cosmos.azure.com
GREMLIN_DATABASE=my-database
GREMLIN_CONTAINER=my-graph
GREMLIN_PARTITION_KEY_PATH=/tenantId
FUSEKI_DATASET_URL=http://localhost:3030/my-dataset
```

Discovery also recognizes graph-specific keys in appsettings/config JSON, Azure Functions `local.settings.json`,
VS Code workspace settings, supported properties files and simple YAML driver/application settings.
It does not evaluate application code, expand environment expressions, read arbitrary files, or infer endpoint
compatibility from configuration. See [setup discovery details](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/workbench.md#simplified-connection-setup).

Select **Save connection settings**. Standard Fuseki query, update and graph-store URLs are derived from the dataset
URL; run graph names, identity-preserving mapping, batching and request timeouts already have defaults.
Open **Advanced configuration and mapping** only when you need custom endpoints, authentication/CA references,
a custom identity inventory, a domain vocabulary, or different limits.

Use **Store credentials securely** after saving the connection settings. The default source reference is
`env:GRAPH_SOURCE_KEY`. For a protected Fuseki server, configure its target authentication reference in advanced
settings, then store that credential securely. SecretStorage or approved `env:GRAPH_*` references hold credentials;
never paste key/password values into the project JSON, connection fields or migration reports.

## Migrate the database and application

### 1. Review the application and RDF mapping

Run **Assess application** to find graph-client configuration, Gremlin submissions, bindings, result handling and
retry assumptions. Review unsupported queries and mutations before choosing a migration approach.

The default RDF mapping preserves graph identities and property occurrences without requiring a custom ontology.
Run **Validate mapping**; customize labels, properties, relationships and optional domain SHACL rules in advanced
settings if your application needs a particular RDF vocabulary.

### 2. Discover source identities and check compatibility

Saving a new source creates a real empty bootstrap identity inventory if none exists. This permits setup and
discovery; **it is not an extraction-ready inventory or compatibility evidence**.

In **Prepare your source**, select **Check read-only connections**. Source and target results are independent;
missing inventories do not hide connection failures. A blocked result identifies the failed substep and a safe
remedy, without exposing credentials or provider query text.

Select **Find partition candidates**. Supported local seed files (`data/gremlin/gremlin_load_file.txt`) and
`gremlin-partitions.json` can supply candidates automatically; review/add string partition values only when needed.
Seeds are **unverified candidates**, not a live inventory. Choose explicit request, duration and RU ceilings,
confirm the source consistency declaration, then select **Start bounded read-only preparation**.
The RU ceiling applies per run; preparation and the fresh extraction run retain separate request journals.

Enumeration reconciles global and per-partition counts. Incomplete coverage, changed counts, exhausted budgets
or unobservable RU charges block completion. Successful enumeration automatically selects the actual inventory
and checksum in a fresh extraction run, preserving prior artifacts and invalidating old approvals—no advanced
JSON edits or manual hash refresh. Then select **Check read-only source fidelity**.
After interruption, **Find partition candidates** also offers **Resume preserved read-only preparation** when
the same-input checkpoint has remaining reviewed capacity; otherwise review a new bounded run.

Connection success and reconciled counts alone do not prove source property/system-metadata fidelity.
See the [source compatibility and extraction guide](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/source-extraction.md)
for supported identities and endpoint-specific limits.

### 3. Extract and load

Run **Extract read-only source** to produce bounded canonical NDJSON and its completion manifest. Extraction
does not change source records. Incomplete extraction is not loadable.

Review the exact target run graphs, select **Authorize** for **Load approved target graphs**, then run the load.
Approvals are scoped to the operation, run, inputs, resources and expiry; storing a credential is not write approval.
The optional disposable target capability probe has its own approval.

Confirmed batches advance checkpoints. Use **Pause** or **Cancel** to stop work; use **Resume from confirmed
checkpoint** only after reviewing preserved state. Changing endpoints, mappings or other hashed inputs invalidates
dependent evidence and approvals, and may require a new run rather than adopting old checkpoints.

For bounded editor execution, set `limits.maximumOperationMillis` in advanced settings. Progress displays confirmed
record counts and elapsed time. A paused operation is not a successful migration.

### 4. Validate behavior and adapt the application

Run **Validate structural fidelity** to compare canonical RDF in batches, reconcile counts/endpoints and check SHACL.
Compare supported queries with typed expected results, including multiplicity and ordering where applicable.

The translator supports a restricted Gremlin read subset, not arbitrary Groovy or dynamic query assembly.
The Java rewrite workflow supports documented AST patterns and writes only to a separately reviewed new file.
Use **Review Java rewrite plan** before authorizing a rewrite. Broader application changes remain manual.
The Java `GraphAccess` layer provides typed reads and identity-preserving CRUD for supported operations.

Build and compare your before/after application against the intended target. Successful translation or rewriting
alone does not establish application equivalence. See [application migration](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/application-migration.md).

### 5. Review readiness and cut over deliberately

Use **Evaluate readiness** to inspect current evidence and remaining blockers. Production cutover requires
endpoint-specific compatibility, data/application equivalence and operational review.

Manual publication produces a reviewed application configuration artifact; an operator deploys it through their
normal production process. It does not restart applications or route traffic automatically.
Source fixture writes, target cleanup and publication are separately approved operations, not normal extraction steps.

See [cutover and rollback](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/cutover.md) before target-only writes: reverse synchronization is not
implemented, so restoring an old connection after new target writes can lose data without reconciliation.

## Java CLI and building from source

The Java worker can also perform local generation, RDF export, translation and other documented operations without
VS Code. Build dependencies are Node.js 24.15 or newer and Java 21; Maven is pinned through the repository's wrapper.

From the repository root:

```powershell
npm ci
npm run graph:prepare
java -jar resources\graph-migration\worker.jar translate urn:example:canonical "g.V().count()"
npm run package
```

`npm run package` rebuilds the isolated Java workers and extension and writes the installable VSIX to the repository
root. It is packaging, not live migration acceptance. CLI arguments and exact approval scopes are documented in
the [graph migration guide](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/README.md).

## Reference and support

| Guide                                                                                                                                                 | Covers                                                                                                             |
| ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| [Workbench](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/workbench.md)                         | Setup discovery, advanced settings, approvals, progress and evidence.                                              |
| [RDF mapping](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/mapping.md)                         | Canonical identities, supplementary relationships and domain SHACL.                                                |
| [Source compatibility](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/compatibility.md)          | Pinned runtime/driver boundaries and unverified live requirements.                                                 |
| [Source extraction](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/source-extraction.md)         | Identity inventories, bounded discovery, extraction and source recovery.                                           |
| [Recovery](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/recovery.md)                           | Confirmed target checkpoints, ambiguous writes and ownership.                                                      |
| [Application migration](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/application-migration.md) | Supported translations, Java rewrites and typed CRUD.                                                              |
| [Performance evidence](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/docs/graph-migration/performance.md)            | What measured results do and do not establish. Long load/soak runs are optional explicit actions, not setup steps. |

Report fork-specific problems in [alekseys/gremlin2rdf issues](https://github.com/alekseys/gremlin2rdf/issues).
Use the repository's [security policy](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/SECURITY.md) for sensitive reports; do not attach credentials or source-data
artifacts to public issues. Migration artifacts may contain graph identities, values and application details.

The fork retains upstream extension infrastructure. VS Code telemetry/privacy settings still apply; see
[VS Code telemetry controls](https://code.visualstudio.com/docs/configure/telemetry).

Licensed under [MIT](https://github.com/alekseys/gremlin2rdf/blob/alekseys-gremlin-to-rdf-workbench/LICENSE.md). Upstream attribution and notices remain in the repository.
