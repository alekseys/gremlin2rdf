package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.Json;
import io.github.gremlin2rdf.Project;
import io.github.gremlin2rdf.SourceCompatibility;
import io.github.gremlin2rdf.SafeFiles;
import io.github.gremlin2rdf.GraphRecord;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline form/parser/production-builder smoke; never creates a client or submits a request. */
public final class FormClusterProbe {
    public static void main(String[] args) throws Exception {
        var project=Json.MAPPER.readValue(Files.readAllBytes(Path.of(args[0])),Project.class);
        var cluster=SourceMain.createCluster(SourceMain.clusterBuilder(Json.MAPPER.valueToTree(project),"offline-fixture-key"));
        try {
            long records=0;
            if (args.length==2 && args[1].equals("--extract-fixture")) {
                var root=Path.of(args[0]).getParent();
                SourceCompatibility.read(project,root);
                var result=Extraction.run(project,SafeFiles.resolve(root,project.source().identityManifest()),
                    root.resolve("offline-records.ndjson"),new Extraction.Reader() {
                        public GraphRecord read(io.github.gremlin2rdf.IdentityEntry entry) {
                            return new GraphRecord(entry.kind(),new GraphRecord.Identity(project.source().scope(),entry.partition(),entry.id()),
                                "fixture",null,null,null,java.util.List.of(),java.util.Map.of(),
                                new GraphRecord.Provenance("offline-fixture",project.source().identityManifestHash(),
                                    project.consistency(),GraphRecord.Fidelity.complete));
                        }
                        public long count(GraphRecord.Kind kind,GraphRecord.Value partition,String label) {
                            return kind==GraphRecord.Kind.vertex ? 1 : 0;
                        }
                    });
                records=result.records();
            }
            System.out.println(Json.text(java.util.Map.of("status","succeeded","inputHash",project.inputHash(),
                "requestsSubmitted",0,"connectionsOpened",0,"driver","3.4.13","fixtureRecords",records)));
        } finally { cluster.close(); }
    }
}
