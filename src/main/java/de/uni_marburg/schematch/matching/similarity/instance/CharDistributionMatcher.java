package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Sherlock's character-distribution features (Hulsebos et al., KDD 2019, feature group 1), as a
 * matcher: for each of the 96 printable ASCII characters (plus one "other" bucket), the fraction of
 * cells containing it and its mean count per cell. Columns are compared by the cosine of these
 * 194-dimensional vectors.
 *
 * <p>Sherlock feeds these (with word-embedding, paragraph-vector and global statistics features)
 * into a trained network that predicts one of 78 semantic types. The trained model is not
 * installed; this matcher uses the character features directly, which needs no training and no
 * download. The model-based type annotators are available through the Python sidecar's
 * {@code semantic_types} adapter once a model is installed.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CharDistributionMatcher extends TablePairMatcher {
    private static final int CHARS = 97; // ' ' .. '~' plus "other"

    static double[] features(List<String> values) {
        double[] f = new double[2 * CHARS];
        if (values.isEmpty()) {
            return f;
        }
        int[] counts = new int[CHARS];
        for (String v : values) {
            java.util.Arrays.fill(counts, 0);
            for (int k = 0; k < v.length(); k++) {
                char c = v.charAt(k);
                int idx = (c >= 32 && c <= 126) ? c - 32 : CHARS - 1;
                counts[idx]++;
            }
            for (int idx = 0; idx < CHARS; idx++) {
                if (counts[idx] > 0) {
                    f[idx] += 1;                 // cell contains the character
                    f[CHARS + idx] += counts[idx];  // occurrences
                }
            }
        }
        for (int idx = 0; idx < 2 * CHARS; idx++) {
            f[idx] /= values.size();
        }
        return f;
    }

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        double[][] left = new double[s.getNumColumns()][];
        for (int i = 0; i < left.length; i++) {
            left[i] = features(ColumnValues.nonNull(s.getColumn(i)));
        }
        double[][] right = new double[t.getNumColumns()][];
        for (int j = 0; j < right.length; j++) {
            right[j] = features(ColumnValues.nonNull(t.getColumn(j)));
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                double dot = 0, na = 0, nb = 0;
                for (int k = 0; k < left[i].length; k++) {
                    dot += left[i][k] * right[j][k];
                    na += left[i][k] * left[i][k];
                    nb += right[j][k] * right[j][k];
                }
                sim[i][j] = na > 0 && nb > 0 ? (float) (dot / Math.sqrt(na * nb)) : 0f;
            }
        }
        return sim;
    }
}
