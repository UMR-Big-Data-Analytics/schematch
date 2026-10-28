package de.uni_marburg.schematch.similarity.string;

import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Melnik et al.'s Similarity Flooding StringMatch (ICDE 2002), ported from Valentine's
 * {@code prefix_suffix_tokenized} / {@code prefix_suffix_tfidf}
 * (valentine/algorithms/similarity_flooding/string_matcher.py) so that Schematch can seed SF with
 * the matcher the SF paper and Valentine use.
 *
 * <p>Labels are split at camelCase boundaries (case preserved, as in Valentine). Each word is
 * scored against its best partner by common prefix plus common suffix, scaled by the length ratio,
 * and the two directions are combined by a soft Dice coefficient. With IDF weights each word's
 * contribution is weighted by its inverse document frequency.
 */
public class PrefixSuffix implements SimilarityMeasure<String> {
    private final Map<String, Double> idf;

    public PrefixSuffix() {
        this(null);
    }

    /** @param idf lower-cased token to IDF weight, or {@code null} for the unweighted matcher */
    public PrefixSuffix(Map<String, Double> idf) {
        this.idf = idf;
    }

    public static double wordSimilarity(String w1, String w2) {
        if (w1.equals(w2)) {
            return 1.0;
        }
        if (w1.isEmpty() || w2.isEmpty()) {
            return 0.0;
        }
        int prefix = 0;
        int n = Math.min(w1.length(), w2.length());
        while (prefix < n && w1.charAt(prefix) == w2.charAt(prefix)) {
            prefix++;
        }
        String r1 = w1.substring(prefix);
        String r2 = w2.substring(prefix);
        int suffix = 0;
        int m = Math.min(r1.length(), r2.length());
        while (suffix < m && r1.charAt(r1.length() - 1 - suffix) == r2.charAt(r2.length() - 1 - suffix)) {
            suffix++;
        }
        double longer = Math.max(w1.length(), w2.length());
        double base = (prefix + suffix) / longer;
        double ratio = Math.min(w1.length(), w2.length()) / longer;
        return base * ratio;
    }

    private double weight(String token) {
        if (idf == null) {
            return 1.0;
        }
        return idf.getOrDefault(token.toLowerCase(Locale.ROOT), 1.0);
    }

    @Override
    public float compare(String s1, String s2) {
        List<String> a = LabelNormalizer.split(s1);
        List<String> b = LabelNormalizer.split(s2);
        if (a.isEmpty() || b.isEmpty()) {
            return 0f;
        }
        double total = 0.0;
        double denominator = 0.0;
        for (String x : a) {
            double best = 0.0;
            for (String y : b) {
                best = Math.max(best, wordSimilarity(x, y));
            }
            total += best * weight(x);
            denominator += weight(x);
        }
        for (String y : b) {
            double best = 0.0;
            for (String x : a) {
                best = Math.max(best, wordSimilarity(y, x));
            }
            total += best * weight(y);
            denominator += weight(y);
        }
        return denominator > 0 ? (float) (total / denominator) : 0f;
    }
}
