package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.apache.jena.rdf.model.*;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Tdb2LiteralTest {
    @TempDir Path root;
    @Test void privateTdb2RetainsCanonicalMappedRdfTerms() {
        var record = Synthetic.record(Synthetic.Profile.semantic(42), 200);
        var dataset = TDB2Factory.connectDataset(root.resolve("new-private-fixture").toString());
        try (var mapped = new RdfMapper(Mapping.standard()).map(record)) {
            Txn.executeWrite(dataset, () -> dataset.addNamedModel("urn:fixture", mapped.canonical()));
            var actual = Txn.calculateRead(dataset, () -> ModelFactory.createDefaultModel().add(dataset.getNamedModel("urn:fixture")));
            try {
                var value = actual.createProperty(RdfMapper.CANONICAL + "value");
                assertTrue(mapped.canonical().isIsomorphicWith(actual), () -> "Expected literals: "
                    + mapped.canonical().listObjectsOfProperty(value).toList() + "; actual literals: "
                    + actual.listObjectsOfProperty(value).toList());
            } finally { actual.close(); }
        } finally { org.apache.jena.tdb2.sys.TDBInternal.expel(dataset.asDatasetGraph()); dataset.close(); }
    }
}
