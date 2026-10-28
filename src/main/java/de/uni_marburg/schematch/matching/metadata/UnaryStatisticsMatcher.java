package de.uni_marburg.schematch.matching.metadata;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.ColumnValues;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * Column profile similarity over unary statistics, the "cardinality" channel of the harness
 * (encrypted_feature_ablation.unary_statistics) as a first-line matcher, plus the SemInt-style
 * numeric and length features (Li &amp; Clifton, VLDB 1994).
 *
 * <p>Features: null rate, distinctness (distinct / non-null), normalised entropy
 * (H / log(distinct)), collision probability (sum p^2), dominant mass (max p), numeric fraction,
 * all in [0, 1], and mean value length. The score is the mean over features of
 * {@code 1 - |f_a - f_b|}, with {@code min / max} for the unbounded mean length. Every feature is
 * invariant to a consistent value recoding except numeric fraction and length, which
 * {@code recodingInvariant=true} drops.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UnaryStatisticsMatcher extends TablePairMatcher {
    private Boolean recodingInvariant = false;

    static double[] features(Column c) {
        List<String> all = c.getValues();
        List<String> nonNull = ColumnValues.nonNull(c);
        double[] f = new double[7];
        f[0] = all.isEmpty() ? 1.0 : 1.0 - (double) nonNull.size() / all.size();
        if (nonNull.isEmpty()) {
            return f;
        }
        Map<String, Double> p = ColumnValues.frequencies(nonNull);
        int distinct = p.size();
        double entropy = 0, collision = 0, dominant = 0;
        for (double v : p.values()) {
            entropy -= v * Math.log(v);
            collision += v * v;
            dominant = Math.max(dominant, v);
        }
        f[1] = (double) distinct / nonNull.size();
        f[2] = distinct > 1 ? entropy / Math.log(distinct) : 0.0;
        f[3] = collision;
        f[4] = dominant;
        f[5] = ColumnValues.numericFraction(nonNull);
        double length = 0;
        for (String v : nonNull) {
            length += v.length();
        }
        f[6] = length / nonNull.size();
        return f;
    }

    double similarity(double[] a, double[] b) {
        int bounded = Boolean.TRUE.equals(recodingInvariant) ? 5 : 6;
        double sum = 0;
        for (int k = 0; k < bounded; k++) {
            sum += 1.0 - Math.abs(a[k] - b[k]);
        }
        int count = bounded;
        if (!Boolean.TRUE.equals(recodingInvariant)) {
            double hi = Math.max(a[6], b[6]);
            sum += hi > 0 ? Math.min(a[6], b[6]) / hi : 1.0;
            count++;
        }
        return sum / count;
    }

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        double[][] left = new double[s.getNumColumns()][];
        for (int i = 0; i < left.length; i++) {
            left[i] = features(s.getColumn(i));
        }
        double[][] right = new double[t.getNumColumns()][];
        for (int j = 0; j < right.length; j++) {
            right[j] = features(t.getColumn(j));
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                sim[i][j] = (float) similarity(left[i], right[j]);
            }
        }
        return sim;
    }
}
