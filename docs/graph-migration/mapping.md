# Canonical RDF fidelity

The reserved vocabulary is `urn:gremlin2rdf:canonical:v1:`. Vertex IRIs hash the complete typed scope/partition/ID tuple
with a versioned vertex identity domain. Edge IRIs also include the verified edge scope and, for source-vertex scope,
the complete source identity. String `"1"`, integer `1`, differing partitions and different source scopes remain distinct.
Labels and domain predicates are configured or deterministically hashed, never interpolated as unchecked URI fragments.

Each logical edge is a canonical resource with source/destination vertex IRIs and edge-scope metadata. Parallel edges,
self-loops and reciprocal edges therefore remain independent. Optional derived subject/predicate/object relationships
can collapse equal triples and are not a lossless edge model or a source population count.

Properties are occurrence resources owned by their entity. Observed property IDs are typed and preserve identical
values with distinct occurrence IDs. Duplicate observed occurrence IDs fail rather than merge. ID-less reduced-fidelity
occurrences use deterministic content/multiplicity identities; that does not invent missing observed source IDs.
Meta-properties are owned occurrence resources. Required source/system metadata and normalized records are preserved.

Typed values use XSD string, boolean, integer, float or double literals, plus explicit type and original lexical fields
for reconstruction. Literal serialization is performed by Jena. Floating-point comparison normalizes only the supported
float/double value-space representation; it does not stringify or sort away typed, multiplicity or sequence differences.
Unsupported nonfinite values, records over the bound, missing endpoints and insufficient fidelity fail explicitly.

Strict mapping cannot exclude required properties or accept reduced source fidelity. A separately approved reduced
mapping or best-effort source remains ineligible for strict cutover equivalence. Mapping hashes bind provenance and all
later approvals/checkpoints. Changing the mapping requires a new compatible run; never reinterpret an old checkpoint.

SHACL and structural validation inspect the complete canonical dataset, including resolving endpoint context, canonical
record reconstruction, property occurrences and derived-view consistency. A local per-record RDF export is not a global
SHACL pass. Exported N-Triples contain deterministic bounded per-record frames; shared triples can repeat in the file.
RDF graph uniqueness is distinct from serialized frame count and logical source record count.
