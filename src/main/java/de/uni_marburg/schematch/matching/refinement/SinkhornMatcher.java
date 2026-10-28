package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.MatrixRefinementMatcher;
import de.uni_marburg.schematch.matching.ensemble.CompositeMatcher;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** X103 soft selection: exp((unit(base)-1)/tau), then alternating row/column normalization. */
@Data
@EqualsAndHashCode(callSuper = true)
public final class SinkhornMatcher extends MatrixRefinementMatcher {
    private double tau = 0.1;
    private int iterations = 20;

    @Override
    public float[][] refine(float[][] scores, Scenario scenario) {
        if (!(tau > 0) || !Double.isFinite(tau) || iterations < 1) throw new IllegalArgumentException("Invalid Sinkhorn parameters");
        ScoreMatrices.validate(scores);
        float[][] unit = CompositeMatcher.minMax(scores);
        int n = scores.length, m = n == 0 ? 0 : scores[0].length;
        double[][] p = new double[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) p[i][j] = Math.exp((unit[i][j] - 1.0) / tau);
        for (int k = 0; k < iterations; k++) {
            for (int i = 0; i < n; i++) {
                double sum = 0;
                for (int j = 0; j < m; j++) sum += p[i][j];
                for (int j = 0; j < m; j++) p[i][j] /= Math.max(sum, 1e-300);
            }
            for (int j = 0; j < m; j++) {
                double sum = 0;
                for (int i = 0; i < n; i++) sum += p[i][j];
                for (int i = 0; i < n; i++) p[i][j] /= Math.max(sum, 1e-300);
            }
        }
        float[][] out = new float[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) out[i][j] = (float) p[i][j];
        return out;
    }
}
