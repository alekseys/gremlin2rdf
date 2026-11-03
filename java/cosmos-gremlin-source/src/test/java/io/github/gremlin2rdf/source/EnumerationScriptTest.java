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
                request.text().substring(0,request.text().indexOf(after.isEmpty()?".id()":".hasId(")));
            assertEquals(100,request.bindings().get("g2rPageSize"));
            assertTrue(request.text().endsWith(".id().order().limit(g2rPageSize).fold()"));
            assertFalse(request.text().contains("asc"));
            // Syntax-only: Cosmos dialect and ordered paging acceptance remain live-only.
        }
    }
    @Test void pinnedNaturalScalarOrderIsAscendingLexicographicAndKeepsBoundedCursorCoverage() {
            var g=org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph.instance().traversal();
            var ids=java.util.List.of("z","aa","a","10","2");
            var sorted=g.inject(ids.toArray(String[]::new)).order().limit(3).toList();
            assertEquals(java.util.List.of("10","2","a"),sorted);
            var remainder=g.inject(ids.toArray(String[]::new))
                .is(org.apache.tinkerpop.gremlin.process.traversal.P.gt("a")).order().limit(3).toList();
            assertEquals(java.util.List.of("aa","z"),remainder);
            assertEquals(ids.stream().sorted().toList(),java.util.stream.Stream.concat(sorted.stream(),remainder.stream()).toList());
    }
}
