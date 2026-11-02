package io.github.gremlin2rdf.source;
import java.nio.file.*;
public final class DriverProbe {
    public static void main(String[] args) throws Exception {
        var project = io.github.gremlin2rdf.Json.MAPPER.readTree(Files.readAllBytes(Path.of(args[0])));
        var cluster = SourceMain.clusterBuilder(project, "fixture").create();
        try {
            var client = cluster.connect();
            var result = client.submit("g.V().count()");
            var values = result.all().get();
            System.out.println("resultCount=" + values.size());
            for (var value : values) System.out.println("resultType=" + value.getObject().getClass().getName());
            System.out.println("statusAttributes=" + result.statusAttributes().get().keySet());
        } finally { cluster.close(); }
    }
}
