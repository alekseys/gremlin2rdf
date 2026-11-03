package io.github.gremlin2rdf;

import java.nio.file.*;
import java.util.*;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.*;
import org.apache.jena.shacl.ShaclValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RdfExportTest {
    @TempDir Path root;
    private Project project() {
        return new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "fixture", 1, "0.1.0", Mapping.standard(), null, null,
            new Project.Limits(10, 65536, 32768, 1000, 0, 10, 1, 0), "immutable", Map.of(), List.of(), Map.of(), List.of());
    }
    @Test void exportsActualRdfDeterministicallyAndIsomorphicallyWithoutAnyEndpoint() throws Exception {
        var profile = new Synthetic.Profile(1, 42, 5, 10, 2);
        Path source = root.resolve("source.ndjson");
        Synthetic.generate(profile, source);
        var a = RdfExport.run(project(), source, root.resolve("a"));
        var b = RdfExport.run(project(), source, root.resolve("b"));
        assertEquals(a, b); assertEquals(-1, Files.mismatch(root.resolve("a").resolve("canonical.nt"), root.resolve("b").resolve("canonical.nt")));
        var expected = ModelFactory.createDefaultModel();
        var actual = ModelFactory.createDefaultModel();
        try {
            var mapper = new RdfMapper(project().mapping());
            for (int i = 0; i < 15; i++) try (var mapped = mapper.map(Synthetic.record(profile, i))) { expected.add(mapped.canonical()); }
            RDFDataMgr.read(actual, root.resolve("a").resolve("canonical.nt").toString(), Lang.NTRIPLES);
            assertTrue(expected.isIsomorphicWith(actual));
            var shapes = RDFParser.source(getClass().getResourceAsStream("/canonical-v1.shacl.ttl")).lang(Lang.TTL).toGraph();
            try { assertTrue(ShaclValidator.get().validate(shapes, actual.getGraph()).conforms()); }
            finally { shapes.close(); }
        } finally { expected.close(); actual.close(); }
        assertThrows(java.io.IOException.class, () -> RdfExport.run(project(), source, root.resolve("a")));
    }
    @Test void malformedInputRetainsOwnedPartialFilesWithoutPublishingSuccessManifest() throws Exception {
        Path source = root.resolve("malformed.ndjson"); Files.writeString(source, "{}\n");
        assertThrows(Exception.class, () -> RdfExport.run(project(), source, root.resolve("partial")));
        assertTrue(Files.exists(root.resolve("partial").resolve("ownership.json")));
        assertFalse(Files.exists(root.resolve("partial").resolve("manifest.json")));
    }
}
