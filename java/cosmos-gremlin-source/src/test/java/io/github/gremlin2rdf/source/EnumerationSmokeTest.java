package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnumerationSmokeTest {
    @TempDir Path root;
    private Project project() {
        var source = new Project.Source("fixture", "localhost", 443, "db", "container", "/pk", null,
            "verified-identity-manifest", "identities.ndjson", "0".repeat(64), null, null, null, true);
        return new Project(1, "cosmos-gremlin-to-jena", "0".repeat(40), "enumeration-smoke", 1, "0.1.0",
            Mapping.standard(), source, null, new Project.Limits(10, 1048576, 262144, 1000, 0, 10, 1, 0),
            "immutable", Map.of(), List.of(), Map.of(), List.of());
    }
    private Enumeration.Reader reader(boolean unordered) {
        return new Enumeration.Reader() {
            public List<String> vertices(GraphRecord.Value partition, String after, int limit) {
                return unordered ? List.of("v2", "v1") : List.of("v1", "v2").stream()
                    .filter(id -> id.compareTo(after) > 0).limit(limit).toList();
            }
            public List<String> edges(GraphRecord.Value partition, String owner, String after, int limit) {
                return owner.equals("v1") && after.isEmpty() ? List.of("e1") : List.of();
            }
            public long count(GraphRecord.Kind kind, GraphRecord.Value partition) {
                return kind == GraphRecord.Kind.vertex ? 2 : 1;
            }
        };
    }
    @Test void discoversBoundedOwnedIdentitiesAndReopensConfirmedOutput() throws Exception {
        var plan = new Enumeration.Plan(1, List.of(GraphRecord.Value.string("p")), 1, 30, 10000);
        Path output = root.resolve("enumeration");
        var result = Enumeration.run(project(), plan, output, reader(false));
        assertEquals("succeeded", result.get("status"));
        assertEquals(2L, result.get("vertices")); assertEquals(1L, result.get("edges"));
        var lines = Files.readAllLines(output.resolve("identities.ndjson"));
        assertEquals(3, lines.size());
        var edge = lines.stream().map(line -> {
            try { return Json.MAPPER.readValue(line, IdentityEntry.class); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }).filter(entry -> entry.kind() == GraphRecord.Kind.edge).findFirst().orElseThrow();
        assertEquals(GraphRecord.EdgeScope.sourceVertex, edge.edgeScope());
        assertEquals(GraphRecord.Value.string("v1"), edge.ownerId());
        var repeated = Enumeration.run(project(), plan, output, reader(false));
        assertEquals(result.get("sha256"), repeated.get("sha256"));
    }
    @Test void rejectsUnsupportedOrderingAndExhaustedBudgetWithoutFinalManifest() {
        var partition = List.of(GraphRecord.Value.string("p"));
        assertThrows(IOException.class, () -> Enumeration.run(project(),
            new Enumeration.Plan(1, partition, 2, 30, 10000), root.resolve("unordered"), reader(true)));
        assertThrows(IOException.class, () -> Enumeration.run(project(),
            new Enumeration.Plan(1, partition, 1, 1, 10000), root.resolve("budget"), reader(false)));
        assertFalse(Files.exists(root.resolve("budget").resolve("manifest.json")));
        assertTrue(Files.exists(root.resolve("budget").resolve("checkpoint.json")));
    }
    @Test void incompleteCandidateCoverageReturnsSpecificBlockerWithoutInventingManifest() {
        var partial = new Enumeration.Reader() {
            public List<String> vertices(GraphRecord.Value partition,String after,int limit) throws Exception{return reader(false).vertices(partition,after,limit);}
            public List<String> edges(GraphRecord.Value partition,String owner,String after,int limit) throws Exception{return reader(false).edges(partition,owner,after,limit);}
            public long count(GraphRecord.Kind kind,GraphRecord.Value partition) throws Exception{
                return partition==null && kind==GraphRecord.Kind.vertex ? 3:reader(false).count(kind,partition);
            }
            @Test void basicCheckDoesNotRequireInventoryAndReturnsCredentialFailureOnStdout() throws Exception {
                var original=project();
                var source=new Project.Source("fixture","fixture.invalid",443,"db","graph","/pk",
                    "env:GRAPH_PREPARE_ABSENT_"+UUID.randomUUID().toString().replace("-",""),
                    "verified-identity-manifest","missing.ndjson","0".repeat(64),null,null,null,false);
                var p=new Project(original.schemaVersion(),original.migrationKind(),original.baselineRepositorySha(),original.runId(),
                    original.protocolVersion(),original.workerVersion(),original.mapping(),source,original.target(),original.limits(),
                    original.consistency(),original.phases(),original.approvals(),original.applicationHashes(),original.artifacts());
                Path config=root.resolve("project.json");Files.writeString(config,Json.text(p));
                var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe":"java").toString(),
                    "-cp",System.getProperty("java.class.path"),SourceMain.class.getName(),"check",config.toString(),"","").start();
                try{
                    assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals(2,child.exitValue());
                    var response=Json.MAPPER.readTree(child.getInputStream().readNBytes(8192));
                    assertEquals("CREDENTIAL_REFERENCE_UNAVAILABLE",response.path("code").asText());
                    assertEquals("source-connectivity",response.path("stage").asText());
                    assertFalse(response.toString().contains(source.authenticationRef()));
                }finally{if(child.isAlive())child.destroyForcibly();}
            }
        };
        var error=assertThrows(Diagnostics.Failure.class,()->Enumeration.run(project(),
            new Enumeration.Plan(1,List.of(GraphRecord.Value.string("p")),1,30,10000),root.resolve("coverage"),partial));
        assertEquals("SOURCE_PARTITION_COVERAGE",Diagnostics.failure(error,"source-enumeration").get("code"));
        assertFalse(Files.exists(root.resolve("coverage/manifest.json")));
    }
}
