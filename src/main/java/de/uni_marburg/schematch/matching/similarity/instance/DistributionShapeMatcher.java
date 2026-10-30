package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Scale- and shift-invariant distribution fingerprints of numeric columns, in the spirit of
 * ISResMat's distribution-aware fingerprints (Du et al., ICDE 2024): each column is rescaled over
 * <em>its own</em> range ({@code MINMAX}, default) or standardised ({@code ZSCORE}), summarised by
 * {@code quantiles} evenly spaced quantiles, and two columns are compared by
 * {@code 1 - mean |q_a - q_b|} (ZSCORE quantiles are first squashed to [0, 1] by a logistic).
 *
 * <p>So "price in cents" and "price in euros", or Celsius and Fahrenheit, get the same
 * fingerprint, which a raw-value KS / Wasserstein comparison would not give them. Columns that
 * are not at least 90% numeric fall back to their value-length distribution, as in
 * {@link DistributionTestMatcher}'s AUTO domain.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DistributionShapeMatcher extends TablePairMatcher {
    private int quantiles = 20;
    private String normalization = "MINMAX";

    double[] fingerprint(List<String> nonNull) {
        if (nonNull.isEmpty()) {
            return null;
        }
        double[] v = ColumnValues.numericFraction(nonNull) >= 0.9
                ? ColumnValues.numbers(nonNull) : ColumnValues.lengths(nonNull);
        if (v.length == 0) {
            return null;
        }
        Arrays.sort(v);
        String norm = normalization.toUpperCase(Locale.ROOT);
        double lo = v[0];
        double hi = v[v.length - 1];
        double mean = 0;
        double var = 0;
        for (double d : v) {
            mean += d;
        }
        mean /= v.length;
        for (double d : v) {
            var += (d - mean) * (d - mean);
        }
        double sd = Math.sqrt(var / v.length);
        int q = Math.max(2, quantiles);
        double[] f = new double[q];
        for (int k = 0; k < q; k++) {
            double p = (double) k / (q - 1);
            double pos = p * (v.length - 1);
            int i = (int) Math.floor(pos);
            double frac = pos - i;
            double x = i + 1 < v.length ? v[i] * (1 - frac) + v[i + 1] * frac : v[i];
            switch (norm) {
                case "MINMAX" -> f[k] = hi > lo ? (x - lo) / (hi - lo) : 0.5;
                case "ZSCORE" -> f[k] = 1.0 / (1.0 + Math.exp(-(sd > 0 ? (x - mean) / sd : 0.0)));
                default -> throw new IllegalArgumentException("Unknown normalization: " + normalization);
            }
        }
        return f;
    }

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        double[][] left = new double[s.getNumColumns()][];
        for (int i = 0; i < left.length; i++) {
            left[i] = fingerprint(ColumnValues.nonNull(s.getColumn(i)));
        }
        double[][] right = new double[t.getNumColumns()][];
        for (int j = 0; j < right.length; j++) {
            right[j] = fingerprint(ColumnValues.nonNull(t.getColumn(j)));
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                if (left[i] == null || right[j] == null) {
                    continue;
                }
                double d = 0;
                for (int k = 0; k < left[i].length; k++) {
                    d += Math.abs(left[i][k] - right[j][k]);
                }
                sim[i][j] = (float) (1.0 - d / left[i].length);
            }
        }
        return sim;
    }
}
