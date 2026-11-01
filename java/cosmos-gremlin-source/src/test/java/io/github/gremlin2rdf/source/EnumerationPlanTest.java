package io.github.gremlin2rdf.source;

import io.github.gremlin2rdf.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnumerationPlanTest {
    @TempDir Path root;
    @Test void missingPlanBlocksBeforeSourceConstructionWithSpecificSafeRemedy() {
        var failure=assertThrows(Diagnostics.Failure.class,()->SourceMain.readEnumerationPlan(root,"enumeration-plan.json"));
        var report=SourceDiagnostics.failure(failure,"source-operation");
        assertEquals("SOURCE_ENUMERATION_PLAN_MISSING",report.get("code"));
        assertEquals("source-enumeration-plan",report.get("stage"));
        assertEquals(report,Diagnostics.failure(Diagnostics.acknowledged(Json.MAPPER.valueToTree(report),"source-operation"),"execution"));
        assertFalse(Files.exists(root.resolve("enumeration-plan.json")));
    }
    @Test void validOwnedGuidedPlanLoadsAndMalformedPlanRemainsUntouched() throws Exception {
        Path file=root.resolve("reviewed-plan.json");
        Files.writeString(file,"{\"schemaVersion\":1,\"partitions\":[{\"type\":\"string\",\"lexical\":\"fixture\"}],\"pageSize\":100,\"maximumRequests\":20,\"maximumDurationMillis\":10000}");
        var plan=SourceMain.readEnumerationPlan(root,"reviewed-plan.json");
        assertEquals(20,plan.maximumRequests());assertEquals(10000,plan.maximumDurationMillis());
        Files.writeString(file,"private-malformed-content");
        var error=assertThrows(Diagnostics.Failure.class,()->SourceMain.readEnumerationPlan(root,"reviewed-plan.json"));
        assertEquals("SOURCE_ENUMERATION_PLAN_INVALID",error.getMessage());
        assertFalse(Json.text(SourceDiagnostics.failure(error,"source-operation")).contains("private-"));
        assertEquals("private-malformed-content",Files.readString(file));
    }
}
