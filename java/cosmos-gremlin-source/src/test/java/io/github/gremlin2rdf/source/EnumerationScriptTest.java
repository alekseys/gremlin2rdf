package io.github.gremlin2rdf.source;

import org.codehaus.groovy.control.SourceUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnumerationScriptTest {
    @Test void productionKeysetTemplatesParseWithPinnedGroovyWithoutRequestsOrIteration() {
        for (String owner:new String[]{null,"fixture-owner"}) for (String after:new String[]{"","fixture-after"}) {
            var request=SourceMain.enumerationPage("pk","fixture-partition",owner,after,100);
            var unit=SourceUnit.create("fixture",request.text());
            unit.parse();unit.completePhase();unit.nextPhase();unit.convert();
            assertNotNull(unit.getAST());
            assertFalse(request.text().contains("fixture-partition"));
            assertEquals(owner==null?"g.V().has(g2rPartitionProperty,g2rPartition)":"g.V([g2rPartition,g2rOwner]).outE()",
                request.text().substring(0,request.text().indexOf(after.isEmpty()?".order()":".hasId(")));
            assertEquals(100,request.bindings().get("g2rPageSize"));
            // Syntax-only: Cosmos dialect and ordered paging acceptance remain live-only.
        }
    }
}
