package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Table-context term q+ + beta D + weight * C (rung (d)). For source table a and target table b,
 * T(a, b) = 1/2 (mean over a's columns of the best q+ in b + mean over b's columns of the best q+ in a);
 * C_ij = T(table(i), table(j)) / max T (0 if that is 0). On a single table pair C is 1 wherever max T > 0.
 * weight 0.5 is lambda (\tcLambda), chosen leaving one database out of the independent 3-DB corpus.
 * Reference: sf-matcher-study/reference/score_tablectx.py table_context.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public final class TableContextTermMatcher extends AdditiveTermMatcher {
    private double weight = 0.5;

    @Override
    double[][] term(float[][] scores, Scenario scenario) {
        requireFinite(weight);
        double[][] context = context(scores, scenario.getSourceDatabase().getTables(), scenario.getTargetDatabase().getTables());
        for (double[] row : context) for (int j = 0; j < row.length; j++) row[j] *= weight;
        return context;
    }

    static double[][] context(float[][] scores, List<Table> sources, List<Table> targets) {
        double[][] pair = new double[sources.size()][targets.size()];
        double top = 0;
        for (int a = 0; a < sources.size(); a++) for (int b = 0; b < targets.size(); b++) {
            Table s = sources.get(a), t = targets.get(b);
            double rows = 0, cols = 0;
            for (int i = 0; i < s.getNumColumns(); i++) {
                double best = Double.NEGATIVE_INFINITY;
                for (int j = 0; j < t.getNumColumns(); j++) best = Math.max(best, positive(scores, s, t, i, j));
                rows += best;
            }
            for (int j = 0; j < t.getNumColumns(); j++) {
                double best = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < s.getNumColumns(); i++) best = Math.max(best, positive(scores, s, t, i, j));
                cols += best;
            }
            pair[a][b] = 0.5 * (rows / s.getNumColumns() + cols / t.getNumColumns());
            top = Math.max(top, pair[a][b]);
        }
        double[][] out = new double[scores.length][scores.length == 0 ? 0 : scores[0].length];
        if (!(top > 0)) return out;
        for (int a = 0; a < sources.size(); a++) for (int b = 0; b < targets.size(); b++) {
            Table s = sources.get(a), t = targets.get(b);
            for (int i = 0; i < s.getNumColumns(); i++) for (int j = 0; j < t.getNumColumns(); j++)
                out[s.getOffset() + i][t.getOffset() + j] = pair[a][b] / top;
        }
        return out;
    }

    private static double positive(float[][] scores, Table s, Table t, int i, int j) {
        return Math.max(scores[s.getOffset() + i][t.getOffset() + j], 0.0);
    }
}
