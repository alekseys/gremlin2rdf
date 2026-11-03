package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationTest {
    @TempDir Path root;
    @Test void inventoryCountsRepeatedKeysButRejectsNewBytesBeyondCapacity() throws Exception {
        Map<String, Long> counts = new TreeMap<>();
        assertEquals(4194304, Validation.inventory(counts, "ab", 4194302));
        assertEquals(4194304, Validation.inventory(counts, "ab", 4194304));
        assertEquals(2L, counts.get("ab"));
        assertThrows(IOException.class, () -> Validation.inventory(counts, "c", 4194304));
        assertFalse(counts.containsKey("c"));
    }
    @Test void documentTargetChecksAreScopedObservationsNotStrictSourceEquivalence() throws Exception {
        var strict=RecoveryTest.approved(RecoveryTest.project("document-validation"));
        Path records=root.resolve("records.ndjson");
        Synthetic.generate(new Synthetic.Profile(1,42,2,1,1),records);
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(strict);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("mapping")).put("fidelity","reduced");
        var reduced=RecoveryTest.approved(Json.MAPPER.treeToValue(tree,Project.class));
        tree=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(reduced);
        tree.withArray("approvals").add(Json.MAPPER.valueToTree(Approval.sign(reduced,"reduced-fidelity",
            List.of(reduced.mapping().hash()),java.time.Instant.now().plusSeconds(300).toString(),"env:GRAPH_TEST_APPROVAL_KEY")));
        reduced=Json.MAPPER.treeToValue(tree,Project.class);
        try(var sink=new Sink(reduced,RDFConnection.connect(DatasetFactory.createTxnMem()))){
            new Engine().load(reduced,records,root.resolve("state"),sink,update->{},batch->{});
            var legacy=Validation.validate(reduced,records,sink);
            assertEquals("blocked",legacy.status());
            assertFalse(legacy.strictInputFidelity());
            var source=tree.putObject("source");
            source.put("scope","fixture").put("host","localhost").put("port",443).put("database","fixture")
                .put("container","graph").put("partitionKeyPath","/pk").put("extractionStrategy","verified-identity-manifest")
                .put("identityManifest","identities.ndjson").put("identityManifestHash","0".repeat(64))
                .put("sourceMode","cosmos-documents").put("documentEndpoint","https://localhost/")
                .put("documentProfile","property-bags-v1");
            var documents=Json.MAPPER.treeToValue(tree,Project.class);
            var observed=Validation.validate(documents,records,sink);
            assertEquals("succeeded",observed.status());
            assertTrue(observed.graphContentExact());
            assertEquals("observed-document-canonical-only",observed.validationScope());
            assertFalse(observed.strictInputFidelity());
        }
    }
    @Test void atomicInputReplacementCannotProduceAReportForUnvalidatedBytes() throws Exception {
        var project = RecoveryTest.approved(RecoveryTest.project("validation-replacement"));
        var dataset = DatasetFactory.createTxnMem();
        Path source = root.resolve("records.ndjson"), replacement = root.resolve("replacement.ndjson");
        Synthetic.generate(new Synthetic.Profile(1, 42, 2, 0, 1), source);
        Synthetic.generate(new Synthetic.Profile(1, 43, 3, 0, 1), replacement);
        var connection = RDFConnection.connect(dataset);
        try (var sink = new Sink(project, connection)) {
            new Engine().load(project, source, root.resolve("state"), sink, update -> {}, batch -> {});
            AtomicBoolean replaced = new AtomicBoolean();
            var observed = (RDFConnection) Proxy.newProxyInstance(RDFConnection.class.getClassLoader(),
                new Class<?>[] { RDFConnection.class }, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("query") && replaced.compareAndSet(false, true))
                        Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
            try (var reading = new Sink(project, observed)) {
                var failure = assertThrows(Validation.InputChanged.class, () -> Validation.validate(project, source, reading));
                assertTrue(replaced.get());
                assertEquals("CANONICAL_INPUT_CHANGED", Main.diagnostic(failure).get("code"));
            }
        }
    }
}
