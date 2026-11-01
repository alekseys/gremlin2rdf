# gremlin2rdf: migrate your Cosmos Gremlin project

Move an existing **Azure Cosmos DB for Gremlin database and application** toward **Apache Jena/Fuseki**
or **PostgreSQL with Apache AGE**, using one VS Code extension.

Choose RDF/SPARQL with Jena, or a property graph/Cypher with AGE. Both preserve typed, partition-qualified
identities, parallel edges, self-loops and individual property occurrences when the source exposes their fidelity.
Unsupported or unobservable semantics are reported rather than guessed. This is an **experimental fork**,
not a Microsoft Marketplace release.

## Install and choose your target

1. Install **`vscode-cosmosdb-0.39.3.vsix`** using VS Code's **Extensions > Install from VSIX**, then reload.
   Stop active migrations before upgrading; this replaces the extension, not its saved projects.
2. Open your application's folder as a trusted workspace and enable:

   ```json
   { "cosmosDB.experimental.graphMigration.enabled": true }
   ```

3. From the Command Palette, select **Open Gremlin to RDF Migration (Experimental)** for Jena or
   **Open Gremlin to PostgreSQL AGE Migration (Experimental)** for AGE.

Jena requires **Java 21+** on the extension host. AGE bundles its own independent, self-contained
**Windows x64 .NET worker**; using that binary does not require installing .NET.
Other AGE extension-host platforms are unsupported by this build. VS Code's installer may report `DEP0169`
despite successful installation; that warning [belongs to the VS Code CLI](docs/graph-migration/workbench.md#installing-or-replacing-the-vsix).

## Migrate in four steps

1. **Connect existing databases.** Save your Cosmos Gremlin source and existing Fuseki dataset or PostgreSQL/AGE
   target. Use **Store credentials securely**; never put passwords or keys in configuration JSON.
2. **Prepare and review.** Confirm paused writes or an immutable source. For Jena, **Prepare your source**
   checks source and target connections independently, discovers literal partition candidates from supported
   local seed/config files, and runs explicitly bounded read-only enumeration. Live counts must reconcile;
   candidates are not proof of coverage. The verified inventory/hash is selected automatically in a fresh run,
   with prior artifacts retained and old approvals invalidated. Then check actual source fidelity and RDF/SHACL
   mapping. AGE currently requires a reviewed inventory and endpoint observations, or a complete canonical input;
   it does not use Java's enumeration adapter.
3. **Approve and transfer.** Extract read-only source data, review the exact target/input, authorize the load,
   then run it. Credentials are not write approval. Pause/cancel when needed and resume only from confirmed
   state. Jena's interrupted enumeration can resume within the same reviewed remaining ceilings; new budgets
   require a newly reviewed run. RU ceilings apply per run, not across separate preparation/extraction journals.
4. **Validate and adapt.** Review RDF fidelity or AGE graph/ledger consistency and supported query results.
   Preview supported Gremlin as SPARQL or Cypher, review the language-specific rewrite, and compare the adapted
   application before deliberate manual cutover.

The tool **does not provision services**, install/enable AGE, restart databases, change production routing,
or open a deployed Fuseki server's live TDB2 files. Your AGE operator handles extension allowlisting,
installation/session `LOAD` permissions, a dedicated graph, PostgreSQL privileges, networking and trusted TLS.
Source writes, cleanup and publication require separate scoped approval.

Projects stay separate: `.cosmosdb-graph-migration/` for Jena and `.cosmosdb-age-migration/` for AGE.
Keep their artifacts and checkpoints local. Specific sanitized failures identify the failed substep and remedy;
connection success alone does not establish source fidelity or live migration readiness.

For Fuseki, use an existing **dataset URL** such as `http://localhost:3030/ekg`, not just `http://localhost:3030`.
If the intended dataset is absent, your operator must create or mount it separately; the extension does not.
Save its actual query/update/graph-store paths (custom paths are in advanced settings), then rerun
**Check read-only connections**. HTTP 404 identifies a missing/misconfigured endpoint, not proof every dataset
is absent. **Stale** results are previous input-bound evidence, not a new connection failure.
Version 0.39.3 fixes the extension-owned Gremlin driver pool constructor defect; inventory or credential edits
cannot fix that defect in older builds.

## Start small

Local fixtures and translation tools work without live credentials. They demonstrate canonical records and
restricted queries, **not live Cosmos compatibility or verified PostgreSQL/AGE transactions**.

Java/Jena rewriting and C#/AGE rewriting are separate. AGE's narrow Roslyn rewrite handles literal, one-argument
`SubmitAsync` calls on explicitly declared `IGremlinClient` receivers into a **new reviewed file**, with
`GraphAccess` and a cancellation token already declared. Dynamic queries, bindings, lifecycle/result-model
changes and broader application conversion remain manual. Typed access layers cover only documented operations.

## Guides and source builds

| Guide                                                                       | Use it for                                                              |
| --------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| [Jena workbench](docs/graph-migration/workbench.md)                         | Guided Prepare, RDF/SHACL mapping, approvals and artifacts              |
| [Jena application migration](docs/graph-migration/application-migration.md) | Supported Java rewrites, SPARQL and typed access                        |
| [AGE workflow and CLI](docs/age-migration/README.md)                        | Operator prerequisites, canonical input, Cypher, C# and recovery limits |
| [Cutover and rollback](docs/graph-migration/cutover.md)                     | Operational review; reverse synchronization is not implemented          |

Build the combined VSIX on Windows x64 with Node.js 24.15+, Java 21+ and the pinned **.NET SDK 10.0.403**:

```powershell
npm ci
# If the pinned SDK is not on PATH:
$env:AGE_DOTNET_SDK = "C:\path\to\dotnet.exe"
npm run package
```

Packaging builds both independent workers; it does not establish live migration acceptance.
Native PostgreSQL/AGE execution and live Cosmos extraction remain unverified for this AGE deliverable.

[Report fork issues](https://github.com/alekseys/gremlin2rdf/issues).
[Security policy](SECURITY.md) | [MIT license](LICENSE.md).
Upstream attribution and VS Code telemetry/privacy controls remain applicable.
