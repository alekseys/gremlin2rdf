# Dependency and release evidence

Generate the pinned Java SBOM/license artifacts with `java/mvnw -f java/pom.xml -Preports -DskipTests verify`.
Then run `node scripts/audit-graph-release.mjs` to persist Java and extension CycloneDX SBOMs, third-party notices,
OSV package/version observations and npm production-dependency audit evidence. The collector sends only public
third-party Maven coordinates to OSV, excludes this fork's own Maven modules, handles pagination and bounds responses.
It does not transmit source code, application queries, records or credentials.

`dependency-report.json` is blocked when advisories, missing license declarations or unconfirmed generators/scans
remain. A completed report-generation command is not release approval. OSV IDs identify known affected package/version
advisories; their presence is not an assertion of exploitability in this extension or source adapter.

The current observed audit has 26 Java packages with advisories, seven extension advisories, and one missing Java
license declaration. These remain explicit review blockers. Preserve pre-existing extension findings separately;
do not silently repair unrelated upstream dependencies. The Microsoft-supported 3.4.13 source driver remains isolated
from Jena. Do not apply an unsupported transitive override or incompatible driver upgrade just to suppress advisories.
Any dependency correction requires compatibility, behavioral and release review, including live source evidence where
the source driver is affected.

The reports are repository-level observations, not automatically current-run cutover evidence. An operator must bind
reviewed reports and their linked SBOM/license checksums to a current project before readiness can establish that gate.
No packaging, marketplace publication, production mutation or application restart is authorized by these artifacts.
