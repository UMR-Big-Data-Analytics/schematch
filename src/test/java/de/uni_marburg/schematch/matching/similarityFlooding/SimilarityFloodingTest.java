package de.uni_marburg.schematch.matching.similarityFlooding;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarityFloodingTest {

    @Test
    void testGetAndSetParameters() {
        SimilarityFlooding sf = new SimilarityFlooding();
        Map<String, String> params = new HashMap<>();
        params.put("propCoeffPolicy", "INV_AVG");
        params.put("fixpoint", "A");
        params.put("indFilterThreshold", "0.5");
        params.put("fdFilter", "ngpdep");
        params.put("labelScoreWeight", "0.7");
        params.put("selectThresholdWeight", "0.95");

        sf.setParameters(params);
        Map<String, String> result = sf.getParameters();

        assertEquals("INV_AVG", result.get("propCoeffPolicy"));
        assertEquals("A", result.get("fixpoint"));
        assertEquals("0.5", result.get("indFilterThreshold"));
    }

    @Test
    void testGetPossibleValues() {
        SimilarityFlooding sf = new SimilarityFlooding();
        Map<String, Collection<String>> possibleValues = sf.getPossibleValues();

        assertTrue(possibleValues.containsKey("propCoeffPolicy"));
        assertTrue(possibleValues.get("propCoeffPolicy").contains("INV_AVG"));
        assertTrue(possibleValues.get("propCoeffPolicy").contains("INV_PROD"));

        assertTrue(possibleValues.containsKey("fixpoint"));
        assertTrue(possibleValues.get("fixpoint").contains("A"));
        assertTrue(possibleValues.get("fixpoint").contains("B"));
        assertTrue(possibleValues.get("fixpoint").contains("C"));

        assertTrue(possibleValues.containsKey("indFilterThreshold"));
        assertTrue(possibleValues.get("indFilterThreshold").contains("normalizedValue"));

        assertTrue(possibleValues.containsKey("constraintWeighting"));
        assertTrue(possibleValues.get("constraintWeighting").contains("NONE"));
        assertTrue(possibleValues.get("constraintWeighting").contains("QUALITY"));
    }
}
