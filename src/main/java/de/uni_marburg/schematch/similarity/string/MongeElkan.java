package de.uni_marburg.schematch.similarity.string;

import de.uni_marburg.schematch.similarity.SimilarityMeasure;

import java.util.List;

/**
 * Monge-Elkan token similarity (Monge &amp; Elkan, KDD 1996): every token of one label is matched
 * to its best token of the other under an inner string measure, and the best scores are averaged.
 * The original is asymmetric; {@link #symmetric} averages both directions so that the score matrix
 * does not depend on which schema is called the source.
 */
public final class MongeElkan {
    private MongeElkan() {
    }

    public static double directed(List<String> a, List<String> b, SimilarityMeasure<String> inner) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (String x : a) {
            double best = 0.0;
            for (String y : b) {
                best = Math.max(best, inner.compare(x, y));
            }
            sum += best;
        }
        return sum / a.size();
    }

    public static double symmetric(List<String> a, List<String> b, SimilarityMeasure<String> inner) {
        return 0.5 * (directed(a, b, inner) + directed(b, a, inner));
    }
}
