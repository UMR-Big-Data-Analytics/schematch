package de.uni_marburg.schematch.matching.similarityFlooding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LabelEdgeTest {

    @Test
    void testToString() {
        LabelEdge labelEdge = new LabelEdge("type");
        Assertions.assertEquals("type", labelEdge.toString());
    }

    /**
     * Edge equality is switchable, and this asserts whichever contract the run
     * selected. See harness/SF_EDGE_IDENTITY_REPAIR.md.
     *
     * Historically two edges are equal when they carry the same label. That
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

        LabelEdge labelEdge1 = new LabelEdge("type");
        LabelEdge labelEdge2 = new LabelEdge("type");
        if (identityEquality) {
            assertNotEquals(labelEdge1, labelEdge2);
            assertEquals(labelEdge1, labelEdge1);
        } else {
            assertEquals(labelEdge1, labelEdge2);
        }

        LabelEdge labelEdge3 = new LabelEdge("name");
        assertNotEquals(labelEdge1, labelEdge3);

        String labelEdge4 = "type";
        assertNotEquals(labelEdge1, labelEdge4);
    }

    @Test
    void getValue() {
        LabelEdge labelEdge = new LabelEdge("type");
        Assertions.assertEquals("type", labelEdge.getLabel());
    }

    @Test
    void getWeight() {
        LabelEdge defaultWeightedEdge = new LabelEdge("type");
        Assertions.assertEquals(1.0, defaultWeightedEdge.getWeight());

        LabelEdge weightedEdge = new LabelEdge("type", 0.42);
        Assertions.assertEquals(0.42, weightedEdge.getWeight());
    }

    @Test
    void rejectsInvalidWeight() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new LabelEdge("type", -0.1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new LabelEdge("type", 1.1));
    }
}
