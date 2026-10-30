package de.uni_marburg.schematch.matching.similarityFlooding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoefficientEdgeTest {

    @Test
    void testToString() {
        CoefficientEdge coefficientEdge = new CoefficientEdge(0.5);
        Assertions.assertEquals("0.5", coefficientEdge.toString());
    }

    @Test
    void getCoefficient() {
        CoefficientEdge coefficientEdge = new CoefficientEdge(0.5);
        Assertions.assertEquals(0.5, coefficientEdge.getCoefficient());
    }

    /**
     * Edge equality is switchable, and this asserts whichever contract the run
     * selected. See harness/SF_EDGE_IDENTITY_REPAIR.md.
     *
     * Historically two edges are equal when they carry the same coefficient. That
     * is what JGraphT consults in containsEdge, and with no matching hashCode
     * the lookup succeeds only on a bucket coincidence -- at which point
     * addEdge refuses an edge whose endpoints are free and the edge is lost.
     * -Dsmx.sf.consistentEdgeIdentity=true restores identity equality, which is
     * what JGraphT expects of an edge object.
     *
     * The switch is a static final read at class load, so one JVM can exercise
     * only one of the two contracts. Identity equality is the default since
     * 2026-09-17; the suite is run both ways, the historical contract under
     * -Dsmx.sf.consistentEdgeIdentity=false.
     */
    @Test
    void testEquals() {
        boolean identityEquality = Boolean.parseBoolean(
                System.getProperty("smx.sf.consistentEdgeIdentity", "true"));

        CoefficientEdge edge1 = new CoefficientEdge(0.5);
        CoefficientEdge edge2 = new CoefficientEdge(0.5);
        if (identityEquality) {
            assertNotEquals(edge1, edge2);
            assertEquals(edge1, edge1);
        } else {
            assertEquals(edge1, edge2);
        }

        assertNotEquals(edge1, new CoefficientEdge(0.25));
        assertNotEquals(edge1, "0.5");
    }

    @Test
    void checkException() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {CoefficientEdge coefficientEdge = new CoefficientEdge(-0.1);});
        Assertions.assertThrows(IllegalArgumentException.class, () -> {CoefficientEdge coefficientEdge = new CoefficientEdge(1.1);});
    }
}