package example;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

public final class GraphRepository {
    private final GraphTraversalSource g;

    public GraphRepository(GraphTraversalSource g) { this.g = g; }

    public long vertices() { return g.V().count().next(); }
    public long edges() { return g.E().count().next(); }
    public String unrelated() { return "preserved"; }
}
