package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.Diagnostics;
import io.github.gremlin2rdf.Json;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SourceClusterTest {
    @Test void oldPinnedDriverConfigurationFailsBeforeAnyRequests() {
        var builder=Cluster.build("127.0.0.1").port(443)
            .maxInProcessPerConnection(1).maxSimultaneousUsagePerConnection(1)
            .minConnectionPoolSize(1).maxConnectionPoolSize(1);
        var failure=assertThrows(IllegalArgumentException.class,builder::create);
        assertEquals("maxSimultaneousUsagePerConnection cannot be less than minSimultaneousUsagePerConnection",failure.getMessage());
        var classified=assertThrows(Diagnostics.Failure.class,()->SourceMain.createCluster(builder));
        var diagnostic=Diagnostics.failure(classified,"source-connectivity");
        assertEquals("INTERNAL_ADAPTER_CONFIGURATION",diagnostic.get("code"));
        assertEquals("source-cluster-construction",diagnostic.get("stage"));
        assertFalse(Json.text(diagnostic).contains(failure.getMessage()));
    }
    @Test void productionBuilderConstructsAndClosesWithoutConnecting() throws Exception {
        var node=Json.MAPPER.readTree("""
            {"source":{"host":"127.0.0.1","port":443,"database":"fixture","container":"graph","customCaRef":null},
             "limits":{"maxRecordBytes":262144}}
            """);
        Cluster cluster=SourceMain.createCluster(SourceMain.clusterBuilder(node,"offline-fixture-key"));
        try {
            assertFalse(cluster.isClosed());
            // Cluster creation is lazy: no connect(), init() or submit() is called.
        } finally { cluster.close(); }
        assertTrue(cluster.isClosed());
    }
}
