package example;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.jupiter.api.Test;

class GraphRepositoryTest {
    @Test void isolatedGremlinApplicationExecutesDeclaredCountFixtures() throws Exception {
        try (var graph = TinkerGraph.open(); var traversal = graph.traversal()) {
            for (int i = 0; i < 20; i++) graph.addVertex(T.id, "vertex-" + i, T.label, "person");
            for (int i = 0; i < 40; i++) graph.vertices("vertex-" + (i % 19)).next()
                .addEdge("knows", graph.vertices("vertex-" + ((i * 7) % 19)).next(), T.id, "edge-" + i);
            var repository = new GraphRepository(traversal);
            assertEquals(20, repository.vertices()); assertEquals(40, repository.edges());
            assertEquals("preserved", repository.unrelated());
        }
    }
}
