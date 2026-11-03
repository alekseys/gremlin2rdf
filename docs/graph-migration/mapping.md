# Canonical RDF fidelity

## Explicit domain values and occurrence constraints

Optional `valueRules` configure supplementary domain predicates on canonical property-occurrence resources.
Canonical `value`, `valueType` and original lexical fields are never replaced. Rules declare the expected source type
and either `resource` (absolute IRI string, no datatype) or `literal` with a supported explicit XSD datatype.
Invalid source types/lexical conversions stop rather than coercing or inventing dates/numbers.

```json
{
  "valueRules": {
    "homepage": {"sourceType": "string", "representation": "resource", "datatype": null},
    "age": {"sourceType": "integer", "representation": "literal", "datatype": "http://www.w3.org/2001/XMLSchema#integer"}
  },
  "domainRules": [
    {"label": "person", "property": "age", "minimumOccurrences": 1, "maximumOccurrences": 1}
  ]
}
```

Domain rules apply to matching vertex labels. Canonical occurrence resources are counted, preserving duplicate
equal-valued properties. Qualified SHACL occurrence shapes enforce the same minimum/maximum policy without
collapsing equal literals. Value shapes enforce datatype or IRI node kind. Validation combines these generated
shapes with canonical SHACL; `mapping shapes <mapping.json> <new-output.ttl>` exports both for operator review.
Omitting the optional fields preserves old mappings; introducing rules changes the mapping hash and invalidates
prior evidence. Existing reserved vocabulary restrictions still apply.

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

SHACL and structural validation inspect the complete canonical dataset in record/serialized-byte-bounded batches,
including resolving endpoint context, canonical
record reconstruction, property occurrences and derived-view consistency. A local per-record RDF export is not a global
SHACL pass. Exported N-Triples contain deterministic bounded per-record frames; shared triples can repeat in the file.
RDF graph uniqueness is distinct from serialized frame count and logical source record count.

Reconciliation now retrieves the subjects owned by an entire batch with one CONSTRUCT request and endpoint SHACL
context with at most one further request, rather than multiple remote calls per individual edge. Exact typed RDF
differences are attributed to their source records. Global subject/triple inventory and derived-view checks remain
mandatory; batching does not replace fidelity with sampling. These source changes have not been built or tested.
