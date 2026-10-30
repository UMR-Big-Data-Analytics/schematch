package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Exact value-set overlap as a symmetrised Tversky index, the reduction Valentine's
 * JaccardDistanceMatcher uses:
 * {@code T(A,B) = |A∩B| / (|A∩B| + alpha |A-B| + beta |B-A|)}, score {@code max(T(A,B), T(B,A))}.
 *
 * <p>alpha = beta = 1 is Jaccard; alpha = 1, beta = 0 (default) is containment
 * {@code max(|A∩B|/|A|, |A∩B|/|B|)}, the inclusion signal of IND-style matching (cf. LSH Ensemble,
 * Zhu et al. PVLDB 2016). {@code normalise} trims and lower-cases values first.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ContainmentMatcher extends TablePairMatcher {
    private float alpha = 1.0f;
    private float beta = 0.0f;
    private Boolean normalise = false;

    public static double tversky(double inter, double sizeA, double sizeB, double alpha, double beta) {
        if (sizeA == 0 || sizeB == 0) {
            return 0.0;
        }
        double ab = inter + alpha * (sizeA - inter) + beta * (sizeB - inter);
        double ba = inter + alpha * (sizeB - inter) + beta * (sizeA - inter);
        double tab = ab <= 0 ? 0.0 : inter / ab;
        double tba = ba <= 0 ? 0.0 : inter / ba;
        return Math.max(tab, tba);
    }

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        boolean norm = Boolean.TRUE.equals(normalise);
        List<Set<String>> left = new ArrayList<>();
        for (Column c : s.getColumns()) {
            left.add(ColumnValues.distinct(c, norm));
        }
        List<Set<String>> right = new ArrayList<>();
        for (Column c : t.getColumns()) {
            right.add(ColumnValues.distinct(c, norm));
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                Set<String> a = left.get(i);
                Set<String> b = right.get(j);
                Set<String> small = a.size() <= b.size() ? a : b;
                Set<String> large = small == a ? b : a;
                int inter = 0;
                for (String v : small) {
                    if (large.contains(v)) {
                        inter++;
                    }
                }
                sim[i][j] = (float) tversky(inter, a.size(), b.size(), alpha, beta);
            }
        }
        return sim;
    }
}
