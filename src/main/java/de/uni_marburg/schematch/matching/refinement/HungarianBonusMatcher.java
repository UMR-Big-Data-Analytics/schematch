package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.MatrixRefinementMatcher;
import de.uni_marburg.schematch.matching.ensemble.Assignments;
import de.uni_marburg.schematch.matching.ensemble.CompositeMatcher;

/** X103 unit(base) + (1 + 1e-6) * assignment mask. Returns scores, not a final binary matching. */
public final class HungarianBonusMatcher extends MatrixRefinementMatcher {
    @Override
    public float[][] refine(float[][] scores, Scenario scenario) {
        ScoreMatrices.validate(scores);
        float[][] out = CompositeMatcher.minMax(scores);
        int n = scores.length, m = n == 0 ? 0 : scores[0].length;
        if (n == 0 || m == 0) return out;
        double[][] values = new double[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) values[i][j] = scores[i][j];
        int[] assignment = Assignments.best(values).assignment();
        for (int i = 0; i < n; i++) if (assignment[i] >= 0) out[i][assignment[i]] += 1.000001f;
        return out;
    }

    @Override
    public String toString() {
        return "HungarianBonusMatcher(initialMatrix=" + getInitialMatrix() + ", baseMatcherIndex="
                + getBaseMatcherIndex() + ", base=" + getBase() + ")";
    }
}
