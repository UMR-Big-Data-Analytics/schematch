package de.uni_marburg.schematch.matching.metadata;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.ColumnValues;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashSet;
import java.util.List;

/**
 * SemInt-style attribute signatures (Li &amp; Clifton, VLDB 1994 / DKE 2000): each column is a
 * vector of schema and content statistics, the vectors are standardised over all columns of the
 * pair, and columns are compared by {@code exp(-d / sqrt(dim))} of their Euclidean distance.
 *
 * <p>Features (16): numeric fraction, integer fraction, null rate, distinctness, length mean, std,
 * min and max (log1p), numeric mean, std, min and max (signed log1p), coefficient of variation
 * (clipped), and the fractions of letters, digits and punctuation among all characters.
 *
 * <p>SemInt clusters the source attributes and trains a back-propagation network to classify
 * target attributes into those clusters. With one signature per column, that classifier
 * degenerates to a nearest-neighbour rule in the standardised space, which is what this computes;
 * there is no network and nothing is trained.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SemIntMatcher extends TablePairMatcher {
    private static final int DIM = 16;

    static double slog(double x) {
        return Math.signum(x) * Math.log1p(Math.abs(x));
    }

    static double[] signature(Column c) {
        List<String> all = c.getValues();
        List<String> nn = ColumnValues.nonNull(c);
        double[] f = new double[DIM];
        if (nn.isEmpty()) {
            f[2] = 1.0;
            return f;
        }
        double[] nums = ColumnValues.numbers(nn);
        int integers = 0;
        for (String v : nn) {
            if (v.trim().matches("[+-]?\\d+")) {
                integers++;
            }
        }
        f[0] = (double) nums.length / nn.size();
        f[1] = (double) integers / nn.size();
        f[2] = 1.0 - (double) nn.size() / all.size();
        f[3] = (double) new HashSet<>(nn).size() / nn.size();
        double[] len = ColumnValues.lengths(nn);
        double[] ls = stats(len);
        f[4] = Math.log1p(ls[0]);
        f[5] = Math.log1p(ls[1]);
        f[6] = Math.log1p(ls[2]);
        f[7] = Math.log1p(ls[3]);
        if (nums.length > 0) {
            double[] ns = stats(nums);
            f[8] = slog(ns[0]);
            f[9] = Math.log1p(ns[1]);
            f[10] = slog(ns[2]);
            f[11] = slog(ns[3]);
            f[12] = ns[0] != 0 ? Math.min(10.0, ns[1] / Math.abs(ns[0])) : 0.0;
        }
        double letters = 0, digits = 0, punct = 0, chars = 0;
        for (String v : nn) {
            for (int k = 0; k < v.length(); k++) {
                char ch = v.charAt(k);
                chars++;
                if (Character.isLetter(ch)) {
                    letters++;
                } else if (Character.isDigit(ch)) {
                    digits++;
                } else if (!Character.isWhitespace(ch)) {
                    punct++;
                }
            }
        }
        if (chars > 0) {
            f[13] = letters / chars;
            f[14] = digits / chars;
            f[15] = punct / chars;
        }
        return f;
    }

    /** mean, std, min, max. */
    static double[] stats(double[] v) {
        double mean = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double d : v) {
            mean += d;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        mean /= v.length;
        double var = 0;
        for (double d : v) {
            var += (d - mean) * (d - mean);
        }
        return new double[]{mean, Math.sqrt(var / v.length), min, max};
    }

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        int n = s.getNumColumns();
        int m = t.getNumColumns();
        double[][] v = new double[n + m][];
        for (int i = 0; i < n; i++) {
            v[i] = signature(s.getColumn(i));
        }
        for (int j = 0; j < m; j++) {
            v[n + j] = signature(t.getColumn(j));
        }
        // standardise each feature over all columns of the pair
        for (int k = 0; k < DIM; k++) {
            double mean = 0;
            for (double[] row : v) {
                mean += row[k];
            }
            mean /= v.length;
            double var = 0;
            for (double[] row : v) {
                var += (row[k] - mean) * (row[k] - mean);
            }
            double sd = Math.sqrt(var / v.length);
            for (double[] row : v) {
                row[k] = sd > 0 ? (row[k] - mean) / sd : 0.0;
            }
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double d = 0;
                for (int k = 0; k < DIM; k++) {
                    double x = v[i][k] - v[n + j][k];
                    d += x * x;
                }
                sim[i][j] = (float) Math.exp(-Math.sqrt(d) / Math.sqrt(DIM));
            }
        }
        return sim;
    }
}
