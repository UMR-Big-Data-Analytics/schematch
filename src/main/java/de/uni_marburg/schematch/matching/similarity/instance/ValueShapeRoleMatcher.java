package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;

/** X95 V: max, mean, and share >= 0.5 of each column's within-table character-distribution similarities. */
public final class ValueShapeRoleMatcher extends TablePairMatcher {
    static double[][] features(Table table) {
        float[][] self = new CharDistributionMatcher().match(new TablePair(table, table));
        double[][] out = new double[self.length][3];
        for (int i = 0; i < self.length; i++) {
            if (self.length <= 1) continue;
            for (int j = 0; j < self.length; j++) if (i != j) {
                out[i][0] = Math.max(out[i][0], self[i][j]);
                out[i][1] += self[i][j] / (self.length - 1);
                out[i][2] += self[i][j] >= 0.5 ? 1.0 / (self.length - 1) : 0;
            }
        }
        return out;
    }

    static float[][] compare(double[][] left, double[][] right) {
        float[][] out = new float[left.length][right.length];
        for (int i = 0; i < left.length; i++) for (int j = 0; j < right.length; j++) {
            double distance = 0;
            for (int k = 0; k < left[i].length; k++) distance += Math.abs(left[i][k] - right[j][k]);
            out[i][j] = (float) (1.0 - distance / left[i].length);
        }
        return out;
    }

    @Override
    public float[][] match(TablePair pair) { return compare(features(pair.getSourceTable()), features(pair.getTargetTable())); }
}
