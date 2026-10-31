package example;
import io.github.gremlin2rdf.GraphAccess;


public final class GraphRepository {
    private final GraphAccess g;

    public GraphRepository(GraphAccess g) { this.g = g; }

    public long vertices() { return g.count("SELECT  (count(*) AS ?count)\nWHERE\n  { GRAPH <urn:example:fixture:canonical>\n      { ?v0  a  <urn:gremlin2rdf:canonical:v1:Vertex> }}\n"); }
    public long edges() { return g.count("SELECT  (count(*) AS ?count)\nWHERE\n  { GRAPH <urn:example:fixture:canonical>\n      { ?v0  a  <urn:gremlin2rdf:canonical:v1:Edge> }}\n"); }
    public String unrelated() { return "preserved"; }
}
