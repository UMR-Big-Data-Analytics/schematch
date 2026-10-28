package de.uni_marburg.schematch.matching.ensemble;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnsembleMatchersTest {
    private static final float EPS = 1e-6f;

    @Test
    void aggregations() {
        float[][] a = {{1.0f, 0.0f}, {0.5f, 0.5f}};
        float[][] b = {{0.0f, 1.0f}, {0.5f, 0.9f}};
        List<float[][]> m = List.of(a, b);
        double[] w = {1, 1};
        assertEquals(0.5f, CompositeMatcher.aggregate(m, w, "AVERAGE")[0][0], EPS);
        assertEquals(1.0f, CompositeMatcher.aggregate(m, w, "MAX")[0][1], EPS);
        assertEquals(0.0f, CompositeMatcher.aggregate(m, w, "MIN")[0][1], EPS);
        assertEquals(0.75f, CompositeMatcher.aggregate(m, new double[]{3, 1}, "WEIGHTED")[0][0], EPS);
        // confidence: (0.5 has weight 0, 0.9 has weight 0.8) -> 0.9
        assertEquals(0.9f, CompositeMatcher.aggregate(m, w, "CONFIDENCE")[1][1], EPS);
        // both undecided -> plain mean
        assertEquals(0.5f, CompositeMatcher.aggregate(m, w, "CONFIDENCE")[1][0], EPS);
    }

    @Test
    void predictors() {
        float[][] diagonal = {{0.9f, 0.1f, 0.1f}, {0.1f, 0.9f, 0.1f}, {0.1f, 0.1f, 0.9f}};
        float[][] flat = {{0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}};
        assertEquals(1.0, CompositeMatcher.predict(diagonal, "DOMINANTS"), 1e-9);
        assertTrue(CompositeMatcher.predict(diagonal, "STDEV") > CompositeMatcher.predict(flat, "STDEV"));
        assertEquals(0.9, CompositeMatcher.predict(diagonal, "MAXMEAN"), 1e-6);
    }

    @Test
    void probabilisticMappings() {
        float[][] s = {{0.9f, 0.85f, 0.1f}, {0.8f, 0.9f, 0.1f}, {0.1f, 0.1f, 0.9f}};
        float[][] p = ProbabilisticMappingMatcher.probabilities(s, 10, 0.02, "EXP");
        for (float[] row : p) {
            double sum = 0;
            for (float v : row) {
                sum += v;
            }
            assertEquals(1.0, sum, 1e-5);                 // every row is assigned in every mapping
        }
        assertTrue(p[0][0] > p[0][1] && p[0][1] > 0);     // the near-tie keeps some mass
        assertTrue(p[2][2] > 0.99f);                       // the unambiguous pair is certain
        float[][] u = ProbabilisticMappingMatcher.probabilities(s, 6, 0.02, "UNIFORM");
        assertEquals(1.0 / 6 * 2, u[0][0], 1e-5);          // all 3! mappings, (0,0) in two of them
    }
}
