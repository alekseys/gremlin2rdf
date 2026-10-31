package io.github.gremlin2rdf;

import java.util.*;

public record IdentityEntry(int schemaVersion, GraphRecord.Kind kind, GraphRecord.Value partition,
                            GraphRecord.Value id, GraphRecord.Value ownerId, GraphRecord.EdgeScope edgeScope) {
    public IdentityEntry {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported identity manifest version");
        Objects.requireNonNull(kind); Objects.requireNonNull(partition); Objects.requireNonNull(id);
        for (var value : ownerId == null ? List.of(id) : List.of(id, ownerId))
            if (!Set.of(GraphRecord.ValueType.string, GraphRecord.ValueType.integer, GraphRecord.ValueType.booleanValue).contains(value.type()))
                throw new IllegalArgumentException("Unsupported source identity type");
        if (kind == GraphRecord.Kind.edge && (ownerId == null || edgeScope == null)
            || kind == GraphRecord.Kind.vertex && (ownerId != null || edgeScope != null))
            throw new IllegalArgumentException("Verified edge scope and owner identity required");
    }
    public String orderingKey() { return Json.text(List.of(kind == GraphRecord.Kind.vertex ? "0" : "1", partition, id,
        edgeScope == null ? "" : edgeScope.name(), edgeScope == GraphRecord.EdgeScope.sourceVertex ? ownerId : "")); }
    public void verify(GraphRecord record, Project project) {
        if (record.kind() != kind || !record.identity().partition().equals(partition) || !record.identity().id().equals(id)
            || !record.identity().scope().equals(project.source().scope())
            || !record.provenance().sourceManifestHash().equals(project.source().identityManifestHash())
            || !record.provenance().consistency().equals(project.consistency())
            || kind == GraphRecord.Kind.edge && (record.edgeScope() != edgeScope || !record.source().id().equals(ownerId)))
            throw new IllegalArgumentException("Source record differs from verified identity, owner, scope or provenance");
    }
}
