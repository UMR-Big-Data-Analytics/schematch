package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.string.JaroWinkler;
import de.uni_marburg.schematch.similarity.string.Levenshtein;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Port of Valentine's JaccardDistanceMatcher ("JaccardLevenshtein", Koutras et al. ICDE 2021):
 * two values match when their string similarity is at least {@code threshold}; A-side values with
 * a match and B-side values with a match are counted separately and reduced by the symmetrised
 * Tversky index of {@link ContainmentMatcher} (alpha = beta = 1 by default, i.e. Jaccard).
 *
 * <p>{@code distanceFunction}: LEVENSHTEIN (Valentine's default), JARO_WINKLER or EXACT.
 * Valentine compares every distinct value in a C++ kernel; here each column is capped at
 * {@code sampleSize} distinct values (deterministic sample, 0 = all) to keep the quadratic value
 * comparison tractable in Java. A length filter skips Levenshtein pairs that cannot reach the
 * threshold; it does not change any score.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FuzzyJaccardMatcher extends TablePairMatcher {
    private String distanceFunction = "LEVENSHTEIN";
    private float threshold = 0.8f;
    private float alpha = 1.0f;
    private float beta = 1.0f;
    private int sampleSize = 1000;
    private int seed = 20260903;

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        List<List<String>> left = new ArrayList<>();
        for (Column c : s.getColumns()) {
            left.add(ColumnValues.sample(ColumnValues.distinct(c, false), sampleSize, seed));
        }
        List<List<String>> right = new ArrayList<>();
        for (Column c : t.getColumns()) {
            right.add(ColumnValues.sample(ColumnValues.distinct(c, false), sampleSize, seed));
        }
        String fn = distanceFunction.toUpperCase(Locale.ROOT);
        SimilarityMeasure<String> measure = switch (fn) {
            case "LEVENSHTEIN" -> new Levenshtein();
            case "JARO_WINKLER" -> new JaroWinkler();
            case "EXACT" -> (a, b) -> a.equals(b) ? 1f : 0f;
            default -> throw new IllegalArgumentException("Unknown distance function: " + distanceFunction);
        };
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                sim[i][j] = (float) similarity(left.get(i), right.get(j), measure, fn.equals("LEVENSHTEIN"));
            }
        }
        return sim;
    }

    double similarity(List<String> a, List<String> b, SimilarityMeasure<String> measure, boolean lengthFilter) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.size() > b.size()) {
            List<String> tmp = a;
            a = b;
            b = tmp;
        }
        Map<String, Integer> exactB = new HashMap<>();
        for (int k = 0; k < b.size(); k++) {
            exactB.put(b.get(k), k);
        }
        boolean[] bMatched = new boolean[b.size()];
        int aMatch = 0;
        for (String x : a) {
            boolean hit = false;
            Integer same = exactB.get(x);
            if (same != null) {
                hit = true;
                bMatched[same] = true;
            }
            for (int k = 0; k < b.size(); k++) {
                if (bMatched[k] && hit) {
                    continue; // both directional facts for this pair are already known
                }
                String y = b.get(k);
                if (lengthFilter) {
                    int longer = Math.max(x.length(), y.length());
                    if (longer > 0 && Math.abs(x.length() - y.length()) > (1.0 - threshold) * longer + 1e-9) {
                        continue;
                    }
                }
                if (measure.compare(x, y) >= threshold) {
                    hit = true;
                    bMatched[k] = true;
                }
            }
            if (hit) {
                aMatch++;
            }
        }
        int bMatch = 0;
        for (boolean m : bMatched) {
            if (m) {
                bMatch++;
            }
        }
        // Valentine's directional Tversky reduction, symmetrised by max
        double aUn = Math.max(a.size() - aMatch, 0);
        double bUn = Math.max(b.size() - bMatch, 0);
        double dab = aMatch + alpha * aUn + beta * bUn;
        double dba = bMatch + alpha * bUn + beta * aUn;
        double tab = dab <= 0 ? 0.0 : aMatch / dab;
        double tba = dba <= 0 ? 0.0 : bMatch / dba;
        return Math.max(tab, tba);
    }
}
