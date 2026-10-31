package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PopulationTest {
    @TempDir Path root;
    private Project project(List<Approval> approvals) {
        var mapping = new Mapping(1, "urn:test:", "urn:domain:", Map.of(), Map.of(), Map.of(), Set.of(), true, true, "strict");
        var source = new Project.Source("fixture", "localhost", 443, "db", "dedicated", "/pk", null,
            "verified-identity-manifest", "identities.ndjson", "0".repeat(64), null, "10000", null, true);
        return new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "fixture", 1, "0.1.0", mapping, source, null,
            new Project.Limits(1, 65536, 32768, 1000, 0, 1, 1, 0), "quiesced", Map.of(), approvals, Map.of(), List.of());
    }
    private GraphRecord vertex(String id) {
        var identity = new GraphRecord.Identity("fixture", GraphRecord.Value.string("p"), GraphRecord.Value.string("g2r-fixture-" + id));
        return new GraphRecord(GraphRecord.Kind.vertex, identity, "person", null, null, null, List.of(
            new GraphRecord.Property("pk", identity.partition(), GraphRecord.Value.string(identity.id().lexical() + "-pk"), Map.of(), GraphRecord.Fidelity.complete),
            new GraphRecord.Property(PopulationPlan.OWNER_KEY, GraphRecord.Value.string("fixture"), GraphRecord.Value.string(identity.id().lexical() + "-owner"),
                Map.of(), GraphRecord.Fidelity.complete)), Map.of(),
            new GraphRecord.Provenance("fixture", "0".repeat(64), "quiesced", GraphRecord.Fidelity.complete));
    }
    private PopulationPlan plan(GraphRecord... records) throws Exception {
        Files.writeString(root.resolve("records.ndjson"), Arrays.stream(records).map(Json::text).reduce("", (a, b) -> a + b + "\n"));
        return PopulationPlan.create(project(List.of()), root, "records.ndjson", 1000);
    }
    private Project authorized(PopulationPlan plan) {
        var project = project(List.of());
        return project(List.of(Approval.sign(project, "source-populate", plan.resources(), Instant.now().plusSeconds(600).toString(), "env:GRAPH_TEST_APPROVAL_KEY"),
            Approval.sign(project, "source-cleanup", plan.resources(), Instant.now().plusSeconds(600).toString(), "env:GRAPH_TEST_APPROVAL_KEY")));
    }
    static class Memory implements Population.Transport {
        final Set<String> present = new HashSet<>(); int creates, deletes; boolean uncertain, foreign, mismatched, uncertainDelete;
        public boolean empty() { return present.isEmpty(); }
        public Population.Observation inspect(GraphRecord record, boolean compare) {
            return new Population.Observation(present.contains(record.identity().id().lexical()), true, !mismatched, foreign ? 1 : 0, "private-test-version");
        }
        public void create(GraphRecord record) throws IOException {
            creates++; present.add(record.identity().id().lexical());
            if (uncertain) { uncertain = false; throw new IOException("Private injected disconnect after committed creation"); }
        }
        public void delete(GraphRecord record, String etag) throws IOException {
            assertEquals("private-test-version", etag);
            deletes++;
            if (uncertainDelete) throw new IOException("Private injected uncertain deletion");
            present.remove(record.identity().id().lexical());
        }
    }
    @Test void defaultPlansDoNotWriteAndMissingAuthorizationFailsBeforeTransport() throws Exception {
        var plan = plan(vertex("a")); var memory = new Memory();
        assertEquals(4, plan.estimatedWrites());
        assertThrows(SecurityException.class, () -> Population.run(project(List.of()), root, plan, "ownership.json", false, memory));
        assertEquals(0, memory.creates);
    }
    @Test void resumesUncertainOwnedCreationWithoutRepeatingMutationAndCleansOnlyRecordedIdentities() throws Exception {
        var plan = plan(vertex("a"), vertex("b")); var project = authorized(plan); var memory = new Memory(); memory.uncertain = true;
        assertThrows(IOException.class, () -> Population.run(project, root, plan, "ownership.json", false, memory));
        assertTrue(Files.readString(root.resolve("ownership.json")).contains("\"intent\":true"));
        assertEquals("succeeded", Population.run(project, root, plan, "ownership.json", false, memory).get("status"));
        assertEquals(2, memory.creates);
        assertEquals("succeeded", Population.run(project, root, plan, "ownership.json", true, memory).get("status"));
        assertEquals(2, memory.deletes);
        Population.run(project, root, plan, "ownership.json", true, memory); assertEquals(2, memory.deletes);
        assertThrows(IOException.class, () -> Population.run(project, root, plan, "ownership.json", false, memory));
    }
    @Test void rejectsForeignIncidentEdgesPartialContentAndTamperedOwnership() throws Exception {
        var plan = plan(vertex("a")); var project = authorized(plan); var memory = new Memory();
        Population.run(project, root, plan, "ownership.json", false, memory); memory.foreign = true;
        assertThrows(SecurityException.class, () -> Population.run(project, root, plan, "ownership.json", true, memory)); assertEquals(0, memory.deletes);
        Files.writeString(root.resolve("ownership.json"), "{}");
        assertThrows(Exception.class, () -> Population.run(project, root, plan, "ownership.json", true, memory));
    }
    @Test void rejectsOutOfRangeNativeValuesDuplicateIdentitiesAndChangedPlansBeforeWrites() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PopulationPlan.nativeValue(new GraphRecord.Value(GraphRecord.ValueType.integer, "9223372036854775808")));
        assertThrows(IOException.class, () -> plan(vertex("a"), vertex("a")));
        var plan = plan(vertex("a")); Files.writeString(root.resolve("records.ndjson"), Json.text(vertex("b")) + "\n");
        var memory = new Memory();
        assertThrows(IOException.class, () -> Population.run(authorized(plan), root, plan, "ownership.json", false, memory)); assertEquals(0, memory.creates);
    }
    @Test void fixedRecipesBindHostileValuesAndNeverContainUnrestrictedDrops() throws Exception {
        var project = project(List.of()); var original = vertex("a");
        var properties = new ArrayList<>(original.properties());
        String attack = "');g.V().drop();/*";
        properties.add(new GraphRecord.Property("name", GraphRecord.Value.string(attack), GraphRecord.Value.string("g2r-fixture-name"), Map.of(), GraphRecord.Fidelity.complete));
        var record = new GraphRecord(original.kind(), original.identity(), attack, null, null, null, properties, Map.of(), original.provenance());
        var script = PopulationScripts.create(project, record);
        assertFalse(script.text().contains(attack)); assertTrue(script.bindings().containsValue(attack));
        assertTrue(script.text().contains("VertexProperty.Cardinality.list")); assertTrue(script.text().contains("T.id"));
        var deletion = PopulationScripts.delete(project, record, "private-test-version");
        assertFalse(deletion.text().contains("g.V().drop()"));
        assertTrue(deletion.text().contains(".has(g2rOwnerKey,g2rRun).has('_etag',g2rEtag).not(bothE()).drop()"));
        assertEquals("private-test-version", deletion.bindings().get("g2rEtag"));
        assertThrows(IllegalArgumentException.class, () -> PopulationScripts.delete(project, record, null));
    }
    @Test void uncertainDeletionIsRecordedAndNeverBlindlyRetried() throws Exception {
        var plan = plan(vertex("a")); var project = authorized(plan); var memory = new Memory();
        Population.run(project, root, plan, "ownership.json", false, memory);
        memory.uncertainDelete = true;
        assertThrows(IOException.class, () -> Population.run(project, root, plan, "ownership.json", true, memory));
        assertEquals(1, memory.deletes);
        assertTrue(Files.readString(root.resolve("ownership.json")).contains("\"deleting\":\"vertex:0\""));
        memory.uncertainDelete = false;
        assertThrows(IOException.class, () -> Population.run(project, root, plan, "ownership.json", true, memory));
        assertEquals(1, memory.deletes);
        memory.present.clear();
        assertEquals("succeeded", Population.run(project, root, plan, "ownership.json", true, memory).get("status"));
    }
    @Test void derivesByteDeterministicReviewRecipesWithoutMutationAuthorization() throws Exception {
        var plan = plan(vertex("a"));
        var a = PopulationScripts.export(project(List.of()), root, plan, "a.ndjson");
        var b = PopulationScripts.export(project(List.of()), root, plan, "b.ndjson");
        assertEquals(a, b); assertEquals(-1, Files.mismatch(root.resolve("a.ndjson"), root.resolve("b.ndjson")));
        assertEquals(false, a.get("sourceMutationsExecuted"));
        assertTrue(Files.readString(root.resolve("a.ndjson")).contains("\"canonicalRecordHash\""));
        assertThrows(IOException.class, () -> PopulationScripts.export(project(List.of()), root, plan, "a.ndjson"));
    }
    @Test void inputEditsCannotChangeLaterApprovedSourceWritesOrProduceSuccess() throws Exception {
        var plan = plan(vertex("a"), vertex("b"));
        var memory = new Memory() {
            @Override public void create(GraphRecord record) throws IOException {
                super.create(record);
                if (creates == 1) Files.writeString(root.resolve("records.ndjson"), Json.text(vertex("foreign")) + "\n");
            }
        };
        assertThrows(IOException.class, () -> Population.run(authorized(plan), root, plan, "ownership.json", false, memory));
        assertEquals(Set.of("g2r-fixture-a", "g2r-fixture-b"), memory.present);
        assertEquals(2, memory.creates);
        assertEquals(2, Json.MAPPER.readValue(Files.readAllBytes(root.resolve("ownership.json")), Population.State.class).confirmed());
        try (var files = Files.list(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".g2r-approved-")));
        }
    }
}
