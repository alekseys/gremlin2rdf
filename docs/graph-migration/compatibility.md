# Compatibility and evidence boundaries

Baseline: `22e3f2cccbfcc6622cacbf1873b9c620acba085e`.
The existing relational service, project fields and dispatcher remain separate.

| Component               | Pinned choice                                       | Evidence                                                                                |
| ----------------------- | --------------------------------------------------- | --------------------------------------------------------------------------------------- |
| Worker JDK              | Java 21                                             | Jena 6 requires Java 21; local runtime reports 21.0.12                                  |
| Jena                    | 6.2.0, all modules                                  | Official download page retrieved 2026-10-31                                             |
| Cosmos driver           | TinkerPop 3.4.13, isolated source JVM               | Microsoft compatibility table retrieved 2026-10-31                                      |
| Default document reader | Microsoft.Azure.Cosmos 3.58.0, isolated .NET worker | SDK API reference retrieved 2026-11-03; raw storage profiles remain explicitly selected |
| Serializer              | GraphSON V2                                         | Microsoft console configuration; script submission, not bytecode                        |
| Live Cosmos behavior    | Blocked                                             | No authorized endpoint/configuration supplied                                           |
| Remote update atomicity | Must be tested for the configured server            | RDFConnection does not provide distributed HTTP transactions                            |

The source driver and Jena do not share a runtime dependency graph. The source
adapter never submits application code, arbitrary Groovy, bytecode, or mutations.
Its JDK/serializer behavior on actual Cosmos is a live acceptance gate, not
established by TinkerGraph, an HTTP mock or a successful build.

New source configuration defaults to the separately packaged .NET document-feed adapter; existing mode-less projects
retain their pinned Gremlin adapter. Microsoft Learn documents SQL SDK/bulk access for graph accounts and their
`.documents.azure.com` account URI. That documents endpoint feasibility, not every exact read schema variant or live
account permission. [Document-source profiles](document-source.md) preserve raw JSON, require explicit storage layout
selection, and distinguish document fidelity from unestablished Gremlin runtime semantics. There is no automatic fallback
or implicit comparative benchmark. Missing occurrences/metadata/runtime type provenance cannot produce a strict pass.

Official references:

- https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/support
- https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/quickstart-console
- https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/bulk-executor-dotnet
- https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/partitioning
- https://learn.microsoft.com/en-us/dotnet/api/microsoft.azure.cosmos.container.getitemquerystreamiterator?view=azure-dotnet
- https://jena.apache.org/download/
- https://jena.apache.org/documentation/rdfconnection/

Microsoft explicitly warns about 3.5/3.6 driver incompatibilities. Jena documents
that each remote operation's atomicity is the server's responsibility. A data
update and its batch marker must therefore be one server update, never two
requests disguised as a transaction.
