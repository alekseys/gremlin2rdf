package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.Json;
import io.github.gremlin2rdf.Project;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline form/parser/production-builder smoke; never creates a client or submits a request. */
public final class FormClusterProbe {
    public static void main(String[] args) throws Exception {
        var project=Json.MAPPER.readValue(Files.readAllBytes(Path.of(args[0])),Project.class);
        var cluster=SourceMain.createCluster(SourceMain.clusterBuilder(Json.MAPPER.valueToTree(project),"offline-fixture-key"));
        try {
            System.out.println(Json.text(java.util.Map.of("status","succeeded","inputHash",project.inputHash(),
                "requestsSubmitted",0,"connectionsOpened",0,"driver","3.4.13")));
        } finally { cluster.close(); }
    }
}
