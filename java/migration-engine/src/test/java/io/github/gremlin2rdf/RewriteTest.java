package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RewriteTest {
    @Test void shadowedSourceAndEmbeddedCredentialsAreBlockedWithoutPersistingSource() throws Exception {
        for (String body : new String[] {
            "long count(Other g) { return g.V().count().next(); }",
            "String password=\"private-literal\"; long count() { return g.V().count().next(); }"
        }) {
            Files.writeString(root.resolve("Fixture.java"),
                "import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;\n"
                    + "class Fixture { GraphTraversalSource g; " + body + " }");
            var plan = Rewrite.plan(root, "Fixture.java", "urn:fixture");
            assertFalse(plan.blockers().isEmpty()); assertEquals("", plan.output());
            assertFalse(Json.text(plan).contains("private-literal"));
        }
    }
    @TempDir Path root;
    @Test void astRewritePreservesUnrelatedCodeAndComments() throws Exception {
        String source = "import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;\n"
            + "class Fixture { final GraphTraversalSource g; Fixture(GraphTraversalSource g) { this.g=g; }\n"
            + "// Retain this comment\nlong size() { return g.V().count().next(); }\nint unrelated() { return 7; }}";
        Files.writeString(root.resolve("Fixture.java"), source);
        var plan = Rewrite.plan(root, "Fixture.java", "urn:fixture");
        assertTrue(plan.blockers().isEmpty());
        assertTrue(plan.output().contains("// Retain this comment"));
        assertTrue(plan.output().contains("int unrelated() { return 7; }"));
        assertTrue(plan.output().contains("GraphAccess")); assertTrue(plan.output().contains("SELECT"));
        assertFalse(plan.output().contains("GraphTraversalSource"));
        assertEquals(source, Files.readString(root.resolve("Fixture.java")));
    }
    @Test void driverSubmitAndDynamicAssemblyAreNotGuessed() throws Exception {
        Files.writeString(root.resolve("Fixture.java"), "class Fixture { Object query() { return client.submit(\"g.V()\" + input); }}");
        var plan = Rewrite.plan(root, "Fixture.java", "urn:fixture");
        assertFalse(plan.blockers().isEmpty());
    }
    @Test void approvedPlanAppliesOnlyToNewOutputAndChangedSourceFailsClosed() throws Exception {
        var unsigned = RecoveryTest.project("rewrite");
        String source = "import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;\n"
            + "class Fixture { long size(GraphTraversalSource g) { return g.V().count().next(); } }";
        Files.writeString(root.resolve("Fixture.java"), source);
        var plan = Rewrite.plan(root, "Fixture.java", unsigned.graph("canonical"));
        assertTrue(plan.blockers().isEmpty());
        assertThrows(SecurityException.class, () -> Rewrite.apply(unsigned, root, plan, "new/Fixture.java"));
        assertFalse(Files.exists(root.resolve("new").resolve("Fixture.java")));
        var approval = Approval.sign(unsigned, "rewrite", List.of(plan.sourcePath(), plan.sourceHash(), Json.hash(plan), "new/Fixture.java"),
            Instant.now().plusSeconds(300).toString(), "env:GRAPH_TEST_APPROVAL_KEY");
        var approved = new Project(unsigned.schemaVersion(), unsigned.migrationKind(), unsigned.baselineRepositorySha(), unsigned.runId(),
            unsigned.protocolVersion(), unsigned.workerVersion(), unsigned.mapping(), unsigned.source(), unsigned.target(), unsigned.limits(),
            unsigned.consistency(), unsigned.phases(), List.of(approval), unsigned.applicationHashes(), unsigned.artifacts());
        Rewrite.apply(approved, root, plan, "new/Fixture.java");
        assertEquals(plan.output(), Files.readString(root.resolve("new").resolve("Fixture.java")));
        assertEquals(source, Files.readString(root.resolve("Fixture.java")));
        assertThrows(java.io.IOException.class, () -> Rewrite.apply(approved, root, plan, "new/Fixture.java"));
        Files.writeString(root.resolve("Fixture.java"), "class Changed {}");
        assertThrows(java.io.IOException.class, () -> Rewrite.apply(approved, root, plan, "other/Fixture.java"));
        assertFalse(Files.exists(root.resolve("other").resolve("Fixture.java")));
    }
}
